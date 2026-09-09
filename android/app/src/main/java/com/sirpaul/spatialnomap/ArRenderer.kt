package com.sirpaul.spatialnomap

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class ArRenderer(
    context: Context,
    private val coordinator: AlignmentCoordinator,
    private val overlay: TargetOverlayView,
    private val usernameProvider: () -> String,
    private val status: (String) -> Unit,
    private val rotationProvider: () -> Int,
    private val sensorSnapshotProvider: () -> SensorSnapshot = { SpatialSyncApplication.sensorSnapshot() },
) : GLSurfaceView.Renderer {
    private data class RemoteTargetRequest(
        val id: Long,
        val point: FloatArray?,
        val owner: String,
        val confidence: Float,
        val surface: SurfaceTargetReference?,
    )

    private class LocalTarget(
        var anchor: Anchor,
        var owner: String,
        var surface: SurfaceTargetReference?,
        var surfaceVerified: Boolean = false,
        var pendingCorrection: FloatArray? = null,
        var correctionVotes: Int = 0,
        var lastSurfaceShareMs: Long = 0L,
    )

    private class RemoteTarget(
        var anchor: Anchor,
        var owner: String,
        var confidence: Float,
        var surface: SurfaceTargetReference?,
        var surfaceVerified: Boolean = false,
        var pendingCorrection: FloatArray? = null,
        var correctionVotes: Int = 0,
    )

    private data class DynamicTarget(
        val point: FloatArray,
        val owner: String,
        val label: String,
        val confidence: Float,
        val lastSeenMs: Long,
    )

    private data class SurfaceResolveJob(
        val id: Long,
        val local: Boolean,
        val reference: SurfaceTargetReference,
        val expectedWorld: FloatArray,
    )

    private data class SurfaceCorrection(
        val id: Long,
        val local: Boolean,
        val currentFrame: CapturedFrame,
        val result: SurfaceTargetResolver.Result,
    )

    @Volatile var session: Session? = null
    @Volatile var sessionResumed: Boolean = false

    private val background = CameraBackgroundRenderer()
    private val pendingTap = AtomicReference<FloatArray?>(null)
    private val remoteTargetRequests = ConcurrentLinkedQueue<RemoteTargetRequest>()
    private val clearTargetsRequested = AtomicBoolean(false)
    private val trackingGate = TrackingStabilityGate(acquireMs = 300L, lossMs = 1000L)
    private val targetLock = Any()

    private val vehicleDetector = VehicleDetector(context)
    private val pendingVehicleDetections = AtomicReference<List<VehicleDetector.Vehicle>?>(null)
    private val pendingVehicleError = AtomicReference<String?>(null)

    /**
     * Surface resolution is intentionally separate from ARCore. ARCore keeps the
     * marker stable frame-to-frame; this verifier periodically proves that the
     * anchor is still attached to the original texture + metric surface and repairs
     * it if the local VIO world has drifted.
     */
    private val surfaceResolverExecutor = Executors.newSingleThreadExecutor()
    private val surfaceResolveBusy = AtomicBoolean(false)
    private val surfaceCorrections = ConcurrentLinkedQueue<SurfaceCorrection>()

    private val localTargets = LinkedHashMap<Long, LocalTarget>()
    private val remoteTargets = LinkedHashMap<Long, RemoteTarget>()
    private val localVehicles = LinkedHashMap<Long, DynamicTarget>()
    private val remoteVehicles = LinkedHashMap<Long, DynamicTarget>()

    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastFrameError = ""
    private var lastFrameErrorAtMs = 0L
    private var lastSyncHintAtMs = 0L
    private var lastVehicleSubmitMs = 0L
    private var lastVehicleError = ""
    private var lastSurfaceResolveMs = 0L
    private var surfaceResolveCursor = 0

    init {
        overlay.onSceneTap = { x, y -> queueTap(x, y) }
    }

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(floatArrayOf(x, y))
    }

    fun setRemoteTarget(id: Long, pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        remoteTargetRequests.add(
            RemoteTargetRequest(
                id = id,
                point = pointLocalWorld?.copyOf(3),
                owner = owner,
                confidence = confidence,
                surface = if (pointLocalWorld != null) SurfaceTargetRegistry.remote(id) else null,
            ),
        )
    }

    fun setRemoteTarget(pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        setRemoteTarget(0L, pointLocalWorld, owner, confidence)
    }

    fun clearTargets() {
        remoteTargetRequests.clear()
        surfaceCorrections.clear()
        clearTargetsRequested.set(true)
        pendingTap.set(null)
        pendingVehicleDetections.set(null)
        overlay.setTargets(emptyList())
    }

    fun targetCount(): Int = synchronized(targetLock) {
        localTargets.size + remoteTargets.size + localVehicles.size + remoteVehicles.size
    }

    fun detachSession() {
        sessionResumed = false
        session = null
        textureBoundSession = null
        pendingTap.set(null)
        remoteTargetRequests.clear()
        surfaceCorrections.clear()
        pendingVehicleDetections.set(null)
        clearTargetsRequested.set(false)
        detachAnchorsAndTracks()
        lastCaptureNs = 0L
        lastFrameError = ""
        lastSyncHintAtMs = 0L
        lastVehicleSubmitMs = 0L
        lastSurfaceResolveMs = 0L
        surfaceResolveCursor = 0
        trackingGate.reset()
    }

    fun close() {
        vehicleDetector.close()
        surfaceResolverExecutor.shutdownNow()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureBoundSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
        if (sessionResumed) runCatching { session?.setDisplayGeometry(rotationProvider(), width, height) }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!sessionResumed) return
        val s = session ?: return

        try {
            if (textureBoundSession !== s) {
                s.setCameraTextureName(background.textureId)
                textureBoundSession = s
                s.setDisplayGeometry(rotationProvider(), width, height)
            }
            if (!sessionResumed || session !== s) return

            val frame = s.update()
            background.draw(frame)
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING

            if (clearTargetsRequested.getAndSet(false)) detachAnchorsAndTracks()
            applyRemoteTargetRequests(s, tracking)

            when (trackingGate.update(tracking, SystemClock.elapsedRealtime())) {
                true -> status("AR tracking")
                false -> status("AR PAUSED / ${camera.trackingFailureReason}")
                null -> Unit
            }
            if (!tracking) return

            applySurfaceCorrections(s)
            handleTap(s, frame, camera)
            captureIfDue(frame, camera)
            maybeDetectVehicles(s, frame, camera)
            applyVehicleDetections()
            expireVehicleTracks()
            publishVehicleErrorIfNeeded()
            publishSyncGuidanceIfNeeded()
            projectTargets(camera)
        } catch (t: Throwable) {
            if (t.javaClass.simpleName == "SessionPausedException") return
            val error = errorText(t)
            val now = System.currentTimeMillis()
            if (error != lastFrameError || now - lastFrameErrorAtMs > 2500L) {
                lastFrameError = error
                lastFrameErrorAtMs = now
                status("AR frame error: $error")
            }
        }
    }

    private fun handleTap(session: Session, frame: Frame, camera: Camera) {
        val tap = pendingTap.getAndSet(null) ?: return
        if (!coordinator.canPlacePoi()) {
            status("SYNCING — keep both cameras on the same detailed area and move slowly side-to-side")
            return
        }

        val imagePixel = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, tap, Coordinates2d.IMAGE_PIXELS, imagePixel)
        val metricWorld = MetricSupportSampler.pointAtCpuPixel(frame, camera, imagePixel[0], imagePixel[1])
        val cameraWorld = camera.pose.translation
        val metricDistance = metricWorld?.let { pointDistance(cameraWorld, it) }
        val depthTolerance = metricDistance?.let { max(TAP_DEPTH_MIN_TOLERANCE_M, it * TAP_DEPTH_TOLERANCE_RATIO) }

        var bestHit: HitResult? = null
        var bestScore = Float.POSITIVE_INFINITY
        for (hit in frame.hitTest(tap[0], tap[1])) {
            val trackable = hit.trackable
            val usable = when (trackable) {
                is DepthPoint -> true
                is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (!usable) continue

            val hitPoint = hit.hitPose.translation
            val hitDistance = pointDistance(cameraWorld, hitPoint)
            if (!hitDistance.isFinite() || hitDistance > MAX_POI_DISTANCE_M) continue

            val disagreement = metricWorld?.let { pointDistance(it, hitPoint) }
            if (disagreement != null && depthTolerance != null && disagreement > depthTolerance) continue

            val typePenalty = when (trackable) {
                is DepthPoint -> 0f
                is Plane -> 0.02f
                else -> 0.04f
            }
            val score = (disagreement ?: 0f) + typePenalty + hitDistance * 0.0005f
            if (score < bestScore) {
                bestScore = score
                bestHit = hit
            }
        }

        var newAnchor = bestHit?.let { runCatching { it.createAnchor() }.getOrNull() }
        if (newAnchor == null && metricWorld != null && metricDistance != null &&
            metricDistance.isFinite() && metricDistance <= MAX_POI_DISTANCE_M
        ) {
            newAnchor = runCatching { session.createAnchor(Pose.makeTranslation(metricWorld)) }.getOrNull()
        }

        if (newAnchor == null) {
            status("No corroborated metric surface at the tap. Move slightly and tap again.")
            return
        }

        var id = newTargetId()
        synchronized(targetLock) {
            while (localTargets.containsKey(id) || remoteTargets.containsKey(id) ||
                localVehicles.containsKey(id) || remoteVehicles.containsKey(id)
            ) id += 1L
        }
        val owner = usernameProvider()
        val point = newAnchor.pose.translation

        // Capture an explicit visual/depth fingerprint of the physical surface. The
        // exact tap pixel is scaled into the transmitted CPU-camera frame.
        val referenceFrame = FrameCapture.capture(
            frame = frame,
            camera = camera,
            maxWidth = SURFACE_REFERENCE_MAX_WIDTH,
            sensors = sensorSnapshotProvider(),
        )
        val surfaceReference = referenceFrame?.let { captured ->
            val dims = camera.imageIntrinsics.imageDimensions
            val sx = captured.intrinsics.width.toFloat() / dims.getOrElse(0) { captured.intrinsics.width }.coerceAtLeast(1)
            val sy = captured.intrinsics.height.toFloat() / dims.getOrElse(1) { captured.intrinsics.height }.coerceAtLeast(1)
            SurfaceTargetReference(captured, floatArrayOf(imagePixel[0] * sx, imagePixel[1] * sy))
        }

        val sent = if (surfaceReference != null) {
            lastCaptureNs = System.nanoTime()
            coordinator.sendSurfacePoi(id, point, owner, surfaceReference.frame)
        } else {
            coordinator.sendPoi(id, point, owner)
        }

        if (sent) {
            synchronized(targetLock) {
                localTargets[id] = LocalTarget(newAnchor, owner, surfaceReference)
            }
            status(if (surfaceReference != null) "POI sent • surface lock armed" else "POI sent")
        } else {
            runCatching { newAnchor.detach() }
            status("Target blocked: spatial alignment is not ready")
        }
    }

    private fun applyRemoteTargetRequests(session: Session, tracking: Boolean) {
        if (!tracking) return

        while (true) {
            val request = remoteTargetRequests.poll() ?: break
            val dynamicCar = request.owner.startsWith(AUTO_CAR_PREFIX)
            if (dynamicCar) {
                val cleanOwner = request.owner.removePrefix(AUTO_CAR_PREFIX).ifBlank { "Peer" }
                synchronized(targetLock) {
                    if (request.point == null) {
                        remoteVehicles.remove(request.id)
                    } else {
                        remoteVehicles[request.id] = DynamicTarget(
                            point = request.point.copyOf(3),
                            owner = cleanOwner,
                            label = "CAR",
                            confidence = request.confidence,
                            lastSeenMs = System.currentTimeMillis(),
                        )
                    }
                }
                continue
            }

            if (request.point == null) {
                val removed = synchronized(targetLock) { remoteTargets.remove(request.id) }
                SurfaceTargetRegistry.remove(request.id)
                runCatching { removed?.anchor?.detach() }
                continue
            }

            val existing = synchronized(targetLock) { remoteTargets[request.id] }
            if (existing != null) {
                existing.owner = request.owner
                existing.confidence = request.confidence
                if (existing.surface == null && request.surface != null) existing.surface = request.surface

                // Global transform refinement may republish a better provisional XYZ.
                // Never overwrite a target after its own texture/depth verifier proved
                // the local surface; that local evidence is more specific.
                if (!existing.surfaceVerified) {
                    val delta = pointDistance(existing.anchor.pose.translation, request.point)
                    if (delta >= REMOTE_PROVISIONAL_REANCHOR_M && delta <= MAX_SURFACE_CORRECTION_M) {
                        val replacement = runCatching { session.createAnchor(Pose.makeTranslation(request.point)) }.getOrNull()
                        if (replacement != null) {
                            val old = existing.anchor
                            existing.anchor = replacement
                            runCatching { old.detach() }
                        }
                    }
                }
                continue
            }

            val anchor = runCatching { session.createAnchor(Pose.makeTranslation(request.point)) }.getOrNull() ?: continue
            synchronized(targetLock) {
                val prior = remoteTargets.putIfAbsent(
                    request.id,
                    RemoteTarget(anchor, request.owner, request.confidence, request.surface),
                )
                if (prior != null) runCatching { anchor.detach() }
            }
        }
    }

    private fun captureIfDue(frame: Frame, camera: Camera) {
        val locked = coordinator.quality().bothReady
        val budget = SpatialSyncApplication.captureBudget(locked)
        val now = System.nanoTime()
        if (now - lastCaptureNs < budget.intervalNs) return
        val packet = FrameCapture.capture(
            frame = frame,
            camera = camera,
            maxWidth = budget.maxWidth,
            sensors = sensorSnapshotProvider(),
        ) ?: return
        coordinator.onLocalFrame(packet)
        if (locked) scheduleSurfaceResolve(packet)
        lastCaptureNs = now
    }

    private fun scheduleSurfaceResolve(currentFrame: CapturedFrame) {
        val now = System.currentTimeMillis()
        if (now - lastSurfaceResolveMs < SURFACE_RESOLVE_INTERVAL_MS) return
        if (!surfaceResolveBusy.compareAndSet(false, true)) return

        val jobs = ArrayList<SurfaceResolveJob>()
        synchronized(targetLock) {
            localTargets.forEach { (id, target) ->
                val ref = target.surface ?: return@forEach
                if (target.anchor.trackingState == TrackingState.TRACKING) {
                    jobs += SurfaceResolveJob(id, true, ref, target.anchor.pose.translation.copyOf())
                }
            }
            remoteTargets.forEach { (id, target) ->
                val ref = target.surface ?: return@forEach
                if (target.anchor.trackingState == TrackingState.TRACKING) {
                    jobs += SurfaceResolveJob(id, false, ref, target.anchor.pose.translation.copyOf())
                }
            }
        }

        if (jobs.isEmpty()) {
            surfaceResolveBusy.set(false)
            return
        }
        lastSurfaceResolveMs = now
        val selected = ArrayList<SurfaceResolveJob>(SURFACE_RESOLVE_BATCH)
        repeat(minOf(SURFACE_RESOLVE_BATCH, jobs.size)) {
            selected += jobs[(surfaceResolveCursor + it) % jobs.size]
        }
        surfaceResolveCursor = (surfaceResolveCursor + selected.size) % jobs.size

        surfaceResolverExecutor.execute {
            try {
                for (job in selected) {
                    if (Thread.currentThread().isInterrupted) break
                    val result = runCatching {
                        SurfaceTargetResolver.resolve(job.reference, currentFrame, job.expectedWorld)
                    }.getOrNull() ?: continue
                    surfaceCorrections.add(SurfaceCorrection(job.id, job.local, currentFrame, result))
                }
            } finally {
                surfaceResolveBusy.set(false)
            }
        }
    }

    /** Apply only consensus visual+depth corrections on the AR/render thread. */
    private fun applySurfaceCorrections(session: Session) {
        while (true) {
            val correction = surfaceCorrections.poll() ?: break
            if (correction.local) applyLocalSurfaceCorrection(session, correction)
            else applyRemoteSurfaceCorrection(session, correction)
        }
    }

    private fun applyLocalSurfaceCorrection(session: Session, correction: SurfaceCorrection) {
        val target = synchronized(targetLock) { localTargets[correction.id] } ?: return
        val result = correction.result
        val oldPoint = target.anchor.pose.translation
        val delta = pointDistance(oldPoint, result.pointWorld)
        if (!delta.isFinite() || delta > MAX_SURFACE_CORRECTION_M) return
        if (delta < SURFACE_CORRECTION_DEADBAND_M) {
            target.surfaceVerified = true
            target.pendingCorrection = null
            target.correctionVotes = 0
            return
        }
        if (!voteForCorrection(target, result.pointWorld, result.confidence, result.visualInliers, delta)) return

        val replacement = runCatching { session.createAnchor(Pose.makeTranslation(result.pointWorld)) }.getOrNull() ?: return
        val old = target.anchor
        target.anchor = replacement
        target.surfaceVerified = true
        target.pendingCorrection = null
        target.correctionVotes = 0
        val refreshed = SurfaceTargetReference(correction.currentFrame, result.matchedPixel.copyOf())
        target.surface = refreshed
        runCatching { old.detach() }

        val now = System.currentTimeMillis()
        if (delta >= SURFACE_RESHARE_MIN_M && now - target.lastSurfaceShareMs >= SURFACE_RESHARE_INTERVAL_MS) {
            target.lastSurfaceShareMs = now
            coordinator.sendSurfacePoi(correction.id, replacement.pose.translation, target.owner, refreshed.frame)
        }
    }

    private fun applyRemoteSurfaceCorrection(session: Session, correction: SurfaceCorrection) {
        val target = synchronized(targetLock) { remoteTargets[correction.id] } ?: return
        val result = correction.result
        val oldPoint = target.anchor.pose.translation
        val delta = pointDistance(oldPoint, result.pointWorld)
        if (!delta.isFinite() || delta > MAX_SURFACE_CORRECTION_M) return
        if (delta < SURFACE_CORRECTION_DEADBAND_M) {
            target.surfaceVerified = true
            target.pendingCorrection = null
            target.correctionVotes = 0
            return
        }
        if (!voteForCorrection(target, result.pointWorld, result.confidence, result.visualInliers, delta)) return

        val replacement = runCatching { session.createAnchor(Pose.makeTranslation(result.pointWorld)) }.getOrNull() ?: return
        val old = target.anchor
        target.anchor = replacement
        target.surfaceVerified = true
        target.pendingCorrection = null
        target.correctionVotes = 0
        // Once this phone has independently seen the surface, continue tracking it
        // from its own visual reference instead of depending on the peer's old frame.
        target.surface = SurfaceTargetReference(correction.currentFrame, result.matchedPixel.copyOf())
        runCatching { old.detach() }
    }

    private fun voteForCorrection(
        target: LocalTarget,
        point: FloatArray,
        confidence: Float,
        inliers: Int,
        delta: Float,
    ): Boolean {
        val prior = target.pendingCorrection
        if (prior != null && pointDistance(prior, point) <= SURFACE_VOTE_CONSISTENCY_M) {
            target.correctionVotes += 1
            target.pendingCorrection = averagePoint(prior, point)
        } else {
            target.pendingCorrection = point.copyOf()
            target.correctionVotes = 1
        }
        val oneShot = delta <= SURFACE_ONE_SHOT_MAX_M && confidence >= SURFACE_ONE_SHOT_CONFIDENCE &&
            inliers >= SURFACE_ONE_SHOT_INLIERS
        return oneShot || target.correctionVotes >= SURFACE_REQUIRED_VOTES
    }

    private fun voteForCorrection(
        target: RemoteTarget,
        point: FloatArray,
        confidence: Float,
        inliers: Int,
        delta: Float,
    ): Boolean {
        val prior = target.pendingCorrection
        if (prior != null && pointDistance(prior, point) <= SURFACE_VOTE_CONSISTENCY_M) {
            target.correctionVotes += 1
            target.pendingCorrection = averagePoint(prior, point)
        } else {
            target.pendingCorrection = point.copyOf()
            target.correctionVotes = 1
        }
        val oneShot = delta <= SURFACE_ONE_SHOT_MAX_M && confidence >= SURFACE_ONE_SHOT_CONFIDENCE &&
            inliers >= SURFACE_ONE_SHOT_INLIERS
        return oneShot || target.correctionVotes >= SURFACE_REQUIRED_VOTES
    }

    private fun averagePoint(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(3) { i -> (a.getOrElse(i) { 0f } + b.getOrElse(i) { 0f }) * 0.5f }

    private fun maybeDetectVehicles(session: Session, frame: Frame, camera: Camera) {
        if (!coordinator.quality().bothReady || vehicleDetector.isBusy()) return
        val now = System.currentTimeMillis()
        if (now - lastVehicleSubmitMs < VEHICLE_DETECT_INTERVAL_MS) return

        val metric = MetricSupportSampler.sample(frame, camera, VEHICLE_METRIC_BUDGET)
        if (metric.size < 16) return
        val image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
        val accepted = vehicleDetector.submit(
            image = image,
            displayRotation = rotationProvider(),
            cameraId = runCatching { session.cameraConfig.cameraId }.getOrNull(),
            cameraWorld = camera.pose.translation.copyOf(),
            metricPoints = metric,
            onResult = { pendingVehicleDetections.set(it) },
            onError = { pendingVehicleError.set(it) },
        )
        if (accepted) lastVehicleSubmitMs = now
    }

    private fun applyVehicleDetections() {
        val detections = pendingVehicleDetections.getAndSet(null) ?: return
        val now = System.currentTimeMillis()
        val owner = usernameProvider()
        val matched = HashSet<Long>()

        for (vehicle in detections) {
            var bestId: Long? = null
            var bestDistance = Float.POSITIVE_INFINITY
            synchronized(targetLock) {
                for ((id, existing) in localVehicles) {
                    if (id in matched) continue
                    val distance = pointDistance(existing.point, vehicle.pointWorld)
                    if (distance < bestDistance && distance <= VEHICLE_ASSOCIATION_M) {
                        bestDistance = distance
                        bestId = id
                    }
                }
            }

            val isNew = bestId == null
            val id = bestId ?: newTargetId()
            val existing = synchronized(targetLock) { localVehicles[id] }
            val point = if (existing == null) vehicle.pointWorld.copyOf(3)
            else smoothPoint(existing.point, vehicle.pointWorld, VEHICLE_SMOOTH_ALPHA)

            synchronized(targetLock) {
                localVehicles[id] = DynamicTarget(
                    point = point,
                    owner = owner,
                    label = vehicle.label,
                    confidence = vehicle.confidence,
                    lastSeenMs = now,
                )
            }
            matched += id
            coordinator.sendPoi(id, point, "$AUTO_CAR_PREFIX$owner")
            if (isNew) status("Vehicle detected • sharing automatically")
        }
    }

    private fun expireVehicleTracks() {
        val now = System.currentTimeMillis()
        synchronized(targetLock) {
            localVehicles.filterValues { now - it.lastSeenMs > LOCAL_VEHICLE_TTL_MS }.keys.toList().forEach { localVehicles.remove(it) }
            remoteVehicles.filterValues { now - it.lastSeenMs > REMOTE_VEHICLE_TTL_MS }.keys.toList().forEach { remoteVehicles.remove(it) }
        }
    }

    private fun publishVehicleErrorIfNeeded() {
        val error = pendingVehicleError.getAndSet(null) ?: return
        if (error == lastVehicleError) return
        lastVehicleError = error
        status("Vehicle detection unavailable: $error")
    }

    private fun publishSyncGuidanceIfNeeded() {
        if (coordinator.quality().bothReady) return
        val now = System.currentTimeMillis()
        if (now - lastSyncHintAtMs < SYNC_HINT_INTERVAL_MS) return
        lastSyncHintAtMs = now
        status("SYNCING — point both phones at the same textured area and move slowly side-to-side")
    }

    private fun projectTargets(camera: Camera) {
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 500f)

        val localSnapshot: List<Pair<Long, LocalTarget>>
        val remoteSnapshot: List<Pair<Long, RemoteTarget>>
        val localVehicleSnapshot: List<Pair<Long, DynamicTarget>>
        val remoteVehicleSnapshot: List<Pair<Long, DynamicTarget>>
        synchronized(targetLock) {
            localSnapshot = localTargets.entries.map { it.key to it.value }
            remoteSnapshot = remoteTargets.entries.map { it.key to it.value }
            localVehicleSnapshot = localVehicles.entries.map { it.key to it.value }
            remoteVehicleSnapshot = remoteVehicles.entries.map { it.key to it.value }
        }

        val projected = ArrayList<TargetOverlayView.Target>(
            localSnapshot.size + remoteSnapshot.size + localVehicleSnapshot.size + remoteVehicleSnapshot.size,
        )
        for ((id, target) in localSnapshot) {
            projectAnchor(
                camera, target.anchor, id, "YOU • ${shortTargetId(id)}",
                coordinator.quality().confidence, true, view, projection,
            )?.let { projected += it }
        }
        for ((id, target) in remoteSnapshot) {
            val owner = target.owner.ifBlank { "PEER" }
            projectAnchor(camera, target.anchor, id, "$owner • ${shortTargetId(id)}", target.confidence, false, view, projection)
                ?.let { projected += it }
        }
        for ((id, target) in localVehicleSnapshot) {
            projectPoint(camera, target.point, id, "${target.label} • YOU", target.confidence, true, view, projection)
                ?.let { projected += it }
        }
        for ((id, target) in remoteVehicleSnapshot) {
            projectPoint(
                camera, target.point, id, "${target.label} • ${target.owner.ifBlank { "PEER" }}",
                target.confidence, false, view, projection,
            )?.let { projected += it }
        }
        overlay.setTargets(projected)
    }

    private fun projectAnchor(
        camera: Camera,
        anchor: Anchor,
        id: Long,
        label: String,
        confidence: Float,
        isLocal: Boolean,
        view: FloatArray,
        projection: FloatArray,
    ): TargetOverlayView.Target? {
        if (anchor.trackingState != TrackingState.TRACKING) return null
        return projectPoint(camera, anchor.pose.translation, id, label, confidence, isLocal, view, projection)
    }

    private fun projectPoint(
        camera: Camera,
        point: FloatArray,
        id: Long,
        label: String,
        confidence: Float,
        isLocal: Boolean,
        view: FloatArray,
        projection: FloatArray,
    ): TargetOverlayView.Target? {
        if (point.size < 3 || !point.take(3).all { it.isFinite() }) return null
        val world = floatArrayOf(point[0], point[1], point[2], 1f)
        val cameraV = FloatArray(4)
        val clip = FloatArray(4)
        Matrix.multiplyMV(cameraV, 0, view, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projection, 0, cameraV, 0)

        val inFront = cameraV[2] < -0.05f
        val bearing = atan2(cameraV[0], -cameraV[2])
        var x = Float.NaN
        var y = Float.NaN
        if (inFront && kotlin.math.abs(clip[3]) > 1e-5f) {
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            x = (ndcX + 1f) * 0.5f * width
            y = (1f - ndcY) * 0.5f * height
        }

        val dx = point[0] - camera.pose.tx()
        val dy = point[1] - camera.pose.ty()
        val dz = point[2] - camera.pose.tz()
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        return TargetOverlayView.Target(
            id = id,
            screenX = x,
            screenY = y,
            inFront = inFront,
            bearingRad = bearing,
            distanceM = distance,
            label = label,
            confidence = confidence,
            isLocal = isLocal,
        )
    }

    private fun newTargetId(): Long =
        SystemClock.elapsedRealtimeNanos() xor (usernameProvider().hashCode().toLong() shl 32)

    private fun smoothPoint(old: FloatArray, fresh: FloatArray, alpha: Float): FloatArray =
        FloatArray(3) { i -> old.getOrElse(i) { 0f } * (1f - alpha) + fresh.getOrElse(i) { 0f } * alpha }

    private fun shortTargetId(id: Long): String =
        java.lang.Long.toHexString(id).takeLast(3).uppercase(Locale.US).padStart(3, '0')

    private fun detachAnchorsAndTracks() {
        val locals: List<Anchor>
        val remotes: List<Anchor>
        synchronized(targetLock) {
            locals = localTargets.values.map { it.anchor }
            remotes = remoteTargets.values.map { it.anchor }
            localTargets.clear()
            remoteTargets.clear()
            localVehicles.clear()
            remoteVehicles.clear()
        }
        locals.forEach { anchor -> runCatching { anchor.detach() } }
        remotes.forEach { anchor -> runCatching { anchor.detach() } }
        overlay.setTargets(emptyList())
    }

    private fun pointDistance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun errorText(t: Throwable): String {
        val parts = ArrayList<String>(3)
        var current: Throwable? = t
        repeat(3) {
            val c = current ?: return@repeat
            val item = buildString {
                append(c.javaClass.simpleName)
                c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
            if (item !in parts) parts += item
            current = c.cause
        }
        return parts.joinToString(" <- ").ifBlank { t.javaClass.name }
    }

    companion object {
        private const val TAP_DEPTH_MIN_TOLERANCE_M = 0.18f
        private const val TAP_DEPTH_TOLERANCE_RATIO = 0.06f
        private const val MAX_POI_DISTANCE_M = 30f
        private const val SYNC_HINT_INTERVAL_MS = 9000L

        private const val SURFACE_REFERENCE_MAX_WIDTH = 1440
        private const val SURFACE_RESOLVE_INTERVAL_MS = 420L
        private const val SURFACE_RESOLVE_BATCH = 2
        private const val MAX_SURFACE_CORRECTION_M = 0.65f
        private const val REMOTE_PROVISIONAL_REANCHOR_M = 0.025f
        private const val SURFACE_CORRECTION_DEADBAND_M = 0.010f
        private const val SURFACE_ONE_SHOT_MAX_M = 0.045f
        private const val SURFACE_ONE_SHOT_CONFIDENCE = 0.42f
        private const val SURFACE_ONE_SHOT_INLIERS = 10
        private const val SURFACE_VOTE_CONSISTENCY_M = 0.065f
        private const val SURFACE_REQUIRED_VOTES = 2
        private const val SURFACE_RESHARE_MIN_M = 0.015f
        private const val SURFACE_RESHARE_INTERVAL_MS = 800L

        private const val AUTO_CAR_PREFIX = "AUTO:CAR:"
        private const val VEHICLE_DETECT_INTERVAL_MS = 450L
        private const val VEHICLE_METRIC_BUDGET = 5000
        private const val VEHICLE_ASSOCIATION_M = 3.0f
        private const val VEHICLE_SMOOTH_ALPHA = 0.38f
        private const val LOCAL_VEHICLE_TTL_MS = 2_200L
        private const val REMOTE_VEHICLE_TTL_MS = 3_000L
    }
}
