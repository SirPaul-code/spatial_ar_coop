# StableAR 0.2 multiplatform architecture

StableAR is an attachment-stability layer above a host AR/XR runtime; it is not a replacement SLAM engine.

```
                       StableAR C++20 core
                 geometry / state / correction
                             |
       +---------------------+--------------------+
       |                     |                    |
 Android / ARCore       Apple / ARKit       OpenXR / Meta
 native-android         apple adapter        openxr adapter
       |                     |                    |
       +---------------------+--------------------+
                             |
                        stable C ABI
                             |
                         Unity package
```

## Support matrix

| Target | Core | Runtime adapter | RGB/depth provider | Packaging | Status |
|---|---|---|---|---|---|
| Android / ARCore | C++20 | `ArCoreNativeAdapter` | ARCore camera/depth + optional native OpenCV vision | core AAR + vision AAR | implementation + CI compile target |
| iOS / ARKit | C++20 | `ARKitStableARAdapter` | ARFrame + optional sceneDepth | XCFramework core + Swift adapter | implementation + macOS CI compile target |
| Native OpenXR | C++20 | LOCAL reference-space AnchorStore | host-supplied | CMake static/shared | implementation + Linux compile target |
| Meta Quest 3/3S | C++20/OpenXR | OpenXR or host XR anchors | Meta host camera/depth API | native/Unity | integration boundary; physical Quest validation required |
| Unity | stable C ABI | `IAnchorStore` host bridge | host-supplied | UPM source + native plugin | implementation; engine/device validation required |

## What is shared

Surface fitting, frame history, immutable root identity, ray refinement, parallax/uncertainty gates, held-out commit, state snapshots, travel limits, epoch/generation safety, licensing claim parser and C ABI are one C++ implementation.

Platform code is limited to coordinate conversion, native anchor lifecycle, frame/depth extraction, cryptographic signature verification, and host-language ergonomics.

## Deliberate limitations

- Static material attachments only; moving/deforming objects are not solved.
- Current correction is 1D along the immutable original ray, not a full fixed-lag factor graph.
- Native OpenCV visual front-end exists as an optional source module and Android AAR; binary redistribution still requires the project's native third-party provenance review.
- Cross-session persistence is not promised by the generic core. Vendor persistent anchors/maps can be layered above it later.
- ARKit `sceneDepth` is optional; non-LiDAR devices need another metric-depth path or conservative visual/multi-view behaviour.
- Vision Pro camera access has different platform/entitlement constraints and is not claimed by the iOS ARKit adapter.
- CI compilation is not physical-device validation. No mm/cm, thermal, FPS, battery, or motion-to-photon claims are authorized without the protocol in `VALIDATION.md`.

## Migration

Keep `research_sdk` Android-only code intact until the native AAR reaches physical parity. ShowMe can use the known-good Kotlin Android SDK now; migrating ShowMe to the C++ core should be a later A/B-tested change rather than blocking current product work.
