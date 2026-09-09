package com.sirpaul.showme

import android.content.Context
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Embedded LAN transport. No external backend; deliberately NOT an Internet endpoint. */
class LanSessionServer(private val loadAsset:(String)->ByteArray,private val callbacks:Callbacks) : AutoCloseable {
    constructor(context:Context,callbacks:Callbacks):this({ path -> context.assets.open(path).use { it.readBytes() } },callbacks)
    interface Callbacks {
        fun onJoin(name:String, decide:(Boolean)->Unit)
        fun onPeer(connected:Boolean,name:String)
        fun onCommand(message:JSONObject)
    }
    data class Packet(val id:Long,val bytes:ByteArray)
    private val token=SessionPolicy.token()
    private val listener=ServerSocket()
    private val running=AtomicBoolean(false)
    private val pool=ThreadPoolExecutor(4,4,0L,TimeUnit.SECONDS,ArrayBlockingQueue(8))
    private val outgoing=ThreadPoolExecutor(1,1,0L,TimeUnit.SECONDS,ArrayBlockingQueue(32))
    private val sockets=Collections.newSetFromMap(ConcurrentHashMap<Socket,Boolean>())
    @Volatile private var peer:Peer?=null
    @Volatile private var approvedClient=""
    @Volatile var latest:Packet?=null
    @Volatile var paused=true
    @Volatile var pauseReason="Waiting for AR tracking"
    val hasHelper:Boolean get()=peer?.approved==true
    val port:Int get()=listener.localPort
    val addresses:List<String> get()=localAddresses()
    fun link(address:String)= "http://$address:$port/#token=$token"
    private fun nowMs()=System.nanoTime()/1_000_000L

    fun start() {
        listener.reuseAddress=true; listener.bind(InetSocketAddress("0.0.0.0",0)); running.set(true)
        Thread({
            while(running.get()) {
                val socket=try { listener.accept() } catch(_:IOException) { break }
                if(!socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLoopbackAddress) { socket.close(); continue }
                sockets.add(socket)
                try { pool.execute { handle(socket) } } catch(_:RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        },"showme-listen").apply { isDaemon=true; start() }
    }

    /** All externally triggered writes, including owner approval, stay off UI/AR threads. */
    private fun enqueue(p:Peer,task:()->Unit) {
        try { outgoing.execute { if(peer===p && running.get()) runCatching(task).onFailure { runCatching { p.socket.close() } } } }
        catch(_:RejectedExecutionException) { runCatching { p.socket.close() } }
    }
    fun send(json:JSONObject) {
        val p=peer ?: return
        if(!p.approved) return
        val text=json.toString()
        enqueue(p) { p.text(text) }
    }
    fun status()=send(JSONObject().put("type",if(paused) "paused" else "live").put("message",pauseReason))
    override fun close() {
        running.set(false); latest=null; approvedClient=""
        runCatching { listener.close() }; sockets.forEach { runCatching { it.close() } }; sockets.clear()
        pool.shutdownNow(); outgoing.shutdownNow(); peer=null
    }

    private inner class Peer(val socket:Socket,val output:OutputStream,val client:String,val name:String) {
        @Volatile var approved=false
        @Synchronized fun text(value:String)=WebSocketIO.write(output,1,value.toByteArray(Charsets.UTF_8))
        @Synchronized fun binary(bytes:ByteArray)=WebSocketIO.write(output,2,bytes)
        @Synchronized fun pong(bytes:ByteArray)=WebSocketIO.write(output,10,bytes)
    }
    private fun handle(socket:Socket) {
        var connection:Peer?=null
        try {
            socket.soTimeout=15_000; socket.tcpNoDelay=true
            val input=BufferedInputStream(socket.getInputStream()); val output=BufferedOutputStream(socket.getOutputStream())
            val header=WebSocketIO.readHeader(input)
            val lines=header.split("\r\n"); val request=lines.first().split(' ')
            if(request.size!=3 || request[0]!="GET") { http(output,405,"text/plain","GET required".toByteArray()); return }
            val uri=URI(request[1]); val headers=lines.drop(1).mapNotNull { line ->
                val index=line.indexOf(':'); if(index<1) null else line.substring(0,index).lowercase() to line.substring(index+1).trim()
            }.toMap()
            if(uri.path!="/ws") {
                val asset=when(uri.path) { "/" -> "index.html"; "/index.html" -> "index.html"; "/app.css" -> "app.css"; "/app.mjs" -> "app.mjs"; "/protocol.mjs" -> "protocol.mjs"; else -> null }
                if(asset==null) { http(output,404,"text/plain","Not found".toByteArray()); return }
                val mime=when { asset.endsWith("css") -> "text/css"; asset.endsWith("mjs") -> "text/javascript"; else -> "text/html" }
                http(output,200,mime,loadAsset(asset)); return
            }
            val params=(uri.rawQuery ?: "").split('&').mapNotNull { pair ->
                val i=pair.indexOf('='); if(i<0) null else pair.substring(0,i) to URLDecoder.decode(pair.substring(i+1),"UTF-8")
            }.toMap()
            val client=params["client"] ?: ""
            val expectedOrigin="http://${headers["host"]}"
            if(!SessionPolicy.tokenMatches(token,params["token"] ?: "") ||
                !client.matches(Regex("[A-Za-z0-9_-]{8,80}")) || headers["origin"]!=expectedOrigin ||
                headers["sec-websocket-version"]!="13" || headers["upgrade"]?.lowercase()!="websocket") {
                http(output,403,"text/plain","Invalid or expired session".toByteArray()); return
            }
            val key=headers["sec-websocket-key"] ?: return
            if(Base64.getDecoder().decode(key).size!=16) return
            val name=(params["name"] ?: "Helper").take(32).filter { it>=' ' }.ifBlank { "Helper" }
            val current=Peer(socket,output,client,name)
            connection=current
            synchronized(this) {
                if(peer!=null) { http(output,409,"text/plain","A helper is already connected".toByteArray()); return }
                peer=current
            }
            val digest=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(digest)}\r\n\r\n").toByteArray(Charsets.US_ASCII)); output.flush()
            socket.soTimeout=40_000
            current.text(JSONObject().put("type","waiting").put("message","Waiting for the camera owner to approve you").toString())
            fun approve(allowed:Boolean) {
                enqueue(current) {
                    if(!socket.isClosed) {
                        if(!allowed) {
                            current.text("{\"type\":\"denied\"}"); socket.close()
                        } else if(!current.approved) {
                            // Approval and READY share the writer lock: the first
                            // browser pull cannot race ahead of this acknowledgement.
                            synchronized(current) {
                                current.approved=true; approvedClient=client
                                current.text(JSONObject().put("type","ready").put("name",name).put("protocol",1).put("localOnly",true).toString())
                            }
                            callbacks.onPeer(true,name)
                        }
                    }
                }
            }
            if(approvedClient==client) approve(true) else callbacks.onJoin(name,::approve)
            var window=nowMs(); var count=0
            val joinedAt=window
            while(running.get() && !socket.isClosed) {
                val frame=WebSocketIO.readClient(input) ?: break
                val now=nowMs()
                if(now-window>1000) { window=now; count=0 }
                if(++count>50 || now-joinedAt>5_400_000L || !current.approved && now-joinedAt>45_000L) throw IOException("Session limit")
                if(frame.first==8) break
                if(frame.first==9) { current.pong(frame.second); continue }
                if(frame.first==10) continue
                if(frame.first!=1) throw IOException("Only text commands are accepted")
                if(!current.approved) continue
                val msg=try { JSONObject(String(frame.second,Charsets.UTF_8)) } catch(_:Exception) { current.text("{\"type\":\"error\",\"message\":\"Invalid message\"}"); continue }
                when(msg.optString("type")) {
                    "pull" -> {
                        val packet=latest
                        if(paused || packet==null) current.text(JSONObject().put("type","paused").put("message",pauseReason).toString())
                        else if(packet.id.toString()==msg.optString("since")) current.text("{\"type\":\"idle\"}")
                        else current.binary(packet.bytes)
                    }
                    "ping" -> current.text("{\"type\":\"pong\"}")
                    else -> callbacks.onCommand(msg)
                }
            }
        } catch(_:Exception) {
            // Do not log request URLs, bearer tokens, SDP or camera data.
        } finally {
            synchronized(this) { if(peer===connection) { peer=null; connection?.takeIf { it.approved }?.let { callbacks.onPeer(false,it.name) } } }
            sockets.remove(socket); runCatching { socket.close() }
        }
    }
    private fun http(out:OutputStream,code:Int,mime:String,body:ByteArray) {
        val status=if(code==200) "OK" else "Error"
        // Some browsers do not expand connect-src 'self' to ws:. Actual client
        // connections are same-origin; the server independently verifies Origin.
        val headers="HTTP/1.1 $code $status\r\nContent-Type: $mime; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self' ws:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
    }
    companion object {
        fun localAddresses():List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if(it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
        }.getOrDefault(emptyList())
        fun packet(id:Long,metadata:JSONObject,jpeg:ByteArray):Packet {
            val header=metadata.toString().toByteArray(Charsets.UTF_8)
            require(header.size<=512*1024 && jpeg.size<=7*1024*1024) { "Frame envelope too large" }
            return Packet(id,ByteBuffer.allocate(4+header.size+jpeg.size).putInt(header.size).put(header).put(jpeg).array())
        }
    }
}

/** A bounded subset of RFC 6455. Limits are checked BEFORE allocating payloads. */
object WebSocketIO {
    fun readHeader(input:InputStream):String {
        val out=ByteArrayOutputStream(); var tail=0
        while(out.size()<16_384) {
            val b=input.read(); if(b<0) throw EOFException()
            out.write(b); tail=(tail shl 8) or b
            if(tail==0x0d0a0d0a) return out.toString("US-ASCII")
        }
        throw IOException("Header too large")
    }
    fun readClient(input:InputStream):Pair<Int,ByteArray>? {
        val a=input.read(); if(a<0) return null
        val b=input.read(); if(b<0) throw EOFException()
        val op=a and 15
        if(a and 0x80==0 || a and 0x70!=0 || b and 0x80==0) throw IOException("Invalid or fragmented frame")
        if(op !in setOf(1,2,8,9,10)) throw IOException("Unsupported frame opcode")
        val data=DataInputStream(input); var size=(b and 127).toLong()
        if(size==126L) size=data.readUnsignedShort().toLong() else if(size==127L) size=data.readLong()
        if(size !in 0L..32_768L || op>=8 && size>125) throw IOException("Frame too large")
        val mask=ByteArray(4); data.readFully(mask)
        val payload=ByteArray(size.toInt()); data.readFully(payload)
        for(i in payload.indices) payload[i]=(payload[i].toInt() xor mask[i%4].toInt()).toByte()
        return op to payload
    }
    fun write(output:OutputStream,opcode:Int,bytes:ByteArray) {
        val out=DataOutputStream(output); out.writeByte(0x80 or opcode)
        when { bytes.size<126 -> out.writeByte(bytes.size); bytes.size<=65535 -> { out.writeByte(126); out.writeShort(bytes.size) }; else -> { out.writeByte(127); out.writeLong(bytes.size.toLong()) } }
        out.write(bytes); out.flush()
    }
}
