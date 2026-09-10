package com.sirpaul.showme

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTPS session creation + WSS signaling only. Media and annotations use WebRTC directly. */
class RemoteHostConnection(private val state:ShowMeSession,private val call:RtcVoice,
    private val askApproval:(String,String)->Unit,private val onStatus:(String)->Unit) {
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .pingInterval(20,TimeUnit.SECONDS).build()
    private val worker=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var socket:WebSocket?=null
    @Volatile private var stopped=false
    private var retry=0
    private var socketUrl=""
    private var apiRoot=""
    private var hostToken=""
    @Volatile var inviteUrl=""
        private set
    private var latestRequest=""
    private var helperName="Helper"

    fun start(origin:String,createKey:String,name:String,onReady:(String)->Unit,onFailure:(String)->Unit) {
        worker.execute {
            try {
                require(origin.startsWith("https://")) { "A trusted HTTPS service address is required." }
                val response=post("${origin.trimEnd('/')}/api/rooms",createKey,JSONObject().put("name",name))
                require(response.optBoolean("ok")) { response.optString("message","The service could not start a call.") }
                if(stopped)return@execute
                hostToken=response.getString("hostToken")
                socketUrl=response.getString("socketUrl")
                apiRoot="${origin.trimEnd('/')}/api/rooms/${response.getString("roomId")}"
                inviteUrl=response.getString("inviteUrl")
                state.begin();state.secure=true;state.internet=true
                connect()
                onReady(inviteUrl)
            }catch(e:Exception){onFailure(e.message ?: "Could not reach your ShowMe service.")}
        }
    }
    private fun connect() {
        if(stopped)return
        val request=Request.Builder().url(socketUrl).header("Authorization","Bearer $hostToken").build()
        socket=client.newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response){if(stopped){webSocket.close(1000,"Ended");return};retry=0;onStatus("Ready to invite")}
            override fun onMessage(webSocket:WebSocket,text:String){
                if(stopped||webSocket!==socket||text.length>90_000)return
                val message=runCatching{JSONObject(text)}.getOrNull() ?: return
                when(message.optString("type")) {
                    "join-request" -> {
                        latestRequest=message.optString("viewerId");helperName=message.optString("name","Helper")
                        askApproval(latestRequest,helperName)
                    }
                    "offer" -> worker.execute { answer(message,webSocket) }
                    "ended" -> {state.end();call.disconnect();onStatus("Session ended");stop(false)}
                    "helper-left" -> {state.leave(message.optString("viewerId"));call.disconnect();onStatus("Helper disconnected; the invitation is still active")}
                }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){if(webSocket===socket)reconnect(response?.code)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){if(webSocket===socket)reconnect(if(code==4004)401 else null)}
        })
    }
    private fun reconnect(code:Int?) {
        if(stopped)return
        if(code==401||code==403){state.end();call.disconnect();onStatus("This session expired. Start a new call.");stop(false);return}
        onStatus("Reconnecting the invitation service")
        val delay=(1L shl retry.coerceAtMost(4)).coerceAtMost(15)
        retry++
        if(!worker.isShutdown)worker.schedule({connect()},delay,TimeUnit.SECONDS)
    }
    fun approve(viewerId:String,allow:Boolean) {
        if(stopped||viewerId!=latestRequest)return
        if(allow&&!state.join(viewerId,helperName)){onStatus("A helper is already connected.");return}
        socket?.send(JSONObject().put("type",if(allow)"approve" else "deny").put("viewerId",viewerId).toString())
        latestRequest=""
    }
    private fun answer(message:JSONObject,webSocket:WebSocket) {
        val id=message.optString("id")
        if(stopped||webSocket!==socket||!state.active)return
        try {
            require(state.join(message.getString("viewerId"),state.helperName.ifBlank { helperName })) { "Helper approval required." }
            val iceRequest=Request.Builder().url("$apiRoot/ice").header("Authorization","Bearer $hostToken").build()
            val ice=client.newCall(iceRequest).execute().use { response->
                val body=JSONObject(response.body?.string() ?: "{}")
                require(response.isSuccessful&&body.optBoolean("ok")){body.optString("message","Relay service unavailable.")}
                body.getJSONArray("iceServers")
            }
            // Camera factory starts on its own GL context, independently of the service connection.
            val deadline=monotonicMs()+5000
            while(!call.isReady()&&!stopped&&monotonicMs()<deadline)Thread.sleep(50)
            val answer=call.answer(message.getString("sdp"),ice).get(12,TimeUnit.SECONDS)
            if(!stopped&&webSocket===socket)webSocket.send(JSONObject().put("type","answer").put("id",id).put("ok",true)
                .put("sdp",answer).put("video",call.videoLayout()).toString())
        }catch(e:Exception){
            if(!stopped)webSocket.send(JSONObject().put("type","answer").put("id",id).put("ok",false)
                .put("message",e.message ?: "Could not connect video. Please retry.").toString())
        }
    }
    private fun post(url:String,key:String,body:JSONObject):JSONObject {
        val request=Request.Builder().url(url).header("Authorization","Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use {response->
            val result=JSONObject(response.body?.string() ?: "{}")
            require(response.isSuccessful){result.optString("message","Service returned ${response.code}")};result
        }
    }
    fun stop(endRoom:Boolean=true) {
        if(stopped)return
        stopped=true
        if(endRoom)socket?.send(JSONObject().put("type","end").toString())
        socket?.close(1000,"Session finished");socket=null
        // HTTP fallback revokes the room even if the signaling socket was interrupted.
        if(endRoom&&apiRoot.isNotBlank())worker.execute {runCatching {post("$apiRoot/end",hostToken,JSONObject())}}
        worker.shutdown();client.connectionPool.evictAll()
    }
}
