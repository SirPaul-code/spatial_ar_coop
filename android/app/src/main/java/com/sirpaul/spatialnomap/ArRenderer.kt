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
        val point: FloatArray,
        val owner: String,
        val confidence: Float,
    )

    @Volatile var session: Session? = null
    @Volatile var sessionResumed: Boolean = false

    private val background = CameraBackgroundRenderer()
    private val pendingTap = AtomicReference<FloatArray?>(null)
    private val remoteTargetRequest = AtomicReference<RemoteTargetRequest?>(null)
    private val clearTargetsRequested = AtomicBoolean(false)
    private val trackingGate = TrackingStabilityGate(acquireMs = 300L, lossMs = 1000L)

    private var remoteAnchor: Anchor? = null
    private var remoteAnchorId = Long.MIN_VALUE
    private var remoteOwner = ""
    private var remoteConfidence = 0f

    private var localAnchor: Anchor? = null
    private var localPoiId = Long.MIN_VALUE
    private var localOwner = ""

    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastFrameError = ""
    private var lastFrameErrorAtMs = 0L

    init {
        overlay.onSceneTap = { x, y -> queueTap(x, y) }
    }

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(floatArrayOf(x, y))
    }

    fun setRemoteTarget(id: Long, pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        remoteTargetRequest.set(
            pointLocalWorld?.let {
                RemoteTargetRequest(id, it.copyOf(3), owner, confidence)
            },
        )
        if (pointLocalWorld == null && localAnchor == null) overlay.setTarget(null)
    }

    fun setRemoteTarget(pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        setRemoteTarget(0L, pointLocalWorld, owner, confidence)
    }

    fun clearTargets() {
        remoteTargetRequest.set(null)
        clearTargetsRequested.set(true)
        pendingTap.set(null)
        overlay.setTarget(null)
    }

    fun detachSession() {
        sessionResumed = false
        session = null
        textureBoundSession = null
        pendingTap.set(null)
        remoteTargetRequest.set(null)
        clearTargetsRequested.set(false)
        detachAnchors()
        lastCaptureNs = 0L
        lastFrameError = ""
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
            applyRemoteTargetRequest(s, tracking)

            when (trackingGate.update(tracking, SystemClock.elapsedRealtime())) {
                true -> status("AR tracking")
                false -> status("AR PAUSED / ${camera.trackingFailureReason}")
                null -> Unit
            }
            if (!tracking) return

            handleTap(s, frame, camera)
            captureIfDue(frame, camera)
            projectActiveTarget(camera)
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
            status("SYNCING — keep both cameras on overlapping detail until READY")
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

        // A new local ping is the active room POI. Keep a real local ARCore anchor
        // so the owner sees exactly what was selected, not just the remote phone.
        runCatching { localAnchor?.detach() }
        runCatching { remoteAnchor?.detach() }
        remoteAnchor = null
        remoteAnchorId = Long.MIN_VALUE
        remoteTargetRequest.set(null)
        remoteOwner = ""
        remoteConfidence = 0f

        localAnchor = newAnchor
        localPoiId = SystemClock.elapsedRealtimeNanos()
        localOwner = usernameProvider()

        val p = newAnchor.pose.translation
        if (coordinator.sendPoi(localPoiId, p, localOwner)) {
            status("POI sent")
        } else {
            runCatching { newAnchor.detach() }
            localAnchor = null
            localPoiId = Long.MIN_VALUE
            status("POI blocked: spatial fusion is not ready")
        }
    }

    /**
     * For one POI id, create the receiving ARCore anchor exactly once. A later
     * transform refinement must never drag an already-established physical anchor
     * around the room. Only a new POI id is allowed to replace it.
     */
    private fun applyRemoteTargetRequest(session: Session, tracking: Boolean) {
        val request = remoteTargetRequest.get()
        if (request == null) {
            if (remoteAnchor != null) {
                runCatching { remoteAnchor?.detach() }
                remoteAnchor = null
                remoteAnchorId = Long.MIN_VALUE
                remoteOwner = ""
                remoteConfidence = 0f
            }
            return
        }
        if (!tracking) return

        remoteOwner = request.owner
        remoteConfidence = request.confidence
        if (remoteAnchor != null && request.id == remoteAnchorId) return

        val replacement = runCatching {
            session.createAnchor(Pose.makeTranslation(request.point))
        }.getOrNull() ?: return

        val old = remoteAnchor
        remoteAnchor = replacement
        remoteAnchorId = request.id
        runCatching { old?.detach() }
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

    private fun projectActiveTarget(camera: Camera) {
        val remote = remoteAnchor
        val local = localAnchor
        val anchor: Anchor
        val owner: String
        val confidence: Float

        if (remote != null && remote.trackingState == TrackingState.TRACKING) {
            anchor = remote
            owner = remoteOwner
            confidence = remoteConfidence
        } else if (local != null && local.trackingState == TrackingState.TRACKING) {
            anchor = local
            owner = if (localOwner.isBlank()) "YOU" else "YOU • $localOwner"
            confidence = coordinator.quality().confidence
        } else {
            overlay.setTarget(null)
            return
        }

        val p = anchor.pose.translation
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 500f)

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
        overlay.setTarget(
            TargetOverlayView.Target(
                x,
                y,
                inFront,
                bearing,
                distance,
                owner,
                confidence,
            ),
        )
    }

    private fun detachAnchors() {
        runCatching { localAnchor?.detach() }
        runCatching { remoteAnchor?.detach() }
        localAnchor = null
        remoteAnchor = null
        localPoiId = Long.MIN_VALUE
        remoteAnchorId = Long.MIN_VALUE
        localOwner = ""
        remoteOwner = ""
        remoteConfidence = 0f
        overlay.setTarget(null)
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
    }
}
