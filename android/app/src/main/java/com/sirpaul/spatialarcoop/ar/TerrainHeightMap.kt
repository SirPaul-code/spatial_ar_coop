package com.sirpaul.spatialarcoop.ar

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class TerrainHeightMap private constructor(
    val cellMeters: Float,
    private val cells: Map<Long, Cell>,
    val fallbackGroundY: Float?
) {
    data class HeightSample(
        val y: Float,
        val support: Int,
        val spreadMeters: Float
    )

    data class RayIntersection(
        val position: FloatArray,
        val terrain: HeightSample,
        val distanceMeters: Float
    )

    private data class Cell(
        val ix: Int,
        val iz: Int,
        val y: Float,
        val support: Int,
        val spread: Float
    )

    val cellCount: Int get() = cells.size

    val elevationRangeMeters: ClosedFloatingPointRange<Float>?
        get() {
            if (cells.isEmpty()) return null
            val ys = cells.values.map { it.y }
            return (ys.minOrNull() ?: return null)..(ys.maxOrNull() ?: return null)
        }

    fun heightAt(x: Float, z: Float): HeightSample? {
        if (!x.isFinite() || !z.isFinite() || cells.isEmpty()) return fallbackGroundY?.let { HeightSample(it, 0, 1.5f) }
        val centerX = floor(x / cellMeters).toInt()
        val centerZ = floor(z / cellMeters).toInt()
        val nearby = mutableListOf<Pair<Cell, Float>>()
        for (dz in -2..2) {
            for (dx in -2..2) {
                val cell = cells[key(centerX + dx, centerZ + dz)] ?: continue
                val cx = (cell.ix + 0.5f) * cellMeters
                val cz = (cell.iz + 0.5f) * cellMeters
                val distance = sqrt((cx - x) * (cx - x) + (cz - z) * (cz - z))
                if (distance <= cellMeters * 2.75f) nearby += cell to distance
            }
        }
        if (nearby.isEmpty()) return fallbackGroundY?.let { HeightSample(it, 0, 1.5f) }

        val centerHeight = nearby.minByOrNull { it.second }!!.first.y
        val consistent = nearby.filter { abs(it.first.y - centerHeight) <= MAX_NEIGHBOR_STEP_METERS }
            .ifEmpty { nearby.take(1) }
        var weightedY = 0f
        var totalWeight = 0f
        var support = 0
        var spread = 0f
        for ((cell, distance) in consistent) {
            val quality = (cell.support.coerceAtMost(24) / 24f).coerceAtLeast(0.15f)
            val weight = quality / (0.20f + distance)
            weightedY += cell.y * weight
            totalWeight += weight
            support += cell.support
            spread = maxOf(spread, cell.spread)
        }
        if (totalWeight <= 0f) return null
        return HeightSample(weightedY / totalWeight, support, spread)
    }

    fun intersectRay(
        origin: FloatArray,
        direction: FloatArray,
        maxRangeMeters: Float = 50f
    ): RayIntersection? {
        if (origin.size < 3 || direction.size < 3) return null
        val normalized = PoseMath.normalize(direction)
        if (normalized.any { !it.isFinite() }) return null
        var previousDistance = 0.20f
        var previousDelta: Float? = null
        var previousTerrain: HeightSample? = null
        var best: RayIntersection? = null
        var distance = previousDistance
        while (distance <= maxRangeMeters) {
            val point = pointAt(origin, normalized, distance)
            val terrain = heightAt(point[0], point[2])
            if (terrain != null) {
                val delta = point[1] - terrain.y
                val candidate = RayIntersection(floatArrayOf(point[0], terrain.y, point[2]), terrain, distance)
                if (best == null || abs(delta) < abs(pointAt(origin, normalized, best.distanceMeters)[1] - best.terrain.y)) {
                    best = candidate
                }
                val previous = previousDelta
                if (previous != null && previous > 0f && delta <= 0f && previousTerrain != null) {
                    var low = previousDistance
                    var high = distance
                    var bestRefined = candidate
                    repeat(8) {
                        val mid = (low + high) * 0.5f
                        val midPoint = pointAt(origin, normalized, mid)
                        val midTerrain = heightAt(midPoint[0], midPoint[2]) ?: return@repeat
                        val midDelta = midPoint[1] - midTerrain.y
                        bestRefined = RayIntersection(
                            floatArrayOf(midPoint[0], midTerrain.y, midPoint[2]),
                            midTerrain,
                            mid
                        )
                        if (midDelta > 0f) low = mid else high = mid
                    }
                    return bestRefined
                }
                previousDelta = delta
                previousTerrain = terrain
                previousDistance = distance
            }
            distance += RAY_STEP_METERS
        }
        return best?.takeIf {
            val rayY = pointAt(origin, normalized, it.distanceMeters)[1]
            abs(rayY - it.terrain.y) <= MAX_NEAR_MISS_METERS
        }
    }

    private fun pointAt(origin: FloatArray, direction: FloatArray, distance: Float): FloatArray = floatArrayOf(
        origin[0] + direction[0] * distance,
        origin[1] + direction[1] * distance,
        origin[2] + direction[2] * distance
    )

    companion object {
        private const val DEFAULT_CELL_METERS = 0.40f
        private const val MIN_POINT_CONFIDENCE = 0.20f
        private const val MIN_CELL_POINTS = 2
        private const val LOWER_QUANTILE = 0.18f
        private const val MAX_NEIGHBOR_STEP_METERS = 1.35f
        private const val RAY_STEP_METERS = 0.22f
        private const val MAX_NEAR_MISS_METERS = 0.34f

        fun fromPoints(
            points: List<FloatArray>,
            fallbackGroundY: Float?,
            cellMeters: Float = DEFAULT_CELL_METERS
        ): TerrainHeightMap? {
            if (points.isEmpty() || cellMeters <= 0f) return null
            val bins = linkedMapOf<Long, MutableList<Float>>()
            val coords = linkedMapOf<Long, Pair<Int, Int>>()
            for (point in points) {
                if (point.size < 3 || point.take(3).any { !it.isFinite() }) continue
                val confidence = point.getOrElse(3) { 1f }
                if (!confidence.isFinite() || confidence < MIN_POINT_CONFIDENCE) continue
                val y = point[1]
                if (fallbackGroundY != null && y !in (fallbackGroundY - 2.5f)..(fallbackGroundY + 5.5f)) continue
                val ix = floor(point[0] / cellMeters).toInt()
                val iz = floor(point[2] / cellMeters).toInt()
                val key = key(ix, iz)
                bins.getOrPut(key) { mutableListOf() }.add(y)
                coords[key] = ix to iz
            }
            val cells = linkedMapOf<Long, Cell>()
            for ((key, ys) in bins) {
                if (ys.size < MIN_CELL_POINTS) continue
                ys.sort()
                val quantileIndex = ((ys.lastIndex) * LOWER_QUANTILE).toInt().coerceIn(0, ys.lastIndex)
                val y = ys[quantileIndex]
                val lower = ys[(ys.lastIndex * 0.08f).toInt().coerceIn(0, ys.lastIndex)]
                val upper = ys[(ys.lastIndex * 0.38f).toInt().coerceIn(0, ys.lastIndex)]
                val (ix, iz) = coords.getValue(key)
                cells[key] = Cell(ix, iz, y, ys.size, (upper - lower).coerceAtLeast(0f))
            }
            if (cells.isEmpty()) return null
            return TerrainHeightMap(cellMeters, cells, fallbackGroundY)
        }

        private fun key(ix: Int, iz: Int): Long =
            (ix.toLong() shl 32) xor (iz.toLong() and 0xffffffffL)
    }
}
