package com.sirpaul.stablear.arcore

import com.google.ar.core.*
import com.sirpaul.stablear.core.*
import java.nio.ByteOrder
import kotlin.math.*

/** ARCore camera looks -Z with +Y up. Apply this conversion ONCE at the boundary. */
fun Pose.rigid()=Rigid(V3(tx().toDouble(),ty().toDouble(),tz().toDouble()),
    Q.normalized(qx().toDouble(),qy().toDouble(),qz().toDouble(),qw().toDouble()))
fun Rigid.arPose()=Pose(floatArrayOf(t.x.toFloat(),t.y.toFloat(),t.z.toFloat()),
    floatArrayOf(q.x.toFloat(),q.y.toFloat(),q.z.toFloat(),q.w.toFloat()))
private val CV_TO_GL=Rigid(V3.ZERO,Q(1.0,0.0,0.0,0.0))
fun Camera.worldFromCv()=pose.rigid()*CV_TO_GL
fun Camera.calibration(): Intrinsics {
    val k=imageIntrinsics; val d=k.imageDimensions
    return Intrinsics(k.focalLength[0].toDouble(),k.focalLength[1].toDouble(),
        k.principalPoint[0].toDouble(),k.principalPoint[1].toDouble(),d[0],d[1])
}
data class GrayImage(val width: Int,val height: Int,val bytes: ByteArray,val timestampNs: Long)
data class CameraSample(val ref: FrameRef,val depth: List<DepthSample>,val gray: GrayImage?)
data class Placement(val attachment: AttachmentSnapshot,val fit: SurfaceFit)
data class PlacementAttempt(
    val placement: Placement?,
    val mode: String,
    val reason: String,
    val depthSamples: Int,
    val nearbySamples: Int,
    val nearestSupportPx: Double?,
)
data class ObservationContext(val id: Long,val generation: Long,val root: RootReference,val frame: FrameRef,
    val cameraInAnchor: Rigid)

/** Owns no camera or network session. Host calls exclusively on its AR render thread. */
class ArCoreAdapter(private val session: Session,private val clock: ()->Long=System::nanoTime,
    policy: LockPolicy=LockPolicy()) : AutoCloseable {
    private val thread=Thread.currentThread().id
    private var anchorSequence=0L
    private val attachments=linkedMapOf<Long,Anchor>()
    private val pending=linkedMapOf<Long,LockProposal>()
    private val initialPoints=linkedMapOf<Long,V3>()
    val engine=AttachmentEngine(clock,policy)
    val history=FrameLedger(AnchorFactory { pose ->
        val anchor=session.createAnchor(pose.arPose()); val token=++anchorSequence
        object: AnchorHandle {
            override val id=token
            override fun worldFromAnchor()=if(anchor.trackingState==TrackingState.TRACKING) anchor.pose.rigid() else null
            override fun close() { anchor.detach() }
        }
    },clock)
    private fun owner() { check(thread==Thread.currentThread().id) { "ARCoreAdapter must be used on its owner/render thread" } }
    fun capture(frame: Frame,copyGray: Boolean=true): CameraSample? {
        owner(); if(frame.timestamp<=0 || frame.camera.trackingState!=TrackingState.TRACKING) return null
        val k=frame.camera.calibration()
        val ref=history.capture(frame.camera.worldFromCv(),k,frame.timestamp) ?: return null
        val samples=sampleDepth(frame,k)
        val gray=if(copyGray) Companion.copyGray(frame) else null
        return CameraSample(ref,samples,gray)
    }
    fun freeze(frameId: Long): FrameRef? { owner(); return history.freeze(frameId) }
    fun unfreeze(frameId: Long) { owner(); history.unfreeze(frameId) }

    /** Application placement is interactive/foreground-aware by definition. */
    fun place(sample: CameraSample,pixel: V2): Placement? = placeInteractive(sample,pixel).placement

    /**
     * User-facing placement. Resolve local depth layers before accepting the strict plane result:
     * a large background plane is not allowed to beat a thinner foreground object solely because
     * it has more smoothed-depth samples.
     */
    fun placeInteractive(sample: CameraSample,pixel: V2): PlacementAttempt {
        owner()
        val k=sample.ref.intrinsics
        val radius=max(24.0,k.width*.04)
        val valid=sample.depth.filter { it.confidence>=.5 && it.z in .15..8.0 }
        val nearby=valid.filter { (it.pixel-pixel).norm()<=radius }
        val nearest=valid.minOfOrNull { (it.pixel-pixel).norm() }
        val raw=nearby.count { it.evidence.origin==DepthOrigin.RAW }
        val cloud=nearby.count { it.evidence.origin==DepthOrigin.POINT_CLOUD }
        val smooth=nearby.count { it.evidence.origin==DepthOrigin.SMOOTHED }
        fun rejected(reason:String)=PlacementAttempt(null,"REJECTED",reason,sample.depth.size,nearby.size,nearest)
        if(attachments.size>=64) return rejected("StableAR attachment capacity reached")
        if(!k.contains(pixel)) return rejected("Mapped helper pixel is outside the AR camera image")
        val cameraNow=history.currentWorldFromCamera(sample.ref)
            ?: return rejected("The retained ARCore frame/anchor is no longer tracking")
        if(sample.depth.isEmpty()) return rejected("ARCore returned no depth or point-cloud evidence for this exact video frame")
        if(valid.isEmpty()) return rejected("ARCore evidence exists, but none has usable confidence/range")

        // Interactive layer selection is authoritative for an actual user click. Strict fitting is
        // retained only as an agreement signal; it may not override an ambiguous/foreground result.
        val fit=SurfaceFitter.fitInteractive(k,pixel,sample.depth,radius)
        if(fit==null) {
            val nearText=if(nearest==null) "none" else "%.1f px".format(java.util.Locale.US,nearest)
            return rejected("No unambiguous local surface: total=${sample.depth.size}, local=${nearby.size} (raw=$raw, cloud=$cloud, smooth=$smooth), nearest=$nearText")
        }
        val strict=SurfaceFitter.fit(k,pixel,sample.depth)
        val agreement=strict?.let { abs(it.depth-fit.depth)<=max(.04,fit.depth*.04) } == true
        val mode=when {
            agreement -> "STRICT_CONFIRMED"
            strict!=null -> "FOREGROUND_OVERRIDE"
            else -> "FOREGROUND_AWARE"
        }
        val placement=createPlacement(sample,pixel,cameraNow,fit)
            ?: return rejected("Metric surface fit succeeded, but ARCore could not create/retain its anchor")
        val origins=fit.evidence.map { it.origin.name }.toSet().sorted().joinToString("+")
        return PlacementAttempt(placement,mode,
            "$mode: ${fit.supportCount} supports [$origins], z=${"%.3f".format(java.util.Locale.US,fit.depth)} m; local raw=$raw cloud=$cloud smooth=$smooth",
            sample.depth.size,nearby.size,nearest)
    }

    private fun createPlacement(sample: CameraSample,pixel: V2,cameraNow: Rigid,fit: SurfaceFit): Placement? {
        val world=cameraNow.point(sample.ref.intrinsics.ray(pixel)*fit.depth)
        val anchor=try { session.createAnchor(Rigid(world).arPose()) } catch(_: Exception) { return null }
        try {
            val root=RootReference(sample.ref.id,sample.ref.epoch,++anchorSequence,sample.ref.cameraTimestampNs,
                anchor.pose.rigid().inverse()*cameraNow,sample.ref.intrinsics,pixel)
            val s=engine.create(root,fit); attachments[s.id]=anchor; initialPoints[s.id]=s.pointInAnchor()
            return Placement(s,fit)
        } catch(t: Throwable) { anchor.detach(); throw t }
    }

    fun context(id: Long,sample: CameraSample,frame: Frame): ObservationContext? {
        owner(); val s=engine.snapshot(id) ?: return null
        val a=attachments[id] ?: return null
        if(a.trackingState!=TrackingState.TRACKING || frame.camera.trackingState!=TrackingState.TRACKING ||
            sample.ref.epoch!=history.epoch || sample.ref.cameraTimestampNs!=frame.timestamp) return null
        return ObservationContext(id,s.generation,s.root,sample.ref,a.pose.rigid().inverse()*frame.camera.worldFromCv())
    }
    /** A result does not replace the ARCore anchor. Only its original-ray local geometry is updated. */
    fun observe(id: Long,observation: VisualObservation): LockDecision {
        owner(); val s=engine.snapshot(id)
        if(attachments[id]?.trackingState!=TrackingState.TRACKING) return LockDecision(false,"Anchor is not tracking",s)
        val proposal=pending[id]
        if(proposal!=null) {
            if(observation.generation!=proposal.generation) pending.remove(id)
            else {
                val decision=engine.commit(proposal,observation)
                if(decision.accepted) { pending.remove(id); return decision }
            }
        }
        engine.offer(id,observation)?.let { pending[id]=it }
        return LockDecision(false,"Collecting diverse independent views",engine.snapshot(id))
    }
    fun worldPoint(id: Long): V3? {
        owner(); val a=attachments[id] ?: return null; val s=engine.snapshot(id) ?: return null
        if(a.trackingState!=TrackingState.TRACKING) return null
        return a.pose.rigid().point(s.pointInAnchor())
    }
    /** Paired unrefined reference on the SAME native anchor; not physical ground truth. */
    fun initialWorldPoint(id: Long): V3? {
        owner(); val a=attachments[id] ?: return null; val point=initialPoints[id] ?: return null
        return if(a.trackingState==TrackingState.TRACKING) a.pose.rigid().point(point) else null
    }
    fun remove(id: Long) { owner(); attachments.remove(id)?.detach(); engine.remove(id); pending.remove(id); initialPoints.remove(id) }
    fun trackingLost() { owner(); pending.clear(); engine.snapshots().forEach { engine.visibility(it.id,false,true) } }
    fun reset() { owner(); attachments.values.forEach { it.detach() }; attachments.clear(); pending.clear(); initialPoints.clear(); engine.clear(); history.reset() }
    override fun close()=reset()

    companion object {
        /** Copy plane strides faithfully. Images are always closed on the acquiring thread. */
        fun copyGray(frame: Frame): GrayImage? = try {
            frame.acquireCameraImage().use { image ->
                // Never associate a different CPU camera exposure with this frame's pose.
                if(image.timestamp!=frame.timestamp) return null
                val p=image.planes[0]; val b=p.buffer.duplicate(); val start=b.position()
                val out=ByteArray(image.width*image.height)
                for(y in 0 until image.height) for(x in 0 until image.width)
                    out[y*image.width+x]=b.get(start+y*p.rowStride+x*p.pixelStride)
                GrayImage(image.width,image.height,out,image.timestamp)
            }
        } catch(_: Exception) { null }

        fun sampleDepth(frame: Frame,k: Intrinsics): List<DepthSample> {
            val result=ArrayList<DepthSample>(10000)
            fun read(image: android.media.Image,confidence: android.media.Image?,origin: DepthOrigin) {
                if(image.timestamp<=0 || image.width*image.height>1024*1024) return
                if(confidence!=null && (confidence.width!=image.width || confidence.height!=image.height)) return
                val p=image.planes[0]; val b=p.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); val start=b.position()
                val cp=confidence?.planes?.get(0); val cb=cp?.buffer?.duplicate(); val cs=cb?.position() ?: 0
                val step=ceil(sqrt(image.width.toDouble()*image.height/8000)).toInt().coerceAtLeast(1)
                val coordinates=FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,floatArrayOf(0f,0f,1f,0f,0f,1f),Coordinates2d.IMAGE_PIXELS,coordinates)
                val source=EvidenceId(origin,image.timestamp)
                for(y in step/2 until image.height step step) for(x in step/2 until image.width step step) {
                    val z=(b.getShort(start+y*p.rowStride+x*p.pixelStride).toInt() and 65535)/1000.0
                    if(z !in .15..8.0) continue
                    val c=if(cb==null || cp==null) .5 else (cb.get(cs+y*cp.rowStride+x*cp.pixelStride).toInt() and 255)/255.0
                    if(c<.5) continue
                    val tx=(x+.5)/image.width; val ty=(y+.5)/image.height
                    val u=coordinates[0]+tx*(coordinates[2]-coordinates[0])+ty*(coordinates[4]-coordinates[0])
                    val v=coordinates[1]+tx*(coordinates[3]-coordinates[1])+ty*(coordinates[5]-coordinates[1])
                    if(!u.isFinite() || !v.isFinite()) continue
                    val px=V2(u,v); if(!k.contains(px)) continue
                    result.add(DepthSample(px,z,c,source))
                }
            }
            try { frame.acquireRawDepthImage16Bits().use { d -> frame.acquireRawDepthConfidenceImage().use { c -> read(d,c,DepthOrigin.RAW) } } } catch(_: Exception) { }
            try { frame.acquireDepthImage16Bits().use { read(it,null,DepthOrigin.SMOOTHED) } } catch(_: Exception) { }

            // Point-cloud features are an independent metric cue and are especially valuable on a
            // thin textured foreground object. Never suppress them merely because a smoothed depth
            // image exists; that was exactly how a background monitor could dominate a PCB click.
            try {
                frame.acquirePointCloud().use { cloud ->
                    if(cloud.timestamp<=0) return@use
                    val buf=cloud.points
                    val count=buf.remaining()/4
                    if(count<=0) return@use
                    val inv=frame.camera.worldFromCv().inverse()
                    val evidence=EvidenceId(DepthOrigin.POINT_CLOUD,cloud.timestamp)
                    val step=max(1,ceil(count/1500.0).toInt())
                    for(i in 0 until count step step) {
                        val c=buf.get(i*4+3).toDouble(); if(c<.5) continue
                        val p=inv.point(V3(buf.get(i*4).toDouble(),buf.get(i*4+1).toDouble(),buf.get(i*4+2).toDouble()))
                        val px=k.project(p) ?: continue
                        if(p.z in .15..8.0 && k.contains(px)) result.add(DepthSample(px,p.z,c,evidence))
                    }
                }
            } catch(_: Exception) { }
            return result.toList()
        }
    }
}
