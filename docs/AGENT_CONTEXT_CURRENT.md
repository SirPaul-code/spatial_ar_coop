# Spatial Sync — current continuation context

> **Read this before changing runtime code.** This file is the current-state handoff for `fresh/no-map-runtime-poc`. It exists so a new agent can continue after a context-window reset without re-discovering the architecture or repeating regressions.

## 0. Current runtime baseline

Runtime code baseline when this handoff was written:

- branch: `fresh/no-map-runtime-poc`
- runtime HEAD before this docs-only handoff: `3d21b53988103bbb1e2e7ee6a9d1b49dda707006`
- deterministic Wi-Fi Aware transport lineage: `1b592692e47cc4a15c82c7427c987ac1d4556b45`
- high-assurance false-lock hardening is the series immediately after `5ceecacb6d47dcaf7fdfc53883eeaf65d3122058`

The current runtime is intentionally in a **shared-world stabilization phase**. Do not reintroduce previously removed post-lock refinement/surface correction until the physical acceptance tests in this document pass.

## 1. Product intent

This is an Android infrastructure-free shared-spatial runtime. Two nearby ARCore phones establish a direct link and derive a common metric coordinate frame. Once the frame is genuinely verified, a point placed on one phone should appear at the same physical location on the other phone. The same shared frame also supports dynamic world-space tracks and a Bird's Eye / WORLD visualization.

Hard invariants:

- no Google Cloud Anchors;
- no backend required for alignment;
- no QR/fiducial marker required;
- Wi-Fi Aware NDP + IPv6/TCP is the current direct transport;
- local ARCore `TRACKING` is not shared-world `LOCKED`;
- `LOCKED` must mean the cross-device transform is independently supported by visual **and metric** evidence;
- a wrong transform is worse than a slower lock. Fail closed.

## 2. The physical regression that forced this stabilization

A real device test produced the following state:

- transport: `DIRECT`;
- UI: `LOCKED`;
- peer range shown around `0.1 m`;
- user tapped a wall thermostat on phone A;
- on phone A the local ARCore target was correct and tracked the thermostat correctly;
- phone B rendered the same target about **21.7–22 m away**.

This proves the tap/depth/local-anchor path was not the root cause. The failure was in shared-world registration / transform acceptance. Never treat this incident as a minor POI-placement error.

### Root causes found

1. `AlignmentEngine` used to try Essential geometry before the stronger paired metric 3D↔3D path.
2. `EssentialSharedPoseSolver` allowed a result with only two metric scale pairs and a baseline down to 5 cm. Translation direction from an essential matrix is poorly conditioned when phones are nearly colocated.
3. `AlignmentCoordinator.isMetricConsistent()` previously treated fewer than five metric pairs as acceptable instead of insufficient.
4. A peer transform could be adopted from the sender's reported confidence/inliers/reprojection without the receiving phone independently proving the transform with its own current image+depth observations.
5. The direct 3D↔3D path associated depth to SIFT within a pixel radius but validated reprojection against the SIFT keypoint rather than the exact metric-support pixel. A correct metric solution could therefore lose the 4 px reprojection gate and allow a weaker fallback to win.
6. There was no strict post-lock invalidation path. A stale/wrong static transform could remain labelled `LOCKED`.
7. The renderer could emit a `SYNCING`/"Aligning space" banner without any DIRECT peer, creating contradictory `TRACKING` + alignment UI.

## 3. Current connection layer — preserve this

`WifiAwarePeerTransport.kt` contains the deterministic reconnect/handshake fix. The previous transport could race NDP setup and repeatedly report `Direct Wi-Fi data path unavailable`.

Current intended handshake:

```text
CLIENT                       HOST
   |                           |
   |---------- JOIN ---------->|
   |                           | create responder for exact PeerHandle
   |<--------- READY ----------|
   |                           |
   | client requests NDP       |
   |========= NDP ============>|
   |       IPv6 + TCP           |
   |--------- DIRECT ---------->|
```

Do not restore the old any-peer responder / simultaneous NDP race.

The app is still physically pairwise: one direct peer/socket per app instance. `SharedRoomState` contains a broader canonical-room model, but the transport is not yet N-peer mesh/star networking.

## 4. Acquisition burst

`AcquisitionBurstController.kt` coordinates a short high-rate alignment burst after both Hello identities are known.

Current important behavior:

- 12 burst frames;
- 250 ms sequence interval;
- retry gap is 1.5 s, not the old 5 s;
- `reacquire()` reopens a burst immediately after a verified shared lock is invalidated, without requiring a socket reconnect;
- a new local Hello resets connection-local burst/locked state.

The burst id is derived from the stable sorted identity pair. Sequence proximity is used as a strong frame-pair affinity bonus; clocks do not need to be synchronized.

## 5. Alignment solver hierarchy — metric first

`AlignmentEngine.kt` is now deliberately ordered as:

```text
SIFT mutual/ratio matches
        |
        v
same visual feature has metric XYZ on BOTH phones?
        |
        +--> YES: SharedVisualAnchorSolver robust 3D <-> 3D rigid fit  [FIRST]
        |
        +--> NO/insufficient:
              EssentialSharedPoseSolver + metric scale               [FALLBACK]
        |
        +--> NO:
              metric 3D -> local 2D PnP + local metric validation    [LAST]
```

### 5.1 Direct shared 3D↔3D path

This is the preferred path, especially for nearly colocated phones.

Current key properties:

- at least 8 paired metric supports before the direct path is attempted;
- robust `SharedVisualAnchorSolver` fit;
- at least 8 direct inliers;
- rigid determinant gate around 1;
- gravity gate about 10°;
- image coverage gate about 4%;
- median reprojection gate 4 px;
- **direct reprojection now uses the exact local metric-support UV**, not the nearby SIFT keypoint UV.

That last point is important. Do not regress it. The metric sampler point can be up to the association radius away from a SIFT keypoint; comparing the transformed metric point to the keypoint with a tighter threshold is internally inconsistent.

### 5.2 Essential fallback

`EssentialSharedPoseSolver.kt` is now intentionally hard to accept:

- minimum visual matches: 14;
- minimum visual inliers: 12;
- Essential RANSAC threshold tightened;
- median epipolar error <= ~3 px;
- image coverage >= ~5%;
- metric association radius reduced from 22 px to 14 px;
- minimum metric scale pairs: **6** (was 2);
- minimum metric inliers: 5 and >=70%;
- median metric residual <= ~0.16 m;
- scale MAD/spread gate;
- maximum perpendicular scale-pair error tightened;
- gravity <= ~10°;
- **minimum baseline = 0.20 m**.

If phones are only ~0.1 m apart, Essential translation direction is not allowed to guess the world. The direct metric 3D↔3D path should solve instead.

### 5.3 PnP fallback

PnP remains a fallback for sparse cases. Metric agreement is no longer optional permission to lock: the coordinator requires independent metric evidence even if PnP itself returns a visually plausible matrix.

## 6. TransformSafetyPolicy — fail closed

`TransformSafetyPolicy.kt` centralizes the safety gates for a transform that is allowed to become shared `LOCKED` state.

Current approximate minimum evidence:

- >=8 visual candidates;
- >=8 visual inliers;
- visual inlier ratio >=55%;
- median reprojection <=4 px;
- image coverage >=4%;
- **>=5 metric pairs**;
- **>=4 metric inliers and >=70%**;
- median metric residual <=0.18 m;
- metric support span >=0.12 m;
- gravity <=12°.

Important invariant: **visual-only evidence or a two-depth-point scale estimate can never authorize LOCKED.**

`TransformSafetyPolicyTest.kt` contains regression tests for exactly this, including a catastrophic metric contradiction that must fail despite a good image fit.

## 7. SharedTransformVerifier — receiver proves the transform

`SharedTransformVerifier.kt` is the second independent proof layer.

It does **not** solve another transform. Given a proposed `remoteWorld -> localWorld` transform, it asks whether fresh observations on both devices agree with that exact transform:

1. fresh mutual/ratio SIFT matches;
2. remote SIFT feature -> nearest remote metric support;
3. local SIFT feature -> nearest local metric support;
4. transform the remote metric XYZ with the proposed matrix;
5. compare transformed XYZ to independently measured local XYZ;
6. project transformed XYZ into the local camera;
7. compare projection to the **exact local metric-support pixel**;
8. require broad metric support span, image coverage, gravity consistency, and policy thresholds.

A `null` verification means **inconclusive / insufficient common scene**, not failure. This matters after lock: users are allowed to point the cameras in different directions.

A non-null failed verification means there was enough common evidence to contradict the proposed transform.

## 8. AlignmentCoordinator — current high-assurance state machine

`AlignmentCoordinator.kt` is no longer allowed to equate "solver returned a matrix" with `LOCKED`.

### Initial local solve

Every solver candidate is passed through `SharedTransformVerifier` before it may enter candidate consensus.

Then:

- strong candidate still needs 2 mutually consistent verified candidates;
- ordinary candidate needs 3;
- no one-frame instant lock;
- candidate clustering is tighter than the earlier false-lock build;
- metric evidence is mandatory;
- range/gravity/rigidity gates remain active.

The slightly higher computation cost is intentional. Optimize duplicate feature extraction later only after correctness is physically proven; do not weaken the gates to make a demo faster.

### Peer bootstrap

A peer transform is **not adopted from sender metadata anymore**.

Current flow:

```text
HOST solves verified candidate
       |
       v
sends transform proposal
       |
       v
CLIENT receives proposal
       |
       v
CLIENT independently tests proposal against its own current image + depth
       |
       +-- insufficient overlap -> wait for another frame
       |
       +-- contradiction -> reject/reacquire after repeated proof
       |
       +-- verified -> adopt + send PEER_ACK
       |
       v
HOST verifies ACK agreement
       |
       v
bothReady / LOCKED
```

If both devices independently solve, the stricter scene-space agreement path is retained.

### Post-lock behavior

The shared transform is intentionally **static** once verified. The stabilization build does not continuously blend/refine the world.

A low-rate watchdog instead follows this rule:

> prove the current transform or revoke it; never silently move it.

Approximately every 1.8 s, when a common scene supplies enough evidence:

- a passing verification clears failure count;
- insufficient shared view is inconclusive and keeps the lock;
- repeated conclusive visual+metric contradiction increments the watchdog;
- 3 conclusive failures invalidate the lock, clear stale targets, send alignment reset, and immediately start a fresh acquisition burst.

Fresh Wi-Fi RTT is also a post-lock sanity source. Two repeated physical range contradictions invalidate the shared lock.

This is designed so a future regression should fall back to `ALIGNING` rather than continue showing a target 20 m away under a green `LOCKED` pill.

## 9. UI state semantics

Keep these meanings separate:

- `TRACKING` = local ARCore/VIO tracking only;
- `HOST` / `CONNECTING` = no direct shared data path yet;
- `DIRECT • peer` = Wi-Fi Aware data path + TCP established;
- `ALIGNING` = DIRECT exists, shared world is not yet verified;
- `CONFIRMING` = local transform found, peer verification/ACK still incomplete;
- `LOCKED` = both sides accepted/verified the shared transform.

`ArRenderer.publishSyncGuidanceIfNeeded()` now checks `coordinator.isPeerConnected()` before emitting the `SYNCING` banner. Do not restore the old behavior where an offline HOST could simultaneously show local `TRACKING` and "Aligning space".

## 10. Alignment diagnostics / flight recorder

`AlignmentDiagnostics.kt` records gate-level quality to the current alignment session at 4 Hz.

Current blocker taxonomy includes:

- `VISUAL_INLIERS`
- `CORRESPONDENCES`
- `NO_REPROJECTION`
- `REPROJECTION`
- `IMAGE_COVERAGE`
- `CONFIDENCE`
- `GRAVITY`
- `METRIC_SUPPORT`
- `METRIC_INLIERS`
- `NO_METRIC_RESIDUAL`
- `METRIC_RESIDUAL`
- `WAITING_PEER_READY`
- `PEER_TRANSFORM_VERIFY`
- `CONSENSUS`
- `LOCKED_REVALIDATING`
- `LOCKED`

`quality.ndjson` now records `lockValidationFailures` as well as metric/visual/range/agreement fields.

`AlignmentSessionRecorder.kt` and `RecordedAlignmentReplay.kt` already exist. Prefer collecting a real failing session and replaying it rather than blindly loosening thresholds.

## 11. Local tap / POI path

The most recent physical incident proved the local path works: the sender-local target stayed correctly pinned to the thermostat.

Current manual target behavior in the stabilization runtime:

- tap requires `coordinator.canPlacePoi()` (`DIRECT` + verified shared lock);
- ARCore hit and metric depth are cross-checked when both are present;
- accepted local target becomes a normal ARCore `Anchor`;
- multiple manual local/remote targets are retained;
- remote targets are transformed only after `peerTransformVerified`;
- invalidating a shared lock clears remote/shared stale targets instead of letting them remain under a new coordinate frame.

Do not attempt to "fix" a remote 20 m error by changing local hit-test/depth logic when the sender-local marker is already correct. Diagnose the shared transform first.

## 12. Surface-target / multi-angle work is deliberately dormant

The project previously implemented precision surface correction and multi-angle target learning around commits:

- `4ee42ea77f2ea1eb084e12ddbf05fecabe635530`
- `77402f00f60ac30a4fc6b1fb2114a7d6a88d00ad`

Those changes are **not the active stabilization path**. Files such as `SurfaceTargetResolver.kt` may still exist in the tree, but the current restored `ArRenderer`/`AlignmentCoordinator` does not rely on that refinement system for normal manual POIs.

Do not wholesale cherry-pick those commits back. They changed hundreds of lines across coordinator/renderer/transport at once and were correlated with severe regressions.

After the shared-world baseline passes repeated physical tests, surface fingerprints and multi-view atlas learning may be reintroduced in isolated commits that do not modify Wi-Fi Aware transport or initial shared-world acceptance.

## 13. Relocalization cache

`SharedLandmarkCache.kt` and `RelocalizationEngine.kt` exist, but cached relocalization is intentionally **not in the active initial-lock path** in the current high-assurance coordinator.

Reason: previous cached relocalization could compete with fresh acquisition on the same single-thread solver and starve startup alignment.

Do not re-enable it until it has its own scheduling/priority design and cannot block fresh synchronized acquisition.

## 14. Vehicle tracking

Vehicle support remains present and is separate from static manual anchors:

- on-device EfficientDet-Lite0 / MediaPipe vision model;
- detects `car`, `truck`, `bus`;
- only runs after shared world is ready;
- metric depth converts 2D detection box to world-space position;
- local/remote vehicle tracks are transient, not ARCore static anchors;
- current wire compatibility uses owner prefix `AUTO:CAR:`;
- local/remote TTLs are short;
- nearest-3D association and smoothing are used.

This area is not the priority while shared-world correctness is under physical validation.

## 15. WORLD / Bird's Eye representation

The repo already contains:

- `BirdEyeWorldView.kt`
- `WorldViz.kt` / `WorldVizBus`
- `SpatialMapAccumulator.kt`
- `SharedRoomState.kt`

The intended architecture is that Bird's Eye/WORLD visualizes the same real canonical state; it must not become a separate fake demo coordinate system.

Future hardening should ensure unverified transform proposals are never presented as authoritative WORLD geometry. The primary AR runtime currently gates actual remote POI publication on `peerTransformVerified`.

## 16. Protocol and transport facts

- frame wire protocol: V6;
- frame payload supports burst id + sequence and up to high metric-point counts;
- maximum frame payload is large enough for burst acquisition;
- static POIs use `WireMessage.Poi`;
- moving car packets currently reuse `Poi` via `AUTO:CAR:` owner prefix;
- `ClearPoi` is global clear;
- direct physical transport is Wi-Fi Aware NDP + IPv6 TCP;
- optional Wi-Fi RTT range, BLE as less precise fallback;
- no server and no Google Cloud Anchor dependency.

A future Protocol V7 can replace the `AUTO:CAR:` hack with typed `TargetUpdate`, but do not combine that refactor with alignment stabilization.

## 17. Known-good / dangerous history

Useful landmarks in history:

- `93b3dc7...` multi-target rendering baseline;
- `6923e45...` host canonical world convergence;
- `7546f9c...` shared visual anchor rigid solver;
- `87f93b1...` metric visual anchor integrated before PnP;
- `fc2b8ef...` peer bootstrap path introduced;
- `1787fd38...` static pre-refinement coordinator/renderer baseline;
- `4ee42ea...` continuous refinement/surface work — do not wholesale restore;
- `77402f00...` multi-angle surface learning — dormant until baseline validation;
- `1b592692...` deterministic Wi-Fi Aware reconnect — preserve;
- `5ceecac...` static alignment + deterministic transport stabilization baseline that still exposed the physical false-lock bug;
- current false-lock hardening series after `5ceecac...` adds metric-first solving, independent proof, fail-closed lock state, watchdogs, exact-support reprojection, and updated diagnostics.

## 18. Physical acceptance test — mandatory before new features

Do not call the current stabilization "done" based only on CI. CI proves build/tests/signing; the central problem is physical geometry.

Use the same signed release APK on both phones. Force-stop/reopen both when changing builds.

### A. Direct transport / reconnect

Repeat at least 5 times:

1. CREATE on host;
2. JOIN on client;
3. left pill must become `DIRECT • <peer>` promptly;
4. disconnect;
5. reconnect without waiting a minute or repeatedly seeing `Direct Wi-Fi data path unavailable`.

### B. Shared lock

For each connection:

1. both phones get local ARCore tracking;
2. both point at the same textured, depth-rich scene;
3. move gently side-to-side;
4. expected: `ALIGNING -> CONFIRMING -> LOCKED` in seconds, not minutes;
5. if evidence is poor, staying ALIGNING is acceptable; false LOCKED is not.

### C. Cross-device POI accuracy

Once LOCKED:

1. phones approximately 0.1–1 m apart;
2. tap a distinctive physical point ~1–3 m away on A;
3. sender-local marker must be correct;
4. B must show the marker on the same physical point;
5. reverse direction B -> A;
6. repeat on at least 5 targets at different depths/angles.

Hard failure:

```text
sender target ~1–3 m
receiver target tens of metres away
UI still says LOCKED
```

The new runtime is specifically designed to prevent this condition. If evidence later contradicts the lock, it should revoke it and reacquire rather than lie.

### D. Post-lock view divergence

After a correct lock:

1. point phones in different directions for several seconds;
2. absence of common visual evidence must be treated as inconclusive, not immediate failure;
3. return both to common textured geometry;
4. watchdog should validate without moving the world.

### E. Drift / movement

Walk around with one or both devices while maintaining ARCore tracking. The static shared transform must not be silently blended. If the worlds genuinely become inconsistent and common evidence proves the contradiction, lock should reset/reacquire.

## 19. What to do if the physical build still fails

Do not guess and do not globally loosen thresholds.

First inspect the session recorder:

- `quality.ndjson`
- `events.ndjson`
- recorded frame bundle/replay data

Determine which exact stage is failing:

```text
DIRECT?
frames exchanged?
SIFT matches?
paired metric support?
direct 3D/3D fit?
Essential fallback?
metric proof?
local consensus?
peer proposal?
local peer verification?
ACK?
post-lock watchdog?
RTT contradiction?
```

If it stays ALIGNING, identify the blocker from diagnostics before changing a gate.

If it says LOCKED but any remote point is wrong, treat that as a safety invariant violation. Capture both quality logs and do not mask it with POI offsets.

## 20. Development discipline for the next agent

1. Read `AGENTS.md` and this file before editing.
2. Fetch the current branch HEAD and recent commits; do not assume this SHA is still current.
3. Preserve the deterministic Wi-Fi Aware transport unless a physical transport test specifically disproves it.
4. Do not weaken mandatory metric proof just to regain faster lock.
5. Do not re-enable continuous world refinement yet.
6. Do not re-enable cached relocalization in the startup solver yet.
7. Keep all app UI strings English.
8. Prefer isolated commits: transport, alignment, POI/surface, UI should not be mixed in one giant change.
9. Every runtime change must pass unit tests, lint, debug/release APK build, signature verification and `latest-dev` publish.
10. Do not claim a physical tracking bug is solved until a real two-phone test confirms it.

## 21. Release workflow

Workflow: `.github/workflows/ci.yml`, name `spatial-sync-v3-ci`.

A successful branch push publishes signed APKs to release tag `latest-dev`.

Normal physical-test APK:

`https://github.com/SirPaul-code/spatial_ar_coop/releases/download/latest-dev/SpatialSync-latest-release.apk`

Debug APK:

`https://github.com/SirPaul-code/spatial_ar_coop/releases/download/latest-dev/SpatialSync-latest-dev.apk`

Stable signing certificate SHA-256:

`9E:75:2E:4F:1A:E0:D9:F1:DD:72:B1:7A:3D:72:D8:8E:82:E5:40:75:65:1D:C2:68:65:78:77:A0:E7:3E:AF:3A`

Before telling the user to install a new build, verify:

- workflow completed;
- conclusion `success`;
- release `latest-dev.target_commitish` equals the intended final commit;
- release APK asset exists.

## 22. Immediate next priority

**No new feature work until the high-assurance build passes the physical acceptance test above.**

If it passes, the next safe sequence is:

1. benchmark lock time / cross-device spatial error / long-run drift;
2. optimize duplicate SIFT extraction without changing acceptance semantics;
3. reintroduce surface-specific target fingerprinting as an isolated POI-layer feature;
4. reintroduce multi-angle target view learning as an isolated POI-layer feature;
5. harden Bird's Eye to consume only verified canonical state;
6. only then consider protocol V7 / N-peer / persistence improvements.
