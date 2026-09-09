package com.sirpaul.spatialnomap

import android.util.Base64
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Final centimetre-scale image-space snap for a manual surface target.
 *
 * SurfaceTargetResolver already establishes the physical surface with SIFT,
 * homography and metric depth. This helper deliberately runs only after that strong
 * solve. It treats the homography result as a coarse pixel prediction, then performs
 * a tiny local normalized-correlation search on equalized Canny edges around the
 * exact originally tapped pixel. The refined pixel is accepted only when metric
 * depth agrees with both the current anchor and the coarse surface solve.
 *
 * This is an extra fail-closed precision layer; it is never used for shared-world
 * alignment and therefore cannot affect CREATE/JOIN/LOCKED startup behavior.
 */
object SurfaceEdgeSnapRefiner {
    fun refine(
        reference: SurfaceTargetReference,
        current: CapturedFrame,
        coarse: SurfaceTargetResolver.Result,
        expectedWorld: FloatArray,
    ): SurfaceTargetResolver.Result? {
        if (reference.pixel.size < 2 || coarse.matchedPixel.size < 2 || expectedWorld.size < 3) return null
        if (coarse.visualInliers < MIN_COARSE_INLIERS || coarse.medianReprojectionPx > MAX_COARSE_REPROJECTION_PX) return null

        val sourceGray = decodeGray(reference.frame) ?: return null
        val currentGray = decodeGray(current) ?: run {
            sourceGray.release()
            return null
        }
        val sourceEq = Mat()
        val currentEq = Mat()
        val sourceEdges = Mat()
        val currentEdges = Mat()
        try {
            Imgproc.equalizeHist(sourceGray, sourceEq)
            Imgproc.equalizeHist(currentGray, currentEq)
            Imgproc.GaussianBlur(sourceEq, sourceEq, org.opencv.core.Size(3.0, 3.0), 0.0)
            Imgproc.GaussianBlur(currentEq, currentEq, org.opencv.core.Size(3.0, 3.0), 0.0)
            Imgproc.Canny(sourceEq, sourceEdges, CANNY_LOW, CANNY_HIGH, 3, true)
            Imgproc.Canny(currentEq, currentEdges, CANNY_LOW, CANNY_HIGH, 3, true)

            val radius = PATCH_RADIUS_PX
            val sx = reference.pixel[0].toInt()
            val sy = reference.pixel[1].toInt()
            if (!containsPatch(sourceEdges, sx, sy, radius)) return null

            val templateRect = Rect(sx - radius, sy - radius, radius * 2 + 1, radius * 2 + 1)
            val template = Mat(sourceEdges, templateRect).clone()
            try {
                if (Core.countNonZero(template) < MIN_TEMPLATE_EDGE_PIXELS) return null

                val cx = coarse.matchedPixel[0]
                val cy = coarse.matchedPixel[1]
                if (!cx.isFinite() || !cy.isFinite()) return null
                val searchRadius = SEARCH_RADIUS_PX
                val left = (cx.toInt() - radius - searchRadius).coerceAtLeast(0)
                val top = (cy.toInt() - radius - searchRadius).coerceAtLeast(0)
                val right = (cx.toInt() + radius + searchRadius).coerceAtMost(currentEdges.cols() - 1)
                val bottom = (cy.toInt() + radius + searchRadius).coerceAtMost(currentEdges.rows() - 1)
                val searchW = right - left + 1
                val searchH = bottom - top + 1
                if (searchW < template.cols() || searchH < template.rows()) return null

                val search = Mat(currentEdges, Rect(left, top, searchW, searchH))
                val response = Mat()
                try {
                    Imgproc.matchTemplate(search, template, response, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(response)
                    if (!mm.maxVal.isFinite() || mm.maxVal < MIN_NCC) return null

                    val refinedX = left + mm.maxLoc.x.toFloat() + radius
                    val refinedY = top + mm.maxLoc.y.toFloat() + radius
                    val dx = refinedX - cx
                    val dy = refinedY - cy
                    val pixelShift = sqrt(dx * dx + dy * dy)
                    if (!pixelShift.isFinite() || pixelShift > MAX_PIXEL_SHIFT_PX) return null

                    val depth = robustDepthAtPixel(current, refinedX, refinedY) ?: return null
                    val resolved = unproject(current, refinedX, refinedY, depth.first) ?: return null
                    val fromCoarse = distance(resolved, coarse.pointWorld)
                    val correction = distance(resolved, expectedWorld)
                    if (!fromCoarse.isFinite() || fromCoarse > MAX_WORLD_SHIFT_FROM_COARSE_M) return null
                    if (!correction.isFinite() || correction > MAX_TOTAL_CORRECTION_M) return null

                    val edgeScore = mm.maxVal.toFloat().coerceIn(0f, 1f)
                    val refinedConfidence = max(
                        coarse.confidence,
                        min(0.98f, coarse.confidence * 0.78f + edgeScore * 0.22f),
                    )
                    return coarse.copy(
                        pointWorld = resolved,
                        matchedPixel = floatArrayOf(refinedX, refinedY),
                        depthSupports = max(coarse.depthSupports, depth.second),
                        correctionM = correction,
                        confidence = refinedConfidence,
                    )
                } finally {
                    search.release()
                    response.release()
                }
            } finally {
                template.release()
            }
        } catch (_: Throwable) {
            return null
        } finally {
            sourceGray.release()
            currentGray.release()
            sourceEq.release()
            currentEq.release()
            sourceEdges.release()
            currentEdges.release()
        }
    }

    private fun containsPatch(image: Mat, x: Int, y: Int, radius: Int): Boolean =
        x - radius >= 0 && y - radius >= 0 && x + radius < image.cols() && y + radius < image.rows()

    private fun decodeGray(frame: CapturedFrame): Mat? = try {
        val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        val encoded = MatOfByte(*bytes)
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE)
        encoded.release()
        image.takeUnless { it.empty() }
    } catch (_: Throwable) {
        null
    }

    /** Returns metric depth and support count near the refined pixel. */
    private fun robustDepthAtPixel(frame: CapturedFrame, u: Float, v: Float): Pair<Float, Int>? {
        val cameraFromWorld = invertRigid(AlignmentEngine.poseMatrix(frame.pose)) ?: return null
        val candidates = ArrayList<Pair<Float, Float>>()
        val radius2 = DEPTH_RADIUS_PX * DEPTH_RADIUS_PX
        for (support in frame.metricPoints) {
            if (support.size < 5 || !support.take(5).all { it.isFinite() }) continue
            val du = support[0] - u
            val dv = support[1] - v
            val d2 = du * du + dv * dv
            if (d2 > radius2) continue
            val cameraPoint = AlignmentEngine.transformPoint(
                cameraFromWorld,
                floatArrayOf(support[2], support[3], support[4]),
            )
            val depth = -cameraPoint[2].toFloat()
            if (depth.isFinite() && depth in MIN_DEPTH_M..MAX_DEPTH_M) candidates += d2 to depth
        }
        if (candidates.size < MIN_DEPTH_SUPPORTS) return null
        candidates.sortBy { it.first }
        val nearest = candidates.take(min(DEPTH_SEED_COUNT, candidates.size)).map { it.second }.sorted()
        val seed = nearest[nearest.size / 2]
        val tolerance = max(MIN_DEPTH_CLUSTER_M, seed * DEPTH_CLUSTER_RATIO)
        val cluster = candidates.map { it.second }.filter { abs(it - seed) <= tolerance }.sorted()
        if (cluster.size < MIN_DEPTH_SUPPORTS) return null
        return cluster[cluster.size / 2] to cluster.size
    }

    private fun unproject(frame: CapturedFrame, u: Float, v: Float, depthM: Float): FloatArray? {
        val k = frame.intrinsics
        if (k.fx <= 1f || k.fy <= 1f || !depthM.isFinite()) return null
        val cameraPoint = floatArrayOf(
            (u - k.cx) / k.fx * depthM,
            -((v - k.cy) / k.fy * depthM),
            -depthM,
        )
        val world = AlignmentEngine.transformPoint(AlignmentEngine.poseMatrix(frame.pose), cameraPoint)
        return floatArrayOf(world[0].toFloat(), world[1].toFloat(), world[2].toFloat())
            .takeIf { it.all { value -> value.isFinite() } }
    }

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (t.size < 16 || t.take(16).any { !it.isFinite() }) return null
        val out = doubleArrayOf(
            t[0], t[4], t[8], 0.0,
            t[1], t[5], t[9], 0.0,
            t[2], t[6], t[10], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val tx = t[3]
        val ty = t[7]
        val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return out.takeIf { it.all { value -> value.isFinite() } }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private const val PATCH_RADIUS_PX = 27
    private const val SEARCH_RADIUS_PX = 11
    private const val MAX_PIXEL_SHIFT_PX = 12f
    private const val MIN_TEMPLATE_EDGE_PIXELS = 42
    private const val MIN_NCC = 0.28
    private const val CANNY_LOW = 48.0
    private const val CANNY_HIGH = 132.0

    private const val MIN_COARSE_INLIERS = 7
    private const val MAX_COARSE_REPROJECTION_PX = 2.8f
    private const val DEPTH_RADIUS_PX = 22f
    private const val MIN_DEPTH_SUPPORTS = 3
    private const val DEPTH_SEED_COUNT = 10
    private const val MIN_DEPTH_CLUSTER_M = 0.06f
    private const val DEPTH_CLUSTER_RATIO = 0.035f
    private const val MIN_DEPTH_M = 0.15f
    private const val MAX_DEPTH_M = 65f
    private const val MAX_WORLD_SHIFT_FROM_COARSE_M = 0.14f
    private const val MAX_TOTAL_CORRECTION_M = 0.65f
}
