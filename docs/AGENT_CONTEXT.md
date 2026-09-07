# Spatial Sync — Continuation Context

**Purpose:** durable engineering handoff for any agent inheriting this project after chat/context loss. Read `AGENTS.md` first, then this file. The current code is always the source of truth if this document becomes stale.

## 1. Product goal

Build a high-accuracy, infrastructure-free shared AR world for nearby Android phones. Phones join a room, establish one physical coordinate frame quickly, and then any manual target or automatically detected real-world object observed by one phone appears at the same physical location on its peers.

Priority order is deliberately unusual:

1. spatial correctness,
2. stability,
3. fast/reliable acquisition,
4. observability/debuggability,
5. presentation quality,
6. only then bandwidth/CPU/battery/APK size.

The user explicitly accepts very high local compute and P2P bandwidth if it improves spatial quality.

## 2. Non-negotiable architecture

- No Google Cloud Anchors.
- No required cloud/backend/VPS for the base shared-world path.
- No pre-scanned map requirement.
- ARCore `Anchor` objects are local anchors only; an Anchor ID is never a cross-device coordinate system.
- Nearby transport is Wi-Fi Aware/NAN -> encrypted NDP -> IPv6 TCP.
- Manual POIs are additive; adding one must not remove an older one.
- Cars/persons are dynamic world-space tracks, not permanent ARCore anchors.
- Once a room transform is verified, do not continuously move it in a way that drags already-placed POIs.
- All product UI text remains English.
- When a hardware test fails, instrument the exact gate; do not blindly loosen thresholds.
- Do not claim a release is ready until CI and `latest-dev` are both verified against the final branch HEAD.

## 3. Current checkpoint

Working branch: `fresh/no-map-runtime-poc`.

Runtime code baseline immediately before this context refresh:

`9f34448e33ae110b2644de3550a0d7bc171b49e4`

`fix: restore Android YuvImage import`

That commit includes the full 0.7 spatial-world runtime batch. Documentation commits after it make branch HEAD newer without changing runtime behavior, so always inspect current HEAD and current `latest-dev`.

### Physical-test history that matters

Earlier builds could sit in `ALIGNING` for minutes despite both phones reaching local ARCore `TRACKING` almost immediately. Several real architecture bugs were found rather than treating this as a scene-quality problem:

1. two independently-originated AR worlds were compared too directly by raw transform components;
2. scene-space agreement over real 3D points replaced raw translation equality;
3. direct 3D<->3D shared visual alignment and an essential-matrix + metric-scale path were added;
4. the receiver still required its own `lockedTransform` before accepting the peer transform, recreating a hidden double-solve deadlock;
5. `fc2b8ef...` fixed that receiver bootstrap;
6. 0.7 now goes further: host-first canonical solve, synchronized acquisition bursts, replay/telemetry, and cached visual relocalization.

If a good shared textured scene still remains `ALIGNING` for more than roughly 10–15 seconds, treat it as a concrete diagnosable bug. Pull the recorded session rather than telling the user to wander around for minutes.

## 4. Core world model

Every device begins with its own unrelated ARCore origin.

```text
local ARCore world A                     local ARCore world B
       |                                        |
 camera RGB + intrinsics + pose          camera RGB + intrinsics + pose
 raw/full depth + point cloud            raw/full depth + point cloud
       |                                        |
 metric [u,v,worldX,worldY,worldZ]       metric [u,v,worldX,worldY,worldZ]
       |________________ P2P frames ___________|
                         |
                  visual solver ladder
                         |
                 localFromRemote SE(3)
                         |
                 canonical shared frame
                         |
         +---------------+----------------+
         |                                |
 static manual POIs                dynamic object tracks
 local ARCore Anchors              direct world-space XYZ
```

The host is the preferred canonical authority. The client may become a recovery solver if the host cannot solve quickly.

## 5. Capture quality and synchronized acquisition

### FrameCapture

`FrameCapture.kt` currently sends quality-first registration frames:

- grayscale CPU camera image,
- up to 1280 px width while acquiring,
- JPEG quality 90,
- scaled camera intrinsics,
- ARCore camera pose,
- up to 8000 metric supports,
- sensor snapshot,
- V6 acquisition `burstId` + `burstSequence`.

### RuntimePerformanceGovernor

Cool-device capture is intentionally heavy:

- FULL: ~250 ms cadence; 1280 px before lock, 1152 after lock,
- WARM: ~350 ms,
- HOT: ~500 ms,
- CRITICAL: ~800 ms.

Locked mode intentionally remains relatively high-rate because the live 3D world/actor view and accumulated map need temporal density too.

### AcquisitionBurstController

After a direct TCP socket is established, both sides exchange `Hello`. They derive a stable burst ID from the sorted `username|deviceModel` pair and run a 12-frame burst at ~250 ms intervals.

Important behavior:

- outgoing Hello resets connection-local burst/locked state on reconnect;
- remote Hello starts the burst;
- one frame per burst sequence is preserved even if the phones are nearly stationary;
- frame pairing strongly prefers equal or adjacent sequence numbers;
- retries reuse the same stable pair burst ID if lock did not happen.

No synchronized monotonic clocks are assumed.

## 6. Alignment solver ladder

`AlignmentEngine.kt` starts with high-feature SIFT matching and then tries complementary geometry paths. Do not remove fallback paths just because a newer fast path exists.

### 6.1 EssentialSharedPoseSolver

Use when visual overlap is strong but metric depth is sparse at exact SIFT features:

1. 2D<->2D correspondences,
2. normalized camera coordinates,
3. essential matrix + recoverPose,
4. relative rotation + translation direction,
5. a few metric pairs recover translation scale in metres,
6. metric residual validation,
7. gravity validation,
8. epipolar/coverage/confidence validation.

Approximate gates:

- >=12 visual matches,
- >=10 visual inliers,
- median epipolar <=3.5 px,
- image coverage >=0.045,
- >=2 scale pairs,
- baseline 0.05..12 m,
- median metric residual <=0.24 m,
- gravity <=12 deg,
- confidence >=0.10.

### 6.2 SharedVisualAnchorSolver

When both phones have metric XYZ for corresponding visual features, solve a direct rigid 3D<->3D fit. This is the local/infrastructure-free equivalent of establishing a shared physical anchor.

Approximate gates include >=6 input pairs, robust RANSAC/Horn fit, >=5 final inliers, residual/coverage/support checks, then reprojection/gravity/confidence validation.

### 6.3 PnP fallback

Dense metric PnP remains for device/scene variability:

- remote 3D world supports,
- local matched 2D observations,
- solvePnPRansac EPNP,
- VVS/LM refinement,
- cheirality/spatial diameter/reprojection,
- independent local metric residual validation,
- gravity validation.

## 7. Coordinator and host-first canonical solve

`AlignmentCoordinator.kt` is the authority for two-phone live registration.

Current base lock gates are approximately:

- inliers >=8,
- correspondences >=8,
- reprojection/epipolar <=4 px,
- coverage >=0.05,
- effective confidence >=0.10,
- gravity <=12 deg,
- fresh RTT/BLE range compatibility when available,
- metric consistency when enough pairs exist.

Strong one-frame lock is approximately:

- >=12 inliers,
- <=3 px,
- coverage >=0.07,
- confidence >=0.16,
- gravity <=7 deg,
- range/metric gates pass.

Otherwise candidate consensus is used.

### Host-first behavior

The host is the preferred canonical solver. The joiner spends the first `CLIENT_HOST_SOLVE_GRACE_MS = 7000` ms capturing and streaming rather than competing on a second full solve.

Flow:

```text
DIRECT
 -> both local ARCore TRACKING
 -> synchronized capture burst
 -> HOST solves hostFromClient
 -> host broadcasts strong canonical transform
 -> CLIENT checks rigid + gravity + fresh physical range + visual evidence
 -> client adopts inverse canonical mapping
 -> PEER_ACK
 -> LOCKED
```

If the host still has no usable transform after the grace period, the client automatically enables `CLIENT_RECOVERY` solve. This prevents a weak host camera/depth path from deadlocking the room.

Strong peer bootstrap currently expects roughly confidence >=0.14, >=10 inliers and <=3.5 px peer reprojection plus receiver rigid/gravity/range sanity.

If both devices independently solve before adoption, stricter scene-space agreement remains available.

## 8. Persistent visual relocalization

Files:

- `SharedLandmarkCache.kt`
- `RelocalizationEngine.kt`

After a verified room exists, the app persists a strong old local frame, old remote frame, and old verified `oldLocalFromOldRemote` transform locally.

After a later ARCore world reset/reconnect, the host can reconstruct the shared frame without any Cloud Anchor:

```text
A = newLocalFromOldLocal
B = newRemoteFromOldRemote
oldT = oldLocalFromOldRemote
newT = A * oldT * inverse(B)
```

Both A and B are obtained through the same visual/metric `AlignmentEngine` validation. The resulting transform still passes rigid, gravity and physical-range checks. Failure is safe: the normal fresh host solve continues.

Current host relocalization window is roughly 0.9..9 s after connect with retries around 2.2 s.

The persisted checkpoint is local infrastructure only; it is not a Google Cloud Anchor and is not a pre-scanned remote map.

## 9. Alignment flight recorder / observability

This is now implemented and should be used before threshold tuning.

Files:

- `AlignmentSessionRecorder.kt`
- `AlignmentDiagnostics.kt`
- `RecordedAlignmentReplay.kt`

Each direct connection creates a rolling external-files session directory under `alignment_sessions`. Keep roughly the newest 6 sessions.

Session files:

- `events.ndjson` — wire/registration timeline,
- `quality.ndjson` — exact coordinator quality/gate snapshots at ~4 Hz,
- `frames.spv6` — replayable local+remote registration frames,
- `shared-world.voxels` — accumulated metric world map,
- `README.txt` — format reminder.

The quality blocker taxonomy includes:

- `LOCKED`
- `VISUAL_INLIERS`
- `CORRESPONDENCES`
- `NO_REPROJECTION`
- `REPROJECTION`
- `IMAGE_COVERAGE`
- `CONFIDENCE`
- `GRAVITY`
- `METRIC_INLIERS`
- `METRIC_RESIDUAL`
- `WAITING_PEER_READY`
- `PEER_TRANSFORM_VERIFY`
- `CONFIRMING`
- `CONSENSUS`

`RecordedAlignmentReplay` decodes `frames.spv6` and prefers same-burst/same-sequence local/remote pairs. The intended next debugging pattern is: reproduce once on hardware -> pull session -> replay/inspect offline -> patch exact failure.

## 10. Manual targets

`ArRenderer.kt` holds additive maps:

- `localTargets[id]`
- `remoteTargets[id]`

Every manual tap gets its own local ARCore Anchor and unique target ID. The owner sees its own gizmo. Remote static POIs are anchored locally once; repeat metadata packets must not continually re-anchor them.

`TargetOverlayView.kt` renders multiple on-screen markers plus off-screen directional guidance.

`CLEAR` means clear all manual/dynamic target state on both connected phones.

## 11. Tap precision

Manual placement combines:

- screen -> image coordinate conversion,
- metric depth/world lookup,
- ARCore DepthPoint/Plane/Point hit candidates,
- metric-vs-hit disagreement rejection,
- direct metric anchor fallback.

Approximate tolerances remain ~0.18 m minimum depth disagreement / ~6% relative tolerance, with ~30 m max manual target distance.

The rule is fail closed: rejecting an uncertain tap is better than placing a confident-looking marker on the wrong physical surface.

## 12. Vehicle recognition and dynamic tracking

`VehicleDetector.kt` uses MediaPipe Tasks Vision + EfficientDet-Lite0 COCO fully on-device at runtime. Current recognized classes are `car`, `truck`, `bus`.

The model is downloaded at build time and packaged in the APK; runtime object inference does not require cloud access.

Pipeline:

```text
camera frame
 -> EfficientDet 2D box
 -> metric supports inside box
 -> robust depth/range filtering
 -> world XYZ
 -> MotionTrackFilter constant-velocity alpha-beta smoothing
 -> room dynamic track ID
 -> V6 dynamic-target packet
 -> peer world transform
 -> moving gizmo + trail
```

`MotionTrackFilter.kt` estimates position and velocity and can briefly predict through detector gaps. `MotionTrackFilterTest.kt` covers forward prediction/reset behavior.

The renderer still performs room-level ID association. Vehicles remain direct world-space dynamic targets, never static ARCore Anchors.

## 13. Wire protocol V6

`PeerProtocol.kt` is **VERSION 6**. Older docs saying V4/V5 are stale.

Important V6 behavior:

- 16 MiB max payload,
- up to 8000 metric supports/frame,
- frame `burstId` + `burstSequence`,
- dedicated `T_DYNAMIC_TARGET` wire type for current automatic car updates,
- Hello / Frame / Poi / Clear / Range / Quality+transform / ResetAlignment remain,
- the same wire traffic feeds WorldViz, flight recording and persistent landmark caching.

V6 intentionally breaks compatibility with older APKs. Always update both phones together.

The current Wi-Fi Aware discovery namespace/PSK naming may still contain historical `v6` strings independently of the binary packet version; inspect code rather than assuming those strings represent protocol generation.

## 14. 3D shared-world / Bird's Eye demo view

This is the major 0.7 presentation feature.

Files:

- `WorldViz.kt`
- `SpatialMapAccumulator.kt`
- `BirdEyeWorldView.kt`

`WorldVizBus` mirrors the exact live wire state and uses the real currently observed `localFromPeer` transform. It does not invent a demo-only coordinate system.

`SpatialMapAccumulator` fuses metric geometry from both phones into a bounded ~7.5 cm voxel cloud (up to ~16k voxels) after the shared frame is locked. It persists the current accumulated map into the flight-recorder session.

The `WORLD` button opens a fullscreen screen-recordable visualization showing:

- accumulated live 3D environment point map,
- latest local point cloud,
- latest transformed peer point cloud,
- both phone poses/actors,
- static POIs,
- dynamic vehicle tracks,
- ~18 s actor/vehicle motion trails,
- auto-orbit animation,
- drag-to-orbit,
- pinch-to-zoom,
- alignment state/blocker/inliers/coverage/metric residual,
- synchronized acquisition burst progress.

This visualization is built from the real data already produced by the app. It is not a generated image, Cloud Anchor, SLAM cloud service, or pre-baked map.

See `docs/SPATIAL_WORLD_DEMO.md` for the demonstration sequence.

## 15. Canonical multi-peer room state and current physical boundary

`SharedRoomState.kt` now defines the transport-independent canonical room model:

- arbitrary peer map,
- `canonicalFromPeer` per peer,
- canonical peer pose,
- target types `MANUAL`, `VEHICLE`, `PERSON`, `GENERIC`,
- canonical position/velocity/confidence,
- TTL/pruning for dynamic actors.

This is the intended N-phone star model:

```text
              HOST CANONICAL WORLD
            /         |          \
        peer A      peer B      peer C
```

**Important:** the current live `WifiAwarePeerTransport` still owns one active `currentPeer` / one active TCP socket per app instance and `AlignmentCoordinator` is still one-peer. The data/state model is N-peer ready, but physical N-phone fan-out is not yet enabled in the shipping runtime. Do not present 3+ simultaneously connected phones as already working until the transport/coordinator are refactored into per-peer sessions.

This limitation does not affect the current two-phone demo.

## 16. Networking

`WifiAwarePeerTransport.kt` handles the current direct link:

- Wi-Fi Aware/NAN discovery,
- encrypted NDP,
- IPv6 TCP,
- recovery across app resume/stale handles/data-path failures,
- frame backpressure (newest useful frame),
- optional Wi-Fi RTT ranging,
- BLE fallback range.

RTT is a validation aid, not an authoritative spatial solve, and must remain optional.

The transport still uses historical service namespace `spatialnomap.v6` and a `Spatial-<ROOM>-V6` PSK form.

## 17. Runtime dependencies / build

Android module: `:app`.

Current main stack:

- compileSdk 36,
- targetSdk 36,
- minSdk 33,
- Java/Kotlin 17,
- ARCore 1.56.0,
- OpenCV 4.12.0,
- MediaPipe Tasks Vision 0.10.35,
- JUnit 4.13.2.

Version family is now `0.7.0-spatial-world.*`.

Stable POC signing material is committed so CI/local sideload builds keep the same app identity. Never treat that as production Play signing material.

CI runs unit tests, debug/release lint, debug/release APK builds, signing verification, artifact upload and then republishes `latest-dev`.

Use `SpatialSync-latest-release.apk` for normal hardware testing. Vehicle builds are large (~200 MB) because MediaPipe/model assets are bundled.

## 18. Physical acceptance test

### Alignment

1. Install the exact same V6 release APK on both phones.
2. Let both reach local ARCore `TRACKING`.
3. CREATE/JOIN the same room.
4. For the first ~3 seconds, point both cameras at the same textured matte region and make a small lateral motion.
5. Expect host-first acquisition and `LOCKED` in seconds in a good scene.
6. If it exceeds ~10–15 seconds, capture the newest `alignment_sessions` directory. Do not threshold-tune blind.

### Manual target stability

After lock:

- tap 3–5 distinct physical surfaces,
- owner must see own gizmos,
- peer must see them at the same physical locations,
- adding targets must not remove older ones,
- rotate/walk around and verify target stability,
- test off-screen arrows,
- CLEAR and verify both sides clear.

### Vehicle demo

- point one phone at a car without tapping,
- verify automatic `CAR` track appears locally and remotely,
- turn the peer away and verify directional guidance,
- move observer/car enough to demonstrate smooth dynamic updates/trail,
- disappearance should expire rather than leave a permanent static car marker.

### WORLD view

- press `WORLD`,
- walk the phones to build the accumulated 3D map,
- show local+remote actor motion and trails,
- add POIs and a car so all target classes appear in the same view,
- use auto orbit for recording, then drag/pinch to demonstrate interactive 3D inspection.

## 19. Failure-debug workflow

If hardware behavior is bad:

1. reproduce once;
2. locate newest app external-files `alignment_sessions/session-*` directory;
3. inspect `quality.ndjson` blocker progression;
4. inspect `events.ndjson` for transform/ACK/range timing;
5. replay `frames.spv6` through `RecordedAlignmentReplay` / `AlignmentEngine`;
6. only then alter a solver/gate.

For `ALIGNING` failures, distinguish these classes:

- insufficient visual matches,
- essential geometry failure,
- no metric scale,
- direct 3D<->3D residual failure,
- PnP failure,
- coverage/confidence/gravity failure,
- physical range incompatibility,
- no consensus,
- peer transform not received,
- peer bootstrap rejection,
- ACK/verification failure.

## 20. High-value next work after 0.7 hardware validation

Do these only after the current V6 build has real-device evidence:

1. turn the current recorded physical sessions into deterministic CI regression fixtures;
2. improve same-car multi-observer dedup/fusion and send explicit velocity in the dynamic-target payload;
3. add PERSON semantic tracking using the same dynamic-track architecture;
4. refactor `WifiAwarePeerTransport` into a host multi-session transport and instantiate one alignment session per joiner to make the already-existing `SharedRoomState` truly N-phone;
5. add map/landmark quality selection so relocalization stores several high-value landmarks instead of one latest pair;
6. add quantitative acceptance metrics: median/p95 lock time, target cross-device error, relocalization success rate, long-run anchor drift.

Do not trade away the now-established fail-closed spatial correctness just to make the UI say `LOCKED` sooner.
