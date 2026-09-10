package com.sirpaul.stablear.vision

import com.sirpaul.stablear.core.*
import org.opencv.core.*
import org.opencv.features2d.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import org.opencv.video.Video
import kotlin.math.*

/** Caller owns the single bounded worker. No model weights, detector boxes or screen smoothing. */
// forwardBackwardPx is measured LK cycle error, or ORB symmetric-transfer error.
// method distinguishes them; a missing check is never represented as a measured zero.
data class ImageMatch(val pixel: V2,val inliers: Int,val medianReprojectionPx: Double,
    val forwardBackwardPx: Double,val method: String)

class LocalSurfaceTracker : AutoCloseable {
    private data class Reference(val image: Mat,val pixel: V2,val corners: MatOfPoint2f,
        val keypoints: MatOfKeyPoint,val descriptors: Mat)
    private val refs=linkedMapOf<Long,Reference>()
    private val orb=ORB.create(800)
    private val matcher=BFMatcher.create(Core.NORM_HAMMING,true)
    private var frameId=Long.MIN_VALUE
    private var current=Mat(); private var currentKeys=MatOfKeyPoint(); private var currentDesc=Mat()
    private var described=false
    /** Root is immutable. Deliberately no automatic atlas learning from unaccepted matches. */
    fun add(id: Long,gray: ByteArray,width: Int,height: Int,pixel: V2): Boolean {
        require(width>0 && height>0 && gray.size==width*height)
        if(refs.containsKey(id) || refs.size>=64) return false
        val image=Mat(height,width,CvType.CV_8UC1); image.put(0,0,gray)
        val mask=Mat.zeros(height,width,CvType.CV_8UC1)
        val corners=MatOfPoint(); val keys=MatOfKeyPoint(); val desc=Mat()
        var transferred=false
        try {
            Imgproc.circle(mask,Point(pixel.x,pixel.y),64,Scalar(255.0),-1)
            Imgproc.goodFeaturesToTrack(image,corners,120,.015,5.0,mask)
            orb.detectAndCompute(image,mask,keys,desc)
            if(corners.total()<12) return false
            refs[id]=Reference(image,pixel,MatOfPoint2f(*corners.toArray()),keys,desc)
            transferred=true
            return true
        } finally {
            corners.release(); mask.release()
            if(!transferred) { image.release(); keys.release(); desc.release() }
        }
    }
    /** Once per incoming exposure, shared across all attachments. */
    fun beginFrame(id: Long,gray: ByteArray,width: Int,height: Int) {
        require(gray.size==width*height)
        if(frameId==id) return
        current.release(); currentKeys.release(); currentDesc.release()
        current=Mat(height,width,CvType.CV_8UC1); current.put(0,0,gray)
        currentKeys=MatOfKeyPoint(); currentDesc=Mat(); described=false; frameId=id
    }
    fun track(id: Long,predicted: V2?): ImageMatch? {
        val ref=refs[id] ?: return null
        if(current.empty() || current.size()!=ref.image.size()) return null
        if(predicted!=null) trackLk(ref,predicted)?.let { return it }
        if(!described) { val mask=Mat(); try { orb.detectAndCompute(current,mask,currentKeys,currentDesc) } finally { mask.release() }; described=true }
        if(ref.descriptors.empty() || currentDesc.empty()) return null
        val matches=MatOfDMatch()
        try {
            matcher.match(ref.descriptors,currentDesc,matches)
            val a=ref.keypoints.toArray(); val b=currentKeys.toArray()
            val good=matches.toArray().filter { it.distance<48 }.sortedBy { it.distance }.take(100)
            if(good.size<12) return null
            return geometry(ref,good.map { a[it.queryIdx].pt },good.map { b[it.trainIdx].pt },0.0,"ORB_ROOT")
        } finally { matches.release() }
    }
    private fun trackLk(ref: Reference,predicted: V2): ImageMatch? {
        val src=ref.corners.toArray()
        val shift=predicted-ref.pixel
        val forward=MatOfPoint2f(*src.map { Point(it.x+shift.x,it.y+shift.y) }.toTypedArray())
        val back=MatOfPoint2f(); val status=MatOfByte(); val statusBack=MatOfByte(); val err=MatOfFloat(); val errBack=MatOfFloat()
        try {
            val criteria=TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,25,.01)
            Video.calcOpticalFlowPyrLK(ref.image,current,ref.corners,forward,status,err,Size(21.0,21.0),3,criteria,Video.OPTFLOW_USE_INITIAL_FLOW)
            Video.calcOpticalFlowPyrLK(current,ref.image,forward,back,statusBack,errBack,Size(21.0,21.0),3,criteria)
            val f=forward.toArray(); val b=back.toArray(); val s=status.toArray(); val sb=statusBack.toArray()
            val indices=src.indices.filter { i -> s[i].toInt()!=0 && sb[i].toInt()!=0 &&
                hypot(src[i].x-b[i].x,src[i].y-b[i].y)<=1.0 && f[i].x>=0 && f[i].y>=0 && f[i].x<current.cols() && f[i].y<current.rows() }
            if(indices.size<12) return null
            val fb=indices.map { hypot(src[it].x-b[it].x,src[it].y-b[it].y) }.sorted().let { it[it.size/2] }
            return geometry(ref,indices.map { src[it] },indices.map { f[it] },fb,"LK_ROOT")
        } finally { forward.release(); back.release(); status.release(); statusBack.release(); err.release(); errBack.release() }
    }
    private fun geometry(ref: Reference,a: List<Point>,b: List<Point>,fb: Double,method: String): ImageMatch? {
        val src=MatOfPoint2f(*a.toTypedArray()); val dst=MatOfPoint2f(*b.toTypedArray()); val mask=Mat()
        var h=Mat(); val inverse=Mat(); val backProjected=MatOfPoint2f()
        val projected=MatOfPoint2f(); val click=MatOfPoint2f(Point(ref.pixel.x,ref.pixel.y)); val out=MatOfPoint2f()
        try {
            h.release(); h=Calib3d.findHomography(src,dst,Calib3d.RANSAC,1.5,mask,2000,.995)
            if(h.empty() || Core.invert(h,inverse,Core.DECOMP_SVD)<1e-9) return null
            val flags=ByteArray(a.size); mask.get(0,0,flags)
            val kept=a.indices.filter { flags[it].toInt()!=0 }
            if(kept.size<12 || kept.size<a.size*.65) return null
            // A clicked point must be supported around it, not extrapolated from one edge.
            val angles=kept.map { atan2(a[it].y-ref.pixel.y,a[it].x-ref.pixel.x) }.sorted()
            if((angles.zipWithNext { x,y -> y-x }+(angles.first()+2*PI-angles.last())).max()>PI) return null
            Core.perspectiveTransform(src,projected,h); Core.perspectiveTransform(click,out,h)
            val p=out.toArray().firstOrNull() ?: return null
            if(!p.x.isFinite() || !p.y.isFinite() || p.x<0 || p.y<0 || p.x>=current.cols() || p.y>=current.rows()) return null
            val pp=projected.toArray()
            val errors=kept.map { hypot(pp[it].x-b[it].x,pp[it].y-b[it].y) }.sorted()
            val median=errors[errors.size/2]; if(!median.isFinite() || median>1.0) return null
            Core.perspectiveTransform(dst,backProjected,inverse)
            val back=backProjected.toArray()
            val reverseErrors=kept.map { hypot(back[it].x-a[it].x,back[it].y-a[it].y) }.sorted()
            val reverse=reverseErrors[reverseErrors.size/2]
            if(!reverse.isFinite() || reverse>1.0) return null
            return ImageMatch(V2(p.x,p.y),kept.size,median,max(fb,reverse),method)
        } finally { src.release(); dst.release(); mask.release(); h.release(); inverse.release(); backProjected.release(); projected.release(); click.release(); out.release() }
    }
    fun remove(id: Long) { refs.remove(id)?.let { it.image.release(); it.corners.release(); it.keypoints.release(); it.descriptors.release() } }
    override fun close() { refs.keys.toList().forEach(::remove); current.release(); currentKeys.release(); currentDesc.release(); orb.clear(); matcher.clear() }
}
