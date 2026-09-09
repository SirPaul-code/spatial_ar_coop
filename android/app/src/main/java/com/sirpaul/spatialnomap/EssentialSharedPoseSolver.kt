package com.sirpaul.spatialnomap

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Visual fallback for shared-world bootstrap when the direct metric 3D<->3D path
 * cannot be formed. Essential geometry is deliberately treated as a fallback, not
 * as permission to lock from a tiny baseline: translation direction becomes poorly
 * conditioned when two phones are almost colocated, so metric scale needs several
 * independent depth correspondences and a meaningful camera baseline.
 */
object EssentialSharedPoseSolver {
    data class Observation(
        val remoteX: Double,
        val remoteY: Double,
        val localX: Double,
        val localY: Double,
    )

    data class Fit(
        val transformLocalFromRemote: DoubleArray,
        val visualInliers: Int,
        val visualCorrespondences: Int,
        val medianEpipolarPx: Double,
        val imageCoverage: Double,
        val predictedDeviceDistanceM: Double,
        val confidence: Float,
        val gravityTiltDeg: Double,
        val metricPairs: Int,
        val metricInliers: Int,
        val medianMetricResidualM: Double,
    )

    private data class ScalePair(
        val remoteCv: DoubleArray,
        val localCv: DoubleArray,
        val projectedScale: Double,
    )

    fun solve(
        remote: CapturedFrame,
        local: CapturedFrame,
        observations: List<Observation>,
    ): Fit? {
        if (observations.size < MIN_VISUAL_MATCHES) return null

        val remoteNorm = observations.map {
            Point(
                (it.remoteX - remote.intrinsics.cx) / remote.intrinsics.fx,
                (it.remoteY - remote.intrinsics.cy) / remote.intrinsics.fy,
            )
        }
        val localNorm = observations.map {
            Point(
                (it.localX - local.intrinsics.cx) / local.intrinsics.fx,
                (it.localY - local.intrinsics.cy) / local.intrinsics.fy,
            )
        }

        val p1 = MatOfPoint2f(*remoteNorm.toTypedArray())
        val p2 = MatOfPoint2f(*localNorm.toTypedArray())
        val identity = Mat.eye(3, 3, CvType.CV_64F)
        val mask = Mat()
        var essential: Mat? = null
        val rotation = Mat()
        val translation = Mat()

        try {
            essential = Calib3d.findEssentialMat(
                p1,
                p2,
                identity,
                Calib3d.RANSAC,
                0.999,
                ESSENTIAL_THRESHOLD_NORMALIZED,
                1600,
                mask,
            )
            if (essential.empty() || essential.rows() != 3 || essential.cols() != 3) return null

            val recovered = Calib3d.recoverPose(
                essential,
                p1,
                p2,
                identity,
                rotation,
                translation,
                mask,
            )
            if (recovered < MIN_VISUAL_INLIERS) return null

            val inlierIndices = maskIndices(mask, observations.size)
            if (inlierIndices.size < MIN_VISUAL_INLIERS) return null

            val r = DoubleArray(9)
            rotation.get(0, 0, r)
            val t = DoubleArray(3)
            translation.get(0, 0, t)
            val tNorm = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
            if (!tNorm.isFinite() || tNorm < 1e-8) return null
            val tUnit = doubleArrayOf(t[0] / tNorm, t[1] / tNorm, t[2] / tNorm)

            val scalePairs = metricScalePairs(remote, local, observations, inlierIndices, r, tUnit)
            if (scalePairs.size < MIN_SCALE_PAIRS) return null
            val scales = scalePairs.map { it.projectedScale }.sorted()
            var scale = median(scales)
            if (!scale.isFinite() || scale !in MIN_BASELINE_M..MAX_BASELINE_M) return null

            val scaleTolerance = max(MIN_SCALE_TOLERANCE_M, scale * SCALE_TOLERANCE_RATIO)
            val scaleInliers = scalePairs.filter { abs(it.projectedScale - scale) <= scaleTolerance }
            if (scaleInliers.size < MIN_SCALE_PAIRS) return null
            scale = median(scaleInliers.map { it.projectedScale }.sorted())

            val scaleSpread = scaleInliers.map { abs(it.projectedScale - scale) }.sorted()
            val scaleMad = median(scaleSpread)
            if (!scaleMad.isFinite() || scaleMad > max(MAX_SCALE_MAD_MIN_M, scale * MAX_SCALE_MAD_RATIO)) return null

            val metricResiduals = scaleInliers.map { pair ->
                metricResidual(pair.remoteCv, pair.localCv, r, tUnit, scale)
            }.filter { it.isFinite() }.sorted()
            if (metricResiduals.size < MIN_SCALE_PAIRS) return null
            val medianMetricResidual = median(metricResiduals)
            if (!medianMetricResidual.isFinite() || medianMetricResidual > MAX_MEDIAN_METRIC_RESIDUAL_M) return null
            val metricInliers = metricResiduals.count { it <= METRIC_RESIDUAL_INLIER_M }
            val requiredMetricInliers = max(
                MIN_METRIC_INLIERS,
                ceil(metricResiduals.size * MIN_METRIC_INLIER_RATIO).toInt(),
            )
            if (metricInliers < requiredMetricInliers) return null

            val cvLocalFromRemote = doubleArrayOf(
                r[0], r[1], r[2], tUnit[0] * scale,
                r[3], r[4], r[5], tUnit[1] * scale,
                r[6], r[7], r[8], tUnit[2] * scale,
                0.0, 0.0, 0.0, 1.0,
            )
            val cvArFlip = doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, -1.0, 0.0, 0.0,
                0.0, 0.0, -1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
            val arLocalCameraFromRemoteCamera = AlignmentEngine.multiply4(
                cvArFlip,
                AlignmentEngine.multiply4(cvLocalFromRemote, cvArFlip),
            )
            val remoteCameraFromRemoteWorld = invertRigid(AlignmentEngine.poseMatrix(remote.pose)) ?: return null
            val localFromRemote = AlignmentEngine.multiply4(
                AlignmentEngine.poseMatrix(local.pose),
                AlignmentEngine.multiply4(arLocalCameraFromRemoteCamera, remoteCameraFromRemoteWorld),
            )
            if (!isRigid(localFromRemote)) return null

            val gravityTilt = FusionMath.gravityTiltDeg(localFromRemote)
            if (gravityTilt.isFinite() && gravityTilt > MAX_GRAVITY_TILT_DEG) return null

            val medianEpipolarPx = medianEpipolarErrorPx(
                essential,
                remoteNorm,
                localNorm,
                inlierIndices,
                remote.intrinsics,
                local.intrinsics,
            )
            if (!medianEpipolarPx.isFinite() || medianEpipolarPx > MAX_MEDIAN_EPIPOLAR_PX) return null

            val coverage = imageCoverage(
                inlierIndices.map { Point(observations[it].localX, observations[it].localY) },
                local.intrinsics.width,
                local.intrinsics.height,
            )
            if (coverage < MIN_IMAGE_COVERAGE) return null

            val remoteCameraLocal = AlignmentEngine.transformPoint(localFromRemote, remote.pose.t)
            val lc = local.pose.t
            val dx = remoteCameraLocal[0] - lc.getOrElse(0) { 0f }
            val dy = remoteCameraLocal[1] - lc.getOrElse(1) { 0f }
            val dz = remoteCameraLocal[2] - lc.getOrElse(2) { 0f }
            val predictedDistance = sqrt(dx * dx + dy * dy + dz * dz)
            if (!predictedDistance.isFinite() || predictedDistance !in MIN_BASELINE_M..MAX_BASELINE_M) return null

            val inlierRatio = inlierIndices.size.toDouble() / observations.size
            val visualSupport = min(1.0, inlierIndices.size / 24.0)
            val coverageFit = min(1.0, coverage / 0.14)
            val epipolarFit = exp(-medianEpipolarPx / 3.0)
            val metricFit = exp(-medianMetricResidual / 0.14)
            val scaleFit = exp(-scaleMad / max(0.08, scale * 0.18))
            var confidence = (
                inlierRatio *
                    (0.42 + 0.58 * visualSupport) *
                    (0.45 + 0.55 * coverageFit) *
                    epipolarFit *
                    metricFit *
                    scaleFit
                ).coerceIn(0.0, 1.0).toFloat()
            if (gravityTilt.isFinite()) confidence *= exp(-gravityTilt / 24.0).toFloat()
            if (confidence < MIN_CONFIDENCE) return null

            return Fit(
                transformLocalFromRemote = localFromRemote,
                visualInliers = inlierIndices.size,
                visualCorrespondences = observations.size,
                medianEpipolarPx = medianEpipolarPx,
                imageCoverage = coverage,
                predictedDeviceDistanceM = predictedDistance,
                confidence = confidence,
                gravityTiltDeg = gravityTilt,
                metricPairs = metricResiduals.size,
                metricInliers = metricInliers,
                medianMetricResidualM = medianMetricResidual,
            )
        } catch (_: Throwable) {
            return null
        } finally {
            p1.release()
            p2.release()
            identity.release()
            mask.release()
            essential?.release()
            rotation.release()
            translation.release()
        }
    }

    private fun metricScalePairs(
        remote: CapturedFrame,
        local: CapturedFrame,
        observations: List<Observation>,
        visualInliers: IntArray,
        r: DoubleArray,
        tUnit: DoubleArray,
    ): List<ScalePair> {
        val remoteCameraFromWorld = invertRigid(AlignmentEngine.poseMatrix(remote.pose)) ?: return emptyList()
        val localCameraFromWorld = invertRigid(AlignmentEngine.poseMatrix(local.pose)) ?: return emptyList()
        val usedRemote = HashSet<Int>()
        val usedLocal = HashSet<Int>()
        val out = ArrayList<ScalePair>()

        for (index in visualInliers) {
            val observation = observations.getOrNull(index) ?: continue
            val remoteMetric = nearestMetricIndex(
                remote.metricPoints,
                observation.remoteX,
                observation.remoteY,
                usedRemote,
                METRIC_SEARCH_RADIUS_PX,
            )
            val localMetric = nearestMetricIndex(
                local.metricPoints,
                observation.localX,
                observation.localY,
                usedLocal,
                METRIC_SEARCH_RADIUS_PX,
            )
            if (remoteMetric < 0 || localMetric < 0) continue
            usedRemote += remoteMetric
            usedLocal += localMetric

            val rw = remote.metricPoints[remoteMetric]
            val lw = local.metricPoints[localMetric]
            if (rw.size < 5 || lw.size < 5) continue
            val remoteAr = AlignmentEngine.transformPoint(
                remoteCameraFromWorld,
                floatArrayOf(rw[2], rw[3], rw[4]),
            )
            val localAr = AlignmentEngine.transformPoint(
                localCameraFromWorld,
                floatArrayOf(lw[2], lw[3], lw[4]),
            )
            val remoteCv = doubleArrayOf(remoteAr[0], -remoteAr[1], -remoteAr[2])
            val localCv = doubleArrayOf(localAr[0], -localAr[1], -localAr[2])
            if (!remoteCv.all { it.isFinite() } || !localCv.all { it.isFinite() }) continue

            val rotated = doubleArrayOf(
                r[0] * remoteCv[0] + r[1] * remoteCv[1] + r[2] * remoteCv[2],
                r[3] * remoteCv[0] + r[4] * remoteCv[1] + r[5] * remoteCv[2],
                r[6] * remoteCv[0] + r[7] * remoteCv[1] + r[8] * remoteCv[2],
            )
            val delta = doubleArrayOf(
                localCv[0] - rotated[0],
                localCv[1] - rotated[1],
                localCv[2] - rotated[2],
            )
            val projectedScale = delta[0] * tUnit[0] + delta[1] * tUnit[1] + delta[2] * tUnit[2]
            if (!projectedScale.isFinite() || projectedScale !in MIN_BASELINE_M..MAX_BASELINE_M) continue

            val perpendicularX = delta[0] - projectedScale * tUnit[0]
            val perpendicularY = delta[1] - projectedScale * tUnit[1]
            val perpendicularZ = delta[2] - projectedScale * tUnit[2]
            val perpendicular = sqrt(
                perpendicularX * perpendicularX +
                    perpendicularY * perpendicularY +
                    perpendicularZ * perpendicularZ,
            )
            if (!perpendicular.isFinite() || perpendicular > MAX_SCALE_PAIR_PERP_M) continue
            out += ScalePair(remoteCv, localCv, projectedScale)
        }
        return out
    }

    private fun metricResidual(
        remoteCv: DoubleArray,
        localCv: DoubleArray,
        r: DoubleArray,
        tUnit: DoubleArray,
        scale: Double,
    ): Double {
        val x = r[0] * remoteCv[0] + r[1] * remoteCv[1] + r[2] * remoteCv[2] + tUnit[0] * scale
        val y = r[3] * remoteCv[0] + r[4] * remoteCv[1] + r[5] * remoteCv[2] + tUnit[1] * scale
        val z = r[6] * remoteCv[0] + r[7] * remoteCv[1] + r[8] * remoteCv[2] + tUnit[2] * scale
        val dx = x - localCv[0]
        val dy = y - localCv[1]
        val dz = z - localCv[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun medianEpipolarErrorPx(
        essential: Mat,
        remoteNorm: List<Point>,
        localNorm: List<Point>,
        indices: IntArray,
        remoteIntrinsics: IntrinsicsPacket,
        localIntrinsics: IntrinsicsPacket,
    ): Double {
        val e = DoubleArray(9)
        essential.get(0, 0, e)
        val remoteFocal = (remoteIntrinsics.fx + remoteIntrinsics.fy) * 0.5
        val localFocal = (localIntrinsics.fx + localIntrinsics.fy) * 0.5
        val errors = ArrayList<Double>()
        for (index in indices) {
            val a = remoteNorm.getOrNull(index) ?: continue
            val b = localNorm.getOrNull(index) ?: continue
            val l2x = e[0] * a.x + e[1] * a.y + e[2]
            val l2y = e[3] * a.x + e[4] * a.y + e[5]
            val l2z = e[6] * a.x + e[7] * a.y + e[8]
            val numerator = abs(b.x * l2x + b.y * l2y + l2z)
            val d2 = numerator / max(1e-9, sqrt(l2x * l2x + l2y * l2y))

            val l1x = e[0] * b.x + e[3] * b.y + e[6]
            val l1y = e[1] * b.x + e[4] * b.y + e[7]
            val d1 = numerator / max(1e-9, sqrt(l1x * l1x + l1y * l1y))
            val px = 0.5 * (d1 * remoteFocal + d2 * localFocal)
            if (px.isFinite()) errors += px
        }
        return if (errors.isEmpty()) Double.NaN else median(errors.sorted())
    }

    private fun maskIndices(mask: Mat, maxCount: Int): IntArray {
        if (mask.empty()) return IntArray(0)
        val count = min(maxCount, mask.rows() * mask.cols())
        val bytes = ByteArray(mask.rows() * mask.cols())
        mask.get(0, 0, bytes)
        val out = ArrayList<Int>()
        for (i in 0 until count) {
            if ((bytes[i].toInt() and 0xff) != 0) out += i
        }
        return out.toIntArray()
    }

    private fun nearestMetricIndex(
        points: List<FloatArray>,
        u: Double,
        v: Double,
        used: Set<Int>,
        radiusPx: Double,
    ): Int {
        val gate2 = radiusPx * radiusPx
        var best = -1
        var bestDistance2 = Double.POSITIVE_INFINITY
        for (i in points.indices) {
            if (i in used) continue
            val p = points[i]
            if (p.size < 5) continue
            val dx = p[0] - u
            val dy = p[1] - v
            val d2 = dx * dx + dy * dy
            if (d2 < bestDistance2) {
                bestDistance2 = d2
                best = i
            }
        }
        return if (best >= 0 && bestDistance2 <= gate2) best else -1
    }

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (!isRigid(t)) return null
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
        return out
    }

    private fun isRigid(t: DoubleArray): Boolean {
        if (t.size < 16 || !t.take(16).all { it.isFinite() }) return false
        val det = t[0] * (t[5] * t[10] - t[6] * t[9]) -
            t[1] * (t[4] * t[10] - t[6] * t[8]) +
            t[2] * (t[4] * t[9] - t[5] * t[8])
        return det in 0.985..1.015 && abs(t[15] - 1.0) < 1e-4
    }

    private fun imageCoverage(points: List<Point>, width: Int, height: Int): Double {
        if (points.size < 2 || width <= 0 || height <= 0) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        return ((maxX - minX).coerceAtLeast(0.0) * (maxY - minY).coerceAtLeast(0.0) /
            (width.toDouble() * height.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double =
        if (values.isEmpty()) Double.NaN else values[values.size / 2]

    private const val MIN_VISUAL_MATCHES = 14
    private const val MIN_VISUAL_INLIERS = 12
    private const val ESSENTIAL_THRESHOLD_NORMALIZED = 0.0030
    private const val MAX_MEDIAN_EPIPOLAR_PX = 3.0
    private const val MIN_IMAGE_COVERAGE = 0.05
    private const val METRIC_SEARCH_RADIUS_PX = 14.0
    private const val MIN_SCALE_PAIRS = 6
    private const val MIN_METRIC_INLIERS = 5
    private const val MIN_METRIC_INLIER_RATIO = 0.70

    // Essential translation is unreliable at near-zero baseline. Phones closer than
    // this must use the direct metric 3D<->3D path instead of guessing a direction.
    private const val MIN_BASELINE_M = 0.20
    private const val MAX_BASELINE_M = 10.0
    private const val MIN_SCALE_TOLERANCE_M = 0.10
    private const val SCALE_TOLERANCE_RATIO = 0.20
    private const val MAX_SCALE_MAD_MIN_M = 0.08
    private const val MAX_SCALE_MAD_RATIO = 0.18
    private const val MAX_SCALE_PAIR_PERP_M = 0.28
    private const val METRIC_RESIDUAL_INLIER_M = 0.18
    private const val MAX_MEDIAN_METRIC_RESIDUAL_M = 0.16
    private const val MAX_GRAVITY_TILT_DEG = 10.0
    private const val MIN_CONFIDENCE = 0.12f
}
