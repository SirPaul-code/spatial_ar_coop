# StableAR worklog

Branch: `stablear/multiplatform-sdk`
Frozen reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`
Last updated: 2026-09-11
Documented code checkpoint: `e461e81c375e3875237ab0286718af1d4a4f7939`

## XFeat checkpoint chronology

- `0fad1fb82e77476542e329583408e5b278c95e98` — completed runtime-neutral C++ XFeat matcher/preprocessing translation units after correcting the truncated first push.
- Branch then gained Android LiteRT integration/model pinning/checkpoint work from subsequent commits. Model is pinned to `litert-community/xfeat-litert@bd421aad1ce6d25dc172cd9579cc13b9da21356f`, 1,414,480 bytes, SHA-256 `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`, Apache-2.0.
- `b6323cd9887a26eca7d6758c5fbdb7f0d9f62591` — removed duplicate StableAR `libc++_shared.so` from the vision AAR and added model/STL payload integrity checks. The earlier demo merge failure was caused by duplicate shared STL packaging, not XFeat inference.
- `f4be2008...` — corrected source CPU-image pixel coordinates to/from fixed 640x480 model raster with half-pixel resize convention.
- `147abb6d32d5bb98cc23c8ae31cb54d8de59fbb2` — exact grayscale source timestamp + XFeat source-space sigma wired into `VisualObservation`. Workflow run #33 (`34625962958`) fully green.
- `6027eb6f415d8946d94d608b779727879a67cd80` — do not schedule learned inference when no StableAR attachments exist.
- `125bf04b6538d7622b2b0d8956d57fceb06e52bc` — shared C++ staged-template API: snapshot exact-frame patch, opaque token, one pending candidate/attachment, commit/discard after independent decision. Contract tests stale token, wrong attachment, changed-current-frame and removal.
- `57623c948241340d7a9ac1c6fbbbc3edc83df442` — JNI/Kotlin/demo end-to-end staged admission: `XFeat match -> stage -> StableAR observe -> commit/discard`.
- `405f9477ea470cce62bd611511b0609cbc260896` — keep XFeat descriptor-consensus quality in fixed model-raster pixels while returning `x/y` and `sigmaPx` in source-image units for geometry.
- `dba74bba90c098a3340a990d84288a490227e1da` — feed target-to-camera direction and clamped `rootZ/currentZ` perspective scale into XFeat. This lets the max-8 template bank represent materially different viewpoints instead of redundant frontal patches.
- `e461e81c375e3875237ab0286718af1d4a4f7939` — split portable learned matcher into `StableAR::xfeat`, make OpenCV LK/ORB optional, explicitly link both on Android, and build `StableARXFeatNative.xcframework` for iOS device+simulator. Apple job in workflow run #39 (`34628410948`) is green; inspect Linux/Android final status before calling the whole run green.

## Current architecture

```text
host ARCore / ARKit / OpenXR VIO
                |
       predicted material UV
                |
        +-------+-------+
        |               |
      LK/ORB          XFeat LiteRT
    classical        learned dense map
        |               |
        +------ visual evidence ------+
                                       |
                             StableAR geometry
                       parallax/uncertainty/travel
                         + held-out verification
                                       |
                               corrected attachment
                                       |
       accepted XFeat patch -> staged token -> commit template
       rejected/stale patch ----------------> discard
```

The root visual identity is immutable. XFeat has no authority to move world/anchor state directly.

## Android state

Source-complete and CI-buildable as of the last full green baseline; latest refactor run still needs final Android status.

Implemented:
- persistent LiteRT `CompiledModel` and tensor buffers;
- GPU preferred with CPU fallback;
- exact model revision/hash/size verification and AAR asset packaging;
- source/model coordinate conversion;
- source-space pixel uncertainty;
- bounded owner worker semantics in the lab integration;
- no inference with zero anchors;
- post-acceptance staged multi-view templates;
- viewpoint direction and scale metadata;
- LK/ORB fallback;
- duplicate STL avoidance.

Still prototype-quality:
- descriptor/reliability map crosses public `FloatArray` and JNI copies each inference;
- no physical Android XFeat parity/thermal/false-lock benchmark yet;
- backend selection is preference/fallback, not a calibrated per-device benchmark cache yet.

## Apple state

Core ARKit integration remains compile-tested. At `e461e81...` the portable XFeat matcher is also built into a separate `StableARXFeatNative.xcframework` without requiring OpenCV; run #39 Apple job is green.

The actual XFeat `.tflite` execution runtime is **not** yet integrated on iOS. 2026 LiteRT research found official CPU/Metal support statements but also recent third-party physical-device/prebuilt/accelerator-registration and FP16 GPU problems. Apple implementation must therefore establish CPU parity first and treat Metal as a self-tested optional accelerator with fail-closed CPU fallback.

## Do next

1. Finish/inspect run #39. Fix Linux/Android if needed; do not layer additional runtime changes over a red checkpoint.
2. Add a physical Android validation harness/fixture export so the exact same captured grayscale frames can be replayed through official/reference XFeat and StableAR XFeat; measure UV error, false-lock/reacquisition and latency/thermal behavior.
3. Replace Android FloatArray/JNI map transport with a native/persistent buffer path after parity is frozen.
4. Add iOS LiteRT runtime adapter around the existing `StableARXFeatNative` matcher. CPU correctness first, Metal runtime/output parity self-test, CPU fallback.
5. Make visual evidence semantics source-aware additively instead of overloading LK-named fields; preserve existing C ABI layouts.
6. Implement bounded fixed-lag local material-point/surfel optimization; current 1D original-ray correction is the next mathematical ceiling.
7. Add calibrated multi-camera/temporal observations only after the estimator can consume source-aware factors.

Never claim physical accuracy/FPS from CI or model-card benchmarks alone.
