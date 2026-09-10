# ShowMe continuation checkpoint

Branch: `showme/remote-assistance`.
Read `docs/SHOWME.md` and `showme/server/README.md` first.

This iteration follows the 0.2 WebRTC runtime `b5d43189095911fc56bacab4381678cf4db5efe2` after real reports of periodic video stalls and slightly displaced PCB annotations.

Implemented changes:
- Remove full YUV conversion and metric support expansion from the per-second GL verifier path; copy only luma rows, do ROI verification on a separate single-thread worker.
- Cache root surface descriptors; verify/correct all vertices of a gesture, centroid-based anchors, bounded multi-observation corrections.
- Move metadata projection/JSON off GL; replace 128 footer clears/frame with one tiny texture draw; GPU-backed browser presentation with only footer-row readback.
- Preserve exact frame ID/full epoch/camera timestamp/K/pose/rotation/depth association. Never infer an arbitrary latest pose for a frozen image.
- Add bounded frame and GL/depth/copy timing telemetry.
- Add Cloudflare Workers/SQLite Durable Object invitation/approval/signaling service, short-lived TURN credentials, Android outbound WSS host, browser HTTPS invite routing and WebRTC control RPC for images/drawings.
- Clean native/browser layouts, remove TLS/certificate/IP administration from ordinary call UI. Native Internet service activation happens once; local Wi-Fi is secondary.

Protected: original `android/app/**` and `.github/workflows/ci.yml` remain unchanged; release is `showme-latest`, app ID `com.sirpaul.showme`.

Deployment status: code/configuration and setup scripts are supplied, **not an already deployed Cloudflare account service**. The user must deploy and configure their own Worker/TURN keys, then activate the Android app with the printed private link. No login/monetization is implemented yet.

Validation: inspect current final CI and exact release SHA. Node/JUnit/Chromium/Miniflare tests do not measure Android GPU/camera timing or centimetre placement and do not substitute for a real mobile-data-to-PC TURN test. Do not repeat the earlier overclaim that a synthetic 30-fps browser result guarantees a 30-fps phone call.
