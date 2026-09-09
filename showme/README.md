# ShowMe

**Share your view. Let someone point the way.**

A separate Android camera-owner app plus an install-free browser helper, built on the spatial pipeline in this repository. This branch implements the **local Wi-Fi / hotspot preview**, not an Internet service.

## What is implemented

- A dedicated Android application (`com.sirpaul.showme`) that coexists with Spatial Sync.
- Invite-only sessions with a fresh 192-bit token, an Android share sheet, copy-link and QR code.
- Explicit camera-owner approval before any frames or commands reach the helper.
- Full-colour live camera frames delivered by the phone's embedded LAN endpoint. No external server, account or cloud storage.
- Browser pin, temporary pointer, arrow, freehand stroke and circle tools; four colors and optional labels.
- Exact-frame freezing during drawing, historical pose/depth lookup and real ARCore anchors. Marks are not a screen overlay glued to the display.
- Raw/full-depth/feature support and tracked-plane fallback, bounded surface correction, existing SIFT/homography, multi-view atlas and optional edge refinement.
- Undo, individual remove, clear, pause/resume, text notes, browser screenshots and silent view recording (where MediaRecorder is supported).
- WebRTC audio negotiation, a native microphone toggle and browser listen-only fallback on ordinary LAN HTTP. Browser microphone capture is only offered in a secure context.
- Bounded frame history, 45-second historical-frame validity, request deduplication, limits and connection recovery.

## Try it on two devices

1. Install the ShowMe APK from a **ShowMe local prerelease**, not `SpatialSync-latest-release.apk`.
2. Put both devices on the same Wi-Fi. Alternatively enable the camera phone's hotspot and connect the helper to it. Guest-network client isolation can block this.
3. Open ShowMe on the camera phone. Allow camera access; install/update Google Play Services for AR if prompted.
4. Tap **Start sharing**, then **Share link**, **Copy link**, or let the helper scan the QR code.
5. Open the full link in Chrome/Safari/another browser. Enter a name and tap **Join camera session**.
6. The camera owner taps **Allow**. Slowly move the phone so ARCore can obtain useful depth/planes.
7. The helper taps **Pin**, or drags an arrow/stroke/circle. Move the camera and verify the mark remains on the actual surface.
8. **Freeze** holds a view for up to 40 seconds. The phone resolves marks against that saved view even if its camera has moved meanwhile.
9. **End** invalidates the link and clears the session. Helper disconnection alone retains marks on the camera phone.

**Sharing a link through Messenger does not make a local IP reachable over the Internet.** The helper still needs the same LAN/hotspot in this version. A normal browser cannot join the native Wi-Fi Aware session, so ShowMe intentionally uses standard LAN IP instead.

## Audio boundary

The camera owner must explicitly enable **Mic on**. The helper can then start audio. HTTP on a private LAN IP is not a browser secure context: helper microphone access is disabled there, although receive-only WebRTC audio may be available. ShowMe says this in both UIs rather than pretending the microphone is working.

The media/signaling integration can support two-way voice when the helper page and signaling endpoint are served in a trusted secure context. A production HTTPS origin, signaling and TURN connectivity are a separate deployment task; not shipped or claimed here. The local AR/video/annotation test does not require voice.

## Local build

Requires JDK 17, Android SDK 36 and Internet access for build dependencies. The shipped application needs no Internet for this LAN flow.

```sh
cd showme/android
../../android/gradlew --no-daemon testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Windows, from the same directory:

```powershell
..\..\android\gradlew.bat --no-daemon testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

APKs: `showme/android/app/build/outputs/apk/`. The committed development certificate is used only for consistent sideload updates. **It is public and must not be used for a Play Store/production signing identity.**

Browser tests:

```sh
cd showme/web
npm install
npm test
npx playwright install --with-deps chromium
npm run test:browser
```

## Not shipped as production features

Internet sessions, account authentication, TURN infrastructure, billing/licences, Play publishing, reliable background camera sharing, cross-session anchor persistence, collaboration with multiple simultaneous helpers and a measured centimeter-error guarantee. An AR surface mark follows the static environment; it is not semantic tracking of a battery or car that physically moves independently.

HTTP frames are not transport-encrypted. Use only a trusted WPA-protected LAN/hotspot with this preview. Do not port-forward the embedded endpoint or expose it to the public Internet. Ownership approval and tokens do not replace TLS.

See `AGENT_CONTEXT.md`, `PROTOCOL.md` and `RELEASE_CHECKLIST.md` for implementation invariants and the physical acceptance test.
