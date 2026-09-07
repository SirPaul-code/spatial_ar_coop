package com.sirpaul.spatialnomap

import android.util.Base64
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A manual target is not just an XYZ coordinate. It is a physical surface feature.
 *
 * The reference image remembers the texture around the exact point that was tapped.
 * While that texture is visible again, this resolver estimates a local homography,
 * maps the original target pixel into the current camera image and then requires
 * metric depth at that mapped pixel. The resulting current-world point can be used
 * to repair ARCore-anchor/VIO drift without moving a marker onto a visually similar
 * but geometrically incompatible surface.
 *
 * This is deliberately fail-closed: an unverified target simply keeps its existing
 * ARCore anchor. A bad visual/depth correction is much worse than temporarily not
 * correcting drift.
 */
data class SurfaceTargetReference(
    val frame: CapturedFrame,
    val pixel: FloatArray,
)

object SurfaceTargetRegistry {
    private val remote = LinkedHashMap<Long, SurfaceTargetReference>()

    @Synchronized fun putRemote(id: Long, reference: SurfaceTargetReference) {
        remote[id] = reference
        while (remote.size > MAX_REMOTE_REFERENCES) remote.remove(remote.keys.first())
    }

    @Synchronized fun remote(id: Long): SurfaceTargetReference? = remote[id]

    @Synchronized fun remove(id: Long) {
        remote.remove(id)
    }

    @Synchronized fun clear() {
        remote.clear()
    }

    private const val MAX_REMOTE_REFERENCES = 64
}

object SurfaceTargetResolver {
    data class Result(
        val pointWorld: FloatArray,
        val matchedPixel: FloatArray,
        val visualInliers: Int,
        val medianReprojectionPx: Float,
        val depthSupports: Int,
        val correctionM: Float,
        val confidence: Float,
    )

    private data class MatchSet(
        val matches: List<DMatch>,
        val source: Array<org.opencv.core.KeyPoint>,
        val current: Array<org.opencv.core.KeyPoint>,
    )

    fun resolve(
        reference: SurfaceTargetReference,
        current: CapturedFrame,
        expectedWorld: FloatArray,
    ): Result? {
        if (reference.pixel.size < 2 || expectedWorld.size < 3) return null
        if (current.metricPoints.size < MIN_CURRENT_METRIC_SUPPORTS) return null

        val sourceImage = decodeGray(reference.frame) ?: return null
        val currentImage = decodeGray(current) ?: run {
            sourceImage.release()
            return null
        }
        val sourceMask = Mat.zeros(sourceImage.rows(), sourceImage.cols(), CvType.CV_8UC1)
        val patchRadius = (min(sourceImage.cols(), sourceImage.rows()) * PATCH_RADIUS_RATIO)
            .toInt().coerceIn(MIN_PATCH_RADIUS_PX, MAX_PATCH_RADIUS_PX)
        Imgproc.circle(
            sourceMask,
            Point(reference.pixel[0].toDouble(), reference.pixel[1].toDouble()),
            patchRadius,
            Scalar(255.0),
            -1,
        )

        val matchSet = try {
            patchMatches(sourceImage, sourceMask, currentImage)
        } finally {
            sourceImage.release()
            currentImage.release()
            sourceMask.release()
        } ?: return null
        if (matchSet.matches.size < MIN_VISUAL_MATCHES) return null

        val sourcePoints = matchSet.matches.map { matchSet.source[it.queryIdx].pt }
        val currentPoints = matchSet.matches.map { matchSet.current[it.trainIdx].pt }
        val src = MatOfPoint2f(*sourcePoints.toTypedArray())
        val dst = MatOfPoint2f(*currentPoints.toTypedArray())
        val inlierMask = Mat()
        val homography = try {
            Calib3d.findHomography(src, dst, Calib3d.RANSAC, HOMOGRAPHY_RANSAC_PX, inlierMask)
        } catch (_: Throwable) {
            Mat()
        }

        if (homography.empty()) {
            releaseAll(src, dst, inlierMask, homography)
            return null
        }

        val projectedMatches = MatOfPoint2f()
        Core.perspectiveTransform(src, projectedMatches, homography)
        val predicted = projectedMatches.toArray()
        val observed = dst.toArray()
        val residuals = ArrayList<Double>()
        for (i in predicted.indices) {
            val accepted = inlierMask.rows() <= i || (inlierMask.get(i, 0)?.firstOrNull() ?: 0.0) != 0.0
            if (!accepted) continue
            val dx = predicted[i].x - observed[i].x
            val dy = predicted[i].y - observed[i].y
            val error = sqrt(dx * dx + dy * dy)
            if (error.isFinite()) residuals += error
        }
        if (residuals.size < MIN_HOMOGRAPHY_INLIERS) {
            releaseAll(src, dst, inlierMask, homography, projectedMatches)
            return null
        }
        residuals.sort()
        val medianReprojection = residuals[residuals.size / 2]
        if (!medianReprojection.isFinite() || medianReprojection > MAX_MEDIAN_REPROJECTION_PX) {
            releaseAll(src, dst, inlierMask, homography, projectedMatches)
            return null
        }

        val exactSource = MatOfPoint2f(
            Point(reference.pixel[0].toDouble(), reference.pixel[1].toDouble()),
        )
        val exactCurrent = MatOfPoint2f()
        Core.perspectiveTransform(exactSource, exactCurrent, homography)
        val mapped = exactCurrent.toArray().firstOrNull()
        releaseAll(src, dst, inlierMask, homography, projectedMatches, exactSource, exactCurrent)
        if (mapped == null || !mapped.x.isFinite() || !mapped.y.isFinite()) return null
        if (mapped.x < IMAGE_EDGE_MARGIN_PX || mapped.y < IMAGE_EDGE_MARGIN_PX ||
            mapped.x >= current.intrinsics.width - IMAGE_EDGE_MARGIN_PX ||
            mapped.y >= current.intrinsics.height - IMAGE_EDGE_MARGIN_PX
        ) return null

        val depth = robustDepthAtPixel(current, mapped.x.toFloat(), mapped.y.toFloat()) ?: return null
        val resolved = unproject(current, mapped.x.toFloat(), mapped.y.toFloat(), depth.depthM) ?: return null
        val correction = distance(resolved, expectedWorld)
        if (!correction.isFinite() || correction > MAX_CORRECTION_M) return null

        val visualSupport = min(1.0, residuals.size / 18.0)
        val reprojectionFit = exp(-medianReprojection / 2.2)
        val depthSupport = min(1.0, depth.supports / 12.0)
        val correctionFit = exp(-correction / 0.32)
        val confidence = (visualSupport * reprojectionFit * depthSupport * correctionFit)
            .coerceIn(0.0, 1.0)
            .toFloat()
        if (confidence < MIN_RESULT_CONFIDENCE) return null

        return Result(
            pointWorld = resolved,
            matchedPixel = floatArrayOf(mapped.x.toFloat(), mapped.y.toFloat()),
            visualInliers = residuals.size,
            medianReprojectionPx = medianReprojection.toFloat(),
            depthSupports = depth.supports,
            correctionM = correction,
            confidence = confidence,
        )
    }

    /** Project a sender-world point into the exact reference frame received over P2P. */
    fun pixelForWorld(frame: CapturedFrame, pointWorld: FloatArray): FloatArray? {
        if (pointWorld.size < 3) return null
        val worldFromCamera = AlignmentEngine.poseMatrix(frame.pose)
        val cameraFromWorld = invertRigid(worldFromCamera) ?: return null
        val camera = AlignmentEngine.transformPoint(cameraFromWorld, pointWorld)
        val depth = -camera[2]
        if (!depth.isFinite() || depth <= 0.05) return null
        val u = frame.intrinsics.fx * camera[0] / depth + frame.intrinsics.cx
        val v = frame.intrinsics.fy * (-camera[1]) / depth + frame.intrinsics.cy
        if (!u.isFinite() || !v.isFinite()) return null
        if (u < 0.0 || v < 0.0 || u >= frame.intrinsics.width || v >= frame.intrinsics.height) return null
        return floatArrayOf(u.toFloat(), v.toFloat())
    }

    private data class DepthEstimate(val depthM: Float, val supports: Int)

    private fun robustDepthAtPixel(frame: CapturedFrame, u: Float, v: Float): DepthEstimate? {
        val cameraFromWorld = invertRigid(AlignmentEngine.poseMatrix(frame.pose)) ?: return null
        val candidates = ArrayList<Pair<Double, Float>>()
        val radius2 = METRIC_RADIUS_PX * METRIC_RADIUS_PX
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
            if (depth.isFinite() && depth in MIN_DEPTH_M..MAX_DEPTH_M) candidates += d2.toDouble() to depth
        }
        if (candidates.size < MIN_DEPTH_SUPPORTS) return null
        candidates.sortBy { it.first }

        val nearest = candidates.take(min(NEAREST_DEPTH_SEED_COUNT, candidates.size)).map { it.second }.sorted()
        val seed = nearest[nearest.size / 2]
        val tolerance = max(MIN_DEPTH_CLUSTER_M, seed * DEPTH_CLUSTER_RATIO)
        val cluster = candidates.map { it.second }.filter { abs(it - seed) <= tolerance }.sorted()
        if (cluster.size < MIN_DEPTH_SUPPORTS) return null
        return DepthEstimate(cluster[cluster.size / 2], cluster.size)
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
        val out = floatArrayOf(world[0].toFloat(), world[1].toFloat(), world[2].toFloat())
        return out.takeIf { it.all { value -> value.isFinite() } }
    }

    private fun patchMatches(source: Mat, sourceMask: Mat, current: Mat): MatchSet? {
        val sift = SIFT.create(3000, 3, 0.012, 12.0, 1.6)
        val sourceKeys = MatOfKeyPoint()
        val currentKeys = MatOfKeyPoint()
        val sourceDescriptors = Mat()
        val currentDescriptors = Mat()
        val empty = Mat()
        try {
            sift.detectAndCompute(source, sourceMask, sourceKeys, sourceDescriptors)
            sift.detectAndCompute(current, empty, currentKeys, currentDescriptors)
            if (sourceDescriptors.empty() || currentDescriptors.empty() ||
                sourceKeys.rows() < MIN_VISUAL_MATCHES || currentKeys.rows() < MIN_VISUAL_MATCHES
            ) return null

            val matcher = BFMatcher.create(Core.NORM_L2, false)
            val forward = ArrayList<MatOfDMatch>()
            val reverse = ArrayList<MatOfDMatch>()
            try {
                matcher.knnMatch(sourceDescriptors, currentDescriptors, forward, 2)
                matcher.knnMatch(currentDescriptors, sourceDescriptors, reverse, 2)

                val reverseBest = IntArray(currentDescriptors.rows()) { -1 }
                for (pair in reverse) {
                    val m = pair.toArray()
                    if (m.size >= 2 && m[1].distance > 1e-6f && m[0].distance / m[1].distance <= SIFT_RATIO) {
                        if (m[0].queryIdx in reverseBest.indices) reverseBest[m[0].queryIdx] = m[0].trainIdx
                    }
                    pair.release()
                }

                val good = ArrayList<DMatch>()
                val usedSource = HashSet<Int>()
                val usedCurrent = HashSet<Int>()
                for (pair in forward) {
                    val m = pair.toArray()
                    if (m.size >= 2 && m[1].distance > 1e-6f && m[0].distance / m[1].distance <= SIFT_RATIO) {
                        val best = m[0]
                        if (best.trainIdx in reverseBest.indices && reverseBest[best.trainIdx] == best.queryIdx &&
                            usedSource.add(best.queryIdx) && usedCurrent.add(best.trainIdx)
                        ) good += best
                    }
                    pair.release()
                }
                if (good.size < MIN_VISUAL_MATCHES) return null
                return MatchSet(good, sourceKeys.toArray(), currentKeys.toArray())
            } finally {
                forward.forEach { runCatching { it.release() } }
                reverse.forEach { runCatching { it.release() } }
                matcher.clear()
            }
        } finally {
            releaseAll(sourceKeys, currentKeys, sourceDescriptors, currentDescriptors, empty)
            sift.clear()
        }
    }

    private fun decodeGray(frame: CapturedFrame): Mat? = try {
        val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        val encoded = MatOfByte(*bytes)
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE)
        encoded.release()
        image.takeUnless { it.empty() }
    } catch (_: Throwable) {
        null
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
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun releaseAll(vararg mats: Mat) {
        mats.forEach { runCatching { it.release() } }
    }

    private const val PATCH_RADIUS_RATIO = 0.24
    private const val MIN_PATCH_RADIUS_PX = 120
    private const val MAX_PATCH_RADIUS_PX = 420
    private const val SIFT_RATIO = 0.76f
    private const val MIN_VISUAL_MATCHES = 8
    private const val MIN_HOMOGRAPHY_INLIERS = 7
    private const val HOMOGRAPHY_RANSAC_PX = 3.0
    private const val MAX_MEDIAN_REPROJECTION_PX = 2.8
    private const val IMAGE_EDGE_MARGIN_PX = 3.0

    private const val MIN_CURRENT_METRIC_SUPPORTS = 24
    private const val METRIC_RADIUS_PX = 32f
    private const val MIN_DEPTH_SUPPORTS = 3
    private const val NEAREST_DEPTH_SEED_COUNT = 10
    private const val MIN_DEPTH_CLUSTER_M = 0.08f
    private const val DEPTH_CLUSTER_RATIO = 0.04f
    private const val MIN_DEPTH_M = 0.15f
    private const val MAX_DEPTH_M = 65f

    private const val MAX_CORRECTION_M = 0.65f
    private const val MIN_RESULT_CONFIDENCE = 0.18f
}
