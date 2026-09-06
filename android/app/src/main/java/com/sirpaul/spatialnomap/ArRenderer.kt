package com.sirpaul.spatialnomap

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
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
    private val sensorSnapshotProvider: () -> SensorSnapshot = { SensorSnapshot() },
) : GLSurfaceView.Renderer {
    private data class RemoteTarget(val point: FloatArray, val owner: String, val confidence: Float)

    @Volatile var session: Session? = null
    @Volatile var sessionResumed: Boolean = false

    private val background = CameraBackgroundRenderer()
    private val pendingTap = AtomicReference<FloatArray?>(null)
    private val remoteTarget = AtomicReference<RemoteTarget?>(null)
    private val trackingGate = TrackingStabilityGate(acquireMs = 300L, lossMs = 1000L)
    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastFrameError = ""
    private var lastFrameErrorAtMs = 0L

    fun queueTap(x: Float, y: Float) {
        pendingTap.set(floatArrayOf(x, y))
    }

    fun setRemoteTarget(pointLocalWorld: FloatArray?, owner: String = "", confidence: Float = 0f) {
        remoteTarget.set(pointLocalWorld?.let { RemoteTarget(it.copyOf(3), owner, confidence) })
        if (pointLocalWorld == null) overlay.setTarget(null)
    }

    fun detachSession() {
        sessionResumed = false
        session = null
        textureBoundSession = null
        pendingTap.set(null)
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

            coordinator.onTrackingState(tracking)
            when (trackingGate.update(tracking, SystemClock.elapsedRealtime())) {
                true -> status("AR tracking")
                false -> status("AR PAUSED / ${camera.trackingFailureReason}")
                null -> Unit
            }
            if (!tracking) return

            handleTap(frame, camera)
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

    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = pendingTap.getAndSet(null) ?: return
        if (!coordinator.canPlacePoi()) {
            status("SYNCING — keep both cameras on overlapping detail until FUSED/LOCKED")
            return
        }

        val imagePixel = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, tap, Coordinates2d.IMAGE_PIXELS, imagePixel)
        var pointWorld = MetricSupportSampler.pointAtCpuPixel(frame, camera, imagePixel[0], imagePixel[1])

        if (pointWorld == null) {
            for (hit in frame.hitTest(tap[0], tap[1])) {
                val trackable = hit.trackable
                val usable = when (trackable) {
                    is DepthPoint -> true
                    is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                    is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    else -> false
                }
                if (usable) {
                    pointWorld = hit.hitPose.translation
                    break
                }
            }
        }

        if (pointWorld == null) {
            status("No reliable metric depth at the tap. Move the phone slightly and tap again.")
            return
        }

        if (coordinator.sendPoi(pointWorld, usernameProvider())) status("POI sent")
        else status("POI blocked: spatial fusion is not ready")
    }

    private fun captureIfDue(frame: Frame, camera: Camera) {
        val now = System.nanoTime()
        if (now - lastCaptureNs < 520_000_000L) return
        val packet = FrameCapture.capture(
            frame = frame,
            camera = camera,
            maxWidth = 960,
            sensors = sensorSnapshotProvider(),
        ) ?: return
        coordinator.onLocalFrame(packet)
        lastCaptureNs = now
    }

    private fun projectRemoteTarget(camera: Camera) {
        val target = remoteTarget.get() ?: return
        val p = target.point
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
                target.owner,
                target.confidence,
            ),
        )
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
}
