package com.sirpaul.spatialnomap

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rigid SE(3) interpolation; never linearly blends rotation-matrix elements. */
object RigidTransformMath {
    fun blend(a: DoubleArray, b: DoubleArray, alpha: Double): DoubleArray? {
        if (a.size < 16 || b.size < 16 || a.take(16).any { !it.isFinite() } || b.take(16).any { !it.isFinite() }) {
            return null
        }
        val q0 = quaternion(a) ?: return null
        val q1 = quaternion(b) ?: return null
        val q = slerp(q0, q1, alpha.coerceIn(0.0, 1.0)) ?: return null
        val r = rotation(q)
        val k = alpha.coerceIn(0.0, 1.0)
        return doubleArrayOf(
            r[0], r[1], r[2], a[3] * (1.0 - k) + b[3] * k,
            r[3], r[4], r[5], a[7] * (1.0 - k) + b[7] * k,
            r[6], r[7], r[8], a[11] * (1.0 - k) + b[11] * k,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    private fun quaternion(m: DoubleArray): DoubleArray? {
        val q = DoubleArray(4)
        val trace = m[0] + m[5] + m[10]
        if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            q[3] = 0.25 * s
            q[0] = (m[9] - m[6]) / s
            q[1] = (m[2] - m[8]) / s
            q[2] = (m[4] - m[1]) / s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1.0 + m[0] - m[5] - m[10]) * 2.0
            q[3] = (m[9] - m[6]) / s
            q[0] = 0.25 * s
            q[1] = (m[1] + m[4]) / s
            q[2] = (m[2] + m[8]) / s
        } else if (m[5] > m[10]) {
            val s = sqrt(1.0 + m[5] - m[0] - m[10]) * 2.0
            q[3] = (m[2] - m[8]) / s
            q[0] = (m[1] + m[4]) / s
            q[1] = 0.25 * s
            q[2] = (m[6] + m[9]) / s
        } else {
            val s = sqrt(1.0 + m[10] - m[0] - m[5]) * 2.0
            q[3] = (m[4] - m[1]) / s
            q[0] = (m[2] + m[8]) / s
            q[1] = (m[6] + m[9]) / s
            q[2] = 0.25 * s
        }
        return normalize(q)
    }

    private fun slerp(a: DoubleArray, sourceB: DoubleArray, alpha: Double): DoubleArray? {
        val b = sourceB.copyOf()
        var dot = a.indices.sumOf { a[it] * b[it] }
        if (dot < 0.0) {
            repeat(4) { b[it] = -b[it] }
            dot = -dot
        }
        dot = dot.coerceIn(-1.0, 1.0)
        if (dot > 0.9995) {
            return normalize(DoubleArray(4) { i -> a[i] * (1.0 - alpha) + b[i] * alpha })
        }
        val theta = acos(dot)
        val denominator = sin(theta)
        if (kotlin.math.abs(denominator) < 1e-9) return a.copyOf()
        val wa = sin((1.0 - alpha) * theta) / denominator
        val wb = sin(alpha * theta) / denominator
        return normalize(DoubleArray(4) { i -> a[i] * wa + b[i] * wb })
    }

    private fun normalize(q: DoubleArray): DoubleArray? {
        val n = sqrt(q.sumOf { it * it })
        if (!n.isFinite() || n < 1e-9) return null
        return DoubleArray(4) { q[it] / n }
    }

    private fun rotation(q: DoubleArray): DoubleArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return doubleArrayOf(
            1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy),
            2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx),
            2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy),
        )
    }
}
