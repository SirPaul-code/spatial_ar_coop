# Spatial Sync agent handoff

Before changing this repository, read these in order:

1. `docs/CURRENT_RUNTIME.md` — shortest authoritative delta/index for the latest runtime-code commit.
2. `docs/AGENT_CONTEXT_CURRENT.md` — authoritative long-form current architecture, latest physical regression, safety invariants, exact alignment flow, and acceptance tests.
3. `docs/AGENT_CONTEXT.md` — older historical architecture/context. Treat the two files above as authoritative when they disagree.
4. `docs/SPATIAL_WORLD_DEMO.md` only when changing WORLD/Bird's Eye presentation behavior.

## Non-negotiable project rules

- Working branch: `fresh/no-map-runtime-poc` unless the user explicitly changes it.
- No Google Cloud Anchors, no required external server, no pre-scanned map.
- ARCore `TRACKING` is local VIO only; never equate it with cross-device `LOCKED`.
- A shared transform must fail closed. Visual-only confidence is not enough: current runtime requires independent cross-device metric/depth proof.
- Preserve the deterministic Wi-Fi Aware JOIN -> exact-peer responder -> READY -> NDP -> TCP handshake unless a physical transport test disproves it.
- Do not restore the old near-zero-baseline Essential shortcut. The direct paired metric 3D<->3D path is preferred; Essential is a hardened fallback.
- Do not adopt a peer transform from sender metadata alone. The receiver must independently verify the proposal against its own current image + metric depth before ACK.
- Once verified, the shared transform is static in the current stabilization runtime. Watchdogs may revoke it; they must not silently blend/move the world.
- A spatial `ResetAlignment` on an alive DIRECT link must trigger/retain acquisition `reacquire()`; do not reset away the connection identities/burst state.
- WORLD/Bird's Eye must not expose remote geometry from an unverified transform proposal.
- Do not re-enable continuous shared-world refinement, startup cached relocalization, surface correction, or multi-angle target learning until the high-assurance baseline passes the physical acceptance test in `AGENT_CONTEXT_CURRENT.md`.
- Prefer spatial correctness and stability over bandwidth, compute, battery, APK size, lock speed, or elegance.
- Manual targets are additive. Cars/persons are dynamic tracks, not permanent static ARCore anchors.
- Remote POIs must never be published before `peerTransformVerified`.
- Keep all product UI text in English.
- Wire frame protocol is currently V6; both phones must run the same build.
- Physical alignment failures must be diagnosed from `quality.ndjson`, `events.ndjson`, and replayable recorded frames before relaxing any gate.
- The `WORLD` view must visualize real shared-world state; do not fake/pre-bake map or actor positions.
- `SharedRoomState` has broader room-state abstractions, but current `WifiAwarePeerTransport` still supports one active physical peer/socket. Do not claim live 3+ phone fan-out.
- Do not claim a runtime fix is complete merely because CI passes. Shared spatial correctness requires a real two-phone test.
- Do not claim a release is ready until the final GitHub Action succeeds and `latest-dev` targets the intended final branch HEAD.
- After any substantial architecture or physical-test finding, update `docs/CURRENT_RUNTIME.md` and, when needed, `docs/AGENT_CONTEXT_CURRENT.md`.

## Current stabilization warning

The key physical regression was: sender-local target correct at the thermostat, receiver rendered the same target about 21.7–22 m away while UI said `LOCKED`. The current high-assurance series was specifically reworked so this class of false lock is rejected or revoked rather than trusted. Do not "fix" that incident by offsetting POIs or weakening geometry.

Always inspect current branch HEAD, recent commits, CI, and `latest-dev` before continuing; docs-only commits make branch HEAD newer than the runtime-code SHA recorded in the handoff.
