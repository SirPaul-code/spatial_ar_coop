# StableAR multiplatform SDK handoff/status

Last updated: 2026-09-11
Target branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`
Current documented code checkpoint: `e461e81c375e3875237ab0286718af1d4a4f7939`

## Read this first

StableAR is a **local static-material attachment stability layer above a host AR/XR runtime**. ARCore/ARKit/OpenXR remain responsible for VIO, IMU fusion, world tracking and relocalization. StableAR owns exact-frame evidence, local metric surface/depth evidence, immutable material-point identity, visual correspondence, uncertainty and bounded correction.

Work only on `stablear/multiplatform-sdk`. Do not modify the frozen Android-only reference or product branches. Canonical StableAR camera coordinates are right-handed `+X right, +Y down, +Z forward`.

## Verified baselines

- `147abb6d32d5bb98cc23c8ae31cb54d8de59fbb2` — workflow run #33 / `34625962958` fully green across scope, Linux native, Android AAR/reference demo, Apple and Unity. This includes pinned Android XFeat LiteRT runtime, source/model coordinate correction, exact source timestamp and source-space sigma.
- `125bf04b6538d7622b2b0d8956d57fceb06e52bc` — safe staged XFeat template API/contract added; later native-linux run #36 verified the shared C++ contract green.
- `57623c948241340d7a9ac1c6fbbbc3edc83df442` — Android end-to-end staged template admission after StableAR acceptance.
- `405f9477ea470cce62bd611511b0609cbc260896` — separates fixed-640x480 XFeat matcher-quality residuals from source-image geometry units.
- `dba74bba90c098a3340a990d84288a490227e1da` — feeds target-to-camera view direction plus conservative root-depth/current-depth scale to XFeat.
- `e461e81c375e3875237ab0286718af1d4a4f7939` — isolates XFeat matcher from OpenCV and builds an Apple device+simulator XFeat XCFramework. Workflow run #39 / `34628410948` is the verification run for this code checkpoint; inspect its final status before upgrading the checkpoint to fully green.

All branch moves must remain non-force.

## Native core — DONE / software-verified

`sdk/native` contains the C++20 geometry/session core and C ABI v1: rigid geometry, intrinsics, bounded exact-frame history/freeze leases, immutable root identity, robust inverse-depth/local-surface initialization, original-ray refinement, parallax/uncertainty/travel gates, held-out commit validation, epoch/generation/timestamp rejection and platform-neutral anchor storage.

This remains **software-verified only**. No CI result is a physical mm/cm accuracy claim.

## Learned correspondence — XFeat

Decision: **XFeat + StableAR geometry** is the first mobile ML backend. It supplements LK/ORB and host VIO; it never directly moves an anchor.

Pinned model:
- upstream: `litert-community/xfeat-litert`;
- revision: `bd421aad1ce6d25dc172cd9579cc13b9da21356f`;
- artifact: `xfeat.tflite`;
- size: `1,414,480` bytes;
- SHA-256: `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`;
- license: Apache-2.0;
- input: Float32 `[1,480,640,1]`, grayscale + per-image InstanceNorm;
- dense descriptors: `[1,64,60,80]`; reliability: `[1,1,60,80]`.

`sdk/models/xfeat/manifest.json` is the build-time integrity source. Android AAR inspection verifies the packaged model hash/size.

### Shared matcher — implemented

`sdk/native-vision` now provides an OpenCV-independent `StableAR::xfeat` target and the existing optional `StableAR::vision` LK/ORB target.

XFeat matcher properties:
- arbitrary tapped point through bilinear 64-D dense descriptor sampling;
- official XFeat-style normalized/grid-sample coordinate convention, not naive nearest `pixel/8`;
- reliability-filtered 5x5 local descriptor fingerprint;
- bounded host-prediction search, coarse-to-fine refinement;
- distinct second-peak ambiguity rejection;
- patch-consensus/inlier validation;
- conservative sigma output;
- bounded max-8 template bank;
- target-to-camera view metadata and perspective scale support;
- immutable original root.

### Anti-drift template admission — implemented

Do **not** automatically self-learn from a tracker match.

Current flow is:

`XFeat match -> stage exact-frame local patch -> StableAR geometric/held-out decision -> commit OR discard token`

Staging copies the local descriptor patch while the exact inference map is current. At most one candidate exists per attachment. A newer stage invalidates the older token. Commit checks both attachment id and token. Changing the current frame before commit does not change the staged descriptor snapshot. Remove/reset purge pending candidates.

The C++ contract explicitly tests stale token, wrong attachment, frame-change-before-commit and removal cases.

## Android / ARCore — source/CI complete, physical validation pending

Android learned path exists in `sdk/native-vision-android` and the lab demo:
- LiteRT `CompiledModel` is persistent;
- input/output tensor buffers are persistent;
- GPU is attempted first, CPU is the fallback;
- exact grayscale image timestamp is the visual frame identity;
- source CPU-image coordinates are mapped to/from fixed 640x480 model coordinates with half-pixel resize convention;
- `x/y` and `sigmaPx` returned to StableAR are source-image units;
- XFeat local descriptor-consensus residual remains in fixed model-raster units and is only a matcher-quality gate;
- no inference is scheduled when there are zero active attachments;
- XFeat staged template resolution runs on the same owner worker;
- LK/ORB remains fallback;
- AAR excludes a duplicate StableAR `libc++_shared.so`; the official OpenCV AAR supplies the shared STL in the current packaging;
- CI verifies the exact model asset and absence of a second StableAR STL copy.

Known Android prototype limitation: descriptor/reliability outputs still use public `FloatArray` readback plus JNI copying. Correctness first; shipping path should move to native/shared buffers to reduce ~307k-float-per-frame copies and GC/memory bandwidth.

## Apple / ARKit — geometry + portable XFeat matcher compile path, LiteRT execution pending

Existing `StableARNative.xcframework` contains the StableAR core and ARKit Swift adapter remains host-owned.

At code checkpoint `e461e81...`, XFeat no longer requires OpenCV at CMake configure time. `build_xcframework.sh` also builds `stablear_xfeat` for `iphoneos` and `iphonesimulator` and creates `StableARXFeatNative.xcframework`. This is the shared matcher only; the actual `.tflite` execution runtime is not yet integrated on Apple.

Current 2026 LiteRT research says iOS CPU and Metal are supported in product documentation, but there have also been physical-device/prebuilt/accelerator-registration and FP16 GPU issues. Therefore Apple runtime policy is:
1. CPU correctness baseline first;
2. Metal preferred only after runtime registration + finite-output + parity self-test on the actual device/runtime/model;
3. automatic CPU fallback on any accelerator failure or invalid output;
4. no universal Metal performance claim.

Do not introduce the legacy `tflite::Interpreter` API as the new StableAR architecture merely to bypass current packaging friction; LiteRT upstream now treats it as maintenance-only.

## Viewpoint-aware template bank

For each observation, the Android lab computes in anchor coordinates:
- view direction = normalized `(cameraPositionInAnchor - materialPointInAnchor)`;
- perspective scale = `rootPointCameraZ / currentPointCameraZ`, conservatively clamped to `[0.5, 2.0]`.

The scale adjusts descriptor-patch offsets between views. Direction allows the bounded bank to replace same-direction templates rather than fill all 8 slots with near-identical views. This is geometric metadata, not ML inference.

## Geometry ceiling

Current StableAR correction is still **1D along the immutable original clicked ray**. Better correspondence improves the evidence, but it cannot correct tangential material-point error.

Next accuracy upgrade should be a bounded fixed-lag local optimizer in anchor coordinates, not a second global SLAM:
- material point or local surfel state;
- optional tiny per-keyframe pose deltas with strong host-VIO priors;
- source-aware reprojection factors from LK/XFeat/ORB/multi-camera observations;
- metric depth/surface factors;
- robust loss and anisotropic covariance;
- bounded keyframes/marginalization;
- existing travel, epoch/generation and held-out protections.

## Multi-camera / temporal observations

Roadmap remains a capability-aware observation graph, not a stereo-specific anchor type. Physical camera observations, temporal keyframes, raw depth/ToF/LiDAR/mesh and visual correspondence should all carry exact sensor timestamp, intrinsics/extrinsics, sensor identity and calibrated covariance. Spatial phone-camera baseline is most useful close up; temporal motion can provide a much larger baseline.

## Claims that are NOT yet allowed

- no physical mm/cm stability/accuracy claim;
- no guaranteed XFeat latency/FPS claim from model-card data;
- no measured thermal/battery claim;
- no proof of correctness after long occlusion/reacquisition on real phones;
- no iOS LiteRT runtime/device parity yet;
- no Quest physical validation;
- no full 3D/tangential correction yet;
- no moving/deforming-object guarantee;
- no cross-session persistent-map guarantee.

## Exact next-agent procedure

1. Fetch live `stablear/multiplatform-sdk` HEAD and read `AGENTS.md`, this file, `TRACKING_RESEARCH.md`, `WORKLOG.md`, `VALIDATION.md` and `sdk/checkpoints/XFEAT_LITERT_ANDROID_2026-09-11.md`.
2. Inspect workflow run #39 (`34628410948`) for code checkpoint `e461e81...`; fix any regression before extending the runtime.
3. Retain Android physical A/B validation as the immediate correctness gate: host-only vs LK/ORB vs XFeat, including rotations, distance changes, low/repetitive texture, blur, lighting, occlusion/reacquisition and thermal soak.
4. Replace Android FloatArray/JNI descriptor-map copies with a native/persistent buffer path only after output parity is locked.
5. Integrate Apple LiteRT behind a replaceable runtime adapter: CPU baseline, Metal self-test, fail-closed/fallback behavior. Reuse `StableARXFeatNative`; do not duplicate matching logic in Swift.
6. Clean up source-aware visual evidence semantics additively; do not change existing C ABI struct layouts casually.
7. Implement the bounded fixed-lag local point/surfel optimizer.
8. Then add capability-aware physical multi-camera/temporal observations and run device validation before updating claims.

No synthetic test, CI run or upstream benchmark alone justifies a physical accuracy, FPS or commercial-performance claim.
