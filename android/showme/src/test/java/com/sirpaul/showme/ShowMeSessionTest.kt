package com.sirpaul.showme

import com.sirpaul.spatialnomap.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShowMeSessionTest {
    private fun packet(id: Long,time: Long)=FramePacket(id,1,
        CapturedFrame(id,PosePacket(FloatArray(3),floatArrayOf(0f,0f,0f,1f)),IntrinsicsPacket(500f,500f,320f,240f,640,480),"",emptyList()),
        byteArrayOf(1),90,time,emptyList())
    @Test fun frozenFrameSurvivesRingEvictionButExpiresAfterAMinute() {
        var now=1000L;val frames=FrameHistory{now}
        frames.add(packet(1,now));assertTrue(frames.pin(1))
        for(i in 2L..60L)frames.add(packet(i,now))
        now+=9000L
        assertNotNull(frames.get(1));assertNull(frames.get(60))
        now+=52000L
        assertNull(frames.get(1))
    }
    @Test fun resumingLiveRemovesTheFrozenFrameLease() {
        var now=1000L;val frames=FrameHistory{now}
        frames.add(packet(1,now));frames.pin(1);now+=9000L
        assertNotNull(frames.get(1));frames.unpin();assertNull(frames.get(1))
    }
    @Test fun sessionTokensRotateAndRevokeOnEnd() {
        var now=1000L;val state=ShowMeSession{now}
        state.begin();val token=state.token
        assertTrue(token.length>=32);assertTrue(state.authorized(token));assertFalse(state.authorized(token+"x"))
        state.end();assertFalse(state.authorized(token))
        state.begin();assertNotEquals(token,state.token)
        val fresh=state.token;now+=3_600_001L;assertFalse(state.authorized(fresh))
    }
    @Test fun onlyOneHelperCanControlTheSession() {
        var now=1000L;val state=ShowMeSession{now};state.begin()
        assertTrue(state.join("helper0001","Alice"));assertFalse(state.join("helper0002","Bob"))
        assertFalse(state.heartbeat("helper0002"));assertTrue(state.heartbeat("helper0001"))
        now+=16000L;assertTrue(state.join("helper0002","Bob"));assertFalse(state.heartbeat("helper0001"))
    }
    @Test fun clearingHistoryInvalidatesCommandsFromThePreviousArWorld() {
        val state=ShowMeSession();state.begin();val epoch=state.epoch
        state.frames.add(packet(1,monotonicMs()));state.resetWorld()
        assertTrue(state.epoch>epoch);assertNull(state.frames.get(1))
    }
    @Test fun drawValidationRejectsUnboundedCoordinatesAndUnknownTools() {
        fun request()=JSONObject().put("requestId","test-request-0001").put("tool","pin").put("color","#8ff1c6")
            .put("points",JSONArray().put(JSONArray().put(.4).put(.5)))
        assertNull(ShowMeSession.validateDraw(request()))
        assertNotNull(ShowMeSession.validateDraw(request().put("tool","execute")))
        assertNotNull(ShowMeSession.validateDraw(request().put("color","red")))
        assertNotNull(ShowMeSession.validateDraw(request().put("label","a".repeat(65))))
        assertNotNull(ShowMeSession.validateDraw(request().put("points",JSONArray().put(JSONArray().put(2).put(0)))))
        assertNotNull(ShowMeSession.validateDraw(request().put("points",JSONArray())))
    }
    @Test fun endingSessionRejectsPendingCommandsAndRequestIdsAreRemembered() {
        val state=ShowMeSession();state.begin()
        val future=state.submit(JSONObject().put("requestId","request0001"))
        state.end();assertTrue(future.isDone);assertFalse(future.get().getBoolean("ok"))
        state.begin();val response=JSONObject().put("ok",true).put("id","pinned1234")
        state.remember("same-request",response)
        assertEquals("pinned1234",state.previous("same-request")!!.getString("id"))
    }
    @Test fun commandQueueHasABoundedBacklog() {
        val state=ShowMeSession();state.begin()
        repeat(32){state.submit(JSONObject())}
        val rejected=state.submit(JSONObject())
        assertTrue(rejected.isDone);assertFalse(rejected.get().getBoolean("ok"))
        state.poll();assertFalse(state.submit(JSONObject()).isDone)
    }
}
