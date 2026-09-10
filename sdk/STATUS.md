# StableAR multiplatform SDK handoff/status

Last updated: 2026-09-10
Target branch: `stablear/multiplatform-sdk`
Frozen Android reference: `research_sdk` at `38978da448b6610606e9145367b9b31328bb5cc0`
Native checkpoint parent: `66b200005948c43b3d82a861930dd56da91fb5fe`

## Read this first

StableAR is a **local material-attachment stability layer above a host AR/XR runtime**. It is not a replacement SLAM engine and it is not a two-device synchronization system. ARCore/ARKit/OpenXR keep owning VIO/world tracking. StableAR owns exact-frame history, local metric surface fitting, immutable target identity, visual evidence gating and bounded correction state.

Do not change `research_sdk`, ShowMe branches or unrelated products while working on this branch. The Android-only Kotlin SDK remains the current physical reference and can be integrated into ShowMe independently of the native port.

## Architecture invariant

One implementation of the important maths/state lives in C++20:

`host AR/XR runtime -> platform adapter -> StableAR C ABI/C++ core -> attachment state`

Platform wrappers may convert coordinates, provide exact frame/depth data, create/locate/destroy native anchors, verify signatures and expose language-friendly APIs. They must not implement a second surface solver/correction engine.

Canonical StableAR camera coordinates are right-handed: `+X right, +Y down, +Z forward`.

## DONE and locally verified

### Native C++ core (`sdk/native`)

Implemented: rigid geometry/intrinsics/presentation mapping; robust inverse-depth local surface fit; bounded anchor-relative frame history and freeze leases; immutable root; multi-view original-ray refinement; parallax/visual/uncertainty/travel gates; held-out commit validation; stale epoch/generation/timestamp rejection; lifecycle state; platform-neutral `AnchorStore`; static/shared CMake packaging; `find_package(StableAR 0.2)`; stable C ABI v1.

Local gate on 2026-09-10:

```text
native static build: PASS
native shared build: PASS
C ABI smoke: PASS
installed CMake package consumer: PASS
ASan + UBSan: PASS
-Wall -Wextra -Wpedantic -Werror: PASS
native contract: PASS, 8 suites / 2087 assertions
```

These are synthetic/software invariant tests, **not physical accuracy measurements**.

### Entitlement/licensing protocol

Engineering protocol is `STABLEAR1.<payload>.<signature>` with canonical product/customer/app/platform/features/nbf/exp/grace claims and a P-256 ECDSA/SHA-256 verification boundary. The parser rejects malformed encoding, missing/duplicate/unknown claims, invalid values/times, oversized input, wrong product/platform/app/feature, bad signature and expiry; grace is capped at 31 days and expiry arithmetic is overflow-safe. Development issuer/key tooling lives under `sdk/licensing`; production private keys must never ship in an SDK/customer app.

During the final local gate a real base64url decoder regression was found: a valid entitlement was rejected as malformed. The decoder was replaced and the native contract is green again.

Entitlement enforcement is **not the legal licence** and can be patched on a controlled client. Commercial rights must come from an SDK/EULA agreement. See `COMMERCIALIZATION.md`.

### Existing Android-only reference

The Kotlin implementation at the frozen reference remains usable now: `sdk/core`, `sdk/arcore`, `sdk/vision`, `sdk/demo`. Do not remove it until native Android passes physical A/B parity. ShowMe may use that implementation immediately and must not wait for this port.

## IN PROGRESS / next implementation steps

The C++ core is the stable base. Platform distribution layers still need real build/typecheck/device gates before this can be called a complete multiplatform preview.

1. **Android / ARCore native AAR**: JNI null/closed-handle hardening, typed Kotlin session, host-owned ARCore adapter, exact pose/intrinsics/depth provenance, P-256 verifier and package+signing-certificate binding. No second ARCore session/camera.
2. **Shared native vision**: optional OpenCV immutable-root LK forward/backward fast path plus ORB/RANSAC/homography reacquisition; C ABI; separate Android vision AAR for independent provenance audit.
3. **Apple / ARKit**: device+simulator XCFramework, Swift C-ABI façade, host-owned `ARSession`/ARAnchor adapter, exact frame intrinsics/transform and optional LiDAR `sceneDepth`, CryptoKit P-256. Do not imply unrestricted visionOS camera access.
4. **OpenXR / Meta**: generic host-session/reference-space adapter; session-local `LOCAL` anchoring contract; RGB/depth supplied by host/Quest APIs; physical Quest 3/3S validation required.
5. **Unity**: P/Invoke C ABI, `IAnchorStore`, explicit Unity left-handed <-> StableAR right-handed conversion, ABI layout test, UPM layout.
6. **CI / packaging**: Ubuntu native/OpenCV/OpenXR/sanitizers/P-256; Android NDK AARs/lint; macOS XCFramework/Swift typecheck; Unity C# compile/ABI; branch guard against unrelated product changes.
7. **Private-repo export**: export only SDK/product files and selected CI/docs, not ShowMe/app history. No automatic public release.

## Known limitations

Static material/surface attachments only; current correction is 1D along the immutable original ray, not a full fixed-lag factor graph; no moving/deforming-object solution; no generic cross-session map persistence yet; no calibrated device covariance; no measured phone/Quest CPU/GPU/thermal/battery/motion-to-photon numbers; no independent mm/cm ground truth. Initial absolute depth can still be biased even when the point looks stable. Native OpenCV redistribution is not commercially cleared until exact bundled third-party provenance/notices are reconciled. Google/Apple/Meta platform terms and owner SDK/EULA terms remain release gates.

## Next agent start point

1. Read this file plus `MULTIPLATFORM.md`, `COMMERCIALIZATION.md`, `VERSIONING.md`, `VALIDATION.md`.
2. Run the native gate first; fix core before adapters if it is red.
3. Continue only on `stablear/multiplatform-sdk`.
4. Preserve `research_sdk@38978da...` as the Android physical reference.
5. Add adapters one at a time and require a real build/typecheck before changing status from IN PROGRESS to DONE.
6. Update this file after each checkpoint with exact tests/results.
