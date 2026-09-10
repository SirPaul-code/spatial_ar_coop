package com.sirpaul.showme

import com.sirpaul.spatialnomap.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionLifecycleTest {
    private fun packet(id: Long, time: Long) = FramePacket(id, 1,
        CapturedFrame(id, PosePacket(FloatArray(3), floatArrayOf(0f,0f,0f,1f)),
            IntrinsicsPacket(500f,500f,320f,240f,640,480), "", emptyList()),
        byteArrayOf(1), 90, time, emptyList())

    @Test fun repeatedFreezeCannotKeepAnOldFrameAliveIndefinitely() {
        var now = 1000L
        val history = FrameHistory { now }
        history.add(packet(1L, now)); assertTrue(history.pin(1L))
        now += 50_000L; assertTrue(history.pin(1L))
        now += 11_000L; assertFalse(history.pin(1L)); assertNull(history.get(1L))
    }
    @Test fun worldResetCancelsQueuedCommandsRatherThanApplyingThemToANewWorld() {
        val session = ShowMeSession(); session.begin()
        val command = session.submit(JSONObject().put("requestId", "request-1000"))
        session.resetWorld()
        assertTrue(command.isDone)
        assertEquals("WORLD_CHANGED", command.get().getString("code"))
        assertNull(session.poll())
    }
    @Test fun endedSessionCannotBeClaimedByAHelper() {
        val session = ShowMeSession(); session.begin(); session.end()
        assertFalse(session.join("helper-1000", "Helper"))
        assertFalse(session.heartbeat("helper-1000"))
    }
    @Test fun aPinCannotSmuggleAnArbitraryMultiPointStroke() {
        val request = JSONObject().put("requestId", "request-1000").put("tool", "pin").put("color", "#8ff1c6")
            .put("points", JSONArray().put(JSONArray().put(.2).put(.2)).put(JSONArray().put(.3).put(.3)))
        assertNotNull(ShowMeSession.validateDraw(request))
    }
    @Test fun concurrentEndAndSubmitDoNotLeaveAQueueCounterOutOfSync() {
        val session = ShowMeSession(); session.begin()
        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val tasks = (0..2).map { worker ->
            pool.submit {
                start.await()
                repeat(100) {
                    if (worker == 0) { session.end(); session.begin() }
                    else { session.submit(JSONObject()); session.poll() }
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        session.end(); session.begin()
        val admitted = (0 until 32).map { session.submit(JSONObject()) }
        assertTrue(admitted.none { it.isDone })
        assertTrue(session.submit(JSONObject()).isDone)
        session.end(); assertTrue(admitted.all { it.isDone })
    }
}
