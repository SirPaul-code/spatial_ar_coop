package com.sirpaul.showme

import com.sirpaul.spatialnomap.CapturedFrame
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

/** Immutable snapshots: no ARCore Frame/Image/Anchor crosses the GL-thread boundary. */
data class StrokeSnapshot(val id: String, val tool: String, val color: String, val label: String,
    val points: List<FloatArray>, val verified: Boolean)

data class FramePacket(val id: Long, val epoch: Int, val capture: CapturedFrame, val jpeg: ByteArray,
    val rotation: Int, val capturedMs: Long, val strokes: List<StrokeSnapshot>) {
    fun metadata(): JSONObject = JSONObject().put("id", id).put("epoch", epoch)
        .put("width", capture.intrinsics.width).put("height", capture.intrinsics.height)
        .put("rotation", rotation).put("ageMs", (monotonicMs() - capturedMs).coerceAtLeast(0L))
        .put("depthPoints", capture.metricPoints.size).put("annotations", projectStrokes(capture, rotation, strokes))
}

fun projectStrokes(frame: CapturedFrame, rotation: Int, strokes: List<StrokeSnapshot>): JSONArray {
    val out = JSONArray()
    for (stroke in strokes) {
        val points = JSONArray()
        var valid = true
        for (p in stroke.points) {
            val raw = ShowMeGeometry.project(frame, p)
            if (raw == null) { valid = false; break }
            val xy = ShowMeGeometry.rawToUpright(raw[0] / frame.intrinsics.width, raw[1] / frame.intrinsics.height, rotation)
            if (xy.any { !it.isFinite() }) { valid = false; break }
            points.put(JSONArray().put(xy[0].toDouble()).put(xy[1].toDouble()))
        }
        if (valid) out.put(JSONObject().put("id", stroke.id).put("tool", stroke.tool).put("color", stroke.color)
            .put("label", stroke.label).put("verified", stroke.verified).put("points", points))
    }
    return out
}

class FrameHistory(private val clock: () -> Long = ::monotonicMs) {
    private val frames = LinkedHashMap<Long, FramePacket>()
    private var pinned: FramePacket? = null
    private var pinDeadline = 0L
    @Synchronized fun add(frame: FramePacket) {
        frames[frame.id] = frame
        while (frames.size > 40) frames.remove(frames.keys.first())
    }
    @Synchronized fun latest(): FramePacket? = frames.values.lastOrNull()
    @Synchronized fun get(id: Long): FramePacket? {
        val held = pinned
        if (held?.id == id && clock() <= pinDeadline) return held
        return frames[id]?.takeIf { clock() - it.capturedMs in 0L..8_000L }
    }
    @Synchronized fun pin(id: Long): Boolean {
        // Repeating a freeze request must not extend one historical image forever.
        if (pinned?.id == id) return clock() <= pinDeadline
        val frame = get(id) ?: return false
        pinned = frame; pinDeadline = clock() + 60_000L
        return true
    }
    @Synchronized fun unpin() { pinned = null; pinDeadline = 0L }
    @Synchronized fun clear() { frames.clear(); unpin() }
}

enum class SpatialLeaseAction { FREEZE, UNFREEZE }
data class SpatialLeaseCommand(
    val action: SpatialLeaseAction,
    val frameId: Long,
    val epoch: Int,
    val answer: CompletableFuture<Boolean>,
)

class ShowMeSession(private val clock: () -> Long = ::monotonicMs) {
    val frames = FrameHistory(clock)
    val telemetry = FrameTelemetry()
    val videoFrames = VideoDepthHistory(clock)
    data class Command(val body: JSONObject, val answer: CompletableFuture<JSONObject>, val prepared: PreparedStroke? = null)
    private val commands = ArrayDeque<Command>()
    private val spatialLeases = ArrayDeque<SpatialLeaseCommand>()
    private val random = SecureRandom()
    @Volatile var active = false
        private set
    @Volatile var paused = false
    @Volatile var tracking = false
    @Volatile var trackingMessage = "Scanning the environment"
    @Volatile var epoch = 1
        private set
    @Volatile var token = ""
        private set
    @Volatile var helperName = ""
        private set
    @Volatile var annotationCount = 0
    @Volatile var voiceEnabled = false
    @Volatile var voiceState = "OFF"
    @Volatile var videoState = "STARTING"
    @Volatile var captureFps = 0f
    @Volatile var secure = false
    @Volatile var internet = false
    @Volatile var hostName = "Camera owner"
    @Volatile var stableArEnabled = BuildConfig.STABLE_AR_ENABLED
    @Volatile var stableArDiagnostics = "{}"
    private var helperId = ""
    private var helperSeenMs = 0L
    private var expiresMs = 0L
    private val applied = LinkedHashMap<String, JSONObject>()

    @Synchronized fun begin() {
        end()
        epoch += 1
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        expiresMs = clock() + 3_600_000L
        active = true; paused = false
    }
    @Synchronized fun end() {
        active = false; token = ""; helperId = ""; helperName = ""; helperSeenMs = 0L
        voiceState = "OFF"; frames.clear(); videoFrames.clear(); applied.clear()
        while (true) {
            val command = commands.pollFirst() ?: break
            command.answer.complete(failure("SESSION_ENDED", "The camera owner ended this session."))
        }
        // Reject old lease requests first, then schedule a fresh owner-thread release. This also
        // covers server expiry/revocation paths that call state.end() without an Activity callback.
        failSpatialLeases()
        requestSpatialUnfreezeLocked()
    }
    @Synchronized fun resetWorld() {
        epoch += 1; frames.clear(); videoFrames.clear(); applied.clear()
        while (true) {
            val command = commands.pollFirst() ?: break
            command.answer.complete(failure("WORLD_CHANGED", "The AR camera session changed. Please try again."))
        }
        failSpatialLeases()
        requestSpatialUnfreezeLocked()
    }
    @Synchronized fun authorized(value: String): Boolean = active && clock() < expiresMs && token.isNotBlank() &&
        MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), value.toByteArray(Charsets.UTF_8))
    @Synchronized fun join(id: String, name: String): Boolean {
        if (!active || clock() >= expiresMs || !id.matches(Regex("[A-Za-z0-9-]{8,80}"))) return false
        if (helperId.isNotBlank() && helperId != id && clock() - helperSeenMs < 15_000L) return false
        helperId = id; helperName = name.filter { !it.isISOControl() }.take(32).ifBlank { "Helper" }
        helperSeenMs = clock()
        return true
    }
    @Synchronized fun heartbeat(id: String): Boolean {
        if (!active || clock() >= expiresMs || id != helperId || id.isBlank()) return false
        helperSeenMs = clock(); return true
    }
    @Synchronized fun touchHelper() { if(helperId.isNotBlank()&&active)helperSeenMs=clock() }
    @Synchronized fun leave(id: String) {
        if (id == helperId) {
            helperId = ""; helperName = ""; helperSeenMs = 0L; frames.unpin()
            requestSpatialUnfreezeLocked()
        }
    }
    @Synchronized fun hasHelper(): Boolean = active && helperId.isNotBlank() && clock() - helperSeenMs < 15_000L
    @Synchronized fun info(): JSONObject = JSONObject().put("active", active && clock() < expiresMs).put("paused", paused)
        .put("tracking", tracking).put("trackingMessage", trackingMessage).put("epoch", epoch)
        .put("hostName", hostName).put("helper", if (hasHelper()) helperName else "")
        .put("annotations", annotationCount).put("voiceEnabled", voiceEnabled).put("voiceState", voiceState)
        .put("secure", secure).put("internet",internet).put("version", BuildConfig.VERSION_NAME).put("timing",telemetry.snapshot())
        .put("videoTransport", "WEBRTC").put("videoState", videoState).put("captureFps", captureFps.toDouble()).put("targetFps", 30)
        .put("spatialMode", if(stableArEnabled) "STABLE_AR" else "LEGACY")
        .put("stableAr", if(stableArEnabled) runCatching { JSONObject(stableArDiagnostics) }.getOrElse { JSONObject() } else JSONObject())
    @Synchronized fun previous(id: String): JSONObject? = applied[id]
    @Synchronized fun remember(id: String, result: JSONObject) {
        applied[id] = result
        while (applied.size > 256) applied.remove(applied.keys.first())
    }
    /** Admission and draining share the same lock; session end cannot race a queue counter. */
    @Synchronized fun submit(body: JSONObject, prepared: PreparedStroke? = null): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        if (!active || clock() >= expiresMs || commands.size >= 32) {
            result.complete(failure("BUSY", "Session is unavailable. Please retry.")); return result
        }
        commands.addLast(Command(body, result, prepared)); return result
    }
    @Synchronized fun poll(): Command? = commands.pollFirst()

    /** Session/network workers request leases; only ShowMeRenderer executes them on the AR owner thread. */
    @Synchronized fun requestSpatialFreeze(frameId: Long, requestedEpoch: Int): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        if (!stableArEnabled) { result.complete(true); return result }
        if (!active || paused || !tracking || requestedEpoch != epoch || frameId <= 0L || spatialLeases.size >= 8) {
            result.complete(false); return result
        }
        spatialLeases.addLast(SpatialLeaseCommand(SpatialLeaseAction.FREEZE, frameId, requestedEpoch, result))
        return result
    }
    @Synchronized fun requestSpatialUnfreeze(): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        if (!stableArEnabled) { result.complete(true); return result }
        if (spatialLeases.size >= 8) { result.complete(false); return result }
        spatialLeases.addLast(SpatialLeaseCommand(SpatialLeaseAction.UNFREEZE, -1L, epoch, result))
        return result
    }
    @Synchronized fun pollSpatialLease(): SpatialLeaseCommand? = spatialLeases.pollFirst()
    @Synchronized fun setSpatialMode(enabled: Boolean) {
        stableArEnabled = enabled
        stableArDiagnostics = "{}"
        frames.clear(); videoFrames.clear(); applied.clear()
        failSpatialLeases()
    }
    private fun requestSpatialUnfreezeLocked() {
        if (!stableArEnabled || spatialLeases.size >= 8) return
        spatialLeases.addLast(SpatialLeaseCommand(SpatialLeaseAction.UNFREEZE, -1L, epoch, CompletableFuture()))
    }
    private fun failSpatialLeases() {
        while (true) {
            val lease = spatialLeases.pollFirst() ?: break
            lease.answer.complete(false)
        }
    }

    companion object {
        val TOOLS = setOf("pin", "arrow", "draw", "circle")
        val COLORS = setOf("#8ff1c6", "#ffce73", "#ff7897", "#8dbaff")
        fun failure(code: String, message: String): JSONObject = JSONObject().put("ok", false).put("code", code).put("message", message)
        fun validateDraw(body: JSONObject): String? {
            if (!body.optString("requestId").matches(Regex("[A-Za-z0-9-]{8,80}"))) return "Invalid request id"
            val tool = body.optString("tool")
            if (tool !in TOOLS || body.optString("color") !in COLORS) return "Unknown drawing tool or color"
            if (body.optString("label").length > 64) return "Label is too long"
            val points = body.optJSONArray("points") ?: return "Missing points"
            if (points.length() !in 1..96 || (tool == "pin" && points.length() != 1) || (tool != "pin" && points.length() < 2)) return "Invalid point count"
            for (i in 0 until points.length()) {
                val p = points.optJSONArray(i) ?: return "Invalid point"
                if (p.length() != 2) return "Invalid point"
                for (axis in 0..1) {
                    val n = p.optDouble(axis, Double.NaN)
                    if (!n.isFinite() || n !in 0.0..1.0) return "Point outside the camera image"
                }
            }
            return null
        }
    }
}
