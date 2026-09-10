package com.sirpaul.showme

import com.sirpaul.spatialnomap.CapturedFrame
import com.sirpaul.spatialnomap.PosePacket
import kotlin.math.*

/** Pure geometry. Viewer coordinates refer to the exact rotated JPEG, never the current preview. */
object ShowMeGeometry {
    fun rawToUpright(x: Float, y: Float, rotation: Int): FloatArray = when (rotation) {
        90 -> floatArrayOf(1f - y, x)
        180 -> floatArrayOf(1f - x, 1f - y)
        270 -> floatArrayOf(y, 1f - x)
        else -> floatArrayOf(x, y)
    }
    fun uprightToRaw(x: Float, y: Float, rotation: Int): FloatArray = when (rotation) {
        90 -> floatArrayOf(y, 1f - x)
        180 -> floatArrayOf(1f - x, 1f - y)
        270 -> floatArrayOf(1f - y, x)
        else -> floatArrayOf(x, y)
    }
    fun rotate(q: FloatArray, p: FloatArray, inverse: Boolean = false): FloatArray {
        require(q.size == 4 && p.size >= 3)
        val n = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
        require(n.isFinite() && n > 1e-6f)
        val s = if (inverse) -1f else 1f
        val x = q[0] / n * s; val y = q[1] / n * s; val z = q[2] / n * s; val w = q[3] / n
        val tx = 2f * (y * p[2] - z * p[1])
        val ty = 2f * (z * p[0] - x * p[2])
        val tz = 2f * (x * p[1] - y * p[0])
        return floatArrayOf(p[0] + w * tx + y * tz - z * ty,
            p[1] + w * ty + z * tx - x * tz, p[2] + w * tz + x * ty - y * tx)
    }
    fun toWorld(pose: PosePacket, p: FloatArray): FloatArray = rotate(pose.q, p).also {
        repeat(3) { i -> it[i] += pose.t[i] }
    }
    fun toCamera(pose: PosePacket, p: FloatArray): FloatArray =
        rotate(pose.q, FloatArray(3) { p[it] - pose.t[it] }, true)
    fun distance(a: FloatArray, b: FloatArray): Float = sqrt((0..2).sumOf {
        val d = (a[it] - b[it]).toDouble(); d * d
    }).toFloat()
    fun project(frame: CapturedFrame, point: FloatArray): FloatArray? {
        val p = toCamera(frame.pose, point)
        val depth = -p[2]
        if (!depth.isFinite() || depth < 0.05f) return null
        val k = frame.intrinsics
        return floatArrayOf(k.fx * p[0] / depth + k.cx, k.fy * -p[1] / depth + k.cy)
    }

    /**
     * Resolve a historical pixel using historical metric supports. Fit inverse depth
     * locally (a perspective-correct plane), reject edges/discontinuities and never
     * replace missing depth with an invented fixed-distance plane.
     */
    fun pointAt(frame: CapturedFrame, u: Float, v: Float): FloatArray? {
        val k = frame.intrinsics
        if (!u.isFinite() || !v.isFinite() || u !in 0f..(k.width - 1f) || v !in 0f..(k.height - 1f)) return null
        if (k.fx <= 1f || k.fy <= 1f) return null
        data class Sample(val du: Double, val dv: Double, val z: Double, val radius: Double)
        val radius = max(12.0, k.width * 0.021)
        val near = frame.metricPoints.mapNotNull { m ->
            if (m.size < 5 || m.any { !it.isFinite() }) return@mapNotNull null
            val du = (m[0] - u).toDouble(); val dv = (m[1] - v).toDouble()
            val r = du * du + dv * dv
            if (r > radius * radius) return@mapNotNull null
            val z = -toCamera(frame.pose, floatArrayOf(m[2], m[3], m[4]))[2].toDouble()
            if (z !in 0.15..8.0) null else Sample(du, dv, z, r)
        }.sortedBy { it.radius }.take(32)
        if (near.size < 4 || near.first().radius > radius * radius * 0.36) return null
        val seed = near.take(5).map { it.z }.sorted().let { it[it.size / 2] }
        val tolerance = max(0.035, seed * 0.035)
        val cluster = near.filter { abs(it.z - seed) <= tolerance }
        if (cluster.size < 4 || cluster.size < min(near.size, 10) * 0.6) return null
        // More than one competing depth directly around the clicked pixel is ambiguous.
        if (near.take(6).count { abs(it.z - seed) > tolerance * 2 } >= 2) return null
        val a = Array(3) { DoubleArray(3) }; val b = DoubleArray(3)
        for (p in cluster) {
            val row = doubleArrayOf(p.du / radius, p.dv / radius, 1.0)
            val weight = 1.0 / (1.0 + p.radius / 64.0)
            for (i in 0..2) {
                b[i] += row[i] / p.z * weight
                for (j in 0..2) a[i][j] += row[i] * row[j] * weight
            }
        }
        val fit = solve3(a, b)
        val fitted = fit?.let { if (it[2] > 0.0) 1.0 / it[2] else Double.NaN }
        var depth = seed
        if (fitted != null && fitted.isFinite() && abs(fitted - seed) <= tolerance) {
            val errors = cluster.map { p ->
                val inv = fit[0] * p.du / radius + fit[1] * p.dv / radius + fit[2]
                if (inv <= 0.0) Double.POSITIVE_INFINITY else abs(1.0 / inv - p.z)
            }.sorted()
            if (errors[errors.size / 2] > max(0.015, seed * 0.015)) return null
            depth = fitted
        } else {
            // Degenerate fits are accepted only for a very local, flat depth patch.
            if (cluster.take(4).last().radius > 100.0 || cluster.maxOf { it.z } - cluster.minOf { it.z } > 0.025) return null
        }
        val z = depth.toFloat()
        return toWorld(frame.pose, floatArrayOf((u - k.cx) / k.fx * z, -(v - k.cy) / k.fy * z, -z))
    }
    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (i in 0..2) {
            val pivot = (i..2).maxByOrNull { abs(m[it][i]) } ?: return null
            if (abs(m[pivot][i]) < 1e-7) return null
            val temp = m[i]; m[i] = m[pivot]; m[pivot] = temp
            val scale = m[i][i]
            for (j in i..3) m[i][j] /= scale
            for (r in 0..2) if (r != i) {
                val f = m[r][i]
                for (j in i..3) m[r][j] -= f * m[i][j]
            }
        }
        return DoubleArray(3) { m[it][3] }.takeIf { it.all(Double::isFinite) }
    }
}
