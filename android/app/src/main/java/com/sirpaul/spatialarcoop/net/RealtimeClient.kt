package com.sirpaul.spatialarcoop.net

import android.os.Handler
import android.os.Looper
import com.sirpaul.spatialarcoop.data.SpatialTrack
import com.sirpaul.spatialarcoop.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

interface RealtimeListener {
    fun onConnectionState(connected: Boolean, detail: String)
    fun onTracks(tracks: List<SpatialTrack>, replaceSnapshot: Boolean = false)
    fun onTracksExpired(trackKeys: List<String>)
    fun onManualMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long)
    fun onPresence(clientId: String, action: String, role: String)
}

class RealtimeClient(
    private val serverUrl: String,
    private val apiToken: String,
    private val mapId: String,
    private val clientId: String,
    private val role: String,
    private val logger: FileLogger,
    private val listener: RealtimeListener,
    private val client: OkHttpClient = MapApiClient.sharedClient
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val explicitlyClosed = AtomicBoolean(true)
    private val generation = AtomicLong(0L)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var reconnectAttempt = 0

    fun connect() {
        explicitlyClosed.set(false)
        socket?.cancel()
        socket = null
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        val activeGeneration = generation.incrementAndGet()
        openSocket(activeGeneration)
    }

    private fun openSocket(activeGeneration: Long) {
        if (explicitlyClosed.get() || generation.get() != activeGeneration) return
        val wsBase = when {
            serverUrl.startsWith("https://") -> "wss://${serverUrl.removePrefix("https://")}"
            serverUrl.startsWith("http://") -> "ws://${serverUrl.removePrefix("http://")}"
            else -> "ws://${serverUrl.trimEnd('/')}"
        }.trimEnd('/')
        val request = Request.Builder()
            .url("$wsBase/ws?mapId=${encode(mapId)}&clientId=${encode(clientId)}&role=${encode(role)}")
            .apply { if (apiToken.isNotBlank()) header("Authorization", "Bearer $apiToken") }
            .build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            private fun current(webSocket: WebSocket): Boolean =
                generation.get() == activeGeneration && socket === webSocket && !explicitlyClosed.get()

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!current(webSocket)) {
                    webSocket.close(1000, "stale connection")
                    return
                }
                reconnectAttempt = 0
                logger.info("Realtime connected", mapOf("mapId" to mapId, "role" to role))
                listener.onConnectionState(true, "connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current(webSocket)) return
                runCatching { handleMessage(JSONObject(text)) }
                    .onFailure {
                        logger.warn(
                            "Realtime message parse failed",
                            mapOf("error" to it.message, "payload" to text.take(512))
                        )
                    }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!current(webSocket)) return
                socket = null
                listener.onConnectionState(false, "$code $reason")
                scheduleReconnect(activeGeneration, "closed $code")
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (!current(webSocket)) return
                socket = null
                logger.warn(
                    "Realtime connection failed",
                    mapOf("mapId" to mapId, "error" to throwable.message, "status" to response?.code)
                )
                listener.onConnectionState(false, throwable.message ?: "connection failed")
                scheduleReconnect(activeGeneration, throwable.message ?: "failure")
            }
        })
        socket = newSocket
    }

    private fun scheduleReconnect(activeGeneration: Long, reason: String) {
        if (explicitlyClosed.get() || generation.get() != activeGeneration) return
        val delay = min(15_000L, 750L shl min(reconnectAttempt++, 4))
        logger.debug("Realtime reconnect scheduled", mapOf("delayMs" to delay, "reason" to reason))
        mainHandler.postDelayed({ openSocket(activeGeneration) }, delay)
    }

    private fun handleMessage(json: JSONObject) {
        when (json.optString("type")) {
            "welcome" -> {
                val tracks = parseTracks(json.optJSONArray("tracks"), null)
                listener.onTracks(tracks, replaceSnapshot = true)
            }
            "track_batch" -> {
                val source = json.optString("sourceId", "unknown")
                listener.onTracks(parseTracks(json.optJSONArray("tracks"), source))
            }
            "tracks_expired" -> {
                val array = json.optJSONArray("trackKeys") ?: JSONArray()
                listener.onTracksExpired(buildList {
                    for (index in 0 until array.length()) add(array.optString(index))
                })
            }
            "manual_marker" -> {
                val marker = json.optJSONObject("marker") ?: return
                listener.onManualMarker(
                    marker.optString("id"),
                    marker.optString("label", "marker"),
                    marker.floatArray("position", 3),
                    marker.optLong("expiresAtMs", System.currentTimeMillis() + 60_000)
                )
            }
            "presence" -> listener.onPresence(
                json.optString("clientId"),
                json.optString("action"),
                json.optString("role")
            )
            "error" -> logger.warn(
                "Realtime server error",
                mapOf("code" to json.optString("code"), "message" to json.optString("message"))
            )
        }
    }

    /**
     * Publish the complete set of currently-active tracks for this source phone. The explicit
     * replaceSource flag lets new servers immediately expire IDs that disappeared from the local
     * tracker while remaining backward compatible with older servers/clients.
     */
    fun sendTracks(sequence: Long, tracks: Collection<SpatialTrack>): Boolean {
        val payload = JSONObject()
            .put("type", "track_batch")
            .put("sequence", sequence)
            .put("sentAtMs", System.currentTimeMillis())
            .put("replaceSource", true)
            .put("tracks", JSONArray(tracks.map(SpatialTrack::toJson)))
        return socket?.send(payload.toString()) == true
    }

    fun sendClientPose(position: FloatArray, rotation: FloatArray, tracking: String): Boolean {
        val payload = JSONObject()
            .put("type", "client_pose")
            .put(
                "pose",
                JSONObject()
                    .put("position", JSONArray(position.map { it.toDouble() }))
                    .put("rotation", JSONArray(rotation.map { it.toDouble() }))
                    .put("tracking", tracking)
                    .put("atMs", System.currentTimeMillis())
            )
        return socket?.send(payload.toString()) == true
    }

    fun sendManualMarker(
        id: String,
        label: String,
        position: FloatArray,
        ttlMs: Long = 60_000
    ): Boolean {
        val payload = JSONObject()
            .put("type", "manual_marker")
            .put(
                "marker",
                JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("position", JSONArray(position.map { it.toDouble() }))
                    .put("expiresAtMs", System.currentTimeMillis() + ttlMs)
            )
        return socket?.send(payload.toString()) == true
    }

    fun sendStatus(state: String, detail: String = "") {
        socket?.send(
            JSONObject().put("type", "status").put("state", state).put("detail", detail).toString()
        )
    }

    fun close() {
        explicitlyClosed.set(true)
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        val current = socket
        socket = null
        current?.close(1000, "activity closed")
    }

    private fun parseTracks(array: JSONArray?, source: String?): List<SpatialTrack> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { json ->
                    val track = SpatialTrack.fromJson(json, source)
                    // The server intentionally broadcasts complete room state, including the
                    // sender's own batch. The source phone already renders its precise detector
                    // bbox, so suppress its network echo to avoid a duplicate amber spatial box.
                    if (track.sourceId != clientId) add(track)
                }
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONObject.floatArray(name: String, size: Int): FloatArray {
        val array = optJSONArray(name) ?: JSONArray()
        return FloatArray(size) { index -> array.optDouble(index, 0.0).toFloat() }
    }
}
