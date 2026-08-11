# Android release notes

## 1.0.7

- Add MediaPipe Pose Landmarker Full for temporally confirmed `person` detections while keeping EfficientDet-Lite2 as the object detector and person identity gate.
- Use the same captured camera image for person detection and pose inference, then associate each returned pose to the corresponding confirmed person identity in image space.
- Use visible ankle/foot landmarks as the preferred person ground-contact image point before spatialization, reducing root jitter caused by a changing person bounding-box bottom edge.
- Convert selected body landmarks into a compact shared-site skeleton relative to the existing AR person root. Pose landmarks shape the articulation; Cloud-Anchor/site localization remains authoritative for the person's absolute physical position.
- Share only compact root-relative joint data (`poseJoints`), never camera images or video. The server validates at most 24 unique MediaPipe landmark indices and strips pose data from non-person tracks.
- Smooth person joint offsets temporally and retain the last valid skeleton for a brief pose miss before falling back automatically to the existing person 3D cuboid.
- Render a blue local stick figure on the detecting phone and an amber shared-site stick figure on remote localized phones. A remote person without a usable pose remains visible as the existing 3D cuboid.
- Add pose count to Live AR diagnostics so field tests can distinguish person detection, pose inference, spatialization, active sharing and server acknowledgement.
- Package the official verified Google `pose_landmarker_full/float16/1` model reproducibly during the Android build together with the existing verified EfficientDet-Lite2 model.

## 1.0.6

- Upgrade the on-device detector to the verified EfficientDet-Lite2 int8 model for a higher-precision COCO front end on current Samsung devices.
- Make acquisition precision-first: weak `bird`/person/car scores may maintain an already acquired identity but cannot create a new visible/shared object by themselves; person/car also require repeated strong evidence.
- Preserve class-aware IoU + containment suppression and tighten temporal association so persistent clutter does not become a stable car/bird track merely because the classifier repeats a weak mistake.
- Separate `detected`, `spatialized`, `active` and server-acknowledged counts in the Live HUD. The server now explicitly acknowledges accepted `track_batch` snapshots, so field diagnostics can distinguish detector, 3D estimation and transport failures.
- Add a guarded capture-time monocular 3D fallback only for strong, temporally confirmed detections when ground/plane/depth cannot produce a stable solution. These high-uncertainty tracks require four consistent 3D observations before publication.
- Carry physical object extents `[width,height,depth]` and shared-site yaw in the realtime track protocol while retaining defaults for older clients.
- Render remote people/cars/birds as projected 3D wireframe cuboids instead of camera-facing 2D billboard rectangles, keeping the object volume anchored to the shared site frame through viewpoint changes and walls.
- Replace screen-coordinate offscreen arrows with camera-space bearing/elevation math and viewport-edge intersection, including correct handling for objects behind the camera.
- Reduce remote-client extrapolation to 250 ms and disable it for practically stationary objects to avoid double-prediction drift on top of the source tracker.
- Prefer the map root Cloud Anchor as the shared reference. Backup anchors join after an 8-second root preference grace period without cancelling the root resolve, preserving localization reliability while reducing cross-device reference drift.
- Show the active Cloud Anchor reference ID in the Live localization status so two physical phones can be checked against the same shared reference during field validation.

## 1.0.5

- Add temporal image-space association before spatial tracking. Person/car require a strong observation to create an identity; `bird` may start as an internal weak hypothesis but must persist across several consistent frames before it becomes visible or shared.
- Lower the detector candidate floor for difficult `bird`/chicken views, keep weak confirmed birds alive through short confidence drops, and retain temporal identities longer through brief mesh/occlusion gaps.
- Use class-aware duplicate suppression, including containment suppression for nested person/car boxes, before temporal association.
- Smooth detector boxes over time and keep short confidence dips/partial occlusion from immediately destroying an object's local identity.
- Bind every accepted detection to the ARCore image intrinsics and shared-site camera pose captured with that detector frame, so inference latency cannot mix an older bbox with a newer camera pose while the phone is moving.
- Prefer saved-ground contact projection for people, cars, birds, dogs and cats instead of allowing target/background Depth hits to redefine the shared position frame-to-frame.
- Reject near-horizon ground rays and physically implausible class scale/range combinations instead of publishing large 3D jumps.
- Harden the spatial tracker with measurement outlier rejection, conservative identity re-acquisition, adaptive correction, stationary deadband and bounded 300 ms motion prediction so stale velocity cannot keep drifting a marker.
- Keep WebSocket protocol/server behavior unchanged; 1.0.5 is a client-side perception/tracking stability pass on top of the field-verified v1.0.4 shared localization and synchronization path.

## 1.0.4

- Preserve the last valid shared transform after a successful Cloud Anchor resolve through temporary anchor tracking pauses.
- Show `room connected/reconnecting` and the count of buffered remote tracks even while a phone is still localizing.
- Do not draw a participant's own network-spatial boxes over its local raw detector boxes.
- Remove the class-size monocular 3D fallback; shared moving-object tracks now require Depth, a valid upward-facing plane, or saved-ground evidence.
- Retain v1.0.3 detector NMS, stricter person/car thresholds, two-hit track confirmation, short stale-track lifetime, and uncapped ARCore resolve duration.
- Field validation can now distinguish transport from localization directly on-device: `room connected` proves WebSocket room membership, while `remote buffered` proves tracks are arriving before spatial rendering is possible.

## 1.0.3

- Cloud Anchor resolve futures are no longer cancelled by the app after 12 seconds; pending ARCore visual matching is allowed to complete and real per-anchor failure states are surfaced before retry.
- Moving-object 3D estimation no longer accepts generic background feature-point hits. It uses Depth, upward-facing horizontal planes, saved-ground projection, then monocular class-size fallback.
- Monocular fallback projects the detector bottom-center and uses bbox height consistently, reducing depth jumps.
- Person/car/dog/cat use a stricter field threshold while `bird` keeps lower recall tuning for chickens; same-class overlap NMS removes duplicate raw detections.
- Spatial tracks require two observations before publication and expire faster, reducing one-frame IDs and stacked ghost tracks.
- Field validation should separately verify WebSocket room membership and Cloud Anchor localization: a participant can be connected and buffering tracks while it is still unable to render them spatially.

## 1.0.2

- Live AR now starts on-device object detection and compact track sharing automatically for every participant. There is no separate reporting opt-in in the normal Live flow.
- Local `person`, `car`, `bird`, `dog`, and `cat` detector boxes are visible while shared Cloud Anchor localization is still in progress, making detector health independent from localization status.
- Shared localization now tries several hosted Cloud Anchors concurrently, uses the first successful shared reference, times out stalled batches, and retries automatically.
- A last-resort monocular class-size estimate keeps clear detections shareable when ARCore has no hit/ground point, with deliberately larger uncertainty than depth/plane estimates.

## 1.0.1

- Introduced the explicit ARCore session lifecycle/state machine and Samsung compatibility fallback.
- Hardened Back/teardown behavior and renderer gating after ARCore session failures.
