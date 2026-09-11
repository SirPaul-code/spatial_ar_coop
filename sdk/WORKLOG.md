# StableAR work log

## 2026-09-11 — XFeat learned-correspondence checkpoint

### Context

User requested research and implementation of an existing ML model that can materially improve StableAR tracking while preserving Android+iOS portability and commercial viability. Candidate selected: XFeat dense local descriptors via LiteRT, fused with StableAR geometry rather than replacing ARCore/ARKit VIO.

Starting branch HEAD: `8e5c00392a1de803399235e1c1fa210336f90baf` (`fix(sdk): use actual OpenCV Prefab package name`). Frozen reference remains `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0` and was not modified.

### Research findings

- XFeat upstream is Apache-2.0, 64-D and explicitly designed for efficient visual correspondence on constrained hardware.
- LiteRT-community XFeat exposes 640x480 normalized grayscale -> 64x60x80 descriptor + 1x60x80 reliability + 65x60x80 keypoint logits.
- The headline ~0.4 ms Pixel 8a result is not universal. Published Galaxy S26 measurements are ~4.1-4.3 ms GPU and much slower for the tested NPU path. Runtime device benchmarking/fallback is mandatory.
- Official XFeat interpolation uses normalized coordinates with `grid_sample(align_corners=false)`; naive nearest `pixel/8` is not equivalent.
- Shared C++ matcher is the right seam. Platform ML runtime must remain replaceable and must not own world-geometry decisions.
- Current StableAR 1D original-ray refiner remains a later accuracy ceiling; learned correspondences make a bounded fixed-lag local optimizer more worthwhile, not less.

Full rationale and source list: `TRACKING_RESEARCH.md`.

### Implemented

- `native-vision/include/stablear/xfeat.hpp`: XFeat map/view/policy/result contracts, preprocessing API and bounded tracker.
- `native-vision/src/xfeat.cpp`: coordinate-aware bilinear descriptor sampling, L2 normalization, 5x5 patch fingerprint, reliability weighting, bounded ROI coarse-to-fine matching, distinct-peak ambiguity rejection, descriptor consensus, conservative sigma and explicit bounded view-template bank.
- `native-vision/src/xfeat_c.cpp` + `vision_c.h`: runtime-neutral C ABI.
- `native-vision/tests/xfeat_contract.cpp`: deterministic preprocessing and translated-descriptor-field contract.
- `native-vision/CMakeLists.txt`: builds XFeat source and runs the new contract under existing Linux CI.

### Local verification before branch update

- `g++ -std=c++20 -Wall -Wextra -Wpedantic -Werror` on `xfeat.cpp`: PASS.
- same flags on `xfeat_c.cpp`: PASS.
- C11 compile of extended public `vision_c.h`: PASS.
- deterministic matcher/preprocessing contract: PASS (`StableAR XFeat contract passed`).

This is **matcher/preprocessing complete**, not actual LiteRT graph execution. The model binary is intentionally not vendored yet; model provenance/hash/parity is a release gate.

### Anti-drift decisions

- XFeat cannot directly move an anchor.
- No automatic template self-learning.
- Template admission must follow independent StableAR geometric/held-out acceptance.
- Search is bounded around host-predicted material UV.
- Ambiguous spatial peaks are rejected.
- Do not fake LK forward/backward-flow semantics for XFeat; add source-aware evidence metrics before geometry wiring.

### Precise next steps

1. Confirm `native-linux` CI compiles/runs the new contract.
2. Add source-aware `VisualObservation` evidence/quality representation while retaining ABI compatibility.
3. Add pinned LiteRT/XFeat model manifest + deterministic parity fixtures.
4. Implement Android LiteRT C++ CompiledModel worker with persistent buffers and latest-frame bounded scheduling.
5. Wire calibrated XFeat observations into held-out StableAR geometry.
6. Add explicit post-commit template admission.
7. Implement iOS LiteRT/Metal runtime adapter over the same C++ matcher.
8. Build fixed-lag local material-point/surfel optimizer, then capability-aware multi-camera observations.
9. Run physical A/B ground-truth tests before any accuracy/performance claim.

### CI state

Previous branch HEAD `8e5c00392a1de803399235e1c1fa210336f90baf` had the StableAR multiplatform workflow green after the OpenCV Prefab fix. The new XFeat checkpoint must be judged by the workflow run for its own commit SHA; record any failure/fix in the next entry.
