package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class AlignmentEngineMathTest {
    @Test
    fun identityPoseProducesIdentityMatrix() {
        val m = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
        )
        val expected = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        expected.indices.forEach { assertEquals(expected[it], m[it], 1e-9) }
    }

    @Test
    fun poseTranslationTransformsPointInMeters() {
        val transform = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(2f, -1f, 4f), floatArrayOf(0f, 0f, 0f, 1f)),
        )
        val p = AlignmentEngine.transformPoint(transform, floatArrayOf(1f, 2f, 3f))
        assertEquals(3.0, p[0], 1e-9)
        assertEquals(1.0, p[1], 1e-9)
        assertEquals(7.0, p[2], 1e-9)
    }

    @Test
    fun matrixCompositionPreservesMetricTranslation() {
        val a = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
        )
        val b = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(0f, 2f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
        )
        val combined = AlignmentEngine.multiply4(a, b)
        val p = AlignmentEngine.transformPoint(combined, floatArrayOf(0f, 0f, 0f))
        assertEquals(1.0, p[0], 1e-9)
        assertEquals(2.0, p[1], 1e-9)
        assertEquals(0.0, p[2], 1e-9)
    }

    @Test
    fun transformDeltaReportsTranslationAndRotation() {
        val identity = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)),
        )
        val half = sqrt(0.5).toFloat()
        val rotated = AlignmentEngine.poseMatrix(
            PosePacket(floatArrayOf(0.3f, 0.4f, 0f), floatArrayOf(0f, half, 0f, half)),
        )
        val (translation, rotationDeg) = AlignmentEngine.transformDelta(identity, rotated)
        assertEquals(0.5, translation, 1e-6)
        assertEquals(90.0, rotationDeg, 1e-4)
    }
}
