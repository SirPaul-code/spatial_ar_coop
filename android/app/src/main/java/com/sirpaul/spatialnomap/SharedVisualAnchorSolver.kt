package com.sirpaul.spatialnomap

import java.util.Random
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Robust 3D->3D rigid alignment used as the fast shared-visual-anchor path.
 *
 * Every correspondence is the same visual feature observed by both phones with an
 * independently measured metric world point from ARCore depth/point-cloud support.
 * Three non-collinear pairs are enough to define a rigid frame; RANSAC rejects bad
 * visual/depth associations and a Horn quaternion fit refines all consensus points.
 */
object SharedVisualAnchorSolver {
    data class Fit(
        val transformLocalFromRemote: DoubleArray,
        val inlierIndices: IntArray,
        val medianResidualM: Double,
        val p90ResidualM: Double,
    )

    private data class Pair3(
        val originalIndex: Int,
        val source: DoubleArray,
        val target: DoubleArray,
    )

    fun solve(
        remoteWorld: List<FloatArray>,
        localWorld: List<FloatArray>,
    ): Fit? {
        val count = minOf(remoteWorld.size, localWorld.size)
        val pairs = ArrayList<Pair3>(count)
        for (i in 0 until count) {
            val a = remoteWorld[i]
            val b = localWorld[i]
            if (a.size < 3 || b.size < 3) continue
            if (!a.take(3).all { it.isFinite() } || !b.take(3).all { it.isFinite() }) continue
            pairs += Pair3(
                originalIndex = i,
                source = doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble()),
                target = doubleArrayOf(b[0].toDouble(), b[1].toDouble(), b[2].toDouble()),
            )
            if (pairs.size >= MAX_INPUT_PAIRS) break
        }
        if (pairs.size < MIN_INPUT_PAIRS) return null

        var bestInliers = IntArray(0)
        var bestMedian = Double.POSITIVE_INFINITY
        val n = pairs.size

        fun consider(a: Int, b: Int, c: Int) {
            if (a == b || a == c || b == c) return
            val sample = listOf(pairs[a], pairs[b], pairs[c])
            if (!nonDegenerate(sample)) return
            val candidate = fitRigid(sample) ?: return
            val inliers = residualInliers(candidate, pairs, RANSAC_RESIDUAL_M)
            if (inliers.size < 3) return
            val median = medianResidual(candidate, pairs, inliers)
            if (inliers.size > bestInliers.size ||
                (inliers.size == bestInliers.size && median < bestMedian)
            ) {
                bestInliers = inliers
                bestMedian = median
            }
        }

        if (n <= EXHAUSTIVE_MAX_PAIRS) {
            for (a in 0 until n - 2) {
                for (b in a + 1 until n - 1) {
                    for (c in b + 1 until n) consider(a, b, c)
                }
            }
        } else {
            val random = Random(RANSAC_SEED + n.toLong())
            repeat(RANSAC_TRIALS) {
                val a = random.nextInt(n)
                var b = random.nextInt(n)
                while (b == a) b = random.nextInt(n)
                var c = random.nextInt(n)
                while (c == a || c == b) c = random.nextInt(n)
                consider(a, b, c)
            }
        }

        val minimumConsensus = max(MIN_FINAL_INLIERS, ceil(n * MIN_INLIER_RATIO).toInt())
        if (bestInliers.size < minimumConsensus) return null

        val consensusPairs = bestInliers.map { pairs[it] }
        if (spanM(consensusPairs.map { it.source }) < MIN_SUPPORT_DIAMETER_M ||
            spanM(consensusPairs.map { it.target }) < MIN_SUPPORT_DIAMETER_M
        ) return null

        var refined = fitRigid(consensusPairs) ?: return null
        var refinedInliers = residualInliers(refined, pairs, RANSAC_RESIDUAL_M)
        if (refinedInliers.size < minimumConsensus) return null

        // One more refit after the first all-inlier refinement tightens depth noise.
        refined = fitRigid(refinedInliers.map { pairs[it] }) ?: return null
        refinedInliers = residualInliers(refined, pairs, RANSAC_RESIDUAL_M)
        if (refinedInliers.size < minimumConsensus) return null

        val residuals = refinedInliers
            .map { residualM(refined, pairs[it]) }
            .filter { it.isFinite() }
            .sorted()
        if (residuals.size < minimumConsensus) return null
        val median = residuals[residuals.size / 2]
        val p90 = residuals[((residuals.size - 1) * 0.90).toInt().coerceIn(0, residuals.lastIndex)]
        if (median > MAX_FINAL_MEDIAN_M || p90 > MAX_FINAL_P90_M) return null

        return Fit(
            transformLocalFromRemote = refined,
            inlierIndices = refinedInliers.map { pairs[it].originalIndex }.toIntArray(),
            medianResidualM = median,
            p90ResidualM = p90,
        )
    }

    /** Horn absolute-orientation fit: target ~= R * source + t. */
    private fun fitRigid(pairs: List<Pair3>): DoubleArray? {
        if (pairs.size < 3) return null
        val sourceCenter = DoubleArray(3)
        val targetCenter = DoubleArray(3)
        for (pair in pairs) {
            for (k in 0..2) {
                sourceCenter[k] += pair.source[k]
                targetCenter[k] += pair.target[k]
            }
        }
        val inv = 1.0 / pairs.size
        for (k in 0..2) {
            sourceCenter[k] *= inv
            targetCenter[k] *= inv
        }

        val s = DoubleArray(9)
        for (pair in pairs) {
            val ax = pair.source[0] - sourceCenter[0]
            val ay = pair.source[1] - sourceCenter[1]
            val az = pair.source[2] - sourceCenter[2]
            val bx = pair.target[0] - targetCenter[0]
            val by = pair.target[1] - targetCenter[1]
            val bz = pair.target[2] - targetCenter[2]
            s[0] += ax * bx; s[1] += ax * by; s[2] += ax * bz
            s[3] += ay * bx; s[4] += ay * by; s[5] += ay * bz
            s[6] += az * bx; s[7] += az * by; s[8] += az * bz
        }

        val sxx = s[0]; val sxy = s[1]; val sxz = s[2]
        val syx = s[3]; val syy = s[4]; val syz = s[5]
        val szx = s[6]; val szy = s[7]; val szz = s[8]
        val n = doubleArrayOf(
            sxx + syy + szz, syz - szy, szx - sxz, sxy - syx,
            syz - szy, sxx - syy - szz, sxy + syx, szx + sxz,
            szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy,
            sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz,
        )
        val q = largestEigenvectorSymmetric4(n) ?: return null
        var w = q[0]
        var x = q[1]
        var y = q[2]
        var z = q[3]
        val qn = sqrt(w * w + x * x + y * y + z * z)
        if (!qn.isFinite() || qn < 1e-12) return null
        w /= qn; x /= qn; y /= qn; z /= qn

        val r00 = 1.0 - 2.0 * (y * y + z * z)
        val r01 = 2.0 * (x * y - z * w)
        val r02 = 2.0 * (x * z + y * w)
        val r10 = 2.0 * (x * y + z * w)
        val r11 = 1.0 - 2.0 * (x * x + z * z)
        val r12 = 2.0 * (y * z - x * w)
        val r20 = 2.0 * (x * z - y * w)
        val r21 = 2.0 * (y * z + x * w)
        val r22 = 1.0 - 2.0 * (x * x + y * y)

        val tx = targetCenter[0] - (r00 * sourceCenter[0] + r01 * sourceCenter[1] + r02 * sourceCenter[2])
        val ty = targetCenter[1] - (r10 * sourceCenter[0] + r11 * sourceCenter[1] + r12 * sourceCenter[2])
        val tz = targetCenter[2] - (r20 * sourceCenter[0] + r21 * sourceCenter[1] + r22 * sourceCenter[2])
        val out = doubleArrayOf(
            r00, r01, r02, tx,
            r10, r11, r12, ty,
            r20, r21, r22, tz,
            0.0, 0.0, 0.0, 1.0,
        )
        return out.takeIf { it.all(Double::isFinite) }
    }

    /** Jacobi eigensolver for the 4x4 symmetric Horn matrix. */
    private fun largestEigenvectorSymmetric4(input: DoubleArray): DoubleArray? {
        if (input.size != 16 || !input.all(Double::isFinite)) return null
        val a = input.copyOf()
        val v = DoubleArray(16) { i -> if (i / 4 == i % 4) 1.0 else 0.0 }

        repeat(40) {
            var p = 0
            var q = 1
            var largest = 0.0
            for (i in 0..2) for (j in i + 1..3) {
                val value = kotlin.math.abs(a[i * 4 + j])
                if (value > largest) {
                    largest = value
                    p = i
                    q = j
                }
            }
            if (largest < 1e-12) return@repeat

            val app = a[p * 4 + p]
            val aqq = a[q * 4 + q]
            val apq = a[p * 4 + q]
            val angle = 0.5 * atan2(2.0 * apq, aqq - app)
            val c = cos(angle)
            val s = sin(angle)

            for (k in 0..3) {
                if (k == p || k == q) continue
                val akp = a[k * 4 + p]
                val akq = a[k * 4 + q]
                val newKp = c * akp - s * akq
                val newKq = s * akp + c * akq
                a[k * 4 + p] = newKp
                a[p * 4 + k] = newKp
                a[k * 4 + q] = newKq
                a[q * 4 + k] = newKq
            }
            a[p * 4 + p] = c * c * app - 2.0 * s * c * apq + s * s * aqq
            a[q * 4 + q] = s * s * app + 2.0 * s * c * apq + c * c * aqq
            a[p * 4 + q] = 0.0
            a[q * 4 + p] = 0.0

            for (k in 0..3) {
                val vkp = v[k * 4 + p]
                val vkq = v[k * 4 + q]
                v[k * 4 + p] = c * vkp - s * vkq
                v[k * 4 + q] = s * vkp + c * vkq
            }
        }

        var best = 0
        for (i in 1..3) if (a[i * 4 + i] > a[best * 4 + best]) best = i
        return doubleArrayOf(v[best], v[4 + best], v[8 + best], v[12 + best])
    }

    private fun nonDegenerate(sample: List<Pair3>): Boolean {
        if (sample.size != 3) return false
        return triangleCrossMagnitude(sample[0].source, sample[1].source, sample[2].source) >= MIN_TRIANGLE_CROSS_M2 &&
            triangleCrossMagnitude(sample[0].target, sample[1].target, sample[2].target) >= MIN_TRIANGLE_CROSS_M2
    }

    private fun triangleCrossMagnitude(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    private fun residualInliers(transform: DoubleArray, pairs: List<Pair3>, thresholdM: Double): IntArray {
        val out = ArrayList<Int>()
        for (i in pairs.indices) {
            if (residualM(transform, pairs[i]) <= thresholdM) out += i
        }
        return out.toIntArray()
    }

    private fun medianResidual(transform: DoubleArray, pairs: List<Pair3>, indices: IntArray): Double {
        if (indices.isEmpty()) return Double.POSITIVE_INFINITY
        val values = indices.map { residualM(transform, pairs[it]) }.sorted()
        return values[values.size / 2]
    }

    private fun residualM(transform: DoubleArray, pair: Pair3): Double {
        val a = pair.source
        val x = transform[0] * a[0] + transform[1] * a[1] + transform[2] * a[2] + transform[3]
        val y = transform[4] * a[0] + transform[5] * a[1] + transform[6] * a[2] + transform[7]
        val z = transform[8] * a[0] + transform[9] * a[1] + transform[10] * a[2] + transform[11]
        val dx = x - pair.target[0]
        val dy = y - pair.target[1]
        val dz = z - pair.target[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun spanM(points: List<DoubleArray>): Double {
        if (points.isEmpty()) return 0.0
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = kotlin.math.min(minX, p[0]); maxX = kotlin.math.max(maxX, p[0])
            minY = kotlin.math.min(minY, p[1]); maxY = kotlin.math.max(maxY, p[1])
            minZ = kotlin.math.min(minZ, p[2]); maxZ = kotlin.math.max(maxZ, p[2])
        }
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private const val MIN_INPUT_PAIRS = 6
    private const val MIN_FINAL_INLIERS = 5
    private const val MIN_INLIER_RATIO = 0.55
    private const val MAX_INPUT_PAIRS = 48
    private const val EXHAUSTIVE_MAX_PAIRS = 13
    private const val RANSAC_TRIALS = 220
    private const val RANSAC_SEED = 0x51A7C0DEL
    private const val RANSAC_RESIDUAL_M = 0.18
    private const val MAX_FINAL_MEDIAN_M = 0.12
    private const val MAX_FINAL_P90_M = 0.24
    private const val MIN_SUPPORT_DIAMETER_M = 0.12
    private const val MIN_TRIANGLE_CROSS_M2 = 0.0025
}
