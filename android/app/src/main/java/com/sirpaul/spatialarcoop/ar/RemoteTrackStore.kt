package com.sirpaul.spatialarcoop.ar

import com.sirpaul.spatialarcoop.data.SpatialTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class RemoteTrackStore {
    private val tracks = ConcurrentHashMap<String, SpatialTrack>()
    private val markerExpirations = ConcurrentHashMap<String, Long>()

    fun replaceAll(values: Collection<SpatialTrack>) {
        tracks.clear()
        markerExpirations.clear()
        update(values)
    }

    fun replaceSource(sourceId: String, values: Collection<SpatialTrack>) {
        val incoming = values.associateBy { it.key }
        tracks.entries.removeIf { (_, track) -> track.sourceId == sourceId && track.key !in incoming }
        values.forEach { incomingTrack -> tracks[incomingTrack.key] = incomingTrack }
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

    /**
     * Returns a render-time snapshot with bounded motion extrapolation. The extrapolation horizon is
     * deliberately longer than the network publish cadence so a moving object does not visually
     * stop between packets, but an easing term prevents a stale high velocity from running away.
     */
    fun snapshot(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        tracks.entries.removeIf { (key, value) ->
            val markerExpiry = markerExpirations[key]
            val expired = markerExpiry?.let { nowMs >= it }
                ?: (nowMs - value.serverReceivedAtMs > REMOTE_TIMEOUT_MS)
            if (expired) markerExpirations.remove(key)
            expired
        }
        return tracks.values.map { track ->
            val speed = sqrt(track.velocity.sumOf { value -> (value * value).toDouble() }).toFloat()
            val extrapolationMs = if (speed < STATIONARY_SPEED_METERS_PER_SECOND) {
                0L
            } else {
                (nowMs - track.serverReceivedAtMs).coerceIn(0L, MAX_EXTRAPOLATION_MS)
            }
            val rawSeconds = extrapolationMs / 1000f
            val horizonFraction = (extrapolationMs.toFloat() / MAX_EXTRAPOLATION_MS).coerceIn(0f, 1f)
            val easedSeconds = rawSeconds * (1f - 0.24f * horizonFraction)
            track.copy(
                position = FloatArray(3) { index ->
                    track.position[index] + track.velocity[index] * easedSeconds
                },
                uncertaintyMeters = track.uncertaintyMeters + horizonFraction * 0.08f
            )
        }
    }

    companion object {
        private const val REMOTE_TIMEOUT_MS = 4_000L
        private const val MAX_EXTRAPOLATION_MS = 450L
        private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.20f
    }
}
