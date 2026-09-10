# StableAR multiplatform SDK handoff/status

Last updated: 2026-09-10
Target branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk@38978da448b6610606e9145367b9b31328bb5cc0`

## Read this first

StableAR is a **local material-attachment stability layer above a host AR/XR runtime**. It is not a replacement SLAM engine and it is not a two-device synchronization system. ARCore/ARKit/OpenXR keep owning VIO/world tracking. StableAR owns exact-frame history, local metric surface fitting, immutable target identity, visual evidence gating and bounded correction state.

Do not change `research_sdk`, ShowMe branches or unrelated products while working on this branch. The Android-only Kotlin SDK remains the current physical reference and can be integrated into ShowMe independently of the native port.

Canonical StableAR camera coordinates are right-handed: `+X right, +Y down, +Z forward`.

## Checkpoints already on this branch

- `66b200005948c43b3d82a861930dd56da91fb5fe` — native C++20 core + C ABI v1.
- `67d627cdae8a63185c34db8ced35e5af61f8e4bf` — durable handoff/branch rules + entitlement tooling.
- `edf7ed4701ce7d4d3cd5c6fdd3fcadf0d3b3897e` — shared OpenCV visual-tracker source/C ABI.
- `40b1d34e9c7990e56809382b381ff6f9f6e43f39` — Android/ARCore native AAR/JNI source.
- `2faf3b035c7f538846d3ec5dfaa7a15d7231f091` — separate native OpenCV Android AAR source.
- `09a5fd02e70ace512f9673d25082fd9cfe37fa45` — Apple XCFramework/Swift/ARKit source.
- `267f81e9e043ceec31cffc970521a3b8f95b9a45` — generic OpenXR session-local adapter source.
- `8526b441be137d9fbbb63c63412c798d8c47c6f9` — Unity P/Invoke/UPM source + LP64 ABI contract.

All ref moves were non-force updates.

## DONE and locally verified

### Native C++ core (`sdk/native`)

Implemented: rigid geometry/intrinsics/presentation mapping; robust inverse-depth local surface fitting; bounded anchor-relative frame history/freeze leases; immutable original root; multi-view original-ray depth refinement; parallax/visual/uncertainty/travel gates; held-out commit validation; epoch/generation/timestamp rejection; lifecycle state; platform-neutral `AnchorStore`; static/shared CMake packaging; installed `find_package(StableAR 0.2)` package; stable C ABI v1.

Latest local gate:

```text
native static build: PASS
native shared build: PASS
C ABI smoke: PASS
installed CMake package consumer: PASS
ASan + UBSan: PASS
-Wall -Wextra -Wpedantic -Werror: PASS
native contract: PASS, 8 suites / 2087 assertions
root sdk CMake/CTest discovery: PASS, 2/2 tests
```

These are synthetic/software invariant tests, **not physical accuracy measurements**.

### Entitlement/licensing engineering

`STABLEAR1.<payload>.<signature>` uses canonical product/customer/app/platform/features/nbf/exp/grace claims and a P-256 ECDSA/SHA-256 signature boundary. Parser rejects malformed/unknown/duplicate/missing claims, wrong product/platform/app/feature, invalid times, oversized input, bad signatures and expiry; offline grace is capped at 31 days and expiry arithmetic is overflow-safe. Development P-256 issuer/signature contract passes locally.

A real decoder regression was found during reconstruction (valid token rejected as malformed); the base64url decoder was replaced and native/P-256 tests are green again.

Entitlement enforcement is **not the legal licence**. Production private keys must remain server/KMS/HSM side and commercial rights require SDK/EULA terms. See `COMMERCIALIZATION.md`.

### Local wrapper/source checks completed

- Android core JNI: host-JDK JNI syntax compile with `-Wall -Wextra -Wpedantic -Werror` — PASS.
- Android pure Kotlin `NativeStableAr` façade — local Kotlin compile PASS.
- Native-vision Android JNI syntax compile — PASS.
- Native-vision pure Kotlin wrapper — local Kotlin compile PASS.
- Apple pure `StableARSession.swift` C-ABI façade — local Swift typecheck PASS.
- SDK-only private-repo export tool — PASS; forbidden root `showme/`, `android/`, `server/`, `research_sdk/` do not leak.
- Multiplatform workflow YAML parse — PASS.

## IMPLEMENTED SOURCE, CI TOOLCHAIN VALIDATION PENDING

These modules are now source-complete enough for CI compilation, but must not be called platform-ready until their corresponding CI job passes.

### Shared native vision (`sdk/native-vision`)

Optional OpenCV front-end with immutable root, LK forward/backward fast path and ORB/RANSAC/homography root reacquisition plus C ABI. Image matching only proposes evidence; C++ geometry remains correction authority. Local container lacked OpenCV development headers, so actual CMake/OpenCV compilation is delegated to Linux CI.

### Android / ARCore (`sdk/native-android`)

NDK/JNI + typed Kotlin session + host-owned `ArCoreNativeAdapter`; no second camera/session. ARCore camera axes are converted once; raw/smoothed/point-cloud depth source timestamps are preserved. JNI rejects null handles; Kotlin blocks use after close; `observe()` returns actual correction-commit status. Android entitlement verifies P-256 and binds to package + signing-certificate SHA-256. Real Gradle/NDK/ARCore compile is pending CI.

### Android native vision (`sdk/native-vision-android`)

Separate AAR links OpenCV 4.12.0 and native vision ABI, intentionally separated from core for size/provenance auditing. Real Android Prefab/native link is pending CI.

### Apple / ARKit (`sdk/apple`)

Device+simulator core XCFramework builder, Swift C-ABI façade, host-owned ARKit/ARAnchor adapter, optional `sceneDepth`/smoothed fallback and CryptoKit P-256 verifier are present. `stablear_c.h` copy is kept byte-identical to native public C header. Actual Xcode/iOS ARKit/CryptoKit compile is pending macOS CI. Do not imply unrestricted visionOS camera access.

### OpenXR / Meta (`sdk/openxr`)

Generic OpenXR adapter stores session-local anchors as `XR_REFERENCE_SPACE_TYPE_LOCAL` child spaces and locates them against the host local space at host-supplied `XrTime`. Camera conversion is OpenXR view -> StableAR canonical camera. RGB/depth are host inputs; Quest-specific camera/environment-depth APIs do not contaminate core. Linux OpenXR compile and physical Quest 3/3S validation are pending.

### Unity (`sdk/unity`)

UPM source package/PInvoke C ABI, `IAnchorStore` host callbacks and explicit Unity left-handed <-> StableAR right-handed coordinate conversion are present. LP64 managed ABI expected sizes are captured in an executable .NET contract. Managed compile/ABI execution is pending CI/Unity engine validation.

## CI / packaging next gate

The next checkpoint adds `.github/workflows/stablear-multiplatform.yml` plus root Gradle/CMake wiring. Required jobs:

1. `scope`: prove only `sdk/**` + the StableAR workflow changed from frozen base.
2. `native-linux`: static/shared core, CMake package consumer, symbols, ASan/UBSan/Werror, OpenCV tracker, OpenXR adapter, entitlement, SDK-only export.
3. `android`: NDK core AAR + OpenCV vision AAR + lint while retaining buildability of the original Kotlin reference modules.
4. `apple`: iOS device+simulator XCFramework + Swift/ARKit/CryptoKit typecheck.
5. `unity-abi`: .NET compile + LP64 struct-layout contract.

After CI, update this file with the exact workflow run, failing/fixed jobs and final commit SHA.

## Known limitations that must remain explicit

- static material/surface attachments only;
- current correction is 1D along the immutable original clicked ray, not a full fixed-lag factor graph;
- no moving/deforming-object solution;
- no arbitrary cross-session map persistence contract yet;
- no calibrated device covariance;
- no measured smartphone/Quest CPU/GPU/thermal/battery/motion-to-photon data;
- no independent physical mm/cm ground-truth claim;
- initial absolute depth can be biased even when attachment looks visually stable;
- native OpenCV redistribution is not commercially cleared until exact bundled third-party provenance/notices are reconciled;
- Google/Apple/Meta platform terms and owner SDK/EULA terms remain commercial release gates.

## Existing Android-only path remains usable NOW

Do not remove or block `research_sdk@38978da...`. Its Kotlin `sdk/core`, `sdk/arcore`, `sdk/vision`, `sdk/demo` are the current physically observed Android reference and can be integrated into ShowMe immediately. Migrating ShowMe to native C++ should happen only after native Android physical A/B parity.

## Next agent start point

1. Read this file plus `AGENTS.md`, `MULTIPLATFORM.md`, `COMMERCIALIZATION.md`, `VERSIONING.md`, `VALIDATION.md`.
2. Work only on `stablear/multiplatform-sdk`.
3. Fetch live branch head before every write; never force-update it to discard another agent's work.
4. Run native gate first; fix core before adapters if red.
5. Run/fix the multiplatform CI until compile/typecheck jobs are green.
6. Do physical Android/iOS/Quest validation before upgrading those platforms from compile-tested to device-tested.
7. Update this file after every meaningful checkpoint.
