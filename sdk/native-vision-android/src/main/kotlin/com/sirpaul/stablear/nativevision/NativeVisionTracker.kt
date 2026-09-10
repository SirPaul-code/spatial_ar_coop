package com.sirpaul.stablear.nativevision

internal object NativeVision {
    init { System.loadLibrary("stablear_vision_jni") }
    external fun create():Long
    external fun destroy(handle:Long)
    external fun addRoot(handle:Long,id:Long,gray:ByteArray,width:Int,height:Int,x:Double,y:Double):Boolean
    external fun beginFrame(handle:Long,frameId:Long,gray:ByteArray,width:Int,height:Int):Boolean
    external fun track(handle:Long,id:Long,hasPrediction:Boolean,predictedX:Double,predictedY:Double):DoubleArray?
    external fun remove(handle:Long,id:Long)
    external fun clear(handle:Long)
}

enum class NativeVisionMethod { NONE, LK_ROOT, ORB_ROOT }
data class NativeImageMatch(val x:Double,val y:Double,val inliers:Int,val medianReprojectionPx:Double,val forwardBackwardPx:Double,val method:NativeVisionMethod)

/** One instance belongs to one bounded CV worker thread. */
class NativeVisionTracker:AutoCloseable {
    private val owner=Thread.currentThread();private var handle=NativeVision.create().also{check(it!=0L)}
    private fun h():Long{check(Thread.currentThread()===owner){"NativeVisionTracker must stay on its worker thread"};check(handle!=0L){"NativeVisionTracker is closed"};return handle}
    fun addRoot(id:Long,gray:ByteArray,width:Int,height:Int,x:Double,y:Double):Boolean{require(id>0&&width>0&&height>0&&gray.size==width*height&&x.isFinite()&&y.isFinite());return NativeVision.addRoot(h(),id,gray,width,height,x,y)}
    fun beginFrame(frameId:Long,gray:ByteArray,width:Int,height:Int):Boolean{require(frameId>0&&width>0&&height>0&&gray.size==width*height);return NativeVision.beginFrame(h(),frameId,gray,width,height)}
    fun track(id:Long,predictedX:Double?=null,predictedY:Double?=null):NativeImageMatch?{require(id>0);require((predictedX==null)==(predictedY==null));val x=predictedX?:0.0;val y=predictedY?:0.0;val v=NativeVision.track(h(),id,predictedX!=null,x,y)?:return null;if(v.size!=6||v.any{!it.isFinite()})return null;return NativeImageMatch(v[0],v[1],v[2].toInt(),v[3],v[4],NativeVisionMethod.entries.getOrElse(v[5].toInt()){NativeVisionMethod.NONE})}
    fun remove(id:Long){require(id>0);NativeVision.remove(h(),id)}
    fun clear()=NativeVision.clear(h())
    override fun close(){check(Thread.currentThread()===owner);if(handle!=0L){val old=handle;handle=0;NativeVision.destroy(old)}}
}
