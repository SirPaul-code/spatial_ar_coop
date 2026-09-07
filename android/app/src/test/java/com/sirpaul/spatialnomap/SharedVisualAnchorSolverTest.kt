package com.sirpaul.spatialnomap

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SharedVisualAnchorSolverTest {
    @Test
    fun recoversRigidSharedFrameAndRejectsOutliers() {
        val source = listOf(
            floatArrayOf(-0.8f, -0.3f, 1.2f),
            floatArrayOf(-0.2f, 0.5f, 1.6f),
            floatArrayOf(0.4f, -0.4f, 2.1f),
            floatArrayOf(0.9f, 0.2f, 1.4f),
            floatArrayOf(-0.5f, 0.8f, 2.4f),
            floatArrayOf(0.2f, 1.0f, 1.1f),
            floatArrayOf(1.1f, -0.7f, 2.7f),
            floatArrayOf(-1.0f, 0.1f, 2.9f),
            floatArrayOf(0.7f, 0.9f, 3.1f),
            floatArrayOf(-0.1f, -0.9f, 3.4f),
            floatArrayOf(1.4f, 0.6f, 2.0f),
            floatArrayOf(-1.2f, -0.6f, 1.8f),
        )

        val angle = Math.toRadians(31.0)
        val c = cos(angle)
        val s = sin(angle)
        val tx = 1.35
        val ty = -0.24
        val tz = 0.72

        fun expected(p: FloatArray): FloatArray {
            val x = c * p[0] + s * p[2] + tx
            val y = p[1] + ty
            val z = -s * p[0] + c * p[2] + tz
            return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
        }

        val target = source.map(::expected).toMutableList()
        // Two deliberately wrong visual/depth associations.
        target[2] = floatArrayOf(5.0f, -3.0f, 7.0f)
        target[9] = floatArrayOf(-4.0f, 2.5f, 0.2f)

        val fit = SharedVisualAnchorSolver.solve(source, target)
        assertNotNull(fit)
        fit!!
        assertTrue("expected robust consensus", fit.inlierIndices.size >= 9)
        assertTrue("metric residual should stay centimetric", fit.medianResidualM < 0.02)

        for (i in source.indices) {
            if (i == 2 || i == 9) continue
            val actual = AlignmentEngine.transformPoint(fit.transformLocalFromRemote, source[i])
            val wanted = expected(source[i])
            val dx = actual[0] - wanted[0]
            val dy = actual[1] - wanted[1]
            val dz = actual[2] - wanted[2]
            val error = sqrt(dx * dx + dy * dy + dz * dz)
            assertTrue("point $i error=$error", error < 0.02)
        }
    }
}
