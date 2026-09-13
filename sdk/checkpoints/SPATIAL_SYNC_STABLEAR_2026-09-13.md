# Spatial Sync + StableAR checkpoint — 2026-09-13

## Current state

Spatial Sync now builds successfully with the full StableAR material-tracking integration on branch `stablear/multiplatform-sdk`.

### Green integration checkpoint

- StableAR integration/source checkpoint validated by CI: `0a5db71ca357349678bd70838724e2694471fbdb`
- `stablear-multiplatform` workflow run: **#62**, run ID `34787006114`
- Android job ID: `103804200695`
- Android result: **SUCCESS**
- `Build native core AAR`: SUCCESS
- `Build native OpenCV vision AAR`: SUCCESS
- `Retain original Android reference buildability`: SUCCESS
- `SpatialNoMap StableAR host wiring contract`: SUCCESS
- `Build SpatialNoMap full StableAR integration`: SUCCESS
- Spatial Sync APK artifact publication: SUCCESS

The stale ShowMe Kotlin DSL did not need to be modified. `android/settings.gradle.kts` now avoids configuring `:showme` when Gradle is explicitly invoked only for `:app` tasks, preserving product isolation while allowing the Spatial Sync StableAR host build to compile independently.

## GitHub release checkpoint

Dedicated release workflow added in commit:

- `3093c895ce3ed00aaac30e6e1f64b5e5c5f395f4`
- workflow: `.github/workflows/spatial-stablear-release.yml`
- workflow run ID: `34787339820`
- result: **SUCCESS**

Release:

- tag: `spatial-stablear-latest`
- target commit: `3093c895ce3ed00aaac30e6e1f64b5e5c5f395f4`
- asset: `SpatialAR-StableAR-latest.apk`
- asset size: `240172444` bytes
- APK SHA-256: `5ea1b8d34c5ea7dcaf651c0f4fea29c3e039b9877882016c09bb2665979d6ed7`
- stable direct download: `https://github.com/SirPaul-code/spatial_ar_coop/releases/download/spatial-stablear-latest/SpatialAR-StableAR-latest.apk`

## Integrated architecture

### Cross-device alignment

`CapturedFrame A+B -> StableAR XFeat correspondences -> Spatial AlignmentEngine -> Essential/PnP/3D-3D + depth/gravity/range -> AlignmentCoordinator -> LOCKED`

### Local material POI

`tap -> exact local ARCore exposure -> StableArSpatialRuntime -> ArCoreAdapter -> AttachmentEngine -> StableArSurfaceFrontend -> XFeat/LiteRT first, LK/ORB fallback -> VisualObservation -> observe() -> bounded StableAR material correction`

### Remote material POI

The existing visual/metric `SurfaceTargetResolver` remains only as a one-time local bootstrap proof for a remote POI. The next exact local ARCore exposure becomes the StableAR root; subsequent material tracking uses StableAR and bypasses the legacy resolver.

Moving-vehicle tracks remain outside StableAR because StableAR material attachments assume static material surfaces.

## What is verified

Software/build integration is now green. The installable GitHub Release APK is built from the branch containing the full StableAR integration and includes the pinned XFeat model payload.

This proves buildability and software wiring. It does **not** yet prove real-world accuracy, reacquisition quality, thermal behavior, or cross-device physical stability.

## Remaining physical acceptance gate

Install the same `SpatialAR-StableAR-latest.apk` on two physical Android phones and validate:

1. Both devices reach `ALIGNING -> LOCKED`.
2. Create a local POI on phone A; verify correct placement on phone B.
3. Walk around the object through large viewpoint changes.
4. Change distance substantially.
5. Test partial and full occlusion followed by reacquisition.
6. Test low-texture and repetitive-texture surfaces.
7. Test motion blur and meaningful lighting changes.
8. Record drift, incorrect relocalizations, correction latency, FPS and thermals.

Only after this physical two-phone acceptance should claims such as "StableAR works great physically" or quantitative accuracy/stability claims be made.

## Next agent instructions

Do not rebuild architecture from scratch. Start from branch `stablear/multiplatform-sdk`. Confirm the current branch HEAD and verify `spatial-stablear-latest` still points to a successful full-integration release. The immediate next engineering task is physical two-phone validation and instrumentation of any observed failure mode. Preserve the distinction between cross-device alignment, static material StableAR POIs, and dynamic vehicle tracks.
