package com.sirpaul.stablear.demo

import android.content.Context
import android.opengl.*
import com.google.ar.core.*
import com.sirpaul.stablear.arcore.*
import com.sirpaul.stablear.core.*
import com.sirpaul.stablear.vision.*
import java.nio.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.json.JSONObject
import kotlin.math.*

class LabRenderer(private val context: Context,private val status: (String)->Unit,private val rotation: ()->Int): GLSurfaceView.Renderer {
    @Volatile var session: Session?=null
    @Volatile var running=false
    @Volatile var compareInitial=true
    private var bound: Session?=null; private var adapter: ArCoreAdapter?=null
    private var width=1; private var height=1
    private val layer=CameraLayer()
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false); private val reset=AtomicBoolean(false)
    private val tracker=DemoVisionBackend(context)
    private val lifecycle=AtomicLong(1)
    private data class Displayed(val sample: CameraSample,val viewToImage: FloatArray,val width: Int,val height: Int)
    private data class Tap(val displayed: Displayed,val x: Float,val y: Float)
    private data class Result(val lifecycle: Long,val context: ObservationContext,val match: ImageMatch?)
    @Volatile private var presented: Displayed?=null
    private val tap=AtomicReference<Tap?>(null)
    private val answers=ConcurrentLinkedQueue<Result>()
    private val durations=ArrayDeque<Double>(); private var lastUi=0L; private var corrections=0
    private var lastTimestamp=0L; private var frames=0L; private var reason="Scan a textured surface"
    @Volatile private var metrics="{}"
    fun tap(x: Float,y: Float) { val d=presented ?: return; tap.set(Tap(d,x,y)) }
    fun clear() { reset.set(true) }
    fun invalidate() { presented=null; tap.set(null); lifecycle.incrementAndGet(); reset.set(true) }
    /** Queue on the current GL owner before GLSurfaceView.onPause acknowledges pause. */
    fun releaseArOnOwnerThread() {
        val ids=adapter?.engine?.snapshots()?.map { it.id }.orEmpty()
        adapter?.close(); adapter=null; bound=null; lastTimestamp=0L
        answers.clear(); reset.set(false)
        worker.execute { ids.forEach(tracker::remove) }
    }
    fun exportMetrics()=metrics
    fun shutdown() { lifecycle.incrementAndGet(); worker.execute { tracker.close() }; worker.shutdown() }
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) { layer.create(); bound=null }
    override fun onSurfaceChanged(gl: GL10?,w: Int,h: Int) { width=w.coerceAtLeast(1); height=h.coerceAtLeast(1); GLES20.glViewport(0,0,width,height) }
    override fun onDrawFrame(gl: GL10?) {
        if(!running) return
        val s=session ?: return
        val start=System.nanoTime()
        try {
            if(bound!==s) { adapter?.close(); adapter=ArCoreAdapter(s); s.setCameraTextureName(layer.texture); bound=s }
            val sdk=adapter!!
            if(reset.getAndSet(false)) {
                val ids=sdk.engine.snapshots().map { it.id }
                sdk.reset(); answers.clear(); presented=null
                worker.execute { ids.forEach(tracker::remove) }
            }
            s.setDisplayGeometry(rotation(),width,height)
            val frame=s.update()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            layer.camera(frame)
            if(frame.camera.trackingState!=TrackingState.TRACKING) {
                sdk.trackingLost(); presented=null; reason="Tracking paused: ${frame.camera.trackingFailureReason}"
                report(start,sdk); return
            }
            while(true) {
                val result=answers.poll() ?: break
                if(result.lifecycle!=lifecycle.get()) continue
                val c=result.context; val match=result.match
                if(match==null) { sdk.engine.visibility(c.id,false); continue }
                val o=VisualObservation(c.frame.id,c.root.epoch,c.root.anchorId,c.generation,
                    c.frame.cameraTimestampNs,c.frame.capturedNs,c.cameraInAnchor,c.frame.intrinsics,
                    match.pixel,match.inliers,match.forwardBackwardPx,match.medianReprojectionPx,match.sigmaPx)
                val decision=sdk.observe(c.id,o); reason="${match.method}: ${decision.reason}"
                if(decision.accepted) corrections++
            }
            tap.getAndSet(null)?.let { command ->
                val a=command.displayed.viewToImage
                val x=a[0]+a[2]*command.x/command.displayed.width+a[4]*command.y/command.displayed.height
                val y=a[1]+a[3]*command.x/command.displayed.width+a[5]*command.y/command.displayed.height
                if(sdk.engine.snapshots().size<8 && x.isFinite() && y.isFinite()) {
                    val pixel=V2(x.toDouble(),y.toDouble())
                    val p=sdk.place(command.displayed.sample,pixel)
                    val gray=command.displayed.sample.gray
                    if(p==null) reason="No supported surface at this pixel. Move sideways and retry."
                    else if(gray!=null) {
                        worker.execute { tracker.add(p.attachment.id,gray.timestampNs,gray.bytes,gray.width,gray.height,pixel) }
                        reason="Anchored. XFeat/LiteRT preferred; LK/ORB fallback. Move sideways for verification."
                    } else reason="Anchored with depth; visual reference unavailable."
                }
            }
            val cameraFromWorld=frame.camera.worldFromCv().inverse()
            val calibration=frame.camera.calibration()
            fun project(world: V3?): FloatArray? {
                if(world==null) return null
                val px=calibration.project(cameraFromWorld.point(world)) ?: return null
                val out=FloatArray(2)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,floatArrayOf(px.x.toFloat(),px.y.toFloat()),Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,out)
                return out.takeIf { it.all(Float::isFinite) }
            }
            val snapshots=sdk.engine.snapshots()
            if(compareInitial) layer.points(snapshots.mapNotNull { project(sdk.initialWorldPoint(it.id)) },true)
            // Both paired references and current annotations share the camera GL frame.
            layer.points(snapshots.mapNotNull { project(sdk.worldPoint(it.id)) })
            if(frame.timestamp!=lastTimestamp) {
                lastTimestamp=frame.timestamp
                val sample=sdk.capture(frame)
                if(sample!=null) {
                    val map=FloatArray(6)
                    frame.transformCoordinates2d(Coordinates2d.VIEW,floatArrayOf(0f,0f,width.toFloat(),0f,0f,height.toFloat()),Coordinates2d.IMAGE_PIXELS,map)
                    val affine=floatArrayOf(map[0],map[1],map[2]-map[0],map[3]-map[1],map[4]-map[0],map[5]-map[1])
                    presented=Displayed(sample,affine,width,height)
                    val gray=sample.gray
                    if(gray!=null) {
                        val contexts=sdk.engine.snapshots().mapNotNull { sdk.context(it.id,sample,frame) }
                        // No material attachment means no useful learned observation. Avoid waking LiteRT/GPU
                        // just because the camera produced a new frame; this saves thermal and battery budget.
                        if(contexts.isNotEmpty() && busy.compareAndSet(false,true)) {
                            val predictions=contexts.associate { c -> c.id to sdk.engine.snapshot(c.id)?.let {
                                c.frame.intrinsics.project(c.cameraInAnchor.inverse().point(it.pointInAnchor())) } }
                            val generation=lifecycle.get()
                            worker.execute {
                                try {
                                    tracker.beginFrame(gray.timestampNs,gray.bytes,gray.width,gray.height)
                                    contexts.forEach { c ->
                                        if(generation==lifecycle.get()) answers.add(Result(generation,c,tracker.track(c.id,predictions[c.id])))
                                    }
                                } catch(_: Exception) { /* Verification must never stop camera tracking. */ }
                                finally { busy.set(false) }
                            }
                        }
                    }
                }
            }
            frames++; report(start,sdk)
        } catch(e: Exception) { reason="${e.javaClass.simpleName}: ${e.message}"; if(System.nanoTime()-lastUi>500_000_000L) { status(reason); lastUi=System.nanoTime() } }
    }
    private fun report(start: Long,sdk: ArCoreAdapter) {
        val now=System.nanoTime(); durations.add((now-start)/1e6)
        while(durations.size>120) durations.removeFirst()
        if(now-lastUi<300_000_000L) return
        lastUi=now
        val p95=durations.sorted()[(durations.size*.95).toInt().coerceAtMost(durations.size-1)]
        val n=sdk.engine.snapshots().size
        status("StableAR Lab | $n anchors | $corrections accepted corrections\n$reason\nRender CPU p95: ${"%.1f".format(p95)} ms | Yellow: initial, green: refined")
        metrics=JSONObject().put("sdk","0.2.0-xfeat").put("model",android.os.Build.MODEL)
            .put("frames",frames).put("anchors",n).put("acceptedCorrections",corrections)
            .put("renderCpuP95Ms",p95).put("historyAnchors",sdk.history.anchorCount())
            .put("attachments",org.json.JSONArray().also { array ->
                sdk.engine.snapshots().forEach { s -> array.put(JSONObject().put("id",s.id).put("state",s.state.name)
                    .put("depthM",s.depthM).put("conditionalSigmaM",s.conditionalSigmaM).put("travelM",s.travelM)
                    .put("refinementDisplacementM",sdk.initialWorldPoint(s.id)?.let { initial ->
                        sdk.worldPoint(s.id)?.let { (it-initial).norm() } } ?: JSONObject.NULL)) }
            }).put("physicalAccuracyMeasured",false).put("cameraImagesIncluded",false)
            .put("reason",reason).toString(2)
    }
}

private class CameraLayer {
    var texture=0; private set
    private var cameraProgram=0; private var pointProgram=0
    private val quad=floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)
    private val uv=FloatArray(8)
    private fun buffer(data: FloatArray)=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
    private fun program(v: String,f: String): Int {
        fun shader(kind: Int,code: String): Int {
            val s=GLES20.glCreateShader(kind); GLES20.glShaderSource(s,code); GLES20.glCompileShader(s)
            val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { GLES20.glGetShaderInfoLog(s) }; return s
        }
        val vs=shader(GLES20.GL_VERTEX_SHADER,v); val fs=shader(GLES20.GL_FRAGMENT_SHADER,f)
        val p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p)
        val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0) { GLES20.glGetProgramInfoLog(p) }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs); return p
    }
    fun create() {
        val out=IntArray(1); GLES20.glGenTextures(1,out,0); texture=out[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        for(parameter in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,parameter,GLES20.GL_LINEAR)
        for(parameter in intArrayOf(GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,parameter,GLES20.GL_CLAMP_TO_EDGE)
        cameraProgram=program("attribute vec2 pos; attribute vec2 tex; varying vec2 v; void main(){v=tex;gl_Position=vec4(pos,0.,1.);}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 v; void main(){gl_FragColor=texture2D(image,v);}")
        pointProgram=program("attribute vec2 pos; uniform float diameter; void main(){gl_Position=vec4(pos,0.,1.);gl_PointSize=diameter;}",
            "precision mediump float; uniform vec4 tint; void main(){float r=length(gl_PointCoord-vec2(.5));if(r>.48||r<.32)discard;gl_FragColor=tint;}")
    }
    fun camera(frame: Frame) {
        if(frame.timestamp==0L) return
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,quad,Coordinates2d.TEXTURE_NORMALIZED,uv)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram,"image"),0)
        val p=GLES20.glGetAttribLocation(cameraProgram,"pos"); val t=GLES20.glGetAttribLocation(cameraProgram,"tex")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(t)
        GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,buffer(quad)); GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,0,buffer(uv))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(t)
    }
    fun points(points: List<FloatArray>,initial: Boolean=false) {
        if(points.isEmpty()) return
        GLES20.glUseProgram(pointProgram); val p=GLES20.glGetAttribLocation(pointProgram,"pos")
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pointProgram,"diameter"),if(initial) 36f else 28f)
        if(initial) GLES20.glUniform4f(GLES20.glGetUniformLocation(pointProgram,"tint"),1f,.8f,.15f,1f)
        else GLES20.glUniform4f(GLES20.glGetUniformLocation(pointProgram,"tint"),.1f,1f,.75f,1f)
        GLES20.glEnableVertexAttribArray(p)
        GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,buffer(points.flatMap { it.toList() }.toFloatArray()))
        GLES20.glDrawArrays(GLES20.GL_POINTS,0,points.size); GLES20.glDisableVertexAttribArray(p)
    }
}
