# StableAR multiplatform SDK handoff/status

Last updated: 2026-09-11
Target branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`
Latest software-verified code checkpoint: `e461e81c375e3875237ab0286718af1d4a4f7939`
Verification: workflow run #39 / `34628410948` — **fully green**

## Read this first

StableAR is a **local static-material attachment stability layer above host AR/XR VIO/SLAM**. ARCore/ARKit/OpenXR own camera pose, IMU fusion, world tracking and relocalization. StableAR owns exact-frame evidence, local metric depth/surface evidence, immutable material-point identity, visual correspondence, uncertainty and bounded correction.

Work only on `stablear/multiplatform-sdk`. Do not modify the frozen Android-only reference or product branches. Canonical StableAR camera coordinates are right-handed `+X right, +Y down, +Z forward`.

## CI status

Code checkpoint `e461e81c375e3875237ab0286718af1d4a4f7939` passed the complete `stablear-multiplatform` workflow run #39 (`34628410948`):

- scope / frozen-product isolation: PASS;
- native Linux static/shared/package consumer: PASS;
- sanitizers + warnings-as-errors: PASS;
- OpenCV classical vision + XFeat C++/C contracts: PASS;
- OpenXR coordinate contract: PASS;
- entitlement/export contract: PASS;
- Android native core AAR: PASS;
- Android XFeat/OpenCV vision AAR: PASS;
- original Android reference/demo buildability: PASS;
- Android exact XFeat model payload + no duplicate StableAR `libc++_shared.so`: PASS;
- Apple core + XFeat device/simulator XCFramework build: PASS;
- ARKit Swift typecheck: PASS;
- Unity ABI: PASS.

This is software/build verification only. It is not a physical accuracy, FPS, thermal or battery claim.

## XFeat decision and model pin

**XFeat + StableAR geometry** is the first mobile learned-correspondence backend. It supplements LK/ORB and host VIO; it never directly moves an attachment.

Pinned model:
- upstream: `litert-community/xfeat-litert`;
- revision: `bd421aad1ce6d25dc172cd9579cc13b9da21356f`;
- artifact: `xfeat.tflite`;
- size: `1,414,480` bytes;
- SHA-256: `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`;
- license: Apache-2.0;
- input: Float32 `[1,480,640,1]`, grayscale + per-image InstanceNorm;
- descriptors: `[1,64,60,80]`; reliability: `[1,1,60,80]`.

`sdk/models/xfeat/manifest.json` is the build integrity source and Android CI verifies the packaged model.

## Shared matcher — implemented and CI green

`sdk/native-vision` exposes:
- `StableAR::xfeat` — OpenCV-independent XFeat preprocessing/matching/staged-template C ABI;
- `StableAR::vision` — optional classical LK/ORB OpenCV frontend.

XFeat matcher implements arbitrary tapped points, bilinear 64-D dense descriptor sampling using XFeat/grid-sample coordinate semantics, a reliability-filtered 5x5 local fingerprint, bounded host-prediction search, coarse-to-fine refinement, distinct second-peak ambiguity rejection, patch consensus/inliers, conservative sigma, immutable root identity and a bounded max-8 view template bank.

## Anti-drift template admission — implemented

Never self-learn directly from an XFeat match.

Current path:

`XFeat match -> stage exact-frame descriptor patch -> StableAR geometric/held-out decision -> commit OR discard`

Properties:
- one pending candidate per attachment;
- newer stage invalidates older token;
- attachment id and token must both match;
- commit uses the descriptor snapshot captured at stage time even if the current frame has changed;
- rejected/stale observations are discarded;
- remove/reset purges pending candidates.

The shared C++ contract tests these conditions.

## Android / ARCore — source + CI complete, physical validation pending

Implemented:
- persistent LiteRT `CompiledModel` and tensor buffers;
- GPU attempt + CPU fallback;
- exact grayscale image timestamp as visual frame identity;
- source CPU-image <-> fixed 640x480 model half-pixel coordinate mapping;
- source-image `x/y` and source-image `sigmaPx` for geometry;
- XFeat descriptor-consensus residual retained in fixed model-raster units only as matcher quality;
- no LiteRT inference when there are zero active attachments;
- staged template commit/discard on the same vision worker;
- geometric target-to-camera view direction;
- perspective scale `rootTargetCameraZ/currentTargetCameraZ`, clamped `[0.5,2.0]`;
- LK/ORB fallback;
- exact model hash/size AAR integrity check;
- StableAR vision AAR excludes duplicate `libc++_shared.so`; current packaging uses the OpenCV AAR's shared STL copy.

Known prototype limitation: dense descriptor/reliability output still crosses LiteRT -> Kotlin `FloatArray` -> JNI. The descriptor map is roughly 307k floats per inference. Replace this with native/persistent buffers only after parity is frozen.

## Apple / ARKit — portable XFeat matcher CI green, neural runtime pending

`build_xcframework.sh` now creates for both iPhoneOS and simulator:
- `StableARNative.xcframework` — StableAR core;
- `StableARXFeatNative.xcframework` — OpenCV-independent XFeat matcher/C ABI.

Run #39 Apple is green, so this portable learned matcher is genuinely Apple-buildable. The actual `xfeat.tflite` execution runtime is **not yet integrated on iOS**.

2026 LiteRT research shows official iOS CPU/Metal support statements but also recent third-party physical-device accelerator/prebuilt/registration and FP16 GPU issues. Apple runtime policy is therefore:
1. CPU correctness/parity baseline;
2. Metal optional after successful creation plus finite-output/parity self-test on the pinned XFeat model;
3. automatic CPU fallback on accelerator/inference/parity failure;
4. no universal Metal performance claim.

Do not redesign the SDK around legacy `tflite::Interpreter` merely to avoid current packaging friction; upstream treats that path as maintenance-only.

## Geometry ceiling

Current StableAR material correction remains **1D along the immutable original click ray**. Better XFeat correspondence improves evidence but cannot correct tangential XYZ error.

Next mathematical upgrade should be a bounded fixed-lag local material-point/surfel optimizer in anchor coordinates:
- optional tiny keyframe pose deltas with strong host-VIO priors;
- source-aware reprojection factors from LK/XFeat/ORB/multi-camera observations;
- metric depth/plane/surfel factors;
- robust loss + anisotropic covariance;
- bounded keyframes/marginalization;
- existing travel/epoch/generation/held-out protections.

## Multi-camera / temporal roadmap

Use a capability-aware observation graph, not a stereo-specific anchor type. Physical cameras, temporal keyframes, depth/ToF/LiDAR/mesh and learned/classical correspondence should contribute calibrated observations carrying sensor identity, exact timestamp, intrinsics/distortion, extrinsics/pose and source covariance.

Spatial phone-camera baseline is most useful close up; temporal device motion can provide a much larger baseline.

## Claims that remain forbidden

- no physical mm/cm stability/accuracy claim;
- no guaranteed XFeat latency/FPS from upstream benchmarks;
- no measured thermal/battery claim;
- no real-device proof of long-occlusion reacquisition yet;
- no iOS XFeat LiteRT runtime/device parity yet;
- no Quest physical validation;
- no full 3D/tangential correction yet;
- no moving/deforming-object guarantee;
- no cross-session persistent-map guarantee.

## Exact next-agent procedure

1. Fetch live branch HEAD and read `sdk/AGENTS.md`, this file, `TRACKING_RESEARCH.md`, `WORKLOG.md`, `VALIDATION.md` and `sdk/checkpoints/XFEAT_LITERT_ANDROID_2026-09-11.md`.
2. Treat `e461e81c375e3875237ab0286718af1d4a4f7939` + run #39 as the latest fully software-verified code checkpoint. Later commits may be documentation-only; inspect the live diff before code changes.
3. Immediate correctness gate: physical Android A/B harness — host-only vs LK/ORB vs XFeat — across viewpoint/distance changes, low/repetitive texture, blur, lighting, occlusion/reacquisition and thermal soak.
4. Freeze deterministic captured-frame parity fixtures, then replace Android FloatArray/JNI descriptor copies with native/persistent buffers.
5. Integrate Apple LiteRT behind a replaceable runtime adapter: CPU baseline, Metal self-test/fallback, reuse `StableARXFeatNative`, no Swift matcher duplication.
6. Introduce source-aware visual-evidence quality semantics additively; do not casually change existing C ABI struct layouts.
7. Implement the bounded fixed-lag local point/surfel optimizer.
8. Add capability-aware physical multi-camera/temporal observations and repeat device validation before upgrading claims.

No synthetic test, CI run or upstream benchmark alone justifies physical accuracy, FPS or commercial-performance claims.
