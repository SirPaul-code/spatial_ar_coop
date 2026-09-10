# ShowMe: local camera assistance

## Product and branch

Branch: `showme/remote-assistance`.
Base: `a5970e9be7fef9434dbf2e681274dc56c9462fe4` from `fresh/no-map-runtime-poc`.

The camera owner runs the Android ShowMe application. A helper opens an invitation in a normal browser, sees the owner's camera and places pins, arrows, freehand strokes and circles. Guidance is reconstructed in the owner's physical AR world and remains there as the camera moves. Only one ARCore world exists in this flow; the helper does not need an AR-capable device or a second spatial alignment.

The first delivery is a functional **local network preview**, not a deployed internet service. It can be used to record a real two-device demonstration without an external server. The camera phone itself hosts the browser page and session API.

## Start a local session

1. Install `ShowMe-latest-release.apk` on the camera owner's supported Android 13+ ARM64 phone. The package is `com.sirpaul.showme`; it coexists with the original Spatial Sync application.
2. Connect the owner and helper to the same Wi-Fi, or connect the helper to the owner's phone hotspot. Networks with client isolation may prevent direct communication.
3. Open ShowMe and grant Camera permission. Google Play Services for AR may need installation/update. Slowly scan the surface from slightly different positions to establish depth.
4. Tap **Start local session**. Select the Wi-Fi/hotspot address if multiple local interfaces are present.
5. Share the invitation using Android's share sheet, copy the link, or have the helper scan its QR code.
6. The helper enters a name and joins in the browser. Select Pin, Arrow, Draw or Circle. Drawing automatically freezes the displayed image while the gesture is made; the server uses that exact frame's depth and camera pose.
7. After placement, the live view resumes unless the helper explicitly selected **Freeze frame**. The owner can move the phone and see the same annotations anchored in AR.
8. Undo, individual removal and Clear affect the physical annotations. End revokes the invitation and stops sharing. Owner-local annotations remain until cleared or the AR session is destroyed.

A link to a private LAN IP is not reachable by an expert elsewhere on the internet. Sending it through Messenger does not change that. Browser helpers do not use Android Wi-Fi Aware: shared Wi-Fi/hotspot is used because ordinary browsers cannot join the existing native Aware transport.

## Features present in this branch

- Separate native Android camera app, AR startup and permission flow.
- Native share sheet, selectable invite, clipboard and QR code.
- Responsive desktop/mobile browser helper UI, no account or browser camera permission.
- Color camera stream with atomic frame metadata, bounded backpressure, approximately 7 fps capture ceiling in the local JPEG path. Actual cadence depends on the phone, depth work and network; it has not been benchmarked on user hardware.
- Pins, arrows, circles and freehand strokes with labels and four colors.
- Frame freeze, auto-freeze during a gesture, undo, clear, individual removal and save-current-view image.
- Persistent-in-session AR annotations on the camera owner, including after the helper leaves.
- Frame/AR-epoch validation, bounded frame history and explicit failure messages for unsafe placement.
- The existing raw/full-depth/point-cloud sampler and existing multi-view texture/depth verifier with optional edge refinement are reused from `:app` without modifying those source files.
- Independent two-way WebRTC **audio** path, permission-gated; trusted HTTPS is needed for helper microphone capture.
- Local HTTP or optional local HTTPS with ephemeral session certificate; trusted PKCS12 import for controlled testing.
- CI with Kotlin/JUnit geometry and session tests, Node geometry tests and Chromium desktop/mobile interaction smoke tests.

## Exact spatial and temporal contract

`ShowMeRenderer` copies CPU camera pixels, camera pose, intrinsics and metric support points from one ARCore frame. No live ARCore `Frame`, `Image`, `Camera` or `Anchor` is passed to a networking/encoding worker. Images are closed after copying.

The network response is one binary envelope:

```
4-byte big-endian metadata length
UTF-8 JSON metadata
JPEG bytes
```

Metadata contains `id`, `epoch`, raw dimensions, rotation and the projections of the physical annotations for that frame. The browser rotates the raw image consistently and normalizes input relative to the actual letterboxed image, not the full viewport. It must discard an already-in-flight video response when the user begins a frozen drawing.

A draw command carries `requestId`, `frameId`, `epoch`, tool, color, label and normalized vertices. The owner looks up that historical frame. The image rotation is inverted, every selected point is reconstructed from local metric supports, and a local inverse-depth plane fit estimates depth on the exact pixel ray. Missing depth, competing foreground/background, excessive spatial span and invalid coordinates reject the whole gesture. There is no fixed-distance fallback and no use of the current camera's hitTest for historical pixels.

A drawing has one nearby ARCore anchor and local offsets for all vertices. Large strokes spanning more than 2.5 m from their first point are rejected rather than pretending one anchor is appropriate for arbitrary distances. Pins/arrows are static annotations, not semantic tracking of a moving battery/tool/car. A moved physical object is a distinct future problem.

Frame history keeps at most 40 ordinary frames, with an 8-second usable history. A manually/automatically frozen reference is pinned for up to 60 seconds. The browser resumes at about 55 seconds. An AR world replacement increments `epoch` and invalidates old frames. Backgrounding the owner pauses camera sharing and discards references; the owner must keep ShowMe foregrounded for this preview.

Optional surface verification runs on its own bounded worker. It requires visual and metric evidence; corrections need distinct-frame consensus, are limited to 8 cm per accepted correction and 15 cm cumulative travel per drawing. Failed verification keeps the existing anchor. These limits are guardrails, **not an accuracy measurement**. Current refinement corrects the drawing anchor translation; it is not a full deformable 3D stroke re-fit.

## Voice and HTTPS

Camera video and annotations work over ordinary local HTTP. Modern browsers do not allow `getUserMedia()` microphone access on an insecure LAN HTTP origin. The browser detects this instead of silently pretending voice is active. Where supported it can establish listen-only WebRTC audio; the helper may use a separate call.

**Secure session / voice setup** starts local HTTPS with a newly generated private key and a certificate whose SAN is the selected IP address. The UI shows its SHA-256 fingerprint. Self-signed certificate trust and permission behavior differ between browsers/devices; accepting a browser warning is not claimed to guarantee microphone availability. No certificate verification is globally disabled. A trusted PKCS12 certificate can be imported for a managed test environment; its key remains in app memory for that app session.

For a public release, use a normal trusted HTTPS origin and a provisioned signaling/TURN service. That removes local certificate setup and supports calls across different networks. The native audio engine and browser voice control are implemented here, but must be physically tested with Android audio routing and the chosen browser. CI browser tests do not simulate successful native voice or guarantee echo cancellation.

## Session security and current limits

- A fresh 192-bit random bearer token is generated per session and expires after one hour.
- The invitation uses the URL fragment. API authorization uses headers; the token is not put into request query strings or external analytics.
- Only one helper control lease is active. A stale lease can be replaced after 15 seconds.
- End revokes the token, closes the local server and ends audio.
- The server binds the selected private address, checks same-origin API requests, has strict path allowlists, bounded JSON payloads, a bounded command queue and mutation rate limits.
- CSP, no-store caching, no-referrer, nosniff and frame-denial headers are supplied.
- Plain HTTP is still observable by a network adversary. A capability token is not encryption. Use only a trusted local network in HTTP mode.
- No login, identity verification, team authorization, commercial licensing, payment service or production telemetry is currently implemented.
- No port forwarding, public tunnel, hosting subscription or external service was created.
- The APK currently uses the repository's existing **public development signing key** for repeatable sideloading. Use a private production signing identity before Play distribution.
- Memory retention is bounded; process restart does not preserve AR annotations. Persistent saved instructional sessions require deliberate relocalization and storage work.

## Code map

- `android/showme/`: new Android app module.
- `ShowMeActivity`: native UX, permissions, AR lifecycle, sharing, local HTTPS setup.
- `ShowMeRenderer`: camera snapshots, historical placement, physical annotations and optional surface verification.
- `ShowMeGeometry`: pure quaternion/projection/metric depth math.
- `ShowMeSession`: tokens, leases, frame history, AR epochs, command queue and idempotency.
- `LocalServer`: browser assets, session API, frozen-frame and draw handling.
- `RtcVoice`: native WebRTC audio; no cloud ICE server in LAN mode.
- `LocalTls`: per-session certificate and optional trusted key import.
- `ShowMeOverlay`: native AR annotation presentation.
- `showme/web`: static browser application.
- `showme/tests/browser-smoke.mjs`: synthetic browser protocol/UI fixture, not real camera footage.
- `.github/workflows/showme-ci.yml`: isolated build and `showme-latest` release.

The Gradle `prepareSpatialSources` task reuses a whitelist of core files from `android/app/src/main/java`. Copies exist only in generated build output. A proper shared Android library module can replace this build-time reuse later, but the working original application is not refactored in this delivery.

## Physical acceptance required

1. Install the new ShowMe app alongside Spatial Sync and check that Spatial Sync is not replaced.
2. Join from desktop Chrome and from an actual mobile browser over Wi-Fi/hotspot.
3. Pin a textured point within a few metres, then move/rotate the owner phone. Confirm that both native and streamed annotations follow the same physical point.
4. Draw an arrow/freehand/circle on a mapped surface and repeat from a different camera angle.
5. Move the owner phone while the helper is drawing on a frozen frame. The annotation must use the frozen frame's physical surface, not the new frame's pixel.
6. Reject unsupported depth, textureless/reflective surfaces, expired freezes and tracking loss without a plausible-looking false pin.
7. Undo, remove, clear, leave/rejoin and end/revoke. Check owner-local annotations remain after helper disconnection but are cleared on world reset.
8. Pause the owner and invoke the share sheet. No camera feed or microphone should continue unintentionally while backgrounded; resume only when returning.
9. Try secure-mode audio with a trusted certificate and both permissions. Check two-way sound, mute, end, reconnect and echo behavior. Document actual browser/certificate combinations.

Measure physical placement error and latency separately. Passing compilation, unit tests or browser mocks is not a measured centimetre-accuracy result.

## Next internet/product stage

Preserve the exact-frame/epoch/geometry contract while replacing the local endpoint with a session service. Implement trusted HTTPS, short-lived session capabilities, owner approval, authentication/team roles, signaling and TURN, disconnection recovery and a reliable video/frame timestamp association. A WebRTC video migration must associate the presented video frame with the corresponding AR data; sending merely 'latest pose' is not correct.

Accounts, a database and payments may be hosted independently of the realtime transport. Commercial entitlements must be checked server-side for hosted operations; client-only package checks are not abuse-proof licensing. Nothing in this section is claimed as already deployed.
