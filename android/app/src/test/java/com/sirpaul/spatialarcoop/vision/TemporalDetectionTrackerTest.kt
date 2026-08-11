package com.sirpaul.spatialarcoop.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalDetectionTrackerTest {
    private fun candidate(
        label: String,
        confidence: Float,
        left: Float,
        top: Float = 0f,
        width: Float = 100f,
        height: Float = 100f
    ) = DetectionCandidate2D(label, confidence, left, top, left + width, top + height)

    @Test fun repeatedWeakBirdNeverSpawnsAVisibleObject() {
        val tracker = TemporalDetectionTracker(0.35f)
        val t0 = 10_000L
        repeat(6) { index ->
            assertTrue(tracker.update(listOf(candidate("bird", 0.14f, 20f + index)), t0 + index * 120L).isEmpty())
        }
    }

    @Test fun acquiredBirdSurvivesWeakMeshObservationsBeforeConfirmation() {
        val tracker = TemporalDetectionTracker(0.35f)
        val t0 = 15_000L
        val first = tracker.update(listOf(candidate("bird", 0.31f, 20f)), t0).single()
        assertFalse(first.confirmed)
        val second = tracker.update(listOf(candidate("bird", 0.15f, 23f)), t0 + 120).single()
        assertFalse(second.confirmed)
        val third = tracker.update(listOf(candidate("bird", 0.13f, 25f)), t0 + 240).single()
        assertTrue(third.confirmed)
        assertEquals(first.temporalId, third.temporalId)
    }

    @Test fun borderlineCarDoesNotCreateGhostAndRealCarNeedsRepeatedEvidence() {
        val tracker = TemporalDetectionTracker(0.35f)
        val t0 = 20_000L

        assertTrue(tracker.update(listOf(candidate("car", 0.49f, 40f, width = 180f)), t0).isEmpty())

        val first = tracker.update(listOf(candidate("car", 0.72f, 40f, width = 180f)), t0 + 120).single()
        assertFalse(first.confirmed)
        val second = tracker.update(listOf(candidate("car", 0.66f, 44f, width = 181f)), t0 + 240).single()
        assertFalse(second.confirmed)
        val confirmed = tracker.update(listOf(candidate("car", 0.38f, 46f, width = 182f)), t0 + 360).single()
        assertTrue(confirmed.confirmed)
        assertEquals(first.temporalId, confirmed.temporalId)
    }

    @Test fun nearbyBirdsKeepSeparateImageSpaceIdentities() {
        val tracker = TemporalDetectionTracker(0.35f)
        val t0 = 30_000L
        val first = tracker.update(
            listOf(candidate("bird", 0.31f, 0f, width = 55f), candidate("bird", 0.29f, 150f, width = 55f)),
            t0
        )
        assertEquals(2, first.size)
        assertNotEquals(first[0].temporalId, first[1].temporalId)

        val second = tracker.update(
            listOf(candidate("bird", 0.17f, 5f, width = 56f), candidate("bird", 0.16f, 145f, width = 56f)),
            t0 + 140
        )
        assertEquals(first.map { it.temporalId }.toSet(), second.map { it.temporalId }.toSet())
        assertTrue(second.none { it.confirmed })

        val third = tracker.update(
            listOf(candidate("bird", 0.15f, 8f, width = 56f), candidate("bird", 0.14f, 143f, width = 56f)),
            t0 + 280
        )
        assertEquals(first.map { it.temporalId }.toSet(), third.map { it.temporalId }.toSet())
        assertTrue(third.all { it.confirmed })
    }

    @Test fun shortGapReusesIdentityButLongGapStartsFresh() {
        val tracker = TemporalDetectionTracker(0.35f)
        val t0 = 40_000L
        val first = tracker.update(listOf(candidate("person", 0.80f, 100f)), t0).single()
        val afterShortGap = tracker.update(listOf(candidate("person", 0.78f, 108f)), t0 + 1_100).single()
        assertEquals(first.temporalId, afterShortGap.temporalId)

        val afterExpiry = tracker.update(listOf(candidate("person", 0.82f, 110f)), t0 + 2_500).single()
        assertNotEquals(first.temporalId, afterExpiry.temporalId)
    }
}
