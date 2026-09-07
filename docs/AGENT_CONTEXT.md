# Spatial Sync — Continuation Context

**Purpose:** this is the durable engineering handoff for a new agent after chat/context loss. Read `AGENTS.md` first, then this file. Current code is the source of truth if anything here becomes stale.

## 1. Product goal

Build a high-accuracy, infrastructure-free shared AR world for nearby Android phones. The expected experience is: phones join a room, establish the same physical coordinate frame quickly, and then any target/object placed or observed by one phone appears at the same real-world position on all peers.

Priorities are **accuracy, stability, and reliability first**. The user explicitly accepts high bandwidth, CPU/GPU use, large APKs, and dense data transfer if that improves spatial quality.

Near-term product behavior:

- fast two-phone room alignment,
- multiple manual targets at once,
- owner can see its own target gizmo too,
- remote/off-screen target direction arrows,
- automatic vehicle recognition and sharing,
- later person recognition/tracking,
- later true N-phone rooms and shared perception.

## 2. Non-negotiable architecture

- No Google Cloud Anchors.
- No required cloud/backend/VPS for basic operation.
- No pre-scanned map requirement.
- ARCore `Anchor` is only a local anchor.
- Direct phone-to-phone transport is Wi-Fi Aware/NAN -> encrypted NDP -> IPv6 TCP.
- Once room alignment is accepted, the canonical transform should remain stable; do not casually reintroduce continuous transform refinement that drags already-placed POIs.
- Manual targets are additive.
- Moving objects are dynamic tracks, not static anchors.
- Product text must remain English.

## 3. Current checkpoint

Working branch: `fresh/no-map-runtime-poc`.

Functional code baseline before these docs:

`fc2b8efc3358cda0e53a8de53c4e1c9fbd9b9c92`

`fix: adopt verified peer bootstrap without double solve`

At that point CI/release passed. These documentation commits make HEAD newer without changing runtime behavior, so inspect current HEAD and `latest-dev` rather than treating the SHA above as current HEAD.

### Important physical-test status

The build before `fc2b8ef` (`6ced903...`) could remain in `ALIGNING` indefinitely on both physical phones. The root cause found afterward was real: the receiver still effectively required its own local lock before accepting the peer transform:

```kotlin
val existing = lockedTransform ?: return
```

That meant both phones still had to independently complete the full solve even though the intended model was “first strong shared-world solve bootstraps the other phone”. `fc2b8ef` adds the missing receiver bootstrap path.

At handoff time the exact `fc2b8ef` behavior still needed final real-device validation. If it remains `ALIGNING` >10–15 seconds in an obviously shared textured scene, treat it as a concrete bug to instrument rather than telling the user to wander around for minutes.

## 4. Core data flow

```text
Phone A ARCore world
  -> CPU camera image + intrinsics + AR pose
  -> raw/full depth + point cloud
  -> metric support [u,v,worldX,worldY,worldZ]
  -> CapturedFrame
  -> Wi-Fi Aware peer link
  -> Phone B
  -> SIFT + relative-pose solver ladder
  -> metric/range/gravity validation
  -> localFromRemote SE(3)
  -> canonical room frame
  -> static POIs become local ARCore anchors
  -> dynamic detections remain moving world-space tracks
```

Every phone starts with an unrelated ARCore world origin.

## 5. Capture and keyframes

`FrameCapture.kt` currently favors registration quality:

- grayscale CPU image,
- max width up to 1280 while acquiring,
- JPEG quality 90,
- scaled camera intrinsics,
- current ARCore camera pose,
- up to 8000 metric supports,
- sensor snapshot.

`RuntimePerformanceGovernor.kt` cool-device acquisition is roughly:

- FULL: 250 ms / 1280 px,
- WARM: 350 ms / 1152,
- HOT: 500 ms / 960,
- CRITICAL: 800 ms / 800.

After lock it backs off.

`AlignmentCoordinator.kt` current keyframe behavior:

- window 18 per side,
- new if translation >=0.055 m or rotation >=3.5 deg,
- temporal admission at ~0.9 s,
- minimum motion quality ~0.44,
- a materially better-depth or calmer frame may replace the last one.

Frame pairs are ranked using relative recency, relative-age affinity, metric support, and motion quality. Cross-device monotonic clocks are not assumed equal.

Current solve interval is ~240 ms with up to 8 pair attempts.

## 6. Alignment solver ladder

### 6.1 SIFT

`AlignmentEngine.kt` begins with heavy SIFT matching. Current configuration intentionally uses many features (~4200) and bidirectional BFMatcher/L2 ratio matching with mutual matches plus bounded recovery matches.

### 6.2 Essential shared-pose fast path

`EssentialSharedPoseSolver.kt` exists because visual overlap can be excellent even when ARCore depth is sparse exactly at SIFT keypoints.

Flow:

1. 2D<->2D SIFT correspondences,
2. normalized camera coordinates,
3. essential matrix + recoverPose,
4. relative rotation + translation direction,
5. a few metric pairs recover translation scale in metres,
6. metric residual check,
7. gravity check,
8. epipolar/coverage/confidence check.

Current important gates are approximately:

- >=12 visual matches,
- >=10 visual inliers,
- median epipolar <=3.5 px,
- image coverage >=0.045,
- >=2 scale pairs,
- baseline 0.05..12 m,
- median metric residual <=0.24 m,
- gravity <=12 deg,
- confidence >=0.10.

### 6.3 Direct 3D<->3D shared visual anchor

`SharedVisualAnchorSolver.kt` robustly fits the rigid transform when both phones have metric XYZ for the same visual features.

Approximate current gates:

- >=6 input pairs,
- 3-point RANSAC/Horn fit,
- >=5 final inliers and >=55% ratio,
- residual inlier <=0.18 m,
- median <=0.12 m,
- p90 <=0.24 m,
- support diameter >=0.12 m.

`AlignmentEngine.sharedVisualAnchorResult()` then validates reprojection, coverage, gravity, and confidence.

### 6.4 PnP fallback

Dense metric PnP remains because real devices/scenes vary:

- remote metric 3D world points,
- local matched 2D keypoints,
- solvePnPRansac EPNP,
- VVS/LM refinement,
- cheirality,
- spatial support diameter,
- reprojection,
- independent local metric-depth residual check,
- gravity validation.

Do not remove fallback paths just because a newer fast path exists.

## 7. Coordinator acceptance

Current baseline lock gates:

- inliers >=8,
- correspondences >=8,
- median reprojection/epipolar <=4.0 px,
- image coverage >=0.05,
- effective confidence >=0.10,
- gravity <=12 deg,
- fresh RTT/BLE range compatibility when present,
- metric consistency when enough pairs exist.

Strong one-frame lock is approximately:

- >=12 inliers/correspondences,
- <=3.0 px,
- coverage >=0.07,
- confidence >=0.16,
- gravity <=7 deg,
- valid range/metric checks.

Otherwise candidates cluster. Current cluster tolerance is about 0.24 m / 4.5 deg. A robust candidate (~10 inliers, confidence >=0.14) needs 2-consensus; otherwise 3.

## 8. Canonical handshake and the latest critical fix

The intended model is one canonical room mapping, with the host as canonical authority.

Before `fc2b8ef`, peer verification still required `lockedTransform` on the receiving phone. That recreated a double-solve deadlock.

Current receiver bootstrap allows a phone with no local lock to adopt a strong peer-issued mapping after independent cheap sanity checks:

- rigid transform,
- gravity <=12 deg,
- physical RTT/BLE range compatible if fresh,
- peer confidence >=0.14,
- >=10 transform inliers,
- peer reprojection <=3.5 px.

The receiver then adopts the inverse peer transform and sends `PEER_ACK`. If both phones already independently solved, the stricter scene-space agreement path remains.

This means one good shared-world solve should now be sufficient to bootstrap the pair.

## 9. TRACKING vs ALIGNING

This must stay conceptually clear:

- `TRACKING` means the individual phone has stable local ARCore VIO.
- `ALIGNING` means the two phones do not yet share a verified room coordinate system.

Fast local tracking does not prove inter-phone alignment works.

## 10. Alignment failure history

Several previous approaches were wrong in specific ways:

1. Raw SE(3) translation components from two independently-originated AR worlds were compared too tightly. That could reject physically equivalent mappings.
2. Scene-space agreement over real 3D points replaced raw-matrix translation comparison.
3. Shared 3D<->3D visual alignment was added.
4. Essential-matrix + metric-scale alignment was added for sparse depth.
5. The receiver was still blocked on its own `lockedTransform`, so both phones still had to solve. `fc2b8ef` fixes this.

Do not regress to “both phones must always finish equivalent independent full solves before entering LOCKED”.

## 11. Highest-value next feature: alignment observability

The current largest engineering weakness is that `ALIGNING` is still too opaque.

Add structured per-solve telemetry and a compact dev HUD. At minimum expose:

```text
peer connected
local/remote keyframes
chosen frame pair
SIFT keypoints A/B
ratio matches / mutual matches
essential attempted + inliers
epipolar median
metric scale pairs / scale / residual
3D3D attempted + pairs/inliers/median/p90
PnP attempted + corr/inliers/reprojection
coverage
gravity
predicted phone distance
RTT/BLE distance + delta
confidence
candidate cluster count / required
peer transform received
bootstrap accepted/rejected + exact reason
ACK sent/received
final lock source
```

Prefer both:

- a live developer overlay,
- an NDJSON/ring-buffer session log that can be exported after a failed hardware test.

This should happen even if the current build works, because the next difficult scene otherwise returns us to blind threshold tuning.

## 12. Strong next alignment improvement: explicit synchronized burst

Current frame pairing is heuristic. A cleaner startup would be:

```text
CONNECTED
 -> both ARCore TRACKING
 -> PREPARE_ALIGNMENT(epoch)
 -> CAPTURE_BURST(sequence)
 -> both capture 1.5–3 s high-quality registration burst
 -> network sequence / estimated clock offset pairs near-simultaneous frames
 -> host solves one mapping
 -> peer validates/adopts
 -> ACK
 -> LOCKED
```

A tiny ping/pong clock-offset estimate or explicit capture sequence is better than relying only on relative keyframe age. This is likely the best next architecture change if lock time is still inconsistent.

## 13. Manual multi-target system

`ArRenderer.kt` now keeps additive maps:

- `localTargets[id]`
- `remoteTargets[id]`

Each manual target gets its own ARCore Anchor and unique ID. Adding another target must not detach old ones.

Remote static POIs are anchored locally once. Repeated packets should update metadata rather than continually replacing the anchor.

`CLEAR` clears all target/track state on both devices.

`TargetOverlayView.kt` supports multiple markers plus off-screen guidance.

## 14. Tap precision

Manual taps are not a naive hit test anymore. `ArRenderer.handleTap()` combines:

- screen->image coordinate transform,
- metric depth/world lookup,
- ARCore DepthPoint/Plane/Point hit candidates,
- metric-vs-hit disagreement rejection,
- direct metric anchor fallback.

Current rough tolerances:

- minimum depth agreement ~0.18 m,
- relative tolerance ~6%,
- max target distance ~30 m.

This exists because an earlier build could initially place a target correctly and later reveal it was attached to the wrong surface/world position.

## 15. Vehicle recognition and sharing

`VehicleDetector.kt` uses MediaPipe Tasks Vision + EfficientDet-Lite0 COCO fully on-device at runtime.

Current classes: `car`, `truck`, `bus`.

Confidence threshold ~0.38.

The model is downloaded at build time and bundled as `efficientdet_lite0_uint8.tflite`; runtime does not depend on cloud inference.

The detector converts the 2D vehicle box into a real AR world point by selecting metric supports from the central box region and robustly filtering them by range.

`ArRenderer.kt` maintains dynamic maps:

- `localVehicles[id]`
- `remoteVehicles[id]`

Vehicles are **not** ARCore Anchors.

Current rough tracker values:

- detection every ~450 ms,
- metric budget 5000,
- nearest-track association <=3 m,
- smoothing alpha 0.38,
- local TTL 2.2 s,
- remote TTL 3.0 s.

For now the POI wire packet is reused with owner prefix `AUTO:CAR:<username>`. The coordinator recognizes that prefix and does not persist/replay it as a static pending POI.

Future vehicle work should add velocity, temporal filtering, better data association, same-car dedup across observers, confidence fusion, and optional 3D bounding volume.

## 16. Generic shared target direction

Move away from owner-prefix hacks toward a real data model:

```text
SharedTarget
  id
  kind = MANUAL | VEHICLE | PERSON | ...
  sourceDevice
  positionWorld
  velocityWorld?
  confidence
  createdAt
  observedAt
  staticOrDynamic
  trackingState
  semanticLabel
```

Manual targets persist as anchors; vehicles/persons are short-lived dynamic tracks.

This will simplify N-phone fan-out.

## 17. Networking

`WifiAwarePeerTransport.kt` is currently effectively a two-phone topology.

Current service: `spatialnomap.v6`.

Current PSK form: `Spatial-<ROOM>-V6`.

Transport has recovery for Aware resume/session termination, stale PeerHandles, repeated data-path failures, and RTT backoff.

Client performs Wi-Fi RTT when supported and sends range to host. BLE is optional fallback. RTT must remain optional.

## 18. Wire protocol

`PeerProtocol.kt` is currently **VERSION 5**. Older docs may say V4 and are stale.

V5 supports up to:

- 16 MiB payload,
- 8000 frame metric points.

Messages include Hello, Frame, Poi, ClearPoi, Range, Quality/alignment transform, ResetAlignment.

Both phones should run the same current APK; do not mix protocol versions during testing.

## 19. Sensors and priors

`SpatialSensorFusion.kt` collects orientation, gyro, pressure, and location.

`FusionMath.kt` provides heading/GNSS/co-location/range priors.

These are validation/bootstrapping aids. They are not authoritative shared-world geometry. The coordinator disables heading inside the core vision solve and evaluates heading afterward.

## 20. Build and release

Android module: `:app`.

Current main dependencies/settings:

- compileSdk 36,
- targetSdk 36,
- minSdk 33,
- Java/Kotlin 17,
- ARCore 1.56.0,
- OpenCV 4.12.0,
- MediaPipe Tasks Vision 0.10.35,
- JUnit 4.13.2,
- version family `0.6.0-vehicles.*`.

Stable dev signing is committed for this POC so CI and sideload updates share an identity. It is not a production Play signing key.

CI runs tests, debug/release lint, debug/release APK builds, signature verification, artifact upload, then republishes `latest-dev`.

Normal hardware testing uses `SpatialSync-latest-release.apk`. Vehicle builds are ~200 MB due to MediaPipe/model packaging.

## 21. Physical acceptance test

### Alignment

- install the same current release APK on both phones,
- each phone should reach local AR tracking quickly,
- CREATE/JOIN,
- both cameras see a shared textured matte region,
- modest 10–20 cm lateral movement is useful but minutes of wandering are not acceptable,
- measure direct-connect -> LOCKED time.

If the current architecture is healthy, good scenes should converge in seconds.

### Manual target stability

After LOCKED:

- tap 3–5 distinct physical surfaces,
- owner sees own gizmos,
- peer sees them at the same physical locations,
- adding targets does not remove older ones,
- rotate/walk around and verify no visible drift,
- test off-screen arrows,
- clear and verify both clear.

### Vehicle demo

After LOCKED:

- point one phone at a real car,
- no tap is needed,
- local phone should show `CAR • YOU`,
- peer should receive the car position,
- turn peer away and verify direction guidance,
- move observer/vehicle and verify dynamic updates,
- stale car must disappear after TTL.

## 22. Missing test infrastructure

The biggest testing gap is a real-device registration record/replay corpus.

Add a developer mode that records paired `CapturedFrame` data and session outcome, then replay it offline through the solver ladder. Keep regression scenes that are known-good and known-bad and track:

- chosen solver,
- lock time,
- matches/inliers,
- residuals,
- false positive transforms,
- bootstrap outcome.

This will drastically shorten the development loop.

## 23. Recommended roadmap

Priority order:

1. **Validate the peer-bootstrap fix on hardware.**
2. **Add alignment diagnostics + exportable session logs.**
3. **Add explicit synchronized high-quality alignment burst / sequence pairing.**
4. **Move toward a host-driven single-solver canonical-room architecture.**
5. **Replace `AUTO:CAR:` with an explicit generic target/track protocol (likely protocol V6).**
6. **Improve vehicle temporal tracking and multi-observer dedup.**
7. **Implement true N-phone room fan-out.**
8. **Add person tracking using the same dynamic shared-track layer.**
9. **Add relocalization / shared landmark map for tracking loss and fast future joins.**

## 24. N-phone target architecture

Preferred future model:

```text
Host canonical frame
  +-- peer A transform -> canonical
  +-- peer B transform -> canonical
  +-- peer C transform -> canonical

Each phone emits observations:
  device, localTrackId, class, localPosition, velocity?, confidence, timestamp

Room layer transforms + associates them into:
  RoomTrack(globalId, class, canonicalPose, velocity, contributors, confidence)

RoomTrack is broadcast to all peers.
```

This allows A and C to see the same car while B sees only the shared room track, without creating duplicate unrelated markers.

## 25. Drift strategy

The user previously saw a target initially correct and later displaced. Current mitigation is stable canonical transform + local ARCore Anchors for static targets.

Future drift handling should not blindly mutate the canonical transform underneath anchors. Prefer drift detection, confidence downgrade, robust multi-frame correction consensus, and explicit relocalization/correction states.

## 26. Core file map

- `MainActivity.kt` — lifecycle, permissions, room UI, AR session setup, status/HUD.
- `ArRenderer.kt` — frame loop, taps, anchors, target projection, capture scheduling, vehicle integration.
- `TargetOverlayView.kt` — multi-target gizmos and touch ownership.
- `FrameCapture.kt` — registration packet.
- `MetricSupportSampler.kt` — raw/full depth + PointCloud -> metric supports.
- `AlignmentCoordinator.kt` — keyframes, solve scheduling, acceptance, canonical handshake, POI transform.
- `AlignmentEngine.kt` — SIFT + solver ladder.
- `EssentialSharedPoseSolver.kt` — 2D essential pose + metric scale.
- `SharedVisualAnchorSolver.kt` — robust 3D<->3D alignment.
- `FusionMath.kt` — priors/validation math.
- `SpatialSensorFusion.kt` — sensors.
- `WifiAwarePeerTransport.kt` — discovery/NDP/TCP/RTT/recovery.
- `PeerProtocol.kt` — binary protocol, currently V5.
- `VehicleDetector.kt` — on-device detector + metric 3D association.
- `RuntimePerformanceGovernor.kt` — thermal capture budget.

Docs in `docs/` are useful historically but may lag the implementation. Current code wins.

## 27. Implementation discipline

When an alignment test fails, identify which category it belongs to:

```text
NO PEER LINK
NO FRAMES
NO SIFT MATCHES
ESSENTIAL FAIL
NO METRIC SCALE
3D3D FAIL
PNP FAIL
LOCAL GATE FAIL
RANGE/GRAVITY FAIL
CANDIDATE CONSENSUS FAIL
PEER BOOTSTRAP FAIL
ACK FAIL
LOCKED BUT TARGET WRONG
LOCKED BUT DRIFT
```

Do not collapse all of these into one generic `ALIGNING` diagnosis.

When the user authorizes a repo change, implement it autonomously, commit it, let CI run, and verify the actual release before saying it is ready.