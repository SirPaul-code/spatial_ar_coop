package com.sirpaul.spatialnomap

import android.util.Base64
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.DMatch
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

object AlignmentEngine {
    data class Result(
        val transformLocalFromRemote: DoubleArray,
        val inliers: Int,
        val correspondences: Int,
        val matches: Int,
        val medianReprojectionPx: Double,
        val imageCoverage: Double,
        val predictedDeviceDistanceM: Double,
        val confidence: Float,
        val headingResidualDeg: Double = Double.NaN,
        val sensorPriorConfidence: Float = 0f,
        val gravityTiltDeg: Double = Double.NaN,
    )

    private data class MatchSet(
        val matches: List<DMatch>,
        val keyRemote: Array<KeyPoint>,
        val keyLocal: Array<KeyPoint>,
    )

    private data class RatioMatch(
        val match: DMatch,
        val ratio: Float,
    )

    fun solve(remote: CapturedFrame, local: CapturedFrame): Result? {
        if (remote.metricPoints.size < 18) return null
        val matchSet = siftMatches(remote, local) ?: return null
        if (matchSet.matches.size < 8) return null

        val used = HashSet<Int>()
        val objectPoints = ArrayList<Point3>()
        val imagePoints = ArrayList<Point>()
        val gate2 = METRIC_ASSOCIATION_RADIUS_PX * METRIC_ASSOCIATION_RADIUS_PX
        for (m in matchSet.matches) {
            val p = matchSet.keyRemote[m.queryIdx].pt
            var best = -1
            var bestD2 = Double.POSITIVE_INFINITY
            for (j in remote.metricPoints.indices) {
                if (j in used) continue
                val s = remote.metricPoints[j]
                val dx = s[0] - p.x
                val dy = s[1] - p.y
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) {
                    bestD2 = d2
                    best = j
                }
            }
            if (best < 0 || bestD2 > gate2) continue
            used += best
            val s = remote.metricPoints[best]
            objectPoints += Point3(s[2].toDouble(), s[3].toDouble(), s[4].toDouble())
            val q = matchSet.keyLocal[m.trainIdx].pt
            imagePoints += Point(q.x, q.y)
        }
        if (objectPoints.size < 6) return null

        val obj = MatOfPoint3f(*objectPoints.toTypedArray())
        val img = MatOfPoint2f(*imagePoints.toTypedArray())
        val k = cameraMatrix(local.intrinsics)
        val dist = MatOfDouble()
        val rvec = Mat()
        val tvec = Mat()
        val inlierMat = Mat()
        val ok = Calib3d.solvePnPRansac(
            obj,
            img,
            k,
            dist,
            rvec,
            tvec,
            false,
            900,
            3.6f,
            0.999,
            inlierMat,
            Calib3d.SOLVEPNP_EPNP,
        )
        if (!ok || inlierMat.rows() < 6) {
            releaseAll(obj, img, k, dist, rvec, tvec, inlierMat)
            return null
        }

        val inlierIndexes = IntArray(inlierMat.rows())
        inlierMat.get(0, 0, inlierIndexes)
        val inObj = ArrayList<Point3>()
        val inImg = ArrayList<Point>()
        for (idx in inlierIndexes) {
            if (idx in objectPoints.indices) {
                inObj += objectPoints[idx]
                inImg += imagePoints[idx]
            }
        }
        if (inObj.size < 6 || spatialDiameterM(inObj) < MIN_WORLD_SUPPORT_DIAMETER_M) {
            releaseAll(obj, img, k, dist, rvec, tvec, inlierMat)
            return null
        }

        val objIn = MatOfPoint3f(*inObj.toTypedArray())
        val imgIn = MatOfPoint2f(*inImg.toTypedArray())
        try {
            Calib3d.solvePnPRefineVVS(objIn, imgIn, k, dist, rvec, tvec)
        } catch (_: Throwable) {
            try {
                Calib3d.solvePnPRefineLM(objIn, imgIn, k, dist, rvec, tvec)
            } catch (_: Throwable) {
            }
        }

        val rotation = Mat()
        Calib3d.Rodrigues(rvec, rotation)
        val r = DoubleArray(9)
        rotation.get(0, 0, r)
        val t = DoubleArray(3)
        tvec.get(0, 0, t)

        val tCvCameraFromRemote = doubleArrayOf(
            r[0], r[1], r[2], t[0],
            r[3], r[4], r[5], t[1],
            r[6], r[7], r[8], t[2],
            0.0, 0.0, 0.0, 1.0,
        )
        val cvToAr = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, -1.0, 0.0, 0.0,
            0.0, 0.0, -1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val localFromRemote = multiply4(poseMatrix(local.pose), multiply4(cvToAr, tCvCameraFromRemote))

        val errors = DoubleArray(inObj.size)
        var positiveDepthCount = 0
        for (i in inObj.indices) {
            val p = inObj[i]
            val x = r[0] * p.x + r[1] * p.y + r[2] * p.z + t[0]
            val y = r[3] * p.x + r[4] * p.y + r[5] * p.z + t[1]
            val z = r[6] * p.x + r[7] * p.y + r[8] * p.z + t[2]
            if (z <= 1e-6) {
                errors[i] = 999.0
            } else {
                positiveDepthCount += 1
                val u = local.intrinsics.fx * x / z + local.intrinsics.cx
                val v = local.intrinsics.fy * y / z + local.intrinsics.cy
                val q = inImg[i]
                val dx = u - q.x
                val dy = v - q.y
                errors[i] = sqrt(dx * dx + dy * dy)
            }
        }
        if (positiveDepthCount < (inObj.size * MIN_CHEIRALITY_RATIO).toInt()) {
            releaseAll(obj, img, k, dist, rvec, tvec, inlierMat, objIn, imgIn, rotation)
            return null
        }

        errors.sort()
        val median = if (errors.isNotEmpty()) errors[errors.size / 2] else 999.0
        val coverage = imageCoverage(inImg, local.intrinsics.width, local.intrinsics.height)
        val ratio = inObj.size.toDouble() / objectPoints.size
        val supportFactor = min(1.0, inObj.size / 20.0)
        val coverageFactor = min(1.0, coverage / 0.16)
        var confidence = (ratio * supportFactor * coverageFactor * exp(-median / 4.2))
            .coerceIn(0.0, 1.0)
            .toFloat()

        val gravityTilt = FusionMath.gravityTiltDeg(localFromRemote)
        if (gravityTilt.isFinite()) {
            if (gravityTilt > 32.0) {
                releaseAll(obj, img, k, dist, rvec, tvec, inlierMat, objIn, imgIn, rotation)
                return null
            }
            confidence *= exp(-gravityTilt / 22.0).toFloat()
            if (gravityTilt < 5.0) confidence = (confidence * 1.08f).coerceAtMost(1f)
        }

        val yawPrior = FusionMath.yawPrior(remote, local)
        val headingResidual = FusionMath.yawResidualDeg(localFromRemote, yawPrior)
        if (yawPrior != null && headingResidual.isFinite()) {
            if (yawPrior.confidence >= 0.55f && headingResidual > 65.0) {
                releaseAll(obj, img, k, dist, rvec, tvec, inlierMat, objIn, imgIn, rotation)
                return null
            }
            val headingFit = exp(-abs(headingResidual) / 30.0).toFloat()
            val q = yawPrior.confidence.coerceIn(0f, 1f)
            confidence *= (1f - 0.35f * q + 0.35f * q * headingFit)
            if (headingResidual < 18.0 && q > 0.35f) {
                confidence = (confidence * (1.0f + 0.18f * q)).coerceAtMost(1f)
            }
        }

        val remoteCameraInLocal = transformPoint(localFromRemote, remote.pose.t)
        val lc = local.pose.t
        val dx = remoteCameraInLocal[0] - lc[0]
        val dy = remoteCameraInLocal[1] - lc[1]
        val dz = remoteCameraInLocal[2] - lc[2]
        val predictedDistance = sqrt(dx * dx + dy * dy + dz * dz)

        releaseAll(obj, img, k, dist, rvec, tvec, inlierMat, objIn, imgIn, rotation)
        if (!localFromRemote.all { it.isFinite() } || determinant3(localFromRemote) !in 0.95..1.05) return null

        return Result(
            transformLocalFromRemote = localFromRemote,
            inliers = inObj.size,
            correspondences = objectPoints.size,
            matches = matchSet.matches.size,
            medianReprojectionPx = median,
            imageCoverage = coverage,
            predictedDeviceDistanceM = predictedDistance,
            confidence = confidence,
            headingResidualDeg = headingResidual,
            sensorPriorConfidence = yawPrior?.confidence ?: 0f,
            gravityTiltDeg = gravityTilt,
        )
    }

    /**
     * Mutual SIFT matches remain the trusted core, but we also keep a bounded set
     * of very strong one-way ratio matches. Previously, once eight mutual matches
     * existed, all other high-quality matches were discarded; with sparse depth
     * that could leave only 6-7 metric correspondences forever. RANSAC is exactly
     * the layer which should reject the remaining outliers.
     */
    private fun siftMatches(remote: CapturedFrame, local: CapturedFrame): MatchSet? {
        val a = decodeGray(remote) ?: return null
        val b = decodeGray(local) ?: run {
            a.release()
            return null
        }
        val sift = SIFT.create(2600, 3, 0.016, 12.0, 1.6)
        val kpa = MatOfKeyPoint()
        val kpb = MatOfKeyPoint()
        val da = Mat()
        val db = Mat()
        val emptyMaskA = Mat()
        val emptyMaskB = Mat()
        sift.detectAndCompute(a, emptyMaskA, kpa, da)
        sift.detectAndCompute(b, emptyMaskB, kpb, db)
        emptyMaskA.release()
        emptyMaskB.release()
        if (da.empty() || db.empty() || kpa.rows() < 8 || kpb.rows() < 8) {
            releaseAll(a, b, kpa, kpb, da, db)
            sift.clear()
            return null
        }

        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val forward = ArrayList<MatOfDMatch>()
        val reverse = ArrayList<MatOfDMatch>()
        matcher.knnMatch(da, db, forward, 2)
        matcher.knnMatch(db, da, reverse, 2)

        val reverseBest = IntArray(db.rows()) { -1 }
        for (pair in reverse) {
            val arr = pair.toArray()
            if (arr.size >= 2 && arr[1].distance > 1e-6f) {
                val ratio = arr[0].distance / arr[1].distance
                if (ratio < SIFT_RATIO) {
                    val localIdx = arr[0].queryIdx
                    if (localIdx in reverseBest.indices) reverseBest[localIdx] = arr[0].trainIdx
                }
            }
            pair.release()
        }

        val mutual = ArrayList<DMatch>()
        val ratioAccepted = ArrayList<RatioMatch>()
        for (pair in forward) {
            val arr = pair.toArray()
            if (arr.size >= 2 && arr[1].distance > 1e-6f) {
                val ratio = arr[0].distance / arr[1].distance
                if (ratio < SIFT_RATIO) {
                    val m = arr[0]
                    ratioAccepted += RatioMatch(m, ratio)
                    if (m.trainIdx in reverseBest.indices && reverseBest[m.trainIdx] == m.queryIdx) {
                        mutual += m
                    }
                }
            }
            pair.release()
        }

        val good = ArrayList<DMatch>(mutual.size + MAX_STRICT_AUGMENT)
        val usedQuery = HashSet<Int>()
        val usedTrain = HashSet<Int>()
        for (m in mutual.sortedBy { it.distance }) {
            if (usedQuery.add(m.queryIdx) && usedTrain.add(m.trainIdx)) good += m
        }

        for (candidate in ratioAccepted.sortedWith(compareBy<RatioMatch> { it.ratio }.thenBy { it.match.distance })) {
            if (good.size >= mutual.size + MAX_STRICT_AUGMENT) break
            if (candidate.ratio > STRICT_FALLBACK_RATIO) break
            val m = candidate.match
            if (usedQuery.add(m.queryIdx) && usedTrain.add(m.trainIdx)) good += m
        }

        // Recovery path only when the strict set is still too small to run PnP.
        if (good.size < MIN_MATCHES_FOR_PNP) {
            for (candidate in ratioAccepted.sortedWith(compareBy<RatioMatch> { it.ratio }.thenBy { it.match.distance })) {
                if (good.size >= RECOVERY_MATCH_TARGET) break
                if (candidate.ratio > RECOVERY_FALLBACK_RATIO) break
                val m = candidate.match
                if (usedQuery.add(m.queryIdx) && usedTrain.add(m.trainIdx)) good += m
            }
        }

        val result = MatchSet(good, kpa.toArray(), kpb.toArray())
        releaseAll(a, b, kpa, kpb, da, db)
        matcher.clear()
        sift.clear()
        return result
    }

    private fun spatialDiameterM(points: List<Point3>): Double {
        if (points.isEmpty()) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = kotlin.math.min(minX, p.x)
            minY = kotlin.math.min(minY, p.y)
            minZ = kotlin.math.min(minZ, p.z)
            maxX = kotlin.math.max(maxX, p.x)
            maxY = kotlin.math.max(maxY, p.y)
            maxZ = kotlin.math.max(maxZ, p.z)
        }
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun decodeGray(frame: CapturedFrame): Mat? {
        return try {
            val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
            val mob = MatOfByte(*bytes)
            val image = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_GRAYSCALE)
            mob.release()
            image.takeUnless { it.empty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun cameraMatrix(k: IntrinsicsPacket): Mat {
        val m = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F)
        m.put(0, 0, k.fx.toDouble())
        m.put(1, 1, k.fy.toDouble())
        m.put(0, 2, k.cx.toDouble())
        m.put(1, 2, k.cy.toDouble())
        return m
    }

    private fun imageCoverage(points: List<Point>, width: Int, height: Int): Double {
        if (points.size < 2 || width <= 0 || height <= 0) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = kotlin.math.min(minX, p.x)
            maxX = kotlin.math.max(maxX, p.x)
            minY = kotlin.math.min(minY, p.y)
            maxY = kotlin.math.max(maxY, p.y)
        }
        val area = kotlin.math.max(0.0, maxX - minX) * kotlin.math.max(0.0, maxY - minY)
        return (area / (width.toDouble() * height.toDouble())).coerceIn(0.0, 1.0)
    }

    internal fun poseMatrix(p: PosePacket): DoubleArray {
        val q = p.q
        var x = q.getOrElse(0) { 0f }.toDouble()
        var y = q.getOrElse(1) { 0f }.toDouble()
        var z = q.getOrElse(2) { 0f }.toDouble()
        var w = q.getOrElse(3) { 1f }.toDouble()
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n > 1e-12) {
            x /= n; y /= n; z /= n; w /= n
        }
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy), p.t.getOrElse(0) { 0f }.toDouble(),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx), p.t.getOrElse(1) { 0f }.toDouble(),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy), p.t.getOrElse(2) { 0f }.toDouble(),
            0.0, 0.0, 0.0, 1.0,
        )
    }

    internal fun multiply4(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0..3) for (c in 0..3) {
            var v = 0.0
            for (k in 0..3) v += a[r * 4 + k] * b[k * 4 + c]
            out[r * 4 + c] = v
        }
        return out
    }

    fun transformPoint(t: DoubleArray, p: FloatArray): DoubleArray {
        val x = p.getOrElse(0) { 0f }.toDouble()
        val y = p.getOrElse(1) { 0f }.toDouble()
        val z = p.getOrElse(2) { 0f }.toDouble()
        return doubleArrayOf(
            t[0] * x + t[1] * y + t[2] * z + t[3],
            t[4] * x + t[5] * y + t[6] * z + t[7],
            t[8] * x + t[9] * y + t[10] * z + t[11],
        )
    }

    fun transformDelta(a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val dx = a[3] - b[3]
        val dy = a[7] - b[7]
        val dz = a[11] - b[11]
        val translation = sqrt(dx * dx + dy * dy + dz * dz)
        val trace =
            a[0] * b[0] + a[4] * b[4] + a[8] * b[8] +
                a[1] * b[1] + a[5] * b[5] + a[9] * b[9] +
                a[2] * b[2] + a[6] * b[6] + a[10] * b[10]
        val cosTheta = ((trace - 1.0) * 0.5).coerceIn(-1.0, 1.0)
        return Pair(translation, Math.toDegrees(acos(cosTheta)))
    }

    private fun determinant3(t: DoubleArray): Double =
        t[0] * (t[5] * t[10] - t[6] * t[9]) -
            t[1] * (t[4] * t[10] - t[6] * t[8]) +
            t[2] * (t[4] * t[9] - t[5] * t[8])

    private fun releaseAll(vararg mats: Mat) {
        for (m in mats) runCatching { m.release() }
    }

    private const val SIFT_RATIO = 0.80f
    private const val STRICT_FALLBACK_RATIO = 0.72f
    private const val RECOVERY_FALLBACK_RATIO = 0.77f
    private const val MIN_MATCHES_FOR_PNP = 8
    private const val RECOVERY_MATCH_TARGET = 12
    private const val MAX_STRICT_AUGMENT = 24
    private const val METRIC_ASSOCIATION_RADIUS_PX = 20.0
    private const val MIN_WORLD_SUPPORT_DIAMETER_M = 0.08
    private const val MIN_CHEIRALITY_RATIO = 0.90
}
