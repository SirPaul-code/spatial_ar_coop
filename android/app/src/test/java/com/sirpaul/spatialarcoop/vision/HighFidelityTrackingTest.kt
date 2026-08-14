package com.sirpaul.spatialarcoop.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighFidelityTrackingTest {
    private fun person(confidence: Float, left: Float) = DetectionCandidate2D(
        label = "person",
        confidence = confidence,
        left = left,
        top = 40f,
        right = left + 90f,
        bottom = 250f
    )

    @Test fun longRangePersonCanAcquireBelowGenericUserThresholdAfterTemporalConfirmation() {
        val tracker = TemporalDetectionTracker(userThreshold = 0.70f)
        val t0 = 10_000L
        val first = tracker.update(listOf(person(.46f, 100f)), t0).single()
        assertFalse(first.confirmed)

        val confirmed = tracker.update(listOf(person(.43f, 106f)), t0 + 120).single()
        assertTrue(confirmed.confirmed)
        assertEquals(first.temporalId, confirmed.temporalId)
    }

    @Test fun confirmedMovingPersonCoastsBrieflyThroughDetectorMissAndAdvancesPrediction() {
        val tracker = TemporalDetectionTracker(userThreshold = 0.35f)
        val t0 = 20_000L
        tracker.update(listOf(person(.82f, 100f)), t0)
        val moving = tracker.update(listOf(person(.78f, 130f)), t0 + 120).single()
        assertTrue(moving.confirmed)

        val coast = tracker.update(emptyList(), t0 + 240).single()
        assertEquals(moving.temporalId, coast.temporalId)
        assertTrue("coasting box should continue in the measured direction", coast.left > moving.left)
        assertTrue("coasting confidence must decay", coast.confidence < moving.confidence)

        assertTrue("coasting is bounded and must not invent a permanent track", tracker.update(emptyList(), t0 + 900).isEmpty())
    }
}
