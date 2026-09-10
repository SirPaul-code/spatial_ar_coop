package com.sirpaul.showme

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.*
import com.sirpaul.spatialnomap.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class ShowMeRenderer(private val state: ShowMeSession, private val overlay: ShowMeOverlay,
    private val notice: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var arSession: Session? = null
    @Volatile var resumed = false
    @Volatile var imageRotation = 90
    private val background = CameraBackgroundRenderer()
    private var boundSession: Session? = null
    private var width = 1; private var height = 1
    private val encoding = Executors.newSingleThreadExecutor()
    private val encoderBusy = AtomicBoolean()
    private val verifying = Executors.newSingleThreadExecutor()
    private val verifierBusy = AtomicBoolean()
    private val actions = ConcurrentLinkedQueue<String>()
    private val repairs = ConcurrentLinkedQueue<Repair>()
    private var lastCapture = 0L; private var nextFrameId = 0L; private var lastVerify = 0L; private var verifyIndex = 0
    private var lastError = ""
    private data class Drawing(val id: String, val tool: String, val color: String, val label: String,
        var anchor: Anchor, val offsets: List<FloatArray>, val reference: SurfaceTargetReference,
        var verified: Boolean = false, var pending: FloatArray? = null, var votes: Int = 0,
        var lastVoteFrame: Long = -1L, var repairBudgetM: Float = 0f)
    private data class Repair(val id: String, val epoch: Int, val frameId: Long, val result: SurfaceTargetResolver.Result)
    private val drawings = LinkedHashMap<String, Drawing>()

    fun action(action: String) { actions.add(action) }
    fun close() {
        encoding.shutdownNow(); verifying.shutdownNow()
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.05f, 0.08f, 1f)
        background.createOnGlThread(); boundSession = null
    }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        if (resumed) runCatching { arSession?.setDisplayGeometry(0, width, height) }
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!resumed) return
        val session = arSession ?: return
        try {
            if (boundSession !== session) {
                detachAll(); state.resetWorld()
                session.setCameraTextureName(background.textureId)
                session.setDisplayGeometry(0, width, height)
                boundSession = session
            }
            val frame = session.update()
            background.draw(frame)
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING
            state.tracking = tracking
            state.trackingMessage = if (tracking) "Surface tracking active" else "Scan slowly: ${camera.trackingFailureReason}"
            while (true) {
                val action = actions.poll() ?: break
                when (action) { "clear" -> detachAll(); "undo" -> removeLast() }
            }
            repeat(3) {
                val command = state.poll() ?: return@repeat
                if (command.answer.isDone) return@repeat
                val result = runCatching { applyCommand(session, command.body) }.getOrElse {
                    ShowMeSession.failure("PLACEMENT_FAILED", "Could not attach this drawing. Scan the surface and retry.")
                }
                command.answer.complete(result)
            }
            if (!tracking) { overlay.show(emptyList()); return }
            applyRepairs(session)
            val snapshot = snapshot()
            showNativeOverlay(frame, camera, snapshot)
            captureIfDue(frame, camera, snapshot)
            state.annotationCount = drawings.size
        } catch (t: Throwable) {
            if (t is com.google.ar.core.exceptions.SessionPausedException) return
            state.tracking = false
            val message = t.javaClass.simpleName + ": " + (t.message ?: "AR camera unavailable")
            if (message != lastError) { lastError = message; notice(message) }
        }
    }

    private fun applyCommand(session: Session, body: JSONObject): JSONObject {
        if (!state.active) return ShowMeSession.failure("SESSION_ENDED", "The session has ended.")
        val requestId = body.optString("requestId")
        state.previous(requestId)?.let { return it }
        when (body.optString("action", "draw")) {
            "clear" -> { detachAll(); return JSONObject().put("ok", true).also { state.remember(requestId, it) } }
            "undo" -> { removeLast(); return JSONObject().put("ok", true).also { state.remember(requestId, it) } }
            "remove" -> {
                drawings.remove(body.optString("id"))?.let { runCatching { it.anchor.detach() } }
                return JSONObject().put("ok", true).also { state.remember(requestId, it) }
            }
        }
        ShowMeSession.validateDraw(body)?.let { return ShowMeSession.failure("INVALID_DRAWING", it) }
        if (state.paused || !state.tracking) return ShowMeSession.failure("TRACKING_PAUSED", "Wait for the camera owner to resume surface tracking.")
        val packet = state.frames.get(body.optLong("frameId", -1L))
            ?: return ShowMeSession.failure("STALE_FRAME", "This image has expired. Resume the live view and draw again.")
        if (body.optInt("epoch", -1) != state.epoch || packet.epoch != state.epoch)
            return ShowMeSession.failure("WORLD_CHANGED", "The camera session changed. Resume live view and try again.")
        if (drawings.size >= 48) return ShowMeSession.failure("LIMIT", "Remove a drawing before adding another (48 per session).")
        val pixels = body.getJSONArray("points")
        val world = ArrayList<FloatArray>()
        var firstPixel: FloatArray? = null
        for (i in 0 until pixels.length()) {
            val xy = pixels.getJSONArray(i)
            val raw = ShowMeGeometry.uprightToRaw(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat(), packet.rotation)
            val u = (raw[0] * packet.capture.intrinsics.width).coerceIn(0f, packet.capture.intrinsics.width - 1f)
            val v = (raw[1] * packet.capture.intrinsics.height).coerceIn(0f, packet.capture.intrinsics.height - 1f)
            val point = ShowMeGeometry.pointAt(packet.capture, u, v)
                ?: return ShowMeSession.failure("NO_SURFACE", "No reliable depth under this drawing. Ask the camera owner to move slightly around the surface, then retry.")
            if (firstPixel == null) firstPixel = floatArrayOf(u, v)
            if (world.isNotEmpty() && ShowMeGeometry.distance(world.first(), point) > 2.5f)
                return ShowMeSession.failure("SURFACE_GAP", "This drawing crosses unrelated surfaces. Draw a smaller shape on one surface.")
            world.add(point)
        }
        val base = world.first()
        val anchor = session.createAnchor(Pose.makeTranslation(base))
        val id = java.util.UUID.randomUUID().toString()
        val drawing = Drawing(id, body.getString("tool"), body.getString("color"),
            body.optString("label").filter { !it.isISOControl() }.take(64), anchor,
            world.map { p -> FloatArray(3) { p[it] - base[it] } },
            SurfaceTargetReference(packet.capture, firstPixel!!))
        drawings[id] = drawing
        state.annotationCount = drawings.size
        notice(if (drawing.label.isBlank()) "New ${drawing.tool} anchored to the surface" else "Pinned: ${drawing.label}")
        return JSONObject().put("ok", true).put("id", id).put("frameId", packet.id)
            .put("annotations", projectStrokes(packet.capture, packet.rotation, snapshot()))
            .also { state.remember(requestId, it) }
    }
    private fun removeLast() {
        val id = drawings.keys.lastOrNull() ?: return
        drawings.remove(id)?.let { runCatching { it.anchor.detach() } }
        state.annotationCount = drawings.size
    }
    private fun detachAll() {
        drawings.values.forEach { runCatching { it.anchor.detach() } }
        drawings.clear(); repairs.clear(); state.annotationCount = 0
        SurfaceTargetResolver.clearLearnedReferences()
        overlay.show(emptyList())
    }
    private fun snapshot(): List<StrokeSnapshot> = drawings.values.mapNotNull { d ->
        if (d.anchor.trackingState != TrackingState.TRACKING) null
        else StrokeSnapshot(d.id, d.tool, d.color, d.label, d.offsets.map { d.anchor.pose.transformPoint(it) }, d.verified)
    }
    private fun showNativeOverlay(frame: Frame, camera: Camera, strokes: List<StrokeSnapshot>) {
        val k = camera.imageIntrinsics
        val inverse = camera.pose.inverse()
        val screen = strokes.mapNotNull { stroke ->
            val points = ArrayList<FloatArray>()
            for (world in stroke.points) {
                val p = inverse.transformPoint(world)
                if (p[2] > -0.05f) return@mapNotNull null
                val raw = floatArrayOf(k.focalLength[0] * p[0] / -p[2] + k.principalPoint[0],
                    k.focalLength[1] * -p[1] / -p[2] + k.principalPoint[1])
                val out = FloatArray(2)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, raw, Coordinates2d.VIEW, out)
                if (out.any { !it.isFinite() }) return@mapNotNull null
                points.add(out)
            }
            ShowMeOverlay.Shape(stroke.id, stroke.tool, stroke.color, stroke.label, points, stroke.verified)
        }
        overlay.show(screen)
    }

    private fun captureIfDue(frame: Frame, camera: Camera, strokes: List<StrokeSnapshot>) {
        val now = monotonicMs()
        if (!state.active || state.paused || now - lastCapture < 140L || !encoderBusy.compareAndSet(false, true)) return
        val image = runCatching { frame.acquireCameraImage() }.getOrNull()
        if (image == null) { encoderBusy.set(false); return }
        try {
            val w = image.width; val h = image.height
            val nv21 = ByteArray(w * h * 3 / 2)
            val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
            val yb = y.buffer.duplicate(); val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
            val y0 = yb.position(); val u0 = ub.position(); val v0 = vb.position()
            var at = 0
            for (row in 0 until h) for (col in 0 until w) nv21[at++] = yb.get(y0 + row * y.rowStride + col * y.pixelStride)
            for (row in 0 until h / 2) for (col in 0 until w / 2) {
                nv21[at++] = vb.get(v0 + row * v.rowStride + col * v.pixelStride)
                nv21[at++] = ub.get(u0 + row * u.rowStride + col * u.pixelStride)
            }
            image.close()
            val scale = min(1f, 960f / max(w, h))
            val outW = max(2, (w * scale).toInt()); val outH = max(2, (h * scale).toInt())
            val sx = outW.toFloat() / w; val sy = outH.toFloat() / h
            val intr = camera.imageIntrinsics
            val intrinsics = IntrinsicsPacket(intr.focalLength[0] * sx, intr.focalLength[1] * sy,
                intr.principalPoint[0] * sx, intr.principalPoint[1] * sy, outW, outH)
            val pose = PosePacket(camera.pose.translation.copyOf(), camera.pose.rotationQuaternion.copyOf())
            val supports = MetricSupportSampler.sample(frame, camera, 8000).map {
                floatArrayOf(it[0] * sx, it[1] * sy, it[2], it[3], it[4])
            }
            val stamp = frame.timestamp; val epoch = state.epoch; val rotation = imageRotation
            val id = ++nextFrameId
            lastCapture = now
            encoding.execute {
                val src = Mat(h + h / 2, w, CvType.CV_8UC1)
                val bgr = Mat(); val resized = Mat(); val jpeg = MatOfByte()
                val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 88)
                try {
                    src.put(0, 0, nv21)
                    Imgproc.cvtColor(src, bgr, Imgproc.COLOR_YUV2BGR_NV21)
                    Imgproc.resize(bgr, resized, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    if (Imgcodecs.imencode(".jpg", resized, jpeg, parameters)) {
                        val bytes = jpeg.toArray()
                        val captured = CapturedFrame(stamp, pose, intrinsics, Base64.getEncoder().encodeToString(bytes), supports)
                        if (state.active && !state.paused && state.epoch == epoch) {
                            state.frames.add(FramePacket(id, epoch, captured, bytes, rotation, now, strokes))
                        }
                    }
                } catch (t: Throwable) {
                    notice("Camera sharing: ${t.javaClass.simpleName}")
                } finally {
                    src.release(); bgr.release(); resized.release(); jpeg.release(); parameters.release()
                    encoderBusy.set(false)
                }
            }
        } catch (t: Throwable) {
            runCatching { image.close() }; encoderBusy.set(false)
        }
        scheduleVerification()
    }

    private fun scheduleVerification() {
        val now = monotonicMs()
        if (drawings.isEmpty() || now - lastVerify < 900L || !verifierBusy.compareAndSet(false, true)) return
        val packet = state.frames.latest()
        if (packet == null) { verifierBusy.set(false); return }
        val candidates = drawings.values.filter { it.anchor.trackingState == TrackingState.TRACKING && it.repairBudgetM < 0.15f }
        if (candidates.isEmpty()) { verifierBusy.set(false); return }
        val target = candidates[verifyIndex++ % candidates.size]
        val id = target.id; val reference = target.reference; val expected = target.anchor.pose.translation.copyOf()
        lastVerify = now
        verifying.execute {
            try {
                val coarse = SurfaceTargetResolver.resolve(reference, packet.capture, expected) ?: return@execute
                val refined = runCatching { SurfaceEdgeSnapRefiner.refine(reference, packet.capture, coarse, expected) }.getOrNull() ?: coarse
                if (refined.confidence >= 0.30f && refined.visualInliers >= 9 && refined.medianReprojectionPx <= 2.0f &&
                    refined.depthSupports >= 4 && ShowMeGeometry.distance(expected, refined.pointWorld) <= 0.08f) {
                    repairs.add(Repair(id, packet.epoch, packet.id, refined))
                }
            } catch (_: Throwable) {
                // The optional verifier cannot interrupt the camera, networking or existing anchors.
            } finally { verifierBusy.set(false) }
        }
    }
    private fun applyRepairs(session: Session) {
        while (true) {
            val repair = repairs.poll() ?: break
            if (repair.epoch != state.epoch) continue
            val d = drawings[repair.id] ?: continue
            if (repair.frameId == d.lastVoteFrame || d.anchor.trackingState != TrackingState.TRACKING) continue
            d.lastVoteFrame = repair.frameId
            val point = repair.result.pointWorld
            val delta = ShowMeGeometry.distance(d.anchor.pose.translation, point)
            if (delta > 0.08f || d.repairBudgetM + delta > 0.15f) continue
            if (delta < 0.006f) { d.verified = true; continue }
            val previous = d.pending
            if (previous != null && ShowMeGeometry.distance(previous, point) < 0.025f) d.votes += 1
            else d.votes = 1
            d.pending = point.copyOf()
            if (d.votes < 2) continue
            val replacement = runCatching { session.createAnchor(Pose(point, d.anchor.pose.rotationQuaternion)) }.getOrNull() ?: continue
            val old = d.anchor; d.anchor = replacement; d.verified = true
            d.repairBudgetM += delta; d.pending = null; d.votes = 0
            runCatching { old.detach() }
        }
    }
}
