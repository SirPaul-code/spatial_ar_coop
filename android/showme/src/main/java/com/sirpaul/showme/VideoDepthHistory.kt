package com.sirpaul.showme

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.sirpaul.spatialnomap.CapturedFrame
import com.sirpaul.spatialnomap.IntrinsicsPacket
import com.sirpaul.spatialnomap.PosePacket
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*

/** Depth/pose is copied per video frame; JPEG encoding and depth fitting are NOT. */
data class DepthRaster(val width: Int, val height: Int, val mm: ShortArray, val confidence: ByteArray?) {
    fun depth(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        val i = y * width + x
        if (confidence != null && (confidence[i].toInt() and 255) < 128) return 0
        val d = mm[i].toInt() and 65535
        return if (d in 150..8000) d else 0
    }
}

data class VideoDepthFrame(
    val id: Long, val epoch: Int, val capturedMs: Long, val pose: PosePacket,
    val intrinsics: IntrinsicsPacket, val rotation: Int, val raw: DepthRaster?, val full: DepthRaster?,
    val textureToImage: FloatArray, val cloud: List<FloatArray>, val strokes: List<StrokeSnapshot>,
    val videoWidth: Int, val videoHeight: Int, val contentHeight: Int,
    val cameraTimestampNs: Long = 0L,
) {
    fun metadata(): JSONObject = JSONObject().put("id", id).put("epoch", epoch)
        .put("width", intrinsics.width).put("height", intrinsics.height).put("rotation", rotation)
        .put("cameraTimestampNs",cameraTimestampNs.toString())
        .put("intrinsics",JSONObject().put("fx",intrinsics.fx.toDouble()).put("fy",intrinsics.fy.toDouble())
            .put("cx",intrinsics.cx.toDouble()).put("cy",intrinsics.cy.toDouble()))
        .put("pose",JSONObject().put("t",org.json.JSONArray(pose.t.toList())).put("q",org.json.JSONArray(pose.q.toList())))
        .put("pixelSpace","raw-camera").put("displayRotation",0)
        .put("videoWidth", videoWidth).put("videoHeight", videoHeight).put("contentHeight", contentHeight)
        .put("depthAvailable", raw != null || full != null || cloud.isNotEmpty())
        .put("annotations", projectStrokes(CapturedFrame(id, pose, intrinsics, "", emptyList()), rotation, strokes))

    /** Reconstruct an exact historical camera reference from the presented video image. */
    fun materialize(uprightJpeg: ByteArray): FramePacket {
        require(uprightJpeg.size in 20..1_500_000)
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(uprightJpeg,0,uprightJpeg.size,bounds)
        require(bounds.outWidth in 160..1920 && bounds.outHeight in 160..1920) { "Frozen image dimensions are invalid" }
        val encoded = MatOfByte(*uprightJpeg)
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        val unrotated = Mat(); val scaled = Mat(); val output = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90)
        try {
            require(!image.empty()) { "Empty captured video frame" }
            // Browser sends the visible content, never the hidden stamp band.
            require(image.cols() in 160..1920 && image.rows() in 160..1920)
            val expectedAspect = videoWidth.toDouble() / contentHeight
            require(abs(image.cols().toDouble() / image.rows() - expectedAspect) < 0.015) { "Video aspect changed" }
            when (rotation) {
                90 -> Core.rotate(image, unrotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                180 -> Core.rotate(image, unrotated, Core.ROTATE_180)
                270 -> Core.rotate(image, unrotated, Core.ROTATE_90_CLOCKWISE)
                else -> image.copyTo(unrotated)
            }
            val ratio = min(1.0, 960.0 / max(intrinsics.width, intrinsics.height))
            val w = (intrinsics.width * ratio).roundToInt(); val h = (intrinsics.height * ratio).roundToInt()
            Imgproc.resize(unrotated, scaled, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            require(Imgcodecs.imencode(".jpg", scaled, output, params))
            val sx = w.toFloat() / intrinsics.width; val sy = h.toFloat() / intrinsics.height
            val k = IntrinsicsPacket(intrinsics.fx * sx, intrinsics.fy * sy, intrinsics.cx * sx, intrinsics.cy * sy, w, h)
            val points = supports().map { floatArrayOf(it[0] * sx, it[1] * sy, it[2], it[3], it[4]) }
            val jpeg = output.toArray()
            val captured = CapturedFrame(if(cameraTimestampNs>0L)cameraTimestampNs else id, pose, k, Base64.getEncoder().encodeToString(jpeg), points)
            return FramePacket(id, epoch, captured, jpeg, rotation, capturedMs, strokes)
        } finally {
            encoded.release(); image.release(); unrotated.release(); scaled.release(); output.release(); params.release()
        }
    }

    internal fun supports(): List<FloatArray> {
        val points = ArrayList<FloatArray>(8000)
        val occupied = HashSet<Long>()
        fun add(u: Float, v: Float, world: FloatArray) {
            if (u !in 0f..intrinsics.width.toFloat() || v !in 0f..intrinsics.height.toFloat()) return
            val key = (floor(u / 4).toLong() shl 32) xor floor(v / 4).toLong()
            if (occupied.add(key)) points.add(floatArrayOf(u, v, world[0], world[1], world[2]))
        }
        for (raster in listOfNotNull(raw, full)) {
            val step = ceil(sqrt(raster.width.toDouble() * raster.height / 8000)).toInt().coerceAtLeast(1)
            for (y in step / 2 until raster.height step step) for (x in step / 2 until raster.width step step) {
                if (points.size >= 8000) break
                val center = raster.depth(x, y)
                if (center == 0) continue
                val nearby = IntArray(9); var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val z = raster.depth(x + dx, y + dy)
                    if (z != 0 && abs(z - center) <= max(30, center / 40)) nearby[n++] = z
                }
                if (n < 3) continue
                java.util.Arrays.sort(nearby, 0, n)
                val z = nearby[n / 2] / 1000f
                val tx = (x + .5f) / raster.width; val ty = (y + .5f) / raster.height
                val a = textureToImage
                val u = a[0] + a[2] * tx + a[4] * ty
                val v = a[1] + a[3] * tx + a[5] * ty
                val k = intrinsics
                add(u, v, ShowMeGeometry.toWorld(pose, floatArrayOf((u-k.cx)/k.fx*z, -(v-k.cy)/k.fy*z, -z)))
            }
        }
        if (points.size < 48) cloud.forEach { add(it[0], it[1], floatArrayOf(it[2], it[3], it[4])) }
        return points
    }

    companion object {
        fun capture(frame: Frame, camera: Camera, id: Long, epoch: Int, rotation: Int,
            strokes: List<StrokeSnapshot>, videoWidth: Int, videoHeight: Int, contentHeight: Int): VideoDepthFrame {
            val k = camera.imageIntrinsics; val dims = k.imageDimensions
            val intr = IntrinsicsPacket(k.focalLength[0], k.focalLength[1], k.principalPoint[0], k.principalPoint[1], dims[0], dims[1])
            val coordinates = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, floatArrayOf(0f,0f,1f,0f,0f,1f), Coordinates2d.IMAGE_PIXELS, coordinates)
            val affine = floatArrayOf(coordinates[0], coordinates[1], coordinates[2]-coordinates[0], coordinates[3]-coordinates[1],
                coordinates[4]-coordinates[0], coordinates[5]-coordinates[1])
            val raw = runCatching {
                frame.acquireRawDepthImage16Bits().use { depth -> frame.acquireRawDepthConfidenceImage().use { confidence ->
                    if (depth.width != confidence.width || depth.height != confidence.height) null else copyRaster(depth, confidence)
                } }
            }.getOrNull()
            val full = runCatching { frame.acquireDepthImage16Bits().use { copyRaster(it, null) } }.getOrNull()
            val cloud = ArrayList<FloatArray>()
            if (raw == null && full == null) runCatching {
                frame.acquirePointCloud().use { c ->
                    val points = c.points; val count = points.remaining() / 4
                    val inverse = camera.pose.inverse()
                    for (i in 0 until count step max(1, count / 1200)) {
                        if (points.get(i*4+3) < .3f) continue
                        val world = floatArrayOf(points.get(i*4), points.get(i*4+1), points.get(i*4+2))
                        val p = inverse.transformPoint(world); val z = -p[2]
                        if (z !in .15f..8f) continue
                        cloud.add(floatArrayOf(intr.fx*p[0]/z+intr.cx, intr.fy*-p[1]/z+intr.cy, world[0], world[1], world[2]))
                    }
                }
            }
            return VideoDepthFrame(id, epoch, monotonicMs(), PosePacket(camera.pose.translation.copyOf(), camera.pose.rotationQuaternion.copyOf()),
                intr, rotation, raw, full, affine, cloud, strokes, videoWidth, videoHeight, contentHeight, frame.timestamp)
        }
        private fun copyRaster(image: Image, confidence: Image?): DepthRaster {
            val w = image.width; val h = image.height
            require(w * h <= 512 * 512)
            val plane = image.planes[0]; val data = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN); val base = data.position()
            val mm = ShortArray(w * h)
            for (y in 0 until h) {
                if (plane.pixelStride == 2) {
                    val row = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    row.position(base + y * plane.rowStride); row.asShortBuffer().get(mm, y*w, w)
                } else for (x in 0 until w) mm[y*w+x] = data.getShort(base + y*plane.rowStride+x*plane.pixelStride)
            }
            val quality = confidence?.let {
                val p = it.planes[0]; val b = p.buffer.duplicate(); val start = b.position()
                ByteArray(w*h).also { out -> for(y in 0 until h) {
                    if(p.pixelStride == 1) { b.position(start+y*p.rowStride); b.get(out,y*w,w) }
                    else for(x in 0 until w) out[y*w+x] = b.get(start+y*p.rowStride+x*p.pixelStride)
                } }
            }
            return DepthRaster(w,h,mm,quality)
        }
    }
}

/** Bounded by 180 frames, six seconds and 48 MiB. No growing video queue. */
class VideoDepthHistory(private val clock: () -> Long = ::monotonicMs) {
    private val frames = LinkedHashMap<Long, VideoDepthFrame>()
    private var bytes=0L
    private fun weight(f:VideoDepthFrame):Long = (f.raw?.mm?.size?:0)*2L+(f.raw?.confidence?.size?:0)+(f.full?.mm?.size?:0)*2L+f.cloud.size*20L
    @Synchronized fun add(frame: VideoDepthFrame) {
        frames.put(frame.id,frame)?.let { bytes-=weight(it) };bytes+=weight(frame)
        while (frames.size > 180 || bytes>48L*1024*1024) frames.remove(frames.keys.first())?.let { bytes-=weight(it) }
    }
    @Synchronized fun get(id: Long): VideoDepthFrame? = frames[id]?.takeIf { clock()-it.capturedMs in 0L..6000L }
    @Synchronized fun clear() { frames.clear();bytes=0L }
}
