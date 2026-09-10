package com.sirpaul.showme

import com.sirpaul.spatialnomap.CapturedFrame
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.Base64
import kotlin.math.*

/**
 * Optional image-space verification of the WHOLE stroke, not a snap to its first vertex.
 * A root is immutable: learning a wrong neighbouring edge cannot redefine the target.
 * All methods run on one vision worker, never on the camera/GL or WebRTC threads.
 */
class StrokeSurfaceVerifier {
    data class Reference(val id: String, val frame: CapturedFrame, val pixels: List<FloatArray>)
    data class Result(val points: List<FloatArray>, val pixelResidual: Float, val inliers: Int,
        val depthSupports: Int, val confidence: Float)
    private data class Features(val gray: Mat, val keys: Array<KeyPoint>, val descriptors: Mat)
    private val cache = LinkedHashMap<String,Features>()

    fun clear() { cache.values.forEach { it.gray.release();it.descriptors.release() };cache.clear() }
    fun verify(reference: Reference, current: CapturedFrame, luma: ByteArray,
        expected: List<FloatArray>): Result? {
        if(expected.size!=reference.pixels.size||expected.isEmpty()||current.metricPoints.size<24)return null
        val root=cache[reference.id] ?: prepare(reference)?.also {
            cache[reference.id]=it
            while(cache.size>48)cache.remove(cache.keys.first())?.let { f->f.gray.release();f.descriptors.release() }
        } ?: return null
        val k=current.intrinsics
        if(luma.size!=k.width*k.height)return null
        val expectedPixels=expected.map { ShowMeGeometry.project(current,it) ?: return null }
        if(expectedPixels.any { it[0]<3||it[1]<3||it[0]>k.width-4||it[1]>k.height-4 })return null
        val gray=Mat(k.height,k.width,CvType.CV_8UC1)
        val mask=Mat.zeros(k.height,k.width,CvType.CV_8UC1)
        val keys=MatOfKeyPoint();val descriptors=Mat();val sift=SIFT.create(700,3,.015,10.0,1.6)
        val matcher=BFMatcher.create(Core.NORM_L2,false)
        val pairs=ArrayList<MatOfDMatch>();val mats=ArrayList<Mat>()
        try {
            gray.put(0,0,luma)
            paintMask(mask,expectedPixels,90.0)
            // Local masks and cached reference descriptors avoid full-frame SIFT every second.
            sift.detectAndCompute(gray,mask,keys,descriptors)
            if(descriptors.empty()||descriptors.rows()<10)return null
            matcher.knnMatch(root.descriptors,descriptors,pairs,2)
            val currentKeys=keys.toArray();val used=HashSet<Int>()
            val matches=pairs.mapNotNull { pair ->
                val m=pair.toArray()
                if(m.size>=2&&m[0].distance<.70f*m[1].distance&&used.add(m[0].trainIdx))m[0] else null
            }
            if(matches.size<10)return null
            val src=MatOfPoint2f(*matches.map { root.keys[it.queryIdx].pt }.toTypedArray())
            val dst=MatOfPoint2f(*matches.map { currentKeys[it.trainIdx].pt }.toTypedArray())
            val inlier=Mat();mats.addAll(listOf(src,dst,inlier))
            val h=Calib3d.findHomography(src,dst,Calib3d.RANSAC,1.8,inlier);mats+=h
            if(h.empty())return null
            val indices=matches.indices.filter { (inlier.get(it,0)?.firstOrNull() ?: 0.0)>0 }
            if(indices.size<10||indices.size<ceil(matches.size*.65).toInt())return null
            val projected=MatOfPoint2f();mats+=projected
            Core.perspectiveTransform(src,projected,h)
            val estimated=projected.toArray();val actual=dst.toArray()
            val errors=indices.map { hypot(estimated[it].x-actual[it].x,estimated[it].y-actual[it].y) }.sorted()
            val median=errors[errors.size/2]
            if(!median.isFinite()||median>1.35)return null
            val clicked=reference.pixels.map { Point(it[0].toDouble(),it[1].toDouble()) }
            // Target centroid must be inside the source inlier hull: no extrapolation from a nearby object.
            val cx=clicked.map { it.x }.average();val cy=clicked.map { it.y }.average()
            val originalPoints=src.toArray();val sourceInliers=indices.map { originalPoints[it] }
            if(!surrounded(sourceInliers,cx,cy))return null
            val footprint=MatOfPoint2f(*clicked.toTypedArray());val mapped=MatOfPoint2f();mats.addAll(listOf(footprint,mapped))
            Core.perspectiveTransform(footprint,mapped,h)
            val pixels=mapped.toArray()
            val points=ArrayList<FloatArray>()
            var maxShift=0.0
            for(i in pixels.indices) {
                val p=pixels[i]
                if(!p.x.isFinite()||!p.y.isFinite()||p.x<3||p.y<3||p.x>=k.width-3||p.y>=k.height-3)return null
                val shift=hypot(p.x-expectedPixels[i][0],p.y-expectedPixels[i][1])
                if(shift>max(28.0,k.width*.045))return null
                maxShift=max(maxShift,shift)
                val world=ShowMeGeometry.pointAt(current,p.x.toFloat(),p.y.toFloat()) ?: return null
                if(ShowMeGeometry.distance(expected[i],world)>.06f)return null
                points+=world
            }
            // Local homography is only appropriate for a coherent physical surface, not arbitrary warps.
            if(!StrokeGeometry.shapeCompatible(expected,points))return null
            val confidence=(min(1.0,indices.size/24.0)*exp(-median/1.5)).toFloat()
            if(confidence<.30f)return null
            return Result(points,median.toFloat(),indices.size,points.size,confidence)
        } finally {
            pairs.forEach { it.release() };mats.forEach { it.release() }
            gray.release();mask.release();keys.release();descriptors.release();matcher.clear();sift.clear()
        }
    }
    private fun prepare(reference: Reference): Features? {
        val bytes=runCatching { Base64.getDecoder().decode(reference.frame.jpegBase64) }.getOrNull() ?: return null
        val encoded=MatOfByte(*bytes);val gray=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);encoded.release()
        if(gray.empty()){gray.release();return null}
        val mask=Mat.zeros(gray.rows(),gray.cols(),CvType.CV_8UC1);paintMask(mask,reference.pixels,85.0)
        val keys=MatOfKeyPoint();val descriptor=Mat();val sift=SIFT.create(700,3,.015,10.0,1.6)
        try{
            sift.detectAndCompute(gray,mask,keys,descriptor)
            if(descriptor.rows()<10){gray.release();descriptor.release();return null}
            return Features(gray,keys.toArray(),descriptor)
        }finally{mask.release();keys.release();sift.clear()}
    }
    private fun paintMask(mask: Mat,points: List<FloatArray>,padding: Double) {
        val left=(points.minOf { it[0] }-padding).coerceAtLeast(0.0)
        val right=(points.maxOf { it[0] }+padding).coerceAtMost(mask.cols()-1.0)
        val top=(points.minOf { it[1] }-padding).coerceAtLeast(0.0)
        val bottom=(points.maxOf { it[1] }+padding).coerceAtMost(mask.rows()-1.0)
        Imgproc.rectangle(mask,Point(left,top),Point(right,bottom),Scalar(255.0),-1)
    }
    private fun surrounded(points: List<Point>,cx: Double,cy: Double): Boolean {
        if(points.none { it.x<cx }||points.none { it.x>cx }||points.none { it.y<cy }||points.none { it.y>cy })return false
        val angles=points.map { atan2(it.y-cy,it.x-cx) }.sorted()
        var gap=2*Math.PI+angles.first()-angles.last()
        for(i in 1 until angles.size)gap=max(gap,angles[i]-angles[i-1])
        return gap<Math.PI
    }
}

object StrokeGeometry {
    fun center(points: List<FloatArray>): FloatArray = FloatArray(3) { axis -> points.map { it[axis].toDouble() }.average().toFloat() }
    fun shapeCompatible(before: List<FloatArray>, after: List<FloatArray>): Boolean {
        if(before.isEmpty()||before.size!=after.size)return false
        if(before.any { it.size!=3||it.any { v->!v.isFinite() } }||after.any { it.size!=3||it.any { v->!v.isFinite() } })return false
        if(before.size==1)return true
        for(i in before.indices) {
            val j=(i+maxOf(1,before.size/3))%before.size
            val original=ShowMeGeometry.distance(before[i],before[j])
            val updated=ShowMeGeometry.distance(after[i],after[j])
            if(abs(original-updated)>max(.012f,original*.12f))return false
        }
        return true
    }
}
