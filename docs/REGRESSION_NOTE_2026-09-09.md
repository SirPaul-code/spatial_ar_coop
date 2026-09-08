# Runtime regression note — 2026-09-09

Physical device result on the `fresh/no-map-runtime-poc` branch:

- Wi-Fi Aware transport at `1b592692e47cc4a15c82c7427c987ac1d4556b45` successfully reaches `DIRECT` and then `LOCKED`.
- A manual POI placed on the sender was locally correct and tracked the intended thermostat.
- The receiver rendered that same POI about 21.7 m away while the measured peer distance was about 0.1 m.
- Therefore the failure is not tap/depth placement on the sender; it is a bad shared-world transform / post-lock transform path being accepted as `LOCKED`.

Stabilization decision:

- Keep the deterministic Wi-Fi Aware transport from `1b592692...`.
- Restore `AlignmentCoordinator.kt` and `ArRenderer.kt` to the pre-continuous-refinement runtime from `1787fd38b8bc50e4b7e79dc4cb259f04bee7a2a4`.
- Do not reintroduce continuous post-lock world refinement or surface-target correction until the baseline transport + static canonical transform has passed physical cross-device POI tests again.
- A correct acceptance test is not just `LOCKED`; a point placed roughly 1–3 m from both colocated phones must render at roughly the same physical location and comparable distance on both devices.
