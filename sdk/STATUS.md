# StableAR multiplatform SDK handoff/status

Last updated: 2026-09-11
Target branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`
Starting HEAD for the XFeat checkpoint: `8e5c00392a1de803399235e1c1fa210336f90baf`

## Read this first

StableAR is a **local material-attachment stability layer above a host AR/XR runtime**. It is not a replacement SLAM engine and it is not a two-device synchronization system. ARCore/ARKit/OpenXR keep owning VIO/world tracking. StableAR owns exact-frame history, local metric surface fitting, immutable target identity, visual evidence gating and bounded correction state.

Work only on `stablear/multiplatform-sdk`. Do not modify `research_sdk`, ShowMe branches or unrelated products. The frozen Android-only reference remains independently usable.

Canonical StableAR camera coordinates are right-handed: `+X right, +Y down, +Z forward`.

## Existing checkpoints

- `66b200005948c43b3d82a861930dd56da91fb5fe` — native C++20 core + C ABI v1.
- `67d627cdae8a63185c34db8ced35e5af61f8e4bf` — durable handoff/branch rules + entitlement tooling.
- `edf7ed4701ce7d4d3cd5c6fdd3fcadf0d3b3897e` — shared OpenCV visual tracker source/C ABI.
- `40b1d34e9c7990e56809382b381ff6f9f6e43f39` — Android/ARCore native AAR/JNI source.
- `2faf3b035c7f538846d3ec5dfaa7a15d7231f091` — separate native OpenCV Android AAR source.
- `09a5fd02e70ace512f9673d25082fd9cfe37fa45` — Apple XCFramework/Swift/ARKit source.
- `267f81e9e043ceec31cffc970521a3b8f95b9a45` — generic OpenXR session-local adapter source.
- `8526b441be137d9fbbb63c63412c798d8c47c6f9` — Unity P/Invoke/UPM source + LP64 ABI contract.
- `8e5c00392a1de803399235e1c1fa210336f90baf` — OpenCV Android Prefab package fix; previous multiplatform workflow checkpoint is green.

All branch moves must be non-force unless an explicit recovery procedure says otherwise.

## Native core — DONE / software-verified

`sdk/native` implements rigid geometry/intrinsics/presentation mapping, robust inverse-depth surface fitting, bounded anchor-relative frame history/freeze leases, immutable original root, multi-view original-ray refinement, parallax/visual/uncertainty/travel gates, held-out commit validation, epoch/generation/timestamp rejection, lifecycle state, platform-neutral `AnchorStore`, static/shared CMake packaging and C ABI v1.

Previous local/CI gates include static/shared build, package consumer, C smoke, sanitizers, warnings-as-errors and native contract tests. These are software invariants, **not physical accuracy measurements**.

## Shared native vision

### Existing OpenCV path — compile-tested

`sdk/native-vision` already has immutable-root LK forward/backward fast tracking plus ORB/RANSAC/homography reacquisition. Visual tracking only proposes evidence; geometry remains correction authority. The Android vision AAR uses OpenCV 4.12.0.

### XFeat learned-correspondence frontend — NEW checkpoint, CI pending

Research decision: use **XFeat + StableAR geometry**, not TAPIR/CoTracker as the first mobile backend and not XFeat as a replacement VIO.

Implemented in the new checkpoint:
- runtime-neutral shared C++ `XFeatLocalTracker`;
- deterministic 640x480 grayscale + per-image InstanceNorm preprocessing;
- exact coordinate-aware bilinear sampling of the 64-D descriptor map rather than nearest `pixel/8`;
- reliability-filtered 5x5 local descriptor fingerprint around an arbitrary tap;
- host-prediction-bounded coarse-to-fine search;
- distinct spatial second-peak ambiguity rejection;
- descriptor consensus and conservative pixel sigma;
- bounded explicit multi-view template bank;
- anti-drift rule: no automatic template self-learning;
- additive C ABI and deterministic C++ contract test.

Local pre-commit checks passed under `-Wall -Wextra -Wpedantic -Werror`; public C header also compiled as C11; deterministic XFeat matcher/preprocessing contract passed.

The actual LiteRT graph is **not yet executed by this checkpoint** and the model binary is intentionally not vendored yet. Read `TRACKING_RESEARCH.md` and `WORKLOG.md` before continuing.

## XFeat model/runtime decision

Candidate model provenance observed during research:
- LiteRT-community XFeat revision `bd421aad1ce6d25dc172cd9579cc13b9da21356f`;
- `xfeat.tflite` size 1,414,480 bytes;
- SHA-256 `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`;
- input 480x640 grayscale + host InstanceNorm;
- dense output 64x60x80 plus reliability/keypoint outputs.

Do not ship from mutable upstream `main`. Pin and archive an approved model artifact, preserve notices, and add deterministic parity fixtures first.

Do not advertise one latency number. Published device/backend measurements vary materially. Shipping runtime must compile once, reuse buffers, benchmark available delegates on-device and fall back to LK/ORB when ML is unavailable, slower or thermally undesirable.

## Android / ARCore

`sdk/native-android` provides host-owned ARCore session integration, exact frame timestamp/intrinsics/pose capture, raw/smoothed/point-cloud depth sampling and native StableAR lifecycle. Keep ARCore/session access on the owner AR/render thread.

For XFeat, next Android work is a **dedicated bounded vision worker** using LiteRT `CompiledModel` with persistent tensors. Latest-frame work may coalesce; lifecycle/control commands must not be dropped. Every result must retain exact source frame id/timestamp/epoch/generation. Avoid shipping a Kotlin `FloatArray` copy path for the ~307k-float descriptor tensor; use native/C++ buffers in the final path.

## Apple / ARKit

Existing Apple source contains C-ABI façade, host-owned ARKit adapter, optional scene depth and XCFramework build path. XFeat should reuse the same shared C++ preprocessing/matcher. Put LiteRT CPU/Metal execution behind a replaceable adapter; do not let Apple runtime details duplicate StableAR geometry.

## OpenXR / Meta

Existing generic OpenXR adapter remains host-pose/session-local. Quest-specific RGB/depth/camera APIs remain optional host inputs rather than core dependencies. Physical Quest validation remains required.

## Unity

Existing Unity package/PInvoke boundary and coordinate conversion remain unchanged. Learned correspondence belongs below Unity in the shared/native platform layer; do not implement a second matcher in C#.

## Geometry limitation / next accuracy ceiling

Current `RayRefiner` corrects **one scalar depth along the immutable original clicked ray**. This is deliberately safe but cannot correct tangential material-point error.

After XFeat evidence is calibrated, next estimator should be a bounded fixed-lag local optimizer in anchor coordinates, not a second global SLAM: material point/surfel state, optional tiny pose deltas strongly prior-constrained to host VIO, source-aware reprojection factors, metric depth/surface factors, robust loss, bounded history/marginalization and the existing travel/held-out/epoch/generation protections.

## Multi-camera / temporal observations

Do not create a separate stereo-only anchor type. The roadmap is a capability-aware observation graph: physical cameras, temporal keyframes, raw depth/ToF/LiDAR/mesh and LK/XFeat/ORB observations all contribute calibrated evidence with sensor identity, exact timestamp, intrinsics/extrinsics and source-specific covariance. Spatial phone-camera baseline is strongest close up; temporal motion may provide much larger baseline.

## Known limitations that must stay explicit

- static material/surface attachments only;
- current correction remains 1D along original ray until fixed-lag optimizer lands;
- XFeat matcher is locally software-tested but actual pinned LiteRT graph/runtime parity is pending;
- no calibrated XFeat-to-pixel covariance yet;
- no moving/deforming-object solution;
- no arbitrary cross-session map persistence contract yet;
- no calibrated device covariance;
- no measured smartphone/Quest CPU/GPU/thermal/battery/motion-to-photon claim for the new ML path;
- no independent physical mm/cm ground-truth claim;
- initial absolute depth can be biased even when attachment looks visually stable;
- model/OpenCV/platform redistribution and notices must be cleared before commercial release;
- Google/Apple/Meta terms and owner SDK/EULA remain release gates.

## Existing Android-only path remains usable

Do not remove or block `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`. Its Kotlin Android implementation remains the physical reference and can be integrated independently. Native migration should follow physical A/B parity.

## Exact next-agent procedure

1. Read `AGENTS.md`, this file, `TRACKING_RESEARCH.md`, `WORKLOG.md`, `MULTIPLATFORM.md`, `COMMERCIALIZATION.md`, `VERSIONING.md`, `VALIDATION.md`.
2. Work only on `stablear/multiplatform-sdk` and fetch live HEAD before writes.
3. Check the workflow run for the XFeat checkpoint. Fix any native-linux/native-vision regression before adding runtime code.
4. Add source-aware visual evidence metrics; do not map XFeat consensus residual into LK forward/backward semantics.
5. Add a pinned model manifest, license/NOTICE data and deterministic XFeat input/output parity fixtures.
6. Implement Android LiteRT C++ `CompiledModel` worker with persistent tensors and bounded scheduling.
7. Wire only calibrated XFeat observations through StableAR geometry, then admit new templates after independent held-out acceptance.
8. Add iOS LiteRT/Metal adapter over the same C++ matcher.
9. Implement bounded fixed-lag local point/surfel optimizer and then capability-aware multi-camera observations.
10. Run physical Android/iOS/Quest A/B validation before upgrading claims or platform status.

No synthetic test, CI run or model-card benchmark alone justifies a physical accuracy, FPS or commercial-performance claim.
