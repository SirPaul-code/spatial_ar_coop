package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class PeerProtocolTest {
    private fun roundTrip(message: WireMessage): WireMessage {
        val bytes = ByteArrayOutputStream()
        PeerProtocol.write(bytes, message)
        return requireNotNull(PeerProtocol.read(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun helloRoundTrip() {
        val result = roundTrip(WireMessage.Hello("Pavol", "SM-S938B")) as WireMessage.Hello
        assertEquals("Pavol", result.username)
        assertEquals("SM-S938B", result.deviceModel)
    }

    @Test
    fun frameRoundTripPreservesCameraGeometryMetricSupportsAndSensorFusion() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 4, 0xff.toByte(), 0xd9.toByte())
        val original = CapturedFrame(
            timestampNs = 987654321L,
            pose = PosePacket(
                floatArrayOf(1.1f, -2.2f, 3.3f),
                floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f),
            ),
            intrinsics = IntrinsicsPacket(
                fx = 711.5f,
                fy = 709.25f,
                cx = 479.8f,
                cy = 269.7f,
                width = 960,
                height = 540,
            ),
            jpegBase64 = Base64.getEncoder().encodeToString(jpeg),
            metricPoints = listOf(
                floatArrayOf(100f, 120f, 1f, 2f, 3f),
                floatArrayOf(500f, 220f, -4f, 0.5f, 8f),
            ),
            sensors = SensorSnapshot(
                elapsedRealtimeNs = 555_000_111L,
                headingDeg = 173.25f,
                pitchDeg = -7.5f,
                rollDeg = 2.75f,
                orientationQuality = 0.82f,
                latitudeDeg = 48.998765,
                longitudeDeg = 21.239876,
                altitudeM = 274.6,
                horizontalAccuracyM = 3.25f,
                verticalAccuracyM = 4.75f,
                pressureHpa = 1004.2f,
                gyroRadS = floatArrayOf(0.11f, -0.22f, 0.33f),
            ),
        )

        val result = (roundTrip(WireMessage.Frame(original)) as WireMessage.Frame).frame
        assertEquals(original.timestampNs, result.timestampNs)
        original.pose.t.indices.forEach { assertEquals(original.pose.t[it], result.pose.t[it], 1e-6f) }
        original.pose.q.indices.forEach { assertEquals(original.pose.q[it], result.pose.q[it], 1e-6f) }
        assertEquals(original.intrinsics.fx, result.intrinsics.fx, 1e-6f)
        assertEquals(original.intrinsics.fy, result.intrinsics.fy, 1e-6f)
        assertEquals(original.intrinsics.cx, result.intrinsics.cx, 1e-6f)
        assertEquals(original.intrinsics.cy, result.intrinsics.cy, 1e-6f)
        assertEquals(original.intrinsics.width, result.intrinsics.width)
        assertEquals(original.intrinsics.height, result.intrinsics.height)
        assertEquals(jpeg.toList(), Base64.getDecoder().decode(result.jpegBase64).toList())
        assertEquals(2, result.metricPoints.size)
        for (pointIndex in original.metricPoints.indices) {
            for (valueIndex in 0 until 5) {
                assertEquals(
                    original.metricPoints[pointIndex][valueIndex],
                    result.metricPoints[pointIndex][valueIndex],
                    1e-6f,
                )
            }
        }

        val expected = original.sensors
        val actual = result.sensors
        assertEquals(expected.elapsedRealtimeNs, actual.elapsedRealtimeNs)
        assertEquals(expected.headingDeg, actual.headingDeg, 1e-6f)
        assertEquals(expected.pitchDeg, actual.pitchDeg, 1e-6f)
        assertEquals(expected.rollDeg, actual.rollDeg, 1e-6f)
        assertEquals(expected.orientationQuality, actual.orientationQuality, 1e-6f)
        assertEquals(expected.latitudeDeg, actual.latitudeDeg, 1e-9)
        assertEquals(expected.longitudeDeg, actual.longitudeDeg, 1e-9)
        assertEquals(expected.altitudeM, actual.altitudeM, 1e-9)
        assertEquals(expected.horizontalAccuracyM, actual.horizontalAccuracyM, 1e-6f)
        assertEquals(expected.verticalAccuracyM, actual.verticalAccuracyM, 1e-6f)
        assertEquals(expected.pressureHpa, actual.pressureHpa, 1e-6f)
        repeat(3) { assertEquals(expected.gyroRadS[it], actual.gyroRadS[it], 1e-6f) }
    }

    @Test
    fun poiRoundTripPreservesMetricPoint() {
        val result = roundTrip(
            WireMessage.Poi(42L, "A", floatArrayOf(1.25f, -2.5f, 9.75f), 123456L),
        ) as WireMessage.Poi
        assertEquals(42L, result.id)
        assertEquals("A", result.owner)
        assertEquals(1.25f, result.pointWorld[0], 1e-6f)
        assertEquals(-2.5f, result.pointWorld[1], 1e-6f)
        assertEquals(9.75f, result.pointWorld[2], 1e-6f)
        assertEquals(123456L, result.createdAtMs)
    }

    @Test
    fun qualityAndRangeRoundTrip() {
        val q = roundTrip(WireMessage.Quality(0.73f, 4, true)) as WireMessage.Quality
        assertEquals(0.73f, q.confidence, 1e-6f)
        assertEquals(4, q.stableCount)
        assertTrue(q.ready)

        val r = roundTrip(WireMessage.Range(3.42f, 0.18f, 7)) as WireMessage.Range
        assertEquals(3.42f, r.distanceM, 1e-6f)
        assertEquals(0.18f, r.stdDevM, 1e-6f)
        assertEquals(7, r.samples)
    }

    @Test
    fun alignmentResetRoundTrip() {
        val result = roundTrip(WireMessage.ResetAlignment("camera 2")) as WireMessage.ResetAlignment
        assertEquals("camera 2", result.reason)
    }

    @Test
    fun clearPoiRoundTrip() {
        assertTrue(roundTrip(WireMessage.ClearPoi) === WireMessage.ClearPoi)
    }

    @Test
    fun incompatibleProtocolVersionIsRejected() {
        val out = ByteArrayOutputStream()
        PeerProtocol.write(out, WireMessage.Hello("A", "B"))
        val bytes = out.toByteArray()
        bytes[4] = 99.toByte()
        var rejected = false
        try {
            PeerProtocol.read(ByteArrayInputStream(bytes))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(bytes.isEmpty())
    }
}
