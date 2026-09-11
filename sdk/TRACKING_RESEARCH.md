# StableAR tracking research: XFeat learned correspondence

Last updated: 2026-09-11
Branch: `stablear/multiplatform-sdk`
Scope: local static-material attachment stability above host ARCore / ARKit / OpenXR VIO.
Documented code checkpoint: `e461e81c375e3875237ab0286718af1d4a4f7939`

## Decision

Use **XFeat as an optional learned-correspondence frontend**. Do not replace host VIO and do not let ML directly mutate an anchor.

Production fusion remains:

```text
ARCore / ARKit / OpenXR host pose
             |
      predicted material UV
             |
       +-----+-----+
       |           |
     LK/ORB      XFeat
       |           |
       +--- visual evidence ---+
                               |
                       StableAR geometry
                  uncertainty/parallax/travel
                    + held-out validation
                               |
                      corrected attachment
```

LK is the cheapest high-rate local tracker. XFeat is the learned local correspondence/reacquisition source. ORB remains a no-ML fallback. StableAR geometry is the sole correction authority.

## Why XFeat

XFeat was designed for lightweight local correspondence on constrained hardware. The chosen LiteRT re-authoring is small, dense and suitable for arbitrary tapped pixels rather than requiring a detector keypoint exactly under the tap.

Pinned candidate now used by the Android build:
- upstream revision `bd421aad1ce6d25dc172cd9579cc13b9da21356f`;
- `xfeat.tflite` 1,414,480 bytes;
- SHA-256 `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`;
- Apache-2.0;
- input `[1,480,640,1]` Float32 grayscale after per-image InstanceNorm;
- descriptors `[1,64,60,80]`;
- reliability `[1,1,60,80]`.

References:
- https://github.com/verlab/accelerated_features
- https://www.verlab.dcc.ufmg.br/descriptors/xfeat_cvpr24/
- https://huggingface.co/litert-community/xfeat-litert

## Runtime measurements: never hard-code one number

Published XFeat LiteRT measurements vary sharply by device/runtime/backend. Values around sub-millisecond have been reported in one Pixel 8a setup, while other GPU paths are tens of milliseconds; recent Galaxy measurements are around a few milliseconds on Adreno while a tested NPU route can be dramatically slower.

Therefore StableAR should eventually benchmark candidate accelerators on the actual device/model/runtime combination and cache a correctness-qualified backend choice. Backend name (`GPU`, `NPU`) is not evidence of speed or correctness.

## Dense arbitrary-tap matching

StableAR samples the dense 64-D descriptor map at arbitrary image coordinates using the XFeat/grid-sample coordinate convention rather than nearest `pixel/8`.

Equivalent continuous feature coordinate for an image coordinate is:

`feature = pixel * feature_extent / (image_extent - 1) - 0.5`

All 64 channels are bilinearly sampled and L2-normalized. Border samples that cannot be represented safely are rejected.

A root is a reliability-filtered **5x5 descriptor patch**, not one descriptor. Partial occlusion/reflection can therefore invalidate only part of the fingerprint.

## Search / rejection

The host-projected material UV bounds the search. Current defaults:
- search radius: 64 model pixels;
- coarse step: 8 px;
- local refinement: 1 px;
- min descriptor cosine: 0.82;
- min valid/inlier samples: 12;
- distinct second-peak margin: 0.025;
- local patch-consensus radius: 3 px;
- max templates: 8.

A match is rejected when local descriptor support is weak or when another spatially distinct peak is too competitive. XFeat reports score, second score, margin, reliability, consensus residual, sigma and source template serial.

## Coordinate / uncertainty semantics

The neural graph always sees 640x480. ARCore/StableAR may use a different CPU-image raster.

Android now maps source coordinates to/from the model raster with the same half-pixel resize convention used by preprocessing. Semantics are intentionally split:
- observed `x/y`: source CPU-image pixels;
- `sigmaPx`: conservative source-image pixel uncertainty for geometry;
- XFeat descriptor patch-consensus residual: fixed 640x480 model pixels, used only as matcher-quality evidence.

Do not scale the descriptor-consensus residual into source pixels and then compare it with LK's fixed forward/backward thresholds. Those metrics are not the same physical quantity.

Long-term, visual evidence should gain an additive source-aware quality type instead of overloading LK-named fields; preserve existing C ABI layouts while doing this.

## Anti-drift learning

The most important template-bank rule is now implemented:

**A successful XFeat match is not sufficient to learn a template.**

Flow:

`match -> stage exact current-frame patch -> StableAR held-out/geometric acceptance -> commit or discard`

Staging snapshots only the small local descriptor patch while that exact inference map is current. The opaque token is inert. One pending candidate exists per attachment; a newer stage invalidates the older token. Commit requires the same attachment id and never reads whichever descriptor map is current later.

This prevents self-confirming descriptor drift and async frame-mismatch poisoning.

## Viewpoint bank

Accepted templates now carry geometric metadata computed in the anchor frame:
- direction: normalized target-to-current-camera vector;
- scale: `rootTargetCameraZ / currentTargetCameraZ`, clamped to `[0.5, 2.0]`.

Direction is used to deduplicate near-identical viewpoints. Relative scale adjusts descriptor-patch offsets. Root identity remains immutable.

Further improvement after physical measurements can rank templates by angular proximity, but do not add an unvalidated angle prior that suppresses a visually stronger valid template.

## Android state

Actual LiteRT execution is implemented:
- persistent `CompiledModel`;
- persistent tensors;
- GPU attempt + CPU fallback;
- exact pinned model asset/hash verification;
- exact grayscale source timestamp;
- source/model coordinate conversion;
- source-space sigma;
- no inference when no attachments exist;
- staged multi-view admission;
- LK/ORB fallback.

Current correctness prototype still reads the dense output into Kotlin `FloatArray` then crosses JNI. The descriptor tensor alone is roughly 307k floats per inference, so a native/shared buffer path is the obvious shipping optimization **after** output parity is frozen.

## Portable C++ / Apple state

XFeat matching no longer depends on OpenCV. `StableAR::xfeat` contains preprocessing, descriptor matching, staged admission and C ABI. `StableAR::vision` contains the optional OpenCV LK/ORB frontend.

At code checkpoint `e461e81...`, Apple builds an independent `StableARXFeatNative.xcframework` for iPhoneOS and simulator. Workflow run #39 Apple job is green. This proves the portable matcher builds for Apple; it does not yet execute `xfeat.tflite` on iOS.

## iOS LiteRT research update

LiteRT documentation/repository currently advertises iOS CPU and Metal support, but 2026 upstream issues show that third-party physical-device deployment has had real Metal accelerator registration/prebuilt architecture failures, and another current issue reports FP16 GPU numerical failure on a different graph/device.

Relevant upstream material:
- https://github.com/google-ai-edge/LiteRT
- https://github.com/google-ai-edge/LiteRT/issues/8787
- https://github.com/google-ai-edge/LiteRT/issues/9249
- https://github.com/google-ai-edge/LiteRT/issues/6745

This does **not** prove XFeat fails on Metal. It does mean production StableAR must use:
1. CPU as the correctness baseline;
2. optional Metal creation;
3. deterministic finite-output/parity self-test for the pinned XFeat graph;
4. automatic CPU fallback;
5. no universal Metal latency claim.

Do not redesign around legacy `tflite::Interpreter` only to avoid current packaging friction; upstream now treats that API as maintenance-only.

## Next estimator ceiling

Better image correspondence exposes the current mathematical limit: StableAR's existing `RayRefiner` corrects only depth along the immutable original clicked ray.

For maximum material attachment stability, next estimator should be a bounded **fixed-lag local optimizer**, not another global SLAM:
- material point/surfel in anchor coordinates;
- optional tiny per-keyframe pose deltas with strong host-VIO priors;
- reprojection factors from LK/XFeat/ORB;
- metric depth/plane/surfel factors;
- source-aware covariance;
- robust loss/outlier rejection;
- bounded keyframes and marginalization;
- existing travel/epoch/generation/held-out protections.

That is what will allow small tangential XYZ correction without fighting ARCore/ARKit.

## Multi-camera / temporal extension

Do not create a stereo-specific anchor. Use a capability-aware observation graph where each camera/keyframe/depth source contributes a calibrated observation carrying sensor identity, exact sensor timestamp, intrinsics/distortion, extrinsics/pose and source covariance.

Spatial phone-camera stereo is strongest at close service distances. Temporal movement can create much larger triangulation baselines. Multi-camera should therefore be optional evidence, not mandatory hardware.

## Validation before claims

Physical A/B set:
- host anchor only;
- LK;
- LK+ORB;
- XFeat learned correspondence;
- XFeat + accepted multi-view bank;
- later fixed-lag estimator;
- later multi-camera/temporal factors.

Test low/repetitive texture, specular surfaces, exposure changes, blur, partial/full occlusion and reacquisition, 20/45/70+ degree viewpoint changes, distance/scale changes and thermal soak.

Measure independent material-point reprojection/world error, false locks, reacquisition correctness, latency, memory, CPU/GPU, battery and thermal behavior. CI and model-card benchmarks are not physical accuracy evidence.
