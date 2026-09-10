# StableAR SDK - local surface attachment

Version 0.1.0-research. Development only on `research_sdk`.
This is a standalone build; existing Spatial Sync and ShowMe applications are not
rebuilt, modified, signed, released or deployed by this build.

StableAR uses an existing visual-inertial tracker. It adds exact-frame local
geometry, retained coordinate frames, immutable point identity, bounded worker
processing and evidence-gated surface-depth correction. It is NOT a replacement
SLAM engine, a centimetre guarantee, a moving-object tracker, or a finished
commercially cleared product.

## Modules

- `core`: plain Kotlin/JVM geometry, robust inverse-depth surface fit, retained
  anchor/frame ledger, bounded freeze leases, immutable roots, robust ray-depth
  solver, correlated-error sensitivity and held-out correction transactions.
- `arcore`: host-session adapter. Converts ARCore camera axes once, retains nearby
  anchor frames, copies depth timestamps/provenance, maps historical pixels into
  the current local world and creates surface anchors. Never opens another camera.
- `vision`: optional OpenCV front end. Cached original patches, pyramidal LK with
  forward/backward rejection, immutable-root ORB reacquisition, spatial support
  and robust homography gates. No weights, cloud, automatic atlas learning or
  unsafe nearest-edge snapping. Geometry must still approve an image match.
- `demo`: independent single-phone AR app (`com.sirpaul.stablear.demo`). Tap a
  textured surface, move sideways, observe accepted/rejected corrections. Camera
  and annotations share a GL pass. Exports user-requested metadata only. No
  INTERNET, GPS, microphone, nearby-device or Wi-Fi permissions.

## Build and checks

From the repository root with Android SDK 36 and JDK17 installed:

```sh
python3 sdk/tools/collect_notices.py
sh android/gradlew -p sdk :core:check :arcore:assembleRelease :vision:assembleRelease :demo:assembleDebug
sh android/gradlew -p sdk -I sdk/tools/dependencies.init.gradle exportResolvedArtifacts
python3 sdk/tools/audit_licenses.py --resolved sdk/build/compliance/resolved.json --out sdk/build/compliance
```

PowerShell / Android Studio: open the **sdk** directory as its own Gradle project;
use installed Gradle 8.11.1 (`gradle -p sdk ...`). The repository's shell downloader
is not a Windows wrapper. No change to the old app's Gradle wrapper is required.
CI installs Gradle 8.11.1 explicitly and never calls the old application's build.

Outputs: `sdk/core/build/libs/`, `sdk/arcore/build/outputs/aar/`,
`sdk/vision/build/outputs/aar/`, `sdk/demo/build/outputs/apk/debug/demo-debug.apk`.
AARs are thin libraries, not fat redistributions of Google/OpenCV binaries.
Maven publication descriptors and sources JARs can be produced locally; no package
registry publication or repository-wide release/tag is configured.

## Host integration

Your application owns and configures `Session`, calls `session.update()` on its
render thread, and renders the camera. Create `ArCoreAdapter(session)` on that
same thread. After each new tracking frame, call `adapter.capture(frame)`.
The resulting `CameraSample` has a `FrameRef`, local depth evidence and an optional
same-exposure grayscale image. CPU images are closed before returning.

For a local point, convert your displayed pixel using that frame's exact image
mapping and call `adapter.place(sample, sensorPixel)`. For a remote point, keep
that sample under the exact displayed frame ID, call `adapter.freeze(ref.id)`
when freezing, and convert only that frame's visible camera crop. Never use the
latest camera pose or a guessed depth for an old image. `PresentedImage` provides
normalized crop/rotation mapping; arbitrary encoding crops require the host's
actual transform. ID/epoch mismatch or expired leases fail closed.

Create a cached visual reference from `sample.gray` at placement. Call
`adapter.context(id,currentSample,currentFrame)` before queueing work; it snapshots
camera pose relative to the *same retained surface anchor*. Worker results carry
anchor ID, epoch, frame timestamp and attachment generation. Return them to the
owner thread via `adapter.observe`. The SDK uses an additional held-out view to
commit; the original clicked ray never moves to a neighbouring edge.

Read `adapter.worldPoint(id)` only inside the current AR update/render frame.
Render it in the camera GL pass. Do not EMA-filter its projected screen motion.
When tracking is lost, call `trackingLost`; on a world/session reset call `reset`.
Pause/background must cancel pending tasks and invalidate external frame caches.
`close` detaches all SDK anchors on the owner thread.

## Explicit limitations

This release handles static local patches. It conservatively refuses insufficient
texture, occlusions and poorly conditioned depth. It cannot yet preserve arbitrary
cross-session maps, track a moving/deforming object, refit an entire curved stroke,
or provide a native ARKit implementation. The core types do not depend on ARCore;
an ARKit adapter is an extension point, not claimed as implemented.

Current image reacquisition uses one immutable root, not a learned multi-keyframe
atlas. This intentionally removes the prior learn-before-accept hazard. The
front end is still a plane-patch hypothesis; repeated textures or a curved patch
can fool image matching. Held-out geometry mitigates, not eliminates, that risk.
A full local factor-graph backend and device-calibrated covariance are not present.

Depth/pose/systematic noise thresholds are explicit engineering assumptions.
`conditionalSigmaM` and `GEOMETRY_SUPPORTED` are NOT calibrated accuracy guarantees.
The demo's CPU p95 is not motion-to-photon latency or physical error. No device
thermal/FPS/ground-truth measurement has been claimed from a compiler/CI run.

The adapter currently samples depth synchronously before bounded CV work; this
must be profiled on target phones before promising a per-frame budget. Depth
packing/pooling and a zero-copy host image provider are future optimizations, not
assumed to have been measured. All camera and JNI resources have explicit owners.

## Validation and distribution

`core:check` executes the actual Kotlin contracts without third-party test libs.
The suite covers random rigid rebases, freeze retention/capacity, depth edges,
immutable roots, duplicated/stale inputs, no-parallax, uncertainty, forged proposals
and held-out commit acceptance. Tests are synthetic and do not prove hardware
accuracy. The research branch workflow builds/lints the actual Android modules,
packages a separate demo and records exact dependency/license evidence.

See `compliance/REVIEW.md` and generated `build/compliance/` before distributing.
No commercial release is authorized by a passing build. No analytics or paid cloud
service, account, subscription or second AR device is needed for this SDK.
