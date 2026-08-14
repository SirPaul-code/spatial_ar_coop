package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.PoseJoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseTrackingTest {
    private fun joints(shoulderY: Float = 1.42f) = listOf(
        PoseJoint(0, floatArrayOf(0f, 1.67f, 0f), 0.95f),
        PoseJoint(11, floatArrayOf(-0.22f, shoulderY, 0f), 0.92f),
        PoseJoint(12, floatArrayOf(0.22f, shoulderY, 0f), 0.92f),
        PoseJoint(23, floatArrayOf(-0.14f, 0.92f, 0f), 0.94f),
        PoseJoint(24, floatArrayOf(0.14f, 0.92f, 0f), 0.94f),
        PoseJoint(27, floatArrayOf(-0.12f, 0.04f, 0f), 0.90f),
        PoseJoint(28, floatArrayOf(0.12f, 0.04f, 0f), 0.90f),
        PoseJoint(31, floatArrayOf(-0.13f, 0.01f, -0.10f), 0.88f),
        PoseJoint(32, floatArrayOf(0.13f, 0.01f, -0.10f), 0.88f)
    )

    private fun person(at: Long, x: Float, pose: List<PoseJoint>) = SpatialObservation(
        label = "person",
        confidence = 0.91f,
        position = floatArrayOf(x, 0f, 4f),
        observedAtMs = at,
        uncertaintyMeters = 0.20f,
        associationKey = "d-person",
        requiredHits = 2,
        poseJoints = pose
    )

    @Test fun confirmedPersonPublishesSmoothedPoseAndHoldsItAcrossShortPoseDropouts() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 100_000L

        assertTrue(tracker.update(listOf(person(t0, 0f, joints(1.40f))), t0).isEmpty())
        val confirmed = tracker.update(listOf(person(t0 + 120, 0.02f, joints(1.50f))), t0 + 120).single()
        assertEquals("person", confirmed.label)
        assertTrue(confirmed.poseJoints.size >= 8)
        val shoulder = confirmed.poseJoints.first { it.index == 11 }
        assertTrue("pose smoothing must stay between the two observations", shoulder.offsetMeters[1] in 1.40f..1.50f)

        val missingPose = tracker.update(
            listOf(person(t0 + 360, 0.04f, emptyList())),
            t0 + 360
        ).single()
        assertTrue("brief pose miss should retain the shared stick figure", missingPose.poseJoints.isNotEmpty())

        val stillHeld = tracker.current(t0 + 900).single()
        assertTrue("articulation should survive a short landmark dropout", stillHeld.poseJoints.isNotEmpty())

        val afterPoseHold = tracker.current(t0 + 1_640).single()
        assertTrue("stale articulation should eventually fall back to the person volume", afterPoseHold.poseJoints.isEmpty())
    }

    @Test fun nonPersonTrackNeverPublishesPosePayload() {
        val tracker = DetectionTracker("phone-a")
        val t0 = 200_000L
        fun car(at: Long) = SpatialObservation(
            label = "car",
            confidence = 0.9f,
            position = floatArrayOf(0f, 0f, 5f),
            observedAtMs = at,
            uncertaintyMeters = 0.2f,
            associationKey = "d-car",
            requiredHits = 2,
            poseJoints = joints()
        )
        assertTrue(tracker.update(listOf(car(t0)), t0).isEmpty())
        val track = tracker.update(listOf(car(t0 + 120)), t0 + 120).single()
        assertTrue(track.poseJoints.isEmpty())
    }
}
