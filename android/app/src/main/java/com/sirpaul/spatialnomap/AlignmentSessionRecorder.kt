package com.sirpaul.spatialnomap

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Rolling on-device flight recorder for physical alignment debugging.
 *
 * Every direct connection gets its own session directory. Wire events are written
 * to events.ndjson and registration frames to a compact binary frames.spv6 stream
 * with image, pose, intrinsics, metric geometry, burst tags and sensor data.
 */
object AlignmentSessionRecorder {
    private const val KEEP_SESSIONS = 6
    private const val MAX_RECORDED_FRAMES = 200
    private const val FRAME_MAGIC = 0x53524636 // SRF6

    private var root: File? = null
    private var sessionDir: File? = null
    private var eventsFile: File? = null
    private var frameOut: DataOutputStream? = null
    private var startedAtMs = 0L
    private var frameCount = 0
    private var localIdentity = ""
    private var peerIdentity = ""
    private var connectionSerial = 0

    @Synchronized fun init(context: Context) {
        val base = context.getExternalFilesDir("alignment_sessions")
            ?: File(context.filesDir, "alignment_sessions")
        base.mkdirs()
        root = base
        prune()
    }

    @Synchronized fun observe(direction: WorldVizBus.Direction, message: WireMessage) {
        if (message is WireMessage.Hello && direction == WorldVizBus.Direction.OUT) {
            localIdentity = message.username
            // A new outgoing Hello is emitted exactly when a new direct TCP socket
            // attaches. Rotate after any prior traffic so each hardware test is a
            // self-contained replayable directory.
            if (sessionDir != null && (frameCount > 0 || System.currentTimeMillis() - startedAtMs > 1_500L)) {
                endCurrentSession()
            }
        }
        ensureSession(direction, message)
        val dir = sessionDir ?: return
        val now = System.currentTimeMillis()
        val line = buildString {
            append('{')
            append("\"t\":").append(now).append(',')
            append("\"elapsedMs\":").append(SystemClock.elapsedRealtime()).append(',')
            append("\"dir\":\"").append(if (direction == WorldVizBus.Direction.OUT) "out" else "in").append("\",")
            append("\"type\":\"").append(typeName(message)).append('\"')
            when (message) {
                is WireMessage.Hello -> {
                    append(",\"user\":\"").append(json(message.username)).append("\"")
                    append(",\"device\":\"").append(json(message.deviceModel)).append("\"")
                }
                is WireMessage.Frame -> {
                    append(",\"tsNs\":").append(message.frame.timestampNs)
                    append(",\"burst\":").append(message.frame.burstId)
                    append(",\"seq\":").append(message.frame.burstSequence)
                    append(",\"metric\":").append(message.frame.metricPoints.size)
                    append(",\"jpegBytes\":").append(
                        runCatching { Base64.decode(message.frame.jpegBase64, Base64.DEFAULT).size }.getOrDefault(0),
                    )
                }
                is WireMessage.Poi -> {
                    append(",\"id\":").append(message.id)
                    append(",\"owner\":\"").append(json(message.owner)).append("\"")
                    append(",\"p\":[")
                    append(message.pointWorld.getOrElse(0) { 0f }).append(',')
                    append(message.pointWorld.getOrElse(1) { 0f }).append(',')
                    append(message.pointWorld.getOrElse(2) { 0f }).append(']')
                }
                is WireMessage.Quality -> {
                    append(",\"ready\":").append(message.ready)
                    append(",\"confidence\":").append(message.confidence)
                    append(",\"stable\":").append(message.stableCount)
                    append(",\"inliers\":").append(message.transformInliers)
                    append(",\"reproj\":").append(message.transformMedianReprojectionPx)
                    append(",\"source\":\"").append(json(message.transformSource)).append("\"")
                    append(",\"hasTransform\":").append(message.senderFromPeer != null)
                }
                is WireMessage.Range -> {
                    append(",\"distanceM\":").append(message.distanceM)
                    append(",\"stdM\":").append(message.stdDevM)
                    append(",\"samples\":").append(message.samples)
                }
                is WireMessage.ResetAlignment -> append(",\"reason\":\"").append(json(message.reason)).append("\"")
                WireMessage.ClearPoi -> Unit
            }
            append('}')
        }
        runCatching { eventsFile?.appendText(line + "\n") }
        if (message is WireMessage.Frame && frameCount < MAX_RECORDED_FRAMES) {
            writeFrame(direction, message.frame)
        }
        if (message is WireMessage.ResetAlignment) {
            runCatching { File(dir, "RESET_${now}.txt").writeText(message.reason) }
        }
    }

    @Synchronized fun latestSessionPath(): String? = sessionDir?.absolutePath

    @Synchronized fun close() {
        endCurrentSession()
    }

    private fun ensureSession(direction: WorldVizBus.Direction, message: WireMessage) {
        if (message is WireMessage.Hello) {
            if (direction == WorldVizBus.Direction.OUT) localIdentity = message.username else peerIdentity = message.username
        }
        if (sessionDir != null) return
        if (message !is WireMessage.Hello && localIdentity.isBlank() && peerIdentity.isBlank()) return
        val base = root ?: return
        startedAtMs = System.currentTimeMillis()
        connectionSerial += 1
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date(startedAtMs))
        val dir = File(base, "session-$stamp-${connectionSerial.toString().padStart(2, '0')}")
        dir.mkdirs()
        sessionDir = dir
        eventsFile = File(dir, "events.ndjson")
        frameCount = 0
        frameOut = DataOutputStream(
            BufferedOutputStream(FileOutputStream(File(dir, "frames.spv6"), true), 256 * 1024),
        )
        runCatching {
            File(dir, "README.txt").writeText(
                "Spatial Sync alignment flight recorder\n" +
                    "events.ndjson = wire/registration timeline\n" +
                    "quality.ndjson = exact alignment gate snapshots\n" +
                    "frames.spv6 = replayable registration frames (local+remote)\n" +
                    "shared-world.voxels = accumulated metric shared map\n" +
                    "Protocol generation = 6\n",
            )
        }
        prune()
    }

    private fun endCurrentSession() {
        runCatching { frameOut?.flush() }
        runCatching { frameOut?.close() }
        frameOut = null
        sessionDir = null
        eventsFile = null
        frameCount = 0
        startedAtMs = 0L
        peerIdentity = ""
    }

    private fun writeFrame(direction: WorldVizBus.Direction, frame: CapturedFrame) {
        val out = frameOut ?: return
        runCatching {
            out.writeInt(FRAME_MAGIC)
            out.writeByte(if (direction == WorldVizBus.Direction.OUT) 0 else 1)
            out.writeLong(frame.timestampNs)
            out.writeLong(frame.burstId)
            out.writeInt(frame.burstSequence)
            repeat(3) { out.writeFloat(frame.pose.t.getOrElse(it) { 0f }) }
            repeat(4) { out.writeFloat(frame.pose.q.getOrElse(it) { if (it == 3) 1f else 0f }) }
            with(frame.intrinsics) {
                out.writeFloat(fx)
                out.writeFloat(fy)
                out.writeFloat(cx)
                out.writeFloat(cy)
                out.writeInt(width)
                out.writeInt(height)
            }
            val jpeg = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
            out.writeInt(jpeg.size)
            out.write(jpeg)
            out.writeInt(frame.metricPoints.size)
            frame.metricPoints.forEach { p -> repeat(5) { out.writeFloat(p.getOrElse(it) { 0f }) } }
            val s = frame.sensors
            out.writeLong(s.elapsedRealtimeNs)
            out.writeFloat(s.headingDeg)
            out.writeFloat(s.pitchDeg)
            out.writeFloat(s.rollDeg)
            out.writeFloat(s.orientationQuality)
            out.writeDouble(s.latitudeDeg)
            out.writeDouble(s.longitudeDeg)
            out.writeDouble(s.altitudeM)
            out.writeFloat(s.horizontalAccuracyM)
            out.writeFloat(s.verticalAccuracyM)
            out.writeFloat(s.pressureHpa)
            repeat(3) { out.writeFloat(s.gyroRadS.getOrElse(it) { Float.NaN }) }
            frameCount += 1
            if (frameCount % 4 == 0) out.flush()
        }
    }

    private fun typeName(message: WireMessage): String = when (message) {
        is WireMessage.Hello -> "hello"
        is WireMessage.Frame -> "frame"
        is WireMessage.Poi -> if (message.owner.startsWith("AUTO:CAR:")) "vehicle" else "poi"
        WireMessage.ClearPoi -> "clear"
        is WireMessage.Range -> "range"
        is WireMessage.Quality -> if (message.senderFromPeer != null) "transform" else "quality"
        is WireMessage.ResetAlignment -> "reset"
    }

    private fun json(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun prune() {
        val base = root ?: return
        val sessions = base.listFiles { file -> file.isDirectory && file.name.startsWith("session-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        sessions.drop(KEEP_SESSIONS).forEach { runCatching { it.deleteRecursively() } }
    }
}
