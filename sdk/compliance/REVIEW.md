# Commercial distribution review

Reviewed 2026-09-10. This is an engineering licence inventory and release checklist,
not a legal opinion or a promise that a product is cleared for sale.

## What is shipped in this implementation

| Component | Version / role | Verified primary licence evidence | Consequence |
|---|---|---|---|
| StableAR original Kotlin code | 0.1.0-research | Repository owner-controlled; no OSS grant added | Owner must choose the commercial EULA and contributor/IP policy. |
| Kotlin standard library | 1.9.24 | https://github.com/JetBrains/kotlin/blob/v1.9.24/license/LICENSE.txt | Apache-2.0; retain required notices. Compiler-only dependencies are not automatically app dependencies. |
| JetBrains annotations | Gradle-resolved version in SBOM | https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt | Apache-2.0. |
| Google ARCore binary | 1.56.0; host dependency | https://github.com/google-ar/arcore-android-sdk/blob/main/LICENSE section 1; https://developers.google.com/ar/develop/terms | NOT blanket Apache-2.0. ARCore Additional Terms + Google APIs Terms apply to AAR. |
| OpenCV | 4.12.0; optional vision module | https://opencv.org/license/ and https://github.com/opencv/opencv/blob/4.12.0/LICENSE | Apache-2.0 for OpenCV; native transitive subcomponents have separate notices. |

ARCore is compileOnly in the adapter, not copied into our AAR and not represented
as our own tracker. The integrating application explicitly supplies Google ARCore
and accepts its terms. The demo includes it for installation testing.
The ARCore source samples' Apache licence is not the binary SDK's licence.

Google requires meaningful independent value beyond Google features, end-user
Google Terms/Privacy disclosure, and applicable user-privacy requirements.
https://developers.google.com/terms/ also applies. Whether this particular paid
SDK and distribution model satisfy those contractual conditions requires legal
review. An adapter boundary does not exempt a product from the terms.
The demo includes a visible disclosure and links; a commercial host must include
its own required terms, privacy documentation and distribution compliance.

OpenCV's top-level permissive licence is not enough to certify every statically
linked native component. CI extracts embedded notices, records exact AAR/JAR/SO
hashes and a CycloneDX component inventory. Reconcile the selected OpenCV binary's
native build manifest and notices before sale. No nonfree algorithm or downloaded
model is enabled by this SDK. No FFmpeg package is added by our build.
This does not prove which codecs an upstream binary may contain.

## Existing applications are separate, not SDK dependencies

These dependencies were read in the existing ShowMe/Spatial build files. They are
NOT pulled into the new SDK build. Their top-level evidence is listed to avoid
mistaking repository membership for SDK shipment:

| Existing component | Declared version | Evidence / action |
|---|---|---|
| NanoHTTPD | 2.3.1 | Modified BSD: https://github.com/NanoHttpd/nanohttpd . Retain copyright/conditions. |
| WebRTC Android wrapper | 144.7559.09 | Wrapper MIT: https://github.com/webrtc-sdk/android/blob/main/LICENSE . Native WebRTC BSD + Apache patches: https://github.com/webrtc-sdk/webrtc . MIT wrapper does NOT clear codecs/native bundle. |
| Bouncy Castle PKIX and transitives | 1.84 | MIT-style: https://www.bouncycastle.org/license.html . Retain notices for bcprov/bcutil too. |
| ZXing core | 3.5.3 | Apache-2.0: https://github.com/zxing/zxing/blob/master/LICENSE . Retain NOTICE. |
| MediaPipe tasks-vision | 0.10.35 | https://github.com/google-ai-edge/mediapipe/blob/master/LICENSE . Framework and downloaded detector weights are separate reviews. |
| JUnit | 4.13.2; tests only | https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt . Test tooling not distributed as SDK runtime. |
| JSON-java | 20240303; tests only | https://github.com/stleary/JSON-java . Verify exact tagged artefact, do not assume old JSON licence. |
| Playwright | 1.55.0; browser tests only | https://github.com/microsoft/playwright/blob/main/LICENSE . Browser distributions carry their own notices; not shipped in SDK. |

The old app's detector model is neither copied nor downloaded by the standalone
SDK build. This table is a declaration-level review of those other apps, not their
resolved transitive binary clearance. A separate shipping ShowMe product still
needs its own complete SBOM, including WebRTC native codecs and cryptographic
export/distribution requirements. Patent/codec rights are not automatically
settled by an open-source copyright licence.

## Candidate learned models: none included or downloaded

- CoTracker: official code/model identify CC-BY-NC. Excluded from commercial SDK
  unless separate permission is obtained. https://github.com/facebookresearch/co-tracker
  and https://huggingface.co/facebook/cotracker .
- LightGlue / Glue Factory: Apache-2.0 for covered code and weights, but original
  SuperPoint/SuperGlue in nonfree paths have different restrictive terms. The
  newer open SuperPoint is not the same artefact as the original. Pin exact files
  and checksums before enabling. https://github.com/cvg/glue-factory .
- XFeat: top-level Apache-2.0. Audit exact weights, training data and LighterGlue
  additions independently before bundling. https://github.com/verlab/accelerated_features/blob/main/LICENSE .
- TAPNet's current README explicitly places linked pretrained TAPIR, BootsTAPIR,
  TAPNext, TAPNext++ and TRAJAN checkpoints under Apache-2.0. TAPVid-3D and source
  video/dataset licences differ. None of these weights is included here.
  https://github.com/google-deepmind/tapnet/blob/main/README.md .

## Build/research-only tools

Gradle 8.11.1 (Apache-2.0), Android Gradle Plugin 8.10.1 (Android Open Source Project,
Apache-2.0 code plus toolchain terms), Kotlin compiler 1.9.24 (Apache-2.0 and its
third-party notices), JDK17 and Android SDK are build tools, not bundled SDK code.
Android SDK tools have Google's own SDK terms; JDK redistribution differs from
merely running a JDK to compile application code. CI uses installed toolchains,
not redistributed JDK/Android SDK packages.
Primary sources: https://github.com/gradle/gradle/blob/v8.11.1/LICENSE ;
https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/NOTICE ;
https://developer.android.com/studio/terms ; https://openjdk.org/legal/gplv2+ce.html .
The earlier NumPy/SciPy/OpenCV Python research environment is not shipped in AARs.

## Enforced release posture

`audit_licenses.py` resolves actual SDK/demo artefacts through Gradle, records
POM inheritance, hashes and embedded notices. Unknown dependencies fail its audit
step. The generated report deliberately says `commercialReleaseCleared: false`:
(1) owner EULA pending; (2) Google terms/independent-value review; (3) native-bundle
notice/provenance reconciliation. Passing compilation or an allowlist does not
turn these unresolved obligations into approval.

No repository-wide licence was changed. No MIT/Apache grant was attached to the
owner's original SDK code. Public hosting alone is not a complete commercial
licensing strategy; review contributor rights before accepting external code.
