package com.sirpaul.showme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One genuine WebRTC connection for camera video, audio and per-frame AR metadata. */
class RtcVoice(context: Context, private val state: ShowMeSession) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val videoLock = Any()
    private val metadataWorker=Executors.newSingleThreadExecutor()
    private val latestFrame=AtomicReference<VideoDepthFrame?>(null)
    private val metadataBusy=AtomicBoolean()
    private val controlWorker=java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(8), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val answerTimer=ScheduledThreadPoolExecutor(1)
    private val fragments=ControlFragments()
    @Volatile private var control:DataChannel?=null
    @Volatile var controlHandler:((String,JSONObject)->JSONObject)?=null
    private var controlLastAt=0L
    private var controlCount=0
    fun isReady():Boolean=videoSource!=null&&!closed
    private var factory: PeerConnectionFactory? = null
    private var module: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var metadata: DataChannel? = null
    private var pending: CompletableFuture<String>? = null
    @Volatile private var generation = 0
    private var previousMode = AudioManager.MODE_NORMAL
    @Volatile private var muted = false
    @Volatile private var closed = false
    @Volatile private var layout = JSONObject()
    private var fpsStart = monotonicMs()
    private var frameCount = 0

    init {
        if (initialized.compareAndSet(false,true)) PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
    }
    fun attachVideoContext(context: EglBase.Context, width: Int, height: Int, contentHeight: Int, rawWidth: Int, rawHeight: Int, rotation: Int) {
        if (closed) return
        layout = JSONObject().put("videoWidth",width).put("videoHeight",height).put("contentHeight",contentHeight).put("targetFps",30).put("width",rawWidth).put("height",rawHeight).put("rotation",rotation)
        worker.execute {
            disposePeer()
            synchronized(videoLock) {
                disposeFactory()
                module = JavaAudioDeviceModule.builder(app).setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
                factory = PeerConnectionFactory.builder().setAudioDeviceModule(module)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(context,true,true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(context)).createPeerConnectionFactory()
                videoSource = factory!!.createVideoSource(false)
                videoSource!!.adaptOutputFormat(width,height,30)
                videoTrack = factory!!.createVideoTrack("showme-camera",videoSource)
                videoSource!!.capturerObserver.onCapturerStarted(true)
                state.videoState = "READY"
            }
        }
    }
    fun videoLayout(): JSONObject = JSONObject(layout.toString())
    fun onVideoFrame(frame: VideoFrame) {
        if (closed || !state.active || state.paused || !state.hasHelper()) return
        synchronized(videoLock) {
            videoSource?.capturerObserver?.onFrameCaptured(frame)
            frameCount++
            val now = monotonicMs()
            if(now-fpsStart>=1000L) { state.captureFps=frameCount*1000f/(now-fpsStart); fpsStart=now; frameCount=0 }
        }
    }
    fun publishFrame(frame:VideoDepthFrame) {
        if(closed)return
        latestFrame.set(frame)
        if(!metadataBusy.compareAndSet(false,true))return
        metadataWorker.execute {
            try {
                // Coalesce rather than queue metadata for every captured frame.
                while(!closed) {
                    val next=latestFrame.getAndSet(null) ?: break
                    publishMetadata(next.metadata())
                }
            }finally{metadataBusy.set(false)}
        }
    }
    fun publishMetadata(value: JSONObject) {
        val channel=metadata ?: return
        if(channel.state()!=DataChannel.State.OPEN || channel.bufferedAmount()>128_000L) return
        // Never queue seconds of old poses behind live video. Oversized overlays are omitted.
        var bytes=value.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>60_000) { value.remove("annotations"); bytes=value.toString().toByteArray(Charsets.UTF_8) }
        runCatching { channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false)) }
    }
    fun answer(offer: String, iceServers: JSONArray = JSONArray()): CompletableFuture<String> {
        val future=CompletableFuture<String>()
        if(closed) { future.completeExceptionally(IllegalStateException("Session closed")); return future }
        worker.execute {
            try {
                val currentFactory=factory ?: error("The AR camera is still starting. Retry the call in a moment.")
                disposePeer()
                val callGeneration=generation
                pending=future
                val ice=(0 until iceServers.length()).mapNotNull { i->
                    val entry=iceServers.optJSONObject(i) ?: return@mapNotNull null
                    val urls=entry.optJSONArray("urls")?.let { list->(0 until list.length()).map { list.getString(it) } }
                        ?: listOf(entry.optString("urls"))
                    if(urls.isEmpty()||urls.any { !it.matches(Regex("^(stun|turn|turns):.+")) })return@mapNotNull null
                    PeerConnection.IceServer.builder(urls).setUsername(entry.optString("username")).setPassword(entry.optString("credential")).createIceServer()
                }
                val config=PeerConnection.RTCConfiguration(ice).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
                val connection=currentFactory.createPeerConnection(config,object : PeerConnection.Observer {
                    override fun onSignalingChange(value: PeerConnection.SignalingState?) = Unit
                    override fun onIceConnectionChange(value: PeerConnection.IceConnectionState?) = Unit
                    override fun onConnectionChange(value: PeerConnection.PeerConnectionState?) {
                        if(callGeneration!=generation)return
                        state.videoState=value?.name ?: "CONNECTING"
                        state.voiceState=if(audioTrack!=null) state.videoState else "OFF"
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(value: PeerConnection.IceGatheringState?) {
                        if(callGeneration==generation && value==PeerConnection.IceGatheringState.COMPLETE) finishAnswer(callGeneration)
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) = Unit
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: MediaStream?) = Unit
                    override fun onRemoveStream(stream: MediaStream?) = Unit
                    override fun onDataChannel(channel: DataChannel?) {
                        if(callGeneration==generation && channel?.label()=="showme-frames")metadata=channel
                        if(callGeneration==generation && channel?.label()=="showme-control")attachControl(channel,callGeneration)
                    }
                    override fun onRenegotiationNeeded() = Unit
                }) ?: error("Could not create WebRTC call")
                peer=connection
                val sender=connection.addTrack(videoTrack,listOf("showme"))
                sender.parameters.let { parameters ->
                    parameters.degradationPreference=RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    parameters.encodings.forEach { it.maxFramerate=30; it.maxBitrateBps=4_000_000; it.minBitrateBps=350_000 }
                    sender.setParameters(parameters)
                }
                if(state.voiceEnabled && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                    audioSource=currentFactory.createAudioSource(MediaConstraints())
                    audioTrack=currentFactory.createAudioTrack("showme-voice",audioSource).apply { setEnabled(!muted && !state.paused) }
                    connection.addTrack(audioTrack,listOf("showme"))
                }
                previousMode=audioManager.mode
                audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
                audioManager.availableCommunicationDevices.firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
                state.videoState="CONNECTING"
                connection.setRemoteDescription(object : DescriptionObserver() {
                    override fun onSetSuccess() {
                        if(callGeneration!=generation)return
                        connection.createAnswer(object : DescriptionObserver() {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                if(callGeneration!=generation)return
                                if(sdp==null) { future.completeExceptionally(IllegalStateException("Empty WebRTC answer")); return }
                                connection.setLocalDescription(object : DescriptionObserver() {
                                    override fun onSetSuccess() {
                                        if(connection.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE)finishAnswer(callGeneration)
                                        else answerTimer.schedule({ finishAnswer(callGeneration) },5,TimeUnit.SECONDS)
                                    }
                                    override fun onSetFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                                },sdp)
                            }
                            override fun onCreateFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                        },MediaConstraints())
                    }
                    override fun onSetFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
                },SessionDescription(SessionDescription.Type.OFFER,offer))
            }catch(t: Throwable) { state.videoState="FAILED"; future.completeExceptionally(t) }
        }
        return future
    }
    private fun attachControl(channel:DataChannel,expected:Int) {
        control=channel
        channel.registerObserver(object:DataChannel.Observer {
            override fun onBufferedAmountChange(previous:Long)=Unit
            override fun onStateChange()=Unit
            override fun onMessage(buffer:DataChannel.Buffer) {
                if(expected!=generation||buffer.binary||buffer.data.remaining()>48_000)return
                val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                // Reassembly is strictly bounded before any expensive image decoding is enqueued.
                val text=runCatching { fragments.accept(String(bytes,Charsets.UTF_8)) }.getOrNull() ?: return
                val message=runCatching { JSONObject(text) }.getOrNull() ?: return
                val id=message.optString("id")
                if(!id.matches(Regex("[A-Za-z0-9-]{8,80}")))return
                val now=monotonicMs()
                synchronized(fragments) {
                    if(now-controlLastAt>1000L){controlLastAt=now;controlCount=0}
                    if(++controlCount>12)return
                }
                controlWorker.execute {
                    if(expected!=generation)return@execute
                    val response=try{controlHandler?.invoke(message.optString("path"),message.optJSONObject("body")?:JSONObject())
                        ?: ShowMeSession.failure("NOT_READY","Camera session is not ready.")}
                    catch(_:Exception){ShowMeSession.failure("INVALID_REQUEST","This request could not be processed.")}
                    val payload=JSONObject().put("id",id).put("result",response).toString()
                    val deadline=monotonicMs()+10_000L
                    for(part in ControlFragments.split(id,payload)) {
                        while(channel.state()==DataChannel.State.OPEN&&channel.bufferedAmount()>128_000L&&monotonicMs()<deadline)Thread.sleep(4)
                        if(expected!=generation||channel.state()!=DataChannel.State.OPEN||monotonicMs()>=deadline)break
                        if(!channel.send(DataChannel.Buffer(ByteBuffer.wrap(part.toByteArray(Charsets.UTF_8)),false)))break
                    }
                }
            }
        })
    }
    private fun finishAnswer(expected: Int) {
        if(expected!=generation)return
        val sdp=peer?.localDescription?.description ?: return
        pending?.complete(sdp)
    }
    fun setMuted(value: Boolean) {
        muted=value
        if(!closed)worker.execute { audioTrack?.setEnabled(!value && state.voiceEnabled && !state.paused) }
    }
    fun disconnect() {
        if(!closed)worker.execute { disposePeer(); state.videoState="READY"; state.voiceState="OFF" }
    }
    private fun disposePeer() {
        generation++
        pending?.completeExceptionally(IllegalStateException("Call replaced")); pending=null
        metadata=null;control?.unregisterObserver();control=null;fragments.clear();latestFrame.set(null)
        peer?.close(); peer?.dispose(); peer=null
        audioTrack?.dispose(); audioTrack=null; audioSource?.dispose(); audioSource=null
        audioManager.clearCommunicationDevice()
        if(audioManager.mode==AudioManager.MODE_IN_COMMUNICATION)audioManager.mode=previousMode
    }
    private fun disposeFactory() {
        videoSource?.capturerObserver?.onCapturerStopped()
        videoTrack?.dispose(); videoTrack=null
        videoSource?.dispose(); videoSource=null
        factory?.dispose(); factory=null; module?.release(); module=null
    }
    fun close() {
        if(closed)return
        closed=true
        worker.execute { disposePeer(); synchronized(videoLock) { disposeFactory() } }
        worker.shutdown();metadataWorker.shutdown();controlWorker.shutdown();answerTimer.shutdownNow()
    }
    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
    companion object { private val initialized=AtomicBoolean() }
}
