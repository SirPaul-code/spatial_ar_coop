# Research: two-phone shared AR without a prebuilt map

Date: 2026-09-06

## Executive result

For two ordinary Android phones that have never seen the site before, the best commodity-hardware architecture is **runtime registration of two independent metric ARCore worlds**, not another Cloud Anchor system.

```text
World_A -- T_WB_WA --> World_B
P_WB = T_WB_WA * P_WA
```

The strongest path is:

```text
A: ARCore Depth / PointCloud -> metric 3D support in World_A
A+B: current camera frames -> cross-view feature matches
server: 3D(World_A) <-> 2D(B) correspondences -> PnP RANSAC
B: current ARCore camera pose -> compose full T_WB_WA
```

This is stronger than using only an essential matrix plus radio ranging. A pure essential matrix yields relative rotation and translation direction but not translation scale. If A contributes 3D points already expressed in meters, PnP recovers a metric 6-DoF transform directly. Wi-Fi RTT, Bluetooth Channel Sounding and UWB then become fallback/validation/fusion constraints instead of the sole source of scale.

A hard limit remains: with no shared visual geometry, no previous alignment, no common map/landmark and only one scalar phone-to-phone distance, full `SE(3)` registration is not observable. That is geometry, not an implementation gap.

## 1. Pixel -> camera ray -> world ray

For an undistorted image pixel:

```text
p = [u,v,1]^T
K = [[fx,0,cx],[0,fy,cy],[0,0,1]]
r_cv = K^-1 p = [(u-cx)/fx,(v-cy)/fy,1]^T
```

ARCore/OpenGL camera axes are `+x right, +y up, -z forward`; OpenCV camera axes are `+x right, +y down, +z forward`. Therefore:

```text
S = diag(1,-1,-1)
r_ar = S r_cv
```

With ARCore physical camera pose `T_WA_CA=[R,t]`:

```text
o_WA = t
d_WA = R normalize(r_ar)
P_WA(lambda) = o_WA + lambda d_WA
```

The clicked pixel therefore defines a ray. Depth `lambda` is the missing observable.

Sources:
- https://developers.google.com/ar/reference/java/com/google/ar/core/Camera
- https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics
- https://developers.google.com/ar/reference/java/com/google/ar/core/Coordinates2d

## 2. Metric target depth on A

### ARCore Depth

ARCore Depth values are metric z-depth in millimeters, not Euclidean ray length. For z-depth `d`:

```text
P_CV = [(u-cx)d/fx, (v-cy)d/fy, d]
P_CA = S P_CV
P_WA = T_WA_CA P_CA
```

Depth image coordinates are not assumed identical to CPU image coordinates. The app maps between them with `Frame.transformCoordinates2d(IMAGE_PIXELS <-> TEXTURE_NORMALIZED)`.

The POC samples roughly 1-2k valid depth points and sends:

```text
[u_A, v_A, X_WA, Y_WA, Z_WA]
```

Sources:
- https://developers.google.com/ar/develop/java/depth/developer-guide
- https://developers.google.com/ar/develop/depth
- https://developers.google.com/ar/reference/java/com/google/ar/core/Frame

ARCore documentation describes depth as best in roughly near-to-medium phone ranges and improved by motion, so it must be treated as a noisy measurement, not a laser rangefinder.

### Point-cloud fallback

ARCore `PointCloud` contains metric world-space tracked points with confidence. The POC projects those points back into A's current camera, producing sparse 3D-to-2D support compatible with the same PnP pipeline.

Source: https://developers.google.com/ar/reference/java/com/google/ar/core/PointCloud

### Tap priority

1. `DepthPoint` hit.
2. Plane hit inside polygon.
3. Feature point with estimated surface normal.
4. Direct depth-image lookup at the clicked CPU pixel.
5. Temporal triangulation after A moves.
6. Neural metric depth as a prior.
7. Otherwise keep a ray with unknown depth instead of inventing a point.

A local AR anchor only stabilizes a pose inside one phone's ARCore world. It does not create a cross-device frame and does not solve unknown target depth.

## 3. Primary cross-device solution: metric PnP

A sends its frame, intrinsics, `T_WA_CA`, metric support and target `P_WA`. B sends its frame, intrinsics and `T_WB_CB`.

The baseline matcher is SIFT because it is deterministic and easy to validate. Production upgrades should test SuperPoint/ALIKED/DISK + LightGlue and LoFTR for difficult viewpoint or texture cases.

Sources:
- https://github.com/cvg/LightGlue
- https://arxiv.org/abs/1712.07629
- https://openaccess.thecvf.com/content/CVPR2021/html/Sun_LoFTR_Detector-Free_Local_Feature_Matching_With_Transformers_CVPR_2021_paper.html
- https://github.com/cvg/Hierarchical-Localization

For every A<->B feature match, associate the A keypoint with the nearest metric support point in image space. This yields:

```text
P_WAi <-> p_Bi
```

Run `solvePnPRansac` to estimate `T_CVB_WA`. Convert OpenCV output axes to ARCore B-camera axes with:

```text
S4 = diag(1,-1,-1,1)
T_CB_WA = S4 T_CVB_WA
```

Then:

```text
T_WB_WA = T_WB_CB S4 T_CVB_WA
P_WB = T_WB_WA P_WA
```

Because `P_WAi` are already metric, PnP has metric scale. This is the key architectural result.

OpenCV reference: https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html

The POC reports method, correspondence count, RANSAC inliers, median reprojection error and confidence instead of a binary success flag.

## 4. Essential-matrix + ranging fallback

If A has insufficient metric support, calibrated matches satisfy:

```text
x_B^T E x_A = 0
E = [t]_x R
```

`recoverPose` gives `R` and translation direction `t_hat`. Translation magnitude remains unknown. A fresh phone-to-phone range `r` can provide an approximate scale:

```text
t = r normalize(t_hat)
```

This path is weaker because RF measures antenna-to-antenna distance, not optical-center baseline. For decimeter work, phone-model-specific antenna-to-camera lever arms and timing become important.

Source: https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html

## 5. Direct Android phone-to-phone Wi-Fi RTT

Android Wi-Fi RTT can range a Wi-Fi Aware peer directly by `PeerHandle`; an access point is not required for that path.

POC flow:

```text
A: WifiAware publish + setRangingEnabled(true)
B: WifiAware subscribe -> PeerHandle
B: RangingRequest.Builder.addWifiAwarePeer(peer)
B: WifiRttManager.startRanging(...)
```

The app detects `FEATURE_WIFI_AWARE` and `FEATURE_WIFI_RTT` at runtime and continues on vision-only geometry when unavailable.

Sources:
- https://developer.android.com/develop/connectivity/wifi/wifi-rtt
- https://developer.android.com/develop/connectivity/wifi/wifi-aware
- https://developer.android.com/reference/android/net/wifi/rtt/RangingRequest.Builder
- https://developer.android.com/reference/android/net/wifi/aware/PublishConfig.Builder

RTT is one scalar constraint. It cannot by itself determine the orientation or 3D direction between phones.

## 6. Bluetooth

BLE RSSI is suitable only as a weak prior/topology clue because path loss, human-body blockage, antenna orientation and multipath dominate distance inference.

Bluetooth Channel Sounding in Bluetooth Core 6 combines phase-based techniques and round-trip timing to target centimeter/decimeter-class ranging under suitable conditions. Bluetooth SIG material describes early implementations around tens of centimeters rather than millimeter certainty.

Android 16 introduced a unified ranging framework covering technology-specific capabilities including Bluetooth Channel Sounding, BLE RSSI, Wi-Fi NAN RTT and UWB. Android 16 does not imply that every handset contains Channel Sounding-capable hardware; capabilities must be queried at runtime.

Sources:
- https://www.bluetooth.com/learn-about-bluetooth/feature-enhancements/channel-sounding/
- https://developer.android.com/reference/android/ranging/RangingManager
- https://developer.android.com/reference/android/ranging/RangingCapabilities
- https://developer.android.com/reference/android/ranging/ble/cs/BleCsRangingCapabilities

## 7. UWB

UWB is the strongest commodity RF option where both phones expose it. Android Core UWB/platform ranging supports peer sessions on capable devices. Depending on hardware, distance and angular constraints may be available.

Apple Nearby Interaction explicitly supports Apple-device peers through `NINearbyPeerConfiguration`, including iPhone-to-iPhone UWB. This is separate from Bluetooth Channel Sounding; support for one must not be inferred from the other.

Sources:
- https://developer.android.com/jetpack/androidx/releases/core-uwb
- https://developer.android.com/develop/connectivity/ranging
- https://developer.apple.com/documentation/nearbyinteraction
- https://developer.apple.com/documentation/nearbyinteraction/ninearbypeerconfiguration

## 8. GNSS + heading + IMU

GNSS/heading can provide a coarse outdoor global fallback, but ordinary phones cannot provide a universal 10-50 cm AR overlay from these sensors alone.

GPS.gov states smartphones are typically meter-class in open sky and degrade around buildings/trees. Android `Location.getAccuracy()` should be used per fix.

Sources:
- https://www.gps.gov/gps-accuracy-0
- https://developer.android.com/reference/android/location/Location#getAccuracy()

Heading error alone causes approximately:

```text
e_lateral ~= R tan(sigma_heading)
```

For 5 degrees:

| range | lateral error |
|---:|---:|
| 5 m | 0.44 m |
| 20 m | 1.75 m |
| 50 m | 4.37 m |
| 100 m | 8.75 m |

So GNSS+heading is useful for rough outdoor situational awareness, not precise window/person pinning.

## 9. Triangulation and baseline physics

For simplified rectified stereo:

```text
Z = f B / d
sigma_Z ~= Z^2/(f B) sigma_d
```

With `f=1000 px`, `sigma_d=1 px`:

| baseline | 5 m | 20 m | 50 m | 100 m |
|---:|---:|---:|---:|---:|
| 0.2 m | 0.125 m | 2.0 m | 12.5 m | 50 m |
| 0.5 m | 0.05 m | 0.8 m | 5.0 m | 20 m |
| 1.0 m | 0.025 m | 0.4 m | 2.5 m | 10 m |
| 2.0 m | 0.013 m | 0.2 m | 1.25 m | 5 m |

These values isolate disparity geometry and do not include pose/baseline/calibration errors. They demonstrate the fundamental `Z^2` behavior. A blanket 10-50 cm promise from 5 to 100 m is physically wrong.

A can also create a baseline by moving 0.5-2 m after the tap, using ARCore metric camera poses and repeated observations to triangulate/refine a static target.

## 10. No common view

If A and B no longer share visual geometry but have a previously good `T_WB_WA`, propagate it for a limited time using both phones' local VIO motions and increasing covariance.

If they have never shared a view/reference and only have range, exact 6-DoF alignment is impossible. Options are:
- GNSS/heading for coarse registration,
- UWB angular information where exposed,
- a third device bridging both through pairwise visual edges,
- brief common-marker/common-scene initialization,
- or a persistent map/Cloud Anchor, which violates the no-map premise.

The correct state in the unobservable case is `not fully localized`, not a fabricated precise marker.

## 11. Sensor fusion / factor graph

A production system should use a sliding-window factor graph rather than blindly averaging sensors.

Useful state:

```text
X_k = T_WB_WA(k)
P_j = target positions
V_j = dynamic target velocities
optional clock offsets / sensor biases
```

Factors:

```text
visual reprojection
ARCore/VIO inter-frame motion
metric depth
Wi-Fi RTT / Bluetooth CS / UWB range
UWB angle where present
GNSS ENU prior
heading/gravity prior
```

Every factor needs covariance and a robust loss. Useful server libraries: GTSAM, Ceres, g2o.

## 12. Dynamic objects

For a moving person/car, transfer a track state instead of a static world point:

```text
id, class, position, velocity, timestamp, covariance, source
```

B predicts to render time. Cross-device frame registration should be derived mainly from static background geometry; moving target pixels violate the static-scene epipolar model.

## 13. Mesh / 5-20 devices

Each phone keeps an independent local metric VIO world. Every successful pairwise registration becomes a pose-graph edge:

```text
node i = World_i
edge i->j = T_Wj_Wi + covariance
```

A third phone can bridge A and B even when A and B have no current common view. The graph is transient shared spatial state, not a prebuilt environmental map.

## 14. Three architectures

### Variant A - minimum, implemented here

```text
two ARCore Android phones
ARCore VIO + Depth/PointCloud
SIFT matching
metric 3D-to-2D PnP RANSAC
Wi-Fi Aware RTT fallback
WebSocket/FastAPI server
```

### Variant B - maximum commodity precision

```text
ARCore VIO
raw/dense depth + confidence
SuperPoint/ALIKED + LightGlue; LoFTR fallback
multi-keyframe PnP + bundle adjustment
short temporal triangulation
Wi-Fi RTT + Bluetooth CS + UWB when available
per-model antenna/camera calibration
sliding-window factor graph
better time synchronization
```

10-50 cm is plausible only under constrained geometry: sufficient static visual overlap, useful baseline, favorable target distance, recent metric observations and calibration. It is not a universal specification.

### Variant C - dynamic mesh

Each device contributes local VIO, keyframes/features, relative visual edges, optional RF ranges and object tracks. A server or elected peer optimizes a robust dynamic pose graph.

## 15. Fundamental vs engineering

Fundamental:
- one pixel without depth is a ray;
- essential-matrix translation has scale ambiguity;
- one range cannot determine 6-DoF pose;
- triangulated depth error rises roughly with distance squared;
- moving objects are not static scene points;
- no shared reference means no exact fresh common frame.

Engineering:
- feature matcher choice;
- timestamp alignment;
- efficient descriptor transport;
- depth filtering;
- PnP thresholds;
- RF capability discovery;
- antenna/camera calibration;
- factor graph optimization;
- mesh transport;
- UI confidence and diagnostics.

## 16. First field experiments

1. Phones 0.5 m apart, target 2 m.
2. Phones 1 m apart, target 5 m.
3. Viewpoint differences 30/60/90 degrees.
4. Depth vs forced point-cloud fallback.
5. SIFT vs LightGlue.
6. Static vs walking phones to expose timing error.
7. RTT enabled/disabled; compare RF range with visual baseline norm.
8. Outdoor target 10/20/50 m with 0.5/1/2 m baselines.
9. Establish visual alignment, then lose overlap and measure propagated drift.
10. Add a third phone and validate a three-node pose graph.

For every estimate log timestamps, tracking state, matches, PnP correspondences/inliers, median reprojection error, `T_WB_WA`, RF range/stddev/count, depth source/range and tape-measured target error.
