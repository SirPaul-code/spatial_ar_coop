# ShowMe + StableAR Android integration

This document describes the Android-only integration on `showme/stablear-integration`.

StableAR source of truth is the exact tree from commit `38978da448b6610606e9145367b9b31328bb5cc0`. The research branch is not merged and the imported `sdk/` tree is intentionally left unchanged. ShowMe depends only on the StableAR `core`, `arcore`, and `vision` Gradle modules; the StableAR demo is not a ShowMe runtime dependency.

## Scope and ownership

ShowMe continues to own:

- the Android Activity and existing ARCore `Session`;
- the GL render loop and camera background;
- WebRTC media/data transport and Cloudflare signaling;
- browser UI and drawing protocol;
- stroke/arrow/circle geometry and rendering.

StableAR owns the spatial root of StableAR-controlled annotations:

- exact historical frame attachment;
- inverse-depth local surface fitting;
- retained local anchor coordinates;
- immutable clicked material-point identity;
- LK/ORB visual evidence;
- correction proposal and held-out validation;
- attachment tracking state.

No second camera, ARCore session, Cloud Anchor, Geospatial session, SLAM system, or second-device spatial system is created.

## Exact-frame invariant

The integration never interprets a ShowMe video frame ID as a StableAR `FrameRef.id`.

`StableArFrameRegistry` stores an explicit mapping:

```text
ShowMe video frame id + ShowMe epoch
        ->
StableAR CameraSample + FrameRef + PresentedImage
```

The binding is created on the GL/AR owner thread from the same `ARCore Frame.timestamp` immediately before that exposure is submitted to ShowMe's WebRTC video path.

A remote freeze/draw command resolves the exact `frameId` and `epoch`. It also verifies that the mapped StableAR `FrameRef` is still present in the SDK ledger. Missing, stale, wrong-epoch, or expired data fails closed; the current camera pose is never substituted for a historical frame.

Freeze acquires both ShowMe's historical frame lease and the matching StableAR `FrameRef` lease. Repeating freeze on the same frame does not extend the StableAR deadline. Resume, helper departure, call end, app pause, world reset, or a new session invalidates/releases the external frame authority.

## Pixel mapping

Browser drawing points are normalized in the visible WebRTC content area. For StableAR placement they are mapped through the `PresentedImage` corresponding to the exact source frame:

```text
browser normalized content pixel
        -> PresentedImage (same upright rotation as WebRTC)
        -> ARCore IMAGE_PIXELS
        -> exact historical StableAR CameraSample
```

This avoids assuming that browser/display pixels are sensor pixels.

ShowMe's existing `VideoDepthFrame`/`StrokePlacement` path remains responsible for the full stroke vertex geometry. The StableAR root is reconstructed independently from the normalized stroke root pixel. Relative stroke offsets are therefore calculated as `historicalWorldVertex - actualStableArInitialRoot`, not around an assumed 3D centroid. This guarantees that the first StableAR render reproduces the historical ShowMe stroke geometry exactly while later StableAR corrections move the coherent root.

## Pins, strokes, arrows, circles

A pin uses one StableAR attachment.

A multi-point drawing also uses one coherent StableAR attachment at the normalized drawing root/centroid. ShowMe preserves its existing historical world-space vertices as offsets from that exact StableAR root:

```text
current drawing vertex = StableAR world root + immutable relative vertex offset
```

The integration does not create one StableAR attachment per stroke vertex.

## Correction authority

Every drawing has mutually exclusive spatial ownership:

- `stableArAttachmentId != null`: StableAR is the correction authority;
- `stableArAttachmentId == null`: legacy ShowMe ARCore anchor + `StrokeSurfaceVerifier` is the correction authority.

The legacy verifier candidate filter excludes StableAR-owned drawings, and the repair application path rejects them again defensively. The two correction systems therefore cannot move the same annotation.

StableAR is enabled by default on this integration branch. Settings exposes an A/B switch between `StableAR` and `Legacy`. Switching clears current marks and advances the ShowMe world epoch so no frozen frame or pending command can cross spatial-authority modes.

## Visual tracking and occlusion

`StableArShowMeBridge` owns one `LocalSurfaceTracker` and one bounded background CV worker.

On the GL thread, the bridge projects StableAR's current 3D hypothesis into the current camera and supplies that as the predicted LK location. The worker then runs the SDK tracker. LK is the fast path; ORB/root matching is the reacquisition path.

The result carries the source attachment id/generation, ShowMe epoch, StableAR frame/timestamp, and integration lifecycle generation. Results are discarded after deletion, reset, pause/session invalidation, or generation mismatch.

A visual miss does not choose the nearest strong feature and does not move the annotation. StableAR retains the existing world hypothesis and transitions through its visibility/tracking state until valid evidence returns. Visual matching is evidence for the SDK correction proposal; it never directly teleports an ARCore anchor.

No screen-space EMA/lerp is applied. Rendering projects StableAR's current validated 3D point using the current ARCore camera.

## Thread ownership

`ArCoreAdapter` is constructed from ShowMe's existing `Session` on the GLSurfaceView/AR owner thread. Adapter methods that touch ARCore are called on that same thread.

Network/control threads only enqueue StableAR frame-lease commands. The renderer drains those commands on the owner thread. OpenCV work receives copied grayscale/context data; ARCore `Frame`, `Image`, and `Anchor` objects are not passed to the CV worker.

## Lifecycle

The integration handles:

- ARCore tracking loss: StableAR pending proposals are cleared and attachments retain their world hypothesis in a lost/occluded state;
- pause: pending worker generations and external frame mappings are invalidated; freeze release is marshalled to the owner thread;
- resume: new exact samples are captured before new remote placement is accepted;
- ARCore Session replacement/world reset: ShowMe marks and all StableAR attachments/frame references are removed/reset;
- helper disconnect: frozen ShowMe and StableAR frame leases are released while placed marks remain in the AR world;
- helper reconnect: new frame mappings are required for new annotations;
- call end/expiry: pending commands/leases are rejected and an owner-thread StableAR unfreeze cleanup is queued;
- clear/undo/remove: the owning StableAR attachment is removed before the ShowMe drawing disappears;
- frozen-frame expiry: both histories are bounded; repeating freeze does not renew the StableAR lease indefinitely.

## Diagnostics

`Connection details` includes the current spatial mode and StableAR telemetry. Per StableAR attachment the bridge records:

- attachment id;
- ShowMe source video frame id;
- StableAR generation/state;
- initial and current depth;
- correction displacement relative to the paired initial point on the same native anchor;
- accepted/rejected corrections;
- visual failures;
- LK/ORB method;
- visual residual;
- source-frame age;
- CV processing time;
- latest correction/rejection reason.

These values are diagnostics for A/B work. The paired initial point is not physical ground truth.

## Performance note

Correct frame provenance currently takes priority over deduplicating all sensor work. StableAR's known-good `ArCoreAdapter.capture()` retains its own depth/grayscale acquisition contract, while ShowMe's existing `VideoDepthFrame.capture()` retains the exact depth representation needed by the current full-stroke reconstruction/freeze path. They can overlap on published frames.

This is intentional for the first integration: changing the StableAR source or silently sharing differently-timestamped data would be a larger correctness risk. The next optimization step should profile real devices and introduce a single immutable same-exposure capture representation only if both consumers can keep their provenance contracts unchanged. No timestamp substitution is allowed as an optimization.

## Validation

Integration CI does not deploy the Worker and does not create a release/tag. It runs:

```text
ShowMe browser contract tests
real-browser WebRTC + annotation smoke test
StableAR :core:check
StableAR :arcore:assembleRelease
StableAR :vision:assembleRelease
ShowMe :showme:testDebugUnitTest
ShowMe :showme:lintDebug
ShowMe :showme:assembleDebug
APK package/signature verification
```

Automated tests cover exact live/delayed frame identity, stale/wrong-epoch rejection, bounded freeze/unfreeze, coherent pin/stroke/arrow/circle geometry, attachment removal/generation safety, occlusion retaining the world hypothesis, legacy mode availability, helper disconnect/reconnect lease cleanup, world reset, and session-end cleanup. Existing ShowMe tests cover command bounds, token/session lifecycle, frozen-frame history, draw validation, and world-epoch invalidation.

Physical ARCore behavior still requires real-device validation for camera motion, temporary tracking loss, hand occlusion, pause/resume, real depth quality, and visible drift. CI cannot establish millimetre/centimetre accuracy or any improvement percentage.

## Build

From the repository root:

```sh
cd android
./gradlew --no-daemon \
  :core:check \
  :arcore:assembleRelease \
  :vision:assembleRelease \
  :showme:testDebugUnitTest \
  :showme:lintDebug \
  :showme:assembleDebug
```

The integration APK is produced at:

```text
android/showme/build/outputs/apk/debug/showme-debug.apk
```

The application id remains `com.sirpaul.showme`.

## Accuracy statement

This branch intentionally makes no claim of millimetre accuracy, centimetre accuracy, zero drift, or a measured improvement over ARCore/other systems. The current physical observation motivating the integration is only that the standalone StableAR Android demo appeared materially more spatially fixed/stable than the previous ShowMe attachment behavior. Ground-truth measurement remains future validation work.
