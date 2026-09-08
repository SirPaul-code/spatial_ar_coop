# Spatial Sync agent handoff

Read `docs/AGENT_CONTEXT.md` before changing this repository, then read `docs/AGENT_CONTEXT_CURRENT.md` for the latest runtime delta. These are the continuation checkpoints for agents that inherit the project after a context-window reset. Read `docs/SPATIAL_WORLD_DEMO.md` before changing WORLD/demo presentation behavior.

## Non-negotiable project rules

- Working branch: `fresh/no-map-runtime-poc` unless the user explicitly changes it.
- No Google Cloud Anchors, no required external server, no pre-scanned map.
- ARCore `Anchor` objects are local anchors only.
- Prefer spatial correctness and stability over bandwidth, compute, battery use, APK size, or elegance.
- Manual targets are additive; a new target must not replace an old one.
- Manual targets are physical surface references when possible: exact reference image + tap pixel + metric depth, with fail-closed visual/depth re-verification.
- Trusted manual targets may learn a bounded multi-angle reference atlas only from already verified visual+metric observations. Never admit unverified observations as new target references.
- Cars/persons are dynamic tracks, not permanent static ARCore anchors.
- Keep all product UI text in English.
- Wire protocol is currently V6; both phones must run the same build.
- Host-first canonical alignment, peer bootstrap, synchronized acquisition bursts, range/gravity sanity, and fail-closed placement are intentional. Do not replace them with blind threshold relaxation.
- Fresh synchronized acquisition has absolute startup priority over cached relocalization. While current frames carry a non-zero acquisition `burstId`, cached relocalization must yield immediately and must not occupy the shared solve executor.
- Failed acquisition bursts retry quickly; do not restore the old long 5 s post-burst wait without hardware evidence.
- Post-lock room refinement is host-authoritative and deliberately strict; per-target surface correction is a separate, more specific correction layer.
- Physical alignment failures must be diagnosed from `quality.ndjson`, `events.ndjson`, and replayable `frames.spv6` before changing gates.
- The `WORLD` view must visualize real current shared-world data; do not fake/pre-bake its map or actor positions.
- `SharedRoomState` is N-peer ready, but current `WifiAwarePeerTransport` is still one active physical peer/socket. Do not claim live 3+ phone fan-out until that transport boundary is actually refactored.
- Do not claim CI/release is ready until the final GitHub Action succeeds and `latest-dev` targets the final branch HEAD.
- After substantial architecture changes, update the continuation context.

Latest runtime sequence before the current documentation-only handoff commits:

- `a66ee0fa69fd9393572669ee045cce728ba883f4` — cached relocalization yields to fresh acquisition bursts.
- `73bf37c21a9f8281f3a0cfb07cc471433ec3bccd` — acquisition retry quiet time reduced from 5.0 s to 1.5 s.
- `453ae33e88cc6433a78a88d8272b5e9726770a83` — regression test protecting fresh-burst priority.

Previous precision runtime remains `77402f00f60ac30a4fc6b1fb2114a7d6a88d00ad` (multi-angle target surface views) on top of `4ee42ea77f2ea1eb084e12ddbf05fecabe635530` (continuous shared-world verification + per-target visual/metric surface locking).

The latest physical regression was startup getting stuck in `ALIGNING` after earlier fast locks. Treat persisted landmark-cache relocalization competing with the single fresh-solve executor as the known regression class; do not loosen spatial gates first. Always inspect current branch HEAD and current release because documentation commits make HEAD newer without changing runtime behavior.
