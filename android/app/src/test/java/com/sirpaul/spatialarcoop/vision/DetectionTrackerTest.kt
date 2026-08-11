package com.sirpaul.spatialarcoop.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackerTest {
    private fun bird(x: Float, z: Float, at: Long) = SpatialObservation(
        label = "bird",
        confidence = 0.85f,
        position = floatArrayOf(x, 0f, z),
        observedAtMs = at,
        uncertaintyMeters = 0.18f
    )

    @Test fun keepsDistinctStableIdsForMultipleNearbyBirds() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 10_000L
        val first = tracker.update(listOf(bird(0f, 2f, t0), bird(1.2f, 2.2f, t0)), t0)
        assertEquals(2, first.size)
        assertNotEquals(first[0].key, first[1].key)
        val initialKeys = first.map { it.key }.toSet()

        val second = tracker.update(
            listOf(bird(0.08f, 2.02f, t0 + 300), bird(1.12f, 2.18f, t0 + 300)),
            t0 + 300
        )
        assertEquals(initialKeys, second.map { it.key }.toSet())
    }

    @Test fun predictsAcrossShortDetectorDropoutThenExpires() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 20_000L
        val initial = tracker.update(listOf(bird(0f, 3f, t0)), t0).single()
        val moved = tracker.update(listOf(bird(0.5f, 3f, t0 + 500)), t0 + 500).single()
        assertEquals(initial.key, moved.key)

        val predicted = tracker.update(emptyList(), t0 + 1_200).single()
        assertEquals(initial.key, predicted.key)
        assertTrue("predicted bird should continue moving through a short missed frame", predicted.position[0] > moved.position[0])
        assertTrue(predicted.confidence < moved.confidence)

        assertTrue(tracker.update(emptyList(), t0 + 2_900).isEmpty())
    }

    @Test fun emptyFrameDoesNotMergeOrInventBirds() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 30_000L
        assertEquals(3, tracker.update(listOf(bird(0f, 2f, t0), bird(1f, 2f, t0), bird(2f, 2f, t0)), t0).size)
        assertEquals(3, tracker.update(emptyList(), t0 + 600).size)
    }

    @Test fun clearImmediatelyRemovesAllSourceTracks() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 40_000L
        assertEquals(2, tracker.update(listOf(bird(0f, 2f, t0), bird(1f, 2f, t0)), t0).size)
        tracker.clear()
        assertTrue(tracker.current(t0 + 1).isEmpty())
    }
}
