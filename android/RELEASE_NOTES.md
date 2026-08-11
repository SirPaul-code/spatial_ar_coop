# Android release notes

## 1.0.5

- Add temporal image-space association before spatial tracking. Weak detections may maintain an existing object, but only stronger class-specific detections may create a new identity.
- Lower the detector candidate floor for difficult `bird`/chicken views while requiring temporal confirmation before any detection can become a shared 3D track.
- Smooth detector boxes over time and keep short confidence dips/partial occlusion from immediately destroying an object's local identity.
- Bind every accepted detection to the ARCore camera pose and image intrinsics captured with that detector frame, so inference latency cannot mix an older bbox with a newer camera pose while the phone is moving.
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
