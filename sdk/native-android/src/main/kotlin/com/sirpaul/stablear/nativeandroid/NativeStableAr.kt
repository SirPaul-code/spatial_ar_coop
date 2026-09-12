package com.sirpaul.stablear.nativeandroid

internal object NativeStableAr {
    init { System.loadLibrary("stablear_jni") }
    external fun create(anchorStore: PlatformAnchorStore): Long
    external fun destroy(handle: Long)
    external fun epoch(handle: Long): Long
    external fun capture(handle: Long, pose7: DoubleArray, k4: DoubleArray, width: Int, height: Int, timestampNs: Long): Long
    external fun freeze(handle: Long, frameId: Long): Boolean
    external fun unfreeze(handle: Long, frameId: Long)
    external fun place(handle: Long, frameId: Long, depth4: DoubleArray, origins: IntArray, evidenceNs: LongArray, x: Double, y: Double): Long
    external fun contextIds(handle: Long, attachmentId: Long, frameId: Long): LongArray?
    external fun contextPose(handle: Long, attachmentId: Long, frameId: Long): DoubleArray?
    external fun observe(handle: Long, attachmentId: Long, frameId: Long, epoch: Long, anchorId: Long, generation: Long,
        cameraTimestampNs: Long, capturedNs: Long, cameraInAnchor7: DoubleArray, k4: DoubleArray, width: Int, height: Int,
        x: Double, y: Double, inliers: Int, forwardBackwardPx: Double, reprojectionPx: Double, sigmaPx: Double): Int
    external fun worldPoint(handle: Long, attachmentId: Long): DoubleArray?
    external fun initialWorldPoint(handle: Long, attachmentId: Long): DoubleArray?
    external fun snapshot(handle: Long, attachmentId: Long): DoubleArray?
    external fun visibility(handle: Long, attachmentId: Long, visible: Boolean, lost: Boolean)
    external fun remove(handle: Long, attachmentId: Long)
    external fun trackingLost(handle: Long)
    external fun reset(handle: Long)
    external fun monotonicNowNs(): Long
    external fun entitlementClaimsStatus(token: String, product: String, platform: String, appId: String, feature: String, nowUnixS: Long, signatureValid: Boolean): Int
}

interface PlatformAnchorStore {
    /** worldFromAnchor = tx,ty,tz,qx,qy,qz,qw. Return non-zero stable ID or 0 on failure. */
    fun create(worldFromAnchor7: DoubleArray): Long
    fun locate(id: Long): DoubleArray?
    fun destroy(id: Long)
}

data class NativeIntrinsics(val fx: Double,val fy: Double,val cx: Double,val cy: Double,val width: Int,val height: Int) {
    init { require(listOf(fx,fy,cx,cy).all(Double::isFinite) && fx>0 && fy>0 && width>0 && height>0) }
    internal fun k4()=doubleArrayOf(fx,fy,cx,cy)
}
data class NativeDepth(val x: Double,val y: Double,val z: Double,val confidence: Double,val origin: Int,val sourceTimestampNs: Long) {
    init { require(listOf(x,y,z,confidence).all(Double::isFinite)); require(z>0 && confidence in 0.0..1.0 && origin in 0..3 && sourceTimestampNs>0) }
}
data class NativeFrameRef(val id: Long,val epoch: Long,val cameraTimestampNs: Long,val intrinsics: NativeIntrinsics)
data class NativeCameraSample(val frame: NativeFrameRef,val depth: List<NativeDepth>,val gray: ByteArray?=null,val grayWidth: Int=0,val grayHeight: Int=0)
data class NativeObservationContext(val generation: Long,val anchorId: Long,val cameraInAnchor7: DoubleArray)
enum class NativeLockState { UNVERIFIED, GEOMETRY_SUPPORTED, OCCLUDED, UNCERTAIN, RELOCALIZING, LOST }
data class NativeAttachmentState(val generation: Long,val depthM: Double,val conditionalSigmaM: Double,val state: NativeLockState,val travelM: Double)

class NativeStableArSession(private val anchors: PlatformAnchorStore): AutoCloseable {
    private val ownerThread=Thread.currentThread()
    private var handle=NativeStableAr.create(anchors).also { check(it!=0L){"Could not create StableAR native session"} }
    private fun checkOwner(){check(Thread.currentThread()===ownerThread){"StableAR session must stay on its owner thread"}}
    private fun h():Long{checkOwner();check(handle!=0L){"StableAR session is closed"};return handle}
    val epoch:Long get()=NativeStableAr.epoch(h())
    fun capture(worldFromCamera7:DoubleArray,k:NativeIntrinsics,timestampNs:Long):NativeFrameRef? { require(worldFromCamera7.size==7&&worldFromCamera7.all(Double::isFinite)&&timestampNs>0);val id=NativeStableAr.capture(h(),worldFromCamera7,k.k4(),k.width,k.height,timestampNs);return id.takeIf{it!=0L}?.let{NativeFrameRef(it,epoch,timestampNs,k)} }
    fun freeze(frameId:Long):Boolean{require(frameId>0);return NativeStableAr.freeze(h(),frameId)}
    fun unfreeze(frameId:Long){require(frameId>0);NativeStableAr.unfreeze(h(),frameId)}
    fun place(sample:NativeCameraSample,x:Double,y:Double):Long? {require(x.isFinite()&&y.isFinite());val d=DoubleArray(sample.depth.size*4);val o=IntArray(sample.depth.size);val t=LongArray(sample.depth.size);sample.depth.forEachIndexed{i,v->d[4*i]=v.x;d[4*i+1]=v.y;d[4*i+2]=v.z;d[4*i+3]=v.confidence;o[i]=v.origin;t[i]=v.sourceTimestampNs};return NativeStableAr.place(h(),sample.frame.id,d,o,t,x,y).takeIf{it!=0L}}
    fun context(id:Long,current:NativeFrameRef):NativeObservationContext? {require(id>0);val ids=NativeStableAr.contextIds(h(),id,current.id)?:return null;val p=NativeStableAr.contextPose(h(),id,current.id)?:return null;return if(ids.size==2&&p.size==7)NativeObservationContext(ids[0],ids[1],p)else null}
    /** Returns true only when this held-out observation actually committed a correction. */
    fun observe(id:Long,current:NativeFrameRef,c:NativeObservationContext,x:Double,y:Double,inliers:Int,fb:Double,reproj:Double,sigma:Double=1.0,capturedNs:Long=NativeStableAr.monotonicNowNs()):Boolean {require(id>0&&c.generation>0&&c.anchorId>0&&c.cameraInAnchor7.size==7);require(x.isFinite()&&y.isFinite()&&inliers>=0&&fb.isFinite()&&fb>=0&&reproj.isFinite()&&reproj>=0&&sigma.isFinite()&&sigma>0&&capturedNs>=0);return NativeStableAr.observe(h(),id,current.id,current.epoch,c.anchorId,c.generation,current.cameraTimestampNs,capturedNs,c.cameraInAnchor7,current.intrinsics.k4(),current.intrinsics.width,current.intrinsics.height,x,y,inliers,fb,reproj,sigma)==1}
    fun worldPoint(id:Long):DoubleArray?{require(id>0);return NativeStableAr.worldPoint(h(),id)}
    fun initialWorldPoint(id:Long):DoubleArray?{require(id>0);return NativeStableAr.initialWorldPoint(h(),id)}
    fun state(id:Long):NativeAttachmentState?{require(id>0);val x=NativeStableAr.snapshot(h(),id)?:return null;if(x.size!=5||x.any{!it.isFinite()})return null;return NativeAttachmentState(x[0].toLong(),x[1],x[2],NativeLockState.entries.getOrElse(x[3].toInt()){NativeLockState.LOST},x[4])}
    fun visibility(id:Long,visible:Boolean,lost:Boolean=false){require(id>0);NativeStableAr.visibility(h(),id,visible,lost)}
    fun remove(id:Long){require(id>0);NativeStableAr.remove(h(),id)}
    fun trackingLost(){NativeStableAr.trackingLost(h())}
    fun reset(){NativeStableAr.reset(h())}
    override fun close(){checkOwner();if(handle!=0L){val old=handle;handle=0;NativeStableAr.destroy(old)}}
}
