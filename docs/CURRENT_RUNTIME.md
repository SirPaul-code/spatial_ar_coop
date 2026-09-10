# Current branch: ShowMe remote assistance

Working branch: `showme/remote-assistance`.

Read `docs/SHOWME.md` for the authoritative current implementation, limitations, build instructions, security contract and physical acceptance tests.

## Protected source baseline

ShowMe was forked from `a5970e9be7fef9434dbf2e681274dc56c9462fe4` on `fresh/no-map-runtime-poc` (vehicle identity convergence and precision surface snap). The original `android/app/**` files remain unchanged. The new `:showme` module uses selected existing geometry/depth/surface algorithms through generated-source reuse without altering the native two-phone alignment/transport runtime.

The `outdoor/gnss-global` branch is a separate experiment and is not modified by this branch.

## New product flow

Android camera owner -> generated local invite link / Android share sheet / QR -> browser helper -> exact-frame pin/arrow/freehand/circle -> historical metric surface reconstruction -> local ARCore anchor -> native and browser world-projected annotations.

Only the owner's AR world is involved. No cross-device AR alignment, Cloud Anchor, login or external server is required for the local preview.

Local HTTP mode provides camera view and annotations. Optional local HTTPS and native/browser WebRTC audio are implemented, but browser microphone availability requires trusted HTTPS and user permission. Self-signed trust is a testing step, not a guarantee of one-click public calling.

Release channel is `showme-latest`; package is `com.sirpaul.showme`. Never overwrite Spatial Sync's `latest-dev` while publishing ShowMe.

## Verification status

Code is implemented and includes Kotlin/Node geometry/session tests and Chromium desktop/mobile protocol/UI smoke tests. Check the final GitHub Actions run and release assets for the exact commit being tested. Physical AR placement, real mobile browsers, hotspot behavior and native/browser voice are not certified by those automated tests; use the acceptance procedure in `SHOWME.md`.

Do not claim internet reachability, login, billing, durable cross-session anchors, centimetre guarantees or an automotive/safety certification. Those are not delivered by the local preview.

Older `docs/AGENT_CONTEXT.md` remains valuable for the original spatial pipeline, but its branch/checkpoint and two-phone UI descriptions are historical in this ShowMe branch.
