# Current runtime continuation — 2026-09-12

This file is the authoritative short delta on top of `docs/AGENT_CONTEXT.md`.

## Active integration continuation

For the current StableAR product-integration work, the explicit working branch is `integration/p2p-stablear`. It is 14+ commits ahead of, and not behind, the protected direct-P2P product baseline `fresh/no-map-runtime-poc@a5970e9be7fef9434dbf2e681274dc56c9462fe4`.

The integration preserves the physically tuned direct-phone product boundaries: `WifiAwarePeerTransport`, V6 peer framing, acquisition startup, `AlignmentCoordinator`, shared-world solvers and dynamic-vehicle ownership remain product-owned. StableAR is injected only into **static material POI refinement**.

StableAR source provenance on this branch is exact: the complete `/sdk` subtree has Git tree SHA `4076558f24f8454a88791dc121ba3937424b0547`, the same tree as `stablear/multiplatform-sdk@59ec2cb952008c3c7b92843ee5088a466a967cc7` at the integration checkpoint. See `docs/STABLEAR_P2P_INTEGRATION.md` for the detailed flow and package gates.

Current runtime integration fixes that must survive future edits:

- product ARCore hit/depth selection remains the initial static-POI authority, avoiding the standalone SDK's stricter close-range `SurfaceFitter` as a placement prerequisite;
- `StableArP2pBridge` seeds StableAR through `placeWithDepthPrior` using the exact CPU-image pixel and host-trusted z-depth;
- XFeat/LiteRT is preferred, LK/ORB is fallback, and XFeat template candidates are committed only after StableAR geometric/held-out acceptance;
- a depth-prior-only StableAR attachment is **not** authoritative until immutable visual root evidence is actually captured; `worldPoint()` stays fail-closed until then;
- if product/legacy surface bootstrap obtains a newer trusted point before StableAR visual evidence arms, the dormant StableAR seed is replaced rather than preserving stale geometry;
- remote POIs use the existing fail-closed visual/metric bootstrap until receiver-local StableAR root evidence is available;
- cars remain dynamic P2P tracks and must never become StableAR attachments.

Latest runtime checkpoint before this documentation update:

- `1e25835f86fb8ed72ebfee5ebc8b7e453b3a837b` — `fix(p2p): keep dormant StableAR seeds fail-closed`.
- `ec5cbfeba5ae306587cb643ed202f2854be9173a` — removed two Android `Range` lint blockers in `BirdEyeWorldView` without disabling lint or changing spatial architecture.

Final release gate for this integration is the `spatial-sync-v3-ci` workflow on the **current integration branch HEAD**. It must pass unit tests, debug/release lint, debug/release assembly, StableAR XFeat/native payload checks, stable APK signature verification and `p2p-stablear-latest` prerelease publication. Do not call the integration release-ready from an older/cancelled run.

Physical validation remains required after software/package CI: two-phone CREATE/JOIN/LOCKED regression check, local and remote static POI orbit/distance/occlusion tests, and confirmation that vehicle behavior is unchanged. CI is not a cm/mm accuracy claim.

## Branch and protected baseline

Product baseline branch: `fresh/no-map-runtime-poc`.

The physically useful baseline immediately before this iteration is:

- `02fc3c4ae145e25fb429936da94b68a8aa020f38` — `fix: keep golden alignment and harden manual POI placement`.
- User reported CREATE/JOIN shared-world startup as fast/useful again before requesting the changes below.
- Do **not** casually change `AlignmentCoordinator`, acquisition-burst startup, `WifiAwarePeerTransport`, Wi-Fi Aware NDP/TCP handshake, or protocol framing while tuning vehicles/POIs. The current vehicle and target-precision work is intentionally isolated from those systems.

## Vehicle tracking: one physical car -> one room track

The previous renderer had two independent maps (`localVehicles` and `remoteVehicles`) but local detections associated only against local tracks. If both phones saw the same car they therefore produced two unrelated room ids and the UI rendered overlapping `CAR • YOU` and `CAR • <peer>` markers. The physical video from 2026-09-09 demonstrated this clearly.

The current design fixes that at the renderer/track layer without changing the wire protocol:

- `VehicleDetector.Vehicle` exposes a detector-local stable `trackId`, filtered `velocityWorld`, and a depth-derived `WorldBox` in addition to the centroid.
- The renderer maps detector-local ids onto persistent room/shared ids.
- Before a local phone publishes a new car, it checks active remote tracks. An active peer track reserves that physical volume and suppresses a duplicate local publication.
- If both phones create ids simultaneously, both apply the same unsigned-lowest-id rule (`VehicleTrackPolicy.winnerId`) and converge to one owner/id without a new negotiation message.
- Ownership is leased, not permanent. If the current owner stops observing a moving car for about 2.8 s, another peer that still sees it may take ownership.
- Dynamic positions use constant-velocity prediction during short detector misses; prediction is bounded (~2.6 s) and tracks are removed after a longer memory TTL (~6.5 s).
- Incoming stale tracks cannot permanently block reacquisition.
- The existing V6 dynamic-target `WireMessage.Poi`/`AUTO:CAR:` payload remains unchanged on purpose. Do not bump the transport protocol just to tune the visual tracker.

## Vehicle 3D presentation

`VehicleDetector` now estimates a world-space oriented box from metric supports:

1. detector 2D box selects central ARCore metric supports,
2. median-range clustering removes background/glass outliers,
3. horizontal X/Z PCA estimates the major vehicle direction,
4. robust 10–90 percentile extents estimate visible geometry,
5. class-specific minimum/maximum half-extents prevent sparse depth from collapsing the box,
6. when the vehicle is moving, filtered velocity becomes the preferred heading.

The renderer projects the eight OBB corners into the camera and `TargetOverlayView` renders the 12 wireframe box edges plus the normal centre marker/label. Remote tracks that do not carry full OBB geometry get a conservative box, oriented from inferred motion when possible.

This is presentation geometry, not an assertion that monocular phone depth has measured an automotive-grade cuboid.

## Manual POI precision stack

Do not replace the current good ARCore hit selection. The target precision path is additive:

1. ARCore plane/feature/depth hit chooses a sane physical surface; nearby tracked surfaces beat bogus long-range depth.
2. A reference CPU-camera frame plus the exact local tap pixel is retained as `SurfaceTargetReference`.
3. `SurfaceTargetResolver` matches texture around that pixel, estimates a local homography, maps the exact pixel into the current frame, verifies metric depth there and may correct the local anchor.
4. The bounded multi-view surface atlas is restored **only inside `SurfaceTargetResolver`**. A target can learn up to 8 verified viewpoints and resolve against up to 4 of them. New views are admitted only after a trusted visual+metric solve and meaningful baseline/view-angle/range change. This must not be confused with shared-world alignment.
5. `SurfaceEdgeSnapRefiner` is a final optional fail-closed precision layer. After the strong SIFT/homography solve, it equalizes both grayscale patches, computes Canny edges and performs a very small normalized-correlation search around the predicted pixel. The nudge is accepted only with sufficient edge support/correlation and compatible metric depth/world displacement. Failure simply leaves the coarse surface result untouched.

The edge snap is specifically intended for the user's request to look at the actual pixel/edge texture under the marker and reduce small on-surface offsets, including moderate low-light contrast changes. It must never be allowed to move a target based on edges alone.

## Important exact implementation boundaries

Files intentionally changed by the vehicle/precision baseline iteration:

- `ArRenderer.kt`
- `VehicleDetector.kt`
- `TargetOverlayView.kt`
- `VehicleTrackPolicy.kt` (new)
- `SurfaceEdgeSnapRefiner.kt` (new)
- `SurfaceTargetResolver.kt` (restores the previously tested target-only multi-view atlas implementation)
- `VehicleTrackPolicyTest.kt` (new)
- handoff docs

Files intentionally **not** changed by the StableAR integration:

- `AlignmentCoordinator.kt`
- `WifiAwarePeerTransport.kt`
- `PeerProtocol.kt`
- acquisition-burst controller
- shared-world solvers

If CREATE/JOIN/LOCKED regresses after this iteration, first verify the installed APK SHA/version and compare those untouched files before changing thresholds.

## Physical acceptance test

Run the same signed release APK on both phones.

1. CREATE/JOIN: both must reach `LOCKED` at the same speed/reliability as the `02fc3c4` baseline.
2. Parked car seen by both phones for >=10 s: each UI should converge to **one** shared vehicle track, not overlapping local+remote targets.
3. Walk/pan away briefly (<2.5 s) and back: the vehicle track should coast/hold instead of spawning a new id.
4. Hide/leave the vehicle long enough (>6.5 s): it should disappear from memory; reappearance may create a new track.
5. Moving car: box centre should move smoothly; heading should prefer velocity once speed is meaningful.
6. Manual target: tap a textured physical point, walk around it, return from several angles. It should keep one POI id, learn verified views and correct small surface offsets without jumping to similar nearby texture.
7. Low light: failure to obtain trustworthy visual/edge/depth evidence must leave the anchor where ARCore has it; it must not invent a correction.

Do not call a vehicle or POI precision change complete from CI alone; use this two-phone physical test.
