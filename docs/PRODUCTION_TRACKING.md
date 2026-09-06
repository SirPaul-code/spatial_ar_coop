# Spatial Sync — Production Tracking Architecture

This document describes the current direct phone-to-phone AR tracking stack on branch `fresh/no-map-runtime-poc`, what changed from the earlier V2 implementation, what the app is expected to do now, and how the pieces fit together.

The system is intentionally designed as a local premium-product architecture rather than a cloud-backed demo:

- no router or access point,
- no external server,
- no Cloud Anchors,
- no pre-scanned shared map,
- no fixed world origin stored in advance,
- no RF-only pose estimation,
- no fake `READY` state from one weak frame.

The two phones build a shared spatial relationship at runtime and then maintain that relationship while the user moves.

---

## 1. Product behavior

The intended user experience is:

1. Open the same application on both phones.
2. Enter a username.
3. One phone selects **CREATE**.
4. The other selects **JOIN NEARBY** and chooses the nearby room.
5. The phones establish a direct encrypted Wi-Fi Aware data path.
6. Both users point the cameras at some overlapping static scene detail and move the phones naturally for a short time.
7. The HUD progresses through acquisition/alignment states until the shared transform is verified.
8. Once both phones show **LOCKED/READY**, either user can tap a real physical point.
9. The tapped point is stored as a local ARCore Anchor, sent to the peer, transformed into the peer's ARCore world, and stored there as another local Anchor.
10. The receiving phone shows the POI in AR. If it is outside the view or behind the camera, an edge guidance arrow indicates where to turn.
11. The shared transform is refined opportunistically in the background when good visual overlap reappears.
12. Temporary loss of overlap does not immediately destroy the last verified lock.

The product should prefer **refusing a weak lock** over displaying a confidently wrong POI.

---

## 2. High-level architecture

```text
Phone A                                                   Phone B
────────────────────────────────────────────────────────────────────────────
ARCore VIO + Depth                                        ARCore VIO + Depth
physical camera config                                    physical camera config
local AR world WA                                         local AR world WB
       │                                                        │
       ├──────────── Wi-Fi Aware discovery / NDP ────────────────┤
       │              direct encrypted local link                │
       │                                                        │
       ├──────── camera frame + intrinsics + metric 3D ──────────►
       ◄──────── camera frame + intrinsics + metric 3D ──────────┤
       │                                                        │
       │        visual feature matching + metric PnP             │
       │                                                        │
       │                  estimate T_WB_WA                       │
       │                                                        │
       ├──────── transform / verification / POI / range ─────────►
       ◄──────── transform / verification / POI / range ─────────┤
       │                                                        │
ARCore Anchor A                                            ARCore Anchor B
       │                                                        │
       └──────────── same physical real-world point ─────────────┘
```

The core spatial relationship is a rigid SE(3) transform:

```text
T_WB_WA
```

which converts a point from Phone A's ARCore world into Phone B's ARCore world:

```text
P_WB = T_WB_WA * P_WA
```

The inverse relationship is used in the opposite direction.

---

## 3. Direct peer-to-peer transport

### Technology

The primary transport is **Wi-Fi Aware / NAN** with a Wi-Fi Aware Network Data Path (NDP). No access point participates.

### Current production-oriented behavior

`WifiAwarePeerTransport` implements:

- local publish/subscribe discovery,
- username + room identity in discovery metadata,
- Android 13+ Instant Communication mode when supported,
- Android 12+ any-peer responder path,
- peer-specific responder fallback for vendor stacks,
- direct IPv6 TCP socket bound to the Aware network,
- encrypted NDP using the room PSK,
- reconnect/error handling,
- generation/epoch checks so stale network callbacks cannot tear down a newer connection,
- TCP frame back-pressure: only the newest unsent camera frame is retained,
- direct Wi-Fi RTT after the data path is established.

The transport is deliberately separated from alignment. RF provides connectivity and optional range sanity; it does not define the shared 6-DoF pose.

---

## 4. ARCore camera policy

A major production change is that the app no longer blindly accepts whichever ARCore camera configuration happens to be the default.

`ArCameraCatalog` enumerates ARCore-supported rear tracking configurations and ranks them primarily by:

1. stereo camera usage,
2. 60 fps support,
3. hardware depth support,
4. then image resolution.

The best tracking configuration for the physical default AR camera is explicitly applied when the session starts.

This matters because the tracking camera is part of the SLAM/VIO system. A higher-resolution CPU image is not automatically a better tracking choice if a different config provides better stereo/VIO/depth behavior.

The camera selector only shows camera configurations that ARCore itself exposes as valid tracking cameras.

---

## 5. Metric geometry generation

The shared transform is metric because it uses ARCore-provided 3D geometry.

`MetricSupportSampler` now uses a three-level geometry policy.

### 5.1 Primary: Raw Depth + Confidence

The preferred source is:

```text
acquireRawDepthImage16Bits()
acquireRawDepthConfidenceImage()
```

For raw depth:

- depth is interpreted in millimeters,
- confidence below `128/255` is rejected,
- nearby samples are collected in a local window,
- high-confidence observations receive higher weight,
- the weighted median is used,
- median absolute deviation (MAD) rejects noisy depth edges/neighborhoods.

A single raw depth pixel is never trusted by itself.

### 5.2 Secondary: Full ARCore Depth

If raw depth is too sparse, the system falls back to:

```text
acquireDepthImage16Bits()
```

Full depth is denser and more temporally smoothed. It still goes through local median/MAD rejection before being used.

### 5.3 Final fallback: ARCore PointCloud

If depth is unavailable or insufficient, tracked ARCore point-cloud points are projected into the CPU image and used as sparse metric support.

Only points with usable tracking confidence and valid camera projection are kept.

### Output

The sampler creates correspondences of the form:

```text
[u, v, X_world, Y_world, Z_world]
```

Each sample therefore ties an image pixel to a real metric point in that phone's ARCore world.

---

## 6. Spatially-diverse micro-map instead of FIFO frames

The previous implementation behaved more like a rolling camera-frame queue. The current coordinator instead keeps a small **session micro-map** of useful keyframes.

Each side stores up to 12 recent keyframes.

A frame is considered spatially new when the camera moved approximately:

```text
>= 0.08 m translation
or
>= 5 degrees rotation
```

A frame is also admitted after about 1.5 s even without large motion.

Within that interval, a frame can replace the previous keyframe if it provides materially better metric support or was captured under calmer angular motion.

This gives the solver several distinct viewpoints instead of many nearly identical frames.

---

## 7. Frame pairing

The system does not simply solve newest-remote-frame against newest-local-frame.

The local and remote micro-maps are cross-paired. Candidate pairs are scored using:

- recency,
- number of remote metric support points,
- frame motion quality.

The strongest pairs are attempted first.

Before initial lock the solver may test up to 7 high-ranked frame pairs per solve cycle. After lock, background refinement uses fewer attempts to reduce CPU cost.

---

## 8. Visual feature matching

The deterministic classical vision path currently uses OpenCV SIFT.

### Matching policy

For each candidate frame pair:

1. SIFT descriptors are computed on both grayscale frames.
2. BFMatcher L2 KNN matching runs in both directions.
3. Lowe ratio filtering is applied.
4. Mutual nearest-neighbour consistency is preferred.
5. If mutual matching becomes too sparse, a stricter forward-ratio fallback is allowed.

Current ratios:

```text
normal ratio:   0.80
strict fallback: 0.72
```

This is intended to remove many repeated-texture mismatches before RANSAC.

---

## 9. Metric 3D-to-2D registration

A matched remote feature is associated with the nearest remote metric support sample in image space.

The current metric association radius is approximately:

```text
16 px
```

This creates:

```text
remote World XYZ  <->  local image pixel uv
```

Those correspondences are solved using:

```text
solvePnPRansac
```

with the current configuration approximately:

```text
iterations:          900
reprojection gate:   3.6 px
confidence:          0.999
initial solver:      EPNP
```

The inlier solution is refined using:

```text
solvePnPRefineVVS
```

with LM as a fallback.

The result is converted from OpenCV camera axes into ARCore camera axes and composed with the local ARCore camera pose.

The result is the full metric rigid transform between the two independent ARCore worlds.

---

## 10. Geometric rejection tests

A numerical PnP result is not automatically accepted.

The engine additionally checks:

- enough metric correspondences,
- enough RANSAC inliers,
- minimum 3D support diameter,
- positive-depth / cheirality ratio,
- finite transform values,
- rotation determinant close to 1,
- image-space coverage,
- reprojection error,
- gravity consistency,
- optional heading residual as a soft prior rather than an authority.

The metric-support diameter gate helps reject solutions built from a tiny spatial patch.

---

## 11. Lock acquisition

The room host is the canonical initial solver. The joiner can become a fallback solver if the host has not produced a usable solution after roughly 4.5 seconds.

### Baseline candidate gate

Current initial candidate requirements include approximately:

```text
inliers               >= 8
metric correspondences >= 8
median reprojection    <= 4.6 px
image coverage         >= 0.055
confidence             >= 0.11
gravity tilt           <= 16 degrees when available
```

These are only the first candidate gates.

### Strong one-shot lock

A particularly strong single solve may lock immediately when it reaches approximately:

```text
inliers               >= 14
metric correspondences >= 14
median reprojection    <= 3.4 px
image coverage         >= 0.085
confidence             >= 0.19
gravity tilt           <= 8 degrees when available
```

### Multi-frame consensus

Otherwise, accepted candidate transforms are clustered by spatial agreement.

The current broad initial cluster tolerances are about:

```text
translation agreement <= 0.45 m
rotation agreement    <= 8 degrees
```

The system chooses a **transform medoid** from the strongest cluster rather than averaging rotation matrices directly.

This avoids allowing one unstable frame to drag the shared coordinate system.

---

## 12. Bidirectional verification

A local visual lock is not enough for the user-facing ready state.

The canonical transform is sent to the peer. The peer:

1. validates that the matrix is a plausible rigid transform,
2. inverts it into its own coordinate direction,
3. validates gravity consistency,
4. validates that the sender reports real visual evidence,
5. adopts the transform,
6. sends an acknowledgement transform back.

The original solver verifies the acknowledgement against its own transform.

Current ACK agreement tolerance is approximately:

```text
translation <= 0.18 m
rotation    <= 3.5 degrees
```

The final user-facing readiness condition is effectively:

```text
localReady
AND peerReady
AND peerTransformVerified
```

Only then can a user place a POI.

---

## 13. RTT / BLE / heading fusion policy

The current product architecture treats radio and compass sensors as **supporting evidence**, not as the spatial truth.

### Wi-Fi RTT

Wi-Fi RTT is used after the direct connection exists. It can provide an independent phone-to-phone range check.

It is useful for detecting obviously impossible visual solutions, but it cannot recover the full relative 6-DoF transform by itself.

### BLE range

BLE range can be retained as a weaker fallback range input when a recent RTT sample is unavailable. Its uncertainty is intentionally treated conservatively.

### Magnetometer / heading

Heading is a prior/diagnostic. It can help rank or reject extreme orientation hypotheses, but a strong visual metric solution remains authoritative because phone magnetometers are easily disturbed by local metal/electronics.

---

## 14. POI placement is now anchor-based

A major change from the earlier implementation is that a POI is no longer treated as a permanently fixed raw XYZ number.

### Sender

When a user taps:

1. ARCore hit-test is attempted first.
2. Valid `DepthPoint`, `Plane`, or surface-normal `Point` hits can create an Anchor.
3. If hit-test cannot create a reliable point, robust metric depth at the tap pixel is used.
4. A local ARCore Anchor is created.

The POI is therefore attached to the phone's active ARCore map.

### Continuous sender correction

The sender periodically republishes the local Anchor position when:

```text
anchor movement >= 0.015 m
or
heartbeat >= 2 s
```

This means later ARCore map refinement can propagate corrected anchor coordinates to the peer.

### Receiver

The transformed remote POI is stored as a local receiver-side ARCore Anchor.

The receiver replaces/re-anchors it if the transformed input position changes by about:

```text
>= 0.04 m
```

Both devices therefore render using their own local anchors instead of blindly retaining one historic world XYZ value.

---

## 15. Background drift refinement

`LOCKED` no longer means that the transform is computed once and frozen forever.

After the initial lock, the host opportunistically attempts a lower-rate refinement roughly every 1.8 seconds when useful overlap exists.

### Small corrections

Small, high-quality changes are smoothly incorporated in SE(3):

- translation is interpolated,
- rotation is blended with quaternion SLERP,
- strong measurements receive a larger update factor.

Typical blend factors are around:

```text
0.16 normal refinement
0.28 strong refinement
```

### Larger corrections

A larger correction is not accepted immediately. Multiple refinement measurements must form a cluster first.

A cluster consensus can then be applied with a stronger smoothing factor of roughly `0.62`.

### Hard rejection

A refinement measurement is rejected outright when it jumps more than roughly:

```text
1.5 m translation
18 degrees rotation
```

The purpose is to follow genuine ARCore/world refinement gradually without allowing a single bad match to teleport the shared space.

If no overlap is available, the last verified transform is retained rather than continuously inventing new pose corrections.

---

## 16. Thermal-aware compute governor

Production tracking is not improved by running the vision stack so hard that Android throttles the phone or ARCore VIO loses CPU time.

`RuntimePerformanceGovernor` samples Android thermal state/headroom and adjusts **our own** capture workload while leaving ARCore VIO itself alone.

Current tiers are:

```text
FULL
WARM
HOT
CRITICAL
```

Example capture budgets:

| Tier | Initial alignment | After lock |
| --- | --- | --- |
| FULL | ~500 ms, max width 960 | ~2.0 s, max width 896 |
| WARM | ~650 ms, max width 896 | ~3.0 s, max width 800 |
| HOT | ~900 ms, max width 768 | ~4.5 s, max width 704 |
| CRITICAL | ~1.35 s, max width 640 | ~7.0 s, max width 576 |

Escalation happens immediately; recovery happens one tier at a time to avoid thermal oscillation.

This protects SLAM/VIO quality during longer sessions.

---

## 17. Runtime state model

The practical user-visible state progression is conceptually:

```text
OFFLINE
  ↓
DISCOVERING / CONNECTING
  ↓
DIRECT LINK
  ↓
ACQUIRING GEOMETRY
  ↓
ALIGNING
  ↓
CONFIRMING
  ↓
LOCKED / READY
  ↓
POI ACTIVE
```

Behind the UI, `LOCKED` requires a verified shared transform on both devices, not merely a network connection.

A camera/session change resets alignment because the relation between camera observations and the AR world may have changed.

---

## 18. Off-screen guidance

The remote Anchor is projected through the latest ARCore camera view/projection every render frame.

When the target is in front of the camera and inside the viewport, the normal AR marker is shown.

When it is outside the viewport or behind the camera:

- the target direction is calculated in camera space,
- the bearing is derived with `atan2`,
- the overlay clamps the indicator to the screen edge,
- distance and owner information remain available.

The user therefore does not lose a POI merely because they turned away from it.

---

## 19. What is new compared with the earlier V2 state

The important production-hardening changes are:

### Geometry

- Raw Depth is now preferred over smoothed depth.
- Raw depth confidence filtering is used.
- Local robust median/MAD depth validation was added.
- Point-cloud fallback remains available.

### Vision

- SIFT matching is bidirectional.
- Mutual-nearest consistency is preferred.
- Strict ratio fallback is used when mutual matching is too sparse.
- stronger PnP validation and VVS refinement are used.
- cheirality, support diameter, determinant, gravity and coverage rejection were added/hardened.

### Temporal tracking

- FIFO frames became a spatially-diverse micro-map.
- frame pairs are ranked rather than blindly paired by arrival order.
- candidate transform clustering/medoid selection was added.
- a canonical host transform is explicitly shared and acknowledged.
- background drift refinement now continues after lock.
- SE(3) smoothing uses quaternion SLERP instead of naïve matrix averaging.

### POIs

- raw persistent XYZ was replaced by ARCore Anchors.
- sender anchor updates are periodically propagated.
- receiver re-anchors corrected positions.

### Device/runtime

- the best ARCore tracking camera config is explicitly selected.
- thermal-aware compute throttling protects ARCore VIO.
- post-lock vision traffic is reduced.

### Transport

- Wi-Fi Aware connection setup is hardened for modern Android and vendor edge cases.
- stale data-path callbacks are guarded by generation IDs.
- frame transport is back-pressure aware.
- RTT begins after the data path is established instead of competing with discovery/NDP setup.

### UX correctness

- POI placement remains disabled until both devices agree that the shared transform is verified.
- weak/unobservable geometry should remain in alignment state instead of pretending success.

---

## 20. What the system should do well now

Under a normal textured static scene with useful overlap and ARCore tracking:

- discover the other phone without infrastructure,
- establish the direct link automatically after room selection,
- accumulate useful geometry while the phones move naturally,
- acquire one shared metric coordinate transform,
- reject obviously weak transforms,
- verify the transform on both devices,
- allow either user to place a shared physical POI,
- keep the POI locally stable using ARCore Anchors,
- guide the receiver back to an off-screen POI,
- retain the last good lock when the phones stop looking at the same scene,
- opportunistically improve the shared transform when overlap returns,
- reduce app-side compute load as the device becomes hot.

---

## 21. What it should intentionally NOT do

The system should not claim a precise fresh lock when the geometry is fundamentally insufficient.

Examples:

- completely textureless wall,
- both phones looking at unrelated scenes before any previous lock exists,
- extreme motion blur,
- very poor lighting,
- mostly moving/dynamic objects,
- depth dominated by reflective/transparent surfaces,
- insufficient common visual structure,
- ARCore itself not tracking.

A scalar A↔B range measurement alone cannot determine a new arbitrary 6-DoF transform.

In these situations the correct product behavior is to remain in alignment/acquisition state and ask for better visual geometry rather than fabricate precision.

---

## 22. Accuracy expectations

The software is engineered to improve stability and reject weak spatial solutions, but no CI build can certify real-world centimeter accuracy.

Actual error depends on:

- target range,
- depth quality,
- scene texture,
- overlap,
- phone baseline,
- camera calibration,
- rolling-shutter/motion blur,
- lighting,
- reflective/transparent surfaces,
- ARCore VIO quality,
- thermal state,
- dynamic objects.

The correct next step for accuracy tuning is controlled physical measurement, not lowering software thresholds until every scene says `READY`.

---

## 23. Recommended field acceptance tests

For S25 Ultra ↔ S26 testing, record both successful locks and deliberate failure cases.

### Baseline tests

| Test | Phone separation | Target range | Overlap |
| --- | ---: | ---: | --- |
| A | 0.5 m | 2 m | high |
| B | 1.0 m | 3 m | high |
| C | 2.0 m | 5 m | medium/high |
| D | 5.0 m | 10 m | medium |
| E | variable | variable | deliberately poor |

### For each test record

- time to direct connection,
- time to first lock,
- inlier count,
- correspondence count,
- median reprojection error,
- image coverage,
- lock confidence,
- RTT range if available,
- measured physical POI error,
- drift after 1/5/10 minutes,
- recovery after turning 180 degrees,
- recovery after temporary AR tracking loss,
- thermal tier,
- lighting/scene notes.

### Important product tests

- host and joiner roles swapped,
- both phones can create a POI,
- receiver turns completely away and follows edge arrow back,
- walk several metres after lock,
- lose common overlap and retain POI,
- regain overlap and observe stable refinement,
- disconnect/reconnect,
- change AR camera and verify alignment resets safely,
- long-session thermal behavior,
- deliberately bad low-texture scene must refuse/slow lock rather than hallucinate one.

---

## 24. Main implementation files

```text
android/app/src/main/java/com/sirpaul/spatialnomap/

WifiAwarePeerTransport.kt       Direct Wi-Fi Aware discovery/NDP/TCP/RTT
AlignmentCoordinator.kt         keyframe micro-map, host solve, consensus, verification, refinement
AlignmentEngine.kt              SIFT, metric association, PnP, geometric validation
MetricSupportSampler.kt         Raw Depth / confidence / full depth / PointCloud metric geometry
ArCameraCatalog.kt              ARCore physical tracking-camera ranking and selection
ArRenderer.kt                   ARCore session loop, anchors, taps, POI projection
RuntimePerformanceGovernor.kt   thermal-aware capture/compute budget
TargetOverlayView.kt            marker + off-screen guidance UI
FrameCapture.kt                 grayscale frame/intrinsics/pose/metric packet creation
Models.kt                       wire/runtime packet models
```

---

## 25. Current development status

The current codebase is at the stage where the software architecture is production-oriented and the Android CI validates compilation, tests, lint and signed APK generation.

The remaining gate before calling the spatial accuracy itself production-certified is **physical acceptance testing on the target phones** and measured tuning from real error data.

That distinction is intentional:

```text
software/build correctness  -> CI can verify
physical spatial accuracy   -> real devices must verify
```
