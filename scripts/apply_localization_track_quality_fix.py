from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)


ar = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
text = ar.read_text()
text = replace_once(
    text,
    '''    @Volatile private var realtimeConnected = false
    private var sequence = 0L''',
    '''    @Volatile private var realtimeConnected = false
    @Volatile private var localizationDetail = ""
    private var sequence = 0L''',
    "localizationDetail insertion point",
)
text = replace_once(
    text,
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
    "CloudAnchorCoordinator construction block",
)
text = replace_once(
    text,
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
    "relocalize block",
)
text = replace_once(
    text,
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
            // The source phone already has an accurate raw detector box. Rendering its own
            // spatialized network copy on top only duplicates labels and makes estimator jitter
            // look like multiple objects. Spatial boxes are for other participants (and markers).
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
    "updateProjectedTracks block",
)
text = replace_once(
    text,
    '''        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val locationState = when {''',
    '''        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val bufferedRemoteTracks = remoteTracks.snapshot(now).count { track ->
            track.sourceId != spatialApp.preferences.deviceId && track.sourceId != "marker"
        }
        val locationState = when {''',
    "HUD remote buffer insertion point",
)
text = replace_once(
    text,
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
    "LIVE HUD block",
)
ar.write_text(text)


estimator = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/SpatialEstimator.kt")
estimator.write_text(r'''package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.vision.Detection2D
import kotlin.math.abs


data class EstimatedPosition(
    val sitePosition: FloatArray,
    val uncertaintyMeters: Float,
    val method: String
)

object SpatialEstimator {
    fun estimate(
        frame: Frame,
        detection: Detection2D,
        worldFromSite: FloatArray,
        groundY: Float?
    ): EstimatedPosition? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val ground = groundY?.let {
            intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it)
        }

        val view = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            detection.rawBottomCenter,
            Coordinates2d.VIEW,
            view
        )
        val bestHit = frame.hitTest(view[0], view[1])
            .filter { hit ->
                when (val trackable = hit.trackable) {
                    is DepthPoint -> true
                    is Plane -> trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
                    is Point -> trackable.trackingState == TrackingState.TRACKING
                    else -> false
                }
            }
            .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))

        if (bestHit != null) {
            val site = PoseMath.transformPoint(siteFromWorld, bestHit.hitPose.translation)
            return when (bestHit.trackable) {
                is DepthPoint -> {
                    if (ground != null && usesGroundContact(detection.label)) {
                        if (PoseMath.distance(site, ground) <= 2.0f) {
                            EstimatedPosition(
                                sitePosition = floatArrayOf(
                                    site[0] * 0.60f + ground[0] * 0.40f,
                                    ground[1],
                                    site[2] * 0.60f + ground[2] * 0.40f
                                ),
                                uncertaintyMeters = 0.28f,
                                method = "depth+ground"
                            )
                        } else {
                            EstimatedPosition(ground, 0.52f, "ground-ray")
                        }
                    } else {
                        EstimatedPosition(site, 0.30f, "depth")
                    }
                }
                is Plane -> {
                    if (ground != null && usesGroundContact(detection.label)) {
                        if (PoseMath.distance(site, ground) <= 1.5f) {
                            EstimatedPosition(
                                sitePosition = floatArrayOf(
                                    site[0] * 0.35f + ground[0] * 0.65f,
                                    ground[1],
                                    site[2] * 0.35f + ground[2] * 0.65f
                                ),
                                uncertaintyMeters = 0.38f,
                                method = "plane+ground"
                            )
                        } else {
                            EstimatedPosition(ground, 0.55f, "ground-ray")
                        }
                    } else {
                        EstimatedPosition(site, 0.45f, "plane")
                    }
                }
                is Point -> {
                    // Feature points behind moving objects caused the field-test duplicate tracks.
                    if (usesGroundContact(detection.label)) {
                        ground?.let { EstimatedPosition(it, 0.60f, "ground-ray") }
                    } else {
                        EstimatedPosition(site, 0.75f, "feature-point")
                    }
                }
                else -> null
            }
        }

        // Keep the accurate local 2D box, but do not invent a networked 3D position from class size.
        return ground?.let { EstimatedPosition(it, 0.60f, "ground-ray") }
    }

    fun centerGroundPoint(frame: Frame, worldFromSite: FloatArray, groundY: Float?): FloatArray? {
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        return if (groundY != null) {
            intersectGround(
                frame,
                floatArrayOf(dimensions[0] * 0.5f, dimensions[1] * 0.58f),
                PoseMath.rigidInverse(worldFromSite),
                groundY
            )
        } else {
            val view = floatArrayOf(0.5f * dimensions[0], 0.58f * dimensions[1])
            val output = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, view, Coordinates2d.VIEW, output)
            frame.hitTest(output[0], output[1]).firstOrNull()?.hitPose?.translation?.let {
                PoseMath.transformPoint(PoseMath.rigidInverse(worldFromSite), it)
            }
        }
    }

    private fun usesGroundContact(label: String): Boolean = when (label.lowercase()) {
        "person", "car", "bird", "dog", "cat" -> true
        else -> false
    }

    private fun intersectGround(
        frame: Frame,
        imagePixel: FloatArray,
        siteFromWorld: FloatArray,
        groundY: Float
    ): FloatArray? {
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        if (focal[0] <= 0f || focal[1] <= 0f) return null
        val cameraDirection = PoseMath.normalize(
            floatArrayOf(
                (imagePixel[0] - principal[0]) / focal[0],
                -(imagePixel[1] - principal[1]) / focal[1],
                -1f
            )
        )
        val worldFromCamera = PoseMath.poseToMatrix(frame.camera.pose)
        val worldDirection = PoseMath.transformDirection(worldFromCamera, cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        if (abs(siteDirection[1]) < 0.015f) return null
        val siteOrigin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val distance = (groundY - siteOrigin[1]) / siteDirection[1]
        if (!distance.isFinite() || distance !in 0.15f..60f) return null
        return floatArrayOf(
            siteOrigin[0] + siteDirection[0] * distance,
            groundY,
            siteOrigin[2] + siteDirection[2] * distance
        )
    }

    private fun priority(trackable: Any): Int = when (trackable) {
        is DepthPoint -> 0
        is Plane -> 1
        is Point -> 2
        else -> 3
    }
}
''')


cloud = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt")
text = cloud.read_text()
text = replace_once(
    text,
    '''    private val reference = AtomicReference<Reference?>(null)
    private val hosting = AtomicBoolean(false)''',
    '''    private val reference = AtomicReference<Reference?>(null)
    private val lastWorldFromSite = AtomicReference<FloatArray?>(null)
    private val hosting = AtomicBoolean(false)''',
    "Cloud reference insertion point",
)
text = replace_once(
    text,
    '''    val cloudConfigured: Boolean get() = BuildConfig.CLOUD_ANCHORS_CONFIGURED
    val hasReference: Boolean get() = reference.get()?.anchor?.trackingState == TrackingState.TRACKING
    val isHosting: Boolean get() = hosting.get()''',
    '''    val cloudConfigured: Boolean get() = BuildConfig.CLOUD_ANCHORS_CONFIGURED
    val hasReference: Boolean get() = reference.get() != null
    val isHosting: Boolean get() = hosting.get()''',
    "hasReference block",
)
text = replace_once(
    text,
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
     * Recompute from the live anchor while it is tracking, but keep the last successfully resolved
     * site transform through temporary Anchor.PAUSED periods instead of dropping back to Localizing.
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
    "currentWorldFromSite block",
)
text = replace_once(
    text,
    '''                if (state == CloudAnchorState.SUCCESS && anchor != null) {
                    if (reference.compareAndSet(null, Reference(anchor, definition))) {
                        ownedAnchors += anchor
                        resolving.set(false)
                        resolveStartedAtMs = 0L''',
    '''                if (state == CloudAnchorState.SUCCESS && anchor != null) {
                    if (reference.compareAndSet(null, Reference(anchor, definition))) {
                        ownedAnchors += anchor
                        lastWorldFromSite.set(
                            PoseMath.multiply(
                                PoseMath.poseToMatrix(anchor.pose),
                                PoseMath.rigidInverse(definition.siteFromAnchor)
                            )
                        )
                        resolving.set(false)
                        resolveStartedAtMs = 0L''',
    "resolve success block",
)
text = replace_once(
    text,
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
    "host retained block",
)
text = replace_once(
    text,
    '''    fun resetReference() {
        cancelResolveBatch()
        reference.getAndSet(null)?.let { current ->''',
    '''    fun resetReference() {
        cancelResolveBatch()
        lastWorldFromSite.set(null)
        reference.getAndSet(null)?.let { current ->''',
    "resetReference block",
)
text = replace_once(
    text,
    '''        ownedAnchors.clear()
        reference.set(null)
    }

    companion object {''',
    '''        ownedAnchors.clear()
        reference.set(null)
        lastWorldFromSite.set(null)
    }

    companion object {''',
    "close clear block",
)
text = replace_once(
    text,
    "        private const val RESOLVE_BATCH_TIMEOUT_MS = 12_000L",
    "        private const val RESOLVE_BATCH_TIMEOUT_MS = 60_000L",
    "resolve timeout constant",
)
cloud.write_text(text)


tracker = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/vision/DetectionTracker.kt")
text = tracker.read_text()
text = replace_once(
    text,
    "        private const val TRACK_TIMEOUT_MS = 2_200L",
    "        private const val TRACK_TIMEOUT_MS = 1_500L",
    "tracker timeout constant",
)
tracker.write_text(text)


gradle = Path("android/app/build.gradle.kts")
text = gradle.read_text()
text = replace_once(
    text,
    'versionCode = 3\n        versionName = "1.0.2"',
    'versionCode = 4\n        versionName = "1.0.3"',
    "Android version",
)
gradle.write_text(text)


notes = Path("android/RELEASE_NOTES.md")
original = notes.read_text() if notes.exists() else ""
entry = '''# Android release notes

## 1.0.3

- Preserve a successfully resolved shared transform through temporary Cloud Anchor tracking pauses.
- Give Cloud Anchor resolution up to 60 seconds instead of cancelling every request after 12 seconds.
- Show room connectivity and buffered remote-track count while shared localization is pending.
- Do not render a phone's own spatial network boxes on top of its local detector boxes.
- Remove the unstable class-size monocular range fallback that produced duplicate distant tracks.
- Prefer ground/depth/plane evidence for moving-object 3D positions and shorten stale local-track lifetime.

'''
if original.startswith("# Android release notes\n\n"):
    original = original[len("# Android release notes\n\n"):]
notes.write_text(entry + original)
