# StableAR SDK

StableAR is an attachment-stability layer above a host AR/XR runtime. It does not replace ARCore, ARKit or OpenXR tracking. The runtime supplies camera poses/anchors; StableAR keeps exact-frame history, fits a local metric surface, preserves immutable material-point identity, and applies only evidence-gated corrections.

Current product version: **0.2.0-research**. Native C ABI version: **1**.

## Two implementation tracks on this repository

- `core`, `arcore`, `vision`, `demo`: the known-good Android/Kotlin research implementation. Keep this path available for the current ShowMe integration and Android physical A/B testing.
- `native`, `native-vision`, `native-android`, `native-vision-android`, `apple`, `openxr`, `unity`: the shared C++20 multiplatform implementation. All new platform wrappers call the same native solver instead of reimplementing it.

`research_sdk` remains the frozen Android reference. The multiplatform work is developed on `stablear/multiplatform-sdk`; it must not block current Android use.

## Native architecture

```text
                           StableAR C++20
       geometry / surface fit / frame ledger / attachment solver
                  correction gates / entitlement parser
                                |
                         stable C ABI v1
              +-----------------+------------------+
              |                 |                  |
       Android / ARCore      iOS / ARKit       OpenXR / XR
          Kotlin/JNI          Swift/C ABI          C++
              |                 |                  |
              +-----------------+------------------+
                                |
                         Unity C# binding
```

The canonical camera convention inside StableAR is right-handed `+X right, +Y down, +Z forward`. Every platform adapter converts from its host convention exactly once. Unity has an explicit handedness conversion helper; a handedness change is a reflection, not an ordinary 180-degree rotation.

## What is implemented

- shared C++20 geometry, robust inverse-depth surface fitting and bounded anchor-relative frame ledger;
- immutable clicked root and 1D original-ray multi-view refinement;
- parallax, visual-quality, uncertainty and total-travel gates;
- held-out validation before correction commit;
- epoch/generation/source-frame rejection for stale asynchronous work;
- stable C ABI for JNI, Swift and Unity;
- optional shared OpenCV LK/ORB/homography visual front-end;
- Android native core AAR and separate OpenCV vision AAR;
- ARCore adapter using a host-owned `Session` and exact ARCore timestamps/depth provenance;
- iOS core XCFramework builder and Swift/ARKit adapter;
- native OpenXR session-local anchor adapter using a canonical `LOCAL` reference space;
- Unity UPM C# binding and Unity coordinate conversion helpers;
- offline-first signed entitlement format (`STABLEAR1`) with P-256 signature verification at the platform boundary;
- an SDK-only export tool for moving the product into a dedicated private repository later.

See `MULTIPLATFORM.md`, `VERSIONING.md`, `COMMERCIALIZATION.md`, `VALIDATION.md` and platform READMEs for contracts and limitations.

## Build

Native core:

```sh
cmake -S sdk/native -B build/stablear -DSTABLEAR_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/stablear
ctest --test-dir build/stablear --output-on-failure
```

Android AARs (JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1):

```sh
gradle -p sdk :native-android:assembleRelease :native-vision-android:assembleRelease
```

Apple device + simulator XCFramework (macOS/Xcode):

```sh
sh sdk/apple/scripts/build_xcframework.sh
```

Optional native OpenCV/OpenXR modules are regular CMake projects under `sdk/native-vision` and `sdk/openxr`.

## Host integration contract

The host owns the AR/XR session, camera, render loop and lifecycle. StableAR must never open a second AR session or pair an old image with a new camera pose. Feed each camera exposure with its exact pose, intrinsics, timestamp and metric depth provenance. Preserve the returned frame token when an image is streamed/frozen remotely.

Placement is intentionally conservative. If exact source-frame history, local depth support or coordinate mapping is unavailable, fail closed rather than guessing. Visual matching proposes evidence only; it never directly teleports an anchor. Camera and annotation should be rendered on the same presentation timeline and final screen-space positions should not be EMA-smoothed.

## Commercial status

This is an engineering preview, not a claim that the SDK is legally cleared for commercial redistribution. The entitlement mechanism is technical enforcement; the legal grant must come from an SDK/EULA agreement. Exact third-party binary provenance/notices, ARCore/Apple/Meta terms, physical-device validation and release signing remain release gates in `COMMERCIALIZATION.md`.

Do not claim millimetre/centimetre accuracy, platform superiority, FPS, thermal endurance, battery cost or motion-to-photon figures from synthetic/CI tests. Physical measurements are required by `VALIDATION.md`.
