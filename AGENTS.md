# ShowMe branch handoff

This branch is `showme/remote-assistance`. Read `docs/CURRENT_RUNTIME.md`, `docs/SHOWME.md`, then `showme/server/README.md`. `docs/AGENT_CONTEXT.md` documents the original Spatial Sync pipeline/history and is not the ShowMe product specification.

## Boundaries

- ShowMe starts from Spatial Sync commit `a5970e9be7fef9434dbf2e681274dc56c9462fe4`.
- Do not change `android/app/**` or `.github/workflows/ci.yml` while implementing ShowMe. Its original two-phone alignment, Wi-Fi Aware, vehicles and surface pipeline must remain intact. CI checks this diff.
- ShowMe is a separate Android application module `:showme`, package `com.sirpaul.showme`, not a rename/replacement of Spatial Sync.
- The `outdoor/gnss-global` and `fresh/no-map-runtime-poc` branches are not targets for ShowMe commits.
- ShowMe releases go to `showme-latest`, never `latest-dev`.
- No Google Cloud Anchors. Only the camera owner's ARCore world is needed; the browser has no AR world and does not perform two-device alignment.
- Live video is WebRTC, with a 30-fps capture/sender target. Never restore JPEG live polling. The CRC-coded video identity footer is cropped from presentation and must be correlated with per-frame depth history before placement. See `docs/SHOWME.md`.
- Remote input always carries the exact displayed frame ID and AR epoch. Never hit-test an old browser pixel against a new camera frame.
- Invalid/expired frame, missing depth, discontinuity, lost tracking or world reset must reject the drawing. Never invent a fixed-distance plane to make a demo look successful.
- Drawings remain 3D geometry attached to local ARCore anchors. Browser overlay geometry must originate from those anchors, not a permanent screen-space sketch.
- Optional visual/depth/edge verification runs separately from GL/networking and has bounded correction limits. It is not evidence of guaranteed centimetre accuracy.
- Internet calls use an activated HTTPS Worker service, WSS signaling and direct WebRTC with TURN fallback. Owner approval is required. Never embed the owner activation key or long-lived TURN secret in APK/browser/Git.
- Local Wi-Fi is secondary. Normal UI must not contain certificate imports or require disabling browser security.
- Do not put full-frame YUV conversion, SIFT, 8,000-point expansion or metadata JSON on the GL thread. Whole-stroke texture/depth checks are bounded, off-thread and fail closed.
- Keep the original frozen image reference immutable; do not claim the protected legacy multi-view resolver is the active whole-stroke verifier.
- Server code is not a deployed endpoint. Never claim deployment or physical validation from CI alone.
- Session tokens are ephemeral bearer capabilities. No analytics, cloud image uploads or silent audio capture.
- All user-visible UI is English. Native/browser permission prompts are explicit.
- Never claim hardware validation from CI alone. Inspect final CI and release assets before claiming APK availability.
- Update `docs/SHOWME.md` after meaningful changes and describe unimplemented production features honestly.

## Build

From `android/`: `./gradlew :showme:testDebugUnitTest :showme:lintDebug :showme:lintRelease :showme:assembleDebug :showme:assembleRelease`.

Browser math: `node --test showme/web/geometry.test.mjs` from root. Browser integration: install `showme/package.json` dependencies and run `npm run test:browser` inside `showme/`.

Current code and CI are authoritative; this handoff intentionally does not invent a future successful run or physical acceptance result.
