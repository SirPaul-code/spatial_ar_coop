package com.sirpaul.spatialnomap

import android.util.Base64
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Independent proof for a proposed remote-world -> local-world rigid transform.
 *
 * This does not solve another transform. It asks a stricter question: given the
 * proposed transform, do fresh mutual SIFT correspondences, independently measured
 * metric depth on both phones, gravity and camera geometry all agree with it?
 *
 * A null result means there is not enough shared evidence in this frame pair and is
 * therefore inconclusive. A non-null result with passed=false is a conclusive
 * contradiction and may be used by the post-lock watchdog.
 */
object SharedTransformVerifier {
    data class Verification(
        val evidence: TransformSafetyPolicy.Evidence,
        val predictedDeviceDistanceM: Double,
        val matches: Int,
        val passed: Boolean,
    ) {
        val score: Double
            get() {
                val visualRatio = if (evidence.visualCandidates > 0) {
                    evidence.visualInliers.toDouble() / evidence.visualCandidates
                } else 0.0
                val metricRatio = if (evidence.metricPairs > 0) {
                    evidence.metricInliers.toDouble() / evidence.metricPairs
                } else 0.0
                val reprojection = if (evidence.medianReprojectionPx.isFinite()) {
                    (1.0 - evidence.medianReprojectionPx / 12.0).coerceIn(0.0, 1.0)
                } else 0.0
                val metric = if (evidence.medianMetricResidualM.isFinite()) {
                    (1.0 - evidence.medianMetricResidualM / 0.50).coerceIn(0.0, 1.0)
                } else 0.0
                return visualRatio * 2.0 + metricRatio * 3.0 + reprojection + metric + evidence.imageCoverage
            }
    }

    private data class MatchSet(
        val matches: List<DMatch>,
        val remoteKeys: Array<KeyPoint>,
        val localKeys: Array<KeyPoint>,
    )

    fun verify(
        remote: CapturedFrame,
        local: CapturedFrame,
        transformLocalFromRemote: DoubleArray,
    ): Verification? {
        if (!isRigid(transformLocalFromRemote)) {
            return failedStructuralVerification(remote, local, transformLocalFromRemote)
        }
        val matchSet = siftMatches(remote, local) ?: return null
        if (matchSet.matches.size < MIN_MATCHES) return null

        val localCameraFromWorld = invertRigid(AlignmentEngine.poseMatrix(local.pose)) ?: return null
        val usedRemoteMetric = HashSet<Int>()
        val usedLocalMetric = HashSet<Int>()
        val reprojectionErrors = ArrayList<Double>()
        val visualInlierPoints = ArrayList<Point>()
        val metricResiduals = ArrayList<Double>()
        val remoteMetricWorld = ArrayList<FloatArray>()
        val localMetricWorld = ArrayList<FloatArray>()
        var visualInliers = 0
        var metricInliers = 0

        for (match in matchSet.matches) {
            val remoteKey = matchSet.remoteKeys.getOrNull(match.queryIdx)?.pt ?: continue
            val localKey = matchSet.localKeys.getOrNull(match.trainIdx)?.pt ?: continue

            val remoteMetricIndex = nearestMetricIndex(
                remote.metricPoints,
                remoteKey.x,
                remoteKey.y,
                usedRemoteMetric,
                METRIC_ASSOCIATION_RADIUS_PX,
            )
            if (remoteMetricIndex < 0) continue
            val localMetricIndex = nearestMetricIndex(
                local.metricPoints,
                localKey.x,
                localKey.y,
                usedLocalMetric,
                METRIC_ASSOCIATION_RADIUS_PX,
            )
            if (localMetricIndex < 0) continue

            usedRemoteMetric += remoteMetricIndex
            usedLocalMetric += localMetricIndex
            val remoteSupport = remote.metricPoints[remoteMetricIndex]
            val localSupport = local.metricPoints[localMetricIndex]
            if (remoteSupport.size < 5 || localSupport.size < 5) continue

            val remoteWorld = floatArrayOf(remoteSupport[2], remoteSupport[3], remoteSupport[4])
            val localWorld = floatArrayOf(localSupport[2], localSupport[3], localSupport[4])
            val predictedLocal = AlignmentEngine.transformPoint(transformLocalFromRemote, remoteWorld)
            if (!predictedLocal.all { it.isFinite() }) continue

            // The metric sample is associated to a SIFT feature within a radius, but
            // it is not necessarily the exact SIFT pixel. Reproject against the local
            // metric support UV, which is the pixel that actually generated localWorld.
            // Comparing against localKey with a 4 px gate was internally inconsistent
            // with a 10 px association radius and could reject a geometrically correct
            // transform simply because the depth sample lived a few pixels away.
            val cameraPoint = AlignmentEngine.transformPoint(
                localCameraFromWorld,
                floatArrayOf(predictedLocal[0].toFloat(), predictedLocal[1].toFloat(), predictedLocal[2].toFloat()),
            )
            val zCv = -cameraPoint[2]
            val reprojection = if (!zCv.isFinite() || zCv <= MIN_POSITIVE_DEPTH_M) {
                FAILED_REPROJECTION_PX
            } else {
                val u = local.intrinsics.fx * cameraPoint[0] / zCv + local.intrinsics.cx
                val v = local.intrinsics.fy * (-cameraPoint[1]) / zCv + local.intrinsics.cy
                val du = u - localSupport[0]
                val dv = v - localSupport[1]
                sqrt(du * du + dv * dv).takeIf { it.isFinite() } ?: FAILED_REPROJECTION_PX
            }
            reprojectionErrors += reprojection
            if (reprojection <= VISUAL_INLIER_PX) {
                visualInliers += 1
                // Coverage remains tied to the matched feature distribution rather
                // than the sampler lattice so repeated nearby depth supports do not
                // fake broad visual support.
                visualInlierPoints += Point(localKey.x, localKey.y)
            }

            val dx = predictedLocal[0] - localWorld[0]
            val dy = predictedLocal[1] - localWorld[1]
            val dz = predictedLocal[2] - localWorld[2]
            val residual = sqrt(dx * dx + dy * dy + dz * dz)
            if (!residual.isFinite()) continue
            metricResiduals += residual
            remoteMetricWorld += remoteWorld
            localMetricWorld += localWorld
            if (residual <= METRIC_INLIER_M) metricInliers += 1
        }

        // Not seeing enough of the same physical scene is inconclusive. This is
        // important after LOCKED when the two users are allowed to look elsewhere.
        if (reprojectionErrors.size < TransformSafetyPolicy.MIN_VISUAL_CANDIDATES ||
            metricResiduals.size < TransformSafetyPolicy.MIN_METRIC_PAIRS
        ) return null

        reprojectionErrors.sort()
        metricResiduals.sort()
        val medianReprojection = reprojectionErrors[reprojectionErrors.size / 2]
        val medianMetric = metricResiduals[metricResiduals.size / 2]
        val supportSpan = min(spanM(remoteMetricWorld), spanM(localMetricWorld))
        val coverage = imageCoverage(visualInlierPoints, local.intrinsics.width, local.intrinsics.height)
        val gravity = FusionMath.gravityTiltDeg(transformLocalFromRemote)

        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = reprojectionErrors.size,
            visualInliers = visualInliers,
            medianReprojectionPx = medianReprojection,
            imageCoverage = coverage,
            metricPairs = metricResiduals.size,
            metricInliers = metricInliers,
            medianMetricResidualM = medianMetric,
            metricSupportSpanM = supportSpan,
            gravityTiltDeg = gravity,
        )

        val predictedDistance = predictedDeviceDistance(remote, local, transformLocalFromRemote)
        return Verification(
            evidence = evidence,
            predictedDeviceDistanceM = predictedDistance,
            matches = matchSet.matches.size,
            passed = TransformSafetyPolicy.passes(evidence),
        )
    }

    private fun failedStructuralVerification(
        remote: CapturedFrame,
        local: CapturedFrame,
        transform: DoubleArray,
    ): Verification {
        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = TransformSafetyPolicy.MIN_VISUAL_CANDIDATES,
            visualInliers = 0,
            medianReprojectionPx = FAILED_REPROJECTION_PX,
            imageCoverage = 0.0,
            metricPairs = TransformSafetyPolicy.MIN_METRIC_PAIRS,
            metricInliers = 0,
            medianMetricResidualM = Double.POSITIVE_INFINITY,
            metricSupportSpanM = 0.0,
            gravityTiltDeg = Double.POSITIVE_INFINITY,
        )
        return Verification(evidence, predictedDeviceDistance(remote, local, transform), 0, false)
    }

    private fun predictedDeviceDistance(
        remote: CapturedFrame,
        local: CapturedFrame,
        transform: DoubleArray,
    ): Double {
        if (transform.size < 16) return Double.NaN
        val remoteCameraLocal = AlignmentEngine.transformPoint(transform, remote.pose.t)
        val lc = local.pose.t
        val dx = remoteCameraLocal[0] - lc.getOrElse(0) { 0f }
        val dy = remoteCameraLocal[1] - lc.getOrElse(1) { 0f }
        val dz = remoteCameraLocal[2] - lc.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun siftMatches(remote: CapturedFrame, local: CapturedFrame): MatchSet? {
        val a = decodeGray(remote) ?: return null
        val b = decodeGray(local) ?: run {
            a.release()
            return null
        }
        val sift = SIFT.create(3200, 3, 0.014, 12.0, 1.6)
        val kpa = MatOfKeyPoint()
        val kpb = MatOfKeyPoint()
        val da = Mat()
        val db = Mat()
        val maskA = Mat()
        val maskB = Mat()
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val forward = ArrayList<MatOfDMatch>()
        val reverse = ArrayList<MatOfDMatch>()
        try {
            sift.detectAndCompute(a, maskA, kpa, da)
            sift.detectAndCompute(b, maskB, kpb, db)
            if (da.empty() || db.empty() || kpa.rows() < MIN_MATCHES || kpb.rows() < MIN_MATCHES) return null
            matcher.knnMatch(da, db, forward, 2)
            matcher.knnMatch(db, da, reverse, 2)

            val reverseBest = IntArray(db.rows()) { -1 }
            for (pair in reverse) {
                val arr = pair.toArray()
                if (arr.size >= 2 && arr[1].distance > 1e-6f && arr[0].distance / arr[1].distance < SIFT_RATIO) {
                    val index = arr[0].queryIdx
                    if (index in reverseBest.indices) reverseBest[index] = arr[0].trainIdx
                }
            }

            val candidates = ArrayList<Pair<DMatch, Float>>()
            for (pair in forward) {
                val arr = pair.toArray()
                if (arr.size < 2 || arr[1].distance <= 1e-6f) continue
                val ratio = arr[0].distance / arr[1].distance
                if (ratio <= RECOVERY_RATIO) candidates += arr[0] to ratio
            }

            val usedQuery = HashSet<Int>()
            val usedTrain = HashSet<Int>()
            val good = ArrayList<DMatch>()
            for ((match, ratio) in candidates.sortedWith(compareBy<Pair<DMatch, Float>> { it.second }.thenBy { it.first.distance })) {
                val mutual = match.trainIdx in reverseBest.indices && reverseBest[match.trainIdx] == match.queryIdx
                if (!mutual && ratio > STRICT_RATIO && good.size >= MIN_MATCHES) continue
                if (usedQuery.add(match.queryIdx) && usedTrain.add(match.trainIdx)) good += match
                if (good.size >= MAX_MATCHES) break
            }
            if (good.size < MIN_MATCHES) return null
            return MatchSet(good, kpa.toArray(), kpb.toArray())
        } finally {
            forward.forEach { runCatching { it.release() } }
            reverse.forEach { runCatching { it.release() } }
            listOf(a, b, kpa, kpb, da, db, maskA, maskB).forEach { runCatching { it.release() } }
            runCatching { matcher.clear() }
            runCatching { sift.clear() }
        }
    }

    private fun decodeGray(frame: CapturedFrame): Mat? = try {
        val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        val mob = MatOfByte(*bytes)
        val image = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_GRAYSCALE)
        mob.release()
        image.takeUnless { it.empty() }
    } catch (_: Throwable) {
        null
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
        var bestD2 = Double.POSITIVE_INFINITY
        for (i in points.indices) {
            if (i in used) continue
            val point = points[i]
            if (point.size < 5) continue
            val dx = point[0] - u
            val dy = point[1] - v
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return if (best >= 0 && bestD2 <= gate2) best else -1
    }

    private fun imageCoverage(points: List<Point>, width: Int, height: Int): Double {
        if (points.size < 2 || width <= 0 || height <= 0) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (point in points) {
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
        }
        val area = max(0.0, maxX - minX) * max(0.0, maxY - minY)
        return (area / (width.toDouble() * height.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun spanM(points: List<FloatArray>): Double {
        if (points.isEmpty()) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = min(minX, p[0].toDouble()); maxX = max(maxX, p[0].toDouble())
            minY = min(minY, p[1].toDouble()); maxY = max(maxY, p[1].toDouble())
            minZ = min(minZ, p[2].toDouble()); maxZ = max(maxZ, p[2].toDouble())
        }
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return sqrt(dx * dx + dy * dy + dz * dz)
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
        if (kotlin.math.abs(t[15] - 1.0) > 1e-4) return false
        val det = t[0] * (t[5] * t[10] - t[6] * t[9]) -
            t[1] * (t[4] * t[10] - t[6] * t[8]) +
            t[2] * (t[4] * t[9] - t[5] * t[8])
        if (det !in 0.985..1.015) return false

        val trace = t[0] + t[5] + t[10]
        val angle = Math.toDegrees(acos(((trace - 1.0) * 0.5).coerceIn(-1.0, 1.0)))
        return angle.isFinite()
    }

    private const val MIN_MATCHES = 10
    private const val MAX_MATCHES = 96
    private const val SIFT_RATIO = 0.78f
    private const val STRICT_RATIO = 0.70f
    private const val RECOVERY_RATIO = 0.76f
    private const val METRIC_ASSOCIATION_RADIUS_PX = 10.0
    private const val VISUAL_INLIER_PX = 4.0
    private const val METRIC_INLIER_M = 0.18
    private const val MIN_POSITIVE_DEPTH_M = 1e-4
    private const val FAILED_REPROJECTION_PX = 999.0
}
