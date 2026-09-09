# Spatial Sync agent handoff

Before changing this repository, read these in order:

1. `docs/CURRENT_RUNTIME.md` — authoritative short delta for the current physical runtime and the latest presentation/tracking changes.
2. `docs/AGENT_CONTEXT.md` — long-form architecture/history and continuation context.
3. `docs/SPATIAL_WORLD_DEMO.md` — only when changing WORLD/Bird's Eye presentation behavior.

## Non-negotiable project rules

- Working branch: `fresh/no-map-runtime-poc` unless the user explicitly changes it.
- No Google Cloud Anchors, no required external server, no pre-scanned map.
- ARCore `Anchor` objects are local anchors only.
- Prefer spatial correctness and stability over bandwidth, compute, battery use, APK size, or elegance.
- Manual targets are additive; a new target must not replace an old one.
- Cars/persons are dynamic tracks, not permanent static ARCore anchors.
- Keep all product UI text in English.
- Wire protocol is V6; both phones must run the same build.
- The current CREATE/JOIN -> Wi-Fi Aware -> acquisition burst -> host-canonical LOCKED path is a physically proven baseline. Do not modify `WifiAwarePeerTransport`, peer handshake, or startup alignment while working on vehicle presentation or target-surface precision unless a new physical failure specifically points there.
- Vehicle dedupe is room-level: the same physical vehicle must converge to one shared track id/owner, coast briefly through missed detections, then expire after the memory TTL.
- Manual-target precision is layered: ARCore placement -> visual/metric surface resolver -> bounded multi-view surface atlas -> optional fail-closed edge snap. These target-level refinements must never participate in shared-world startup alignment.
- Host-first canonical alignment, peer bootstrap, acquisition bursts, range/gravity sanity, and fail-closed placement are intentional. Do not replace them with blind threshold relaxation.
- Physical alignment failures must be diagnosed from `quality.ndjson`, `events.ndjson`, and replayable `frames.spv6` before changing alignment gates.
- The `WORLD` view must visualize real current shared-world data; do not fake/pre-bake its map or actor positions.
- `SharedRoomState` is N-peer ready, but current `WifiAwarePeerTransport` is still one active physical peer/socket. Do not claim live 3+ phone fan-out until that transport boundary is actually refactored.
- Do not claim CI/release is ready until the final GitHub Action succeeds and `latest-dev` targets the final branch HEAD.
- After substantial runtime changes, update `docs/CURRENT_RUNTIME.md`; update `docs/AGENT_CONTEXT.md` when the architecture itself changes.

Always inspect current branch HEAD, recent commits, CI and `latest-dev` before continuing. The last known-good alignment/placement baseline before the current vehicle/precision layer was `02fc3c4ae145e25fb429936da94b68a8aa020f38` (`fix: keep golden alignment and harden manual POI placement`).
