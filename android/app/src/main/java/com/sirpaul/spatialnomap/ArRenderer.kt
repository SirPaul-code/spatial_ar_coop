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
import kotlin.math.min
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

    /** Dynamic vehicle state. Never turn this into a permanent ARCore Anchor. */
    private class DynamicTarget(
        var point: FloatArray,
        var velocity: FloatArray,
        var worldBox: VehicleDetector.WorldBox?,
        var owner: String,
        var label: String,
        var confidence: Float,
        var lastSeenMs: Long,
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
    private val stableAr = StableArP2pBridge(context)

    private val vehicleDetector = VehicleDetector(context)
    private val pendingVehicleDetections = AtomicReference<List<VehicleDetector.Vehicle>?>(null)
    private val pendingVehicleError = AtomicReference<String?>(null)

    /**
     * Legacy surface bootstrap remains only as a fail-closed fallback and as the first local visual
     * bootstrap for a remote POI. Once StableAR has immutable local root evidence for a target, this
     * resolver no longer competes with it. Shared-world alignment never enters either path.
     */
    private val surfaceResolverExecutor = Executors.newSingleThreadExecutor()
    private val surfaceResolveBusy = AtomicBoolean(false)
    private val surfaceCorrections = ConcurrentLinkedQueue<SurfaceCorrection>()

    private val localTargets = LinkedHashMap<Long, LocalTarget>()
    private val remoteTargets = LinkedHashMap<Long, RemoteTarget>()
    private val localVehicles = LinkedHashMap<Long, DynamicTarget>()
    private val remoteVehicles = LinkedHashMap<Long, DynamicTarget>()

    /** Detector-local id -> room/shared id. */
    private val detectorTrackToSharedId = LinkedHashMap<Int, Long>()

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
        stableAr.invalidateAsync()
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
        stableAr.closeWorkersAsync()
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
                stableAr.onSession(s)
            }
            if (!sessionResumed || session !== s) return

            val frame = s.update()
            background.draw(frame)
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING

            if (clearTargetsRequested.getAndSet(false)) {
                stableAr.clearOnOwnerThread()
                detachAnchorsAndTracks()
            }
            applyRemoteTargetRequests(s, tracking)
            stableAr.onFrame(frame)

            when (trackingGate.update(tracking, SystemClock.elapsedRealtime())) {
                true -> status("AR tracking")
                false -> status("AR PAUSED / ${camera.trackingFailureReason}")
                null -> Unit
            }
            if (!tracking) return

            applyStableArRefinements()
            applySurfaceCorrections(s, frame)
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
            if (!hitDistance.isFinite() || hitDistance < MIN_POI_DISTANCE_M || hitDistance > MAX_POI_DISTANCE_M) continue
            if (trackable is DepthPoint && hitDistance > MAX_UNCORROBORATED_DEPTH_DISTANCE_M) continue

            val disagreement = metricWorld?.let { pointDistance(it, hitPoint) }
            val metricConsistent = disagreement != null && depthTolerance != null && disagreement <= depthTolerance
            val typePenalty = when (trackable) {
                is Plane -> 0f
                is Point -> 0.08f
                is DepthPoint -> 0.16f
                else -> 0.25f
            }
            val score = hitDistance + typePenalty - if (metricConsistent) METRIC_HIT_BONUS_M else 0f
            if (score < bestScore) {
                bestScore = score
                bestHit = hit
            }
        }

        var newAnchor = bestHit?.let { runCatching { it.createAnchor() }.getOrNull() }
        if (newAnchor == null && metricWorld != null && metricDistance != null &&
            metricDistance.isFinite() && metricDistance in MIN_POI_DISTANCE_M..MAX_METRIC_ONLY_POI_DISTANCE_M
        ) {
            newAnchor = runCatching { session.createAnchor(Pose.makeTranslation(metricWorld)) }.getOrNull()
        }

        if (newAnchor == null) {
            status("No reliable tracked surface at the tap. Move slightly and tap again.")
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
        val stableSeeded = runCatching {
            stableAr.seedFromWorldPoint(frame, id, point, imagePixel)
        }.getOrDefault(false)

        val referenceFrame = FrameCapture.capture(
            frame = frame,
            camera = camera,
            maxWidth = SURFACE_REFERENCE_MAX_WIDTH,
            sensors = sensorSnapshotProvider(),
        )
        val surfaceReference = referenceFrame?.let { captured ->
            val dims = camera.imageIntrinsics.imageDimensions
            val sx = captured.intrinsics.width.toFloat() /
                dims.getOrElse(0) { captured.intrinsics.width }.coerceAtLeast(1)
            val sy = captured.intrinsics.height.toFloat() /
                dims.getOrElse(1) { captured.intrinsics.height }.coerceAtLeast(1)
            SurfaceTargetReference(captured, floatArrayOf(imagePixel[0] * sx, imagePixel[1] * sy))
        }
        val pointToSend = stableAr.worldPoint(id) ?: point

        val sent = if (surfaceReference != null) {
            lastCaptureNs = System.nanoTime()
            coordinator.sendSurfacePoi(id, pointToSend, owner, surfaceReference.frame)
        } else {
            coordinator.sendPoi(id, pointToSend, owner)
        }

        if (sent) {
            synchronized(targetLock) {
                localTargets[id] = LocalTarget(newAnchor, owner, surfaceReference)
            }
            status(
                when {
                    stableSeeded -> "POI sent • StableAR/XFeat material lock armed"
                    surfaceReference != null -> "POI sent • surface lock armed"
                    else -> "POI sent"
                },
            )
        } else {
            if (stableSeeded) stableAr.remove(id)
            runCatching { newAnchor.detach() }
            status("Target blocked: spatial alignment is not ready")
        }
    }

    private fun applyRemoteTargetRequests(session: Session, tracking: Boolean) {
        if (!tracking) return

        while (true) {
            val request = remoteTargetRequests.poll() ?: break
            if (request.owner.startsWith(AUTO_CAR_PREFIX)) {
                applyRemoteVehicleRequest(request)
                continue
            }

            if (request.point == null) {
                val removed = synchronized(targetLock) { remoteTargets.remove(request.id) }
                stableAr.remove(request.id)
                SurfaceTargetRegistry.remove(request.id)
                runCatching { removed?.anchor?.detach() }
                continue
            }

            val existing = synchronized(targetLock) { remoteTargets[request.id] }
            if (existing != null) {
                existing.owner = request.owner
                existing.confidence = request.confidence
                if (existing.surface == null && request.surface != null) existing.surface = request.surface

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

    /**
     * Room-level vehicle reservation/deduplication.
     *
     * Both phones may detect the same car before either receives the other's first
     * packet. Active duplicates converge deterministically to the unsigned-lowest
     * shared id. A stale owner loses its reservation after a short lease so a peer
     * that still sees a moving vehicle can take ownership without waiting for the
     * longer display-memory TTL.
     */
    private fun applyRemoteVehicleRequest(request: RemoteTargetRequest) {
        val now = System.currentTimeMillis()
        val cleanOwner = request.owner.removePrefix(AUTO_CAR_PREFIX).ifBlank { "Peer" }
        synchronized(targetLock) {
            val incomingPoint = request.point
            if (incomingPoint == null) {
                remoteVehicles.remove(request.id)
                return
            }

            val existingSameId = remoteVehicles[request.id]
            if (existingSameId != null) {
                updateRemoteVehicle(existingSameId, incomingPoint, cleanOwner, request.confidence, now)
                return
            }

            val localDuplicate = nearestDynamicTrack(
                localVehicles,
                incomingPoint,
                now,
                CROSS_DEVICE_BASE_GATE_M,
                CROSS_DEVICE_MAX_GATE_M,
            )
            if (localDuplicate != null) {
                val (localId, localTrack) = localDuplicate
                val localLeaseActive = now - localTrack.lastSeenMs <= VEHICLE_OWNERSHIP_LEASE_MS
                if (localLeaseActive && VehicleTrackPolicy.winnerId(localId, request.id) == localId) {
                    return
                }
                localVehicles.remove(localId)
                detectorTrackToSharedId.entries.removeIf { it.value == localId }
            }

            val otherRemote = nearestDynamicTrack(
                remoteVehicles,
                incomingPoint,
                now,
                CROSS_DEVICE_BASE_GATE_M,
                CROSS_DEVICE_MAX_GATE_M,
                excludeId = request.id,
            )
            if (otherRemote != null) {
                val otherId = otherRemote.first
                if (VehicleTrackPolicy.winnerId(otherId, request.id) == otherId) return
                remoteVehicles.remove(otherId)
            }

            val velocity = FloatArray(3)
            remoteVehicles[request.id] = DynamicTarget(
                point = incomingPoint.copyOf(3),
                velocity = velocity,
                worldBox = defaultVehicleBox(incomingPoint, velocity, "CAR"),
                owner = cleanOwner,
                label = "CAR",
                confidence = request.confidence,
                lastSeenMs = now,
            )
        }
    }

    private fun updateRemoteVehicle(
        track: DynamicTarget,
        incomingPoint: FloatArray,
        owner: String,
        confidence: Float,
        now: Long,
    ) {
        val dt = ((now - track.lastSeenMs).coerceAtLeast(1L) / 1000f).coerceIn(0.02f, 2f)
        val measuredVelocity = FloatArray(3) { i ->
            (incomingPoint.getOrElse(i) { 0f } - track.point.getOrElse(i) { 0f }) / dt
        }
        limitVelocity(measuredVelocity, MAX_VEHICLE_SPEED_MPS)
        track.velocity = smoothPoint(track.velocity, measuredVelocity, REMOTE_VELOCITY_ALPHA)
        limitVelocity(track.velocity, MAX_VEHICLE_SPEED_MPS)
        track.point = smoothPoint(track.point, incomingPoint, REMOTE_POSITION_ALPHA)
        track.owner = owner
        track.confidence = max(track.confidence * 0.35f, confidence)
        track.lastSeenMs = now
        track.worldBox = defaultVehicleBox(track.point, track.velocity, track.label, track.worldBox)
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
                if (stableAr.visualTrackingActive(id)) return@forEach
                val ref = target.surface ?: return@forEach
                if (target.anchor.trackingState == TrackingState.TRACKING) {
                    jobs += SurfaceResolveJob(id, true, ref, target.anchor.pose.translation.copyOf())
                }
            }
            remoteTargets.forEach { (id, target) ->
                if (stableAr.visualTrackingActive(id)) return@forEach
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

    private fun applySurfaceCorrections(session: Session, frame: Frame) {
        while (true) {
            val correction = surfaceCorrections.poll() ?: break
            if (correction.local) applyLocalSurfaceCorrection(session, frame, correction)
            else applyRemoteSurfaceCorrection(session, frame, correction)
        }
    }

    private fun applyLocalSurfaceCorrection(session: Session, frame: Frame, correction: SurfaceCorrection) {
        if (stableAr.visualTrackingActive(correction.id)) return
        val target = synchronized(targetLock) { localTargets[correction.id] } ?: return
        val oldPoint = target.anchor.pose.translation
        val coarse = correction.result
        val result = target.surface?.let { reference ->
            runCatching {
                SurfaceEdgeSnapRefiner.refine(reference, correction.currentFrame, coarse, oldPoint)
            }.getOrNull()
        } ?: coarse
        val delta = pointDistance(oldPoint, result.pointWorld)
        if (!delta.isFinite() || delta > MAX_SURFACE_CORRECTION_M) return
        if (delta < SURFACE_CORRECTION_DEADBAND_M) {
            target.surfaceVerified = true
            target.pendingCorrection = null
            target.correctionVotes = 0
            runCatching { stableAr.seedFromWorldPoint(frame, correction.id, result.pointWorld) }
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
        runCatching { stableAr.seedFromWorldPoint(frame, correction.id, result.pointWorld) }

        val now = System.currentTimeMillis()
        if (delta >= SURFACE_RESHARE_MIN_M && now - target.lastSurfaceShareMs >= SURFACE_RESHARE_INTERVAL_MS) {
            target.lastSurfaceShareMs = now
            coordinator.sendSurfacePoi(correction.id, replacement.pose.translation, target.owner, refreshed.frame)
        }
    }

    private fun applyRemoteSurfaceCorrection(session: Session, frame: Frame, correction: SurfaceCorrection) {
        if (stableAr.visualTrackingActive(correction.id)) return
        val target = synchronized(targetLock) { remoteTargets[correction.id] } ?: return
        val oldPoint = target.anchor.pose.translation
        val coarse = correction.result
        val result = target.surface?.let { reference ->
            runCatching {
                SurfaceEdgeSnapRefiner.refine(reference, correction.currentFrame, coarse, oldPoint)
            }.getOrNull()
        } ?: coarse
        val delta = pointDistance(oldPoint, result.pointWorld)
        if (!delta.isFinite() || delta > MAX_SURFACE_CORRECTION_M) return
        if (delta < SURFACE_CORRECTION_DEADBAND_M) {
            target.surfaceVerified = true
            target.pendingCorrection = null
            target.correctionVotes = 0
            runCatching { stableAr.seedFromWorldPoint(frame, correction.id, result.pointWorld) }
            return
        }
        if (!voteForCorrection(target, result.pointWorld, result.confidence, result.visualInliers, delta)) return

        val replacement = runCatching { session.createAnchor(Pose.makeTranslation(result.pointWorld)) }.getOrNull() ?: return
        val old = target.anchor
        target.anchor = replacement
        target.surfaceVerified = true
        target.pendingCorrection = null
        target.correctionVotes = 0
        target.surface = SurfaceTargetReference(correction.currentFrame, result.matchedPixel.copyOf())
        runCatching { old.detach() }
        runCatching { stableAr.seedFromWorldPoint(frame, correction.id, result.pointWorld) }
    }

    private fun applyStableArRefinements() {
        val now = System.currentTimeMillis()
        for (refinement in stableAr.drainRefinements()) {
            val local = synchronized(targetLock) { localTargets[refinement.productTargetId] }
            if (local != null) {
                local.surfaceVerified = true
                if (now - local.lastSurfaceShareMs < SURFACE_RESHARE_INTERVAL_MS) continue
                val delta = pointDistance(local.anchor.pose.translation, refinement.worldPoint)
                if (!delta.isFinite() || delta < SURFACE_RESHARE_MIN_M) continue
                local.lastSurfaceShareMs = now
                val sent = local.surface?.let { reference ->
                    coordinator.sendSurfacePoi(
                        refinement.productTargetId,
                        refinement.worldPoint,
                        local.owner,
                        reference.frame,
                    )
                } ?: coordinator.sendPoi(refinement.productTargetId, refinement.worldPoint, local.owner)
                if (sent) status("POI refined • StableAR material lock")
            } else {
                synchronized(targetLock) {
                    remoteTargets[refinement.productTargetId]?.surfaceVerified = true
                }
            }
        }
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
        val acceptedObservations = ArrayList<FloatArray>()

        for (vehicle in detections.sortedByDescending { it.confidence }) {
            // Suppress duplicate boxes from one detector frame before room association.
            if (acceptedObservations.any { pointDistance(it, vehicle.pointWorld) <= LOCAL_DETECTION_MERGE_M }) continue
            acceptedObservations += vehicle.pointWorld

            val remoteReservation = synchronized(targetLock) {
                nearestDynamicTrack(
                    remoteVehicles.filterValues { now - it.lastSeenMs <= VEHICLE_OWNERSHIP_LEASE_MS },
                    vehicle.pointWorld,
                    now,
                    CROSS_DEVICE_BASE_GATE_M,
                    CROSS_DEVICE_MAX_GATE_M,
                )
            }
            if (remoteReservation != null) {
                synchronized(targetLock) {
                    detectorTrackToSharedId.remove(vehicle.trackId)?.let { staleId ->
                        localVehicles.remove(staleId)
                    }
                }
                continue
            }

            var id: Long? = synchronized(targetLock) {
                detectorTrackToSharedId[vehicle.trackId]?.takeIf { localVehicles.containsKey(it) }
            }
            if (id == null) {
                id = synchronized(targetLock) {
                    nearestDynamicTrack(
                        localVehicles,
                        vehicle.pointWorld,
                        now,
                        LOCAL_VEHICLE_BASE_GATE_M,
                        LOCAL_VEHICLE_MAX_GATE_M,
                    )?.first
                }
            }

            val isNew = id == null
            val sharedId = id ?: newTargetId()
            val existing = synchronized(targetLock) { localVehicles[sharedId] }
            val predicted = existing?.let {
                VehicleTrackPolicy.predict(it.point, it.velocity, it.lastSeenMs, now, VEHICLE_COAST_MS)
            }
            val point = if (predicted == null) vehicle.pointWorld.copyOf(3)
            else smoothPoint(predicted, vehicle.pointWorld, LOCAL_POSITION_ALPHA)
            val velocity = if (existing == null) vehicle.velocityWorld.copyOf(3)
            else smoothPoint(existing.velocity, vehicle.velocityWorld, LOCAL_VELOCITY_ALPHA)
            limitVelocity(velocity, MAX_VEHICLE_SPEED_MPS)
            val worldBox = (vehicle.worldBox ?: existing?.worldBox ?: defaultVehicleBox(point, velocity, vehicle.label))
                ?.let { moveBoxCenter(it, point, velocity) }

            synchronized(targetLock) {
                localVehicles[sharedId] = DynamicTarget(
                    point = point,
                    velocity = velocity,
                    worldBox = worldBox,
                    owner = owner,
                    label = vehicle.label,
                    confidence = vehicle.confidence,
                    lastSeenMs = now,
                )
                detectorTrackToSharedId[vehicle.trackId] = sharedId
            }
            matched += sharedId
            coordinator.sendPoi(sharedId, point, "$AUTO_CAR_PREFIX$owner")
            if (isNew) status("Vehicle tracked • shared 3D track reserved")
        }
    }

    private fun expireVehicleTracks() {
        val now = System.currentTimeMillis()
        synchronized(targetLock) {
            val removedLocal = localVehicles
                .filterValues { now - it.lastSeenMs > VEHICLE_MEMORY_TTL_MS }
                .keys.toList()
            removedLocal.forEach { id -> localVehicles.remove(id) }
            if (removedLocal.isNotEmpty()) {
                detectorTrackToSharedId.entries.removeIf { it.value in removedLocal }
            }
            remoteVehicles
                .filterValues { now - it.lastSeenMs > VEHICLE_MEMORY_TTL_MS }
                .keys.toList()
                .forEach { remoteVehicles.remove(it) }
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
        val now = System.currentTimeMillis()

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
            val stablePoint = stableAr.worldPoint(id)
            if (stablePoint != null) {
                projectPoint(
                    camera, stablePoint, id, "YOU • ${shortTargetId(id)}",
                    coordinator.quality().confidence, true, view, projection, null,
                )?.let { projected += it }
            } else {
                projectAnchor(
                    camera, target.anchor, id, "YOU • ${shortTargetId(id)}",
                    coordinator.quality().confidence, true, view, projection,
                )?.let { projected += it }
            }
        }
        for ((id, target) in remoteSnapshot) {
            val owner = target.owner.ifBlank { "PEER" }
            val stablePoint = stableAr.worldPoint(id)
            if (stablePoint != null) {
                projectPoint(
                    camera, stablePoint, id, "$owner • ${shortTargetId(id)}",
                    target.confidence, false, view, projection, null,
                )?.let { projected += it }
            } else {
                projectAnchor(
                    camera, target.anchor, id, "$owner • ${shortTargetId(id)}",
                    target.confidence, false, view, projection,
                )?.let { projected += it }
            }
        }
        for ((id, target) in localVehicleSnapshot) {
            projectDynamicTarget(camera, target, id, "${target.label} • YOU", true, now, view, projection)
                ?.let { projected += it }
        }
        for ((id, target) in remoteVehicleSnapshot) {
            projectDynamicTarget(
                camera,
                target,
                id,
                "${target.label} • ${target.owner.ifBlank { "PEER" }}",
                false,
                now,
                view,
                projection,
            )?.let { projected += it }
        }
        overlay.setTargets(projected)
    }

    private fun projectDynamicTarget(
        camera: Camera,
        target: DynamicTarget,
        id: Long,
        label: String,
        isLocal: Boolean,
        now: Long,
        view: FloatArray,
        projection: FloatArray,
    ): TargetOverlayView.Target? {
        val predicted = VehicleTrackPolicy.predict(target.point, target.velocity, target.lastSeenMs, now, VEHICLE_COAST_MS)
        val age = (now - target.lastSeenMs).coerceAtLeast(0L)
        val confidenceDecay = (1f - min(0.72f, age / VEHICLE_MEMORY_TTL_MS.toFloat() * 0.72f)).coerceAtLeast(0.20f)
        val confidence = target.confidence * confidenceDecay
        val box = target.worldBox?.let { moveBoxCenter(it, predicted, target.velocity) }
            ?: defaultVehicleBox(predicted, target.velocity, target.label)
        return projectPoint(camera, predicted, id, label, confidence, isLocal, view, projection, box)
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
        return projectPoint(camera, anchor.pose.translation, id, label, confidence, isLocal, view, projection, null)
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
        worldBox: VehicleDetector.WorldBox?,
    ): TargetOverlayView.Target? {
        if (point.size < 3 || !point.take(3).all { it.isFinite() }) return null
        val projectedCenter = projectWorldPoint(point, view, projection)
        val cameraV = projectedCenter?.camera ?: return null
        val clip = projectedCenter.clip
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
            boxCorners = worldBox?.let { projectWorldBox(it, view, projection) },
        )
    }

    private data class ProjectedWorldPoint(val camera: FloatArray, val clip: FloatArray)

    private fun projectWorldPoint(point: FloatArray, view: FloatArray, projection: FloatArray): ProjectedWorldPoint? {
        if (point.size < 3 || !point.take(3).all { it.isFinite() }) return null
        val world = floatArrayOf(point[0], point[1], point[2], 1f)
        val cameraV = FloatArray(4)
        val clip = FloatArray(4)
        Matrix.multiplyMV(cameraV, 0, view, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projection, 0, cameraV, 0)
        if (!cameraV.take(4).all { it.isFinite() } || !clip.take(4).all { it.isFinite() }) return null
        return ProjectedWorldPoint(cameraV, clip)
    }

    /** Project eight world-space OBB corners into the camera overlay. */
    private fun projectWorldBox(
        box: VehicleDetector.WorldBox,
        view: FloatArray,
        projection: FloatArray,
    ): FloatArray? {
        val c = box.centerWorld
        val f = horizontalUnit(box.forwardWorld) ?: return null
        val r = floatArrayOf(-f[2], 0f, f[0])
        val out = FloatArray(16)
        for (index in 0 until 8) {
            val sl = if (index and 1 == 0) -1f else 1f
            val sw = if (index and 2 == 0) -1f else 1f
            val sh = if (index and 4 == 0) -1f else 1f
            val p = floatArrayOf(
                c.getOrElse(0) { 0f } + f[0] * box.halfLengthM * sl + r[0] * box.halfWidthM * sw,
                c.getOrElse(1) { 0f } + box.halfHeightM * sh,
                c.getOrElse(2) { 0f } + f[2] * box.halfLengthM * sl + r[2] * box.halfWidthM * sw,
            )
            val projected = projectWorldPoint(p, view, projection) ?: return null
            if (projected.camera[2] >= -0.05f || kotlin.math.abs(projected.clip[3]) <= 1e-5f) return null
            val ndcX = projected.clip[0] / projected.clip[3]
            val ndcY = projected.clip[1] / projected.clip[3]
            val x = (ndcX + 1f) * 0.5f * width
            val y = (1f - ndcY) * 0.5f * height
            if (!x.isFinite() || !y.isFinite()) return null
            out[index * 2] = x
            out[index * 2 + 1] = y
        }
        return out
    }

    private fun moveBoxCenter(
        box: VehicleDetector.WorldBox,
        center: FloatArray,
        velocity: FloatArray,
    ): VehicleDetector.WorldBox {
        val movingAxis = horizontalUnit(velocity)
        var axis = movingAxis ?: horizontalUnit(box.forwardWorld) ?: floatArrayOf(1f, 0f, 0f)
        val previous = horizontalUnit(box.forwardWorld)
        if (previous != null && dot3(axis, previous) < 0f) axis = FloatArray(3) { -axis[it] }
        return box.copy(centerWorld = center.copyOf(3), forwardWorld = axis)
    }

    private fun defaultVehicleBox(
        center: FloatArray,
        velocity: FloatArray,
        label: String,
        previous: VehicleDetector.WorldBox? = null,
    ): VehicleDetector.WorldBox {
        var axis = horizontalUnit(velocity) ?: previous?.forwardWorld?.let(::horizontalUnit) ?: floatArrayOf(1f, 0f, 0f)
        previous?.forwardWorld?.let { prior ->
            if (dot3(axis, prior) < 0f) axis = FloatArray(3) { -axis[it] }
        }
        val dims = when (label.uppercase(Locale.US)) {
            "BUS" -> floatArrayOf(4.8f, 1.15f, 1.45f)
            "TRUCK" -> floatArrayOf(3.2f, 1.05f, 1.20f)
            else -> floatArrayOf(2.15f, 0.90f, 0.78f)
        }
        return VehicleDetector.WorldBox(
            centerWorld = center.copyOf(3),
            forwardWorld = axis,
            halfLengthM = previous?.halfLengthM ?: dims[0],
            halfWidthM = previous?.halfWidthM ?: dims[1],
            halfHeightM = previous?.halfHeightM ?: dims[2],
        )
    }

    private fun nearestDynamicTrack(
        tracks: Map<Long, DynamicTarget>,
        observation: FloatArray,
        now: Long,
        baseGateM: Float,
        maxGateM: Float,
        excludeId: Long? = null,
    ): Pair<Long, DynamicTarget>? {
        var best: Pair<Long, DynamicTarget>? = null
        var bestDistance = Float.POSITIVE_INFINITY
        for ((id, track) in tracks) {
            if (excludeId != null && id == excludeId) continue
            if (now - track.lastSeenMs > VEHICLE_MEMORY_TTL_MS) continue
            val predicted = VehicleTrackPolicy.predict(track.point, track.velocity, track.lastSeenMs, now, VEHICLE_COAST_MS)
            val distance = pointDistance(predicted, observation)
            val gate = VehicleTrackPolicy.associationGateM(track.velocity, track.lastSeenMs, now, baseGateM, maxGateM)
            if (distance <= gate && distance < bestDistance) {
                bestDistance = distance
                best = id to track
            }
        }
        return best
    }

    private fun horizontalUnit(v: FloatArray): FloatArray? {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val n = sqrt(x * x + z * z)
        if (!n.isFinite() || n < 1e-4f) return null
        return floatArrayOf(x / n, 0f, z / n)
    }

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } +
            a.getOrElse(1) { 0f } * b.getOrElse(1) { 0f } +
            a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    private fun limitVelocity(velocity: FloatArray, maxSpeedMps: Float) {
        val speed = VehicleTrackPolicy.speedMps(velocity)
        if (speed <= maxSpeedMps || speed < 1e-4f) return
        val scale = maxSpeedMps / speed
        repeat(3) { i -> velocity[i] *= scale }
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
            detectorTrackToSharedId.clear()
        }
        locals.forEach { anchor -> runCatching { anchor.detach() } }
        remotes.forEach { anchor -> runCatching { anchor.detach() } }
        overlay.setTargets(emptyList())
    }

    private fun pointDistance(a: FloatArray, b: FloatArray): Float = VehicleTrackPolicy.distance(a, b)

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
        private const val MIN_POI_DISTANCE_M = 0.08f
        private const val MAX_POI_DISTANCE_M = 30f
        private const val MAX_UNCORROBORATED_DEPTH_DISTANCE_M = 8f
        private const val MAX_METRIC_ONLY_POI_DISTANCE_M = 8f
        private const val METRIC_HIT_BONUS_M = 0.12f
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
        private const val LOCAL_DETECTION_MERGE_M = 1.45f
        private const val LOCAL_VEHICLE_BASE_GATE_M = 1.9f
        private const val LOCAL_VEHICLE_MAX_GATE_M = 4.8f
        private const val CROSS_DEVICE_BASE_GATE_M = 2.1f
        private const val CROSS_DEVICE_MAX_GATE_M = 5.2f
        private const val VEHICLE_OWNERSHIP_LEASE_MS = 2_800L
        private const val VEHICLE_COAST_MS = 2_600L
        private const val VEHICLE_MEMORY_TTL_MS = 6_500L
        private const val LOCAL_POSITION_ALPHA = 0.62f
        private const val LOCAL_VELOCITY_ALPHA = 0.60f
        private const val REMOTE_POSITION_ALPHA = 0.72f
        private const val REMOTE_VELOCITY_ALPHA = 0.36f
        private const val MAX_VEHICLE_SPEED_MPS = 75f
    }
}
