package com.sirpaul.spatialnomap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTrackPolicyTest {
    @Test
    fun duplicateWinnerIsDeterministicOnBothPeers() {
        val a = -2L
        val b = 42L
        val winnerAB = VehicleTrackPolicy.winnerId(a, b)
        val winnerBA = VehicleTrackPolicy.winnerId(b, a)
        assertEquals(winnerAB, winnerBA)
        assertEquals(42L, winnerAB) // unsigned 42 is lower than unsigned -2
    }

    @Test
    fun predictionCoastsButIsTimeBounded() {
        val p = floatArrayOf(1f, 2f, 3f)
        val v = floatArrayOf(2f, 0f, -1f)
        val atOneSecond = VehicleTrackPolicy.predict(p, v, 1_000L, 2_000L, 2_500L)
        val farLater = VehicleTrackPolicy.predict(p, v, 1_000L, 20_000L, 2_500L)

        assertArrayEquals(floatArrayOf(3f, 2f, 2f), atOneSecond, 0.0001f)
        assertArrayEquals(floatArrayOf(6f, 2f, 0.5f), farLater, 0.0001f)
    }

    @Test
    fun movingTrackGetsLargerAssociationGateThanStationaryTrack() {
        val stationary = VehicleTrackPolicy.associationGateM(FloatArray(3), 1_000L, 2_000L)
        val moving = VehicleTrackPolicy.associationGateM(floatArrayOf(3f, 0f, 0f), 1_000L, 2_000L)
        assertTrue(moving > stationary)
    }

    @Test
    fun physicalAssociationUsesPredictedPosition() {
        val same = VehicleTrackPolicy.samePhysicalVehicle(
            existingPosition = floatArrayOf(0f, 0f, 0f),
            existingVelocity = floatArrayOf(2f, 0f, 0f),
            existingLastSeenMs = 1_000L,
            observation = floatArrayOf(2.1f, 0f, 0f),
            nowMs = 2_000L,
            maxCoastMs = 2_500L,
        )
        val different = VehicleTrackPolicy.samePhysicalVehicle(
            existingPosition = floatArrayOf(0f, 0f, 0f),
            existingVelocity = FloatArray(3),
            existingLastSeenMs = 1_000L,
            observation = floatArrayOf(9f, 0f, 0f),
            nowMs = 2_000L,
            maxCoastMs = 2_500L,
        )
        assertTrue(same)
        assertFalse(different)
    }
}
