# ShowMe 0.3: field fixes and Internet invitations

Working branch: `showme/remote-assistance`. Previous field-tested source: `b5d43189095911fc56bacab4381678cf4db5efe2` (0.2 WebRTC).

## What this iteration addresses

The user supplied a real device screenshot (13 fps, circle slightly below PCB component `3R3`) and a browser recording reporting periodic stalls. Those observations demonstrate a problem, but do not alone establish which error came from depth, projection, tracking, transport or battery state. Do not label a single screenshot as a measured centimetre error.

The old `ShowMeRenderer.captureIfDue()` performed whole-camera YUV byte-by-byte copying and `MetricSupportSampler.sample(...,8000)` on the AR/GL thread every second once a mark existed. The old surface repair tracked the first vertex and translated the whole drawing. These are concrete code issues addressed here; successful compilation is not proof that all physical-device stutter or drift disappeared.

## Live video / frame identity

Real WebRTC RTP video remains mandatory. No JPEG live polling or substitute slide show.

- Camera OES texture -> shared EGL -> SurfaceTextureHelper -> native WebRTC encoder.
- 30-fps source/sender target; measured FPS shown separately. Camera config prefers <=1280 CPU-image dimensions rather than maximum-size readback. Video adaptation prefers maintaining frame rate.
- Hidden CRC16-protected video footer retains the exact frame identity. It is now one tiny texture upload/draw instead of 128 scissor/clear calls per frame.
- Browser video staging is GPU-backed; only two footer rows are read back for identity. Do not reintroduce full-frame `getImageData`/`willReadFrequently` for live presentation.
- Compact depth rasters and pose are copied for the same exposure ID. JSON projections and SCTP metadata work run off GL.
- History is bounded by 180 frames, six seconds and 48 MiB, whichever constrains it first. Missing history is a rejected mark, not a guessed newer pose.
- Metadata includes full AR epoch, camera exposure timestamp, intrinsics, translation/quaternion, raw dimensions and rotation. The phone's saved record is authoritative. Client-supplied pose/intrinsics are not trusted to replace it.
- Frozen browser image and ID come from the same decoded canvas. Materialization verifies aspect ratio, dimensions, ID, full epoch, tracking and expiration.

## Precision / surface verification

`StrokePlacement.prepare()` performs historical depth reconstruction on the API worker, not GL. Every gesture vertex must have reliable local depth and reproject onto its original pixel. A circle's anchor is centered on the full stroke centroid, not its first rightmost circumference point.

`StrokeSurfaceVerifier` is a separate one-worker, ROI-limited visual verifier with cached immutable reference descriptors. It matches texture around the whole gesture, estimates a robust local homography and transfers **all original vertices** to the current image. The inlier geometry must surround the marked area. New vertices require current metric depth and coherent 3D shape; nearby unrelated edges are not sufficient.

- Reference and current CPU image belong to an exact camera exposure; current image/depth mismatch skips verification.
- The GL thread only makes a bulk luma-row copy at low cadence. No UV conversion loops, JPEG encoding or 8,000-point fitting runs there.
- OpenCV is restricted to one worker thread to reduce contention with ARCore and the encoder.
- Accepted shape corrections need two distinct observations with consistent vertices. Per-update displacement is bounded to 6 cm and cumulative repair travel to 15 cm; these are rejection guardrails, **not claimed accuracy**.
- Current iteration deliberately uses an immutable trusted root, not self-teaching unverified views. The previous multi-view/single-point resolver is still in the protected source dependency, but is not the authoritative new whole-stroke repair path.
- Failure leaves the existing ARCore anchor unchanged. Reflective, textureless, moving, insufficiently mapped or ambiguous-depth surfaces may reject placement/repair.
- Marks are static AR annotations. They do not semantically follow an object physically moved after placement. They persist only inside this camera session, not through process restarts.

## Internet architecture

`showme/server` is a deployable Cloudflare Worker + SQLite Durable Objects service. See `showme/server/README.md` for exact account setup, commands and provider pricing references.

1. Owner activates the phone once using a private service activation link.
2. Android creates a room via HTTPS and keeps an outbound authenticated WSS signaling connection.
3. The guest opens `https://<actual-worker>/r/<random-room>#<guest-capability>` in a browser.
4. Native owner explicitly allows or declines the guest.
5. SDP/ICE setup uses signaling and separate short-lived TURN credentials. Media prefers direct WebRTC with TURN fallback.
6. Frozen images, state and drawings use the encrypted WebRTC control data channel, fragmented into 8,000-character chunks. They are not tunneled through the Worker or a localhost proxy.

Normal Internet UX has no certificate import, IP entry or TLS warnings. Owner and helper microphones require user permission. Declining microphone permission must leave video usable. Muting an existing microphone does not recreate the video connection.

The server is **not deployed by committing this code**. A real HTTPS address and TURN credentials exist only after the owner runs deployment/configuration in their own Cloudflare account. Never hardcode a fictitious public URL in the APK. The helper's normal invite is not the owner's private activation link.

No login, billing or licence system is implemented. Private owner activation protects this early release. Production multi-customer access must replace it with real authentication/entitlements; an APK-contained secret would not be sufficient.

## User interface

Native camera is laid out between measured header/control rows, with system-bar/cutout insets applied to the root. Controls are not positioned on top of the content using fixed absolute offsets. The start page and browser use flat, restrained layouts. Connection internals live under More > Connection details; normal actions are Start, Invite, Mute, Pause, Undo/Clear and End.

Local Wi-Fi remains an explicit secondary mode under Settings. It uses the existing local server, no Internet account. Ordinary HTTP browser microphone restrictions still apply there.

## Boundaries and build

Do not modify original `android/app/**`, `.github/workflows/ci.yml`, `fresh/no-map-runtime-poc`, `outdoor/gnss-global` or Spatial Sync `latest-dev`. ShowMe package stays `com.sirpaul.showme`; release tag stays `showme-latest`.

New key files: `StrokePlacement`, `StrokeSurfaceVerifier`, `FrameTelemetry`, `SessionApi`, `ControlFragments`, `RemoteHostConnection`, `remote-session.mjs`, `control-rpc.mjs`, and `showme/server/**`.

Android:
`cd android && ./gradlew :showme:testDebugUnitTest :showme:lintDebug :showme:lintRelease :showme:assembleDebug :showme:assembleRelease`

Browser:
`node --test showme/web/*.test.mjs`
`cd showme && npm install && npx playwright install chromium && npm run test:browser`

Server:
`cd showme/server && npm install && npm test && npm run build && npm run test:integration && npm run check`

The release includes a separately signed APK and `ShowMe-server.zip`. It still uses the repository's public development sideload certificate, not a production Play signing identity.

## Acceptance before presenting as validated

- Check final GitHub Action and matching release SHA. A branch push is not a successful APK build.
- Test one local session and a session with the Android phone on mobile data and browser on a different network. Verify approval, voice both ways, mute, reconnect, End, expired/revoked invites and Direct/Relay status.
- On a PCB/printed feature, draw a small circle at a recognizable location, move the owner camera during a frozen gesture, and compare both views after placing it.
- Compare marks from frontal/oblique viewpoints, without allowing a neighbouring texture to steal the annotation.
- Measure GL frame/depth/luma-copy p95 and stalls alongside receiver FPS/RTT/jitter. Charge the phone for a controlled comparison; do not assume battery was the cause of earlier failures.
- Test two minutes after adding a mark; periodic verifier work must not cause the old per-second stall.
- Repeat with low light, glare, missing depth, denied mic, background/resume and rotation/cutout devices. Do not claim centimetre accuracy or arbitrary-NAT connectivity from synthetic tests alone.
