# Spatial Sync agent handoff

Read `docs/AGENT_CONTEXT.md` before changing this repository. It is the continuation checkpoint for agents that inherit the project after a context-window reset.

## Non-negotiable project rules

- Working branch: `fresh/no-map-runtime-poc` unless the user explicitly changes it.
- No Google Cloud Anchors, no required external server, no pre-scanned map.
- ARCore `Anchor` objects are local anchors only.
- Prefer spatial correctness and stability over bandwidth, compute, battery use, APK size, or elegance.
- Manual targets are additive; a new target must not replace an old one.
- Cars/persons are dynamic tracks, not permanent static ARCore anchors.
- Keep all product UI text in English.
- Do not blindly loosen alignment gates when hardware tests fail. Instrument the failing stage first.
- Do not claim CI/release is ready until the GitHub Action and `latest-dev` target are actually verified.
- After substantial architecture changes, update `docs/AGENT_CONTEXT.md`.

Functional code baseline immediately before these handoff docs: `fc2b8efc3358cda0e53a8de53c4e1c9fbd9b9c92` (`fix: adopt verified peer bootstrap without double solve`). The documentation commit itself will make branch HEAD newer; always inspect current HEAD and current release.