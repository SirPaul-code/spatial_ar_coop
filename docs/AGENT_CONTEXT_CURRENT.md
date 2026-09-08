# Spatial Sync — Current continuation delta

**Read order:** `AGENTS.md` -> `docs/AGENT_CONTEXT.md` -> this file.

This file is the authoritative delta after the older 0.7 handoff. The code remains source of truth.

## Current runtime checkpoint

Working branch: `fresh/no-map-runtime-poc`.

Latest runtime sequence before this documentation update:

- `a66ee0fa69fd9393572669ee045cce728ba883f4` — `fix: keep cached relocalization off the fresh lock path`
- `73bf37c21a9f8281f3a0cfb07cc471433ec3bccd` — `fix: retry fresh acquisition bursts sooner`
- `453ae33e88cc6433a78a88d8272b5e9726770a83` — `test: keep fresh burst ahead of cached relocalization`

The previous precision/runtime commits remain:

- `77402f00f60ac30a4fc6b1fb2114a7d6a88d00ad` — `feat: learn multi-angle target surface views`
- `4ee42ea77f2ea1eb084e12ddbf05fecabe635530` — `fix: continuously verify world and surface-locked POIs`

The fresh-lock regression fix intentionally changes startup scheduling, not solver thresholds. Preserve it unless hardware evidence proves a better architecture.

## Latest physical feedback

Two distinct pieces of hardware feedback matter:

1. Before the latest precision/surface batch, startup shared-world acquisition had become excellent: the two phones commonly reached `LOCKED` in only a few seconds.
2. After repeated successful sessions and newer builds, the user observed a regression where both phones again remained in `ALIGNING` for a long time.

The important diagnosis is that a successful previous session creates a persisted `SharedLandmarkCache`. On later starts, `AlignmentCoordinator.maybeRelocalize()` could begin after roughly 900 ms and grab the same single `solving` guard / single-thread solve executor used by the fresh host-canonical acquisition path. `RelocalizationEngine.relocalize()` performs two full `AlignmentEngine.solve()` calls. A stale or expensive cached checkpoint could therefore serialize/starve the synchronized fresh burst even though both local ARCore sessions were already TRACKING.

The fix is fail-safe and scheduling-oriented:

- any current frame carrying a non-zero acquisition `burstId` makes `RelocalizationEngine.relocalize()` yield immediately;
- fresh synchronized host-canonical acquisition always gets first use of the expensive solver;
- cached relocalization remains a fallback concept, but it must never compete with the initial fresh burst;
- acquisition retry quiet time was shortened from 5.0 s to 1.5 s, so a failed first 12-frame burst retries much sooner;
- a unit test guards the rule that an active fresh burst short-circuits cached relocalization before touching the expensive visual solver.

Do not “fix” this regression by loosening visual/depth gates. The failure was scheduling/resource contention, not evidence that the spatial thresholds were too strict.

## 1. Continuous post-lock shared-world verification

`4ee42ea...` changed the previous "freeze the room transform forever" behavior.

`AlignmentCoordinator` now keeps the initial host-canonical lock, but after `LOCKED` the host periodically runs a deliberately strict visual+metric drift watchdog on fresh shared frames.

Important properties:

- host-only refinement; the room still has one canonical authority;
- only runs after a verified peer transform exists;
- uses the same authoritative visual solver ladder rather than sensors as the spatial source;
- requires strong inliers/correspondences, reprojection, coverage, confidence, gravity, physical-range compatibility and metric residual/inlier gates;
- tiny scene-space disagreement is a deadband and causes no change;
- small bounded corrections may be blended directly;
- larger but still plausible corrections require a stable multi-frame candidate cluster;
- rotation blending uses rigid-transform quaternion interpolation rather than element-wise matrix interpolation;
- implausible large corrections are rejected fail-closed;
- accepted corrections are broadcast so the peer follows the same canonical world.

This exists specifically to correct slow relative ARCore/VIO drift without returning to the earlier behavior where unstable refinement could throw POIs across the room.

## 2. Manual target is now a physical surface reference, not only XYZ

A manual tap can now carry a `SurfaceTargetReference`:

- the exact CPU-camera frame captured around placement time,
- its camera pose + intrinsics,
- dense metric support/depth,
- the exact tapped image pixel.

`ArRenderer.handleTap()` captures this reference at placement. `AlignmentCoordinator.sendSurfacePoi()` sends the exact reference frame on the reliable control FIFO immediately before the normal POI packet. The POI timestamp is used to associate the point with that precise reference frame on the receiver.

The receiver stores the reference in `SurfaceTargetRegistry` and creates its normal local ARCore anchor provisionally.

### SurfaceTargetResolver

`SurfaceTargetResolver.kt` periodically proves that a target is still on the original physical surface:

1. detect SIFT features in a bounded patch around the target in the trusted reference image;
2. match them against the current camera frame using bidirectional ratio-filtered matching;
3. estimate a RANSAC homography;
4. transform the original exact target pixel into the current image;
5. require metric depth support at the transformed pixel;
6. unproject that pixel/depth through the current ARCore camera pose;
7. reject if reprojection, depth support, confidence or correction magnitude is implausible.

This is intentionally fail-closed. If the surface cannot be proven, keep the existing ARCore anchor instead of applying a speculative correction.

`ArRenderer` schedules these checks from normal locked capture frames. A correction must pass additional consensus logic before a target is re-anchored. Local corrected targets can be re-shared to the peer with a refreshed surface reference; once a peer independently sees and verifies the physical surface, it can continue from its own local reference.

This separates two different errors:

- **room/world drift** -> strict host continuous registration,
- **specific POI drift** -> per-target texture + depth relocalization.

## 3. Multi-angle target surface atlas

`77402f00...` extends each trusted surface target from one reference image into a bounded on-device multi-view landmark atlas.

Why: a target created from the front should remain recoverable after walking around it. A single planar-looking reference can fail at larger viewpoint changes even if the target is still clearly visible.

Rules:

- a new view may be learned only after an existing trusted view has already successfully matched the current frame and metric depth agrees;
- unverified observations can never become references;
- max `8` stored views per target;
- max `4` candidate references tried per resolve;
- original/root tap reference is retained as fallback;
- newest learned views are preferred because they are likely closer to the current viewpoint;
- learned view requires roughly:
  - confidence >= `0.25`,
  - >= `9` visual inliers,
  - reprojection <= `2.4 px`,
  - >= `3` metric depth supports;
- a view is retained only if sufficiently novel by camera baseline/view angle/range, approximately:
  - `0.14 m` baseline, or
  - `9 deg` viewing-angle change, or
  - `12%` range change;
- very strong matches may short-circuit further reference attempts;
- target banks and aliases are bounded/pruned to prevent unbounded memory growth.

The intent is a trust chain:

`original tap -> verified nearby view -> verified side view -> verified farther side view`

not uncontrolled self-training.

## 4. Current ArRenderer target behavior

Static manual targets remain additive maps (`localTargets`, `remoteTargets`). They now store:

- local ARCore anchor,
- owner/confidence metadata,
- optional `SurfaceTargetReference`,
- whether the surface has been independently verified,
- pending correction + vote count,
- local re-share timing.

Remote target XYZ updates remain provisional until that target's own surface verifier succeeds. Once a target is independently surface-verified on a phone, generic room-transform updates must not blindly overwrite its more specific local surface evidence.

Dynamic cars remain world-space dynamic tracks and do not use this static surface-anchor machinery.

## 5. What is already present from the 0.7 architecture

Do not rebuild these from scratch:

- fast host-first canonical alignment;
- synchronized acquisition bursts;
- Essential + direct 3D/3D + PnP solver ladder;
- peer bootstrap without hidden double-solve requirement;
- range/gravity sanity checks;
- alignment flight recorder (`events.ndjson`, `quality.ndjson`, `frames.spv6`);
- recorded replay tooling;
- cached visual relocalization, now strictly subordinate to fresh acquisition;
- multi-target static POIs;
- automatic car recognition/tracking;
- V6 dynamic-target protocol;
- canonical `SharedRoomState` data model;
- real Bird's Eye / WORLD visualization and spatial map accumulation.

The live physical transport is still one active peer/socket per app instance even though the state model is N-peer ready.

## 6. Immediate validation priority

Use the same V6 release on both phones.

### Startup regression test

1. Start both apps and let both local ARCore sessions reach `TRACKING`.
2. CREATE/JOIN the same room while both cameras see the same textured area.
3. Fresh synchronized acquisition must get priority over any persisted landmark cache.
4. Expect `LOCKED` in the same few-second regime as the previously good build.
5. Repeat several disconnect/reconnect cycles specifically because the regression was most plausible after a successful session had already persisted a cache.
6. If a retry is needed, the next acquisition burst should begin about 1.5 s after the 12-frame burst ends rather than waiting 5 s.
7. If it still remains `ALIGNING` for >10–15 s, pull `quality.ndjson`, `events.ndjson`, and `frames.spv6` and identify the exact blocker before another algorithm change.

### Manual POI test

1. lock both phones;
2. place a marker on a distinctive physical texture with usable depth;
3. verify it from both phones;
4. walk around the target gradually so the multi-view atlas can learn trusted novel views;
5. return to the original angle, then inspect from side/high/low angles;
6. repeat after several minutes of phone motion to test both room refinement and target-specific correction;
7. measure actual cross-device physical error, not only visual impression.

If target correction is still poor, instrument the surface verifier rather than loosening it blindly. High-value telemetry is:

- reference view selected,
- atlas view count,
- visual matches/inliers,
- homography reprojection median,
- depth support count,
- proposed correction metres,
- correction vote count,
- correction accepted/rejected reason.

## 7. Do not confuse future business discussion with current runtime scope

There was a later discussion about potentially packaging target persistence / colocation as an SDK. No SDK extraction, licensing system, Maven publishing or product split has been implemented. The repo remains the Android application/runtime POC. Business packaging is intentionally deferred unless the user explicitly resumes it.

## 8. Context maintenance rule

After any future material change to alignment, surface locking, transport protocol, target semantics, relocalization or WORLD state, update this delta (or merge it back into `docs/AGENT_CONTEXT.md`) and update the runtime checkpoint in `AGENTS.md`.
