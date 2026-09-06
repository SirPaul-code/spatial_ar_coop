package com.sirpaul.spatialnomap

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object FusionMath {
    data class YawPrior(
        val yawRemoteToLocalDeg: Double,
        val confidence: Float,
    )

    data class Bootstrap(
        val transformLocalFromRemote: DoubleArray,
        val confidence: Float,
        val source: String,
        val expectedDeviceDistanceM: Double,
    )

    fun yawPrior(remote: CapturedFrame, local: CapturedFrame): YawPrior? {
        val rs = remote.sensors
        val ls = local.sensors
        if (!rs.hasHeading || !ls.hasHeading) return null

        val remoteLocalHeading = cameraHeadingInArWorldDeg(remote.pose)
        val localLocalHeading = cameraHeadingInArWorldDeg(local.pose)
        if (!remoteLocalHeading.isFinite() || !localLocalHeading.isFinite()) return null

        val remoteWorldOffset = normalizeDeg(rs.headingDeg.toDouble() - remoteLocalHeading)
        val localWorldOffset = normalizeDeg(ls.headingDeg.toDouble() - localLocalHeading)
        val yaw = normalizeDeg(remoteWorldOffset - localWorldOffset)
        val q = min(rs.orientationQuality, ls.orientationQuality).coerceIn(0f, 1f)
        return YawPrior(yaw, q)
    }

    /** Heading change induced by the rotation in a remote->local transform. */
    fun yawFromTransformDeg(transform: DoubleArray): Double {
        if (transform.size < 16) return Double.NaN
        // Remote world forward is (0,0,-1). Transform it as a direction.
        val x = -transform[2]
        val z = -transform[10]
        return normalizeDeg(Math.toDegrees(atan2(x, -z)))
    }

    fun yawResidualDeg(transform: DoubleArray, prior: YawPrior?): Double {
        if (prior == null) return Double.NaN
        return abs(angleDeltaDeg(yawFromTransformDeg(transform), prior.yawRemoteToLocalDeg))
    }

    /**
     * Creates an absolute coarse transform when GNSS is genuinely informative.
     * It is never treated as centimeter precision; it exists to seed and score
     * visual/ranging alignment and can become a fallback only at high confidence.
     */
    fun bootstrapFromGnss(remote: CapturedFrame, local: CapturedFrame): Bootstrap? {
        val prior = yawPrior(remote, local) ?: return null
        val rs = remote.sensors
        val ls = local.sensors
        if (!rs.hasLocation || !ls.hasLocation) return null

        val combinedAccuracy = sqrt(
            rs.horizontalAccuracyM.toDouble() * rs.horizontalAccuracyM +
                ls.horizontalAccuracyM.toDouble() * ls.horizontalAccuracyM,
        )
        if (!combinedAccuracy.isFinite() || combinedAccuracy > 12.0) return null

        val enu = enuMeters(
            lat0Deg = ls.latitudeDeg,
            lon0Deg = ls.longitudeDeg,
            alt0M = ls.altitudeM,
            latDeg = rs.latitudeDeg,
            lonDeg = rs.longitudeDeg,
            altM = rs.altitudeM,
        )
        val horizontalSeparation = sqrt(enu[0] * enu[0] + enu[1] * enu[1])

        // If both phones are in the same room and GNSS says they are only a few
        // meters apart with several meters of uncertainty, its bearing is noise.
        if (horizontalSeparation < combinedAccuracy * 0.85) return null

        val localWorldHeading = cameraHeadingInArWorldDeg(local.pose)
        val localEarthOffset = normalizeDeg(ls.headingDeg.toDouble() - localWorldHeading)
        val earthHeading = Math.toDegrees(atan2(enu[0], enu[1]))
        val localHeading = Math.toRadians(normalizeDeg(earthHeading - localEarthOffset))
        val horizontal = horizontalSeparation
        val targetRemoteCameraInLocal = doubleArrayOf(
            local.pose.t.getOrElse(0) { 0f } + horizontal * sin(localHeading),
            local.pose.t.getOrElse(1) { 0f }.toDouble() + enu[2],
            local.pose.t.getOrElse(2) { 0f } - horizontal * cos(localHeading),
        )

        val r = headingYawMatrix(prior.yawRemoteToLocalDeg)
        val mappedRemote = transformDirectionPoint(r, remote.pose.t)
        r[3] = targetRemoteCameraInLocal[0] - mappedRemote[0]
        r[7] = targetRemoteCameraInLocal[1] - mappedRemote[1]
        r[11] = targetRemoteCameraInLocal[2] - mappedRemote[2]

        val orientationConfidence = prior.confidence.toDouble()
        val locationConfidence = (1.0 - combinedAccuracy / 12.0).coerceIn(0.0, 1.0)
        val baselineConfidence = (horizontalSeparation / (combinedAccuracy * 2.0)).coerceIn(0.0, 1.0)
        val confidence = (orientationConfidence * locationConfidence * baselineConfidence)
            .coerceIn(0.0, 0.72)
            .toFloat()

        return Bootstrap(r, confidence, "GNSS+COMPASS", horizontalSeparation)
    }

    /**
     * When the radios prove the phones are physically very close, absolute yaw
     * plus ARCore's metric camera positions gives a useful immediate coarse seed.
     * Translation error is bounded by the measured phone separation.
     */
    fun bootstrapFromCoLocation(
        remote: CapturedFrame,
        local: CapturedFrame,
        rangeM: Float?,
        rangeStdM: Float?,
    ): Bootstrap? {
        val prior = yawPrior(remote, local) ?: return null
        val range = rangeM?.takeIf { it.isFinite() && it in 0.05f..1.25f } ?: return null
        val r = headingYawMatrix(prior.yawRemoteToLocalDeg)
        val mappedRemote = transformDirectionPoint(r, remote.pose.t)

        // Co-location prior: align the current camera centers. Vision/RTT history
        // then refines the remaining <=range translation ambiguity.
        r[3] = local.pose.t.getOrElse(0) { 0f } - mappedRemote[0]
        r[7] = local.pose.t.getOrElse(1) { 0f } - mappedRemote[1]
        r[11] = local.pose.t.getOrElse(2) { 0f } - mappedRemote[2]

        val std = rangeStdM?.takeIf { it.isFinite() } ?: 0.35f
        val rangeQuality = (1f - (range / 1.25f)).coerceIn(0f, 1f) *
            (1f - (std / 0.8f)).coerceIn(0.25f, 1f)
        val confidence = (0.18f + 0.34f * min(prior.confidence, rangeQuality)).coerceAtMost(0.52f)
        return Bootstrap(r, confidence, "RTT+COMPASS", range.toDouble())
    }

    fun cameraHeadingInArWorldDeg(pose: PosePacket): Double {
        val q = pose.q
        var x = q.getOrElse(0) { 0f }.toDouble()
        var y = q.getOrElse(1) { 0f }.toDouble()
        var z = q.getOrElse(2) { 0f }.toDouble()
        var w = q.getOrElse(3) { 1f }.toDouble()
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-9) return Double.NaN
        x /= n; y /= n; z /= n; w /= n

        // Rotate camera forward (0,0,-1) into the AR world.
        val fx = -2.0 * (x * z + y * w)
        val fz = -(1.0 - 2.0 * (x * x + y * y))
        if (abs(fx) + abs(fz) < 1e-8) return Double.NaN
        return normalizeDeg(Math.toDegrees(atan2(fx, -fz)))
    }

    /** Matrix that increases our heading convention by yawDeg. */
    fun headingYawMatrix(yawDeg: Double): DoubleArray {
        val a = Math.toRadians(yawDeg)
        val c = cos(a)
        val s = sin(a)
        return doubleArrayOf(
            c, 0.0, -s, 0.0,
            0.0, 1.0, 0.0, 0.0,
            s, 0.0, c, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    fun angleDeltaDeg(a: Double, b: Double): Double = normalizeDeg(a - b)

    fun normalizeDeg(value: Double): Double {
        var x = value % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }

    private fun transformDirectionPoint(t: DoubleArray, p: FloatArray): DoubleArray {
        val x = p.getOrElse(0) { 0f }.toDouble()
        val y = p.getOrElse(1) { 0f }.toDouble()
        val z = p.getOrElse(2) { 0f }.toDouble()
        return doubleArrayOf(
            t[0] * x + t[1] * y + t[2] * z,
            t[4] * x + t[5] * y + t[6] * z,
            t[8] * x + t[9] * y + t[10] * z,
        )
    }

    /** ENU displacement from (lat0,lon0,alt0) to (lat,lon,alt), meters. */
    fun enuMeters(
        lat0Deg: Double,
        lon0Deg: Double,
        alt0M: Double,
        latDeg: Double,
        lonDeg: Double,
        altM: Double,
    ): DoubleArray {
        val meanLat = Math.toRadians((lat0Deg + latDeg) * 0.5)
        val dLat = Math.toRadians(latDeg - lat0Deg)
        val dLon = Math.toRadians(lonDeg - lon0Deg)
        val earthRadius = 6_378_137.0
        val north = dLat * earthRadius
        val east = dLon * earthRadius * cos(meanLat)
        val up = if (alt0M.isFinite() && altM.isFinite()) altM - alt0M else 0.0
        return doubleArrayOf(east, north, up)
    }
}
