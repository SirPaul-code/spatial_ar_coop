package com.sirpaul.spatialnomap

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class NetworkClient(private val callbacks: Callbacks) {
    interface Callbacks {
        fun onNetworkStatus(text: String)
        fun onRemoteTarget(pointWb: FloatArray?, detail: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val sessionId = UUID.randomUUID().toString()

    @Volatile private var socket: WebSocket? = null
    @Volatile var connected: Boolean = false
        private set

    fun connect(baseUrl: String, room: String, role: String) {
        disconnect()
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}" 
            baseUrl.startsWith("http://") -> "ws://${baseUrl.removePrefix("http://")}" 
            baseUrl.startsWith("wss://") || baseUrl.startsWith("ws://") -> baseUrl
            else -> "ws://$baseUrl"
        }.trimEnd('/')
        val url = "$wsBase/ws/${room.trim()}/${role.uppercase()}"
        callbacks.onNetworkStatus("connecting $url")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                callbacks.onNetworkStatus("connected as ${role.uppercase()}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "hello" -> callbacks.onNetworkStatus("server hello / room ${o.optString("room")}")
                        "alignment" -> {
                            if (o.optBoolean("ok")) {
                                callbacks.onNetworkStatus(
                                    "aligned ${o.optString("method")} | inliers ${o.optInt("inliers")} | conf ${"%.2f".format(o.optDouble("confidence"))}"
                                )
                            } else callbacks.onNetworkStatus("alignment pending: ${o.optString("reason")}")
                        }
                        "remote_target" -> {
                            val p = o.getJSONArray("point_wb")
                            val arr = floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat())
                            val a = o.optJSONObject("alignment")
                            val detail = if (a != null) "${a.optString("method")} / ${a.optInt("inliers")} inliers" else "remote"
                            callbacks.onRemoteTarget(arr, detail)
                        }
                        "clear_target" -> callbacks.onRemoteTarget(null, "cleared")
                        "diagnostic" -> callbacks.onNetworkStatus("${o.optString("level")}: ${o.optString("message")}")
                        "range" -> callbacks.onNetworkStatus("RTT ${"%.2f".format(o.optDouble("distance_m"))} m")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "bad websocket message", t)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                callbacks.onNetworkStatus("closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                callbacks.onNetworkStatus("network error: ${t.message}")
            }
        })
    }

    fun disconnect() {
        connected = false
        socket?.close(1000, "reconnect")
        socket = null
    }

    fun sendFrame(frame: CapturedFrame) {
        val o = JSONObject()
            .put("type", "frame")
            .put("session_id", sessionId)
            .put("timestamp_ns", frame.timestampNs)
            .put("pose", poseJson(frame.pose))
            .put("intrinsics", intrinsicsJson(frame.intrinsics))
            .put("jpeg_b64", frame.jpegBase64)
        val points = JSONArray()
        for (p in frame.metricPoints) {
            points.put(JSONArray().put(p[0]).put(p[1]).put(p[2]).put(p[3]).put(p[4]))
        }
        o.put("metric_points", points)
        send(o)
    }

    fun sendTarget(pointWa: FloatArray, selectedImagePixel: FloatArray?) {
        val o = JSONObject()
            .put("type", "target")
            .put("session_id", sessionId)
            .put("point_wa", JSONArray().put(pointWa[0]).put(pointWa[1]).put(pointWa[2]))
            .put("client_time_ns", System.nanoTime())
        if (selectedImagePixel != null) {
            o.put("selected_pixel", JSONArray().put(selectedImagePixel[0]).put(selectedImagePixel[1]))
        }
        send(o)
    }

    fun sendRange(distanceM: Float, stdDevM: Float, successful: Int) {
        send(
            JSONObject()
                .put("type", "range")
                .put("source", "wifi_aware_rtt")
                .put("distance_m", distanceM)
                .put("stddev_m", stdDevM)
                .put("successful_measurements", successful)
                .put("client_time_ns", System.nanoTime())
        )
    }

    fun clearTarget() = send(JSONObject().put("type", "clear_target"))

    private fun send(o: JSONObject) {
        if (socket?.send(o.toString()) != true) {
            callbacks.onNetworkStatus("not connected")
        }
    }

    private fun poseJson(p: PosePacket) = JSONObject()
        .put("t", JSONArray().put(p.t[0]).put(p.t[1]).put(p.t[2]))
        .put("q", JSONArray().put(p.q[0]).put(p.q[1]).put(p.q[2]).put(p.q[3]))

    private fun intrinsicsJson(k: IntrinsicsPacket) = JSONObject()
        .put("fx", k.fx).put("fy", k.fy).put("cx", k.cx).put("cy", k.cy)
        .put("width", k.width).put("height", k.height)

    companion object { private const val TAG = "SpatialNoMapNet" }
}
