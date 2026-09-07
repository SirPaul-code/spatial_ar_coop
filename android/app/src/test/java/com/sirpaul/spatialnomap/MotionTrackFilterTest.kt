package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackFilterTest {
    @Test
    fun constantVelocityMeasurementProducesForwardPrediction() {
        val filter = MotionTrackFilter(alpha = 0.6f, beta = 0.25f)
        filter.update(floatArrayOf(0f, 0f, 0f), 1_000L, 0.9f)
        val second = filter.update(floatArrayOf(1f, 0f, 0f), 2_000L, 0.9f)
        val predicted = filter.predict(3_000L)!!

        assertTrue(second.position[0] in 0.5f..1.01f)
        assertTrue(second.velocity[0] > 0f)
        assertTrue(predicted.position[0] > second.position[0])
    }

    @Test
    fun resetDropsPreviousVelocity() {
        val filter = MotionTrackFilter()
        filter.update(floatArrayOf(0f, 0f, 0f), 1_000L, 1f)
        filter.update(floatArrayOf(5f, 0f, 0f), 2_000L, 1f)
        filter.reset()
        val state = filter.update(floatArrayOf(2f, 3f, 4f), 3_000L, 0.7f)

        assertEquals(2f, state.position[0], 0.0001f)
        assertEquals(0f, state.velocity[0], 0.0001f)
    }
}
