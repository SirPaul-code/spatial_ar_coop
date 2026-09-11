# StableAR Apple preview

`build_xcframework.sh` now builds two native artifacts for iOS device + simulator:

- `StableARNative.xcframework` — StableAR C++20 geometry/session core;
- `StableARXFeatNative.xcframework` — OpenCV-independent XFeat preprocessing/matcher/staged-template C ABI.

`Sources/StableARApple` remains the Swift façade and host-owned ARKit adapter. ARKit world tracking remains the VIO/SLAM provider. StableAR converts only camera local axes from ARKit (`+Y up/-Z forward`) to StableAR (`+Y down/+Z forward`). `sceneDepth` is optional frame-aligned metric depth; `smoothedSceneDepth` is a fallback, not independent evidence.

`StableARXFeatNative.xcframework` does **not** yet execute the `.tflite` model. It exists so the eventual Apple LiteRT runtime can feed the same shared C++ matcher used on Android without pulling OpenCV or reimplementing correspondence in Swift.

## Planned Apple XFeat runtime policy

Current LiteRT documentation advertises iOS CPU and Metal support, but 2026 upstream issue history includes physical-device accelerator/prebuilt/registration problems and at least one model-specific FP16 Metal numerical failure. Therefore StableAR should:

1. establish pinned-XFeat output parity on CPU first;
2. attempt Metal only behind runtime/capability checks;
3. run finite-output + deterministic parity self-test before selecting Metal;
4. fall back to CPU on creation/inference/parity failure;
5. keep LK/ORB/host-only operation available when ML is unavailable or thermally undesirable.

Do not make universal iOS Metal latency/FPS claims from upstream model-card data.

This preview also does not claim unrestricted visionOS camera support. Vision Pro camera access has separate entitlement/product constraints and must be reviewed for the actual distribution model.
