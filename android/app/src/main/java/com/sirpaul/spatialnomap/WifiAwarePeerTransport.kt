package com.sirpaul.spatialnomap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
import android.os.SystemClock
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct Wi-Fi Aware/NDP transport for exactly two nearby phones.
 *
 * The discovery messages are deliberately treated as lossy signalling. JOIN and
 * NDP announcements are retried until the real TCP socket is alive, and a failed
 * NDP request is torn down without destroying the Wi-Fi Aware discovery session.
 * This avoids the old state where one phone was DIRECT while the other stayed
 * LINKING until both apps were restarted.
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
        val nearbyPermissionGranted: Boolean,
        val fineLocationGranted: Boolean,
        val coarseLocationGranted: Boolean,
        val locationEnabled: Boolean,
    ) {
        val permissionsReady: Boolean
            get() = nearbyPermissionGranted && fineLocationGranted && coarseLocationGranted
    }

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
    private val location = context.getSystemService(LocationManager::class.java)

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
    private val endpointResolveBusy = AtomicBoolean(false)
    private val hostAcceptStarted = AtomicBoolean(false)
    private val rangingBusy = AtomicBoolean(false)
    private val recoveryScheduled = AtomicBoolean(false)
    private val messageId = AtomicInteger(100)
    private val latestFrame = AtomicReference<CapturedFrame?>(null)
    private val framePumpRunning = AtomicBoolean(false)
    private val writeLock = Any()

    private var networkRequestedAtMs = 0L
    private var joinAttempt = 0
    private var lastRangingError = ""

    @Volatile var connected = false
        private set

    @Volatile private var hostRole = false
    val isHostRole: Boolean get() = hostRole

    fun capabilities(): Capabilities {
        val pm = context.packageManager
        val hasAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val hasRtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        val fineGranted =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted =
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationEnabled = runCatching { location?.isLocationEnabled != false }.getOrDefault(true)
        return Capabilities(
            awareSupported = hasAware,
            awareAvailable = hasAware && aware?.isAvailable == true,
            rttSupported = hasRtt,
            rttAvailable = hasRtt && rtt?.isAvailable == true,
            nearbyPermissionGranted = nearbyGranted,
            fineLocationGranted = fineGranted,
            coarseLocationGranted = coarseGranted,
            locationEnabled = locationEnabled,
        )
    }

    fun createRoom(name: String, code: String) {
        startCommon(name)
        hostRole = true
        roomCode = normalizeRoom(code)
        attachAware { publish(it, allowRanging = capabilities().rttAvailable) }
    }

    fun scanRooms(name: String) {
        startCommon(name)
        hostRole = false
        attachAware { subscribe(it, allowRanging = capabilities().rttAvailable) }
    }

    fun joinRoom(code: String) {
        val missing = missingPeerPermissions()
        if (missing.isNotEmpty()) {
            status("Permission missing: ${missing.joinToString()}. Re-open connection setup.")
            return
        }

        val normalized = normalizeRoom(code)
        val peer = roomPeers[normalized]
        val session = subscribeSession
        if (peer == null || session == null) {
            status("Room $normalized is no longer visible. Scan again.")
            return
        }

        hostRole = false
        roomCode = normalized
        currentPeer = peer
        peerUsername = rooms[normalized]?.username ?: "Peer"
        status("Connecting to $peerUsername / $normalized…")
        joinAttempt = 0
        scheduleJoinHandshake(0L)
        scheduleRanging()
    }

    fun sendFrame(frame: CapturedFrame) {
        if (!connected) return
        latestFrame.set(frame)
        scheduleFramePump()
    }

    private fun scheduleFramePump() {
        if (!connected || !framePumpRunning.compareAndSet(false, true)) return
        writer.execute {
            try {
                val next = latestFrame.getAndSet(null)
                if (next != null && connected) writeMessage(next = WireMessage.Frame(next))
            } finally {
                framePumpRunning.set(false)
                if (connected && latestFrame.get() != null) scheduleFramePump()
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

    fun sendAlignmentTransform(
        senderFromPeer: DoubleArray,
        confidence: Float,
        inliers: Int,
        medianReprojectionPx: Float,
        source: String,
    ) = sendControl(
        WireMessage.AlignmentTransform(
            senderFromPeer = senderFromPeer.copyOf(16),
            confidence = confidence,
            inliers = inliers,
            medianReprojectionPx = medianReprojectionPx,
            source = source.take(48),
        ),
    )

    private fun sendRange(distanceM: Float, stdDevM: Float, samples: Int) =
        sendControl(WireMessage.Range(distanceM, stdDevM, samples))

    private fun sendControl(message: WireMessage) {
        if (connected) writer.execute { writeMessage(message) }
    }

    fun stop(reason: String = "stopped") {
        val wasRunning = running.getAndSet(false)
        val wasConnected = connected
        main.removeCallbacks(rangeRunnable)
        main.removeCallbacks(joinRetryRunnable)
        recoveryScheduled.set(false)
        cleanupDataPath()
        rangingBusy.set(false)
        latestFrame.set(null)
        lastRangingError = ""

        try { publishSession?.close() } catch (_: Throwable) {}
        try { subscribeSession?.close() } catch (_: Throwable) {}
        try { awareSession?.close() } catch (_: Throwable) {}
        publishSession = null
        subscribeSession = null
        awareSession = null
        currentPeer = null
        rooms.clear()
        roomPeers.clear()
        roomCode = ""
        hostRole = false

        if (wasRunning && wasConnected) safeCallback { callbacks.onDisconnected(reason) }
    }

    fun close() {
        stop("closed")
        writer.shutdownNow()
        io.shutdownNow()
    }

    private fun startCommon(name: String) {
        stop("restart")
        username = name.trim().ifBlank { Build.MODEL }.take(32)

        val missing = missingPeerPermissions()
        if (missing.isNotEmpty()) {
            throw SecurityException("Missing runtime permission(s): ${missing.joinToString()}")
        }

        val caps = capabilities()
        if (!caps.locationEnabled) {
            throw IllegalStateException("Location services are OFF. Samsung Wi-Fi Aware/RTT requires Location to be enabled while pairing.")
        }
        if (!caps.awareSupported || aware == null) {
            throw IllegalStateException("Wi-Fi Aware is not supported on this device")
        }
        if (!caps.awareAvailable) {
            throw IllegalStateException("Wi-Fi Aware unavailable. Enable Wi-Fi + Location and disable hotspot/tethering/Wi-Fi Direct conflicts")
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
                        reportError("Wi-Fi Aware setup failed", t)
                        running.set(false)
                        try { session.close() } catch (_: Throwable) {}
                        awareSession = null
                    }
                }

                override fun onAttachFailed() {
                    running.set(false)
                    status("Wi-Fi Aware attach failed (framework rejected the session)")
                }
            }, main)
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "Wi-Fi Aware attach threw", t)
            throw IllegalStateException("Wi-Fi Aware start failed: ${errorText(t)}", t)
        }
    }

    private fun publish(session: WifiAwareSession, allowRanging: Boolean) {
        val info = "V5|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8)
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
                        status("$peerUsername joined — negotiating direct Wi-Fi link…")
                        ensureHostNetwork(peerHandle)
                        announceNdp(peerHandle)
                        scheduleRanging()
                    } catch (t: Throwable) {
                        reportError("Peer join handling failed", t)
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowRanging && running.get()) {
                        status("Retrying room without RTT discovery filter…")
                        try {
                            publish(session = awareSession ?: return, allowRanging = false)
                        } catch (t: Throwable) {
                            reportError("Wi-Fi Aware publish failed", t)
                        }
                    } else {
                        status("Wi-Fi Aware publish configuration failed")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowRanging) {
                status("RTT-enabled publish rejected; retrying plain Wi-Fi Aware…")
                publish(session, allowRanging = false)
            } else {
                throw t
            }
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
                        if (normalizeRoom(text.substringAfter('|')) != roomCode) return
                        currentPeer = peerHandle
                        requestClientNetwork(peerHandle)
                    } catch (t: Throwable) {
                        reportError("Peer data-path message failed", t)
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowRanging && running.get()) {
                        status("Retrying scan without RTT distance filter…")
                        try {
                            subscribe(session = awareSession ?: return, allowRanging = false)
                        } catch (t: Throwable) {
                            reportError("Wi-Fi Aware scan failed", t)
                        }
                    } else {
                        status("Wi-Fi Aware scan configuration failed")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowRanging) {
                status("RTT-enabled scan rejected; retrying plain Wi-Fi Aware…")
                subscribe(session, allowRanging = false)
            } else {
                throw t
            }
        }
    }

    private fun registerRoom(peer: PeerHandle, info: ByteArray?, distanceM: Float?) {
        try {
            val parts = info?.toString(StandardCharsets.UTF_8)?.split('|', limit = 3) ?: return
            if (parts.size < 3 || parts[0] != "V5") return
            val code = normalizeRoom(parts[1])
            val room = NearbyRoom(
                code = code,
                username = parts[2].ifBlank { "Nearby user" }.take(32),
                distanceM = distanceM,
            )
            rooms[code] = room
            roomPeers[code] = peer
            safeCallback { callbacks.onRoomFound(room) }
        } catch (t: Throwable) {
            reportError("Nearby room decode failed", t)
        }
    }

    private fun scheduleJoinHandshake(delayMs: Long) {
        main.removeCallbacks(joinRetryRunnable)
        if (running.get() && !connected && !hostRole) main.postDelayed(joinRetryRunnable, delayMs)
    }

    private val joinRetryRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || connected || hostRole) return
            val peer = currentPeer ?: return
            val session = subscribeSession ?: return

            val now = SystemClock.elapsedRealtime()
            if (clientConnectStarted.get() && networkRequestedAtMs > 0L && now - networkRequestedAtMs > NDP_REQUEST_TIMEOUT_MS) {
                status("Direct link handshake timed out — retrying without app restart…")
                cleanupDataPath()
            }

            try {
                session.sendMessage(
                    peer,
                    messageId.incrementAndGet(),
                    "JOIN|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8),
                )
                joinAttempt += 1
            } catch (t: Throwable) {
                Log.w(TAG, "JOIN retry failed", t)
            }

            if (running.get() && !connected) {
                main.postDelayed(this, if (joinAttempt < 8) 700L else 1200L)
            }
        }
    }

    private fun ensureHostNetwork(peer: PeerHandle) {
        if (!running.get() || connected) return
        val publish = publishSession ?: return
        val now = SystemClock.elapsedRealtime()

        if (networkCallback != null) {
            if (networkRequestedAtMs > 0L && now - networkRequestedAtMs > NDP_REQUEST_TIMEOUT_MS) {
                status("Refreshing stale Wi-Fi Aware data path…")
                cleanupDataPath()
            } else {
                return
            }
        }

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
                    if (!hostAcceptStarted.compareAndSet(false, true)) return
                    io.execute {
                        try {
                            attachSocket(ss.accept().apply { tcpNoDelay = true })
                        } catch (t: Throwable) {
                            hostAcceptStarted.set(false)
                            if (running.get() && !connected) recoverDataPath("Host socket failed: ${errorText(t)}")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (running.get()) recoverDataPath("Direct Wi-Fi link lost")
                }

                override fun onUnavailable() {
                    if (running.get()) recoverDataPath("Direct Wi-Fi data path unavailable")
                }
            }
            networkCallback = callback
            networkRequestedAtMs = now
            connectivity.requestNetwork(request, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "Host data path failed", t)
            recoverDataPath("Host data path failed: ${errorText(t)}")
        }
    }

    private fun announceNdp(peer: PeerHandle) {
        val publish = publishSession ?: return
        if (!running.get() || connected) return
        try {
            publish.sendMessage(
                peer,
                messageId.incrementAndGet(),
                "NDP|$roomCode".toByteArray(StandardCharsets.UTF_8),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NDP announcement failed; JOIN retry will request another", t)
        }
    }

    private fun requestClientNetwork(peer: PeerHandle) {
        if (!running.get() || connected) return
        val now = SystemClock.elapsedRealtime()
        if (clientConnectStarted.get()) {
            if (networkRequestedAtMs > 0L && now - networkRequestedAtMs > NDP_REQUEST_TIMEOUT_MS) {
                cleanupDataPath()
            } else {
                return
            }
        }
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
                    resolveClientEndpoint(network)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    resolveClientEndpoint(network)
                }

                override fun onLost(network: Network) {
                    if (running.get()) recoverDataPath("Direct Wi-Fi link lost")
                }

                override fun onUnavailable() {
                    if (running.get()) recoverDataPath("Direct Wi-Fi data path unavailable")
                }
            }
            networkCallback = callback
            networkRequestedAtMs = now
            connectivity.requestNetwork(request, callback)
        } catch (t: Throwable) {
            clientConnectStarted.set(false)
            Log.e(TAG, "Client data path failed", t)
            recoverDataPath("Client data path failed: ${errorText(t)}")
        }
    }

    private fun resolveClientEndpoint(network: Network) {
        if (!running.get() || connected || !endpointResolveBusy.compareAndSet(false, true)) return
        io.execute {
            try {
                repeat(ENDPOINT_RESOLVE_ATTEMPTS) {
                    if (!running.get() || connected) return@execute
                    val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                    val info = caps?.transportInfo as? WifiAwareNetworkInfo
                    val address = info?.peerIpv6Addr
                    val port = info?.port ?: 0
                    if (address != null && port > 0) {
                        if (!socketConnected.compareAndSet(false, true)) return@execute
                        try {
                            val peerSocket = network.socketFactory.createSocket(address, port).apply { tcpNoDelay = true }
                            attachSocket(peerSocket)
                            return@execute
                        } catch (t: Throwable) {
                            socketConnected.set(false)
                            Log.w(TAG, "Client socket connect attempt failed", t)
                        }
                    }
                    Thread.sleep(ENDPOINT_RESOLVE_SLEEP_MS)
                }

                if (running.get() && !connected) {
                    main.post { recoverDataPath("Peer endpoint was not published in time") }
                }
            } catch (t: Throwable) {
                if (running.get() && !connected) main.post { recoverDataPath("Peer endpoint resolution failed: ${errorText(t)}") }
            } finally {
                endpointResolveBusy.set(false)
            }
        }
    }

    @Synchronized private fun attachSocket(s: Socket) {
        if (!running.get()) {
            try { s.close() } catch (_: Throwable) {}
            return
        }
        if (connected) {
            try { s.close() } catch (_: Throwable) {}
            return
        }

        socket = s
        socketConnected.set(true)
        connected = true
        networkRequestedAtMs = 0L
        recoveryScheduled.set(false)
        main.removeCallbacks(joinRetryRunnable)
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
            if (running.get() && connected) recoverDataPath("Peer socket closed")
        } catch (t: Throwable) {
            if (running.get() && connected) {
                Log.e(TAG, "Peer read loop failed", t)
                recoverDataPath("Peer link error: ${errorText(t)}")
            }
        }
    }

    private fun writeMessage(next: WireMessage) {
        val s = socket ?: return
        if (!connected || s.isClosed) return
        try {
            synchronized(writeLock) { PeerProtocol.write(s.getOutputStream(), next) }
        } catch (t: Throwable) {
            if (running.get() && connected) {
                Log.e(TAG, "Peer send failed", t)
                recoverDataPath("Send failed: ${errorText(t)}")
            }
        }
    }

    @Synchronized private fun cleanupDataPath() {
        connected = false
        socketConnected.set(false)
        clientConnectStarted.set(false)
        endpointResolveBusy.set(false)
        hostAcceptStarted.set(false)
        networkRequestedAtMs = 0L
        latestFrame.set(null)

        try { socket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        socket = null
        serverSocket = null

        networkCallback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        networkCallback = null
    }

    private fun recoverDataPath(reason: String) {
        if (!running.get()) return
        val wasConnected = connected
        cleanupDataPath()
        if (wasConnected) safeCallback { callbacks.onDisconnected(reason) }
        status("$reason — retrying automatically…")

        if (!recoveryScheduled.compareAndSet(false, true)) return
        main.postDelayed({
            recoveryScheduled.set(false)
            if (!running.get() || connected) return@postDelayed
            val peer = currentPeer ?: return@postDelayed
            if (hostRole) {
                ensureHostNetwork(peer)
                announceNdp(peer)
            } else {
                scheduleJoinHandshake(0L)
            }
        }, RECOVERY_DELAY_MS)
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
        if (missingPeerPermissions().isNotEmpty()) return
        if (!capabilities().rttAvailable || !rangingBusy.compareAndSet(false, true)) return
        try {
            val request = RangingRequest.Builder()
                .addWifiAwarePeer(peer)
                .setRttBurstSize(8)
                .build()
            manager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    rangingBusy.set(false)
                    val error = "framework failure code $code"
                    if (error != lastRangingError) {
                        lastRangingError = error
                        status("Wi-Fi RTT unavailable: $error — continuing with fused alignment")
                    }
                }

                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rangingBusy.set(false)
                    lastRangingError = ""
                    val result = results.firstOrNull { it.status == RangingResult.STATUS_SUCCESS } ?: return
                    val samples = result.numSuccessfulMeasurements
                    val std = if (samples >= 2) result.distanceStdDevMm / 1000f else Float.NaN
                    val distance = result.distanceMm / 1000f
                    safeCallback { callbacks.onRange(distance, std, samples) }
                    sendRange(distance, std, samples)
                }
            })
        } catch (t: Throwable) {
            rangingBusy.set(false)
            val error = errorText(t)
            Log.e(TAG, "Wi-Fi RTT start failed", t)
            if (error != lastRangingError) {
                lastRangingError = error
                status("Wi-Fi RTT failed: $error — continuing with fused alignment")
            }
        }
    }

    private fun missingPeerPermissions(): List<String> {
        val missing = ArrayList<String>(3)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += "Nearby Wi-Fi devices"
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "Location"
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "Precise location"
        }
        return missing
    }

    private fun status(text: String) = safeCallback { callbacks.onTransportStatus(text) }

    private fun reportError(prefix: String, t: Throwable) {
        Log.e(TAG, prefix, t)
        status("$prefix: ${errorText(t)}")
    }

    private inline fun safeCallback(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun errorText(t: Throwable): String {
        val parts = ArrayList<String>(3)
        var current: Throwable? = t
        repeat(3) {
            val c = current ?: return@repeat
            val item = buildString {
                append(c.javaClass.simpleName)
                c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
            if (item !in parts) parts += item
            current = c.cause
        }
        return parts.joinToString(" <- ").ifBlank { t.javaClass.name }
    }

    private fun normalizeRoom(code: String) =
        code.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(8).ifBlank { "ROOM01" }

    private fun safeToken(value: String) =
        value.replace('|', '_').replace('\n', ' ').replace('\r', ' ').trim().take(32)

    private fun psk(code: String) = "Spatial-${normalizeRoom(code)}-V5"

    companion object {
        private const val SERVICE = "spatialnomap.v5"
        private const val TAG = "SpatialAware"
        private const val NDP_REQUEST_TIMEOUT_MS = 8_000L
        private const val RECOVERY_DELAY_MS = 650L
        private const val ENDPOINT_RESOLVE_ATTEMPTS = 36
        private const val ENDPOINT_RESOLVE_SLEEP_MS = 100L
    }
}
