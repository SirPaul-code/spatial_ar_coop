# Android release notes

## 1.0.3

- Cloud Anchor resolves are no longer cancelled/restarted after an arbitrary 12-second app timeout; pending ARCore resolves are allowed to complete normally until success, a real ARCore result state, explicit re-localization, or teardown.
- Dynamic object tracks prefer the mapped ground/contact ray before background geometry, preventing people/cars from spawning distant ghost tracks on walls, trees, or other feature points behind the object.
- Without mapped ground, spatial estimation accepts depth or an upward-facing horizontal plane before falling back to class-size monocular range.
- Monocular fallback uses class-appropriate bbox dimensions and the bbox bottom-contact ray.
- The source phone no longer renders its own server-echoed spatial tracks on top of its precise local detector bounding boxes.

## 1.0.2

- Live AR now starts on-device object detection and compact track sharing automatically for every participant. There is no separate reporting opt-in in the normal Live flow.
- Local `person`, `car`, `bird`, `dog`, and `cat` detector boxes are visible while shared Cloud Anchor localization is still in progress, making detector health independent from localization status.
- Shared localization tries several hosted Cloud Anchors concurrently and uses the first successful shared reference.
- Spatial target estimation includes a high-uncertainty monocular class-size fallback when ARCore cannot produce a usable contact-point hit.
- Detector recall is tuned for field use, including small `bird` targets used for chicken tracking.
- Includes the scoped Samsung Android 16 ARCore sensor-queue keepalive workaround for affected devices running current Google Play Services for AR.

Passing CI verifies compilation, unit tests, server tests, dependency checks, container build, and debug APK assembly. Multi-phone Cloud Anchor localization and end-to-end shared track rendering still require a physical field test.
