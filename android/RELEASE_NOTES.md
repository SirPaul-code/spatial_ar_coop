# Android release notes

## 1.0.4

- Preserve the last valid shared transform after a successful Cloud Anchor resolve through temporary anchor tracking pauses.
- Show `room connected/reconnecting` and the count of buffered remote tracks even while a phone is still localizing.
- Do not draw a participant's own network-spatial boxes over its local raw detector boxes.
- Remove the class-size monocular 3D fallback; shared moving-object tracks now require Depth, a valid upward-facing plane, or saved-ground evidence.
- Retain v1.0.3 detector NMS, stricter person/car thresholds, two-hit track confirmation, short stale-track lifetime, and uncapped ARCore resolve duration.

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
- Spatial target estimation keeps Depth/plane/feature/ground hits as preferred sources and adds a high-uncertainty monocular class-size fallback when ARCore cannot produce a usable contact-point hit.
- Detector recall is tuned for field use, including small `bird` targets used for chicken tracking.
- Includes the scoped Samsung Android 16 ARCore sensor-queue keepalive workaround for affected devices running current Google Play Services for AR.

Passing CI verifies compilation, unit tests, server tests, dependency checks, container build, and debug APK assembly. Multi-phone Cloud Anchor localization and end-to-end shared track rendering still require a physical field test.
