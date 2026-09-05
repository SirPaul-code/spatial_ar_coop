package com.sirpaul.spatialnomap

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WifiAwarePeerTransport(private val context: Context, private val callbacks: Callbacks) {
    data class NearbyRoom(val code: String, val username: String, val distanceM: Float?)
    data class Capabilities(val awareSupported: Boolean, val awareAvailable: Boolean, val rttSupported: Boolean, val rttAvailable: Boolean)
    interface Callbacks {
        fun onTransportStatus(text: String)
        fun onRoomFound(room: NearbyRoom)
        fun onConnected(peerUsername: String)
        fun onDisconnected(reason: String)
        fun onWireMessage(message: WireMessage)
        fun onRange(distanceM: Float, stdDevM: Float, samples: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()
    private val aware = context.getSystemService(WifiAwareManager::class.java)
    private val rtt = context.getSystemService(WifiRttManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var currentPeer: PeerHandle? = null
    private var username = "User"
    private var roomCode = ""
    private var peerUsername = "Peer"
    private val rooms = LinkedHashMap<String, NearbyRoom>()
    private val roomPeers = HashMap<String, PeerHandle>()
    private val running = AtomicBoolean(false)
    private val socketConnected = AtomicBoolean(false)
    private val clientConnectStarted = AtomicBoolean(false)
    private val rangingBusy = AtomicBoolean(false)
    private val messageId = AtomicInteger(100)
    private val latestFrame = AtomicReference<CapturedFrame?>(null)
    private val framePumpRunning = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile var connected = false; private set

    fun capabilities(): Capabilities {
        val pm = context.packageManager
        val hasAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val hasRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        return Capabilities(hasAware, hasAware && aware?.isAvailable == true, hasRtt, hasRtt && rtt?.isAvailable == true)
    }

    fun createRoom(name: String, code: String) { startCommon(name); if (!running.get()) return; roomCode = normalizeRoom(code); attachAware { publish(it) } }
    fun scanRooms(name: String) { startCommon(name); if (!running.get()) return; attachAware { subscribe(it) } }

    fun joinRoom(code: String) {
        val normalized = normalizeRoom(code); val peer = roomPeers[normalized]; val session = subscribeSession
        if (peer == null || session == null) { callbacks.onTransportStatus("Room $normalized is no longer visible. Scan again."); return }
        roomCode = normalized; currentPeer = peer; peerUsername = rooms[normalized]?.username ?: "Peer"
        callbacks.onTransportStatus("Connecting to $peerUsername / $normalized…")
        try {
            session.sendMessage(peer, messageId.incrementAndGet(), "JOIN|$normalized|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8))
            scheduleRanging()
        } catch (t: Throwable) { callbacks.onTransportStatus("Join message failed: ${t.message}") }
    }

    fun sendFrame(frame: CapturedFrame) {
        if (!connected) return
        latestFrame.set(frame)
        if (!framePumpRunning.compareAndSet(false, true)) return
        writer.execute {
            try { while (connected) { val next = latestFrame.getAndSet(null) ?: break; writeMessage(WireMessage.Frame(next)) } }
            finally { framePumpRunning.set(false); latestFrame.get()?.let { if (connected) sendFrame(it) } }
        }
    }
    fun sendPoi(id: Long, owner: String, pointWorld: FloatArray) = sendControl(WireMessage.Poi(id, owner, pointWorld.copyOf(3), System.currentTimeMillis()))
    fun sendClearPoi() = sendControl(WireMessage.ClearPoi)
    fun sendQuality(confidence: Float, stableCount: Int, ready: Boolean) = sendControl(WireMessage.Quality(confidence, stableCount, ready))
    private fun sendRange(distanceM: Float, stdDevM: Float, samples: Int) = sendControl(WireMessage.Range(distanceM, stdDevM, samples))
    private fun sendControl(message: WireMessage) { if (connected) writer.execute { writeMessage(message) } }

    fun stop(reason: String = "stopped") {
        val wasRunning = running.getAndSet(false); main.removeCallbacksAndMessages(null); connected = false
        socketConnected.set(false); clientConnectStarted.set(false); rangingBusy.set(false); latestFrame.set(null)
        try { socket?.close() } catch (_: Throwable) {}; try { serverSocket?.close() } catch (_: Throwable) {}
        socket = null; serverSocket = null
        networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Throwable) {} }; networkCallback = null
        publishSession?.close(); publishSession = null; subscribeSession?.close(); subscribeSession = null; awareSession?.close(); awareSession = null
        currentPeer = null; rooms.clear(); roomPeers.clear()
        if (wasRunning) callbacks.onDisconnected(reason)
    }
    fun close() { stop("closed"); writer.shutdownNow(); io.shutdownNow() }

    private fun startCommon(name: String) {
        stop("restart"); username = name.trim().ifBlank { Build.MODEL }.take(32); val caps = capabilities()
        if (!caps.awareSupported || aware == null) { callbacks.onTransportStatus("Wi‑Fi Aware is not supported on this device."); return }
        if (!caps.awareAvailable) { callbacks.onTransportStatus("Wi‑Fi Aware unavailable. Enable Wi‑Fi/Location and disable hotspot/tethering."); return }
        running.set(true)
    }

    private fun attachAware(action: (WifiAwareSession) -> Unit) {
        try { aware?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) { if (!running.get()) session.close() else { awareSession = session; action(session) } }
            override fun onAttachFailed() { callbacks.onTransportStatus("Wi‑Fi Aware attach failed.") }
        }, main) } catch (t: Throwable) { callbacks.onTransportStatus("Wi‑Fi Aware start failed: ${t.message}") }
    }

    private fun publish(session: WifiAwareSession) {
        val info = "V2|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8)
        val config = PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(info)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).setRangingEnabled(capabilities().rttSupported).build()
        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) { publishSession = session; callbacks.onTransportStatus("Room $roomCode ready — waiting for nearby user") }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val parts = message.toString(StandardCharsets.UTF_8).split('|', limit = 3)
                if (parts.size < 3 || parts[0] != "JOIN" || normalizeRoom(parts[1]) != roomCode) return
                currentPeer = peerHandle; peerUsername = parts[2].ifBlank { "Peer" }.take(32)
                callbacks.onTransportStatus("$peerUsername joined — creating direct Wi‑Fi link…"); requestHostNetwork(peerHandle); scheduleRanging()
            }
            override fun onSessionConfigFailed() { callbacks.onTransportStatus("Wi‑Fi Aware publish configuration failed.") }
        }, main)
    }

    private fun subscribe(session: WifiAwareSession) {
        val builder = SubscribeConfig.Builder().setServiceName(SERVICE).setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
        if (capabilities().rttSupported) { @Suppress("DEPRECATION") builder.setMaxDistanceMm(100_000) }
        session.subscribe(builder.build(), object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) { subscribeSession = session; callbacks.onTransportStatus("Scanning nearby Spatial rooms…") }
            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) { registerRoom(peerHandle, serviceSpecificInfo, null) }
            override fun onServiceDiscoveredWithinRange(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?, distanceMm: Int) { registerRoom(peerHandle, serviceSpecificInfo, distanceMm / 1000f) }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                val text = message.toString(StandardCharsets.UTF_8); if (!text.startsWith("NDP|")) return
                if (normalizeRoom(text.substringAfter('|')) == roomCode) { currentPeer = peerHandle; requestClientNetwork(peerHandle) }
            }
            override fun onSessionConfigFailed() { callbacks.onTransportStatus("Wi‑Fi Aware scan configuration failed.") }
        }, main)
    }

    private fun registerRoom(peer: PeerHandle, info: ByteArray?, distanceM: Float?) {
        val parts = info?.toString(StandardCharsets.UTF_8)?.split('|', limit = 3) ?: return
        if (parts.size < 3 || parts[0] != "V2") return
        val code = normalizeRoom(parts[1]); val room = NearbyRoom(code, parts[2].ifBlank { "Nearby user" }.take(32), distanceM)
        rooms[code] = room; roomPeers[code] = peer; callbacks.onRoomFound(room)
    }

    private fun requestHostNetwork(peer: PeerHandle) {
        if (networkCallback != null) return
        val publish = publishSession ?: return
        try {
            val ss = ServerSocket(0); serverSocket = ss
            val spec = WifiAwareNetworkSpecifier.Builder(publish, peer).setPskPassphrase(psk(roomCode)).setPort(ss.localPort).setTransportProtocol(6).build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { callbacks.onTransportStatus("Direct Wi‑Fi link up — opening peer socket…"); io.execute { try { attachSocket(ss.accept().apply { tcpNoDelay = true }) } catch (t: Throwable) { if (running.get()) failLink("Host socket failed: ${t.message}") } } }
                override fun onLost(network: Network) { failLink("Direct Wi‑Fi link lost") }
            }
            networkCallback = callback; connectivity.requestNetwork(request, callback)
            publish.sendMessage(peer, messageId.incrementAndGet(), "NDP|$roomCode".toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) { failLink("Host data path failed: ${t.message}") }
    }

    private fun requestClientNetwork(peer: PeerHandle) {
        if (!clientConnectStarted.compareAndSet(false, true)) return
        val subscribe = subscribeSession ?: return
        try {
            val spec = WifiAwareNetworkSpecifier.Builder(subscribe, peer).setPskPassphrase(psk(roomCode)).build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { callbacks.onTransportStatus("Direct Wi‑Fi link up — resolving peer endpoint…") }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (socketConnected.get()) return
                    val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                    val address = info.peerIpv6Addr ?: return; val port = info.port; if (port <= 0 || !socketConnected.compareAndSet(false, true)) return
                    io.execute { try { attachSocket(network.socketFactory.createSocket(address, port).apply { tcpNoDelay = true }) } catch (t: Throwable) { socketConnected.set(false); failLink("Client socket failed: ${t.message}") } }
                }
                override fun onLost(network: Network) { failLink("Direct Wi‑Fi link lost") }
            }
            networkCallback = callback; connectivity.requestNetwork(request, callback)
        } catch (t: Throwable) { clientConnectStarted.set(false); failLink("Client data path failed: ${t.message}") }
    }

    private fun attachSocket(s: Socket) {
        if (!running.get()) { try { s.close() } catch (_: Throwable) {}; return }
        socket = s; socketConnected.set(true); connected = true
        callbacks.onTransportStatus("Connected directly to $peerUsername — no AP / no server")
        writeMessage(WireMessage.Hello(username, Build.MODEL)); callbacks.onConnected(peerUsername); io.execute { readLoop(s) }
    }

    private fun readLoop(s: Socket) {
        try {
            while (running.get() && connected && !s.isClosed) {
                when (val message = PeerProtocol.read(s.getInputStream()) ?: break) {
                    is WireMessage.Hello -> { peerUsername = message.username.ifBlank { peerUsername }; callbacks.onConnected(peerUsername) }
                    is WireMessage.Range -> callbacks.onRange(message.distanceM, message.stdDevM, message.samples)
                    else -> callbacks.onWireMessage(message)
                }
            }
            if (running.get()) failLink("Peer disconnected")
        } catch (t: Throwable) { if (running.get()) failLink("Peer link error: ${t.message}") }
    }

    private fun writeMessage(message: WireMessage) {
        val s = socket ?: return; if (!connected || s.isClosed) return
        try { synchronized(writeLock) { PeerProtocol.write(s.getOutputStream(), message) } } catch (t: Throwable) { if (running.get()) failLink("Send failed: ${t.message}") }
    }

    private fun scheduleRanging() { if (running.get()) { main.removeCallbacks(rangeRunnable); main.post(rangeRunnable) } }
    private val rangeRunnable = object : Runnable { override fun run() { rangeOnce(); if (running.get()) main.postDelayed(this, 1200L) } }
    private fun rangeOnce() {
        val peer = currentPeer ?: return; val manager = rtt ?: return
        if (!capabilities().rttAvailable || !rangingBusy.compareAndSet(false, true)) return
        try {
            val request = RangingRequest.Builder().addWifiAwarePeer(peer).setRttBurstSize(8).build()
            manager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rangingBusy.set(false) }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rangingBusy.set(false); val result = results.firstOrNull { it.status == RangingResult.STATUS_SUCCESS } ?: return
                    val samples = result.numSuccessfulMeasurements; val std = if (samples >= 2) result.distanceStdDevMm / 1000f else Float.NaN; val distance = result.distanceMm / 1000f
                    callbacks.onRange(distance, std, samples); sendRange(distance, std, samples)
                }
            })
        } catch (_: Throwable) { rangingBusy.set(false) }
    }

    private fun failLink(reason: String) { if (!running.get()) return; connected = false; try { socket?.close() } catch (_: Throwable) {}; socket = null; callbacks.onDisconnected(reason); callbacks.onTransportStatus(reason) }
    private fun normalizeRoom(code: String) = code.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(8).ifBlank { "ROOM01" }
    private fun safeToken(value: String) = value.replace('|', '_').replace('\n', ' ').replace('\r', ' ').trim().take(32)
    private fun psk(code: String) = "Spatial-${normalizeRoom(code)}-V2"
    companion object { private const val SERVICE = "spatialnomap.v2" }
}
