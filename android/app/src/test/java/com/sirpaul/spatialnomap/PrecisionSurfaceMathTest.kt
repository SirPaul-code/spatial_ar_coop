package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PrecisionSurfaceMathTest {
    @Test
    fun rigidBlendKeepsRotationOrthonormal() {
        val identity = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val yaw90 = doubleArrayOf(
            0.0, 0.0, 1.0, 1.0,
            0.0, 1.0, 0.0, -0.4,
            -1.0, 0.0, 0.0, 2.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val blended = RigidTransformMath.blend(identity, yaw90, 0.5)
        assertNotNull(blended)
        blended!!
        assertEquals(0.5, blended[3], 1e-9)
        assertEquals(-0.2, blended[7], 1e-9)
        assertEquals(1.0, blended[11], 1e-9)

        val c0 = doubleArrayOf(blended[0], blended[4], blended[8])
        val c1 = doubleArrayOf(blended[1], blended[5], blended[9])
        val c2 = doubleArrayOf(blended[2], blended[6], blended[10])
        fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        assertEquals(1.0, norm(c0), 1e-9)
        assertEquals(1.0, norm(c1), 1e-9)
        assertEquals(1.0, norm(c2), 1e-9)
        assertEquals(0.0, dot(c0, c1), 1e-9)
        assertEquals(0.0, dot(c0, c2), 1e-9)
        assertEquals(0.0, dot(c1, c2), 1e-9)
    }

    @Test
    fun surfaceReferenceProjectsWorldPointBackToImage() {
        val frame = CapturedFrame(
            timestampNs = 1L,
            pose = PosePacket(
                t = floatArrayOf(0f, 0f, 0f),
                q = floatArrayOf(0f, 0f, 0f, 1f),
            ),
            intrinsics = IntrinsicsPacket(
                fx = 500f,
                fy = 500f,
                cx = 320f,
                cy = 240f,
                width = 640,
                height = 480,
            ),
            jpegBase64 = "",
            metricPoints = emptyList(),
        )
        val pixel = SurfaceTargetResolver.pixelForWorld(frame, floatArrayOf(0.4f, -0.2f, -2f))
        assertNotNull(pixel)
        pixel!!
        assertEquals(420f, pixel[0], 1e-4f)
        assertEquals(290f, pixel[1], 1e-4f)
        assertTrue(pixel[0] in 0f..640f && pixel[1] in 0f..480f)
    }
}
