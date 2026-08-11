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

    @Test fun oneFrameHypothesisIsNotPublished() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 5_000L
        assertTrue(tracker.update(listOf(bird(0f, 2f, t0)), t0).isEmpty())
        assertTrue(tracker.current(t0 + 100).isEmpty())
    }

    @Test fun keepsDistinctStableIdsForMultipleNearbyBirds() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 10_000L
        assertTrue(tracker.update(listOf(bird(0f, 2f, t0), bird(1.2f, 2.2f, t0)), t0).isEmpty())

        val confirmed = tracker.update(
            listOf(bird(0.08f, 2.02f, t0 + 180), bird(1.12f, 2.18f, t0 + 180)),
            t0 + 180
        )
        assertEquals(2, confirmed.size)
        assertNotEquals(confirmed[0].key, confirmed[1].key)
        val initialKeys = confirmed.map { it.key }.toSet()

        val third = tracker.update(
            listOf(bird(0.14f, 2.03f, t0 + 360), bird(1.06f, 2.16f, t0 + 360)),
            t0 + 360
        )
        assertEquals(initialKeys, third.map { it.key }.toSet())
    }

    @Test fun predictsAcrossShortDetectorDropoutThenExpires() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 20_000L
        assertTrue(tracker.update(listOf(bird(0f, 3f, t0)), t0).isEmpty())
        val confirmed = tracker.update(listOf(bird(0.25f, 3f, t0 + 250)), t0 + 250).single()
        val moved = tracker.update(listOf(bird(0.5f, 3f, t0 + 500)), t0 + 500).single()
        assertEquals(confirmed.key, moved.key)

        val predicted = tracker.update(emptyList(), t0 + 1_200).single()
        assertEquals(confirmed.key, predicted.key)
        assertTrue("predicted bird should continue moving through a short missed frame", predicted.position[0] > moved.position[0])
        assertTrue(predicted.confidence < moved.confidence)

        assertTrue(tracker.update(emptyList(), t0 + 1_701).isEmpty())
    }

    @Test fun emptyFrameDoesNotMergeOrInventBirds() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 30_000L
        val first = listOf(bird(0f, 2f, t0), bird(1f, 2f, t0), bird(2f, 2f, t0))
        assertTrue(tracker.update(first, t0).isEmpty())
        val confirmed = listOf(bird(0.04f, 2f, t0 + 180), bird(1.04f, 2f, t0 + 180), bird(2.04f, 2f, t0 + 180))
        assertEquals(3, tracker.update(confirmed, t0 + 180).size)
        assertEquals(3, tracker.update(emptyList(), t0 + 600).size)
    }

    @Test fun clearImmediatelyRemovesAllSourceTracks() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 40_000L
        assertTrue(tracker.update(listOf(bird(0f, 2f, t0), bird(1f, 2f, t0)), t0).isEmpty())
        assertEquals(
            2,
            tracker.update(listOf(bird(0.04f, 2f, t0 + 180), bird(1.04f, 2f, t0 + 180)), t0 + 180).size
        )
        tracker.clear()
        assertTrue(tracker.current(t0 + 181).isEmpty())
    }
}
