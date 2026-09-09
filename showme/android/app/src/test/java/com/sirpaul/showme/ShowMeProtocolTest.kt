package com.sirpaul.showme
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ShowMeProtocolTest {
    private fun draw()=JSONObject().put("requestId","abcdefgh1234").put("frameId","17").put("tool","pin").put("color","mint").put("points",JSONArray("[[0.5,0.25]]"))
    @Test fun commandIsBoundedAndValid() { val d=SessionPolicy.parseDraw(draw()); assertEquals(17L,d.frameId); assertEquals(.5f,d.points[0].x,0f) }
    @Test(expected=IllegalArgumentException::class) fun outOfImageCoordinatesFailClosed() { SessionPolicy.parseDraw(draw().put("points",JSONArray("[[1.01,0.5]]"))) }
    @Test(expected=IllegalArgumentException::class) fun incompleteArrowFailsClosed() { SessionPolicy.parseDraw(draw().put("tool","arrow")) }
    @Test fun duplicateRequestIsNotExecutedTwice() { val ledger=RequestLedger(); assertTrue(ledger.begin("a")); assertFalse(ledger.begin("a")); ledger.finish("a","ok"); assertEquals("ok",ledger.reply("a")) }
    @Test fun tokensAreRandomAndConstantLength() { val a=SessionPolicy.token(); val b=SessionPolicy.token(); assertEquals(32,a.length); assertNotEquals(a,b); assertTrue(SessionPolicy.tokenMatches(a,a)); assertFalse(SessionPolicy.tokenMatches(a,b)) }
    @Test(expected=IOException::class) fun unmaskedWebsocketFrameRejected() { WebSocketIO.readClient(ByteArrayInputStream(byteArrayOf(0x81.toByte(),0))) }
    @Test(expected=IOException::class) fun giantPayloadRejectedBeforeAllocation() { val out=ByteArrayOutputStream(); DataOutputStream(out).apply { writeByte(0x81); writeByte(0xff); writeLong(100_000_000L) }; WebSocketIO.readClient(ByteArrayInputStream(out.toByteArray())) }
    @Test fun maskedTextDecoded() { val input=byteArrayOf(0x81.toByte(),0x82.toByte(),1,2,3,4,('o'.code xor 1).toByte(),('k'.code xor 2).toByte()); val message=WebSocketIO.readClient(ByteArrayInputStream(input))!!; assertEquals(1,message.first); assertEquals("ok",String(message.second)) }
    @Test fun headerIsBounded() { try { WebSocketIO.readHeader(ByteArrayInputStream(ByteArray(17000) { 65 })); fail("Should reject") } catch(_:IOException) {} }
}
