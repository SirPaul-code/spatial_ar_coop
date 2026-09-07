package com.sirpaul.spatialnomap

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import kotlin.math.floor
import kotlin.math.max

/**
 * Lightweight metric world-map accumulator for demo visualization and landmarks.
 *
 * The map is a bounded voxel cloud in the receiver's current shared coordinate
 * system. It fuses both phones' metric samples after registration, so walking either
 * phone around progressively reveals a real 3D representation of the environment.
 * No image/point cloud is uploaded anywhere.
 */
object SpatialMapAccumulator {
    data class Point(val xyz: FloatArray, val remoteSeen: Boolean, val observations: Int)

    private data class Voxel(
        var x: Float,
        var y: Float,
        var z: Float,
        var count: Int,
        var remoteSeen: Boolean,
        var lastSeenMs: Long,
    )

    private const val VOXEL_M = 0.075f
    private const val MAX_VOXELS = 16_000
    private const val PERSIST_INTERVAL_MS = 5_000L
    private val voxels = LinkedHashMap<Long, Voxel>(MAX_VOXELS, 0.75f, true)
    private var lastPersistMs = 0L

    @Synchronized fun ingest(snapshot: WorldVizBus.Snapshot) {
        if (!snapshot.locked) return
        val now = snapshot.timestampMs
        for (point in snapshot.points) {
            val p = point.xyz
            if (p.size < 3 || p.take(3).any { !it.isFinite() }) continue
            val key = voxelKey(p[0], p[1], p[2])
            val existing = voxels[key]
            if (existing == null) {
                voxels[key] = Voxel(p[0], p[1], p[2], 1, point.remote, now)
            } else {
                val n = existing.count.coerceAtMost(64)
                val alpha = 1f / (n + 1f)
                existing.x += (p[0] - existing.x) * alpha
                existing.y += (p[1] - existing.y) * alpha
                existing.z += (p[2] - existing.z) * alpha
                existing.count = minOf(existing.count + 1, 65_535)
                existing.remoteSeen = existing.remoteSeen || point.remote
                existing.lastSeenMs = now
                // access-order LinkedHashMap: touch the entry so old regions are
                // evicted before recently observed geometry when at capacity.
                voxels[key] = existing
            }
        }
        while (voxels.size > MAX_VOXELS) {
            val first = voxels.entries.iterator()
            if (!first.hasNext()) break
            first.next()
            first.remove()
        }
        if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
            persist(now)
            lastPersistMs = now
        }
    }

    @Synchronized fun snapshot(limit: Int = 6000): List<Point> {
        if (voxels.isEmpty()) return emptyList()
        val values = voxels.values.toList()
        val step = max(1, values.size / limit.coerceAtLeast(1))
        val out = ArrayList<Point>(minOf(limit, values.size))
        var i = values.lastIndex
        while (i >= 0 && out.size < limit) {
            val v = values[i]
            out += Point(floatArrayOf(v.x, v.y, v.z), v.remoteSeen, v.count)
            i -= step
        }
        return out
    }

    @Synchronized fun clear() {
        voxels.clear()
        lastPersistMs = 0L
    }

    @Synchronized fun size(): Int = voxels.size

    private fun persist(nowMs: Long) {
        val session = AlignmentSessionRecorder.latestSessionPath() ?: return
        val file = File(session, "shared-world.voxels")
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file, false), 128 * 1024)).use { out ->
                out.writeInt(0x53565731) // SVW1
                out.writeFloat(VOXEL_M)
                out.writeLong(nowMs)
                out.writeInt(voxels.size)
                voxels.values.forEach { v ->
                    out.writeFloat(v.x); out.writeFloat(v.y); out.writeFloat(v.z)
                    out.writeInt(v.count)
                    out.writeBoolean(v.remoteSeen)
                }
            }
        }
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / VOXEL_M).toLong().coerceIn(-1_048_575L, 1_048_575L)
        val iy = floor(y / VOXEL_M).toLong().coerceIn(-1_048_575L, 1_048_575L)
        val iz = floor(z / VOXEL_M).toLong().coerceIn(-1_048_575L, 1_048_575L)
        val bias = 1_048_576L
        return ((ix + bias) shl 42) xor ((iy + bias) shl 21) xor (iz + bias)
    }
}
