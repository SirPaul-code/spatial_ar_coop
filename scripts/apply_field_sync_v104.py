from pathlib import Path


def rep(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {text.count(old)}")
    return text.replace(old, new, 1)


# 1) Live AR: preserve resolver detail, prove WS room traffic during localization,
# and never draw this phone's own network-spatial copy over its raw detector box.
ar = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
t = ar.read_text()
t = rep(t,
'''    @Volatile private var realtimeConnected = false
    private var sequence = 0L''',
'''    @Volatile private var realtimeConnected = false
    @Volatile private var localizationDetail = ""
    private var sequence = 0L''',
"ArActivity localizationDetail")
t = rep(t,
'''            cloudAnchors = CloudAnchorCoordinator(
                session = created,
                mapId = mapId,
                database = spatialApp.database,
                logger = spatialApp.logger,
                scheduleUpload = { UploadScheduler.enqueue(this) },
                onState = ::showDetail
            )''',
'''            cloudAnchors = CloudAnchorCoordinator(
                session = created,
                mapId = mapId,
                database = spatialApp.database,
                logger = spatialApp.logger,
                scheduleUpload = { UploadScheduler.enqueue(this) },
                onState = { message ->
                    localizationDetail = message
                    showDetail(message)
                }
            )''',
"ArActivity coordinator callback")
t = rep(t,
'''        if (requestRelocalize.getAndSet(false)) {
            manualWorldFromSite.set(null)
            manualAlignmentOverride = false
            cloudAnchors?.resetReference()
            resolveLastAttemptMs = 0L
            showDetail("Re-localizing · point around a mapped anchor area and move slowly")
        }''',
'''        if (requestRelocalize.getAndSet(false)) {
            manualWorldFromSite.set(null)
            manualAlignmentOverride = false
            cloudAnchors?.resetReference()
            resolveLastAttemptMs = 0L
            localizationDetail = "Re-localizing · point around a mapped anchor area and move slowly"
            showDetail(localizationDetail)
        }''',
"ArActivity relocalize")
t = rep(t,
'''            updateHud(frame, null, instruction)
            overlay.updateTracks(emptyList())
            return''',
'''            // In Live mode the diagnostic HUD itself reports room connectivity, buffered remote
            // tracks and the actual Cloud Anchor resolver state. Do not overwrite it every frame
            // with the generic localization sentence.
            updateHud(frame, null, if (mode == ArMode.LIVE) null else instruction)
            overlay.updateTracks(emptyList())
            return''',
"ArActivity localizing HUD override")
t = rep(t,
'''    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray) {
        val now = System.currentTimeMillis()
        val projected = remoteTracks.snapshot(now).mapNotNull { track ->
            val world = PoseMath.transformPoint(worldFromSite, track.position)
            val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight) ?: return@mapNotNull null
            ProjectedTrack(
                key = track.key,
                label = track.label,
                confidence = track.confidence,
                x = screen.x,
                y = screen.y,
                onScreen = screen.onScreen,
                distanceMeters = PoseMath.distance(cameraSite, track.position),
                uncertaintyMeters = track.uncertaintyMeters,
                ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),
                sourceId = track.sourceId,
                bounds = projectTrackBounds(track, worldFromSite)
            )
        }
        overlay.updateTracks(projected)
    }''',
'''    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray) {
        val now = System.currentTimeMillis()
        val localDeviceId = spatialApp.preferences.deviceId
        val projected = remoteTracks.snapshot(now)
            .asSequence()
            .filter { track -> track.sourceId == "marker" || track.sourceId != localDeviceId }
            .mapNotNull { track ->
                val world = PoseMath.transformPoint(worldFromSite, track.position)
                val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight) ?: return@mapNotNull null
                ProjectedTrack(
                    key = track.key,
                    label = track.label,
                    confidence = track.confidence,
                    x = screen.x,
                    y = screen.y,
                    onScreen = screen.onScreen,
                    distanceMeters = PoseMath.distance(cameraSite, track.position),
                    uncertaintyMeters = track.uncertaintyMeters,
                    ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),
                    sourceId = track.sourceId,
                    bounds = projectTrackBounds(track, worldFromSite)
                )
            }
            .toList()
        overlay.updateTracks(projected)
    }''',
"ArActivity self track filter")
t = rep(t,
'''        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val locationState = when {''',
'''        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val bufferedRemoteTracks = remoteTracks.snapshot(now).count { track ->
            track.sourceId != spatialApp.preferences.deviceId && track.sourceId != "marker"
        }
        val locationState = when {''',
"ArActivity remote buffer count")
t = rep(t,
'''                    ArMode.LIVE -> if (worldFromSite == null) {
                        "Detector active · $latestDetectionCount visible object(s) · resolving shared location"
                    } else {
                        "$latestDetectionCount detected · $latestLocalTrackCount spatial track(s) · sharing automatically"
                    }''',
'''                    ArMode.LIVE -> if (worldFromSite == null) {
                        val room = if (realtimeConnected) "room connected" else "room reconnecting"
                        val resolver = localizationDetail.ifBlank { "resolving saved Cloud Anchors" }
                        "Detector active · $latestDetectionCount visible · $bufferedRemoteTracks remote buffered · $room · $resolver"
                    } else {
                        "$latestDetectionCount detected · $latestLocalTrackCount spatial track(s) · sharing automatically"
                    }''',
"ArActivity live HUD")
ar.write_text(t)


# 2) Cloud Anchor: once a resolve succeeds, preserve the last valid site transform across a
# temporary Anchor.PAUSED state. The latest main already correctly removed the old short timeout.
cloud = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt")
t = cloud.read_text()
t = rep(t,
'''    private val reference = AtomicReference<Reference?>(null)
    private val hosting = AtomicBoolean(false)''',
'''    private val reference = AtomicReference<Reference?>(null)
    private val lastWorldFromSite = AtomicReference<FloatArray?>(null)
    private val hosting = AtomicBoolean(false)''',
"Cloud cache field")
t = rep(t,
'''    val cloudConfigured: Boolean get() = BuildConfig.CLOUD_ANCHORS_CONFIGURED
    val hasReference: Boolean get() = reference.get()?.anchor?.trackingState == TrackingState.TRACKING
    val isHosting: Boolean get() = hosting.get()''',
'''    val cloudConfigured: Boolean get() = BuildConfig.CLOUD_ANCHORS_CONFIGURED
    val hasReference: Boolean get() = reference.get() != null
    val isHosting: Boolean get() = hosting.get()''',
"Cloud hasReference")
t = rep(t,
'''    /** Recomputed from the tracking anchor every frame, so ARCore world-frame refinements are absorbed. */
    fun currentWorldFromSite(): FloatArray? {
        val current = reference.get() ?: return null
        if (current.anchor.trackingState != TrackingState.TRACKING) return null
        val worldFromAnchor = PoseMath.poseToMatrix(current.anchor.pose)
        return PoseMath.multiply(worldFromAnchor, PoseMath.rigidInverse(current.definition.siteFromAnchor))
    }

    fun attachHostedReference(localAnchor: Anchor, definition: AnchorDefinition) {
        ownedAnchors += localAnchor
        reference.compareAndSet(null, Reference(localAnchor, definition))
    }''',
'''    /**
     * Refresh from the live anchor while it is tracking. After a successful resolve, retain the
     * last valid site transform through temporary Anchor.PAUSED periods rather than reverting a
     * healthy Live session to Localizing.
     */
    fun currentWorldFromSite(): FloatArray? {
        val current = reference.get() ?: return lastWorldFromSite.get()?.copyOf()
        if (current.anchor.trackingState == TrackingState.TRACKING) {
            val live = PoseMath.multiply(
                PoseMath.poseToMatrix(current.anchor.pose),
                PoseMath.rigidInverse(current.definition.siteFromAnchor)
            )
            lastWorldFromSite.set(live.copyOf())
            return live
        }
        return lastWorldFromSite.get()?.copyOf()
    }

    fun attachHostedReference(localAnchor: Anchor, definition: AnchorDefinition) {
        ownedAnchors += localAnchor
        if (reference.compareAndSet(null, Reference(localAnchor, definition))) {
            lastWorldFromSite.set(
                PoseMath.multiply(
                    PoseMath.poseToMatrix(localAnchor.pose),
                    PoseMath.rigidInverse(definition.siteFromAnchor)
                )
            )
        }
    }''',
"Cloud current transform")
t = rep(t,
'''                if (state == CloudAnchorState.SUCCESS && anchor != null) {
                    if (reference.compareAndSet(null, Reference(anchor, definition))) {
                        ownedAnchors += anchor
                        resolving.set(false)''',
'''                if (state == CloudAnchorState.SUCCESS && anchor != null) {
                    if (reference.compareAndSet(null, Reference(anchor, definition))) {
                        ownedAnchors += anchor
                        lastWorldFromSite.set(
                            PoseMath.multiply(
                                PoseMath.poseToMatrix(anchor.pose),
                                PoseMath.rigidInverse(definition.siteFromAnchor)
                            )
                        )
                        resolving.set(false)''',
"Cloud resolve success cache")
t = rep(t,
'''                val retainedAsReference = reference.compareAndSet(null, Reference(localAnchor, definition))
                if (!retainedAsReference) {
                    localAnchor.detach()
                    ownedAnchors.remove(localAnchor)
                }''',
'''                val retainedAsReference = reference.compareAndSet(null, Reference(localAnchor, definition))
                if (retainedAsReference) {
                    lastWorldFromSite.set(
                        PoseMath.multiply(
                            PoseMath.poseToMatrix(localAnchor.pose),
                            PoseMath.rigidInverse(definition.siteFromAnchor)
                        )
                    )
                } else {
                    localAnchor.detach()
                    ownedAnchors.remove(localAnchor)
                }''',
"Cloud hosted cache")
t = rep(t,
'''    fun resetReference() {
        cancelResolveBatch()
        reference.getAndSet(null)?.let { current ->''',
'''    fun resetReference() {
        cancelResolveBatch()
        lastWorldFromSite.set(null)
        reference.getAndSet(null)?.let { current ->''',
"Cloud reset cache")
t = rep(t,
'''        ownedAnchors.clear()
        reference.set(null)
    }''',
'''        ownedAnchors.clear()
        reference.set(null)
        lastWorldFromSite.set(null)
    }''',
"Cloud close cache")
cloud.write_text(t)


# 3) Spatial estimation: keep latest main's Depth/upward-plane/ground filtering, but do not invent
# shared 3D coordinates from an assumed class size. Raw local boxes remain visible either way.
est = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/SpatialEstimator.kt")
t = est.read_text()
t = rep(t,
'''        // Last-resort monocular estimate. It is intentionally marked with much larger uncertainty
        // than Depth/plane/ground hits, but keeps obvious people/cars/birds shareable when ARCore
        // has no hit at the moving object's contact point.
        return estimateFromBoundingBoxSize(frame, detection, siteFromWorld)
    }

    fun centerGroundPoint''',
'''        // Do not invent a networked 3D point from class-size assumptions. Keep the accurate local
        // 2D detector box; publish a shared track only when Depth/plane/saved-ground supplies actual
        // spatial evidence.
        return null
    }

    fun centerGroundPoint''',
"Estimator fallback call")
start = t.find("    private fun estimateFromBoundingBoxSize(")
end = t.find("    private fun intersectGround(", start)
if start < 0 or end < 0:
    raise SystemExit("Estimator fallback function not found")
t = t[:start] + t[end:]
est.write_text(t)


# 4) Bump version beyond current main v1.0.3.
gradle = Path("android/app/build.gradle.kts")
t = gradle.read_text()
t = rep(t,
'''        versionCode = 4
        versionName = "1.0.3"''',
'''        versionCode = 5
        versionName = "1.0.4"''',
"Version bump")
gradle.write_text(t)

notes = Path("android/RELEASE_NOTES.md")
t = notes.read_text()
head = "# Android release notes\n\n"
if not t.startswith(head):
    raise SystemExit("Unexpected release notes header")
section = '''## 1.0.4

- Preserve the last valid shared transform after a successful Cloud Anchor resolve through temporary anchor tracking pauses.
- Show `room connected/reconnecting` and the count of buffered remote tracks even while a phone is still localizing.
- Do not draw a participant's own network-spatial boxes over its local raw detector boxes.
- Remove the class-size monocular 3D fallback; shared moving-object tracks now require Depth, a valid upward-facing plane, or saved-ground evidence.
- Retain v1.0.3 detector NMS, stricter person/car thresholds, two-hit track confirmation, short stale-track lifetime, and uncapped ARCore resolve duration.

'''
notes.write_text(head + section + t[len(head):])
