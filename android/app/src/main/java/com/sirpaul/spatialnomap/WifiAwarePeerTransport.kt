package com.sirpaul.spatialnomap

import android.Manifest
import android.annotation.SuppressLint
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

/**
 * All public entry points are reached after MainActivity's permission flow, but
 * the transport also checks the permission itself. The SuppressLint annotation
 * only tells static analysis about that cross-method invariant; it is not the
 * runtime safety mechanism.
 */
@SuppressLint("MissingPermission")
class WifiAwarePeerTransport(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    data class NearbyRoom(val code: String, val username: String, val distanceM: Float?)
    data class Capabilities(
        val awareSupported: Boolean,
        val awareAvailable: Boolean,
        val rttSupported: Boolean,
        val rttAvailable: Boolean,
    )

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

    @Volatile var connected = false
        private set

    fun capabilities(): Capabilities {
        val pm = context.packageManager
        val hasAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val hasRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        return Capabilities(
            awareSupported = hasAware,
            awareAvailable = hasAware && aware?.isAvailable == true,
            rttSupported = hasRtt,
            rttAvailable = hasRtt && rtt?.isAvailable == true,
        )
    }

    fun createRoom(name: String, code: String) {
        startCommon(name)
        roomCode = normalizeRoom(code)
        attachAware { publish(it, allowRanging = capabilities().rttAvailable) }
    }

    fun scanRooms(name: String) {
        startCommon(name)
        attachAware { subscribe(it, allowRanging = capabilities().rttAvailable) }
    }

    fun joinRoom(code: String) {
        if (!hasPeerPermission()) {
            status("Nearby devices permission was revoked. Re-open connection setup.")
            return
        }
        val normalized = normalizeRoom(code)
        val peer = roomPeers[normalized]
        val session = subscribeSession
        if (peer == null || session == null) {
            status("Room $normalized is no longer visible. Scan again.")
            return
        }
        roomCode = normalized
        currentPeer = peer
        peerUsername = rooms[normalized]?.username ?: "Peer"
        status("Connecting to $peerUsername / $normalized…")
        try {
            session.sendMessage(
                peer,
                messageId.incrementAndGet(),
                "JOIN|$normalized|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8),
            )
            scheduleRanging()
        } catch (t: Throwable) {
            status("Join message failed: ${errorText(t)}")
        }
    }

    fun sendFrame(frame: CapturedFrame) {
        if (!connected) return
        latestFrame.set(frame)
        if (!framePumpRunning.compareAndSet(false, true)) return
        writer.execute {
            try {
                while (connected) {
                    val next = latestFrame.getAndSet(null) ?: break
                    writeMessage(WireMessage.Frame(next))
                }
            } finally {
                framePumpRunning.set(false)
                latestFrame.get()?.let { if (connected) sendFrame(it) }
            }
        }
    }

    fun sendPoi(id: Long, owner: String, pointWorld: FloatArray) =
        sendControl(WireMessage.Poi(id, owner, pointWorld.copyOf(3), System.currentTimeMillis()))

    fun sendClearPoi() = sendControl(WireMessage.ClearPoi)

    fun sendQuality(confidence: Float, stableCount: Int, ready: Boolean) =
        sendControl(WireMessage.Quality(confidence, stableCount, ready))

    fun sendAlignmentReset(reason: String) =
        sendControl(WireMessage.ResetAlignment(reason.take(128)))

    private fun sendRange(distanceM: Float, stdDevM: Float, samples: Int) =
        sendControl(WireMessage.Range(distanceM, stdDevM, samples))

    private fun sendControl(message: WireMessage) {
        if (connected) writer.execute { writeMessage(message) }
    }

    fun stop(reason: String = "stopped") {
        val wasRunning = running.getAndSet(false)
        main.removeCallbacks(rangeRunnable)
        connected = false
        socketConnected.set(false)
        clientConnectStarted.set(false)
        rangingBusy.set(false)
        latestFrame.set(null)

        try { socket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        socket = null
        serverSocket = null

        networkCallback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        networkCallback = null

        try { publishSession?.close() } catch (_: Throwable) {}
        try { subscribeSession?.close() } catch (_: Throwable) {}
        try { awareSession?.close() } catch (_: Throwable) {}
        publishSession = null
        subscribeSession = null
        awareSession = null
        currentPeer = null
        rooms.clear()
        roomPeers.clear()

        if (wasRunning) safeCallback { callbacks.onDisconnected(reason) }
    }

    fun close() {
        stop("closed")
        writer.shutdownNow()
        io.shutdownNow()
    }

    private fun startCommon(name: String) {
        stop("restart")
        username = name.trim().ifBlank { Build.MODEL }.take(32)
        if (!hasPeerPermission()) {
            throw SecurityException("Nearby devices permission is required for direct peer discovery")
        }
        val caps = capabilities()
        if (!caps.awareSupported || aware == null) {
            throw IllegalStateException("Wi-Fi Aware is not supported on this device")
        }
        if (!caps.awareAvailable) {
            throw IllegalStateException("Wi-Fi Aware unavailable. Enable Wi-Fi/Location and disable hotspot/tethering")
        }
        running.set(true)
    }

    private fun attachAware(action: (WifiAwareSession) -> Unit) {
        try {
            aware?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    if (!running.get()) {
                        session.close()
                        return
                    }
                    awareSession = session
                    try {
                        action(session)
                    } catch (t: Throwable) {
                        status("Wi-Fi Aware setup failed: ${errorText(t)}")
                        running.set(false)
                        try { session.close() } catch (_: Throwable) {}
                        awareSession = null
                    }
                }

                override fun onAttachFailed() {
                    running.set(false)
                    status("Wi-Fi Aware attach failed")
                }
            }, main)
        } catch (t: Throwable) {
            running.set(false)
            throw IllegalStateException("Wi-Fi Aware start failed: ${errorText(t)}", t)
        }
    }

    private fun publish(session: WifiAwareSession, allowRanging: Boolean) {
        val info = "V3|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8)
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE)
            .setServiceSpecificInfo(info)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setRangingEnabled(allowRanging)
            .build()

        try {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    status("Room $roomCode ready — waiting for nearby user")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    try {
                        val parts = message.toString(StandardCharsets.UTF_8).split('|', limit = 3)
                        if (parts.size < 3 || parts[0] != "JOIN" || normalizeRoom(parts[1]) != roomCode) return
                        currentPeer = peerHandle
                        peerUsername = parts[2].ifBlank { "Peer" }.take(32)
                        status("$peerUsername joined — creating direct Wi-Fi link…")
                        requestHostNetwork(peerHandle)
                        scheduleRanging()
                    } catch (t: Throwable) {
                        status("Peer join handling failed: ${errorText(t)}")
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowRanging && running.get()) {
                        status("Retrying room without RTT discovery filter…")
                        try { publish(session = awareSession ?: return, allowRanging = false) }
                        catch (t: Throwable) { status("Wi-Fi Aware publish failed: ${errorText(t)}") }
                    } else {
                        status("Wi-Fi Aware publish configuration failed")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowRanging) publish(session, allowRanging = false)
            else throw t
        }
    }

    private fun subscribe(session: WifiAwareSession, allowRanging: Boolean) {
        val builder = SubscribeConfig.Builder()
            .setServiceName(SERVICE)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
        if (allowRanging) {
            @Suppress("DEPRECATION")
            builder.setMaxDistanceMm(100_000)
        }

        try {
            session.subscribe(builder.build(), object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    status("Scanning nearby Spatial rooms…")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?,
                ) {
                    registerRoom(peerHandle, serviceSpecificInfo, null)
                }

                override fun onServiceDiscoveredWithinRange(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?,
                    distanceMm: Int,
                ) {
                    registerRoom(peerHandle, serviceSpecificInfo, distanceMm / 1000f)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    try {
                        val text = message.toString(StandardCharsets.UTF_8)
                        if (!text.startsWith("NDP|")) return
                        if (normalizeRoom(text.substringAfter('|')) == roomCode) {
                            currentPeer = peerHandle
                            requestClientNetwork(peerHandle)
                        }
                    } catch (t: Throwable) {
                        status("Peer data-path message failed: ${errorText(t)}")
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowRanging && running.get()) {
                        status("Retrying scan without RTT distance filter…")
                        try { subscribe(session = awareSession ?: return, allowRanging = false) }
                        catch (t: Throwable) { status("Wi-Fi Aware scan failed: ${errorText(t)}") }
                    } else {
                        status("Wi-Fi Aware scan configuration failed")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowRanging) subscribe(session, allowRanging = false)
            else throw t
        }
    }

    private fun registerRoom(peer: PeerHandle, info: ByteArray?, distanceM: Float?) {
        try {
            val parts = info?.toString(StandardCharsets.UTF_8)?.split('|', limit = 3) ?: return
            if (parts.size < 3 || parts[0] != "V3") return
            val code = normalizeRoom(parts[1])
            val room = NearbyRoom(
                code,
                parts[2].ifBlank { "Nearby user" }.take(32),
                distanceM,
            )
            rooms[code] = room
            roomPeers[code] = peer
            safeCallback { callbacks.onRoomFound(room) }
        } catch (t: Throwable) {
            status("Nearby room decode failed: ${errorText(t)}")
        }
    }

    private fun requestHostNetwork(peer: PeerHandle) {
        if (networkCallback != null) return
        val publish = publishSession ?: return
        try {
            val ss = ServerSocket(0)
            serverSocket = ss
            val spec = WifiAwareNetworkSpecifier.Builder(publish, peer)
                .setPskPassphrase(psk(roomCode))
                .setPort(ss.localPort)
                .setTransportProtocol(6)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    status("Direct Wi-Fi link up — opening peer socket…")
                    io.execute {
                        try {
                            attachSocket(ss.accept().apply { tcpNoDelay = true })
                        } catch (t: Throwable) {
                            if (running.get()) failLink("Host socket failed: ${errorText(t)}")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    failLink("Direct Wi-Fi link lost")
                }
            }
            networkCallback = callback
            connectivity.requestNetwork(request, callback)
            publish.sendMessage(
                peer,
                messageId.incrementAndGet(),
                "NDP|$roomCode".toByteArray(StandardCharsets.UTF_8),
            )
        } catch (t: Throwable) {
            failLink("Host data path failed: ${errorText(t)}")
        }
    }

    private fun requestClientNetwork(peer: PeerHandle) {
        if (!clientConnectStarted.compareAndSet(false, true)) return
        val subscribe = subscribeSession ?: run {
            clientConnectStarted.set(false)
            return
        }
        try {
            val spec = WifiAwareNetworkSpecifier.Builder(subscribe, peer)
                .setPskPassphrase(psk(roomCode))
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    status("Direct Wi-Fi link up — resolving peer endpoint…")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (socketConnected.get()) return
                    try {
                        val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                        val address = info.peerIpv6Addr ?: return
                        val port = info.port
                        if (port <= 0 || !socketConnected.compareAndSet(false, true)) return
                        io.execute {
                            try {
                                attachSocket(network.socketFactory.createSocket(address, port).apply { tcpNoDelay = true })
                            } catch (t: Throwable) {
                                socketConnected.set(false)
                                failLink("Client socket failed: ${errorText(t)}")
                            }
                        }
                    } catch (t: Throwable) {
                        socketConnected.set(false)
                        failLink("Peer endpoint resolution failed: ${errorText(t)}")
                    }
                }

                override fun onLost(network: Network) {
                    failLink("Direct Wi-Fi link lost")
                }
            }
            networkCallback = callback
            connectivity.requestNetwork(request, callback)
        } catch (t: Throwable) {
            clientConnectStarted.set(false)
            failLink("Client data path failed: ${errorText(t)}")
        }
    }

    private fun attachSocket(s: Socket) {
        if (!running.get()) {
            try { s.close() } catch (_: Throwable) {}
            return
        }
        socket = s
        socketConnected.set(true)
        connected = true
        status("Connected directly to $peerUsername — no AP / no server")
        writeMessage(WireMessage.Hello(username, Build.MODEL))
        safeCallback { callbacks.onConnected(peerUsername) }
        io.execute { readLoop(s) }
    }

    private fun readLoop(s: Socket) {
        try {
            while (running.get() && connected && !s.isClosed) {
                when (val message = PeerProtocol.read(s.getInputStream()) ?: break) {
                    is WireMessage.Hello -> {
                        peerUsername = message.username.ifBlank { peerUsername }
                        safeCallback { callbacks.onConnected(peerUsername) }
                    }
                    is WireMessage.Range -> safeCallback {
                        callbacks.onRange(message.distanceM, message.stdDevM, message.samples)
                    }
                    else -> safeCallback { callbacks.onWireMessage(message) }
                }
            }
            if (running.get()) failLink("Peer disconnected")
        } catch (t: Throwable) {
            if (running.get()) failLink("Peer link error: ${errorText(t)}")
        }
    }

    private fun writeMessage(message: WireMessage) {
        val s = socket ?: return
        if (!connected || s.isClosed) return
        try {
            synchronized(writeLock) { PeerProtocol.write(s.getOutputStream(), message) }
        } catch (t: Throwable) {
            if (running.get()) failLink("Send failed: ${errorText(t)}")
        }
    }

    private fun scheduleRanging() {
        if (running.get()) {
            main.removeCallbacks(rangeRunnable)
            main.post(rangeRunnable)
        }
    }

    private val rangeRunnable = object : Runnable {
        override fun run() {
            rangeOnce()
            if (running.get()) main.postDelayed(this, 1200L)
        }
    }

    private fun rangeOnce() {
        val peer = currentPeer ?: return
        val manager = rtt ?: return
        if (!hasPeerPermission()) return
        if (!capabilities().rttAvailable || !rangingBusy.compareAndSet(false, true)) return
        try {
            val request = RangingRequest.Builder()
                .addWifiAwarePeer(peer)
                .setRttBurstSize(8)
                .build()
            manager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    rangingBusy.set(false)
                }

                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rangingBusy.set(false)
                    val result = results.firstOrNull { it.status == RangingResult.STATUS_SUCCESS } ?: return
                    val samples = result.numSuccessfulMeasurements
                    val std = if (samples >= 2) result.distanceStdDevMm / 1000f else Float.NaN
                    val distance = result.distanceMm / 1000f
                    safeCallback { callbacks.onRange(distance, std, samples) }
                    sendRange(distance, std, samples)
                }
            })
        } catch (_: Throwable) {
            rangingBusy.set(false)
        }
    }

    private fun failLink(reason: String) {
        if (!running.get()) return
        connected = false
        socketConnected.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        safeCallback { callbacks.onDisconnected(reason) }
        status(reason)
    }

    private fun hasPeerPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun status(text: String) = safeCallback { callbacks.onTransportStatus(text) }

    private inline fun safeCallback(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun errorText(t: Throwable): String =
        "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"

    private fun normalizeRoom(code: String) =
        code.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(8).ifBlank { "ROOM01" }

    private fun safeToken(value: String) =
        value.replace('|', '_').replace('\n', ' ').replace('\r', ' ').trim().take(32)

    private fun psk(code: String) = "Spatial-${normalizeRoom(code)}-V3"

    companion object {
        private const val SERVICE = "spatialnomap.v3"
    }
}
