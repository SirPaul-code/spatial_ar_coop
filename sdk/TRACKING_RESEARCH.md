# StableAR tracking research: XFeat learned correspondence

Last updated: 2026-09-11
Branch: `stablear/multiplatform-sdk`
Scope: local static-material attachment stability above host ARCore / ARKit / OpenXR VIO.

## Decision

Add **XFeat as an optional learned-correspondence frontend**, not as a replacement for host VIO and not as an unconstrained source of anchor motion.

Production direction:

1. Host AR runtime owns camera pose, IMU fusion, world tracking and relocalization.
2. LK remains the cheapest high-rate local visual tracker.
3. XFeat runs at a lower/adaptive cadence and supplies robust learned local correspondence and reacquisition evidence.
4. ORB + homography remains a no-ML fallback/reacquisition path.
5. StableAR geometry is the only component allowed to change an attachment estimate. Exact frame identity, epoch/generation checks, parallax gates, uncertainty, bounded travel and held-out validation remain mandatory.
6. XFeat templates are never learned from the tracker's own unverified match. A template is admitted only after independent StableAR geometric acceptance.

This deliberately avoids `ML pixel -> directly move world anchor`.

## Why XFeat first

The official XFeat project targets hardware-constrained visual correspondence and explicitly calls out resource-constrained robotics, navigation and AR use cases. It produces compact 64-D descriptors and is Apache-2.0.

Sources:
- https://github.com/verlab/accelerated_features
- https://www.verlab.dcc.ufmg.br/descriptors/xfeat_cvpr24/
- https://huggingface.co/litert-community/xfeat-litert

The LiteRT-community re-authoring documents:
- input `[1,480,640,1]`, grayscale, host-side per-image InstanceNorm;
- dense descriptors `[1,64,60,80]`;
- keypoint logits `[1,65,60,80]`;
- reliability `[1,1,60,80]`.

StableAR deliberately uses the dense descriptor map around an arbitrary tapped material point. The tap does not need to coincide with a detector keypoint.

## Performance: do not hard-code one benchmark

The model card's roughly 0.4 ms Pixel 8a result is a specific LiteRT CompiledModel/GPU measurement, not a universal guarantee. The same published model-card data shows roughly 4.1-4.3 ms GPU on a Galaxy S26 with LiteRT 2.2.0 and substantially worse results for the tested NPU path.

Therefore StableAR must:
- initialize/compile the model once;
- reuse tensor buffers;
- benchmark supported backends on the real device;
- cache a backend decision per device/runtime/model build;
- prefer measured latency and correctness over `GPU`/`NPU` labels;
- degrade to LK/ORB if ML is unavailable or thermally inappropriate.

LiteRT references:
- https://ai.google.dev/edge/litert
- https://ai.google.dev/edge/litert/next/android_cpp_sdk
- https://github.com/google-ai-edge/LiteRT

## Model provenance / commercial gate

Do not silently vendor a mutable model download into a commercial SDK.

Observed candidate provenance at research time:
- repository revision: `bd421aad1ce6d25dc172cd9579cc13b9da21356f`;
- `xfeat.tflite` size: 1,414,480 bytes;
- SHA-256: `6f0756d70218681a317f3630c5946f47812e2531f1fa5aba6cfa2a80115fc0df`.

Before shipping, pin the exact revision/file, preserve Apache-2.0/NOTICE attribution, reproduce parity against official XFeat on representative fixtures, verify Android+iOS tensor ordering/shapes, and archive the approved model as a controlled release artifact. The model binary is **not vendored in this checkpoint**.

## Descriptor sampling detail

Do not approximate official XFeat sampling as nearest `pixel / 8`.

Official `InterpolateSparse2d` normalizes image coordinates then uses `grid_sample(..., align_corners=false)`. The equivalent continuous feature coordinate is:

`feature = pixel * feature_extent / (image_extent - 1) - 0.5`

StableAR mirrors this coordinate transform and bilinearly samples all 64 descriptor channels, then L2-normalizes the sampled vector. Unsafe border samples are rejected instead of inventing padded descriptors.

References:
- https://github.com/verlab/accelerated_features/blob/main/modules/interpolator.py
- https://github.com/verlab/accelerated_features/blob/main/modules/xfeat.py

## Implemented runtime-neutral matcher

`sdk/native-vision/include/stablear/xfeat.hpp` and `src/xfeat.cpp` implement the shared matcher without owning a neural runtime.

### Root fingerprint

At attachment creation, capture a reliability-filtered descriptor patch around the material point. Default is 5x5 descriptor samples with 8 px image spacing; at least 12 valid samples are required. This is intentionally stronger than one descriptor because partial occlusion, reflections or local weak texture can invalidate only part of the fingerprint.

### Bounded search

The host supplies the predicted image location from current host pose + StableAR attachment state. Search is bounded around that prediction (default +/-64 px), with coarse 8 px scanning and 1 px local refinement.

Candidate score combines cosine similarity, reference/current reliability, valid patch coverage and inlier fraction. Repetitive texture is rejected using a distinct-spatial-second-peak margin.

### Template bank

Each attachment has a bounded template bank (default max 8). Optional view direction and scale choose/reweight useful templates. Similar view directions replace only with equal-or-better-quality samples.

Critical anti-drift rule: no automatic self-learning. `addTemplate()` is explicit and should only be called after independent StableAR acceptance.

### Confidence

The XFeat frontend reports best score, second-best distinct score, margin, mean reliability, descriptor consensus residual, conservative pixel sigma and template serial.

Do **not** pretend XFeat descriptor-consensus residual equals LK forward/backward optical-flow error. Before geometry wiring, StableAR needs source-aware visual evidence metrics.

## Shared preprocessing

`prepareXFeatInput()` provides deterministic cross-platform preprocessing:
- bilinear resize to 640x480;
- Float32 grayscale;
- per-image `(x - mean) / sqrt(var + 1e-5)`.

The checkpoint tests normalization numerically. The actual `.tflite` graph has not yet been executed by CI/device code.

## Target runtime architecture

```text
ARCore / ARKit / OpenXR
        | host pose + exact timestamp
        v
 predicted material UV -------------------------+
        |                                       |
        |                       camera grayscale|
        |                                       v
        |                                XFeat LiteRT
        |                                       |
        |                            dense 64D + reliability
        |                                       |
        +-------------------------> XFeatLocalTracker
                                           |
                                     observed UV + confidence
                                           |
LK / ORB evidence --------------------------+
                                           v
                                 source-aware observation
                                           |
                                  StableAR local geometry
                                           |
                       parallax/covariance/travel/held-out gates
                                           v
                                   StableAttachment
```

Rendering stays host-pose driven at display cadence. ML does not need to run at 60/90/120 Hz.

## Android plan

Use LiteRT `CompiledModel` on a dedicated bounded worker. Required shipping properties:
- persistent model + persistent/reusable buffers;
- latest-frame-wins bounded scheduling; lifecycle/control commands cannot be dropped;
- exact `frame_id`, timestamp, epoch and attachment generation travel with each inference job;
- avoid unbounded `FloatArray` churn;
- prefer native/C++ buffers so descriptor output reaches `XFeatLocalTracker` without Kotlin round trips;
- runtime backend benchmarking and fallback;
- keep ML optional.

A Kotlin `readFloat()` bring-up path is acceptable for parity testing but not the final zero-copy path because the descriptor tensor alone is ~307k floats per frame.

## iOS plan

Use the same shared C++ preprocessing/matcher. LiteRT supports iOS CPU/Metal paths; backend selection remains behind an adapter so runtime details can change without changing StableAR geometry. Do not assume a beta delegate is universally available.

References:
- https://ai.google.dev/edge/litert/ios
- https://ai.google.dev/edge/litert/ios/gpu

## Multi-camera / temporal multi-view

XFeat fits the broader observation-graph design. A physical camera or temporal keyframe should contribute another calibrated observation, not create a separate stereo-only anchor type.

Future observation records should carry sensor identity, exact sensor timestamp, intrinsics/distortion, camera pose/extrinsics, observed pixel, evidence source and source-specific covariance/quality. Spatial phone-camera baseline is useful mainly at close ranges; temporal device motion can provide a much larger triangulation baseline.

## Geometry upgrade after XFeat

The current `RayRefiner` is deliberately conservative but only corrects depth along the immutable original click ray. Once correspondence quality improves, that becomes a major mathematical ceiling.

Next estimator should be a **bounded fixed-lag local optimizer**, not another global SLAM:
- optimize material point or local surfel in anchor coordinates;
- optionally tiny per-keyframe pose deltas with strong priors to host VIO;
- reprojection factors from LK/XFeat/ORB/multi-camera observations;
- metric depth/plane/surfel factors where available;
- robust M-estimator/outlier handling;
- anisotropic/source-aware covariance;
- marginalize old keyframes;
- preserve travel limits, epoch/generation checks and held-out validation.

This permits small tangential as well as depth corrections without fighting ARCore/ARKit global tracking.

## Validation before any claim

A/B at minimum:
- host anchor only;
- LK only;
- LK + ORB;
- LK + XFeat;
- LK + XFeat + template bank;
- later fixed-lag and multi-camera variants.

Sequences: low/repetitive texture, specular surfaces, illumination/exposure changes, motion blur, partial/full occlusion and reacquisition, 20/45/70+ degree viewpoint changes, scale/distance changes, close service scenes and thermal soak.

Measure image reprojection stability, independent world-space material-point drift, false-lock rate, reacquisition correctness, latency, CPU/GPU, memory, battery and thermal behavior. Synthetic tests or model-card benchmarks do not justify mm/cm or FPS claims.

## Next implementation steps

1. CI the runtime-neutral matcher and contract tests.
2. Add source-aware visual evidence representation while retaining ABI compatibility.
3. Add pinned model manifest + deterministic parity fixtures.
4. Implement Android LiteRT C++ CompiledModel worker with persistent buffers and bounded scheduling.
5. Wire calibrated XFeat evidence through StableAR held-out geometry.
6. Admit templates only after accepted held-out evidence.
7. Port the same runtime adapter to iOS/Metal.
8. Implement bounded fixed-lag material-point/surfel optimizer.
9. Add capability-aware multi-camera/temporal observations.
10. Run physical A/B validation before changing platform/commercial performance status.
