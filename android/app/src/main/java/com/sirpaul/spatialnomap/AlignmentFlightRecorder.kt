package com.sirpaul.spatialnomap

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-overhead physical-device flight recorder for alignment debugging and offline
 * regression datasets. It deliberately records enough evidence to reproduce a bad
 * session without relying on logcat or a chat screenshot.
 *
 * Files are written under Android external app storage:
 *   Android/data/com.sirpaul.spatialnomap/files/alignment-sessions/<session>/
 *
 * The directory contains events.ndjson plus a bounded sample of registration JPEGs
 * and their metric [u,v,x,y,z] supports. Recording is always-on for this research
 * build; storage is bounded per session and old sessions are pruned automatically.
 */
class AlignmentFlightRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val root = File(appContext.getExternalFilesDir(null), "alignment-sessions")
    private val sessionDir: File
    private val eventsFile: File
    private val frameSerial = AtomicInteger(0)
    @Volatile private var closed = false
    @Volatile private var lastQualityAtMs = 0L

    init {
        root.mkdirs()
        pruneOldSessions()
        val stamp = System.currentTimeMillis()
        sessionDir = File(root, "session-$stamp-${android.os.Build.MODEL.replace(' ', '_')}")
        sessionDir.mkdirs()
        eventsFile = File(sessionDir, "events.ndjson")
        event("session_start", mapOf(
            "device" to android.os.Build.MODEL,
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "elapsed_ms" to SystemClock.elapsedRealtime(),
        ))
    }

    fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (closed) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        executor.execute {
            if (closed) return@execute
            val payload = LinkedHashMap<String, Any?>()
            payload["at_ms"] = now
            payload["elapsed_ms"] = elapsed
            payload["type"] = type
            payload.putAll(fields)
            eventsFile.appendText(toJson(payload) + "\n")
        }
    }

    fun quality(q: AlignmentCoordinator.Quality) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastQualityAtMs < QUALITY_PERIOD_MS) return
        lastQualityAtMs = now
        event("quality", mapOf(
            "confidence" to q.confidence,
            "inliers" to q.inliers,
            "correspondences" to q.correspondences,
            "reprojection_px" to finiteOrNull(q.medianReprojectionPx),
            "coverage" to q.imageCoverage,
            "stable_count" to q.stableCount,
            "local_ready" to q.localReady,
            "peer_ready" to q.peerReady,
            "verified" to q.peerTransformVerified,
            "range_m" to q.rangeM,
            "range_delta_m" to q.rangeDeltaM,
            "range_source" to q.rangeSource,
            "gravity_deg" to finiteOrNull(q.gravityTiltDeg),
            "fusion_source" to q.fusionSource,
            "kf_local" to q.keyframesLocal,
            "kf_remote" to q.keyframesRemote,
            "metric_pairs" to q.metricPairs,
            "metric_inliers" to q.metricInliers,
            "metric_residual_m" to finiteOrNull(q.medianMetricResidualM),
            "peer_scene_median_m" to finiteOrNull(q.peerAgreementMedianM),
            "peer_scene_p90_m" to finiteOrNull(q.peerAgreementP90M),
            "peer_scene_rotation_deg" to finiteOrNull(q.peerAgreementRotationDeg),
        ))
    }

    fun frame(frame: CapturedFrame, locked: Boolean) {
        if (closed) return
        val index = frameSerial.incrementAndGet()
        // Preserve dense evidence during acquisition, then sample more sparsely.
        val stride = if (locked) LOCKED_FRAME_STRIDE else ACQUIRE_FRAME_STRIDE
        if (index % stride != 0 || index > MAX_FRAMES_PER_SESSION) return
        executor.execute {
            if (closed) return@execute
            val base = "frame-%04d".format(Locale.US, index)
            runCatching {
                val jpg = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
                FileOutputStream(File(sessionDir, "$base.jpg")).use { it.write(jpg) }
                File(sessionDir, "$base.metric.csv").bufferedWriter().use { out ->
                    out.appendLine("u,v,world_x,world_y,world_z")
                    frame.metricPoints.forEach { p ->
                        if (p.size >= 5) out.appendLine("${p[0]},${p[1]},${p[2]},${p[3]},${p[4]}")
                    }
                }
                File(sessionDir, "$base.pose.json").writeText(
                    toJson(mapOf(
                        "timestamp_ns" to frame.timestampNs,
                        "t" to frame.pose.t.toList(),
                        "q" to frame.pose.q.toList(),
                        "fx" to frame.intrinsics.fx,
                        "fy" to frame.intrinsics.fy,
                        "cx" to frame.intrinsics.cx,
                        "cy" to frame.intrinsics.cy,
                        "width" to frame.intrinsics.width,
                        "height" to frame.intrinsics.height,
                        "metric_count" to frame.metricPoints.size,
                    )),
                )
            }
        }
    }

    fun vehicle(id: Long, label: String, confidence: Float, point: FloatArray, isRemote: Boolean) {
        event("vehicle", mapOf(
            "id" to id,
            "label" to label,
            "confidence" to confidence,
            "remote" to isRemote,
            "x" to point.getOrNull(0),
            "y" to point.getOrNull(1),
            "z" to point.getOrNull(2),
        ))
    }

    fun target(id: Long, owner: String, point: FloatArray, isRemote: Boolean) {
        event("target", mapOf(
            "id" to id,
            "owner" to owner,
            "remote" to isRemote,
            "x" to point.getOrNull(0),
            "y" to point.getOrNull(1),
            "z" to point.getOrNull(2),
        ))
    }

    fun close() {
        if (closed) return
        event("session_end")
        closed = true
        executor.shutdown()
    }

    fun directoryPath(): String = sessionDir.absolutePath

    private fun pruneOldSessions() {
        val sessions = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        sessions.drop(MAX_SESSION_DIRECTORIES - 1).forEach { runCatching { it.deleteRecursively() } }
    }

    private fun finiteOrNull(value: Double): Double? = if (value.isFinite()) value else null

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        is String -> "\"${escape(value)}\""
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]") { it.toString() }
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]") { it.toString() }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k.toString())}\":${toJson(v)}"
        }
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    companion object {
        private const val QUALITY_PERIOD_MS = 180L
        private const val ACQUIRE_FRAME_STRIDE = 2
        private const val LOCKED_FRAME_STRIDE = 8
        private const val MAX_FRAMES_PER_SESSION = 240
        private const val MAX_SESSION_DIRECTORIES = 6
    }
}
