# Android release notes

## 1.0.2

- Live AR now starts on-device object detection and compact track sharing automatically for every participant. There is no separate reporting opt-in in the normal Live flow.
- Local `person`, `car`, `bird`, `dog`, and `cat` detector boxes are visible while shared Cloud Anchor localization is still in progress, making detector health independent from localization status.
- Shared localization now tries several hosted Cloud Anchors concurrently, uses the first successful shared reference, times out stalled batches, and retries automatically.
- Spatial target estimation keeps Depth/plane/feature/ground hits as preferred sources and adds a high-uncertainty monocular class-size fallback when ARCore cannot produce a usable contact-point hit.
- Detector recall is tuned for field use, including small `bird` targets used for chicken tracking.
- Includes the scoped Samsung Android 16 ARCore sensor-queue keepalive workaround for affected devices running current Google Play Services for AR.

Passing CI verifies compilation, unit tests, server tests, dependency checks, container build, and debug APK assembly. Multi-phone Cloud Anchor localization and end-to-end shared track rendering still require a physical field test.
