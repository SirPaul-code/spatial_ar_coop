package com.sirpaul.showme

import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Same application contract over local HTTP or encrypted WebRTC data. Never relay images via signaling. */
class SessionApi(private val state: ShowMeSession, private val call: RtcVoice) {
    private var window=0L
    private var mutations=0
    fun handle(path: String, body: JSONObject = JSONObject()): JSONObject {
        if(!state.active || !state.authorized(state.token))return error("SESSION_ENDED","The owner ended this session.")
        state.touchHelper()
        return when(path) {
            "/api/state" -> state.info().put("ok",true)
            "/api/freeze" -> {
                val id=body.optLong("frameId",-1L);val epoch=body.optInt("epoch",-1)
                val observed=state.videoFrames.get(id)
                if(observed==null||epoch!=state.epoch||observed.epoch!=epoch||state.paused||!state.tracking)
                    return error("STALE_FRAME","This video frame has expired. Resume video and try again.")
                val encoded=body.optString("jpeg")
                if(encoded.length !in 20..2_000_000)return error("INVALID_FRAME","Invalid frozen image.")
                val jpeg=try{Base64.getDecoder().decode(encoded)}catch(_:IllegalArgumentException){return error("INVALID_FRAME","Invalid frozen image.")}
                val packet=try{observed.materialize(jpeg)}catch(_:IllegalArgumentException){return error("FRAME_GEOMETRY","The frozen image does not match its camera geometry.")}
                if(!state.active||state.paused||state.epoch!=epoch)return error("WORLD_CHANGED","The camera session changed. Resume live view.")
                state.frames.add(packet)
                if(!state.frames.pin(id))return error("STALE_FRAME","The frozen frame expired. Try again.")
                packet.metadata().put("ok",true).put("cameraTimestampNs",observed.cameraTimestampNs.toString())
            }
            "/api/resume" -> {state.frames.unpin();ok()}
            "/api/draw" -> {
                if(!allowMutation())return error("RATE_LIMIT","Please wait a moment before drawing again.")
                val id=body.optString("requestId")
                if(!id.matches(Regex("[A-Za-z0-9-]{8,80}")))return error("INVALID_ID","Invalid drawing request.")
                state.previous(id)?.let { return it }
                val action=body.optString("action","draw")
                if(action !in setOf("draw","undo","clear","remove"))return error("INVALID_ACTION","Unknown drawing action.")
                val prepared=if(action=="draw") {
                    ShowMeSession.validateDraw(body)?.let{return error("INVALID_DRAWING",it)}
                    val frame=state.frames.get(body.optLong("frameId",-1L)) ?: return error("STALE_FRAME","Resume live view before drawing again.")
                    if(frame.epoch!=body.optInt("epoch",-1)||frame.epoch!=state.epoch)return error("WORLD_CHANGED","This image belongs to an old camera session.")
                    StrokePlacement.prepare(body,frame) ?: return error("NO_SURFACE","No reliable surface under the whole drawing. Move slightly around the object and try a smaller mark.")
                }else null
                val future=state.submit(body,prepared)
                try{future.get(4,TimeUnit.SECONDS)}catch(_:java.util.concurrent.TimeoutException){
                    val result=error("CAMERA_PAUSED","Keep ShowMe open on the camera phone and try again.")
                    future.complete(result);result
                }
            }
            "/api/voice-stop" -> {call.setMuted(true);ok()}
            else -> error("INVALID_ACTION","This operation is not available over the call.")
        }
    }
    @Synchronized private fun allowMutation(): Boolean {
        val now=monotonicMs();if(now-window>=1000){window=now;mutations=0}
        return ++mutations<=10
    }
    private fun ok()=JSONObject().put("ok",true)
    private fun error(code:String,message:String)=ShowMeSession.failure(code,message)
}
