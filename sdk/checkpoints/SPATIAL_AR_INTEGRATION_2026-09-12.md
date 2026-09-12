# Spatial AR + StableAR integration checkpoint — 2026-09-12

## Verified checkpoint

- Product branch: `integration/spatial-stablear`
- Green code checkpoint: `02cd38d4f36af69dfea4160c5cd60e1a88c9ecf0`
- CI workflow run: `#505` / `34700801149`
- Server tests/container: green
- Android + StableAR unit tests/build: green
- Integrated APK inspection: green
- StableAR benchmark scorer contract: green
- Prerelease publication: green
- Release tag: `spatial-stablear-latest`
- APK asset: `SpatialAR-StableAR-latest.apk`
- APK SHA-256: `26ce482a93fd29322a9acc28ce0a4c4c75a18c52843297f44c5f3794af5d79cc`

The CI release APK contains the exact pinned XFeat model and `libstablear_vision_jni.so`; CI verifies the model source by extracting it from the previously verified StableAR Lab release and checking the pinned size/SHA before the product build.

## Integration architecture

The mature two-phone Spatial AR product remains responsible for ARCore lifecycle, Cloud Anchor/manual SITE localization, QR/map sharing, server/WebSocket transport, persistence, rendering and moving-object tracking.

StableAR remains an independently buildable SDK and is injected only for static material attachments:

1. user taps a static material point;
2. product-side ARCore hit/depth/plane policy provides a practical metric SITE seed;
3. `ArCoreAdapter.placeWithDepthPrior(...)` creates a StableAR root using the exact camera-image pixel as immutable material identity;
4. XFeat/LiteRT is preferred, LK/ORB remains fallback;
5. StableAR geometry/held-out validation is the only authority allowed to accept a correction/template;
6. creator-side accepted refinements may update the canonical shared marker;
7. a second phone receives the SITE prediction and creates a viewer-local StableAR attachment only after conservative local depth agreement;
8. viewer-local corrections are never fed back into the canonical shared marker, avoiding multi-device feedback loops.

The original strict StableAR Lab `SurfaceFitter` is no longer a mandatory product placement gate. This directly addresses physical testing where close-range/thin/multi-depth targets could be rejected before XFeat ran.

## Benchmark contract

Debug integration builds record paired StableAR-vs-stock-ARCore evidence by default.

- Stock baseline: the original unrefined material point on the same native ARCore anchor.
- StableAR candidate: same exposure, exact root pixel and metric seed plus StableAR refinement.
- Independent ground truth: ArUco marker geometry from recorded camera frames, with the exact root click mapped into the marker plane by homography. StableAR/XFeat/LK/ORB output is not used as ground truth.

Offline scorer: `sdk/benchmark/score_aruco.py`.
Target generator: `sdk/benchmark/make_target.py`.
Protocol/details: `sdk/benchmark/README.md` and `android/STABLEAR_INTEGRATION.md`.

Primary reportable metrics: p50/p95/RMSE pixel material-attachment error, availability, false-lock rate, paired win rate, mean paired improvement and bootstrap 95% confidence interval, including per-phase results.

Do not claim universal superiority over ARCore from CI. A commercial comparative claim requires retained physical sessions on real supported devices and a scenario-specific result with the independent benchmark gate satisfied.

## Current limitations / next work

- Current core correction is still scalar depth along the immutable original root ray; full tangential XYZ fixed-lag optimization remains the next major accuracy upgrade after physical validation.
- Physical two-phone and single-phone benchmark sessions are still required; CI proves software/build contracts, not real-world accuracy.
- iOS neural LiteRT runtime remains pending even though the shared XFeat matcher builds for Apple.
- Android output-tensor copy path remains a performance optimization opportunity after deterministic parity/device measurements.
- For commercial distribution, move customer consumption from source-project references to versioned Maven/AAR packages (and XCFramework/Unity artifacts), preserving the SDK/product boundary and detached entitlement verification.

## Next-agent procedure

1. Do not modify the frozen `research_sdk` reference.
2. Start from `integration/spatial-stablear` and verify branch HEAD/CI before changes.
3. Install the `spatial-stablear-latest` prerelease on a physical Android phone.
4. Generate/print the ArUco target and place a StableAR marker on a recognisable point inside it.
5. Capture at least the documented front/orbit45/orbit70/scale/blur/lowlight/occlusion/reacquire/return phases, preferably five runs each.
6. Pull benchmark sessions and score them with `sdk/benchmark/score_aruco.py`.
7. Only after physical results are understood, tune matcher/geometry or implement the full-3D fixed-lag optimizer.
8. Record any new checkpoint, CI run, physical device/model, benchmark raw-data location and exact result here or in a new dated checkpoint.
