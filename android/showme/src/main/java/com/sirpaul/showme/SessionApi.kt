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
                // Acquire the matching StableAR FrameRef lease before doing JPEG materialization. The
                // request is executed by ShowMeRenderer on the AR/GL owner thread and fails closed.
                val stableHeld=try{state.requestSpatialFreeze(id,epoch).get(1500,TimeUnit.MILLISECONDS)}
                    catch(_:Exception){false}
                if(!stableHeld)return error("STALE_FRAME","The exact spatial frame expired. Resume video and draw again.")
                val encoded=body.optString("jpeg")
                if(encoded.length !in 20..2_000_000){state.requestSpatialUnfreeze();return error("INVALID_FRAME","Invalid frozen image.")}
                val jpeg=try{Base64.getDecoder().decode(encoded)}catch(_:IllegalArgumentException){
                    state.requestSpatialUnfreeze();return error("INVALID_FRAME","Invalid frozen image.")}
                val packet=try{observed.materialize(jpeg)}catch(_:IllegalArgumentException){
                    state.requestSpatialUnfreeze();return error("FRAME_GEOMETRY","The frozen image does not match its camera geometry.")}
                if(!state.active||state.paused||state.epoch!=epoch){
                    state.requestSpatialUnfreeze();return error("WORLD_CHANGED","The camera session changed. Resume live view.")
                }
                state.frames.add(packet)
                if(!state.frames.pin(id)){
                    state.requestSpatialUnfreeze();return error("STALE_FRAME","The frozen frame expired. Try again.")
                }
                packet.metadata().put("ok",true).put("cameraTimestampNs",observed.cameraTimestampNs.toString())
                    .put("spatialMode",if(state.stableArEnabled)"STABLE_AR" else "LEGACY")
            }
            "/api/resume" -> {state.frames.unpin();state.requestSpatialUnfreeze();ok()}
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
                    // A StableAR pin's Z is computed only by StableAR from the exact retained SDK
                    // frame. Do not let the legacy ShowMe metric fitter reject or bias that pin.
                    val stablePin=state.stableArEnabled && body.optString("tool")=="pin"
                    StrokePlacement.prepare(body,frame,requireMetricDepth=!stablePin)
                        ?: return error("NO_SURFACE",if(stablePin)
                            "The exact camera pixel could not be prepared for StableAR. Resume live view and try again."
                        else "No reliable surface under the whole drawing. Move slightly around the object and try a smaller mark.")
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
