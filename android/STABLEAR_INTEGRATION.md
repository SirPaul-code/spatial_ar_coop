# Spatial AR + StableAR integration

## Scope

This branch integrates the independently buildable **StableAR SDK** into the existing two-phone Spatial AR product without turning the SDK into a product-specific/network-specific codebase.

- Product branch: `integration/spatial-stablear`
- StableAR source branch: `stablear/multiplatform-sdk`
- Existing Spatial AR global localization remains authoritative for the common `SITE` frame.
- StableAR is used for **static material attachments** only. Moving people/cars/object tracks continue through the existing detector/tracker pipeline.
- The frozen `research_sdk` reference is not part of this integration and must remain untouched.

## Runtime architecture

```text
Phone A                                         Server                              Phone B
-------                                         ------                              -------
ARCore VIO / Cloud Anchor                                                           ARCore VIO / Cloud Anchor
          |                                                                                    |
          +------ world <-> shared SITE frame -------- compact shared marker -----------------+
          |                                                                                    |
user taps exact static material point                                                SITE point predicts local pixel/depth
          |                                                                                    |
host ARCore hit/depth/plane seeds metric XYZ                                         conservative local depth agreement gate
          |                                                                                    |
StableAR root = exact source-image pixel                                              StableAR local root/reacquisition
          |                                                                                    |
XFeat/LiteRT + LK/ORB                                                                 XFeat/LiteRT + LK/ORB
          |                                                                                    |
held-out bounded StableAR correction                                                  viewer-local StableAR correction
          |                                                                                    |
refined SITE point may be re-published                                                never writes viewer correction back
```

### Division of responsibility

**Spatial AR host owns:**

- ARCore `Session` and render lifecycle;
- Cloud Anchors/manual fallback and `worldFromSite`;
- server/WebSocket, map keys, persistence and QR sharing;
- user interaction and product UI;
- initial metric raycast/hit/depth policy;
- moving-object tracking.

**StableAR SDK owns:**

- immutable material-point identity from the exact root pixel;
- learned XFeat correspondence with LK/ORB fallback;
- uncertainty and bounded geometric refinement;
- held-out verification before accepting corrections;
- staged-template admission only after StableAR accepts the geometry.

The integration deliberately uses the same host ARCore `Session`; it does not open another camera or another ARCore session.

## Why initial placement is host-seeded

The standalone StableAR Lab intentionally uses a conservative local surface fitter. Physical testing showed that this can reject close-range, thin or multi-depth surfaces before XFeat ever starts.

For product integration, the SDK now exposes `ArCoreAdapter.placeWithDepthPrior(...)`. Spatial AR first obtains a practical metric seed from ARCore `DepthPoint`, tracked `Plane` or tracked `Point`, then StableAR attaches the exact tapped image pixel to that seed and refines it visually.

This keeps the conservative SDK geometry intact while avoiding a product usability dependency on the Lab's strict initial surface-fit gate.

## Using the integration build

In an already localized map/session:

1. Open **More**.
2. Choose **Place shared test marker**.
3. Tap the exact static material feature you want to share.
4. The host resolves an ARCore metric seed and creates a StableAR material attachment.
5. Move sideways/orbit around the point. The HUD reports StableAR attachment count, backend and accepted corrections.
6. Another localized phone receives the canonical SITE point. When its predicted point agrees with local depth, it creates its own local StableAR visual attachment and can refine the rendering locally.

Creator-side accepted corrections may update the canonical shared marker. Viewer-side corrections are intentionally local-only to prevent multi-device feedback loops.

## Benchmark: StableAR vs stock ARCore

The integration debug APK has benchmark capture enabled by default. Release builds keep it disabled unless explicitly enabled by the host intent.

The A/B comparison is paired and fair:

- **Stock ARCore** = the original, unrefined material point on the same native anchor.
- **StableAR** = the same initial exposure/pixel/metric seed, followed by StableAR refinement.

The benchmark therefore does not compare different placements or different taps.

### Independent ground truth

Generate/print the benchmark target:

```bash
python -m pip install numpy opencv-contrib-python
python sdk/benchmark/make_target.py --output stablear-aruco-23.png
```

Place a StableAR marker on the printed target. The debug app records:

- `root.pgm`: the exact root camera exposure;
- `root.json`: the exact tapped root pixel and scaled intrinsics;
- later grayscale frames;
- per-frame stock and StableAR projected pixels;
- StableAR method/latency/correction count.

The offline scorer independently detects the four ArUco corners. It maps the exact root click into the marker plane via homography, then projects that same physical material point from independently detected corners in every later frame. XFeat/LK/ORB output is never used as truth.

Typical device path for the debug package is:

```text
/sdcard/Android/data/com.sirpaul.spatialarcoop.debug/files/stablear-benchmark/session-<timestamp>/
```

Pull a session with ADB and score it:

```bash
adb pull /sdcard/Android/data/com.sirpaul.spatialarcoop.debug/files/stablear-benchmark/session-<timestamp> ./stablear-session
python sdk/benchmark/score_aruco.py ./stablear-session --json-out summary.json
```

### Required physical test phases

Run multiple independent passes of at least:

- frontal 0.5-1.0 m;
- roughly 45 degree orbit;
- roughly 70 degree oblique view;
- near/far scale change;
- normal hand motion / motion blur;
- reduced light;
- full occlusion for 1-2 seconds;
- reacquisition from a different angle;
- return to the original viewpoint.

Use at least 5 runs per condition for an engineering comparison and multiple supported phone models before making a broad commercial claim.

### Metrics

The scorer reports both candidates using the same independently visible frames:

- p50 and p95 pixel attachment error;
- RMSE and maximum error;
- availability while independent ground truth is visible;
- false-lock rate above a configured threshold;
- paired improvement `stock_error - stable_error`;
- paired win rate;
- bootstrap 95% confidence interval for mean paired improvement;
- metrics per named phase;
- the ground-truth mode actually used.

Positive paired improvement means StableAR was closer to the physical material point on that frame.

## Commercial claim gate

Do **not** advertise "better than ARCore" from CI, synthetic tests or one video. A comparative release claim requires real-device sessions and retained raw evidence.

Minimum gate for a named scenario/device class:

1. independent `aruco_root_homography` ground truth (or stronger external calibrated tracking);
2. sufficient paired visible frames for a meaningful p95;
3. StableAR p95 lower than stock ARCore;
4. bootstrap 95% CI for mean paired improvement entirely above zero;
5. false-lock rate within the product threshold and not hidden by dropping predictions;
6. availability and latency reported beside accuracy;
7. raw sessions retained so the result can be reproduced.

The first claim should be narrow, for example: **"On tested supported Android devices and the published orbit/reacquisition protocol, StableAR reduced p95 screen-space material attachment error versus the same unrefined ARCore anchor."** The actual numeric result must come from physical data, not code assumptions.

## Current geometry ceiling

The current StableAR core still refines scalar depth along the immutable original root ray. It does not yet perform a full tangential XYZ local optimization. The A/B benchmark is intentionally capable of exposing this ceiling rather than masking it.

The next major mathematical upgrade after physical validation is a bounded full-3D fixed-lag local optimizer with source-aware visual/depth factors, while ARCore/ARKit remain authoritative for global SLAM/VIO.

## SDK/product boundary for sale

The integration product references SDK modules from `../sdk`, but product server/network/UI logic is not copied into the SDK. The SDK remains independently buildable and exportable as Android AAR/native libraries, Apple XCFrameworks and Unity/native bindings.

Commercial licensing continues through `sdk/licensing`: the private issuer signs detached entitlements and customer/runtime verification uses only public verification material. Do not ship the issuer or private signing keys inside customer SDK artifacts.

## CI / release

Integration CI verifies:

- server tests/container;
- benchmark scorer contract;
- Android unit tests;
- integrated debug APK build;
- presence of the pinned XFeat model and StableAR native vision library inside the APK;
- SHA-256 of the generated APK.

A successful push on this integration branch publishes the prerelease tag:

```text
spatial-stablear-latest
```

with `SpatialAR-StableAR-latest.apk` and its SHA-256 file.
