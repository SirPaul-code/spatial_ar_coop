# XFeat LiteRT Android checkpoint — 2026-09-11

Branch: `stablear/multiplatform-sdk`

## Goal

Add a ready-made learned visual-correspondence backend for StableAR without replacing ARCore/ARKit/OpenXR geometry. XFeat is visual evidence only: the host projects the material 3D point into the current frame, XFeat searches locally around that prediction, and StableAR geometry decides whether/how to use the residual.

## Upstream model contract

Pinned model metadata lives in `sdk/models/xfeat/manifest.json`.

- upstream: `litert-community/xfeat-litert`
- revision: `bd421aad1ce6d25dc172cd9579cc13b9da21356f`
- artifact: `xfeat.tflite`
- size: `1,414,480` bytes
- SHA-256: `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`
- license: Apache-2.0
- input: float32 `[1,480,640,1]`, grayscale + per-image InstanceNorm
- output 0: descriptors `[1,64,60,80]`
- output 1: keypoint logits `[1,65,60,80]`
- output 2: reliability `[1,1,60,80]`

## Implemented in this checkpoint

### Shared C++

Existing `XFeatLocalTracker` is retained as the platform-independent matcher. It already has bounded coarse-to-fine search, a local descriptor fingerprint, ambiguity rejection, multi-view templates, and explicit-only template admission.

Two correctness fixes were added:

1. Android Clang rejected aggregate/default construction because `XFeatLocalTracker` has an explicit constructor. `stablear_xfeat_tracker` now explicitly constructs it with `XFeatPolicy{}`.
2. The C ABI previously let `beginFrame()` retain a borrowed descriptor pointer past the call. This is unsafe through JNI because `GetFloatArrayElements` may return temporary storage. `stablear_xfeat_tracker` now owns copies of the current descriptor and reliability maps until the next frame/clear/destroy.

Relevant commits:

- `77916efaa715234588a269891eb4c512d2020dca` — explicit XFeat construction fix
- `07811d256a3717842556015f476cd7f07212c6b8` — native ownership of current XFeat frame buffers

### Android model packaging

`native-vision-android` now has a deterministic `prepareXFeatModel` build task which downloads the exact pinned artifact, verifies SHA-256 and byte size, and packages it under `assets/models/xfeat.tflite`.

LiteRT dependency is pinned to `com.google.ai.edge.litert:litert:2.2.0`.

Commit: `377abf45a885c39be3b2e9ef6ba35a77f3a52863`.

### Android runtime prototype

`XFeatLiteRtTracker.kt` now:

- creates one persistent LiteRT `CompiledModel`, GPU preferred;
- creates persistent input/output tensor buffers once;
- resizes grayscale to 640x480 and mirrors the shared InstanceNorm contract;
- runs XFeat;
- reads descriptor output 0 and reliability output 2;
- feeds them into the shared C++ `XFeatLocalTracker` through JNI;
- supports root capture, bounded tracking, explicit multi-view template admission, remove/clear;
- enforces single-worker-thread ownership.

JNI XFeat bridge commit: `441093ce8c38660d44ffb83796505fc9a65dcbcd`.
Android LiteRT runtime commit: `282e2556ee0689d5e170e8e08d16267489a1f2e2`.
Android enum exposure commit: `de920af7e4ddbcefc58103377312f959cef69894`.

## Deliberate prototype limitations

This is not yet the final shipping data path.

The public Kotlin LiteRT API currently reads the approximately 307k-float descriptor tensor into a `FloatArray`, then JNI copies/borrows it for the native call, and the C ABI copies the current map for safe lifetime. This is acceptable for proving end-to-end correctness but is too much memory traffic for the final high-rate mobile backend.

Do not optimize this by reintroducing borrowed cross-call pointers. The shipping solution should make LiteRT output memory directly consumable by native matching, using persistent native/host buffers or a supported zero-copy buffer type.

The CPU fallback is not a performance target for this XFeat graph. Production policy should prefer LiteRT GPU and fall back to the existing OpenCV tracker when XFeat GPU initialization/inference is unavailable.

No code in this checkpoint gives ML authority to mutate world anchors directly. XFeat returns image-space evidence plus score/ambiguity/reliability/sigma; geometry must gate corrections.

## CI state

The pre-XFeat Android CI failure at head `0fad1fb...` was diagnosed from the uploaded Android diagnostics artifact. Native Linux, Apple and Unity jobs were green; Android failed only on the explicit-constructor Clang error described above.

After the fixes/runtime commits, GitHub Actions run `34619815226` for head `07811d...` was still pending at the time this checkpoint was written because the immediately previous run was being superseded by branch concurrency. Do not record this checkpoint as CI-green until the newest run completes.

## Precise next steps

1. Let the newest `stablear-multiplatform` run complete. If Android fails, download the `stablear-android-diagnostics-*` artifact and fix the exact compiler/Gradle/LiteRT issue rather than guessing.
2. Once Android assembles, inspect `native-vision-android-release.aar` and assert that `assets/models/xfeat.tflite` exists and matches the pinned SHA-256. Add that assertion to CI.
3. Add an Android instrumentation/device smoke test that initializes `CompiledModel(GPU)`, runs one deterministic fixture frame, verifies output shapes/finiteness, and performs one known local match.
4. Add a build/test parity fixture proving Kotlin preprocessing matches `prepareXFeatInput` numerically.
5. Replace the prototype descriptor `FloatArray` roundtrip with a native/persistent LiteRT buffer path. Preserve frame lifetime and thread ownership contracts.
6. Wire XFeat observations into the existing StableAR held-out reprojection/depth acceptance pipeline. Carry source frame id, timestamp, epoch/generation and `XFeatMatch.sigma_px` into observation metrics.
7. Admit new view templates only after independent geometric acceptance; never learn directly from the tracker's own unchecked prediction.
8. Add the iOS adapter using the same `.tflite` artifact and the same shared C++ matcher. Prefer LiteRT Metal/GPU where supported; keep ARKit as geometry authority.
9. Benchmark on real Android hardware: inference time, total frame processing time, descriptor transfer/copy time, matcher time, memory bandwidth, thermals, and tracking accuracy versus existing LK/ORB.
10. Test the real target failures: oblique viewpoint, 180-degree orbit, temporary occlusion, repeated texture, low texture, specular surfaces, motion blur, depth dropout, and ARCore relocalization jumps.

## Resume rule

Before continuing, read `sdk/STATUS.md`, `sdk/WORKLOG.md`, this checkpoint, and the newest GitHub Actions run for `stablear/multiplatform-sdk`. Do not touch the frozen Android-only reference product or unrelated branches.
