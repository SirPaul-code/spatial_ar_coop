package com.sirpaul.spatialnomap

import android.util.Base64
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
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

    fun solve(remote: CapturedFrame, local: CapturedFrame): Result? {
        if (remote.metricPoints.size < 18) return null
        val matchSet = siftMatches(remote, local) ?: return null
        if (matchSet.matches.size < 8) return null

        val used = HashSet<Int>()
        val objectPoints = ArrayList<Point3>()
        val imagePoints = ArrayList<Point>()
        val gate2 = 18.0 * 18.0
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
            700,
            4.0f,
            0.997,
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
        if (inObj.size < 6) {
            releaseAll(obj, img, k, dist, rvec, tvec, inlierMat)
            return null
        }

        val objIn = MatOfPoint3f(*inObj.toTypedArray())
        val imgIn = MatOfPoint2f(*inImg.toTypedArray())
        try {
            Calib3d.solvePnPRefineLM(objIn, imgIn, k, dist, rvec, tvec)
        } catch (_: Throwable) {
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
        for (i in inObj.indices) {
            val p = inObj[i]
            val x = r[0] * p.x + r[1] * p.y + r[2] * p.z + t[0]
            val y = r[3] * p.x + r[4] * p.y + r[5] * p.z + t[1]
            val z = r[6] * p.x + r[7] * p.y + r[8] * p.z + t[2]
            if (z <= 1e-6) {
                errors[i] = 999.0
            } else {
                val u = local.intrinsics.fx * x / z + local.intrinsics.cx
                val v = local.intrinsics.fy * y / z + local.intrinsics.cy
                val q = inImg[i]
                val dx = u - q.x
                val dy = v - q.y
                errors[i] = sqrt(dx * dx + dy * dy)
            }
        }
        errors.sort()
        val median = if (errors.isNotEmpty()) errors[errors.size / 2] else 999.0
        val coverage = imageCoverage(inImg, local.intrinsics.width, local.intrinsics.height)
        val ratio = inObj.size.toDouble() / objectPoints.size
        val supportFactor = min(1.0, inObj.size / 20.0)
        val coverageFactor = min(1.0, coverage / 0.16)
        var confidence = (ratio * supportFactor * coverageFactor * exp(-median / 4.5))
            .coerceIn(0.0, 1.0)
            .toFloat()

        // ARCore fuses accelerometer/gyro to keep each local world gravity aligned.
        // A remote->local transform that tilts gravity substantially is therefore
        // physically implausible even when PnP found a tempting visual solution.
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

    private fun siftMatches(remote: CapturedFrame, local: CapturedFrame): MatchSet? {
        val a = decodeGray(remote) ?: return null
        val b = decodeGray(local) ?: run {
            a.release()
            return null
        }
        val sift = SIFT.create(2200, 3, 0.018, 12.0, 1.6)
        val kpa = MatOfKeyPoint()
        val kpb = MatOfKeyPoint()
        val da = Mat()
        val db = Mat()
        sift.detectAndCompute(a, Mat(), kpa, da)
        sift.detectAndCompute(b, Mat(), kpb, db)
        if (da.empty() || db.empty() || kpa.rows() < 8 || kpb.rows() < 8) {
            releaseAll(a, b, kpa, kpb, da, db)
            sift.clear()
            return null
        }

        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(da, db, knn, 2)
        val good = ArrayList<DMatch>()
        for (pair in knn) {
            val arr = pair.toArray()
            if (arr.size >= 2 && arr[0].distance < 0.80f * arr[1].distance) good += arr[0]
            pair.release()
        }
        val result = MatchSet(good, kpa.toArray(), kpb.toArray())
        releaseAll(a, b, kpa, kpb, da, db)
        matcher.clear()
        sift.clear()
        return result
    }

    private fun decodeGray(frame: CapturedFrame): Mat? = try {
        val encoded = MatOfByte(*Base64.decode(frame.jpegBase64, Base64.NO_WRAP))
        val image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE)
        encoded.release()
        if (image.empty()) {
            image.release()
            null
        } else image
    } catch (_: Throwable) {
        null
    }

    private fun cameraMatrix(k: IntrinsicsPacket): Mat = Mat.eye(3, 3, CvType.CV_64F).apply {
        put(0, 0, k.fx.toDouble())
        put(0, 2, k.cx.toDouble())
        put(1, 1, k.fy.toDouble())
        put(1, 2, k.cy.toDouble())
    }

    fun poseMatrix(pose: PosePacket): DoubleArray {
        var x = pose.q.getOrElse(0) { 0f }.toDouble()
        var y = pose.q.getOrElse(1) { 0f }.toDouble()
        var z = pose.q.getOrElse(2) { 0f }.toDouble()
        var w = pose.q.getOrElse(3) { 1f }.toDouble()
        val n = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-12)
        x /= n; y /= n; z /= n; w /= n
        val t = pose.t
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), t.getOrElse(0) { 0f }.toDouble(),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), t.getOrElse(1) { 0f }.toDouble(),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), t.getOrElse(2) { 0f }.toDouble(),
            0.0, 0.0, 0.0, 1.0,
        )
    }

    fun multiply4(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0..3) for (c in 0..3) {
            var s = 0.0
            for (k in 0..3) s += a[r * 4 + k] * b[k * 4 + c]
            out[r * 4 + c] = s
        }
        return out
    }

    fun transformPoint(t: DoubleArray, p: FloatArray): DoubleArray {
        val x = p.getOrElse(0) { 0f }.toDouble()
        val y = p.getOrElse(1) { 0f }.toDouble()
        val z = p.getOrElse(2) { 0f }.toDouble()
        val ox = t[0] * x + t[1] * y + t[2] * z + t[3]
        val oy = t[4] * x + t[5] * y + t[6] * z + t[7]
        val oz = t[8] * x + t[9] * y + t[10] * z + t[11]
        val ow = t[12] * x + t[13] * y + t[14] * z + t[15]
        val d = if (kotlin.math.abs(ow) < 1e-9) 1.0 else ow
        return doubleArrayOf(ox / d, oy / d, oz / d)
    }

    fun transformDelta(a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val dx = a[3] - b[3]
        val dy = a[7] - b[7]
        val dz = a[11] - b[11]
        val translation = sqrt(dx * dx + dy * dy + dz * dz)
        var trace = 0.0
        for (i in 0..2) {
            var v = 0.0
            for (k in 0..2) v += a[k * 4 + i] * b[k * 4 + i]
            trace += v
        }
        return translation to Math.toDegrees(acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    private fun imageCoverage(points: List<Point>, w: Int, h: Int): Double {
        if (points.size < 4 || w <= 0 || h <= 0) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = kotlin.math.min(minX, p.x)
            maxX = kotlin.math.max(maxX, p.x)
            minY = kotlin.math.min(minY, p.y)
            maxY = kotlin.math.max(maxY, p.y)
        }
        return sqrt(
            ((maxX - minX) / w).coerceIn(0.0, 1.0) *
                ((maxY - minY) / h).coerceIn(0.0, 1.0),
        )
    }

    private fun determinant3(m: DoubleArray) =
        m[0] * (m[5] * m[10] - m[6] * m[9]) -
            m[1] * (m[4] * m[10] - m[6] * m[8]) +
            m[2] * (m[4] * m[9] - m[5] * m[8])

    private fun releaseAll(vararg mats: Mat) {
        mats.forEach { try { it.release() } catch (_: Throwable) {} }
    }
}
