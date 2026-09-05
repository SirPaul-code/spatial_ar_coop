package com.sirpaul.spatialnomap

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Handler
import android.os.Looper

class WifiAwareRttRanger(
    private val context: Context,
    private val network: NetworkClient,
    private val status: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var peer: PeerHandle? = null
    private var running = false
    private var rangingBusy = false

    private val aware: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val rtt: WifiRttManager? = context.getSystemService(WifiRttManager::class.java)

    fun start(role: String) {
        stop()
        val hasAware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val hasRtt = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        if (!hasAware || !hasRtt || aware == null || rtt == null) {
            status("direct Wi-Fi RTT unavailable (Aware=$hasAware RTT=$hasRtt); vision path still works")
            return
        }
        if (!aware.isAvailable || !rtt.isAvailable) {
            status("Wi-Fi Aware/RTT hardware exists but is currently unavailable")
            return
        }
        running = true
        status("starting Wi-Fi Aware ${role.uppercase()} / peer RTT")
        try {
            aware.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    if (role.uppercase() == "A") publish(session) else subscribe(session)
                }

                override fun onAttachFailed() {
                    status("Wi-Fi Aware attach failed")
                }
            }, handler)
        } catch (se: SecurityException) {
            status("Wi-Fi Aware permission missing: ${se.message}")
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        publishSession?.close(); publishSession = null
        subscribeSession?.close(); subscribeSession = null
        awareSession?.close(); awareSession = null
        peer = null
        rangingBusy = false
    }

    private fun publish(session: WifiAwareSession) {
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setRangingEnabled(true)
            .build()
        session.publish(cfg, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSession = session
                status("A publishing Wi-Fi Aware responder; B can range directly")
            }

            override fun onSessionConfigFailed() { status("Wi-Fi Aware publish config failed") }
        }, handler)
    }

    private fun subscribe(session: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder()
            .setServiceName(SERVICE)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
            .setMaxDistanceMm(100_000)
            .build()
        session.subscribe(cfg, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSession = session
                status("B searching for A over Wi-Fi Aware")
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                peer = peerHandle
                status("B discovered A; starting peer-to-peer RTT")
                rangeNow()
            }

            override fun onServiceDiscoveredWithinRange(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?,
                distanceMm: Int,
            ) {
                peer = peerHandle
                network.sendRange(distanceMm / 1000f, Float.NaN, 1)
                rangeNow()
            }

            override fun onSessionConfigFailed() { status("Wi-Fi Aware subscribe config failed") }
        }, handler)
    }

    private fun rangeNow() {
        if (!running || rangingBusy) return
        val p = peer ?: return
        val manager = rtt ?: return
        if (!manager.isAvailable) {
            scheduleNext()
            return
        }
        rangingBusy = true
        try {
            val request = RangingRequest.Builder().addWifiAwarePeer(p).build()
            manager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    rangingBusy = false
                    status("RTT ranging failure code=$code")
                    scheduleNext()
                }

                override fun onRangingResults(results: List<RangingResult>) {
                    rangingBusy = false
                    val result = results.firstOrNull { it.status == RangingResult.STATUS_SUCCESS }
                    if (result != null) {
                        val n = result.numSuccessfulMeasurements
                        val std = if (n >= 2) result.distanceStdDevMm / 1000f else Float.NaN
                        network.sendRange(result.distanceMm / 1000f, std, n)
                        status("direct RTT ${"%.2f".format(result.distanceMm / 1000f)} m (n=$n)")
                    }
                    scheduleNext()
                }
            })
        } catch (se: SecurityException) {
            rangingBusy = false
            status("RTT permission missing: ${se.message}")
        } catch (t: Throwable) {
            rangingBusy = false
            status("RTT error: ${t.message}")
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (running) handler.postDelayed({ rangeNow() }, 1200L)
    }

    companion object { private const val SERVICE = "spatialnomap" }
}
