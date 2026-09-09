# ShowMe branch handoff

Active branch for this product: `showme/local-assist`.

Read `showme/BUILD_STATUS.md` first for the exact tested runtime, deliverable artifact, and unresolved release-publication permission issue. Then read `showme/AGENT_CONTEXT.md`, `showme/README.md` and `showme/PROTOCOL.md` before changing code. `docs/CURRENT_RUNTIME.md` and `docs/AGENT_CONTEXT.md` describe the underlying Spatial Sync baseline, not this application's lifecycle.

## Protected boundaries

- Based on `a5970e9be7fef9434dbf2e681274dc56c9462fe4`.
- Do not change any existing file under `android/` or `.github/workflows/ci.yml` for ShowMe. The separate build under `showme/android` reuses selected spatial sources through a generated-source library.
- Do not modify `fresh/no-map-runtime-poc`, `outdoor/gnss-global` or `latest-dev`.
- Camera owner has the only ARCore world. Browser clients do not run ARCore, require Wi-Fi Aware, or solve cross-device alignment.
- Draw requests MUST reference the exact displayed frame ID. Never run a historical browser click through the current frame's hitTest.
- No fabricated depth. Reject an unresolvable surface; do not place a point at a default distance.
- Camera poses and geometry must be sampled from the same frame; account for crop, rotation and letterboxing.
- ARCore operations stay on the render thread; encoding, feature matching, socket I/O and audio negotiation do not.
- Tokens and owner approval protect a LAN session, not HTTP transport confidentiality. No public exposure of the embedded endpoint.
- Browser microphone requires a secure context. Never claim two-way LAN HTTP audio works unconditionally.
- No login, billing, licence enforcement, Internet relay or persistent cross-session anchors are implemented yet.
- Tests/CI are not a substitute for a physical camera+browser AR test. Report this distinction explicitly.
- Every substantial change updates `showme/AGENT_CONTEXT.md`. Releases use ShowMe-specific prerelease tags and never replace the Spatial Sync release.
