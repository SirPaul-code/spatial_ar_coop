# Current runtime index

Read `AGENT_CONTEXT_CURRENT.md` for the full continuation handoff. This file is the concise delta/index that should be checked first when the branch has moved beyond the long-form handoff.

Latest runtime-code commit in the current high-assurance stabilization series:

`778b44194cdcaaea245dac8cbf95735fec40fbc1` — `fix: keep reacquisition alive and hide unverified world geometry`

The false-lock hardening immediately below it includes:

- metric-first `AlignmentEngine` (paired 3D<->3D before Essential/PnP fallback);
- hardened Essential fallback with >=6 metric scale pairs and >=0.20 m baseline;
- mandatory metric/depth evidence through `TransformSafetyPolicy`;
- independent receiver-side transform proof through `SharedTransformVerifier`;
- exact metric-support UV reprojection validation;
- 2/3 verified-candidate consensus instead of one-frame lock;
- static verified transform + watchdog revocation rather than continuous drift blending;
- Wi-Fi RTT contradiction reset;
- immediate acquisition-burst `reacquire()` after lock invalidation;
- no `SYNCING` banner when there is no DIRECT peer;
- WORLD/Bird's Eye no longer exposes remote geometry from an unverified transform proposal.

Important latest bug fix: a spatial `ResetAlignment` is **not** a transport disconnect. `WorldVizBus` previously called `AcquisitionBurstController.reset()` after the coordinator had called `reacquire()`, erasing the peer identities and killing the fresh burst. It now retains the live DIRECT connection's acquisition identity and restarts acquisition instead.

Current priorities:

1. prevent false `LOCKED` shared transforms;
2. preserve deterministic Wi-Fi Aware DIRECT connection/reconnect;
3. require independent visual + metric proof before peer bootstrap is adopted;
4. revoke contradicted locks instead of continuously moving/refining them;
5. physically validate A->B and B->A cross-device POI accuracy before restoring surface/multi-angle refinement.

The next agent must inspect branch HEAD and `latest-dev` because docs-only commits are expected to sit above the runtime-code SHA.
