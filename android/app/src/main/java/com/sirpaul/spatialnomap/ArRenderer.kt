package com.sirpaul.spatialnomap

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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

class ArRenderer(
    private val network: NetworkClient,
    private val overlay: TargetOverlayView,
    private val status: (String) -> Unit,
    private val rotationProvider: () -> Int,
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var role: String = "A"

    private val background = CameraBackgroundRenderer()
    private val pendingTap = AtomicReference<FloatArray?>(null)
    private val remoteTarget = AtomicReference<FloatArray?>(null)
    @Volatile private var remoteDetail: String = ""
    private var width = 1
    private var height = 1
    private var textureBoundSession: Session? = null
    private var lastCaptureNs = 0L
    private var lastTrackingText = ""

    fun queueTap(x: Float, y: Float) {
        if (role == "A") pendingTap.set(floatArrayOf(x, y))
    }

    fun setRemoteTarget(pointWb: FloatArray?, detail: String) {
        remoteTarget.set(pointWb)
        remoteDetail = detail
        if (pointWb == null) overlay.setTarget(null, null)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(rotationProvider(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            if (textureBoundSession !== s) {
                s.setCameraTextureName(background.textureId)
                textureBoundSession = s
                s.setDisplayGeometry(rotationProvider(), width, height)
            }
            val frame = s.update()
            background.draw(frame)
            val camera = frame.camera
            val trackingText = "AR ${camera.trackingState}${if (camera.trackingState != TrackingState.TRACKING) " / ${camera.trackingFailureReason}" else ""}"
            if (trackingText != lastTrackingText) {
                lastTrackingText = trackingText
                status(trackingText)
            }
            if (camera.trackingState != TrackingState.TRACKING) return

            handleTap(frame, camera)
            captureIfDue(frame, camera)
            projectRemoteTarget(camera)
        } catch (t: Throwable) {
            status("AR frame error: ${t.message}")
        }
    }

    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = pendingTap.getAndSet(null) ?: return
        val imagePixel = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, tap, Coordinates2d.IMAGE_PIXELS, imagePixel)

        var pointWa: FloatArray? = null
        for (hit in frame.hitTest(tap[0], tap[1])) {
            val trackable = hit.trackable
            val usable = when (trackable) {
                is DepthPoint -> true
                is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
            if (usable) {
                pointWa = hit.hitPose.translation
                break
            }
        }
        if (pointWa == null) {
            pointWa = MetricSupportSampler.pointAtCpuPixel(frame, camera, imagePixel[0], imagePixel[1])
        }
        if (pointWa == null) {
            status("tap has no metric depth/ARCore hit; move phone slightly and retry")
            return
        }

        FrameCapture.capture(frame, camera)?.let {
            network.sendFrame(it)
            lastCaptureNs = System.nanoTime()
        }
        network.sendTarget(pointWa, imagePixel)
        status("target WA = ${"%.2f".format(pointWa[0])}, ${"%.2f".format(pointWa[1])}, ${"%.2f".format(pointWa[2])}")
    }

    private fun captureIfDue(frame: Frame, camera: Camera) {
        if (!network.connected) return
        val now = System.nanoTime()
        if (now - lastCaptureNs < 800_000_000L) return
        val packet = FrameCapture.capture(frame, camera) ?: return
        network.sendFrame(packet)
        lastCaptureNs = now
    }

    private fun projectRemoteTarget(camera: Camera) {
        if (role != "B") return
        val p = remoteTarget.get() ?: return
        val view = FloatArray(16)
        val projection = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.05f, 500f)
        val world = floatArrayOf(p[0], p[1], p[2], 1f)
        val cameraV = FloatArray(4)
        val clip = FloatArray(4)
        Matrix.multiplyMV(cameraV, 0, view, 0, world, 0)
        Matrix.multiplyMV(clip, 0, projection, 0, cameraV, 0)
        if (clip[3] <= 1e-5f) {
            overlay.setTarget(null, null)
            return
        }
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val x = (ndcX + 1f) * 0.5f * width
        val y = (1f - ndcY) * 0.5f * height
        overlay.setTarget(x, y, remoteDetail)
    }
}
