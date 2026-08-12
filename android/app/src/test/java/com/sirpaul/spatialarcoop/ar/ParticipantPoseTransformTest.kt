package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Pose
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ParticipantPoseTransformTest {
    @Test
    fun remotePoseTranslationAndCameraAxesStayInSiteFrame() {
        val pose = Pose(
            floatArrayOf(1.25f, 1.65f, -2.5f),
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val siteFromClient = PoseMath.poseToMatrix(pose)

        assertArrayEquals(
            floatArrayOf(1.25f, 1.65f, -2.5f),
            PoseMath.translationOf(siteFromClient),
            1e-5f
        )
        assertArrayEquals(
            floatArrayOf(0.35f, 0f, 0f),
            PoseMath.transformDirection(siteFromClient, floatArrayOf(0.35f, 0f, 0f)),
            1e-5f
        )
        assertArrayEquals(
            floatArrayOf(0f, 0.35f, 0f),
            PoseMath.transformDirection(siteFromClient, floatArrayOf(0f, 0.35f, 0f)),
            1e-5f
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, -0.55f),
            PoseMath.transformDirection(siteFromClient, floatArrayOf(0f, 0f, -0.55f)),
            1e-5f
        )
    }
}
