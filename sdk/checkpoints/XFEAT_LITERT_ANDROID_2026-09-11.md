# XFeat + LiteRT Android / portable matcher checkpoint — 2026-09-11

Branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`
Documented code checkpoint: `e461e81c375e3875237ab0286718af1d4a4f7939`

## Outcome

The XFeat idea is no longer just research. StableAR now has:

- a pinned Apache-2.0 XFeat LiteRT model;
- an Android LiteRT execution path;
- a runtime-neutral C++ learned-correspondence matcher;
- arbitrary-tap 5x5 dense descriptor fingerprinting;
- source/model coordinate mapping and source-space uncertainty;
- LK/ORB fallback;
- post-geometric-acceptance staged multi-view template admission;
- geometric view direction + perspective-scale metadata;
- an OpenCV-independent `StableAR::xfeat` target;
- an iOS device+simulator `StableARXFeatNative.xcframework` build path.

XFeat still does **not** replace ARCore/ARKit/OpenXR VIO and never directly moves an attachment.

## Model pin

- repo: `litert-community/xfeat-litert`
- revision: `bd421aad1ce6d25dc172cd9579cc13b9da21356f`
- file: `xfeat.tflite`
- bytes: `1,414,480`
- SHA-256: `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`
- input: `[1,480,640,1]` Float32 grayscale after per-image InstanceNorm
- dense descriptors: `[1,64,60,80]`
- reliability: `[1,1,60,80]`

`sdk/models/xfeat/manifest.json` is the build integrity record. Android CI checks the exact asset hash and size inside the release AAR.

## Android implementation

`XFeatLiteRtTracker` owns one persistent LiteRT `CompiledModel` and reusable tensor buffers on its vision worker. GPU is attempted first and CPU is the fallback.

Coordinates are explicit:
- caller/StableAR uses source CPU-image pixels;
- model uses fixed 640x480 pixels;
- wrapper uses half-pixel resize transforms in both directions;
- returned point and `sigmaPx` are source-image units;
- XFeat patch-consensus residual remains a fixed-model-raster quality metric rather than being mislabeled as a source-pixel geometric error.

The placement root uses the actual grayscale image timestamp. No synthetic visual frame id is generated.

When there are zero active attachments, the demo does not schedule XFeat inference.

## Anti-drift multi-view bank

A raw XFeat match is never admitted as a new template.

Safe flow:

```text
inference frame A
   -> XFeat match
   -> copy/stage local descriptor patch from A
   -> inert token
   -> StableAR geometry + held-out validation on AR owner thread
        -> accepted: commit exact staged patch
        -> rejected/stale: discard token
```

Properties:
- at most one pending candidate per attachment;
- newer candidate invalidates older token;
- token must match attachment id;
- commit does not read the current descriptor map;
- changing to frame B before commit still commits the snapshot from frame A;
- remove/reset purges pending candidates.

The shared C++ contract tests these conditions.

## Viewpoint metadata

For each current observation the lab derives, in anchor coordinates:

- `viewDirection = normalize(cameraPosition - materialPoint)`;
- `scale = clamp(rootPointCameraZ / currentPointCameraZ, 0.5, 2.0)`.

The bank uses direction for same-view replacement/deduplication and uses relative template scale to resize descriptor-patch offsets. This is intended to keep the bounded max-8 bank diverse across orbit/distance changes.

## Packaging fixes

The official OpenCV Android AAR already supplies `libc++_shared.so`. StableAR's vision AAR excludes its duplicate copy, preventing `mergeDebugNativeLibs` collisions. CI checks that the StableAR vision AAR does not package another shared STL.

The learned matcher was split from the OpenCV frontend at `e461e81...`:
- `StableAR::xfeat` — XFeat preprocessing/matching/C ABI, no OpenCV;
- `StableAR::vision` — classical LK/ORB OpenCV frontend;
- Android JNI explicitly links both;
- Apple can build XFeat without OpenCV.

## CI evidence

Fully green software baseline:
- commit `147abb6d32d5bb98cc23c8ae31cb54d8de59fbb2`
- workflow run #33 / `34625962958`
- Linux native, Android AAR/reference demo, Apple and Unity all green.

Shared staged-template C++ contract was green in later native-linux CI.

For code checkpoint `e461e81c375e3875237ab0286718af1d4a4f7939`, workflow run #39 / `34628410948` is the authoritative verification run. Its Apple job is already green, proving device+simulator XFeat XCFramework compilation; inspect final Linux/Android status before marking the whole checkpoint green.

## Apple runtime decision

`StableARXFeatNative.xcframework` is the portable matcher, **not** XFeat neural execution yet.

Current LiteRT documentation advertises iOS CPU and Metal. However 2026 upstream issue history includes physical-device Metal accelerator registration/packaging failures and GPU FP16 numerical failures on some graphs/devices. This is not proof that XFeat itself fails, but it changes the release policy:

1. iOS CPU backend establishes reference output parity first.
2. Metal is tried only behind capability/runtime checks.
3. Run finite-output + parity self-test on the pinned XFeat graph.
4. Fall back to CPU automatically on accelerator creation, inference, finiteness or parity failure.
5. Never publish one universal Metal latency number.

Useful upstream references:
- https://github.com/google-ai-edge/LiteRT
- https://github.com/google-ai-edge/LiteRT/issues/8787
- https://github.com/google-ai-edge/LiteRT/issues/9249
- https://github.com/google-ai-edge/LiteRT/issues/6745

## Remaining technical risks

- Android still copies the ~307k-float dense descriptor output through `FloatArray`/JNI each inference; replace only after parity is frozen.
- No physical smartphone A/B data yet for false locks, occlusion/reacquisition, thermal behavior or power.
- No calibrated learned-correspondence covariance model yet; current `sigmaPx` is deliberately conservative.
- Generic StableAR observation fields are still partly LK-named; make the source/quality type explicit additively without casually changing C ABI layouts.
- Current material-point solver corrects only depth along the immutable original ray. Tangential correction requires the planned bounded fixed-lag local optimizer.
- iOS `.tflite` runtime is pending even though the portable matcher now builds for Apple.

## Next exact work

1. Finish run #39 and fix any red Linux/Android job before extending code.
2. Add deterministic recorded-frame parity fixtures and physical Android A/B harness.
3. Replace Android descriptor-map copies with a native/persistent buffer path after parity.
4. Integrate iOS LiteRT CPU runtime, then guarded/self-tested Metal acceleration, reusing `StableARXFeatNative`.
5. Introduce source-aware visual-evidence quality semantics additively.
6. Implement fixed-lag local point/surfel optimization.
7. Add capability-aware multi-camera/temporal factors.

No current result justifies a physical mm/cm accuracy claim or guaranteed mobile FPS.
