package com.sirpaul.showme

import android.media.Image
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs

/** Full-colour stream. The alignment pipeline's grayscale FrameCapture is intentionally not used for video. */
object StreamCapture {
    data class Encoded(val raw:ByteArray,val upright:ByteArray)
    fun copyNv21(image:Image):ByteArray {
        val w=image.width; val h=image.height
        require(w%2==0 && h%2==0 && image.planes.size>=3)
        val out=ByteArray(w*h*3/2)
        fun sample(plane:Int,x:Int,y:Int):Byte {
            val p=image.planes[plane]; return p.buffer.get(p.buffer.position()+y*p.rowStride+x*p.pixelStride)
        }
        var at=0
        for(y in 0 until h) for(x in 0 until w) out[at++]=sample(0,x,y)
        for(y in 0 until h/2) for(x in 0 until w/2) { out[at++]=sample(2,x,y); out[at++]=sample(1,x,y) }
        return out
    }
    fun encode(nv21:ByteArray,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int,rotation:Int):Encoded {
        val yuv=Mat(sourceHeight+sourceHeight/2,sourceWidth,CvType.CV_8UC1)
        val color=Mat(); val scaled=Mat(); val upright=Mat(); val rawJpeg=MatOfByte(); val viewJpeg=MatOfByte()
        val quality=MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY,86)
        try {
            yuv.put(0,0,nv21); Imgproc.cvtColor(yuv,color,Imgproc.COLOR_YUV2BGR_NV21)
            Imgproc.resize(color,scaled,Size(width.toDouble(),height.toDouble()),0.0,0.0,Imgproc.INTER_AREA)
            check(Imgcodecs.imencode(".jpg",scaled,rawJpeg,quality))
            when(rotation) {
                90 -> Core.rotate(scaled,upright,Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(scaled,upright,Core.ROTATE_180)
                270 -> Core.rotate(scaled,upright,Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> scaled.copyTo(upright)
            }
            check(Imgcodecs.imencode(".jpg",upright,viewJpeg,quality))
            return Encoded(rawJpeg.toArray(),viewJpeg.toArray())
        } finally { listOf(yuv,color,scaled,upright,rawJpeg,viewJpeg,quality).forEach { it.release() } }
    }
}
