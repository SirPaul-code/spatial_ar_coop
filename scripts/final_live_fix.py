from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str) -> str:
    updated, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, found {count}")
    return updated


# -----------------------------------------------------------------------------
# ArActivity: Live AR is automatic detection/sharing; detector runs while
# localizing so local boxes prove inference independently of shared localization.
# -----------------------------------------------------------------------------
ar_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
ar = ar_path.read_text()

ar = replace_once(
    ar,
    "    @Volatile private var reporting = false\n",
    "    @Volatile private var reporting = false\n    @Volatile private var latestDetectionCount = 0\n",
    "ArActivity latestDetectionCount",
)

ar = replace_once(
    ar,
    '''        if (mode == ArMode.SENSOR) {\n            reporting = true\n            ensureDetector()\n        }\n''',
    '''        if (mode == ArMode.SENSOR || mode == ArMode.LIVE) {\n            // Live AR is cooperative by default: every participant detects and shares what its\n            // camera sees. There is no hidden \"reporting\" opt-in that can silently disable the\n            // core feature. Spatial publishing still waits for successful shared localization.\n            reporting = true\n            ensureDetector()\n            if (mode == ArMode.LIVE) {\n                spatialApp.logger.info("Live automatic detection enabled", mapOf("mapId" to mapId))\n            }\n        }\n''',
    "ArActivity automatic reporting",
)

ar = replace_once(
    ar,
    '''            ArMode.LIVE -> {\n                reportButton = action("Start reporting") { setReporting(!reporting) }.also(actions::addView)\n                actions.addView(action("More") { showLiveMenu() })\n            }\n''',
    '''            ArMode.LIVE -> {\n                // Detection/sharing is automatic in Live AR. Keep the bottom bar focused on\n                // navigation/recovery instead of exposing an implementation-mode toggle.\n                actions.addView(action("More") { showLiveMenu() })\n            }\n''',
    "ArActivity remove reporting toggle",
)

ar = replace_once(
    ar,
    '''        if (camera.trackingState != TrackingState.TRACKING) {\n            updateHud(frame, null, "Tracking ${camera.trackingState}: ${camera.trackingFailureReason}")\n            return\n        }\n\n        var map = currentMap() ?: return\n''',
    '''        if (camera.trackingState != TrackingState.TRACKING) {\n            updateHud(frame, null, "Tracking ${camera.trackingState}: ${camera.trackingFailureReason}")\n            return\n        }\n\n        // 2D inference is local and does not require the shared worldFromSite transform. Run it\n        // while Cloud Anchors are still resolving so the user immediately sees detector boxes and\n        // we can distinguish \"detector works\" from \"shared localization is not ready yet\".\n        if ((mode == ArMode.LIVE || mode == ArMode.SENSOR) && reporting) {\n            captureDetectorFrame(frame)\n            pendingDetection.get()?.let { pending ->\n                overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))\n            }\n        }\n\n        var map = currentMap() ?: return\n''',
    "ArActivity inference before localization",
)

ar = regex_once(
    ar,
    r'''\n        if \(now - lastDetectionCaptureAtMs >= DETECTION_INTERVAL_MS\) \{.*?\n        \}\n    \}\n\n    private fun ensureDetector\(\)''',
    '''\n    }\n\n    private fun captureDetectorFrame(frame: Frame) {\n        val now = System.currentTimeMillis()\n        if (now - lastDetectionCaptureAtMs < DETECTION_INTERVAL_MS) return\n        val image = try {\n            frame.acquireCameraImage()\n        } catch (_: NotYetAvailableException) {\n            null\n        } ?: return\n        try {\n            val yuv = YuvFrame.copyOf(image)\n            val cameraId = session?.cameraConfig?.cameraId\n            val rotation = if (cameraId == null) {\n                0\n            } else {\n                runCatching { displayRotation.cameraSensorToDisplayRotation(cameraId) }.getOrDefault(0)\n            }\n            if (detector?.submit(yuv, rotation, now) == true) lastDetectionCaptureAtMs = now\n        } finally {\n            image.close()\n        }\n    }\n\n    private fun ensureDetector()''',
    "ArActivity detector capture extraction",
)

ar = replace_once(
    ar,
    '''            onResult = { values, inferenceMs -> pendingDetection.set(PendingDetection(values, inferenceMs)) },\n''',
    '''            onResult = { values, inferenceMs ->\n                latestDetectionCount = values.size\n                latestInferenceMs = inferenceMs\n                pendingDetection.set(PendingDetection(values, inferenceMs))\n            },\n''',
    "ArActivity detector result metrics",
)

ar = replace_once(
    ar,
    '''        latestLocalTrackCount = 0\n        latestInferenceMs = 0\n''',
    '''        latestLocalTrackCount = 0\n        latestDetectionCount = 0\n        latestInferenceMs = 0\n''',
    "ArActivity detector reset metrics",
)

ar = replace_once(
    ar,
    '''                    ArMode.LIVE -> if (worldFromSite == null) {\n                        "Move slowly while the app resolves a saved Cloud Anchor"\n                    } else if (reporting) {\n                        "$latestLocalTrackCount local tracks · reporting to this place"\n                    } else {\n                        "Observing shared tracks · tap Start reporting to contribute detections"\n                    }\n''',
    '''                    ArMode.LIVE -> if (worldFromSite == null) {\n                        "Detector active · $latestDetectionCount visible object(s) · resolving shared location"\n                    } else {\n                        "$latestDetectionCount detected · $latestLocalTrackCount spatial track(s) · sharing automatically"\n                    }\n''',
    "ArActivity live HUD",
)

ar = replace_once(
    ar,
    '''                connected && mode == ArMode.MAP -> "Server connected · map sync is automatic"\n                connected -> "Server connected · live sharing active"\n''',
    '''                connected && mode == ArMode.MAP -> "Server connected · map sync is automatic"\n                connected && mode == ArMode.LIVE -> "Server connected · automatic object sharing active"\n                connected -> "Server connected · live sharing active"\n''',
    "ArActivity live network text",
)

ar = replace_once(
    ar,
    '''            if (connected && mode == ArMode.LIVE) realtime?.sendStatus(if (reporting) "reporting" else "observing")\n''',
    '''            if (connected && mode == ArMode.LIVE) realtime?.sendStatus("detecting", "automatic object detection and sharing enabled")\n''',
    "ArActivity live status",
)

ar = replace_once(ar, "        private const val RESOLVE_RETRY_MS = 15_000L\n", "        private const val RESOLVE_RETRY_MS = 5_000L\n", "ArActivity resolve retry")

ar_path.write_text(ar)


# -----------------------------------------------------------------------------
# CloudAnchorCoordinator: don't block an invited phone indefinitely on one root
# anchor. Resolve a bounded set concurrently; first success wins; timeout/retry.
# -----------------------------------------------------------------------------
ca_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt")
ca = ca_path.read_text()
ca = replace_once(
    ca,
    "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicReference\n",
    "import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicReference\n",
    "CloudAnchorCoordinator AtomicInteger import",
)
ca = replace_once(
    ca,
    '''    private val resolving = AtomicBoolean(false)\n    private val hostFutures = CopyOnWriteArrayList<HostCloudAnchorFuture>()\n''',
    '''    private val resolving = AtomicBoolean(false)\n    private val resolveGeneration = AtomicInteger(0)\n    @Volatile private var resolveStartedAtMs = 0L\n    private val hostFutures = CopyOnWriteArrayList<HostCloudAnchorFuture>()\n''',
    "CloudAnchorCoordinator resolve state",
)

ca = regex_once(
    ca,
    r'''    fun resolveMap\(map: MapDefinition\) \{.*?\n    \}\n\n    private fun resolveNext\(candidates: List<AnchorDefinition>, index: Int\) \{.*?\n    \}\n\n    fun featureQuality''',
    '''    fun resolveMap(map: MapDefinition) {\n        if (!cloudConfigured) {\n            onState("Cloud Anchors are not configured in this APK")\n            return\n        }\n        if (hasReference) return\n\n        val now = System.currentTimeMillis()\n        if (resolving.get()) {\n            if (now - resolveStartedAtMs <= RESOLVE_BATCH_TIMEOUT_MS) return\n            logger.warn(\n                "Cloud Anchor resolve batch timed out",\n                mapOf("mapId" to mapId, "elapsedMs" to (now - resolveStartedAtMs), "pending" to resolveFutures.size)\n            )\n            cancelResolveBatch()\n            onState("Saved-anchor lookup timed out · retrying automatically")\n        }\n        if (!resolving.compareAndSet(false, true)) return\n\n        val candidates = map.anchors\n            .filter { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }\n            .sortedWith(\n                compareByDescending<AnchorDefinition> { it.id == map.rootAnchorId }\n                    .thenByDescending { it.updatedAtMs }\n            )\n            .take(MAX_CONCURRENT_RESOLVES)\n        if (candidates.isEmpty()) {\n            resolving.set(false)\n            onState("Map has no hosted anchors yet")\n            return\n        }\n\n        resolveStartedAtMs = now\n        val generation = resolveGeneration.incrementAndGet()\n        val remaining = AtomicInteger(candidates.size)\n        onState("Trying ${candidates.size} saved Cloud Anchors · move slowly and look around")\n        logger.info(\n            "Cloud Anchor resolve batch started",\n            mapOf("mapId" to mapId, "anchors" to candidates.size, "rootAnchorId" to map.rootAnchorId)\n        )\n\n        candidates.forEachIndexed { index, definition ->\n            logger.info(\n                "Resolving Cloud Anchor",\n                mapOf("mapId" to mapId, "anchorId" to definition.id, "candidate" to (index + 1), "total" to candidates.size)\n            )\n            var pending: ResolveCloudAnchorFuture? = null\n            val future = session.resolveCloudAnchorAsync(definition.cloudAnchorId) { anchor, state ->\n                pending?.let(resolveFutures::remove)\n                if (generation != resolveGeneration.get() || reference.get() != null) {\n                    anchor?.detach()\n                    return@resolveCloudAnchorAsync\n                }\n\n                if (state == CloudAnchorState.SUCCESS && anchor != null) {\n                    if (reference.compareAndSet(null, Reference(anchor, definition))) {\n                        ownedAnchors += anchor\n                        resolving.set(false)\n                        resolveStartedAtMs = 0L\n                        // Invalidate/cancel every other candidate from this batch. First valid\n                        // shared reference wins; all remaining callbacks become stale by generation.\n                        resolveGeneration.incrementAndGet()\n                        val others = resolveFutures.toList()\n                        resolveFutures.clear()\n                        others.filter { it !== pending }.forEach { runCatching { it.cancel() } }\n                        logger.info(\n                            "Cloud Anchor resolved",\n                            mapOf("mapId" to mapId, "anchorId" to definition.id, "candidate" to (index + 1), "total" to candidates.size)\n                        )\n                        onState("Localized · matched saved anchor ${index + 1}/${candidates.size}")\n                    } else {\n                        anchor.detach()\n                    }\n                    return@resolveCloudAnchorAsync\n                }\n\n                anchor?.detach()\n                val left = remaining.decrementAndGet()\n                logger.warn(\n                    "Cloud Anchor resolve failed",\n                    mapOf(\n                        "mapId" to mapId,\n                        "anchorId" to definition.id,\n                        "candidate" to (index + 1),\n                        "total" to candidates.size,\n                        "state" to state.name,\n                        "remaining" to left\n                    )\n                )\n                if (left <= 0 && generation == resolveGeneration.get() && reference.get() == null) {\n                    resolving.set(false)\n                    resolveStartedAtMs = 0L\n                    resolveFutures.clear()\n                    onState("No saved anchor matched yet · retrying automatically")\n                }\n            }\n            pending = future\n            resolveFutures += future\n        }\n    }\n\n    private fun cancelResolveBatch() {\n        resolveGeneration.incrementAndGet()\n        resolveFutures.forEach { runCatching { it.cancel() } }\n        resolveFutures.clear()\n        resolving.set(false)\n        resolveStartedAtMs = 0L\n    }\n\n    fun featureQuality''',
    "CloudAnchorCoordinator concurrent resolver",
)

ca = replace_once(
    ca,
    '''    fun resetReference() {\n        resolveFutures.forEach { runCatching { it.cancel() } }\n        resolveFutures.clear()\n        resolving.set(false)\n''',
    '''    fun resetReference() {\n        cancelResolveBatch()\n''',
    "CloudAnchorCoordinator resetReference",
)
ca = replace_once(
    ca,
    '''    fun close() {\n        hostFutures.forEach { runCatching { it.cancel() } }\n        resolveFutures.forEach { runCatching { it.cancel() } }\n        ownedAnchors.forEach { runCatching { it.detach() } }\n        hostFutures.clear()\n        resolveFutures.clear()\n''',
    '''    fun close() {\n        hostFutures.forEach { runCatching { it.cancel() } }\n        cancelResolveBatch()\n        ownedAnchors.forEach { runCatching { it.detach() } }\n        hostFutures.clear()\n''',
    "CloudAnchorCoordinator close",
)
ca = replace_once(
    ca,
    '''        private const val AUTO_HOST_COOLDOWN_MS = 8_000L\n        private const val RETRY_RADIUS_METERS = 4f\n''',
    '''        private const val AUTO_HOST_COOLDOWN_MS = 8_000L\n        private const val RETRY_RADIUS_METERS = 4f\n        private const val MAX_CONCURRENT_RESOLVES = 4\n        private const val RESOLVE_BATCH_TIMEOUT_MS = 12_000L\n''',
    "CloudAnchorCoordinator resolver constants",
)
ca_path.write_text(ca)


# -----------------------------------------------------------------------------
# SpatialEstimator: preserve precise depth/plane/ground paths, then fall back to
# bbox-size monocular range with explicit large uncertainty instead of dropping.
# -----------------------------------------------------------------------------
se_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/SpatialEstimator.kt")
se = se_path.read_text()
se = replace_once(
    se,
    '''        val fallback = groundY?.let { intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it) }\n            ?: return null\n        return EstimatedPosition(fallback, 0.65f, "ground-ray")\n''',
    '''        val fallback = groundY?.let { intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it) }\n        if (fallback != null) return EstimatedPosition(fallback, 0.65f, "ground-ray")\n\n        // Last-resort monocular estimate. It is intentionally marked with much larger uncertainty\n        // than Depth/plane/ground hits, but keeps obvious people/cars/birds shareable when ARCore\n        // has no hit at the moving object's contact point.\n        return estimateFromBoundingBoxSize(frame, detection, siteFromWorld)\n''',
    "SpatialEstimator monocular fallback call",
)
se = replace_once(
    se,
    '''    private fun intersectGround(\n''',
    '''    private fun estimateFromBoundingBoxSize(\n        frame: Frame,\n        detection: Detection2D,\n        siteFromWorld: FloatArray\n    ): EstimatedPosition? {\n        val physicalHeightMeters = when (detection.label.lowercase()) {\n            "person" -> 1.70f\n            "car" -> 1.50f\n            "bird" -> 0.40f\n            "dog" -> 0.65f\n            "cat" -> 0.38f\n            else -> 0.60f\n        }\n        val pixelExtent = maxOf(detection.rawBoundingBox.width(), detection.rawBoundingBox.height())\n        if (!pixelExtent.isFinite() || pixelExtent < 6f) return null\n\n        val intrinsics = frame.camera.imageIntrinsics\n        val focal = intrinsics.focalLength\n        val principal = intrinsics.principalPoint\n        if (focal[0] <= 0f || focal[1] <= 0f) return null\n        val focalPixels = (focal[0] + focal[1]) * 0.5f\n        val opticalDepth = (focalPixels * physicalHeightMeters / pixelExtent).coerceIn(0.45f, 55f)\n        if (!opticalDepth.isFinite()) return null\n\n        val centerX = detection.rawBoundingBox.centerX()\n        val centerY = detection.rawBoundingBox.centerY()\n        val cameraDirection = PoseMath.normalize(\n            floatArrayOf(\n                (centerX - principal[0]) / focal[0],\n                -(centerY - principal[1]) / focal[1],\n                -1f\n            )\n        )\n        val worldFromCamera = PoseMath.poseToMatrix(frame.camera.pose)\n        val worldDirection = PoseMath.transformDirection(worldFromCamera, cameraDirection)\n        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))\n        val siteOrigin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)\n        val rayLength = (opticalDepth / abs(cameraDirection[2]).coerceAtLeast(0.18f)).coerceIn(0.45f, 70f)\n        val site = floatArrayOf(\n            siteOrigin[0] + siteDirection[0] * rayLength,\n            siteOrigin[1] + siteDirection[1] * rayLength,\n            siteOrigin[2] + siteDirection[2] * rayLength\n        )\n        val uncertainty = (0.75f + opticalDepth * 0.28f).coerceIn(0.9f, 10f)\n        return EstimatedPosition(site, uncertainty, "monocular-class-size")\n    }\n\n    private fun intersectGround(\n''',
    "SpatialEstimator monocular fallback implementation",
)
se_path.write_text(se)


# -----------------------------------------------------------------------------
# Detector recall: keep per-class filtering but make visible people/cars/birds
# much less likely to be silently rejected by the old 0.48 preference default.
# -----------------------------------------------------------------------------
od_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/vision/ObjectDetectorEngine.kt")
od = od_path.read_text()
od = replace_once(
    od,
    '''                    val effectiveThreshold = if (label == "bird") minOf(threshold, BIRD_SCORE_THRESHOLD) else threshold\n''',
    '''                    val effectiveThreshold = minOf(threshold, classScoreThreshold(label))\n''',
    "ObjectDetectorEngine per-class threshold",
)
od = replace_once(
    od,
    '''        val modelThreshold = minOf(threshold, BIRD_SCORE_THRESHOLD)\n''',
    '''        val modelThreshold = minOf(threshold, MIN_MODEL_SCORE_THRESHOLD)\n''',
    "ObjectDetectorEngine model threshold",
)
od = replace_once(
    od,
    '''                    "birdThreshold" to minOf(threshold, BIRD_SCORE_THRESHOLD),\n                    "maxResults" to MAX_RESULTS,\n''',
    '''                    "birdThreshold" to minOf(threshold, BIRD_SCORE_THRESHOLD),\n                    "generalThreshold" to minOf(threshold, GENERAL_SCORE_THRESHOLD),\n                    "maxResults" to MAX_RESULTS,\n''',
    "ObjectDetectorEngine log thresholds",
)
od = replace_once(
    od,
    '''    companion object {\n        private val ALLOWED_LABELS = setOf("person", "car", "bird", "dog", "cat")\n        private const val BIRD_SCORE_THRESHOLD = 0.30f\n        private const val MAX_RESULTS = 32\n    }\n''',
    '''    private fun classScoreThreshold(label: String): Float = when (label) {\n        "bird" -> BIRD_SCORE_THRESHOLD\n        else -> GENERAL_SCORE_THRESHOLD\n    }\n\n    companion object {\n        private val ALLOWED_LABELS = setOf("person", "car", "bird", "dog", "cat")\n        private const val MIN_MODEL_SCORE_THRESHOLD = 0.25f\n        private const val BIRD_SCORE_THRESHOLD = 0.25f\n        private const val GENERAL_SCORE_THRESHOLD = 0.35f\n        private const val MAX_RESULTS = 48\n    }\n''',
    "ObjectDetectorEngine constants",
)
od_path.write_text(od)


# Version bump so physical testers can positively identify the fixed APK.
bg_path = Path("android/app/build.gradle.kts")
bg = bg_path.read_text()
bg = replace_once(bg, '        versionCode = 2\n        versionName = "1.0.1"\n', '        versionCode = 3\n        versionName = "1.0.2"\n', "Android version bump")
bg_path.write_text(bg)


# Public docs must match the actual no-toggle Live behavior.
readme_path = Path("README.md")
readme = readme_path.read_text()
readme = readme.replace(
    "- **Live AR**: all participants observe remote tracks; each phone can independently enable/disable object reporting.",
    "- **Live AR**: every localized participant automatically runs on-device detection, publishes compact tracks and observes everyone else's shared tracks."
)
readme = readme.replace(
    "7. The phone receives shared tracks once localized/connected. Tap **Start reporting** only if this phone should also run local object detection and publish tracks.",
    "7. Object detection starts automatically. Local detector boxes appear even while shared localization is still resolving; once localized, compact 3D tracks are published automatically to the place."
)
readme_path.write_text(readme)

print("final live integration patch applied")
