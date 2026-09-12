package com.sirpaul.stablear.nativeandroid

import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

private val CV_TO_GL = Pose(floatArrayOf(0f,0f,0f),floatArrayOf(1f,0f,0f,0f))
private fun Pose.pose7()=doubleArrayOf(tx().toDouble(),ty().toDouble(),tz().toDouble(),qx().toDouble(),qy().toDouble(),qz().toDouble(),qw().toDouble())
private fun pose7(v:DoubleArray)=Pose(floatArrayOf(v[0].toFloat(),v[1].toFloat(),v[2].toFloat()),floatArrayOf(v[3].toFloat(),v[4].toFloat(),v[5].toFloat(),v[6].toFloat()))
private fun Camera.stablePose7()=pose.compose(CV_TO_GL).pose7()
private fun Camera.nativeIntrinsics():NativeIntrinsics { val i=imageIntrinsics;val d=i.imageDimensions;return NativeIntrinsics(i.focalLength[0].toDouble(),i.focalLength[1].toDouble(),i.principalPoint[0].toDouble(),i.principalPoint[1].toDouble(),d[0],d[1]) }
private fun NativeIntrinsics.project(x:Double,y:Double,z:Double):Pair<Double,Double>?=if(z<.05)null else Pair(fx*x/z+cx,fy*y/z+cy)

data class NativeGrayImage(val width:Int,val height:Int,val bytes:ByteArray,val timestampNs:Long)

private class ArCoreAnchorStore(private val session:Session):PlatformAnchorStore {
    private var next=0L
    private val anchors=linkedMapOf<Long,Anchor>()
    override fun create(worldFromAnchor7:DoubleArray):Long {
        if(worldFromAnchor7.size!=7||worldFromAnchor7.any{!it.isFinite()})return 0
        val a=try{session.createAnchor(pose7(worldFromAnchor7))}catch(_:Exception){return 0}
        val id=++next;anchors[id]=a;return id
    }
    override fun locate(id:Long):DoubleArray?=anchors[id]?.takeIf{it.trackingState==TrackingState.TRACKING}?.pose?.pose7()
    override fun destroy(id:Long){anchors.remove(id)?.detach()}
    fun close(){anchors.values.forEach{it.detach()};anchors.clear()}
}

/** Host-owned ARCore adapter. Create/use/close on the same AR/render thread. */
class ArCoreNativeAdapter(private val arSession:Session):AutoCloseable {
    private val owner=Thread.currentThread()
    private val store=ArCoreAnchorStore(arSession)
    private val native=NativeStableArSession(store)
    private fun checkOwner(){check(Thread.currentThread()===owner){"ArCoreNativeAdapter must stay on its owner/render thread"}}

    val epoch:Long get(){checkOwner();return native.epoch}

    fun capture(frame:Frame,copyGray:Boolean=true):NativeCameraSample? {
        checkOwner();if(frame.timestamp<=0||frame.camera.trackingState!=TrackingState.TRACKING)return null
        val k=frame.camera.nativeIntrinsics();val ref=native.capture(frame.camera.stablePose7(),k,frame.timestamp)?:return null
        val gray=if(copyGray)copyGray(frame)else null
        return NativeCameraSample(ref,sampleDepth(frame,k),gray?.bytes,gray?.width?:0,gray?.height?:0)
    }
    fun freeze(frameId:Long)=native.freeze(frameId)
    fun unfreeze(frameId:Long)=native.unfreeze(frameId)
    fun place(sample:NativeCameraSample,x:Double,y:Double)=native.place(sample,x,y)
    fun context(id:Long,current:NativeCameraSample)=native.context(id,current.frame)
    fun observe(id:Long,current:NativeCameraSample,c:NativeObservationContext,x:Double,y:Double,inliers:Int,forwardBackwardPx:Double,reprojectionPx:Double,sigmaPx:Double=1.0)=native.observe(id,current.frame,c,x,y,inliers,forwardBackwardPx,reprojectionPx,sigmaPx)
    fun worldPoint(id:Long)=native.worldPoint(id)
    fun initialWorldPoint(id:Long)=native.initialWorldPoint(id)
    fun state(id:Long)=native.state(id)
    fun visibility(id:Long,visible:Boolean,lost:Boolean=false)=native.visibility(id,visible,lost)
    fun remove(id:Long)=native.remove(id)
    fun trackingLost()=native.trackingLost()
    fun reset()=native.reset()
    override fun close(){checkOwner();native.close();store.close()}

    companion object {
        fun copyGray(frame:Frame):NativeGrayImage?=try {
            frame.acquireCameraImage().use { image ->
                if(image.timestamp!=frame.timestamp)return null
                val p=image.planes[0];val b=p.buffer.duplicate();val start=b.position();val out=ByteArray(image.width*image.height)
                for(y in 0 until image.height)for(x in 0 until image.width)out[y*image.width+x]=b.get(start+y*p.rowStride+x*p.pixelStride)
                NativeGrayImage(image.width,image.height,out,image.timestamp)
            }
        }catch(_:Exception){null}

        fun sampleDepth(frame:Frame,k:NativeIntrinsics):List<NativeDepth> {
            val result=ArrayList<NativeDepth>(8000)
            fun read(image:android.media.Image,confidence:android.media.Image?,origin:Int){
                if(image.timestamp<=0||image.width*image.height>1024*1024)return
                if(confidence!=null&&(confidence.width!=image.width||confidence.height!=image.height))return
                val p=image.planes[0];val b=p.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);val start=b.position()
                val cp=confidence?.planes?.get(0);val cb=cp?.buffer?.duplicate();val cs=cb?.position()?:0
                val step=ceil(sqrt(image.width.toDouble()*image.height/8000)).toInt().coerceAtLeast(1)
                val transform=FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED,floatArrayOf(0f,0f,1f,0f,0f,1f),Coordinates2d.IMAGE_PIXELS,transform)
                for(y in step/2 until image.height step step)for(x in step/2 until image.width step step){
                    val z=(b.getShort(start+y*p.rowStride+x*p.pixelStride).toInt() and 65535)/1000.0;if(z !in .15..8.0)continue
                    val c=if(cb==null||cp==null).5 else (cb.get(cs+y*cp.rowStride+x*cp.pixelStride).toInt() and 255)/255.0;if(c<.5)continue
                    val tx=(x+.5)/image.width;val ty=(y+.5)/image.height
                    val u=transform[0]+tx*(transform[2]-transform[0])+ty*(transform[4]-transform[0]);val v=transform[1]+tx*(transform[3]-transform[1])+ty*(transform[5]-transform[1])
                    if(u.isFinite()&&v.isFinite()&&u>=0&&v>=0&&u<k.width&&v<k.height)result.add(NativeDepth(u,v,z,c,origin,image.timestamp))
                }
            }
            try{frame.acquireRawDepthImage16Bits().use{d->frame.acquireRawDepthConfidenceImage().use{c->read(d,c,0)}}}catch(_:Exception){}
            try{frame.acquireDepthImage16Bits().use{read(it,null,1)}}catch(_:Exception){}
            if(result.size<12)try{
                frame.acquirePointCloud().use{cloud->
                    if(cloud.timestamp<=0)return@use
                    val points=cloud.points;val worldToCv=frame.camera.pose.compose(CV_TO_GL).inverse()
                    for(i in 0 until min(2000,points.remaining()/4)){
                        val confidence=points.get(i*4+3).toDouble();if(confidence<.5)continue
                        val c=worldToCv.transformPoint(floatArrayOf(points.get(i*4),points.get(i*4+1),points.get(i*4+2)))
                        val px=k.project(c[0].toDouble(),c[1].toDouble(),c[2].toDouble())?:continue
                        if(c[2] in .15f..8f&&px.first>=0&&px.second>=0&&px.first<k.width&&px.second<k.height)result.add(NativeDepth(px.first,px.second,c[2].toDouble(),confidence,2,cloud.timestamp))
                    }
                }
            }catch(_:Exception){}
            return result
        }
    }
}
