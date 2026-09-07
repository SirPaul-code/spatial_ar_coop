package com.sirpaul.spatialnomap

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class ArRenderer(
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
    )

    private data class LocalTarget(
        val anchor: Anchor,
        val owner: String,
    )

    private data class RemoteTarget(
        val anchor: Anchor,
        val owner: String,
        val confidence: Float,
    )

    @Volatile var session: Session? = null
    @Volatile var sessionResumed: Boolean = false

    private val background = CameraBackgroundRenderer()
    private val pendingTap = AtomicReference<FloatArray?>(null)
    private val remoteTargetRequests = ConcurrentLinkedQueue<RemoteTargetRequest>()
    private val clearTargetsRequested = AtomicBoolean(false)
    private val trackingGate = TrackingStabilityGate(acquireMs = 300L, lossMs = 1000L)
    private val targetLock = Any()

    /**
     * Targets are intentionally additive. A new tap must never detach an older
     * anchor: every manual ping gets its own ARCore anchor and remains pinned until
     * the user explicitly clears the room. The same representation can later be
     * fed by person/car detections without changing the rendering path.
     */
    private val localTargets = LinkedHashMap<Long, LocalTarget>()
    private val remoteTargets = LinkedHashMap<Long, RemoteTarget>()

    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastFrameError = ""
    private var lastFrameErrorAtMs = 0L
    private var lastSyncHintAtMs = 0L

    init {
        overlay.onSceneTap = { x, y -> queueTap(x, y) }
    }

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(floatArrayOf(x, y))
    }

    /**
     * Network/UI threads only enqueue mutations. ARCore anchor creation happens on
     * the render thread, which keeps target lifetime deterministic and avoids races
     * while a camera frame is being projected.
     */
    fun setRemoteTarget(id: Long, pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        remoteTargetRequests.add(
            RemoteTargetRequest(
                id = id,
                point = pointLocalWorld?.copyOf(3),
                owner = owner,
                confidence = confidence,
            ),
        )
    }

    fun setRemoteTarget(pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        setRemoteTarget(0L, pointLocalWorld, owner, confidence)
    }

    fun clearTargets() {
        remoteTargetRequests.clear()
        clearTargetsRequested.set(true)
        pendingTap.set(null)
        overlay.setTargets(emptyList())
    }

    fun targetCount(): Int = synchronized(targetLock) { localTargets.size + remoteTargets.size }

    fun detachSession() {
        sessionResumed = false
        session = null
        textureBoundSession = null
        pendingTap.set(null)
        remoteTargetRequests.clear()
        clearTargetsRequested.set(false)
        detachAnchors()
        lastCaptureNs = 0L
        lastFrameError = ""
        lastSyncHintAtMs = 0L
        trackingGate.reset()
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
        if (sessionResumed) {
            runCatching { session?.setDisplayGeometry(rotationProvider(), width, height) }
        }
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

            if (clearTargetsRequested.getAndSet(false)) detachAnchors()
            applyRemoteTargetRequests(s, tracking)

            when (trackingGate.update(tracking, SystemClock.elapsedRealtime())) {
                true -> status("AR tracking")
                false -> status("AR PAUSED / ${camera.trackingFailureReason}")
                null -> Unit
            }
            if (!tracking) return

            handleTap(s, frame, camera)
            captureIfDue(frame, camera)
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

    /**
     * A tap is accepted only when ARCore hit testing and metric depth agree, when
     * both are available. This prevents a valid screen tap from silently landing on
     * a different plane several metres behind the intended surface.
     */
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
        if (newAnchor == null && metricWorld != null &&
            metricDistance != null && metricDistance.isFinite() && metricDistance <= MAX_POI_DISTANCE_M
        ) {
            newAnchor = runCatching { session.createAnchor(Pose.makeTranslation(metricWorld)) }.getOrNull()
        }

        if (newAnchor == null) {
            status("No corroborated metric surface at the tap. Move slightly and tap again.")
            return
        }

        var id = SystemClock.elapsedRealtimeNanos()
        synchronized(targetLock) {
            while (localTargets.containsKey(id) || remoteTargets.containsKey(id)) id += 1L
        }
        val owner = usernameProvider()
        val point = newAnchor.pose.translation

        if (coordinator.sendPoi(id, point, owner)) {
            synchronized(targetLock) {
                localTargets[id] = LocalTarget(newAnchor, owner)
            }
            status("POI sent")
        } else {
            runCatching { newAnchor.detach() }
            status("Target blocked: spatial alignment is not ready")
        }
    }

    /**
     * Every remote POI id owns one ARCore anchor. Repeated packets for the same id
     * update metadata but never re-anchor the physical point, so later networking or
     * alignment messages cannot drag an existing target across the scene.
     */
    private fun applyRemoteTargetRequests(session: Session, tracking: Boolean) {
        if (!tracking) return

        while (true) {
            val request = remoteTargetRequests.poll() ?: break
            if (request.point == null) {
                val removed = synchronized(targetLock) { remoteTargets.remove(request.id) }
                runCatching { removed?.anchor?.detach() }
                continue
            }

            val existing = synchronized(targetLock) { remoteTargets[request.id] }
            if (existing != null) {
                synchronized(targetLock) {
                    remoteTargets[request.id] = existing.copy(
                        owner = request.owner,
                        confidence = request.confidence,
                    )
                }
                continue
            }

            val anchor = runCatching {
                session.createAnchor(Pose.makeTranslation(request.point))
            }.getOrNull() ?: continue

            synchronized(targetLock) {
                // Another request for the same id cannot normally race because this
                // function is render-thread-only, but keep the invariant explicit.
                val prior = remoteTargets.putIfAbsent(
                    request.id,
                    RemoteTarget(anchor, request.owner, request.confidence),
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
        lastCaptureNs = now
    }

    /**
     * While alignment is still being acquired, periodically surface one concise
     * instruction through the existing English UI banner. This does not alter any
     * solver threshold; it only tells the operator how to generate useful parallax.
     */
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
        synchronized(targetLock) {
            localSnapshot = localTargets.entries.map { it.key to it.value }
            remoteSnapshot = remoteTargets.entries.map { it.key to it.value }
        }

        val projected = ArrayList<TargetOverlayView.Target>(localSnapshot.size + remoteSnapshot.size)
        for ((id, target) in localSnapshot) {
            projectAnchor(
                camera = camera,
                anchor = target.anchor,
                id = id,
                label = "YOU • ${shortTargetId(id)}",
                confidence = coordinator.quality().confidence,
                isLocal = true,
                view = view,
                projection = projection,
            )?.let { projected += it }
        }
        for ((id, target) in remoteSnapshot) {
            val owner = target.owner.ifBlank { "PEER" }
            projectAnchor(
                camera = camera,
                anchor = target.anchor,
                id = id,
                label = "$owner • ${shortTargetId(id)}",
                confidence = target.confidence,
                isLocal = false,
                view = view,
                projection = projection,
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

        val p = anchor.pose.translation
        val world = floatArrayOf(p[0], p[1], p[2], 1f)
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

        val dx = p[0] - camera.pose.tx()
        val dy = p[1] - camera.pose.ty()
        val dz = p[2] - camera.pose.tz()
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

    private fun shortTargetId(id: Long): String =
        java.lang.Long.toHexString(id).takeLast(3).uppercase(Locale.US).padStart(3, '0')

    private fun detachAnchors() {
        val locals: List<Anchor>
        val remotes: List<Anchor>
        synchronized(targetLock) {
            locals = localTargets.values.map { it.anchor }
            remotes = remoteTargets.values.map { it.anchor }
            localTargets.clear()
            remoteTargets.clear()
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
    }
}
