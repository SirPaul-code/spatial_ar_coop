# Spatial Sync final vehicle tracking pass — 2026-09-15

## Why this pass exists

Physical two-phone testing exposed three real defects in the previous vehicle path:

1. sparse-depth PCA could rotate a vehicle cuboid 90 degrees or toward background geometry;
2. detection was gated by depth availability and throttled to 450 ms, so road-speed vehicles could cross the association gate between observations;
3. dynamic packets carried only XYZ, so a peer could not reproduce the sender's cuboid yaw and could render a second/misaligned box.

## Final architecture

- EfficientDet-Lite0 remains on-device recognition, but the detector now uses a **latest-frame mailbox**. If inference is busy, stale pending camera images are closed and replaced by the newest frame instead of adding latency.
- Submission cadence is 120 ms (~8.3 Hz target). Actual inference rate is hardware-dependent, but inference always consumes the freshest available frame.
- 2D recognition is **not gated by depth**. ARCore depth/point-cloud data enrich translation when available; otherwise an intrinsics + class-height range estimate supplies a temporary 3D observation which later metric observations correct.
- Sparse metric supports determine **translation only**. PCA yaw and PCA dimensions are removed.
- Cuboid dimensions are fixed physical class priors (car/truck/bus), preventing a few background depth points from creating giant boxes.
- Initial yaw uses camera-to-target bearing plus silhouette aspect (rear/front vs side-view line). Once horizontal vehicle velocity is >= 1 m/s, velocity becomes the yaw authority with temporal continuity.
- Detector-local and room-level association gates grow with observed speed and elapsed time, bounded to prevent arbitrary merges.
- Duplicate 2D boxes are NMS-filtered before 3D estimation.
- Dynamic vehicle owner metadata compactly carries class + 180-degree yaw inside the existing V6 owner field, so no protocol-version break is required. AlignmentCoordinator rotates the peer yaw line through the shared-world transform before handing it to ArRenderer.
- Render-time cross-device duplicate suppression is an additional safety net while canonical IDs converge.
- Dynamic vehicle packets no longer trigger the user-facing "POI added" banner/haptic.

## Scope boundary

Vehicle tracks remain dynamic and are intentionally NOT StableAR material attachments or permanent ARCore anchors. StableAR continues to own static material POIs.

## Verification gate

The applying CI workflow must pass:

```
gradle -p android --no-daemon --stacktrace :app:testDebugUnitTest :app:assembleDebug
```

and confirm the packaged XFeat model before committing/publishing the APK.

## Physical final acceptance

1. parked vehicle rear/front: cuboid longitudinal axis must not randomly sit across the bumper;
2. side view: cuboid may choose either 180-degree direction but must follow the vehicle's long axis;
3. no giant cuboid caused by wall/road/tree depth leaking into the 2D box;
4. drive-by vehicle at road speed must be detected while crossing the camera, with no stale-frame inference backlog;
5. second phone looking at the same vehicle must converge to one logical shared target, not persistent green + blue duplicates;
6. one phone turns away while the other still observes: track stays alive; after both lose it, expiry remains bounded;
7. remote cuboid orientation should agree with sender after the shared-world transform;
8. LOCKED alignment/static StableAR POIs must remain unaffected.

## Resume instruction

If physical tests still fail, log the exact screenshot/video + approximate range/speed and instrument detector inference time, fallback-vs-metric source, track id, shared id, association distance/gate and yaw source. Do not return to sparse-depth PCA.
