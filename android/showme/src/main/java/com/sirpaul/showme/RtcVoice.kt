package com.sirpaul.showme

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Audio is separate from frame-exact annotation transport. No external ICE server in LAN mode. */
class RtcVoice(context: Context, private val state: ShowMeSession) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var pending: CompletableFuture<String>? = null
    private var previousMode = AudioManager.MODE_NORMAL
    @Volatile private var muted = false

    fun answer(offer: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        if (!state.voiceEnabled) {
            future.completeExceptionally(IllegalStateException("The camera owner must enable the microphone first.")); return future
        }
        worker.execute {
            try {
                disposePeer()
                if (factory == null) {
                    if (initialized.compareAndSet(false, true)) PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
                    module = JavaAudioDeviceModule.builder(app).setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
                    factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
                    source = factory!!.createAudioSource(MediaConstraints())
                    track = factory!!.createAudioTrack("showme-voice", source)
                    previousMode = audioManager.mode
                }
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
                track?.setEnabled(!muted)
                pending = future
                val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                val connection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                    override fun onIceConnectionChange(value: PeerConnection.IceConnectionState?) {
                        state.voiceState = value?.name ?: "CONNECTING"
                    }
                    override fun onConnectionChange(value: PeerConnection.PeerConnectionState?) {
                        state.voiceState = value?.name ?: "CONNECTING"
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(value: PeerConnection.IceGatheringState?) {
                        if (value == PeerConnection.IceGatheringState.COMPLETE) finishAnswer()
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) = Unit
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: MediaStream?) = Unit
                    override fun onRemoveStream(stream: MediaStream?) = Unit
                    override fun onDataChannel(channel: DataChannel?) = Unit
                    override fun onRenegotiationNeeded() = Unit
                }) ?: error("Could not create local audio connection")
                peer = connection
                connection.addTrack(track, listOf("showme"))
                state.voiceState = "CONNECTING"
                connection.setRemoteDescription(object : DescriptionObserver() {
                    override fun onSetSuccess() {
                        connection.createAnswer(object : DescriptionObserver() {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                if (sdp == null) { future.completeExceptionally(IllegalStateException("Empty audio answer")); return }
                                connection.setLocalDescription(object : DescriptionObserver() {
                                    override fun onSetSuccess() {
                                        if (connection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) finishAnswer()
                                    }
                                    override fun onSetFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                                }, sdp)
                            }
                            override fun onCreateFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                }, SessionDescription(SessionDescription.Type.OFFER, offer))
            } catch (t: Throwable) { state.voiceState = "FAILED"; future.completeExceptionally(t) }
        }
        return future
    }
    private fun finishAnswer() {
        val sdp = peer?.localDescription?.description ?: return
        pending?.complete(sdp)
    }
    fun setMuted(value: Boolean) {
        muted = value
        if (!worker.isShutdown) worker.execute { track?.setEnabled(!value && state.voiceEnabled) }
    }
    fun disconnect() { if (!worker.isShutdown) worker.execute { disposePeer(); state.voiceState = "OFF" } }
    private fun disposePeer() {
        pending?.completeExceptionally(IllegalStateException("Voice connection replaced")); pending = null
        peer?.close(); peer?.dispose(); peer = null
        audioManager.clearCommunicationDevice()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = previousMode
    }
    fun close() {
        if (worker.isShutdown) return
        worker.execute {
            disposePeer(); track?.dispose(); source?.dispose(); factory?.dispose(); module?.release()
            track = null; source = null; factory = null; module = null
        }
        worker.shutdown()
    }
    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
    companion object { private val initialized = AtomicBoolean() }
}
