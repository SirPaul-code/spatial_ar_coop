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
import android.net.wifi.ScanResult
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
 * The v7 transport deliberately uses a deterministic two-phase handshake instead of
 * continuously pre-arming/toggling responder modes:
 *
 *   subscriber -- JOIN --> publisher
 *   publisher registers a peer-specific NDP responder
 *   publisher -- READY --> subscriber
 *   subscriber starts the matching NDP initiator
 *   NDP -> IPv6/TCP -> HELLO
 *
 * On Samsung devices this avoids the old race where the subscriber requested an NDP
 * while the publisher's any-peer responder was unavailable/being recycled. A failed
 * NDP returns to JOIN/READY signalling first; only repeated failures rebuild the whole
 * Wi-Fi Aware attach session. Every NDP attempt is epoch-tagged so stale Android
 * callbacks cannot tear down a newer successful link.
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
    private val awareRecoveryScheduled = AtomicBoolean(false)
    private val messageId = AtomicInteger(100)
    private val dataPathEpoch = AtomicInteger(0)
    private val latestFrame = AtomicReference<CapturedFrame?>(null)
    private val framePumpRunning = AtomicBoolean(false)
    private val writeLock = Any()

    private var networkRequestedAtMs = 0L
    private var hostResponderStartedAtMs = 0L
    private var joinAttempt = 0
    private var dataPathFailureCount = 0
    private var lastRangingError = ""
    private var rttBackoffUntilMs = 0L

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
        attachAware {
            publish(
                session = it,
                allowInstant = instantAwareSupported(),
            )
        }
    }

    fun scanRooms(name: String) {
        startCommon(name)
        hostRole = false
        attachAware { subscribe(it, allowInstant = instantAwareSupported()) }
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
        joinAttempt = 0
        dataPathFailureCount = 0
        status("Connecting to $peerUsername / $normalized…")

        // Do NOT request the NDP yet. The host first registers its peer-specific
        // responder and explicitly tells us that it is ready.
        scheduleJoinHandshake(0L)
    }

    fun sendFrame(frame: CapturedFrame) {
        if (!connected) return
        latestFrame.set(frame)
        scheduleFramePump()
    }

    /**
     * Reference frames that define a physical manual target must never be replaced by
     * latest-frame backpressure. They share the control FIFO with the following POI.
     */
    fun sendReferenceFrame(frame: CapturedFrame) = sendControl(WireMessage.Frame(frame))

    private fun scheduleFramePump() {
        if (!connected || !framePumpRunning.compareAndSet(false, true)) return
        writer.execute {
            try {
                val next = latestFrame.getAndSet(null)
                if (next != null && connected) writeMessage(WireMessage.Frame(next))
            } finally {
                framePumpRunning.set(false)
                if (connected && latestFrame.get() != null) scheduleFramePump()
            }
        }
    }

    fun sendPoi(
        id: Long,
        owner: String,
        pointWorld: FloatArray,
        createdAtMs: Long = System.currentTimeMillis(),
    ) = sendControl(WireMessage.Poi(id, owner, pointWorld.copyOf(3), createdAtMs))

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
        main.removeCallbacks(awareRecoveryRunnable)
        recoveryScheduled.set(false)
        awareRecoveryScheduled.set(false)
        cleanupDataPath()
        rangingBusy.set(false)
        latestFrame.set(null)
        lastRangingError = ""
        dataPathFailureCount = 0
        rttBackoffUntilMs = 0L

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
        hostResponderStartedAtMs = 0L

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
                    awareRecoveryScheduled.set(false)
                    try {
                        action(session)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Wi-Fi Aware setup interrupted", t)
                        try { session.close() } catch (_: Throwable) {}
                        awareSession = null
                        recoverAwareSession("Wi-Fi Aware setup interrupted")
                    }
                }

                override fun onAttachFailed() {
                    if (running.get()) recoverAwareSession("Wi-Fi Aware attach was interrupted")
                }

                override fun onAwareSessionTerminated() {
                    if (running.get()) recoverAwareSession("Android restarted the Wi-Fi Aware session")
                }
            }, main)
        } catch (t: Throwable) {
            Log.e(TAG, "Wi-Fi Aware attach threw", t)
            if (running.get()) {
                recoverAwareSession("Wi-Fi Aware attach was interrupted: ${errorText(t)}")
            } else {
                throw IllegalStateException("Wi-Fi Aware start failed: ${errorText(t)}", t)
            }
        }
    }

    private fun publish(session: WifiAwareSession, allowInstant: Boolean) {
        val info = "$DISCOVERY_VERSION|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8)
        val builder = PublishConfig.Builder()
            .setServiceName(SERVICE)
            .setServiceSpecificInfo(info)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            // Discovery ranging is intentionally disabled. RTT is optional and starts
            // only after TCP is established, so NAN resources stay focused on NDP.
            .setRangingEnabled(false)
        if (Build.VERSION.SDK_INT >= 33 && allowInstant) {
            builder.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_5_GHZ)
        }

        try {
            session.publish(builder.build(), object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    status("Room $roomCode ready — waiting for nearby user")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    try {
                        val parts = message.toString(StandardCharsets.UTF_8).split('|', limit = 3)
                        if (parts.size < 3 || parts[0] != JOIN_TOKEN || normalizeRoom(parts[1]) != roomCode) return

                        val previousPeer = currentPeer
                        currentPeer = peerHandle
                        peerUsername = parts[2].ifBlank { "Peer" }.take(32)
                        status("$peerUsername joined — preparing direct Wi-Fi path…")

                        val peerChanged = previousPeer != null && previousPeer != peerHandle
                        ensureHostResponder(peerHandle, force = peerChanged)
                        signalHostReady(peerHandle)
                    } catch (t: Throwable) {
                        reportError("Peer join handling failed", t)
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowInstant && running.get()) {
                        status("Instant Wi-Fi Aware mode rejected — retrying standard discovery…")
                        publish(session = awareSession ?: return, allowInstant = false)
                    } else if (running.get()) {
                        recoverAwareSession("Wi-Fi Aware publish configuration failed")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowInstant) {
                status("Instant Wi-Fi Aware mode threw — retrying standard discovery…")
                publish(session, allowInstant = false)
            } else {
                recoverAwareSession("Wi-Fi Aware publish failed: ${errorText(t)}")
            }
        }
    }

    private fun subscribe(session: WifiAwareSession, allowInstant: Boolean) {
        val builder = SubscribeConfig.Builder()
            .setServiceName(SERVICE)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
        if (Build.VERSION.SDK_INT >= 33 && allowInstant) {
            builder.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_5_GHZ)
        }

        try {
            session.subscribe(builder.build(), object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    status(if (roomCode.isBlank()) "Scanning nearby Spatial rooms…" else "Restoring nearby Spatial peer…")
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
                        val parts = message.toString(StandardCharsets.UTF_8).split('|', limit = 3)
                        if (parts.size < 2 || parts[0] != READY_TOKEN || normalizeRoom(parts[1]) != roomCode) return
                        if (connected || hostRole) return
                        currentPeer = peerHandle
                        status("Host ready — establishing direct Wi-Fi data path…")
                        requestClientNetwork(peerHandle)
                    } catch (t: Throwable) {
                        reportError("Peer data-path message failed", t)
                    }
                }

                override fun onSessionConfigFailed() {
                    if (allowInstant && running.get()) {
                        status("Instant Wi-Fi Aware scan rejected — retrying standard discovery…")
                        subscribe(session = awareSession ?: return, allowInstant = false)
                    } else if (running.get()) {
                        recoverAwareSession("Wi-Fi Aware discovery session was interrupted")
                    }
                }
            }, main)
        } catch (t: Throwable) {
            if (allowInstant) {
                status("Instant Wi-Fi Aware scan threw — retrying standard discovery…")
                subscribe(session, allowInstant = false)
            } else {
                recoverAwareSession("Wi-Fi Aware discovery was interrupted: ${errorText(t)}")
            }
        }
    }

    private fun registerRoom(peer: PeerHandle, info: ByteArray?, distanceM: Float?) {
        try {
            val parts = info?.toString(StandardCharsets.UTF_8)?.split('|', limit = 3) ?: return
            if (parts.size < 3 || parts[0] != DISCOVERY_VERSION) return
            val code = normalizeRoom(parts[1])
            val room = NearbyRoom(
                code = code,
                username = parts[2].ifBlank { "Nearby user" }.take(32),
                distanceM = distanceM,
            )
            rooms[code] = room
            roomPeers[code] = peer
            safeCallback { callbacks.onRoomFound(room) }

            // Reconnect only through JOIN -> READY -> NDP. Never start an NDP just
            // because discovery produced a new PeerHandle.
            if (!hostRole && roomCode.isNotBlank() && code == roomCode && !connected) {
                currentPeer = peer
                peerUsername = room.username
                joinAttempt = 0
                status("Nearby peer restored — negotiating a fresh direct link…")
                scheduleJoinHandshake(0L)
            }
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
                status("Direct link timed out — returning to peer handshake…")
                cleanupDataPath()
                dataPathFailureCount += 1
                if (dataPathFailureCount >= MAX_DATAPATH_FAILURES_BEFORE_REATTACH) {
                    recoverAwareSession("Direct link repeatedly timed out")
                    return
                }
            }

            try {
                session.sendMessage(
                    peer,
                    messageId.incrementAndGet(),
                    "$JOIN_TOKEN|$roomCode|${safeToken(username)}".toByteArray(StandardCharsets.UTF_8),
                )
                joinAttempt += 1
                if (joinAttempt == 1) status("Requesting peer-specific direct path…")
            } catch (t: Throwable) {
                Log.w(TAG, "JOIN retry failed", t)
            }

            if (running.get() && !connected) {
                main.postDelayed(this, if (joinAttempt < 10) JOIN_FAST_RETRY_MS else JOIN_SLOW_RETRY_MS)
            }
        }
    }

    @Synchronized private fun ensureHostResponder(peer: PeerHandle, force: Boolean) {
        if (!running.get() || connected || !hostRole) return
        val now = SystemClock.elapsedRealtime()
        val responderHealthy = networkCallback != null && serverSocket != null &&
            now - hostResponderStartedAtMs < HOST_RESPONDER_REFRESH_MS
        if (!force && responderHealthy) return

        cleanupDataPath()
        armHostResponder(peer)
    }

    private fun armHostResponder(peer: PeerHandle) {
        if (!running.get() || connected || !hostRole) return
        if (networkCallback != null || serverSocket != null) return
        val publish = publishSession ?: return

        val ss = try {
            ServerSocket(0).apply { reuseAddress = true }
        } catch (t: Throwable) {
            recoverDataPath("Host socket create failed: ${errorText(t)}")
            return
        }
        serverSocket = ss

        val epoch = dataPathEpoch.incrementAndGet()
        hostResponderStartedAtMs = SystemClock.elapsedRealtime()
        try {
            val spec = WifiAwareNetworkSpecifier.Builder(publish, peer)
                .setPskPassphrase(psk(roomCode))
                .setPort(ss.localPort)
                .setTransportProtocol(TCP_PROTOCOL)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(spec)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isCurrentEpoch(epoch)) return
                    status("Direct Wi-Fi path ready — accepting peer socket…")
                }

                override fun onLost(network: Network) {
                    if (isCurrentEpoch(epoch) && running.get()) {
                        recoverDataPath(if (connected) "Direct Wi-Fi link lost" else "Host Wi-Fi Aware path lost", epoch)
                    }
                }

                override fun onUnavailable() {
                    if (isCurrentEpoch(epoch) && running.get()) {
                        recoverDataPath("Host peer-specific Wi-Fi path unavailable", epoch)
                    }
                }
            }

            networkCallback = callback
            networkRequestedAtMs = hostResponderStartedAtMs
            connectivity.requestNetwork(request, callback)
            startHostAccept(ss, epoch)
        } catch (t: Throwable) {
            Log.e(TAG, "Host responder setup failed", t)
            recoverDataPath("Host data path failed: ${errorText(t)}", epoch)
        }
    }

    private fun startHostAccept(ss: ServerSocket, epoch: Int) {
        if (!hostAcceptStarted.compareAndSet(false, true)) return
        io.execute {
            try {
                val accepted = ss.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                }
                attachSocket(accepted, epoch)
            } catch (t: Throwable) {
                if (isCurrentEpoch(epoch)) {
                    hostAcceptStarted.set(false)
                    if (running.get() && !connected) {
                        main.post { recoverDataPath("Host socket failed: ${errorText(t)}", epoch) }
                    }
                }
            }
        }
    }

    private fun signalHostReady(peer: PeerHandle) {
        val publish = publishSession ?: return
        if (!running.get() || connected || !hostRole || networkCallback == null || serverSocket == null) return
        val epoch = dataPathEpoch.get()
        main.postDelayed({
            if (!running.get() || connected || !hostRole || !isCurrentEpoch(epoch)) return@postDelayed
            try {
                publish.sendMessage(
                    peer,
                    messageId.incrementAndGet(),
                    "$READY_TOKEN|$roomCode|$epoch".toByteArray(StandardCharsets.UTF_8),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Host-ready signal failed; JOIN retry will request it again", t)
            }
        }, HOST_READY_SIGNAL_DELAY_MS)
    }

    private fun requestClientNetwork(peer: PeerHandle) {
        if (!running.get() || connected || hostRole) return
        if (clientConnectStarted.get()) return
        if (!clientConnectStarted.compareAndSet(false, true)) return

        val subscribe = subscribeSession ?: run {
            clientConnectStarted.set(false)
            return
        }

        val epoch = dataPathEpoch.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
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
                    if (!isCurrentEpoch(epoch)) return
                    status("Direct Wi-Fi link up — resolving peer endpoint…")
                    resolveClientEndpoint(network, epoch)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!isCurrentEpoch(epoch)) return
                    resolveClientEndpoint(network, epoch)
                }

                override fun onLost(network: Network) {
                    if (isCurrentEpoch(epoch) && running.get()) recoverDataPath("Direct Wi-Fi link lost", epoch)
                }

                override fun onUnavailable() {
                    if (isCurrentEpoch(epoch) && running.get()) recoverDataPath("Direct Wi-Fi data path unavailable", epoch)
                }
            }
            networkCallback = callback
            networkRequestedAtMs = now
            connectivity.requestNetwork(request, callback)
        } catch (t: Throwable) {
            clientConnectStarted.set(false)
            Log.e(TAG, "Client data path failed", t)
            recoverDataPath("Client data path failed: ${errorText(t)}", epoch)
        }
    }

    private fun resolveClientEndpoint(network: Network, epoch: Int) {
        if (!isCurrentEpoch(epoch) || !running.get() || connected || !endpointResolveBusy.compareAndSet(false, true)) return
        io.execute {
            try {
                repeat(ENDPOINT_RESOLVE_ATTEMPTS) {
                    if (!isCurrentEpoch(epoch) || !running.get() || connected) return@execute
                    val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                    val info = caps?.transportInfo as? WifiAwareNetworkInfo
                    val address = info?.peerIpv6Addr
                    val port = info?.port ?: 0
                    if (address != null && port > 0) {
                        if (!socketConnected.compareAndSet(false, true)) return@execute
                        try {
                            val peerSocket = network.socketFactory.createSocket(address, port).apply {
                                tcpNoDelay = true
                                keepAlive = true
                            }
                            attachSocket(peerSocket, epoch)
                            return@execute
                        } catch (t: Throwable) {
                            socketConnected.set(false)
                            Log.w(TAG, "Client socket connect attempt failed", t)
                        }
                    }
                    Thread.sleep(ENDPOINT_RESOLVE_SLEEP_MS)
                }

                if (isCurrentEpoch(epoch) && running.get() && !connected) {
                    main.post { recoverDataPath("Peer endpoint was not published in time", epoch) }
                }
            } catch (t: Throwable) {
                if (isCurrentEpoch(epoch) && running.get() && !connected) {
                    main.post { recoverDataPath("Peer endpoint resolution failed: ${errorText(t)}", epoch) }
                }
            } finally {
                if (isCurrentEpoch(epoch)) endpointResolveBusy.set(false)
            }
        }
    }

    @Synchronized private fun attachSocket(s: Socket, epoch: Int) {
        if (!running.get() || !isCurrentEpoch(epoch)) {
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
        dataPathFailureCount = 0
        rttBackoffUntilMs = 0L
        networkRequestedAtMs = 0L
        recoveryScheduled.set(false)
        awareRecoveryScheduled.set(false)
        main.removeCallbacks(joinRetryRunnable)
        main.removeCallbacks(rangeRunnable)
        status("Connected directly to $peerUsername — no AP / no server")
        writeMessage(WireMessage.Hello(username, Build.MODEL))
        safeCallback { callbacks.onConnected(peerUsername) }
        io.execute { readLoop(s, epoch) }

        if (!hostRole) scheduleRanging()
    }

    private fun readLoop(s: Socket, epoch: Int) {
        try {
            while (running.get() && connected && socket === s && isCurrentEpoch(epoch) && !s.isClosed) {
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
            if (running.get() && connected && socket === s && isCurrentEpoch(epoch)) {
                recoverDataPath("Peer socket closed", epoch)
            }
        } catch (t: Throwable) {
            if (running.get() && connected && socket === s && isCurrentEpoch(epoch)) {
                Log.e(TAG, "Peer read loop failed", t)
                recoverDataPath("Peer link error: ${errorText(t)}", epoch)
            }
        }
    }

    private fun writeMessage(message: WireMessage) {
        val s = socket ?: return
        if (!connected || s.isClosed) return
        try {
            synchronized(writeLock) { PeerProtocol.write(s.getOutputStream(), message) }
        } catch (t: Throwable) {
            if (running.get() && connected && socket === s) {
                Log.e(TAG, "Peer send failed", t)
                recoverDataPath("Send failed: ${errorText(t)}")
            }
        }
    }

    @Synchronized private fun cleanupDataPath() {
        dataPathEpoch.incrementAndGet()
        connected = false
        socketConnected.set(false)
        clientConnectStarted.set(false)
        endpointResolveBusy.set(false)
        hostAcceptStarted.set(false)
        networkRequestedAtMs = 0L
        hostResponderStartedAtMs = 0L
        latestFrame.set(null)
        main.removeCallbacks(rangeRunnable)
        rangingBusy.set(false)

        try { socket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        socket = null
        serverSocket = null

        networkCallback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        networkCallback = null
    }

    private fun recoverDataPath(reason: String, expectedEpoch: Int? = null) {
        if (!running.get()) return
        if (expectedEpoch != null && !isCurrentEpoch(expectedEpoch)) return

        val wasConnected = connected
        cleanupDataPath()
        if (wasConnected) safeCallback { callbacks.onDisconnected(reason) }
        dataPathFailureCount += 1

        if (dataPathFailureCount >= MAX_DATAPATH_FAILURES_BEFORE_REATTACH) {
            recoverAwareSession("$reason — refreshing nearby session")
            return
        }

        status("$reason — restarting direct handshake…")
        if (!recoveryScheduled.compareAndSet(false, true)) return
        main.postDelayed({
            recoveryScheduled.set(false)
            if (!running.get() || connected) return@postDelayed
            val peer = currentPeer
            if (hostRole) {
                if (peer != null) {
                    ensureHostResponder(peer, force = true)
                    signalHostReady(peer)
                } else {
                    status("Room $roomCode ready — waiting for nearby user")
                }
            } else if (peer != null) {
                // Important: return to discovery signalling. Starting another client
                // NDP before the host has recreated its responder is the old race.
                scheduleJoinHandshake(0L)
            } else {
                recoverAwareSession("Nearby peer handle expired")
            }
        }, RECOVERY_DELAY_MS)
    }

    @Synchronized private fun recoverAwareSession(reason: String) {
        if (!running.get()) return
        val wasConnected = connected
        cleanupDataPath()
        main.removeCallbacks(joinRetryRunnable)
        recoveryScheduled.set(false)

        try { publishSession?.close() } catch (_: Throwable) {}
        try { subscribeSession?.close() } catch (_: Throwable) {}
        try { awareSession?.close() } catch (_: Throwable) {}
        publishSession = null
        subscribeSession = null
        awareSession = null
        currentPeer = null
        rooms.clear()
        roomPeers.clear()
        hostResponderStartedAtMs = 0L
        dataPathFailureCount = 0

        if (wasConnected) safeCallback { callbacks.onDisconnected(reason) }
        status("$reason — restoring direct session…")

        if (awareRecoveryScheduled.compareAndSet(false, true)) {
            main.removeCallbacks(awareRecoveryRunnable)
            main.postDelayed(awareRecoveryRunnable, AWARE_RECOVERY_DELAY_MS)
        }
    }

    private val awareRecoveryRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || connected) {
                awareRecoveryScheduled.set(false)
                return
            }
            val caps = capabilities()
            if (!caps.awareAvailable) {
                status("Wi-Fi Aware is temporarily busy — waiting to restore direct link…")
                main.postDelayed(this, AWARE_RETRY_WHILE_BUSY_MS)
                return
            }

            awareRecoveryScheduled.set(false)
            attachAware { session ->
                if (hostRole) {
                    publish(session = session, allowInstant = instantAwareSupported())
                } else {
                    subscribe(session, allowInstant = instantAwareSupported())
                }
            }
        }
    }

    private fun isCurrentEpoch(epoch: Int): Boolean = epoch == dataPathEpoch.get()

    private fun scheduleRanging() {
        if (running.get() && connected && !hostRole) {
            main.removeCallbacks(rangeRunnable)
            main.post(rangeRunnable)
        }
    }

    private val rangeRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || !connected || hostRole) return
            rangeOnce()
            if (running.get() && connected && !hostRole) main.postDelayed(this, RTT_PERIOD_MS)
        }
    }

    private fun rangeOnce() {
        if (!connected || hostRole) return
        if (SystemClock.elapsedRealtime() < rttBackoffUntilMs) return
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
                    rttBackoffUntilMs = SystemClock.elapsedRealtime() + RTT_BACKOFF_MS
                    val error = "framework code $code"
                    if (error != lastRangingError) {
                        lastRangingError = error
                        status("RTT optional • $error • visual alignment continues")
                    }
                }

                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rangingBusy.set(false)
                    lastRangingError = ""
                    rttBackoffUntilMs = 0L
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
            rttBackoffUntilMs = SystemClock.elapsedRealtime() + RTT_BACKOFF_MS
            val error = errorText(t)
            Log.e(TAG, "Wi-Fi RTT start failed", t)
            if (error != lastRangingError) {
                lastRangingError = error
                status("RTT optional • $error • visual alignment continues")
            }
        }
    }

    private fun instantAwareSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching { aware?.characteristics?.isInstantCommunicationModeSupported == true }.getOrDefault(false)
    }

    private fun missingPeerPermissions(): List<String> {
        val missing = ArrayList<String>(3)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) missing += "Nearby Wi-Fi devices"
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += "Location"
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += "Precise location"
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

    private fun psk(code: String) = "Spatial-${normalizeRoom(code)}-$DISCOVERY_VERSION"

    companion object {
        private const val SERVICE = "spatialnomap.v7"
        private const val DISCOVERY_VERSION = "V7"
        private const val JOIN_TOKEN = "JOIN"
        private const val READY_TOKEN = "READY"
        private const val TAG = "SpatialAware"
        private const val TCP_PROTOCOL = 6

        private const val NDP_REQUEST_TIMEOUT_MS = 7_000L
        private const val HOST_RESPONDER_REFRESH_MS = 9_000L
        private const val HOST_READY_SIGNAL_DELAY_MS = 120L
        private const val JOIN_FAST_RETRY_MS = 450L
        private const val JOIN_SLOW_RETRY_MS = 900L
        private const val RECOVERY_DELAY_MS = 350L
        private const val AWARE_RECOVERY_DELAY_MS = 650L
        private const val AWARE_RETRY_WHILE_BUSY_MS = 1_200L
        private const val MAX_DATAPATH_FAILURES_BEFORE_REATTACH = 4
        private const val ENDPOINT_RESOLVE_ATTEMPTS = 60
        private const val ENDPOINT_RESOLVE_SLEEP_MS = 100L
        private const val RTT_PERIOD_MS = 1_500L
        private const val RTT_BACKOFF_MS = 30_000L
    }
}
