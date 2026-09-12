#!/usr/bin/env python3
"""Idempotently wire StableAR into the legacy Spatial AR ArActivity.

Kept as a durable, reviewable integration recipe because the SDK and product remain separate trees.
"""

from pathlib import Path

PATH = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one integration anchor, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.view.Gravity\n",
    "import android.view.Gravity\nimport android.view.MotionEvent\n",
)
replace_once(
    "import com.sirpaul.spatialarcoop.net.UploadScheduler\n",
    "import com.sirpaul.spatialarcoop.net.UploadScheduler\n"
    "import com.sirpaul.spatialarcoop.stablear.StableArCoordinator\n"
    "import com.sirpaul.spatialarcoop.stablear.StableArRefinedMarker\n"
    "import com.sirpaul.spatialarcoop.stablear.StableArTapPlacement\n",
)
replace_once(
    "import java.util.concurrent.ConcurrentHashMap\n",
    "import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.ConcurrentLinkedQueue\n",
)
replace_once(
    "    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)\n",
    "    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)\n"
    "    private data class PendingStableMarker(val id: String, val label: String, val position: FloatArray, val expiresAtMs: Long)\n",
)
replace_once(
    "    private var cloudAnchors: CloudAnchorCoordinator? = null\n"
    "    private var pointRecorder: PointCloudRecorder? = null\n",
    "    private var cloudAnchors: CloudAnchorCoordinator? = null\n"
    "    private var pointRecorder: PointCloudRecorder? = null\n"
    "    @Volatile private var stableAr: StableArCoordinator? = null\n"
    "    private val pendingStableMarkers = ConcurrentLinkedQueue<PendingStableMarker>()\n"
    "    private val stableArBroadcastAt = linkedMapOf<String, Long>()\n"
    "    private val stableArTapArmed = AtomicBoolean(false)\n"
    "    private val pendingStableArTap = AtomicReference<FloatArray?>(null)\n"
    "    private val stableArBenchmarkEnabled by lazy { intent.getBooleanExtra(EXTRA_STABLEAR_BENCHMARK, false) }\n"
    "    private val stableArBenchmarkPhase by lazy { intent.getStringExtra(EXTRA_STABLEAR_BENCHMARK_PHASE) ?: \"field\" }\n",
)
replace_once(
    "        overlay = SpatialOverlayView(this)\n"
    "        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))\n",
    "        overlay = SpatialOverlayView(this)\n"
    "        overlay.setOnTouchListener { _, event ->\n"
    "            if (!stableArTapArmed.get()) return@setOnTouchListener false\n"
    "            when (event.actionMasked) {\n"
    "                MotionEvent.ACTION_DOWN -> true\n"
    "                MotionEvent.ACTION_UP -> {\n"
    "                    pendingStableArTap.set(floatArrayOf(event.x, event.y))\n"
    "                    stableArTapArmed.set(false)\n"
    "                    requestMarker.set(true)\n"
    "                    showDetail(\"StableAR target selected · resolving metric surface…\")\n"
    "                    true\n"
    "                }\n"
    "                MotionEvent.ACTION_CANCEL -> { stableArTapArmed.set(false); true }\n"
    "                else -> true\n"
    "            }\n"
    "        }\n"
    "        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))\n",
)
replace_once(
    "        if (!closing.get()) {\n"
    "            arState.beginPause()\n"
    "            pauseGlIfNeeded()\n",
    "        if (!closing.get()) {\n"
    "            arState.beginPause()\n"
    "            releaseStableArOnGlThread()\n"
    "            pauseGlIfNeeded()\n",
)
replace_once(
    "        spatialApp.logger.info(\"AR teardown begin\", mapOf(\"mapId\" to mapId, \"mode\" to mode.name))\n"
    "        pauseGlIfNeeded()\n",
    "        spatialApp.logger.info(\"AR teardown begin\", mapOf(\"mapId\" to mapId, \"mode\" to mode.name))\n"
    "        releaseStableArOnGlThread()\n"
    "        pauseGlIfNeeded()\n",
)
replace_once(
    "        runOnUiThread {\n"
    "            pauseGlIfNeeded()\n"
    "            failedCoordinator?.close()\n",
    "        runOnUiThread {\n"
    "            releaseStableArOnGlThread()\n"
    "            pauseGlIfNeeded()\n"
    "            failedCoordinator?.close()\n",
)
replace_once(
    "        if (camera.trackingState != TrackingState.TRACKING) {\n"
    "            updateHud(frame, null, \"Tracking ${camera.trackingState}: ${camera.trackingFailureReason}\")\n"
    "            return\n"
    "        }\n\n"
    "        if ((mode == ArMode.LIVE || mode == ArMode.SENSOR) && reporting) {\n",
    "        if (camera.trackingState != TrackingState.TRACKING) {\n"
    "            stableAr?.let { runCatching { it.onFrame(frame, PoseMath.identity()) } }\n"
    "            updateHud(frame, null, \"Tracking ${camera.trackingState}: ${camera.trackingFailureReason}\")\n"
    "            return\n"
    "        }\n"
    "        val stableCoordinator = ensureStableAr(session ?: return)\n\n"
    "        if ((mode == ArMode.LIVE || mode == ArMode.SENSOR) && reporting) {\n",
)
# The tracking-lost call above deliberately gets replaced below with a direct coordinator helper;
# keep this anchor separate so the final source never feeds a fake SITE transform.
text = text.replace(
    "            stableAr?.let { runCatching { it.onFrame(frame, PoseMath.identity()) } }\n",
    "            stableAr?.trackingLost()\n",
)
replace_once(
    "        map = currentMap() ?: map\n\n"
    "        handleRequests(frame, cameraSite, worldFromSite, map)\n",
    "        map = currentMap() ?: map\n\n"
    "        stableCoordinator.onFrame(frame, worldFromSite)\n"
    "        handleRequests(frame, cameraSite, worldFromSite, map)\n",
)
replace_once(
    "                    4 -> {\n"
    "                        showDetail(\"Placing a temporary shared test marker…\")\n"
    "                        requestMarker.set(true)\n"
    "                    }\n",
    "                    4 -> armStableArMarkerPlacement()\n",
)
replace_once(
    "                    selected.startsWith(\"Place\") -> requestMarker.set(true)\n",
    "                    selected.startsWith(\"Place\") -> armStableArMarkerPlacement()\n",
)
replace_once(
    "    private fun showMapSetupMenu() {\n",
    "    private fun armStableArMarkerPlacement() {\n"
    "        pendingStableArTap.set(null)\n"
    "        stableArTapArmed.set(true)\n"
    "        showDetail(\"Tap the exact static material point to share with StableAR\")\n"
    "    }\n\n"
    "    private fun showMapSetupMenu() {\n",
)
replace_once(
    "        if (requestMarker.getAndSet(false)) {\n"
    "            val point = SpatialEstimator.centerGroundPoint(frame, worldFromSite, map.groundY)\n"
    "                ?: floatArrayOf(cameraSite[0], cameraSite[1], cameraSite[2] - 3f)\n"
    "            val id = \"m-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}\"\n"
    "            realtime?.sendManualMarker(id, \"marker\", point)\n"
    "            remoteTracks.addMarker(id, \"marker\", point, System.currentTimeMillis() + 60_000L)\n"
    "            showDetail(\"Shared test marker placed for 60 seconds\")\n"
    "        }\n",
    "        if (requestMarker.getAndSet(false)) {\n"
    "            val tap = pendingStableArTap.getAndSet(null)\n"
    "            val point = tap?.let { StableArTapPlacement.resolveSitePoint(frame, worldFromSite, it[0], it[1]) }\n"
    "            val now = System.currentTimeMillis()\n"
    "            val expiresAt = now + 60_000L\n"
    "            val id = \"m-$now-${UUID.randomUUID().toString().take(4)}\"\n"
    "            val attachment = point?.let { stableAr?.placeFromSitePoint(frame, worldFromSite, it, id, expiresAt) }\n"
    "            if (point == null) {\n"
    "                showDetail(\"No ARCore depth/plane/point at that tap · move sideways and tap the material again\")\n"
    "            } else if (attachment == null) {\n"
    "                // Keep the host product usable if CPU-image acquisition or StableAR creation is temporarily unavailable.\n"
    "                realtime?.sendManualMarker(id, \"marker\", point)\n"
    "                remoteTracks.addMarker(id, \"marker\", point, expiresAt)\n"
    "                showDetail(\"Shared marker placed with stock ARCore fallback · StableAR visual root was unavailable\")\n"
    "            } else {\n"
    "                val benchmark = stableAr?.status()?.benchmarkPath\n"
    "                showDetail(buildString {\n"
    "                    append(\"StableAR shared marker active for 60 seconds · move around it to verify material lock\")\n"
    "                    if (stableArBenchmarkEnabled && benchmark != null) append(\" · benchmark recording: $benchmark\")\n"
    "                })\n"
    "            }\n"
    "        }\n",
)
replace_once(
    "        val bufferedRemoteTracks = remoteTracks.snapshot(now).count { track ->\n"
    "            track.sourceId != spatialApp.preferences.deviceId && track.sourceId != \"marker\"\n"
    "        }\n",
    "        val bufferedRemoteTracks = remoteTracks.snapshot(now).count { track ->\n"
    "            track.sourceId != spatialApp.preferences.deviceId && track.sourceId != \"marker\"\n"
    "        }\n"
    "        val stableStatus = stableAr?.status()\n"
    "        val stableText = stableStatus?.let { status ->\n"
    "            val backend = if (status.learnedBackendActive) \"xfeat\" else status.lastMethod.lowercase()\n"
    "            \" · StableAR ${status.attachments}/$backend · corr ${status.acceptedCorrections}\"\n"
    "        }.orEmpty()\n",
)
replace_once(
    "                        \"$latestDetectionCount detected · $latestPoseCount pose · $latestSpatializedCount spatialized · $latestLocalTrackCount active · $ack · $bufferedRemoteTracks remote · ${latestInferenceMs} ms · $detectorText · $terrainText\"\n",
    "                        \"$latestDetectionCount detected · $latestPoseCount pose · $latestSpatializedCount spatialized · $latestLocalTrackCount active · $ack · $bufferedRemoteTracks remote · ${latestInferenceMs} ms · $detectorText · $terrainText$stableText\"\n",
)
replace_once(
    "                    ArMode.VIEWER -> if (worldFromSite == null) \"Resolving shared location…\" else \"Observing shared tracks\"\n",
    "                    ArMode.VIEWER -> if (worldFromSite == null) \"Resolving shared location…\" else \"Observing shared tracks$stableText\"\n",
)
replace_once(
    "    override fun onManualMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long) {\n"
    "        remoteTracks.addMarker(id, label, position, expiresAtMs)\n"
    "    }\n",
    "    override fun onManualMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long) {\n"
    "        remoteTracks.addMarker(id, label, position, expiresAtMs)\n"
    "        if (label.equals(\"stablear\", ignoreCase = true)) {\n"
    "            val value = stableAr\n"
    "            if (value != null) value.queueRemoteMarker(id, position, expiresAtMs)\n"
    "            else pendingStableMarkers.add(PendingStableMarker(id, label, position.copyOf(), expiresAtMs))\n"
    "        }\n"
    "    }\n",
)
replace_once(
    "    private fun action(label: String, block: () -> Unit): Button = Button(this).apply {\n",
    "    private fun ensureStableAr(active: Session): StableArCoordinator {\n"
    "        stableAr?.let { return it }\n"
    "        return StableArCoordinator(\n"
    "            context = applicationContext,\n"
    "            session = active,\n"
    "            benchmarkEnabled = stableArBenchmarkEnabled,\n"
    "            benchmarkPhase = stableArBenchmarkPhase,\n"
    "            onRefinedMarker = ::onStableArRefinedMarker\n"
    "        ).also { coordinator ->\n"
    "            stableAr = coordinator\n"
    "            while (true) {\n"
    "                val pending = pendingStableMarkers.poll() ?: break\n"
    "                coordinator.queueRemoteMarker(pending.id, pending.position, pending.expiresAtMs)\n"
    "            }\n"
    "            spatialApp.logger.info(\n"
    "                \"StableAR coordinator attached to host ARCore session\",\n"
    "                mapOf(\"benchmark\" to stableArBenchmarkEnabled, \"benchmarkPath\" to coordinator.status().benchmarkPath)\n"
    "            )\n"
    "        }\n"
    "    }\n\n"
    "    private fun onStableArRefinedMarker(marker: StableArRefinedMarker) {\n"
    "        remoteTracks.addMarker(marker.markerId, \"stablear\", marker.sitePosition, marker.expiresAtMs)\n"
    "        if (!marker.broadcast) return\n"
    "        val now = System.currentTimeMillis()\n"
    "        val previous = stableArBroadcastAt[marker.markerId] ?: 0L\n"
    "        if (previous == 0L || marker.acceptedCorrection || now - previous >= 2_000L) {\n"
    "            val ttl = (marker.expiresAtMs - now).coerceIn(1_000L, 24L * 60L * 60L * 1000L)\n"
    "            realtime?.sendManualMarker(marker.markerId, \"stablear\", marker.sitePosition, ttl)\n"
    "            stableArBroadcastAt[marker.markerId] = now\n"
    "        }\n"
    "    }\n\n"
    "    private fun releaseStableArOnGlThread() {\n"
    "        if (!::glSurface.isInitialized) return\n"
    "        glSurface.queueEvent {\n"
    "            val value = stableAr\n"
    "            stableAr = null\n"
    "            runCatching { value?.close() }\n"
    "        }\n"
    "    }\n\n"
    "    private fun action(label: String, block: () -> Unit): Button = Button(this).apply {\n",
)
replace_once(
    "        const val EXTRA_MAP_ID = \"map_id\"\n"
    "        const val EXTRA_MODE = \"mode\"\n",
    "        const val EXTRA_MAP_ID = \"map_id\"\n"
    "        const val EXTRA_MODE = \"mode\"\n"
    "        const val EXTRA_STABLEAR_BENCHMARK = \"stablear_benchmark\"\n"
    "        const val EXTRA_STABLEAR_BENCHMARK_PHASE = \"stablear_benchmark_phase\"\n",
)

PATH.write_text(text, encoding="utf-8")
print(f"StableAR ArActivity integration ready: {PATH}")
