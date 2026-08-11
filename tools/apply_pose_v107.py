from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)

ar_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
ar = ar_path.read_text()

ar = replace_once(
    ar,
    "import com.sirpaul.spatialarcoop.ar.PoseMath\n",
    "import com.sirpaul.spatialarcoop.ar.PoseMath\nimport com.sirpaul.spatialarcoop.ar.PoseSkeletonBuilder\n",
    "ArActivity PoseSkeletonBuilder import",
)
ar = replace_once(
    ar,
    "import com.sirpaul.spatialarcoop.ui.ProjectedCuboid\nimport com.sirpaul.spatialarcoop.ui.ProjectedTrack\n",
    "import com.sirpaul.spatialarcoop.ui.ProjectedCuboid\nimport com.sirpaul.spatialarcoop.ui.ProjectedJoint\nimport com.sirpaul.spatialarcoop.ui.ProjectedPose\nimport com.sirpaul.spatialarcoop.ui.ProjectedSkeleton\nimport com.sirpaul.spatialarcoop.ui.ProjectedTrack\n",
    "ArActivity projected pose imports",
)
ar = replace_once(
    ar,
    "    private var latestLocalTrackCount = 0\n    private var latestSpatializedCount = 0\n",
    "    private var latestLocalTrackCount = 0\n    private var latestSpatializedCount = 0\n    private var latestPoseCount = 0\n",
    "ArActivity pose counter",
)
ar = replace_once(
    ar,
    "            pendingDetection.get()?.let { pending ->\n                overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))\n            }\n",
    "            pendingDetection.get()?.let { pending ->\n                overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))\n                overlay.updateLocalPoses(projectDetectionPoses(frame, pending.detections))\n            }\n",
    "ArActivity pre-localization pose overlay",
)
ar = replace_once(
    ar,
    "                    SpatialObservation(\n                        label = detection.label,\n                        confidence = detection.confidence,\n                        position = estimate.sitePosition,\n                        observedAtMs = detection.capturedAtMs,\n                        uncertaintyMeters = estimate.uncertaintyMeters,\n                        associationKey = detection.temporalId,\n                        extentMeters = estimate.extentMeters,\n                        yawRadians = estimate.yawRadians,\n                        requiredHits = estimate.requiredHits\n                    )\n",
    "                    val poseJoints = detection.captureGeometry?.let { geometry ->\n                        PoseSkeletonBuilder.build(detection, estimate.sitePosition, geometry)\n                    }.orEmpty()\n                    SpatialObservation(\n                        label = detection.label,\n                        confidence = detection.confidence,\n                        position = estimate.sitePosition,\n                        observedAtMs = detection.capturedAtMs,\n                        uncertaintyMeters = estimate.uncertaintyMeters,\n                        associationKey = detection.temporalId,\n                        extentMeters = estimate.extentMeters,\n                        yawRadians = estimate.yawRadians,\n                        requiredHits = estimate.requiredHits,\n                        poseJoints = poseJoints\n                    )\n",
    "ArActivity spatial pose observation",
)
ar = replace_once(
    ar,
    "            overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))\n        }\n\n        val now = System.currentTimeMillis()\n",
    "            overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))\n            overlay.updateLocalPoses(projectDetectionPoses(frame, pending.detections))\n        }\n\n        val now = System.currentTimeMillis()\n",
    "ArActivity sensor local pose overlay",
)
ar = replace_once(
    ar,
    "                latestDetectionCount = values.size\n                latestInferenceMs = inferenceMs\n",
    "                latestDetectionCount = values.size\n                latestPoseCount = values.count { detection ->\n                    detection.label.equals(\"person\", true) && detection.poseLandmarks.size >= 8\n                }\n                latestInferenceMs = inferenceMs\n",
    "ArActivity pose result count",
)
ar = replace_once(
    ar,
    "        latestSpatializedCount = 0\n        latestSentTrackCount = 0\n        latestDetectionCount = 0\n",
    "        latestSpatializedCount = 0\n        latestSentTrackCount = 0\n        latestDetectionCount = 0\n        latestPoseCount = 0\n",
    "ArActivity pose reset",
)
ar = replace_once(
    ar,
    "        if (::overlay.isInitialized) overlay.updateLocalBoxes(emptyList())\n",
    "        if (::overlay.isInitialized) {\n            overlay.updateLocalBoxes(emptyList())\n            overlay.updateLocalPoses(emptyList())\n        }\n",
    "ArActivity clear pose overlay",
)
pose_projector = '''\n    private fun projectDetectionPoses(frame: Frame, detections: List<Detection2D>): List<ProjectedPose> {\n        return detections.mapNotNull { detection ->\n            if (!detection.label.equals("person", true) || detection.poseLandmarks.size < 6) return@mapNotNull null\n            val input = FloatArray(detection.poseLandmarks.size * 2)\n            detection.poseLandmarks.forEachIndexed { index, joint ->\n                input[index * 2] = joint.x\n                input[index * 2 + 1] = joint.y\n            }\n            val output = FloatArray(input.size)\n            runCatching {\n                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.VIEW, output)\n                ProjectedPose(\n                    detection.poseLandmarks.mapIndexed { index, joint ->\n                        ProjectedJoint(joint.index, output[index * 2], output[index * 2 + 1], joint.confidence)\n                    }\n                )\n            }.getOrNull()\n        }\n    }\n\n'''
ar = replace_once(
    ar,
    "    private fun handleRequests(frame: Frame, cameraSite: FloatArray, worldFromSite: FloatArray, map: MapDefinition) {\n",
    pose_projector + "    private fun handleRequests(frame: Frame, cameraSite: FloatArray, worldFromSite: FloatArray, map: MapDefinition) {\n",
    "ArActivity pose projector insertion",
)
old_map = '''            .map { track ->\n                val world = PoseMath.transformPoint(worldFromSite, track.position)\n                val cameraPoint = PoseMath.transformPoint(viewMatrix, world)\n                val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)\n                val direction = OffscreenIndicatorMath.direction(cameraPoint)\n                val onScreen = screen?.onScreen == true\n                ProjectedTrack(\n                    key = track.key,\n                    label = track.label,\n                    confidence = track.confidence,\n                    x = screen?.x ?: viewportWidth * 0.5f,\n                    y = screen?.y ?: viewportHeight * 0.5f,\n                    onScreen = onScreen,\n                    distanceMeters = PoseMath.distance(cameraSite, track.position),\n                    uncertaintyMeters = track.uncertaintyMeters,\n                    ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),\n                    sourceId = track.sourceId,\n                    cuboid = if (onScreen && track.sourceId != "marker") projectTrackCuboid(track, worldFromSite) else null,\n                    offscreenDx = direction.dx,\n                    offscreenDy = direction.dy\n                )\n            }\n'''
new_map = '''            .map { track ->\n                val world = PoseMath.transformPoint(worldFromSite, track.position)\n                val cameraPoint = PoseMath.transformPoint(viewMatrix, world)\n                val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)\n                val direction = OffscreenIndicatorMath.direction(cameraPoint)\n                val onScreen = screen?.onScreen == true\n                val skeleton = if (onScreen && track.label.equals("person", true) && track.poseJoints.isNotEmpty()) {\n                    projectTrackSkeleton(track, worldFromSite)\n                } else null\n                ProjectedTrack(\n                    key = track.key,\n                    label = track.label,\n                    confidence = track.confidence,\n                    x = screen?.x ?: viewportWidth * 0.5f,\n                    y = screen?.y ?: viewportHeight * 0.5f,\n                    onScreen = onScreen,\n                    distanceMeters = PoseMath.distance(cameraSite, track.position),\n                    uncertaintyMeters = track.uncertaintyMeters,\n                    ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),\n                    sourceId = track.sourceId,\n                    cuboid = if (onScreen && skeleton == null && track.sourceId != "marker") projectTrackCuboid(track, worldFromSite) else null,\n                    skeleton = skeleton,\n                    offscreenDx = direction.dx,\n                    offscreenDy = direction.dy\n                )\n            }\n'''
ar = replace_once(ar, old_map, new_map, "ArActivity shared skeleton mapping")
skeleton_projector = '''\n    private fun projectTrackSkeleton(track: SpatialTrack, worldFromSite: FloatArray): ProjectedSkeleton? {\n        if (!track.label.equals("person", true) || track.poseJoints.size < 6) return null\n        val joints = track.poseJoints.mapNotNull { joint ->\n            if (joint.offsetMeters.size < 3) return@mapNotNull null\n            val site = floatArrayOf(\n                track.position[0] + joint.offsetMeters[0],\n                track.position[1] + joint.offsetMeters[1],\n                track.position[2] + joint.offsetMeters[2]\n            )\n            val world = PoseMath.transformPoint(worldFromSite, site)\n            val projected = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)\n                ?: return@mapNotNull null\n            ProjectedJoint(joint.index, projected.x, projected.y, joint.confidence)\n        }\n        return joints.takeIf { it.size >= 6 }?.let(::ProjectedSkeleton)\n    }\n\n'''
ar = replace_once(
    ar,
    "    private fun projectTrackCuboid(track: SpatialTrack, worldFromSite: FloatArray): ProjectedCuboid? {\n",
    skeleton_projector + "    private fun projectTrackCuboid(track: SpatialTrack, worldFromSite: FloatArray): ProjectedCuboid? {\n",
    "ArActivity shared skeleton projector",
)
ar = replace_once(
    ar,
    '                        "Detector active · $latestDetectionCount visible · $bufferedRemoteTracks remote buffered · $room · $resolver"\n',
    '                        "Detector active · $latestDetectionCount visible · $latestPoseCount pose · $bufferedRemoteTracks remote buffered · $room · $resolver"\n',
    "ArActivity localizing pose HUD",
)
ar = replace_once(
    ar,
    '                        "$latestDetectionCount detected · $latestSpatializedCount spatialized · $latestLocalTrackCount active · $ack · $bufferedRemoteTracks remote · ${latestInferenceMs} ms"\n',
    '                        "$latestDetectionCount detected · $latestPoseCount pose · $latestSpatializedCount spatialized · $latestLocalTrackCount active · $ack · $bufferedRemoteTracks remote · ${latestInferenceMs} ms"\n',
    "ArActivity live pose HUD",
)
ar_path.write_text(ar)

object_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/vision/ObjectDetectorEngine.kt")
obj = object_path.read_text()
old_detection = '''                    val detections = confirmed.map { detection ->\n                        val rawBox = RectF(detection.left, detection.top, detection.right, detection.bottom)\n                        Detection2D(\n                            label = detection.label,\n                            confidence = detection.confidence,\n                            rawBoundingBox = rawBox,\n                            rawBottomCenter = floatArrayOf(\n                                rawBox.centerX(),\n                                rawBox.bottom - rawBox.height() * BOTTOM_CENTER_INSET\n                            ),\n                            capturedAtMs = capturedAtMs,\n                            rawImageWidth = frame.width,\n                            rawImageHeight = frame.height,\n                            temporalId = detection.temporalId,\n                            temporallyConfirmed = true,\n                            captureGeometry = captureGeometry,\n                            poseLandmarks = poseByTemporalId[detection.temporalId].orEmpty()\n                        )\n                    }\n'''
new_detection = '''                    val detections = confirmed.map { detection ->\n                        val rawBox = RectF(detection.left, detection.top, detection.right, detection.bottom)\n                        val pose = poseByTemporalId[detection.temporalId].orEmpty()\n                        val contact = if (detection.label == "person") poseGroundContact(pose) else null\n                        Detection2D(\n                            label = detection.label,\n                            confidence = detection.confidence,\n                            rawBoundingBox = rawBox,\n                            rawBottomCenter = contact ?: floatArrayOf(\n                                rawBox.centerX(),\n                                rawBox.bottom - rawBox.height() * BOTTOM_CENTER_INSET\n                            ),\n                            capturedAtMs = capturedAtMs,\n                            rawImageWidth = frame.width,\n                            rawImageHeight = frame.height,\n                            temporalId = detection.temporalId,\n                            temporallyConfirmed = true,\n                            captureGeometry = captureGeometry,\n                            poseLandmarks = pose\n                        )\n                    }\n'''
obj = replace_once(obj, old_detection, new_detection, "ObjectDetector pose ground contact")
contact_method = '''\n    private fun poseGroundContact(pose: List<PoseLandmark2D>): FloatArray? {\n        val feet = pose.filter { it.index in POSE_GROUND_INDICES && it.confidence >= POSE_GROUND_CONFIDENCE }\n        if (feet.size < 2) return null\n        val sortedX = feet.map { it.x }.sorted()\n        val sortedY = feet.map { it.y }.sorted()\n        return floatArrayOf(sortedX[sortedX.size / 2], sortedY[sortedY.size / 2])\n    }\n\n'''
obj = replace_once(
    obj,
    "    private fun suppressOverlaps(values: List<DetectionCandidate2D>): List<DetectionCandidate2D> {\n",
    contact_method + "    private fun suppressOverlaps(values: List<DetectionCandidate2D>): List<DetectionCandidate2D> {\n",
    "ObjectDetector contact method insertion",
)
obj = replace_once(
    obj,
    "        private val SHARED_POSE_INDICES = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 31, 32)\n",
    "        private val SHARED_POSE_INDICES = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 31, 32)\n        private val POSE_GROUND_INDICES = setOf(27, 28, 31, 32)\n",
    "ObjectDetector ground indices",
)
obj = replace_once(
    obj,
    "        private const val POSE_BOX_CONFIDENCE = 0.28f\n",
    "        private const val POSE_BOX_CONFIDENCE = 0.28f\n        private const val POSE_GROUND_CONFIDENCE = 0.42f\n",
    "ObjectDetector ground confidence",
)
object_path.write_text(obj)

print("pose v1.0.7 integration patch applied")
