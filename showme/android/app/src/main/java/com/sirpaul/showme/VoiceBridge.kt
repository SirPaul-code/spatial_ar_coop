package com.sirpaul.showme

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/** Optional native WebRTC audio. LAN HTTP browsers can listen; browser talk needs a secure context. */
class VoiceBridge(private val context:Context,private val send:(JSONObject)->Unit) : AutoCloseable {
    private val executor=Executors.newSingleThreadExecutor()
    private val audioManager=context.getSystemService(AudioManager::class.java)
    private var factory:PeerConnectionFactory?=null
    private var connection:PeerConnection?=null
    private var source:AudioSource?=null
    private var track:AudioTrack?=null
    private var remoteReady=false
    private var serial=0L
    private val ice=ArrayList<IceCandidate>()
    @Volatile var enabled=false
        private set
    fun setEnabled(value:Boolean) {
        enabled=value
        executor.execute {
            if(!value) { disposeConnection(); send(JSONObject().put("type","voiceClosed")) }
        }
    }
    fun message(message:JSONObject) {
        if(!enabled) { send(JSONObject().put("type","voiceError").put("message","Ask the camera owner to turn on the microphone.")); return }
        executor.execute {
            try {
                when(message.optString("type")) {
                    "voiceOffer" -> offer(message.getString("sdp"))
                    "voiceIce" -> {
                        val candidate=IceCandidate(message.optString("sdpMid","0"),message.optInt("sdpMLineIndex",0),message.getString("candidate"))
                        if(remoteReady) connection?.addIceCandidate(candidate) else if(ice.size<128) ice.add(candidate)
                    }
                    "voiceStop" -> disposeConnection()
                }
            } catch(_:Exception) { send(JSONObject().put("type","voiceError").put("message","Audio connection failed. Visual guidance is still available.")); disposeConnection() }
        }
    }
    private fun offer(sdp:String) {
        require(sdp.length in 1..24_000)
        disposeConnection(); val generation=++serial
        if(factory==null) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            val adm=JavaAudioDeviceModule.builder(context.applicationContext).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
            factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory(); adm.release()
        }
        audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn=true }
        val config=PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val observer=object:PeerConnection.Observer {
            override fun onSignalingChange(state:PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state:PeerConnection.IceConnectionState) { if(generation==serial) send(JSONObject().put("type","voiceState").put("state",state.name.lowercase())) }
            override fun onIceConnectionReceivingChange(receiving:Boolean) {}
            override fun onIceGatheringChange(state:PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate:IceCandidate) {
                if(generation==serial) send(JSONObject().put("type","voiceIce").put("candidate",candidate.sdp).put("sdpMid",candidate.sdpMid).put("sdpMLineIndex",candidate.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(candidates:Array<out IceCandidate>) {}
            override fun onAddStream(stream:MediaStream) {}
            override fun onRemoveStream(stream:MediaStream) {}
            override fun onDataChannel(channel:DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver:RtpReceiver,streams:Array<out MediaStream>) {}
        }
        val pc=factory!!.createPeerConnection(config,observer) ?: error("WebRTC unavailable")
        connection=pc
        source=factory!!.createAudioSource(MediaConstraints())
        track=factory!!.createAudioTrack("showme-microphone",source)
        pc.addTrack(track,listOf("showme-voice"))
        pc.setRemoteDescription(SdpResult(onSet={
            executor.execute {
                if(generation!=serial) return@execute
                remoteReady=true; ice.forEach { pc.addIceCandidate(it) }; ice.clear()
                pc.createAnswer(SdpResult(onCreate={ answer ->
                    pc.setLocalDescription(SdpResult(onSet={ if(generation==serial) send(JSONObject().put("type","voiceAnswer").put("sdp",answer.description)) }),answer)
                }),MediaConstraints())
            }
        }),SessionDescription(SessionDescription.Type.OFFER,sdp))
    }
    private fun disposeConnection() {
        serial++; remoteReady=false; ice.clear()
        runCatching { connection?.close(); connection?.dispose() }; connection=null
        runCatching { track?.dispose(); source?.dispose() }; track=null; source=null
        audioManager.mode=AudioManager.MODE_NORMAL
    }
    override fun close() {
        enabled=false
        executor.execute { disposeConnection(); factory?.dispose(); factory=null }
        executor.shutdown()
    }
    private inner class SdpResult(private val onSet:()->Unit={},private val onCreate:(SessionDescription)->Unit={}) : SdpObserver {
        override fun onCreateSuccess(description:SessionDescription)=onCreate(description)
        override fun onSetSuccess()=onSet()
        override fun onCreateFailure(error:String)=failed()
        override fun onSetFailure(error:String)=failed()
        private fun failed() { send(JSONObject().put("type","voiceError").put("message","Could not negotiate audio. Visual guidance is still available.")) }
    }
}
