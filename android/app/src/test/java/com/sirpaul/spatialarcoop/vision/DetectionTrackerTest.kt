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

    @Test fun sameImageTargetKeepsIdAcrossLargeDepthSpike() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 50_000L
        fun person(z: Float, at: Long, box: FloatArray) = SpatialObservation("person", 0.82f, floatArrayOf(0f,0f,z), at, 0.8f, box)
        val first = tracker.update(listOf(person(4f,t0,floatArrayOf(0.30f,0.15f,0.58f,0.92f))),t0).single()
        val second = tracker.update(listOf(person(19f,t0+150,floatArrayOf(0.31f,0.16f,0.59f,0.93f))),t0+150).single()
        assertEquals(first.key, second.key)
        assertTrue(second.position[2] < 8f)
    }

    @Test fun visuallySeparatedSameClassTargetsRemainDistinct() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 60_000L
        val left = SpatialObservation("bird",0.8f,floatArrayOf(-0.5f,0f,3f),t0,0.5f,floatArrayOf(0.10f,0.45f,0.28f,0.75f))
        val right = SpatialObservation("bird",0.8f,floatArrayOf(0.5f,0f,3f),t0,0.5f,floatArrayOf(0.70f,0.45f,0.88f,0.75f))
        val first = tracker.update(listOf(left,right),t0)
        val next = tracker.update(listOf(left.copy(observedAtMs=t0+150),right.copy(observedAtMs=t0+150)),t0+150)
        assertEquals(2, next.size)
        assertEquals(first.map { it.key }.toSet(), next.map { it.key }.toSet())
    }

}
