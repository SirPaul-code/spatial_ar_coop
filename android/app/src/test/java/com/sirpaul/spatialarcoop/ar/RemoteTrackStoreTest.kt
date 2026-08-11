package com.sirpaul.spatialarcoop.ar

import com.sirpaul.spatialarcoop.data.SpatialTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTrackStoreTest {
    private fun track(source: String, id: String, x: Float, now: Long) = SpatialTrack(
        key = "$source:$id",
        id = id,
        sourceId = source,
        label = "bird",
        confidence = 0.8f,
        position = floatArrayOf(x, 0f, 2f),
        velocity = floatArrayOf(0f, 0f, 0f),
        uncertaintyMeters = 0.2f,
        observedAtMs = now,
        serverReceivedAtMs = now
    )

    @Test fun localSourceIsClearedWithoutTouchingRemoteParticipants() {
        val now = 100_000L
        val store = RemoteTrackStore()
        store.update(
            listOf(
                track("phone-a", "old-local", 0f, now),
                track("phone-b", "remote", 4f, now)
            )
        )

        store.replaceSource(
            "phone-a",
            listOf(track("phone-a", "one", 0f, now), track("phone-a", "two", 1f, now))
        )
        assertEquals(setOf("phone-b:remote"), store.snapshot(now).map { it.key }.toSet())

        store.replaceSource("phone-a", listOf(track("phone-a", "one", 0.2f, now + 100)))
        assertEquals(setOf("phone-b:remote"), store.snapshot(now + 100).map { it.key }.toSet())
    }
}
