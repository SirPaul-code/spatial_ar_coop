package com.sirpaul.spatialnomap

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.pow

/**
 * Optional BLE RSSI fallback ranging. RSSI is deliberately NOT treated as a
 * precision measurement. Wi-Fi RTT wins whenever it is available; BLE only
 * supplies a weak distance prior when RTT is missing/stale.
 */
@SuppressLint("MissingPermission")
class BlePeerRanger(
    private val context: Context,
    private val callback: Callback,
) {
    interface Callback {
        fun onBleRange(distanceM: Float, stdDevM: Float, rssiDbm: Int)
        fun onBleStatus(text: String)
    }

    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val serviceUuid = ParcelUuid(SERVICE_UUID)
    private val ownToken: String by lazy { stableToken() }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var activeRoom = ""
    private var running = false
    private var filteredRssi = Float.NaN
    private var lastReportAtMs = 0L

    fun isPermissionReady(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start(roomCode: String) {
        stop()
        val room = normalizeRoom(roomCode)
        if (room.isBlank()) return
        if (!isPermissionReady()) {
            callback.onBleStatus("BLE ranging optional — Nearby Bluetooth permission not granted")
            return
        }

        val adapter = bluetooth?.adapter
        if (adapter == null || !adapter.isEnabled) {
            callback.onBleStatus("BLE ranging optional — Bluetooth is off")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            callback.onBleStatus("BLE ranging optional — advertising unsupported")
            return
        }

        val adv = adapter.bluetoothLeAdvertiser
        val scan = adapter.bluetoothLeScanner
        if (adv == null || scan == null) {
            callback.onBleStatus("BLE ranging optional — scanner/advertiser unavailable")
            return
        }

        activeRoom = room
        advertiser = adv
        scanner = scan
        filteredRssi = Float.NaN
        running = true

        val payload = "$room|$ownToken".toByteArray(StandardCharsets.US_ASCII)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .addServiceData(serviceUuid, payload)
            .setIncludeTxPowerLevel(true)
            .build()

        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure {
                callback.onBleStatus("BLE advertise failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
            }

        val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scan.startScan(listOf(filter), scanSettings, scanCallback) }
            .onSuccess { callback.onBleStatus("BLE fallback ranging active") }
            .onFailure {
                callback.onBleStatus("BLE scan failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
            }
    }

    fun stop() {
        if (!running && advertiser == null && scanner == null) return
        running = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { scanner?.stopScan(scanCallback) }
        advertiser = null
        scanner = null
        activeRoom = ""
        filteredRssi = Float.NaN
        lastReportAtMs = 0L
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            callback.onBleStatus("BLE advertise rejected ($errorCode); Wi-Fi RTT/vision remain active")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val serviceData = result.scanRecord?.getServiceData(serviceUuid) ?: return
            val text = runCatching { serviceData.toString(StandardCharsets.US_ASCII) }.getOrNull() ?: return
            val parts = text.split('|', limit = 2)
            if (parts.size != 2 || normalizeRoom(parts[0]) != activeRoom || parts[1] == ownToken) return

            val rssi = result.rssi
            if (rssi !in -120..-15) return
            filteredRssi = if (filteredRssi.isFinite()) {
                filteredRssi * 0.72f + rssi * 0.28f
            } else {
                rssi.toFloat()
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastReportAtMs < 350L) return
            lastReportAtMs = now

            val advertisedTx = result.txPower.takeIf { it in -100..20 } ?: DEFAULT_TX_POWER_DBM
            val distance = rssiToDistanceM(filteredRssi, advertisedTx.toFloat())
            // BLE RSSI multipath error is intentionally modeled very loosely.
            val std = max(0.85f, distance * 0.70f)
            callback.onBleRange(distance, std, filteredRssi.toInt())
        }

        override fun onScanFailed(errorCode: Int) {
            callback.onBleStatus("BLE scan rejected ($errorCode); Wi-Fi RTT/vision remain active")
        }
    }

    private fun rssiToDistanceM(rssi: Float, txPower: Float): Float {
        val exponent = (txPower - rssi) / (10f * PATH_LOSS_EXPONENT)
        return 10.0.pow(exponent.toDouble()).toFloat().coerceIn(0.08f, 25f)
    }

    private fun normalizeRoom(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }.take(8)

    private fun stableToken(): String {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().ifBlank { context.packageName }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(4).joinToString("") { "%02X".format(it) }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("62e5a812-7bb4-4c78-8a46-3b5880fcb4f1")
        private const val DEFAULT_TX_POWER_DBM = -59
        private const val PATH_LOSS_EXPONENT = 2.25f
    }
}
