# Spatial Sync agent handoff

Read `docs/AGENT_CONTEXT.md` before changing this repository. It is the continuation checkpoint for agents that inherit the project after a context-window reset. Read `docs/SPATIAL_WORLD_DEMO.md` before changing demo behavior.

## Non-negotiable project rules

- Working branch: `fresh/no-map-runtime-poc` unless the user explicitly changes it.
- No Google Cloud Anchors, no required external server, no pre-scanned map.
- ARCore `Anchor` objects are local anchors only.
- Prefer spatial correctness and stability over bandwidth, compute, battery use, APK size, or elegance.
- Manual targets are additive; a new target must not replace an old one.
- Cars/persons are dynamic tracks, not permanent static ARCore anchors.
- Keep all product UI text in English.
- Wire protocol is currently V6; both phones must run the same build.
- Host-first canonical alignment, peer bootstrap, acquisition bursts, range/gravity sanity, and fail-closed placement are intentional. Do not replace them with blind threshold relaxation.
- Physical alignment failures must be diagnosed from `quality.ndjson`, `events.ndjson`, and replayable `frames.spv6` before changing gates.
- The `WORLD` view must visualize real current shared-world data; do not fake/pre-bake its map or actor positions.
- `SharedRoomState` is N-peer ready, but current `WifiAwarePeerTransport` is still one active physical peer/socket. Do not claim live 3+ phone fan-out until that transport boundary is actually refactored.
- Do not claim CI/release is ready until the final GitHub Action succeeds and `latest-dev` targets the final branch HEAD.
- After substantial architecture changes, update `docs/AGENT_CONTEXT.md`.

Runtime code baseline immediately before this handoff refresh: `9f34448e33ae110b2644de3550a0d7bc171b49e4` (`fix: restore Android YuvImage import`). Documentation commits make branch HEAD newer; always inspect current HEAD and current release.
