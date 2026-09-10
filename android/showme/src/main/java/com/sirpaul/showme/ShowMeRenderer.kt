package com.sirpaul.showme

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.*
import com.sirpaul.spatialnomap.*
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class ShowMeRenderer(private val state: ShowMeSession, private val overlay: ShowMeOverlay,
    private val notice: (String) -> Unit, private val call: RtcVoice) : GLSurfaceView.Renderer {
    @Volatile var arSession: Session? = null
    @Volatile var resumed = false
    @Volatile var imageRotation = 90
    private val background = CameraBackgroundRenderer()
    private var boundSession: Session? = null
    private var width = 1; private var height = 1
    private val verifying = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "ShowMe-surface-verifier")
    }
    private val surfaceVerifier = StrokeSurfaceVerifier()
    private val stableAr = StableArShowMeBridge(notice)
    private val verifierBusy = AtomicBoolean()
    private val actions = ConcurrentLinkedQueue<String>()
    private val repairs = ConcurrentLinkedQueue<Repair>()
    private var lastCapture = 0L; private var nextFrameId = 0L; private var verifyIndex = 0
    private var lastError = ""
    private var videoPipe: RtcVideoPipe? = null
    private var observedShowEpoch = state.epoch
    private data class Drawing(val id: String, val tool: String, val color: String, val label: String,
        var anchor: Anchor?, val stableArAttachmentId: Long?, var offsets: List<FloatArray>,
        val reference: StrokeSurfaceVerifier.Reference?, var verified: Boolean = false,
        var pending: List<FloatArray>? = null, var votes: Int = 0,
        var lastVoteFrame: Long = -1L, var repairBudgetM: Float = 0f)
    private data class Repair(val id: String, val epoch: Int, val frameId: Long, val result: StrokeSurfaceVerifier.Result)
    private val drawings = LinkedHashMap<String, Drawing>()

    fun action(action: String) { actions.add(action) }
    fun requestStableArEnabled(enabled: Boolean) { actions.add("stable:${if(enabled)1 else 0}") }
    fun invalidateStableArFrames() { stableAr.invalidateExternalFramesAsync() }
    fun close() {
        verifying.execute { surfaceVerifier.clear() }; verifying.shutdown()
        stableAr.closeWorkers()
    }
    /** Called on GL owner thread (including the queued Activity destruction path). */
    fun releaseGlResources() {
        runCatching { videoPipe?.close() }; videoPipe = null
        runCatching { stableAr.releaseOnOwnerThread() }
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.035f, 0.05f, 0.08f, 1f)
        releaseGlResources()
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
        val workStart = System.nanoTime()
        lastDepthMs = 0f
        try {
            if (boundSession !== session) {
                detachAll()
                state.resetWorld()
                session.setCameraTextureName(background.textureId)
                session.setDisplayGeometry(0, width, height)
                boundSession = session
                stableAr.onSession(session)
                observedShowEpoch = state.epoch
            }
            val frame = session.update()
            background.draw(frame)
            val camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING
            state.tracking = tracking
            state.trackingMessage = if (tracking) "Surface tracking active" else "Scan slowly: ${camera.trackingFailureReason}"

            // A new remote call/session epoch invalidates every browser-frame -> SDK sample mapping,
            // while existing local marks may remain in the same ARCore world.
            if (observedShowEpoch != state.epoch) {
                stableAr.endRemoteSession()
                observedShowEpoch = state.epoch
            }
            processSpatialLeases()
            while (true) {
                val action = actions.poll() ?: break
                when {
                    action == "clear" -> detachAll()
                    action == "undo" -> removeLast()
                    action.startsWith("stable:") -> switchSpatialMode(action.endsWith("1"))
                }
            }

            val useStableAr = state.stableArEnabled && (state.hasHelper() || drawings.values.any { it.stableArAttachmentId != null })
            stableAr.onFrame(frame, state.epoch, useStableAr)

            repeat(3) {
                val command = state.poll() ?: return@repeat
                if (command.answer.isDone) return@repeat
                val result = runCatching { applyCommand(session, command.body, command.prepared) }.getOrElse {
                    ShowMeSession.failure("PLACEMENT_FAILED", "Could not attach this drawing. Scan the surface and retry.")
                }
                command.answer.complete(result)
            }
            if (!tracking) {
                overlay.show(emptyList())
                publishVideo(frame, camera, emptyList())
                state.stableArDiagnostics = if(state.stableArEnabled) stableAr.diagnosticsJson() else "{}"
                return
            }
            applyRepairs(session)
            val snapshot = snapshot()
            showNativeOverlay(frame, camera, snapshot)
            val depthFrame = publishVideo(frame, camera, snapshot)
            if (depthFrame != null) captureIfDue(frame, camera, depthFrame)
            state.annotationCount = drawings.size
            state.stableArDiagnostics = if(state.stableArEnabled) stableAr.diagnosticsJson() else "{}"
        } catch (t: Throwable) {
            if (t is com.google.ar.core.exceptions.SessionPausedException) return
            state.tracking = false
            val message = t.javaClass.simpleName + ": " + (t.message ?: "AR camera unavailable")
            if (message != lastError) { lastError = message; notice(message) }
        } finally { state.telemetry.frame((System.nanoTime()-workStart)/1_000_000f,lastDepthMs) }
    }

    private fun processSpatialLeases() {
        repeat(8) {
            val lease = state.pollSpatialLease() ?: return
            if (lease.answer.isDone) return@repeat
            val ok = runCatching {
                when (lease.action) {
                    SpatialLeaseAction.FREEZE -> state.stableArEnabled && stableAr.freeze(lease.frameId, lease.epoch, state.epoch)
                    SpatialLeaseAction.UNFREEZE -> { if(state.stableArEnabled) stableAr.unfreeze(); true }
                }
            }.getOrDefault(false)
            lease.answer.complete(ok)
        }
    }

    private fun switchSpatialMode(enabled: Boolean) {
        if (state.stableArEnabled == enabled) return
        // Ownership cannot change under an existing mark: clear the comparison scene and bump the
        // ShowMe epoch so a browser cannot submit geometry captured in the previous mode.
        detachAll()
        stableAr.resetWorld()
        state.setSpatialMode(enabled)
        state.resetWorld()
        observedShowEpoch = state.epoch
        notice(if(enabled) "Spatial attachment: StableAR" else "Spatial attachment: legacy verifier")
    }

    private fun applyCommand(session: Session, body: JSONObject, prepared: PreparedStroke?): JSONObject {
        if (!state.active) return ShowMeSession.failure("SESSION_ENDED", "The session has ended.")
        val requestId = body.optString("requestId")
        state.previous(requestId)?.let { return it }
        when (body.optString("action", "draw")) {
            "clear" -> { detachAll(); return JSONObject().put("ok", true).also { state.remember(requestId, it) } }
            "undo" -> { removeLast(); return JSONObject().put("ok", true).also { state.remember(requestId, it) } }
            "remove" -> {
                drawings.remove(body.optString("id"))?.let(::releaseDrawing)
                state.annotationCount = drawings.size
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
        val ready = prepared ?: return ShowMeSession.failure("NO_SURFACE", "This drawing has no verified historical surface.")
        if (ready.frame.id != packet.id || ready.frame.epoch != state.epoch)
            return ShowMeSession.failure("WORLD_CHANGED", "The camera session changed. Try again.")
        val world = ready.world
        val id = java.util.UUID.randomUUID().toString()
        val label = body.optString("label").filter { !it.isISOControl() }.take(64)
        val drawing = if (state.stableArEnabled) {
            val points = body.getJSONArray("points")
            val normalized = ArrayList<DoubleArray>(points.length())
            for (i in 0 until points.length()) {
                val p = points.getJSONArray(i)
                normalized += doubleArrayOf(p.getDouble(0), p.getDouble(1))
            }
            val root = StableArPlacementMath.normalizedRoot(normalized)
                ?: return ShowMeSession.failure("INVALID_DRAWING", "Drawing coordinates are invalid.")
            val placement = stableAr.place(packet.id, state.epoch, root)
                ?: return ShowMeSession.failure("NO_SURFACE", "StableAR could not retain the exact surface under this historical frame. Resume live view and try again.")
            val offsets = StableArPlacementMath.relativeOffsets(world, placement.rootWorld)
            if (offsets == null) {
                stableAr.remove(placement.attachmentId)
                return ShowMeSession.failure("NO_SURFACE", "The drawing geometry could not be retained.")
            }
            Drawing(id, body.getString("tool"), body.getString("color"), label, null,
                placement.attachmentId, offsets, null)
        } else {
            // Legacy A/B path is intentionally kept intact and remains the only owner of its verifier.
            val base = StrokeGeometry.center(world)
            val anchor = session.createAnchor(Pose.makeTranslation(base))
            Drawing(id, body.getString("tool"), body.getString("color"), label, anchor, null,
                world.map { p -> anchor.pose.inverse().transformPoint(p) },
                StrokeSurfaceVerifier.Reference(id,packet.capture,ready.pixels))
        }
        drawings[id] = drawing
        state.annotationCount = drawings.size
        notice(if (drawing.label.isBlank()) "New ${drawing.tool} attached with ${if(drawing.stableArAttachmentId!=null)"StableAR" else "legacy tracking"}" else "Pinned: ${drawing.label}")
        return JSONObject().put("ok", true).put("id", id).put("frameId", packet.id)
            .put("spatialMode", if(drawing.stableArAttachmentId!=null)"STABLE_AR" else "LEGACY")
            .put("annotations", projectStrokes(packet.capture, packet.rotation, snapshot()))
            .also { state.remember(requestId, it) }
    }

    private fun releaseDrawing(d: Drawing) {
        d.stableArAttachmentId?.let { stableAr.remove(it) }
            ?: d.anchor?.let { runCatching { it.detach() } }
    }
    private fun removeLast() {
        val id = drawings.keys.lastOrNull() ?: return
        drawings.remove(id)?.let(::releaseDrawing)
        state.annotationCount = drawings.size
    }
    private fun detachAll() {
        drawings.values.forEach(::releaseDrawing)
        drawings.clear(); repairs.clear(); state.annotationCount = 0
        if (!verifying.isShutdown) verifying.execute { surfaceVerifier.clear() }
        overlay.show(emptyList())
    }
    private fun snapshot(): List<StrokeSnapshot> = drawings.values.mapNotNull { d ->
        val stableId = d.stableArAttachmentId
        if (stableId != null) {
            val root = stableAr.worldPoint(stableId) ?: return@mapNotNull null
            val points = d.offsets.map { o -> floatArrayOf(root[0]+o[0],root[1]+o[1],root[2]+o[2]) }
            StrokeSnapshot(d.id,d.tool,d.color,d.label,points,stableAr.isGeometrySupported(stableId))
        } else {
            val anchor = d.anchor ?: return@mapNotNull null
            if (anchor.trackingState != TrackingState.TRACKING) null
            else StrokeSnapshot(d.id,d.tool,d.color,d.label,d.offsets.map { anchor.pose.transformPoint(it) },d.verified)
        }
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

    private var lastDepthMs = 0f
    private fun publishVideo(frame: Frame, camera: Camera, strokes: List<StrokeSnapshot>): VideoDepthFrame? {
        // Keep the local AR preview completely independent of WebRTC. In particular, do not create
        // a second shared EGL surface until an approved helper actually needs encoded video.
        if (!state.active || state.paused || !state.hasHelper()) return null
        try {
            val dims = camera.imageIntrinsics.imageDimensions
            val pipe = videoPipe ?: RtcVideoPipe(call, dims[0], dims[1], imageRotation).also { videoPipe = it }
            if (!pipe.isDue(frame.timestamp, System.nanoTime())) return null
            val id = ++nextFrameId
            val epoch = state.epoch
            // This exact mapping is established BEFORE submitting the same exposure to WebRTC.
            if (state.stableArEnabled) stableAr.bindVideoFrame(id, epoch, imageRotation, frame.timestamp)
            val start = System.nanoTime()
            val captured = VideoDepthFrame.capture(frame, camera, id, epoch, imageRotation, strokes,
                pipe.videoWidth, pipe.videoHeight, pipe.contentHeight)
            lastDepthMs = (System.nanoTime()-start)/1_000_000f
            if (state.active && !state.paused && state.epoch == epoch) {
                state.videoFrames.add(captured)
                // JSON projection/serialization and SCTP sends do not run on the AR thread.
                call.publishFrame(captured)
                pipe.draw(frame, camera, background.textureId, id, epoch)
                return captured
            }
        } catch (t: Throwable) {
            state.videoState = "CAPTURE_ERROR"
            val error = "Video capture: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            if (error != lastError) { lastError = error; notice(error) }
        }
        return null
    }

    /** Legacy verifier only. StableAR-controlled drawings never enter this correction system. */
    private fun captureIfDue(frame: Frame, camera: Camera, depthFrame: VideoDepthFrame) {
        val now = monotonicMs()
        if (!state.active || state.paused || drawings.isEmpty() || now-lastCapture < 1_200L ||
            !verifierBusy.compareAndSet(false,true)) return
        val candidates=drawings.values.filter { it.stableArAttachmentId==null &&
            it.anchor?.trackingState==TrackingState.TRACKING && it.reference!=null && it.repairBudgetM<.15f }
        if(candidates.isEmpty()){verifierBusy.set(false);return}
        val target=candidates[verifyIndex++%candidates.size]
        val anchor=target.anchor ?: run {verifierBusy.set(false);return}
        val reference=target.reference ?: run {verifierBusy.set(false);return}
        val expected=target.offsets.map { anchor.pose.transformPoint(it) }
        val image=runCatching { frame.acquireCameraImage() }.getOrNull()
        if(image==null){verifierBusy.set(false);return}
        try {
            val w=image.width;val h=image.height
            // A verifier image and its depth/pose must refer to the same exposure.
            if (image.timestamp != frame.timestamp || w != depthFrame.intrinsics.width || h != depthFrame.intrinsics.height) {
                image.close();verifierBusy.set(false);return
            }
            val start=System.nanoTime()
            val plane=image.planes[0];val buffer=plane.buffer.duplicate();val base=buffer.position()
            val luma=ByteArray(w*h)
            for(y in 0 until h) {
                if(plane.pixelStride==1){buffer.position(base+y*plane.rowStride);buffer.get(luma,y*w,w)}
                else for(x in 0 until w)luma[y*w+x]=buffer.get(base+y*plane.rowStride+x*plane.pixelStride)
            }
            image.close()
            state.telemetry.copy((System.nanoTime()-start)/1_000_000f)
            lastCapture=now
            verifying.execute {
                val source=Mat(h,w,CvType.CV_8UC1);val gray=Mat()
                try {
                    source.put(0,0,luma)
                    val scale=min(1.0,960.0/max(w,h))
                    val outW=(w*scale).roundToInt();val outH=(h*scale).roundToInt()
                    Imgproc.resize(source,gray,Size(outW.toDouble(),outH.toDouble()),0.0,0.0,Imgproc.INTER_AREA)
                    val pixels=ByteArray(outW*outH);gray.get(0,0,pixels)
                    val sx=outW.toFloat()/w;val sy=outH.toFloat()/h;val k=depthFrame.intrinsics
                    // Expensive depth sampling stays entirely on this background worker.
                    val metric=depthFrame.supports().map { floatArrayOf(it[0]*sx,it[1]*sy,it[2],it[3],it[4]) }
                    val captured=CapturedFrame(depthFrame.id,depthFrame.pose,
                        IntrinsicsPacket(k.fx*sx,k.fy*sy,k.cx*sx,k.cy*sy,outW,outH),"",metric)
                    val result=surfaceVerifier.verify(reference,captured,pixels,expected)
                    if(result!=null&&state.active&&state.epoch==depthFrame.epoch)
                        repairs.add(Repair(reference.id,depthFrame.epoch,depthFrame.id,result))
                } catch (_: Exception) {
                    // An optional texture check must never interrupt live media or replace a good anchor.
                } finally {source.release();gray.release();verifierBusy.set(false)}
            }
        } catch (_: Exception) {runCatching { image.close() };verifierBusy.set(false)}
    }

    private fun applyRepairs(session: Session) {
        while (true) {
            val repair=repairs.poll() ?: break
            if(repair.epoch!=state.epoch)continue
            val d=drawings[repair.id] ?: continue
            if(d.stableArAttachmentId!=null)continue
            val anchor=d.anchor ?: continue
            if(repair.frameId==d.lastVoteFrame||anchor.trackingState!=TrackingState.TRACKING)continue
            d.lastVoteFrame=repair.frameId
            val current=d.offsets.map { anchor.pose.transformPoint(it) }
            val points=repair.result.points
            if(!StrokeGeometry.shapeCompatible(current,points))continue
            val delta=current.indices.maxOf { ShowMeGeometry.distance(current[it],points[it]) }
            if(delta>.06f||d.repairBudgetM+delta>.15f)continue
            if(delta<.004f){d.verified=true;d.pending=null;d.votes=0;continue}
            val previous=d.pending
            if(previous!=null&&previous.size==points.size&&points.indices.all { ShowMeGeometry.distance(previous[it],points[it])<.018f })d.votes++
            else d.votes=1
            d.pending=points.map { it.copyOf() }
            if(d.votes<2)continue
            // Legacy bounded consensus correction. Never applied to StableAR-owned marks.
            val blended=current.indices.map { i->FloatArray(3) { axis->current[i][axis]*.5f+points[i][axis]*.5f } }
            val base=StrokeGeometry.center(blended)
            val replacement=runCatching { session.createAnchor(Pose.makeTranslation(base)) }.getOrNull() ?: continue
            val old=d.anchor;d.anchor=replacement
            d.offsets=blended.map { replacement.pose.inverse().transformPoint(it) }
            d.verified=true;d.repairBudgetM+=delta*.5f;d.pending=null;d.votes=0
            runCatching { old?.detach() }
        }
    }
}
