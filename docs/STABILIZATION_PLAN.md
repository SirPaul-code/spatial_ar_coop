# Stabilization plan

Runtime source of truth for the next physical-device test:

- Keep deterministic Wi-Fi Aware transport from `1b592692e47cc4a15c82c7427c987ac1d4556b45`.
- Restore pre-refinement `AlignmentCoordinator.kt` and `ArRenderer.kt` from `1787fd38b8bc50e4b7e79dc4cb259f04bee7a2a4`.
- Do not re-enable continuous transform refinement or surface-target correction until cross-device POI geometry passes.

Acceptance criteria:

1. CREATE/JOIN reaches `DIRECT`.
2. Shared registration reaches `LOCKED` in a few seconds.
3. A target placed 1–3 m away on one phone appears on the same physical object on the peer.
4. With phones approximately colocated, sender and receiver target distances must be physically comparable; a 1–3 m local target must never become a 20+ m remote target.
5. Disconnect/reconnect returns to `DIRECT` deterministically.
