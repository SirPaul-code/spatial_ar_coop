package com.sirpaul.spatialarcoop.ar

import com.sirpaul.spatialarcoop.data.SpatialTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTrackStoreTest {
    private fun track(
        source: String,
        id: String,
        x: Float,
        now: Long,
        velocityX: Float = 0f
    ) = SpatialTrack(
        key = "$source:$id",
        id = id,
        sourceId = source,
        label = "bird",
        confidence = 0.8f,
        position = floatArrayOf(x, 0f, 2f),
        velocity = floatArrayOf(velocityX, 0f, 0f),
        uncertaintyMeters = 0.2f,
        observedAtMs = now,
        serverReceivedAtMs = now
    )

    @Test fun replacingOneSourceDoesNotTouchOtherParticipants() {
        val now = 100_000L
        val store = RemoteTrackStore()
        store.update(listOf(track("phone-b", "remote", 4f, now)))
        store.replaceSource("phone-a", listOf(track("phone-a", "one", 0f, now), track("phone-a", "two", 1f, now)))
        assertEquals(setOf("phone-a:one", "phone-a:two", "phone-b:remote"), store.snapshot(now).map { it.key }.toSet())

        store.replaceSource("phone-a", listOf(track("phone-a", "one", 0.2f, now + 100)))
        assertEquals(setOf("phone-a:one", "phone-b:remote"), store.snapshot(now + 100).map { it.key }.toSet())
    }

    @Test fun stationaryRemoteTrackIsNeverExtrapolated() {
        val now = 200_000L
        val store = RemoteTrackStore()
        store.update(listOf(track("phone-a", "car", 3f, now, velocityX = 0f)))
        assertEquals(3f, store.snapshot(now + 1_000).single().position[0], 0.001f)
    }

    @Test fun movingRemoteTrackExtrapolationIsCappedAtQuarterSecond() {
        val now = 300_000L
        val store = RemoteTrackStore()
        store.update(listOf(track("phone-a", "person", 1f, now, velocityX = 2f)))
        // 2 m/s * 0.25 s = 0.5 m maximum receiver-side prediction.
        assertEquals(1.5f, store.snapshot(now + 1_000).single().position[0], 0.001f)
    }
}
