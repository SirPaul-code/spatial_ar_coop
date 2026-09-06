package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FusionMathTest {
    private val identityPose = PosePacket(
        t = floatArrayOf(0f, 0f, 0f),
        q = floatArrayOf(0f, 0f, 0f, 1f),
    )

    private fun frame(
        heading: Float,
        quality: Float = 0.9f,
        x: Float = 0f,
        lat: Double = Double.NaN,
        lon: Double = Double.NaN,
        accuracy: Float = Float.POSITIVE_INFINITY,
    ) = CapturedFrame(
        timestampNs = 1L,
        pose = identityPose.copy(t = floatArrayOf(x, 0f, 0f)),
        intrinsics = IntrinsicsPacket(700f, 700f, 480f, 270f, 960, 540),
        jpegBase64 = "",
        metricPoints = emptyList(),
        sensors = SensorSnapshot(
            headingDeg = heading,
            orientationQuality = quality,
            latitudeDeg = lat,
            longitudeDeg = lon,
            horizontalAccuracyM = accuracy,
        ),
    )

    @Test
    fun identityArPoseFacesZeroHeadingInLocalWorld() {
        assertEquals(0.0, FusionMath.cameraHeadingInArWorldDeg(identityPose), 1e-8)
    }

    @Test
    fun compassPriorRecoversRelativeArWorldYaw() {
        val remote = frame(heading = 40f)
        val local = frame(heading = 10f)
        val prior = requireNotNull(FusionMath.yawPrior(remote, local))
        assertEquals(30.0, prior.yawRemoteToLocalDeg, 1e-5)
        assertEquals(0.9f, prior.confidence, 1e-6f)

        val transform = FusionMath.headingYawMatrix(prior.yawRemoteToLocalDeg)
        assertEquals(30.0, FusionMath.yawFromTransformDeg(transform), 1e-5)
        assertEquals(0.0, FusionMath.yawResidualDeg(transform, prior), 1e-5)
    }

    @Test
    fun angleWrapUsesShortestSignedDifference() {
        assertEquals(2.0, FusionMath.angleDeltaDeg(-179.0, 179.0), 1e-9)
        assertEquals(-2.0, FusionMath.angleDeltaDeg(179.0, -179.0), 1e-9)
    }

    @Test
    fun enuConvertsSmallGpsOffsetToMetricEastNorth() {
        val enu = FusionMath.enuMeters(
            lat0Deg = 49.0,
            lon0Deg = 21.0,
            alt0M = 250.0,
            latDeg = 49.0001,
            lonDeg = 21.0001,
            altM = 253.0,
        )
        assertTrue(enu[0] in 7.0..7.6)
        assertTrue(enu[1] in 10.8..11.4)
        assertEquals(3.0, enu[2], 1e-9)
    }

    @Test
    fun rttColocationSeedRespectsCompassYawAndBoundsConfidence() {
        val remote = frame(heading = 55f, x = 0.2f)
        val local = frame(heading = 15f, x = 1.1f)
        val seed = FusionMath.bootstrapFromCoLocation(remote, local, 0.65f, 0.12f)
        assertNotNull(seed)
        seed!!
        assertEquals("RTT+COMPASS", seed.source)
        assertEquals(40.0, FusionMath.yawFromTransformDeg(seed.transformLocalFromRemote), 1e-4)
        assertTrue(seed.confidence in 0.18f..0.52f)
        assertEquals(0.65, seed.expectedDeviceDistanceM, 1e-5)
    }

    @Test
    fun noisySameRoomGpsIsNotAllowedToPretendItKnowsTranslation() {
        val remote = frame(heading = 20f, lat = 49.0, lon = 21.0, accuracy = 4f)
        val local = frame(heading = 18f, lat = 49.00001, lon = 21.00001, accuracy = 4f)
        val seed = FusionMath.bootstrapFromGnss(remote, local)
        assertTrue(seed == null)
    }

    @Test
    fun headingMatrixRoundTripsAcrossCardinalAngles() {
        for (yaw in listOf(-179.0, -90.0, -15.0, 0.0, 25.0, 90.0, 179.0)) {
            val recovered = FusionMath.yawFromTransformDeg(FusionMath.headingYawMatrix(yaw))
            assertTrue(abs(FusionMath.angleDeltaDeg(recovered, yaw)) < 1e-7)
        }
    }
}
