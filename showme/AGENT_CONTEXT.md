# ShowMe engineering continuation

## Request and branch

The user requested a practical, polished Android+browser remote-guidance application on a NEW branch. The app owner shares a camera view; an expert opens an invitation in a browser and pins/draws on physical surfaces. First delivery may use local Wi-Fi/hotspot without an external backend. Accounts, commercial licensing, payment and Internet hosting are later work. Do not pivot this into a mock demo or a second peer-AR alignment UI.

Branch: `showme/local-assist`. Source baseline: `a5970e9be7fef9434dbf2e681274dc56c9462fe4`. Do not change the original `android/` tree or the existing Spatial Sync CI/release. `outdoor/gnss-global` remains independent.

## Build layout

`showme/android` is a separate Gradle project, using the existing wrapper script. `:spatial` compiles selected original files unchanged into its generated-source directory. It includes MetricSupportSampler, surface resolver/edge refinement, their math/vision dependencies and the background renderer. There is no AlignmentCoordinator, vehicle detector, Wi-Fi Aware transport, sensor background service or double-solve handshake in the ShowMe runtime.

`:app` has a new package ID `com.sirpaul.showme`. Its web assets are the production files in `showme/web`, not a separately deployed placeholder. Original camera-frame JPEG is grayscale; `StreamCapture` adds full-colour YUV/NV21 -> OpenCV -> JPEG encoding without altering the old capture code.

## Data flow

1. Activity owns explicit foreground camera/sharing/microphone lifecycle.
2. ShowMeRenderer samples image, pose, intrinsics, metric supports and current tracked planes from the SAME ARCore frame.
3. CPU image bytes are copied respecting plane buffer offset/row stride/pixel stride. Image is closed promptly. JPEG conversion happens on a single encoder worker.
4. Raw color JPEG is retained for surface matching; an upright JPEG is encoded for the browser. The exact rotation is attached to the saved record.
5. A camera-pose ARCore anchor belongs to each retained frame. At placement time, its current pose rebases that historical frame's world geometry to the current AR world. Never use `frame.hitTest` from the current frame for an old browser click.
6. The latest-frame packet combines a JSON header and JPEG in ONE binary envelope. Annotation projections in the header belong to this same image.
7. Browser computes normalized image coordinates after removing `object-fit: contain` letterboxing. Pointer-down sends `hold(frameId)` and prevents replacement of the displayed image during the entire gesture.
8. Draw sends that exact frameId, a unique requestId, bounded normalized vertices, tool, color and label.
9. Render thread validates frame age/tracking, resolves metric/plane surfaces in historical coordinates, rebases through the camera anchor, and creates one physical ARCore anchor per annotation with relative vertices.
10. Browser receives ACK only after that real anchor exists. No depth evidence -> visible rejection, never a guessed distance.
11. Existing visual+depth multi-view resolver and optional edge refiner run on a separate worker. Small target-only translation corrections require repeated agreement; they do not create or change any cross-device world.

## Limits and explicit tradeoffs

- Raw stream width <=1280, intended capture cadence 125 ms while a helper watches. This is a configuration, not a measured FPS claim.
- One helper per session. Random 192-bit invitation token and owner approval. Known client can reconnect within the same session.
- Socket parser caps headers at 16 KiB and masked client messages at 32 KiB before allocation. Fragmented client messages are not supported.
- Browser frame requests are demand-driven; no unbounded video queue. Encoder/verifier are single-job bounded.
- ~24 historical frames plus one held frame, maximum age 45 seconds. Browser explicit Freeze releases after 40 seconds.
- At most 48 annotation objects and 64 vertices per mark. Request IDs prevent repeated draw execution.
- Placement range is 0.08..15 m; near-field service guidance is the intended initial workflow.
- Continuous stroke depth discontinuities can be rejected; a clean error is better than a floating arrow behind the battery.
- One translation correction applies to the annotation group. This is not a deformable multi-surface annotation solver.
- Static scene anchors do not follow physically moving cars, terminals or tools.
- Ending the session resets drawings and invalidates its token. Browser disconnect alone retains drawings.
- Going into the background pauses sharing and disables microphone; returning requires Resume. No hidden background capture.

## LAN and voice constraints

A browser has no access to the original native Wi-Fi Aware transport. Standard Wi-Fi/hotspot IP reachability is required. Android embeds the HTTP/WebSocket endpoint, so there is no cloud/server deployment step for local testing, but this is still an embedded server, not an Internet-reachable invitation.

Ordinary HTTP on LAN is unencrypted and does not allow getUserMedia. WebRTC audio uses a native microphone explicitly enabled by the owner and a browser recvonly fallback. Full browser microphone is attempted only under window.isSecureContext with getUserMedia available. Do not claim a full two-way HTTP browser call. Production needs HTTPS/signaling/TURN and user auth; no deployment exists yet.

## UI and tests

Android: branded home, live AR camera, QR/share/copy invitation, approval prompt, pause, mic, end, clear and notes. Browser: responsive workspace, pin/arrow/freehand/circle/temporary pointer, color/label, freeze, undo/remove/clear, notes, fullscreen, snapshot and silent recording. No external web assets, fonts, analytics or image uploads.

JVM tests cover frame expiry, projection/inverse mapping, exact-ray depth, command validation, request dedupe and bounded WebSocket framing. Node tests cover letterboxing, shapes and frame envelopes. Playwright uses an explicitly mocked camera transport solely for UI tests; screenshots are test fixtures, not evidence of physical AR operation.

CI: `.github/workflows/showme.yml`; ShowMe-specific prerelease tags. The original latest-dev must not move. Check build, test and publication results before claiming the APK is ready. Hardware correctness remains pending until the physical checklist passes.

## Next steps after physical testing

First fix any real device/browser lifecycle, projection or historical-frame mapping issue without touching the old engine. Add quantitative surface error and latency telemetry, capture reprojection fixtures, then improve the media transport (hardware WebRTC video plus exact frame-correlated pose metadata). Current JPEG frame transport is deliberately straightforward and accurately frame-addressable, not an optimized low-bitrate production video codec.

For Internet deployment, extract the existing session commands behind a signaling/session provider, use HTTPS/WSS and authenticated WebRTC data/media with TURN, retain exact-frame addressing, then add accounts and entitlements. Vercel can host web/account endpoints, but sustained signaling/media/relay connectivity must be designed separately rather than assuming serverless handlers are a TURN server.
