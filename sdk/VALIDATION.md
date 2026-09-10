# Physical validation and interpreting the Lab app

No physical-device acceptance has been recorded for this implementation.
A built APK, a successful lint run and synthetic Kotlin checks are separate facts.

## Paired unrefined reference

The yellow ring is the initial reconstructed point, transported by the same live
ARCore surface anchor as the green ring. The green ring adds accepted SDK ray-depth
refinement. Both are drawn in the same GL camera pass. If no correction is accepted,
they coincide (different ring sizes keep the paired reference visible).

This isolates the effect of our refinement. Yellow is NOT independently measured
ground truth, and it is NOT a full comparison against a different ARCore hit-test
application. Both share the same VIO, initial surface fit and native anchor.
`refinementDisplacementM` measures how far we changed the point, not how much more
accurate it became. `conditionalSigmaM` remains an assumed error model, not a
calibrated centimetre guarantee. Rejection/coverage must be reported with errors.

## First real-device run

Install the independent `com.sirpaul.stablear.demo` APK on an ARCore-supported
ARM64 phone. It does not replace ShowMe or Spatial Sync. Grant Camera permission.
Use a well-lit, textured, static surface roughly 0.5-2 m away. Scan with lateral
motion, tap an unambiguous material point, then move sideways and return. Observe
both rings, accepted correction count, and visual failures. Export JSON using the
explicit button; no camera images are included or uploaded by the application.

Test a slanted surface, a depth edge, repeated screw/tile patterns, glossy surfaces,
a hand passing in front, temporary camera obstruction, rapid rotation, portrait /
landscape, pause/resume and a 15-minute sustained session. Clear and repeat at least
20 placements per scene/device. Include rejected placements in the denominator.
The demo deliberately invalidates references on backgrounding; saved cross-session
maps are not implemented. User-selected JSON exports remain on the device.

Measure selected-point reprojection error from independently annotated video,
physical error against calibrated fiducials or a known surface, time to reacquire,
false target switches, retention after the return path, p50/p95/max render time,
and thermal throttling. A separate fiducial evaluation must not silently become
an input to the tested markerless algorithm. Do not infer millimetres directly
from an arbitrary screen recording without calibration and surface geometry.

## Packaging and licences

Before redistributing a rebuilt demo, run the exact dependency exporter and audit,
then `python3 sdk/tools/native_notices.py`, then rebuild the APK. CI performs these
steps automatically. The native notice collector verifies the pinned official
OpenCV Android ZIP SHA-256 and compares its native libraries with the resolved
Maven AAR. It adds the official release's licence texts to the demo asset before
the final APK build. Any mismatch remains visible in `native-provenance.json`.

Read `compliance/REVIEW.md`, `compliance/TOOLCHAIN_TERMS.md` and the generated
licence report. Byte equality establishes provenance, not legal clearance for the
business model or an absence of third-party patent rights. The research artefacts
are not represented as an authorized, certified commercial SDK release.
