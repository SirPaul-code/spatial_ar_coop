package com.sirpaul.spatialarcoop.ar

import com.sirpaul.spatialarcoop.data.SpatialTrack
import java.util.concurrent.ConcurrentHashMap

class RemoteTrackStore {
    private val tracks = ConcurrentHashMap<String, SpatialTrack>()
    private val markerExpirations = ConcurrentHashMap<String, Long>()

    fun replaceAll(values: Collection<SpatialTrack>) {
        tracks.clear()
        markerExpirations.clear()
        update(values)
    }

    /**
     * ArActivity calls this for the local reporting source. The source phone already draws the
     * detector's image-space bounding boxes, so its own spatial tracks must not also live in the
     * remote overlay store. The values are still published to the server by ArActivity; other
     * participants receive them through update().
     */
    @Suppress("UNUSED_PARAMETER")
    fun replaceSource(sourceId: String, values: Collection<SpatialTrack>) {
        tracks.entries.removeIf { (_, track) -> track.sourceId == sourceId }
    }

    fun update(values: Collection<SpatialTrack>) {
        values.forEach { incoming ->
            tracks.compute(incoming.key) { _, existing ->
                if (existing == null || incoming.observedAtMs >= existing.observedAtMs) incoming else existing
            }
        }
    }

    fun remove(keys: Collection<String>) {
        keys.forEach { key ->
            tracks.remove(key)
            markerExpirations.remove(key)
        }
    }

    fun addMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long) {
        val now = System.currentTimeMillis()
        val key = "marker:$id"
        tracks[key] = SpatialTrack(
            key = key,
            id = id,
            sourceId = "marker",
            label = label,
            confidence = 1f,
            position = position.copyOf(),
            velocity = floatArrayOf(0f, 0f, 0f),
            uncertaintyMeters = 0.15f,
            observedAtMs = now,
            serverReceivedAtMs = now
        )
        markerExpirations[key] = expiresAtMs
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        tracks.entries.removeIf { (key, value) ->
            val markerExpiry = markerExpirations[key]
            val expired = markerExpiry?.let { nowMs >= it }
                ?: (nowMs - value.serverReceivedAtMs > REMOTE_TIMEOUT_MS)
            if (expired) markerExpirations.remove(key)
            expired
        }
        return tracks.values.map { track ->
            val ageSeconds = (
                (nowMs - track.serverReceivedAtMs).coerceIn(0L, MAX_EXTRAPOLATION_MS) / 1000f
            )
            track.copy(
                position = FloatArray(3) { index ->
                    track.position[index] + track.velocity[index] * ageSeconds
                }
            )
        }
    }

    companion object {
        private const val REMOTE_TIMEOUT_MS = 4_000L
        private const val MAX_EXTRAPOLATION_MS = 700L
    }
}
