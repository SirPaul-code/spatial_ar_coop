# Exact binary findings, not blanket commercial clearance

Inspected on 2026-09-10. The first fully successful SDK build was commit
592d5dc6f14ef66f68a1c2bf7b7a68b8e1bcc317, Actions run 34473392086.
Subsequent packaging changes distinguish notice documents from detector XML and
separate matching OpenCV binaries from an independently supplied C++ runtime.

## Resolved dependency scope

The SDK/demo inventory contains 10 deliverables: our three modules and seven
external Maven components. External versions are ARCore 1.56.0, OpenCV 4.12.0,
Kotlin stdlib 1.9.24, its jdk7/jdk8 bridge artifacts 1.8.20, JetBrains annotations
13.0, and AndroidX annotation 1.3.0. All seven POM licence declarations were
retrieved. This is NOT a count of all statically linked native components.
ARCore's binary has custom terms, not the Apache licence of its example sources.

## OpenCV byte-level provenance

The official OpenCV 4.12.0 Android archive was checksum-verified against:
fd7f2332331b4eb8b67e55137281cfb16823c9399d90deb9cfa3476783b99e35
Source: https://github.com/opencv/opencv/releases/tag/4.12.0

All four JNI libopencv_java4.so ABI variants match that official release exactly.
The Maven AAR also has identical copies under prefab/libs/android.<abi>; these
must be recognized as ABI paths, not falsely labelled binary mismatches.
The revised report records eight matching OpenCV entries across four ABIs.

Four libc++_shared.so runtime entries are present in the Maven AAR but have no
counterpart in that official OpenCV ZIP. They are NOT verified by matching the
OpenCV files. Their hashes are retained, with status NO_RELEASE_COUNTERPART.
The demo ships ARM64 only; the AAR evidence inventory includes all four ABIs.
This distinction remains a distribution review item rather than a hidden success.

The ARM64 binary's embedded build information identifies Android NDK
27.2.12479018 / Clang 18.0.3 and says Non-free algorithms: NO. Listed third-party
components include cpufeatures, protobuf, ade, TBB, ITT, libjpeg-turbo, WebP, PNG,
TIFF, OpenJPEG, OpenEXR, tegra_hal and KleidiCV. This provenance identifies work
still needed; a permissive top-level OpenCV licence does not relicense them.

## Actual notices and dual licensing

The official package contains 31 genuine notice documents accepted by the revised
collector, including authorship files with .ijg/.ilmbase/.openexr extensions.
A detector filename containing license_plate is not a licence document and is
excluded. No detector data is loaded or intentionally bundled by this SDK.
The verified WebP BSD notice from the matching OpenCV source tag is added as a
supplement, pinned to Git blob 7a6f99547d4d6b4c5e6d5321acfaeba313e08de3:
https://github.com/opencv/opencv/blob/4.12.0/3rdparty/libwebp/COPYING

ITT includes a GPL text AND a BSD text. The compiled entry-point sources both
explicitly state GPL-2.0-only OR BSD-3-Clause. The alternative selected for this
integration is BSD-3-Clause, retaining its notices; finding the GPL text alone
does not establish a mandatory GPL licence for this application.
https://github.com/opencv/opencv/blob/4.12.0/3rdparty/ittnotify/CMakeLists.txt
https://github.com/opencv/opencv/blob/4.12.0/3rdparty/ittnotify/src/ittnotify/ittnotify_static.c
https://github.com/opencv/opencv/blob/4.12.0/3rdparty/ittnotify/src/ittnotify/jitprofiling.c

## Remaining review before a commercial redistribution

Reconcile the full native build manifest and actual notice coverage, especially
C++ runtime, tegra_hal/carotene and KleidiCV components. Verify exact fork/version
provenance where an upstream binary does not supply a complete native SBOM.
The included notices are evidence, not a claim that this remaining audit is done.
Separately finalize the owner's commercial terms, the applicable Google ARCore
API-client obligations and independent-value assessment, and the documented
Android toolchain/distribution-model review. Patent rights are a separate matter.

commercialReleaseCleared remains false. No research APK is represented as a
commercially cleared release. None of these open review items has been silently
converted into an assumed permissive licence by the build scripts.
