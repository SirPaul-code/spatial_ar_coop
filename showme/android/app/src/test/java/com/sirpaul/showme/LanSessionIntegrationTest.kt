package com.sirpaul.showme

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real loopback sockets exercise the embedded server, not a mocked transport. */
class LanSessionIntegrationTest {
    private var server:LanSessionServer?=null
    private val decision=AtomicReference<((Boolean)->Unit)?>(null)
    private val requested=CountDownLatch(1)
    private fun start():LanSessionServer {
        val callbacks=object:LanSessionServer.Callbacks {
            override fun onJoin(name:String,decide:(Boolean)->Unit) { decision.set(decide); requested.countDown() }
            override fun onPeer(connected:Boolean,name:String) {}
            override fun onCommand(message:JSONObject) {}
        }
        return LanSessionServer({ _:String -> "ShowMe asset".toByteArray() },callbacks).also { server=it; it.start() }
    }
    @After fun stop() { server?.close() }
    private fun connect(s:LanSessionServer,token:String=s.link("127.0.0.1").substringAfter("token="),origin:String="http://127.0.0.1:${s.port}"):Socket {
        val socket=Socket("127.0.0.1",s.port); socket.soTimeout=2500
        val key=Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        socket.getOutputStream().write(("GET /ws?token=$token&client=testclient123&name=Helper HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nOrigin: $origin\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: $key\r\n\r\n").toByteArray())
        socket.getOutputStream().flush()
        return socket
    }
    private fun send(socket:Socket,text:String) {
        val bytes=text.toByteArray(); val out=DataOutputStream(socket.getOutputStream()); out.writeByte(0x81)
        if(bytes.size<126) out.writeByte(0x80 or bytes.size) else { out.writeByte(0xfe); out.writeShort(bytes.size) }
        val mask=byteArrayOf(1,2,3,4); out.write(mask)
        out.write(ByteArray(bytes.size) { (bytes[it].toInt() xor mask[it%4].toInt()).toByte() }); out.flush()
    }
    private fun receive(socket:Socket):Pair<Int,ByteArray> {
        val input=DataInputStream(socket.getInputStream()); val op=input.readUnsignedByte() and 15
        var length=input.readUnsignedByte().toLong(); assertTrue(length<128)
        if(length==126L) length=input.readUnsignedShort().toLong() else if(length==127L) length=input.readLong()
        require(length in 0..1_000_000)
        return op to ByteArray(length.toInt()).also { input.readFully(it) }
    }
    @Test fun rejectsInvalidTokenAndForeignOrigin() {
        val s=start()
        connect(s,token="wrong").use { assertTrue(WebSocketIO.readHeader(it.getInputStream()).startsWith("HTTP/1.1 403")) }
        connect(s,origin="http://untrusted.invalid").use { assertTrue(WebSocketIO.readHeader(it.getInputStream()).startsWith("HTTP/1.1 403")) }
        assertFalse(s.hasHelper)
    }
    @Test fun noCameraBeforeApprovalThenExactBinaryFrame() {
        val s=start(); val bytes=byteArrayOf(4,5,6,7)
        s.latest=LanSessionServer.Packet(17,bytes); s.paused=false
        connect(s).use { socket ->
            assertTrue(WebSocketIO.readHeader(socket.getInputStream()).startsWith("HTTP/1.1 101"))
            assertEquals("waiting",JSONObject(String(receive(socket).second)).getString("type"))
            assertTrue(requested.await(2,TimeUnit.SECONDS)); assertFalse(s.hasHelper)
            send(socket,"{\"type\":\"pull\"}")
            socket.soTimeout=150
            try { receive(socket); fail("Camera data must not be sent before approval") } catch(_:SocketTimeoutException) {}
            socket.soTimeout=2500; decision.get()!!.invoke(true)
            assertEquals("ready",JSONObject(String(receive(socket).second)).getString("type"))
            send(socket,"{\"type\":\"pull\"}")
            val packet=receive(socket); assertEquals(2,packet.first); assertArrayEquals(bytes,packet.second)
            s.paused=true; s.pauseReason="Owner paused"
            send(socket,"{\"type\":\"pull\"}")
            assertEquals("paused",JSONObject(String(receive(socket).second)).getString("type"))
        }
    }
    @Test fun servesOnlyWhitelistedAssetsAndNeverCachesInvitations() {
        val s=start()
        Socket("127.0.0.1",s.port).use { socket ->
            socket.soTimeout=2500; socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val response=socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(response.startsWith("HTTP/1.1 200")); assertTrue(response.contains("Cache-Control: no-store")); assertTrue(response.endsWith("ShowMe asset"))
        }
        Socket("127.0.0.1",s.port).use { socket ->
            socket.soTimeout=2500; socket.getOutputStream().write("GET /../secret HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            assertTrue(WebSocketIO.readHeader(socket.getInputStream()).startsWith("HTTP/1.1 404"))
        }
    }
}
