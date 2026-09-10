package com.sirpaul.showme

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Same-origin local session API. Tokens are in the URL fragment, then Authorization headers. */
class LocalServer(context: Context, address: String, port: Int, private val state: ShowMeSession,
    private val voice: RtcVoice, private val tls: LocalTls? = null) : NanoHTTPD(address, port) {
    private val assets = context.applicationContext.assets
    private val inFlight = AtomicInteger()
    private var mutationWindow = 0L
    private var mutationCount = 0
    init { if (tls != null) makeSecure(tls.sockets, null) }

    override fun serve(request: IHTTPSession): Response {
        if (inFlight.incrementAndGet() > 12) {
            inFlight.decrementAndGet()
            return json(ShowMeSession.failure("BUSY", "Too many requests"), Response.Status.SERVICE_UNAVAILABLE)
        }
        try {
            val path = request.uri
            if (!path.startsWith("/api/")) {
                if (request.method != Method.GET) return json(ShowMeSession.failure("METHOD", "GET required"), Response.Status.METHOD_NOT_ALLOWED)
                val file = when (path) {
                    "/", "/index.html" -> "index.html"
                    "/app.js" -> "app.js"
                    "/geometry.mjs" -> "geometry.mjs"
                    "/live-video.mjs" -> "live-video.mjs"
                    "/style.css" -> "style.css"
                    else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
                val mime = when {
                    file.endsWith("css") -> "text/css; charset=utf-8"
                    file.endsWith("js") || file.endsWith("mjs") -> "text/javascript; charset=utf-8"
                    else -> "text/html; charset=utf-8"
                }
                val bytes = assets.open("showme/$file").use { it.readBytes() }
                return harden(newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()))
            }
            val token = request.headers["authorization"]?.removePrefix("Bearer ") ?: ""
            if (!state.authorized(token)) return json(ShowMeSession.failure("SESSION_ENDED", "This invitation is invalid or has expired."), Response.Status.UNAUTHORIZED)
            val origin = request.headers["origin"]
            if (origin != null && URI(origin).rawAuthority != request.headers["host"])
                return json(ShowMeSession.failure("ORIGIN", "Cross-origin requests are not allowed."), Response.Status.FORBIDDEN)
            if (path == "/api/join" && request.method == Method.POST) {
                val body = body(request)
                if (!state.join(body.optString("viewerId"), body.optString("name", "Helper")))
                    return json(ShowMeSession.failure("HELPER_BUSY", "A helper is already connected. Wait for them to leave."), Response.Status.CONFLICT)
                return json(state.info().put("ok", true))
            }
            val viewer = request.headers["x-showme-viewer"] ?: ""
            if (!state.heartbeat(viewer)) return json(ShowMeSession.failure("REJOIN", "Reconnect to the session."), Response.Status.CONFLICT)
            if (request.method == Method.GET) return when (path) {
                "/api/state" -> json(state.info())
                "/api/frame" -> json(ShowMeSession.failure("VIDEO_REQUIRED", "Use the WebRTC video connection."), Response.Status.GONE)
                "/api/certificate" -> {
                    val cert = tls?.certificate ?: return json(ShowMeSession.failure("NO_TLS", "This session uses local HTTP."))
                    harden(newFixedLengthResponse(Response.Status.OK, "application/pkix-cert", ByteArrayInputStream(cert), cert.size.toLong())).apply {
                        addHeader("Content-Disposition", "attachment; filename=showme-local.cer")
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
            if (request.method != Method.POST) return json(ShowMeSession.failure("METHOD", "POST required"), Response.Status.METHOD_NOT_ALLOWED)
            val body = body(request)
            return when (path) {
                "/api/freeze" -> {
                    val id = body.optLong("frameId", -1L)
                    val epoch = body.optInt("epoch", -1)
                    val observed = state.videoFrames.get(id)
                    if (observed == null || epoch != state.epoch || observed.epoch != epoch || state.paused || !state.tracking)
                        return json(ShowMeSession.failure("STALE_FRAME", "The video frame is no longer placeable. Resume live view and try again."))
                    val jpeg = java.util.Base64.getDecoder().decode(body.optString("jpeg"))
                    val packet = observed.materialize(jpeg)
                    if (!state.active || state.paused || state.epoch != epoch)
                        return json(ShowMeSession.failure("WORLD_CHANGED", "The camera session changed. Please resume live video."))
                    state.frames.add(packet)
                    val ok = state.frames.pin(packet.id)
                    json(if (ok) packet.metadata().put("ok", true) else ShowMeSession.failure("STALE_FRAME", "Resume live view and retry."))
                }
                "/api/resume" -> { state.frames.unpin(); json(JSONObject().put("ok", true)) }
                "/api/draw" -> {
                    if (!allowMutation()) return json(ShowMeSession.failure("RATE_LIMIT", "Please wait a moment before drawing again."))
                    val action = body.optString("action", "draw")
                    if (action !in setOf("draw", "undo", "clear", "remove")) return json(ShowMeSession.failure("INVALID_ACTION", "Unknown action"))
                    if (!body.optString("requestId").matches(Regex("[A-Za-z0-9-]{8,80}"))) return json(ShowMeSession.failure("INVALID_ID", "Invalid request id"))
                    if (action == "draw") ShowMeSession.validateDraw(body)?.let {
                        return json(ShowMeSession.failure("INVALID_DRAWING", it))
                    }
                    val answer = state.submit(body)
                    try { json(answer.get(4, TimeUnit.SECONDS)) } catch (_: java.util.concurrent.TimeoutException) {
                        val error = ShowMeSession.failure("CAMERA_PAUSED", "The camera owner must keep ShowMe open. Please retry.")
                        // Cancel timed-out work so it cannot create a ghost drawing after resuming.
                        answer.complete(error); json(error)
                    }
                }
                "/api/call", "/api/voice" -> {
                    val sdp = body.optString("sdp")
                    if (sdp.length !in 20..60_000 || !sdp.startsWith("v=0")) return json(ShowMeSession.failure("INVALID_SDP", "Invalid audio offer"))
                    try {
                        val answer = voice.answer(sdp).get(12, TimeUnit.SECONDS)
                        json(JSONObject().put("ok", true).put("sdp", answer).put("video", voice.videoLayout()))
                    } catch (_: Exception) {
                        voice.disconnect(); json(ShowMeSession.failure("VOICE_FAILED", "The video call could not connect. Keep ShowMe open and retry on the same network."))
                    }
                }
                "/api/voice-stop" -> { voice.setMuted(true); json(JSONObject().put("ok", true)) }
                "/api/call-stop" -> { voice.disconnect(); json(JSONObject().put("ok", true)) }
                "/api/leave" -> { state.leave(viewer); voice.disconnect(); json(JSONObject().put("ok", true)) }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (_: IllegalArgumentException) {
            return json(ShowMeSession.failure("INVALID_REQUEST", "Malformed request"), Response.Status.BAD_REQUEST)
        } catch (_: org.json.JSONException) {
            return json(ShowMeSession.failure("INVALID_JSON", "Malformed request"), Response.Status.BAD_REQUEST)
        } catch (_: Exception) {
            return json(ShowMeSession.failure("UNAVAILABLE", "Local session temporarily unavailable"), Response.Status.SERVICE_UNAVAILABLE)
        } finally { inFlight.decrementAndGet() }
    }
    private fun body(request: IHTTPSession): JSONObject {
        require(request.headers["content-type"]?.startsWith("application/json") == true)
        val length = request.headers["content-length"]?.toLongOrNull() ?: throw IllegalArgumentException("Content-Length required")
        require(length in 2..2_100_000L)
        val files = HashMap<String, String>()
        request.parseBody(files)
        val content = files["postData"] ?: throw IllegalArgumentException("Missing JSON")
        require(content.length <= 2_100_000)
        return JSONObject(content)
    }
    @Synchronized private fun allowMutation(): Boolean {
        val now = monotonicMs()
        if (now - mutationWindow >= 1000L) { mutationWindow = now; mutationCount = 0 }
        return ++mutationCount <= 12
    }
    private fun json(value: JSONObject, status: Response.Status = Response.Status.OK): Response =
        harden(newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()))
    private fun harden(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store, max-age=0")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Permissions-Policy", "camera=(), microphone=(self), geolocation=()")
        addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
    }
}
