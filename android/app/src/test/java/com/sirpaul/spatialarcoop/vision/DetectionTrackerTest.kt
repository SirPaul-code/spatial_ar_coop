package com.sirpaul.spatialarcoop.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
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

    private fun car(x: Float, z: Float, at: Long, associationKey: String? = null) = SpatialObservation(
        label = "car",
        confidence = 0.82f,
        position = floatArrayOf(x, 0f, z),
        observedAtMs = at,
        uncertaintyMeters = 0.28f,
        associationKey = associationKey
    )

    @Test fun oneFrameHypothesisIsNotPublished() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 5_000L
        assertTrue(tracker.update(listOf(bird(0f, 2f, t0)), t0).isEmpty())
        assertTrue(tracker.current(t0 + 100).isEmpty())
    }

    @Test fun highUncertaintyFallbackNeedsFourHitsAndCarriesPhysicalGeometry() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 7_000L
        fun fallback(at: Long, x: Float) = SpatialObservation(
            label = "car",
            confidence = 0.86f,
            position = floatArrayOf(x, 0f, 8f),
            observedAtMs = at,
            uncertaintyMeters = 1.10f,
            associationKey = "d-car",
            extentMeters = floatArrayOf(1.9f, 1.55f, 4.5f),
            yawRadians = 0.35f,
            requiredHits = 4
        )

        assertTrue(tracker.update(listOf(fallback(t0, 0f)), t0).isEmpty())
        assertTrue(tracker.update(listOf(fallback(t0 + 120, 0.03f)), t0 + 120).isEmpty())
        assertTrue(tracker.update(listOf(fallback(t0 + 240, 0.04f)), t0 + 240).isEmpty())
        val published = tracker.update(listOf(fallback(t0 + 360, 0.05f)), t0 + 360).single()
        assertEquals("car", published.label)
        assertArrayEquals(floatArrayOf(1.9f, 1.55f, 4.5f), published.extentMeters, 0.08f)
        assertEquals(0.35f, published.yawRadians, 0.08f)
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

    @Test fun predictsOnlyBrieflyAcrossDetectorDropoutThenExpires() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 20_000L
        assertTrue(tracker.update(listOf(bird(0f, 3f, t0)), t0).isEmpty())
        val confirmed = tracker.update(listOf(bird(0.25f, 3f, t0 + 250)), t0 + 250).single()
        val moved = tracker.update(listOf(bird(0.5f, 3f, t0 + 500)), t0 + 500).single()
        assertEquals(confirmed.key, moved.key)

        val predicted = tracker.update(emptyList(), t0 + 1_200).single()
        assertEquals(confirmed.key, predicted.key)
        assertTrue("bird should advance a little through a short missed interval", predicted.position[0] >= moved.position[0])
        assertTrue(predicted.confidence < moved.confidence)

        assertTrue(tracker.update(emptyList(), t0 + 2_001).isEmpty())
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

    @Test fun singleCarDepthJumpDoesNotCreateFiveGhostIds() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 40_000L
        assertTrue(tracker.update(listOf(car(0f, 7f, t0)), t0).isEmpty())
        val stable = tracker.update(listOf(car(0.08f, 7.02f, t0 + 140)), t0 + 140).single()

        val afterBadDepth = tracker.update(listOf(car(5.2f, 10.5f, t0 + 280)), t0 + 280)
        assertEquals(1, afterBadDepth.size)
        assertEquals(stable.key, afterBadDepth.single().key)
        assertTrue("rejected measurement must not teleport the car", afterBadDepth.single().position[0] < 1.5f)
    }

    @Test fun temporalIdentityKeepsOneCarTrackAcrossRepeatedHugeOutliers() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 45_000L
        assertTrue(tracker.update(listOf(car(0f, 7f, t0, "d-car")), t0).isEmpty())
        val stable = tracker.update(listOf(car(0.05f, 7.02f, t0 + 140, "d-car")), t0 + 140).single()

        repeat(4) { index ->
            val at = t0 + 280L + index * 140L
            val track = tracker.update(
                listOf(car(8f + index, 13f + index, at, "d-car")),
                at
            ).single()
            assertEquals(stable.key, track.key)
            assertTrue("bad 3D samples must not walk the marker away", track.position[0] < 1.5f)
        }
    }

    @Test fun movingCarRebasesAfterConsistentPhysicallyPlausibleMeasurements() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 47_000L
        assertTrue(tracker.update(listOf(car(0f, 7f, t0, "d-moving")), t0).isEmpty())
        val stable = tracker.update(listOf(car(0.05f, 7.01f, t0 + 140, "d-moving")), t0 + 140).single()

        val rejectedOne = tracker.update(listOf(car(8f, 7.05f, t0 + 740, "d-moving")), t0 + 740).single()
        assertEquals(stable.key, rejectedOne.key)
        assertTrue(rejectedOne.position[0] < 2f)

        val rejectedTwo = tracker.update(listOf(car(9.5f, 7.08f, t0 + 880, "d-moving")), t0 + 880).single()
        assertEquals(stable.key, rejectedTwo.key)
        assertTrue(rejectedTwo.position[0] < 3f)

        val reacquired = tracker.update(listOf(car(11f, 7.10f, t0 + 1_020, "d-moving")), t0 + 1_020).single()
        assertEquals(stable.key, reacquired.key)
        assertTrue("consistent moving-car evidence should rebase instead of freezing", reacquired.position[0] > 9f)
        assertTrue("reacquired car should carry meaningful velocity", reacquired.velocity[0] > 3f)
    }

    @Test fun movingCarYawFollowsWorldMotionAndDoesNotSpinWithViewHeuristics() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 49_000L
        assertTrue(tracker.update(listOf(car(0f, 7f, t0, "d-yaw")), t0).isEmpty())
        tracker.update(listOf(car(0.05f, 7f, t0 + 140, "d-yaw")), t0 + 140)
        tracker.update(listOf(car(0.9f, 7f, t0 + 280, "d-yaw")), t0 + 280)
        tracker.update(listOf(car(2.0f, 7f, t0 + 420, "d-yaw")), t0 + 420)
        val moving = tracker.update(listOf(car(3.3f, 7f, t0 + 560, "d-yaw")), t0 + 560).single()

        val expected = -(PI.toFloat() / 2f)
        assertTrue("car yaw should converge toward its +X motion direction", abs(moving.yawRadians - expected) < 0.65f)

        val badViewYaw = car(3.32f, 7f, t0 + 700, "d-yaw").copy(yawRadians = 2.6f)
        val afterViewChange = tracker.update(listOf(badViewYaw), t0 + 700).single()
        assertTrue("camera-side heuristic must not spin an established car", abs(afterViewChange.yawRadians - moving.yawRadians) < 0.35f)
    }

    @Test fun stationaryJitterDoesNotTurnIntoVelocityDrift() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 50_000L
        assertTrue(tracker.update(listOf(car(0f, 5f, t0)), t0).isEmpty())
        tracker.update(listOf(car(0.03f, 5.01f, t0 + 120)), t0 + 120)
        tracker.update(listOf(car(-0.02f, 4.98f, t0 + 240)), t0 + 240)
        val track = tracker.update(listOf(car(0.04f, 5.02f, t0 + 360)), t0 + 360).single()
        val speed = sqrt(track.velocity.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("stationary car velocity should be damped", speed < 0.25f)
        assertTrue("stationary car should stay close to its original site position", kotlin.math.abs(track.position[0]) < 0.15f)
    }

    @Test fun clearImmediatelyRemovesAllSourceTracks() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 60_000L
        assertTrue(tracker.update(listOf(bird(0f, 2f, t0), bird(1f, 2f, t0)), t0).isEmpty())
        assertEquals(
            2,
            tracker.update(listOf(bird(0.04f, 2f, t0 + 180), bird(1.04f, 2f, t0 + 180)), t0 + 180).size
        )
        tracker.clear()
        assertTrue(tracker.current(t0 + 181).isEmpty())
    }
}
