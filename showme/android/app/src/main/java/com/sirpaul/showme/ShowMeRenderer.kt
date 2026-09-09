package com.sirpaul.showme

import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.*
import com.sirpaul.spatialnomap.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** One AR world, owned by the camera phone. A browser is an observer, never a second AR world. */
class ShowMeRenderer(
    private val overlay:DrawingOverlay,
    private val server:()->LanSessionServer?,
    private val rotation:()->Int,
    private val imageRotation:()->Int,
    private val onStatus:(String,Int)->Unit,
) : GLSurfaceView.Renderer {
    @Volatile var session:Session?=null
    @Volatile var resumed=false
    @Volatile var sharingPaused=false
    @Volatile var sharing=false
    private val cameraBackground=CameraBackgroundRenderer()
    private var bound:Session?=null
    private var width=1; private var height=1
    private val commands=ArrayBlockingQueue<JSONObject>(64)
    private val encoder=Executors.newSingleThreadExecutor()
    private val verifier=Executors.newSingleThreadExecutor()
    private val captureBusy=AtomicBoolean(false)
    private val verifyBusy=AtomicBoolean(false)
    private val generation=AtomicLong(1)
    private val clearRequested=AtomicBoolean(false)
    private var nextFrame=1L
    private var nextAnnotation=1L
    private var lastCapture=0L
    private var lastVerify=0L
    private var lastStatus=""
    private var verifyCursor=0
    private val ledger=RequestLedger()
    private val ready=ConcurrentLinkedQueue<EncodedFrame>()
    private val corrections=ConcurrentLinkedQueue<Correction>()
    private val frames=LinkedHashMap<Long,StoredFrame>()
    private val annotations=LinkedHashMap<String,Annotation>()
    private var heldFrame:Long?=null
    private var holdUntil=0L

    private data class StoredFrame(val id:Long,val frame:CapturedFrame,val rotation:Int,val cameraAnchor:Anchor,val planes:List<PlaneSnapshot>,val atMs:Long)
    private data class EncodedFrame(val generation:Long,val stored:StoredFrame,val packet:LanSessionServer.Packet?)
    private data class Annotation(val id:String,val tool:String,val color:String,val label:String,var anchor:Anchor,
        val vertices:List<FloatArray>,val reference:SurfaceTargetReference,val expires:Long,
        var verifiedAt:Long=0L,var vote:FloatArray?=null,var votes:Int=0,var voteAt:Long=0L)
    private data class Correction(val generation:Long,val id:String,val frameId:Long,val result:SurfaceTargetResolver.Result)

    fun queue(command:JSONObject) {
        if(!commands.offer(command)) reply(JSONObject().put("type","error").put("requestId",command.optString("requestId")).put("message","Camera is busy; try again"))
    }
    fun clear() { generation.incrementAndGet(); commands.clear(); clearRequested.set(true) }
    fun close() { generation.incrementAndGet(); encoder.shutdownNow(); verifier.shutdownNow() }
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) { cameraBackground.createOnGlThread(); bound=null; GLES20.glClearColor(0.04f,0.06f,0.09f,1f) }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) { width=w; height=h; GLES20.glViewport(0,0,w,h); bound=null }

    override fun onDrawFrame(gl:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s=session ?: return
        if(!resumed) return
        try {
            if(bound!==s) { s.setCameraTextureName(cameraBackground.textureId); s.setDisplayGeometry(rotation(),width,height); bound=s }
            val frame=s.update(); cameraBackground.draw(frame); val camera=frame.camera
            if(clearRequested.getAndSet(false)) clearEverything()
            drainEncodings()
            val tracking=camera.trackingState==TrackingState.TRACKING
            val now=SystemClock.elapsedRealtime()
            val state=when { !tracking -> "Move slowly - ${camera.trackingFailureReason.name.lowercase().replace('_',' ')}"; sharingPaused -> "Sharing paused"; server()?.hasHelper==true -> "LIVE / AR tracking"; sharing -> "Ready / waiting for helper"; else -> "AR ready / start a session" }
            if(state!=lastStatus) { lastStatus=state; onStatus(state,annotations.size) }
            server()?.let { it.paused=!tracking || sharingPaused || !sharing; it.pauseReason=state }
            processCommands(s,tracking,now)
            if(!tracking) { overlay.update(emptyList()); return }
            applyCorrections(s,now)
            annotations.values.filter { it.expires>0 && now>it.expires || it.anchor.trackingState==TrackingState.STOPPED }.map { it.id }.forEach(::remove)
            renderOverlay(camera,now)
            if((sharing && !sharingPaused && server()?.hasHelper==true) || annotations.isNotEmpty()) capture(s,frame,camera,now)
            pruneFrames(now)
        } catch(t:Exception) {
            val message="Camera paused: ${t.javaClass.simpleName}"
            if(message!=lastStatus) { lastStatus=message; onStatus(message,annotations.size) }
            server()?.let { it.paused=true; it.pauseReason=message }; overlay.update(emptyList())
        }
    }

    private fun processCommands(s:Session,tracking:Boolean,now:Long) {
        repeat(8) {
            val command=commands.poll() ?: return
            when(command.optString("type")) {
                "hold" -> {
                    val id=command.optString("frameId").toLongOrNull()
                    if(id!=null && frames[id]?.let { SessionPolicy.fresh(it.atMs,now) }==true) { heldFrame=id; holdUntil=now+SessionPolicy.MAX_FRAME_AGE_MS }
                    else reply(JSONObject().put("type","error").put("message","That frame expired. Resume live view."))
                }
                "release" -> heldFrame=null
                "clear" -> { generation.incrementAndGet(); clearAnnotations(); reply(JSONObject().put("type","cleared")) }
                "undo" -> { annotations.values.lastOrNull { it.tool!="pointer" }?.let { remove(it.id) }; reply(JSONObject().put("type","changed").put("count",annotations.size)) }
                "remove" -> { remove(command.optString("id")); reply(JSONObject().put("type","changed").put("count",annotations.size)) }
                "draw" -> {
                    val request=command.optString("requestId")
                    if(!ledger.begin(request)) { ledger.reply(request)?.takeUnless { it=="pending" }?.let { reply(JSONObject(it)) }; return@repeat }
                    val result=runCatching {
                        check(tracking && !sharingPaused && sharing && server()?.hasHelper==true) { "Camera tracking or sharing is paused" }
                        val draw=SessionPolicy.parseDraw(command)
                        require(annotations.size<SessionPolicy.MAX_ANNOTATIONS) { "Annotation limit reached. Remove a mark first." }
                        val recorded=frames[draw.frameId] ?: error("Frame expired. Resume live view and draw again.")
                        check(SessionPolicy.fresh(recorded.atMs,now) && recorded.cameraAnchor.trackingState==TrackingState.TRACKING) { "Frame tracking is no longer reliable. Resume live view." }
                        val k=recorded.frame.intrinsics
                        val raw=draw.points.map { SurfaceGeometry.rawPixel(it,k.width,k.height,recorded.rotation) }
                        val hits=raw.map { SurfaceGeometry.resolve(recorded.frame,it,recorded.planes) ?: error("No reliable surface there. Ask the camera owner to move slightly, then try again.") }
                        for(i in 1 until hits.size) {
                            val span=SurfaceGeometry.distance(hits[i-1].world,hits[i].world)
                            require(span<4f && !(span>0.6f && hypot(draw.points[i].x-draw.points[i-1].x,draw.points[i].y-draw.points[i-1].y)<0.04f)) { "Shape crosses an uncertain depth edge. Draw a shorter mark." }
                        }
                        val oldPose=Pose(recorded.frame.pose.t,recorded.frame.pose.q)
                        val rebase=recorded.cameraAnchor.pose.compose(oldPose.inverse())
                        val points=hits.map { rebase.transformPoint(it.world) }
                        val pivot=points.size/2
                        val anchor=s.createAnchor(Pose.makeTranslation(points[pivot]))
                        val id="mark-${nextAnnotation++}"
                        val correctedFrame=recorded.frame.copy(pose=PosePacket(recorded.cameraAnchor.pose.translation,recorded.cameraAnchor.pose.rotationQuaternion),metricPoints=recorded.frame.metricPoints.map { m -> val p=rebase.transformPoint(floatArrayOf(m[2],m[3],m[4])); floatArrayOf(m[0],m[1],p[0],p[1],p[2]) })
                        val ref=SurfaceTargetReference(correctedFrame,floatArrayOf(raw[pivot].x,raw[pivot].y))
                        annotations[id]=Annotation(id,draw.tool,draw.color,draw.label,anchor,points.map { anchor.pose.inverse().transformPoint(it) },ref,if(draw.tool=="pointer") now+3000 else 0)
                        onStatus(lastStatus,annotations.size)
                        JSONObject().put("type","ack").put("requestId",request).put("annotationId",id).put("count",annotations.size).put("surface",hits[pivot].source)
                    }.getOrElse { JSONObject().put("type","error").put("requestId",request).put("message",it.message ?: "Could not place this mark") }
                    ledger.finish(request,result.toString()); reply(result)
                }
            }
        }
    }

    private fun capture(s:Session,frame:Frame,camera:Camera,now:Long) {
        val interval=if(sharing && !sharingPaused && server()?.hasHelper==true) 125L else 700L
        if(now-lastCapture<interval || !captureBusy.compareAndSet(false,true)) return
        var cameraAnchor:Anchor?=null
        try {
            val image=frame.acquireCameraImage()
            val sourceW=image.width; val sourceH=image.height
            val nv21=image.use { StreamCapture.copyNv21(it) }
            val scale=min(1f,1280f/sourceW); val w=(sourceW*scale).toInt(); val h=(sourceH*scale).toInt()
            val intr=camera.imageIntrinsics; val f=intr.focalLength; val c=intr.principalPoint
            val pose=camera.pose
            val metric=MetricSupportSampler.sample(frame,camera,5000).map { floatArrayOf(it[0]*scale,it[1]*scale,it[2],it[3],it[4]) }
            val data=CapturedFrame(frame.timestamp,PosePacket(pose.translation,pose.rotationQuaternion),IntrinsicsPacket(f[0]*scale,f[1]*scale,c[0]*scale,c[1]*scale,w,h),"",metric)
            val planes=s.getAllTrackables(Plane::class.java).filter { it.trackingState==TrackingState.TRACKING && it.subsumedBy==null }.take(32).map {
                val buffer=it.polygon.duplicate(); val polygon=FloatArray(buffer.remaining()); buffer.get(polygon)
                PlaneSnapshot(PosePacket(it.centerPose.translation,it.centerPose.rotationQuaternion),polygon)
            }
            cameraAnchor=s.createAnchor(pose)
            val id=nextFrame++; val rot=imageRotation(); val gen=generation.get()
            val stored=StoredFrame(id,data,rot,cameraAnchor,planes,now)
            val metadata=JSONObject().put("type","frame").put("frameId",id.toString()).put("width",if(rot%180==0) w else h).put("height",if(rot%180==0) h else w)
                .put("tracking",true).put("annotations",imageDrawings(data,rot,now)).put("count",annotations.size).put("depthPoints",metric.size)
            lastCapture=now
            encoder.execute {
                try {
                    val encoded=StreamCapture.encode(nv21,sourceW,sourceH,w,h,rot)
                    val record=stored.copy(frame=data.copy(jpegBase64=Base64.getEncoder().encodeToString(encoded.raw)))
                    ready.add(EncodedFrame(gen,record,LanSessionServer.packet(id,metadata,encoded.upright)))
                } catch(_:Exception) { ready.add(EncodedFrame(gen,stored,null)) }
                finally { captureBusy.set(false) }
            }
        } catch(_:Exception) { cameraAnchor?.detach(); captureBusy.set(false) }
    }

    private fun drainEncodings() {
        while(true) {
            val encoded=ready.poll() ?: break
            if(encoded.generation!=generation.get() || encoded.packet==null) { encoded.stored.cameraAnchor.detach(); continue }
            frames[encoded.stored.id]=encoded.stored
            if(sharing && !sharingPaused) server()?.latest=encoded.packet
            scheduleVerification(encoded.stored)
        }
    }
    private fun scheduleVerification(stored:StoredFrame) {
        val now=SystemClock.elapsedRealtime()
        if(now-lastVerify<850 || verifyBusy.get()) return
        val candidates=annotations.values.filter { it.tool!="pointer" && it.anchor.trackingState==TrackingState.TRACKING && SurfaceGeometry.project(stored.frame,it.anchor.pose.translation)?.let { p -> p.x in 0f..stored.frame.intrinsics.width.toFloat() && p.y in 0f..stored.frame.intrinsics.height.toFloat() }==true }
        if(candidates.isEmpty() || !verifyBusy.compareAndSet(false,true)) return
        val target=candidates[(verifyCursor++).mod(candidates.size)]
        val expected=target.anchor.pose.translation.copyOf(); val gen=generation.get(); val reference=target.reference; val id=target.id
        lastVerify=now
        verifier.execute {
            try {
                val coarse=SurfaceTargetResolver.resolve(reference,stored.frame,expected) ?: return@execute
                val result=SurfaceEdgeSnapRefiner.refine(reference,stored.frame,coarse,expected) ?: coarse
                if(result.visualInliers>=10 && result.medianReprojectionPx<=2.4f && result.depthSupports>=3) corrections.add(Correction(gen,id,stored.id,result))
            } catch(_:Exception) { /* Keep the existing anchor when visual evidence is unavailable. */ }
            finally { verifyBusy.set(false) }
        }
    }
    private fun applyCorrections(s:Session,now:Long) {
        while(true) {
            val correction=corrections.poll() ?: break
            if(correction.generation!=generation.get()) continue
            val target=annotations[correction.id] ?: continue
            val record=frames[correction.frameId] ?: continue
            if(now-record.atMs>2500 || record.cameraAnchor.trackingState!=TrackingState.TRACKING) continue
            val rebase=record.cameraAnchor.pose.compose(Pose(record.frame.pose.t,record.frame.pose.q).inverse())
            val point=rebase.transformPoint(correction.result.pointWorld)
            val delta=SurfaceGeometry.distance(point,target.anchor.pose.translation)
            if(delta>0.12f) { target.votes=0; continue }
            if(delta<0.008f) { target.verifiedAt=now; continue }
            if(target.vote!=null && now-target.voteAt<2500 && SurfaceGeometry.distance(target.vote!!,point)<0.025f) target.votes++ else target.votes=1
            target.vote=point; target.voteAt=now
            if(target.votes<2) continue
            val replacement=s.createAnchor(Pose.makeTranslation(point)); target.anchor.detach(); target.anchor=replacement
            target.verifiedAt=now; target.votes=0
        }
    }
    private fun imageDrawings(frame:CapturedFrame,rotation:Int,now:Long):JSONArray {
        val out=JSONArray(); val k=frame.intrinsics
        for(a in annotations.values) {
            if(a.anchor.trackingState!=TrackingState.TRACKING) continue
            val points=JSONArray()
            for(v in a.vertices) {
                val p=SurfaceGeometry.project(frame,a.anchor.pose.transformPoint(v))
                if(p==null) points.put(JSONObject.NULL) else { val uv=SurfaceGeometry.displayPixel(p,k.width,k.height,rotation); points.put(JSONArray().put(uv.x.toDouble()).put(uv.y.toDouble())) }
            }
            out.put(JSONObject().put("id",a.id).put("tool",a.tool).put("color",a.color).put("label",a.label).put("points",points).put("verified",now-a.verifiedAt<5000 && a.verifiedAt>0))
        }
        return out
    }
    private fun renderOverlay(camera:Camera,now:Long) {
        val view=FloatArray(16); val projection=FloatArray(16)
        camera.getViewMatrix(view,0); camera.getProjectionMatrix(projection,0,0.05f,100f)
        val out=annotations.values.filter { it.anchor.trackingState==TrackingState.TRACKING }.map { a ->
            val points=a.vertices.map { vertex ->
                val p=a.anchor.pose.transformPoint(vertex); val cv=FloatArray(4); val clip=FloatArray(4)
                Matrix.multiplyMV(cv,0,view,0,floatArrayOf(p[0],p[1],p[2],1f),0); Matrix.multiplyMV(clip,0,projection,0,cv,0)
                if(cv[2]>=-0.05 || abs(clip[3])<1e-6) null else PointF((clip[0]/clip[3]+1f)*0.5f*width,(1f-clip[1]/clip[3])*0.5f*height)
            }
            OverlayDrawing(a.id,a.tool,a.color,a.label,points,a.verifiedAt>0 && now-a.verifiedAt<5000)
        }
        overlay.update(out)
    }
    private fun pruneFrames(now:Long) {
        if(now>holdUntil) heldFrame=null
        val expired=frames.keys.filter { id -> id!=heldFrame && (frames.size>24 && id<nextFrame-24 || !SessionPolicy.fresh(frames.getValue(id).atMs,now)) }
        expired.forEach { frames.remove(it)?.cameraAnchor?.detach() }
    }
    private fun remove(id:String) { annotations.remove(id)?.anchor?.detach(); onStatus(lastStatus,annotations.size) }
    private fun clearAnnotations() { annotations.values.forEach { it.anchor.detach() }; annotations.clear(); corrections.clear(); overlay.update(emptyList()); onStatus(lastStatus,0) }
    private fun clearEverything() { clearAnnotations(); frames.values.forEach { it.cameraAnchor.detach() }; frames.clear(); heldFrame=null; server()?.latest=null }
    private fun reply(json:JSONObject) { server()?.send(json) }
}
