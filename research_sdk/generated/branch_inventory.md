# All-branch inventory

Baseline: `4763e0fa2845b5b3e443fb9e298b7b2964a3067f`.

Structural coverage only; semantic review and hardware validation are separate.

| Branch | Head | Files | Source files | Changed paths |
|---|---|---:|---:|---:|
| `archive/pre-rollback-2026-09-09` | `a24f4638e72b` | 75 | 46 | 60 |
| `dependabot/docker/server/node-26-alpine` | `da1ce67161d7` | 111 | 60 | 204 |
| `dependabot/github_actions/actions/checkout-7` | `00c03c736604` | 108 | 59 | 201 |
| `dependabot/github_actions/actions/setup-java-6` | `38b1eba4b3a3` | 142 | 88 | 235 |
| `dependabot/github_actions/actions/setup-node-7` | `714af80669fb` | 111 | 60 | 204 |
| `dependabot/gradle/android/com.android.application-9.3.1` | `0cef805b84e6` | 108 | 59 | 202 |
| `dependabot/gradle/android/com.google.android.material-material-1.14.0` | `9f1ab03f1037` | 129 | 76 | 222 |
| `dependabot/gradle/android/com.google.mediapipe-tasks-vision-1.0.0` | `19bf864a4682` | 129 | 76 | 222 |
| `dependabot/gradle/android/gradle-wrapper-9.7.0` | `c2eb7c5530ad` | 108 | 59 | 201 |
| `dependabot/gradle/android/org.jetbrains.kotlin.android-2.4.10` | `b223ac509d61` | 108 | 59 | 202 |
| `dependabot/npm_and_yarn/server/ws-8.21.3` | `1cac5a80948c` | 128 | 76 | 221 |
| `feat/hifi-tracking-v1.2.0` | `dd8d8e126adc` | 140 | 86 | 233 |
| `feat/live-participant-gizmos` | `fb3c1aadb4f6` | 123 | 71 | 216 |
| `feat/ops-cop-v1.2.0` | `26869bc6a317` | 136 | 82 | 229 |
| `feat/person-pose-v107` | `6a4771f9342d` | 122 | 70 | 215 |
| `feat/spatial-debugger-v1.1.0` | `ec8902a376bc` | 128 | 76 | 221 |
| `feature/participant-gizmos` | `e032bb6be9e2` | 123 | 71 | 216 |
| `fix/car-motion-v1.2.1` | `5f21e13c6853` | 140 | 86 | 233 |
| `fix/detection-v1.1.2` | `7760fa62807d` | 132 | 79 | 225 |
| `fix/field-sync-v104` | `a4d71611ca6f` | 116 | 64 | 209 |
| `fix/final-live-auto-localize` | `fa1380495937` | 116 | 64 | 209 |
| `fix/lite2-checksum` | `89ca34e10c17` | 129 | 76 | 222 |
| `fix/live-v103-localization-tracker` | `7554f66e075f` | 116 | 64 | 209 |
| `fix/live-v103-spatial-overlay-final` | `54dbb55d79f5` | 116 | 64 | 209 |
| `fix/localization-and-track-quality` | `610c0dec1064` | 116 | 64 | 209 |
| `fix/localize-tracker-v103` | `8468839f432b` | 116 | 64 | 209 |
| `fix/perception-v106` | `ef5e65ce4526` | 120 | 68 | 213 |
| `fix/pro-recognition-v105` | `26dd4c666c5e` | 118 | 66 | 211 |
| `fix/samsung-arcore-sensor-keepalive` | `531020aede0f` | 115 | 64 | 208 |
| `fix/v1.1.1-demo-reliability` | `4539af4438ff` | 130 | 77 | 223 |
| `fix/v103-tracking-localization` | `1cc210ea3fd1` | 116 | 64 | 209 |
| `fix/v105-capture-geometry` | `821bc8b0f90c` | 118 | 66 | 211 |
| `fresh/no-map-runtime-poc` | `a5970e9be7fe` | 70 | 46 | 37 |
| `golden/4ee42ea-tracking` | `4ee42ea77f2e` | 66 | 43 | 45 |
| `live-bird-arcore-fix` | `754d633a9bad` | 113 | 62 | 206 |
| `main` | `f65326a096ee` | 142 | 88 | 235 |
| `outdoor/gnss-global` | `a5970e9be7fe` | 70 | 46 | 37 |
| `showme/local-assist` | `67b9aa9e2c0d` | 108 | 63 | 74 |
| `showme/remote-assistance` | `4763e0fa2845` | 103 | 68 | 0 |
| `stabilization-chatgpt` | `fe2d320faf9a` | 108 | 59 | 201 |
| `ux-auth-qr-flow` | `b782301ea759` | 111 | 60 | 204 |

## Tracking-related source versions

- `android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CameraBackgroundRenderer.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt`: 8 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/ar/PoseMath.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/ar/PoseSkeletonBuilder.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/ar/RemoteTrackStore.kt`: 6 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/vision/DepthSnapshot.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/vision/DetectionTracker.kt`: 12 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialarcoop/vision/TemporalDetectionTracker.kt`: 5 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/AlignmentCoordinator.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/AlignmentDiagnostics.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/AlignmentEngine.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/AlignmentSessionRecorder.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/ArRenderer.kt`: 3 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/ArRendererFactory.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/BlePeerRanger.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/CameraBackgroundRenderer.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/EssentialSharedPoseSolver.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/FrameCapture.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/FusionMath.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/MotionTrackFilter.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/RecordedAlignmentReplay.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/SharedVisualAnchorSolver.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/SpatialSensorFusion.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/SurfaceEdgeSnapRefiner.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/SurfaceTargetResolver.kt`: 2 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/TrackingStabilityGate.kt`: 1 distinct versions
- `android/app/src/main/java/com/sirpaul/spatialnomap/VehicleTrackPolicy.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/ar/ParticipantPoseTransformTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/ar/RemoteTrackStoreTest.kt`: 5 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/vision/DetectionTrackerTest.kt`: 6 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/vision/HighFidelityTrackingTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/vision/PoseTrackingTest.kt`: 2 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialarcoop/vision/TemporalDetectionTrackerTest.kt`: 4 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/AlignmentEngineMathTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/FusionMathTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/MotionTrackFilterTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/PrecisionSurfaceMathTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/SharedVisualAnchorSolverTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/TrackingStabilityGateTest.kt`: 1 distinct versions
- `android/app/src/test/java/com/sirpaul/spatialnomap/VehicleTrackPolicyTest.kt`: 1 distinct versions
- `android/showme/src/main/java/com/sirpaul/showme/ShowMeGeometry.kt`: 1 distinct versions
- `android/showme/src/main/java/com/sirpaul/showme/ShowMeRenderer.kt`: 1 distinct versions
- `android/showme/src/main/java/com/sirpaul/showme/VideoDepthHistory.kt`: 1 distinct versions
- `android/showme/src/test/java/com/sirpaul/showme/ShowMeGeometryTest.kt`: 1 distinct versions
- `android/showme/src/test/java/com/sirpaul/showme/VideoDepthHistoryTest.kt`: 1 distinct versions
- `server/src/fusion.mjs`: 2 distinct versions
- `server/test/fusion-pose.test.mjs`: 1 distinct versions
- `server/test/fusion.test.mjs`: 1 distinct versions
- `showme/android/app/src/main/java/com/sirpaul/showme/ShowMeRenderer.kt`: 1 distinct versions
- `showme/android/app/src/main/java/com/sirpaul/showme/StreamCapture.kt`: 1 distinct versions
- `showme/android/app/src/main/java/com/sirpaul/showme/SurfaceGeometry.kt`: 1 distinct versions
- `showme/android/app/src/test/java/com/sirpaul/showme/ShowMeGeometryTest.kt`: 1 distinct versions
- `showme/web/geometry.mjs`: 1 distinct versions
- `showme/web/geometry.test.mjs`: 1 distinct versions
