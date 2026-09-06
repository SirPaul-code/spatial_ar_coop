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
    private var remoteAnchorInputPoint: FloatArray? = null
    private var remoteOwner = ""
    private var remoteConfidence = 0f

    private var localAnchor: Anchor? = null
    private var localPoiId = Long.MIN_VALUE
    private var localOwner = ""
    private var lastLocalSentPoint: FloatArray? = null
    private var lastLocalSentNs = 0L

    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastFrameError = ""
    private var lastFrameErrorAtMs = 0L

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(floatArrayOf(x, y))
    }

    fun setRemoteTarget(id: Long, pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        remoteTargetRequest.set(
            pointLocalWorld?.let {
                RemoteTargetRequest(id, it.copyOf(3), owner, confidence)
            },
        )
        if (pointLocalWorld == null) overlay.setTarget(null)
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
            updateLocalAnchorPose()
            captureIfDue(frame, camera)
            projectRemoteTarget(camera)
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
            status("SYNCING — keep both cameras on overlapping detail until READY")
            return
        }

        var newAnchor: Anchor? = null
        for (hit in frame.hitTest(tap[0], tap[1])) {
            val trackable = hit.trackable
            val usable = when (trackable) {
                is DepthPoint -> true
                is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (!usable) continue
            newAnchor = runCatching { hit.createAnchor() }.getOrNull()
            if (newAnchor != null) break
        }

        if (newAnchor == null) {
            val imagePixel = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.VIEW, tap, Coordinates2d.IMAGE_PIXELS, imagePixel)
            val pointWorld = MetricSupportSampler.pointAtCpuPixel(frame, camera, imagePixel[0], imagePixel[1])
            if (pointWorld != null) {
                newAnchor = runCatching { session.createAnchor(Pose.makeTranslation(pointWorld)) }.getOrNull()
            }
        }

        if (newAnchor == null) {
            status("No reliable metric depth at the tap. Move the phone slightly and tap again.")
            return
        }

        runCatching { localAnchor?.detach() }
        localAnchor = newAnchor
        localPoiId = SystemClock.elapsedRealtimeNanos()
        localOwner = usernameProvider()
        lastLocalSentPoint = null
        lastLocalSentNs = 0L

        val p = newAnchor.pose.translation
        if (coordinator.sendPoi(localPoiId, p, localOwner)) {
            lastLocalSentPoint = p.copyOf()
            lastLocalSentNs = SystemClock.elapsedRealtimeNanos()
            status("POI sent")
        } else {
            runCatching { newAnchor.detach() }
            localAnchor = null
            localPoiId = Long.MIN_VALUE
            status("POI blocked: spatial fusion is not ready")
        }
    }

    private fun updateLocalAnchorPose() {
        val anchor = localAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING || localPoiId == Long.MIN_VALUE) return
        val now = SystemClock.elapsedRealtimeNanos()
        val p = anchor.pose.translation
        val previous = lastLocalSentPoint
        val moved = previous == null || pointDistance(previous, p) >= LOCAL_ANCHOR_UPDATE_M
        val heartbeat = now - lastLocalSentNs >= LOCAL_ANCHOR_HEARTBEAT_NS
        if (!moved && !heartbeat) return

        if (coordinator.sendPoi(localPoiId, p, localOwner)) {
            lastLocalSentPoint = p.copyOf()
            lastLocalSentNs = now
        }
    }

    private fun applyRemoteTargetRequest(session: Session, tracking: Boolean) {
        val request = remoteTargetRequest.get()
        if (request == null) {
            if (remoteAnchor != null) {
                runCatching { remoteAnchor?.detach() }
                remoteAnchor = null
                remoteAnchorId = Long.MIN_VALUE
                remoteAnchorInputPoint = null
                remoteOwner = ""
                remoteConfidence = 0f
            }
            return
        }
        if (!tracking) return

        val previousInput = remoteAnchorInputPoint
        val needsAnchor = remoteAnchor == null ||
            request.id != remoteAnchorId ||
            previousInput == null ||
            pointDistance(previousInput, request.point) >= REMOTE_REANCHOR_THRESHOLD_M

        remoteOwner = request.owner
        remoteConfidence = request.confidence
        if (!needsAnchor) return

        val replacement = runCatching {
            session.createAnchor(Pose.makeTranslation(request.point))
        }.getOrNull() ?: return

        val old = remoteAnchor
        remoteAnchor = replacement
        remoteAnchorId = request.id
        remoteAnchorInputPoint = request.point.copyOf()
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

    private fun projectRemoteTarget(camera: Camera) {
        val anchor = remoteAnchor ?: return
        if (anchor.trackingState != TrackingState.TRACKING) {
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
                remoteOwner,
                remoteConfidence,
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
        lastLocalSentPoint = null
        remoteAnchorInputPoint = null
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
        private const val LOCAL_ANCHOR_UPDATE_M = 0.015f
        private const val LOCAL_ANCHOR_HEARTBEAT_NS = 2_000_000_000L
        private const val REMOTE_REANCHOR_THRESHOLD_M = 0.04f
    }
}
