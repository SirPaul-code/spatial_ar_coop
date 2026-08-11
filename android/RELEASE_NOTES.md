# Android release notes

## 1.0.3

- Live participants still join the WebSocket room independently of Cloud Anchor localization; the UI now says `room joined` so network state is not confused with spatial localization state.
- Removed the app-level 12-second Cloud Anchor resolve cancellation that could repeatedly restart a still-valid ARCore localization attempt. Concurrent anchor resolves are now allowed to finish and exact per-anchor failure states are surfaced when all candidates fail.
- Local multi-object identity now uses normalized 2D bounding-box continuity as the primary association signal and shared 3D distance as a secondary signal, preventing one visible person or chicken from exploding into many `tXX` IDs when a depth estimate jumps.
- Implausible 3D depth/plane/ground hits are checked against a coarse class-size monocular range estimate before they are accepted. Large one-frame spatial jumps are damped instead of teleporting or spawning a new track.
- A source phone no longer re-renders its own shared amber track on top of its raw detector box; shared spatial overlays are reserved for tracks received from other participants.
- `person`, `car`, `dog`, and `cat` return to the configured detector threshold. `bird` keeps a lower 0.30 field threshold for chicken recall.

## 1.0.2

- Live AR starts on-device object detection and compact track sharing automatically for every participant. There is no separate reporting opt-in in the normal Live flow.
- Local `person`, `car`, `bird`, `dog`, and `cat` detector boxes are visible while shared Cloud Anchor localization is still in progress, making detector health independent from localization status.
- Shared localization tries several hosted Cloud Anchors concurrently and uses the first successful shared reference.
- Spatial target estimation keeps Depth/plane/feature/ground hits as preferred sources and adds a high-uncertainty monocular class-size fallback when ARCore cannot produce a usable contact-point hit.
- Includes the scoped Samsung Android 16 ARCore sensor-queue keepalive workaround for affected devices running current Google Play Services for AR.

Passing CI verifies compilation, unit tests, server tests, dependency checks, container build, and debug APK assembly. Multi-phone Cloud Anchor localization and end-to-end shared track rendering still require a physical field test.
