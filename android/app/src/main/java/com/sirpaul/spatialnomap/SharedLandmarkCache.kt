package com.sirpaul.spatialnomap

import android.content.Context
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Persistent local landmark checkpoint for infrastructure-free relocalization.
 *
 * Once a shared transform is verified, both phones already possess a recent local
 * frame and the peer frame that created/validated that world. We persist one strong
 * pair plus the exact localFromRemote transform. If ARCore later restarts with new
 * local origins, RelocalizationEngine can solve each new world back to these cached
 * landmarks and reconstruct the shared transform without a Cloud Anchor.
 */
object SharedLandmarkCache {
    private const val MAGIC = 0x534C4331 // SLC1
    private const val MAX_POINTS = 8000
    private const val MAX_JPEG = 16 * 1024 * 1024
    private const val SAVE_MIN_INTERVAL_MS = 5_000L

    private var file: File? = null
    private var localFrame: CapturedFrame? = null
    private var remoteFrame: CapturedFrame? = null
    private var transform: DoubleArray? = null
    private var localReady = false
    private var peerReady = false
    private var lastSavedAtMs = 0L

    @Synchronized fun init(context: Context) {
        val dir = context.getExternalFilesDir("spatial_landmarks") ?: File(context.filesDir, "spatial_landmarks")
        dir.mkdirs()
        file = File(dir, "latest-shared-landmark.slc1")
    }

    @Synchronized fun observe(direction: WorldVizBus.Direction, message: WireMessage) {
        when (message) {
            is WireMessage.Frame -> {
                if (direction == WorldVizBus.Direction.OUT) localFrame = message.frame
                else remoteFrame = message.frame
            }
            is WireMessage.Quality -> {
                if (direction == WorldVizBus.Direction.OUT) {
                    localReady = message.ready
                    message.senderFromPeer?.takeIf { it.size >= 16 }?.let { transform = it.copyOf(16) }
                } else {
                    peerReady = message.ready
                    // Incoming transform is senderFromPeer. In the local receiver's
                    // coordinates the inverse maps incoming peer world to local.
                    message.senderFromPeer?.takeIf { it.size >= 16 }?.let { incoming ->
                        invertRigid(incoming)?.let { transform = it }
                    }
                }
            }
            is WireMessage.ResetAlignment -> {
                localFrame = null
                remoteFrame = null
                transform = null
                localReady = false
                peerReady = false
            }
            else -> Unit
        }
        maybeSave()
    }

    @Synchronized fun load(): RelocalizationEngine.Checkpoint? {
        val source = file ?: return null
        if (!source.isFile || source.length() <= 0L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(source), 256 * 1024)).use { input ->
                require(input.readInt() == MAGIC) { "bad landmark cache magic" }
                val savedTransform = DoubleArray(16) { input.readDouble() }
                val local = readFrame(input)
                val remote = readFrame(input)
                RelocalizationEngine.Checkpoint(
                    oldLocal = local,
                    oldRemote = remote,
                    oldLocalFromOldRemote = savedTransform,
                )
            }
        }.getOrNull()
    }

    @Synchronized fun clear() {
        localFrame = null
        remoteFrame = null
        transform = null
        localReady = false
        peerReady = false
        lastSavedAtMs = 0L
        runCatching { file?.delete() }
    }

    private fun maybeSave() {
        val local = localFrame ?: return
        val remote = remoteFrame ?: return
        val t = transform ?: return
        if (!localReady || !peerReady || t.size < 16) return
        if (local.metricPoints.size < 24 || remote.metricPoints.size < 24) return
        val now = System.currentTimeMillis()
        if (now - lastSavedAtMs < SAVE_MIN_INTERVAL_MS) return

        val destination = file ?: return
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        val ok = runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp, false), 512 * 1024)).use { out ->
                out.writeInt(MAGIC)
                repeat(16) { out.writeDouble(t[it]) }
                writeFrame(out, local)
                writeFrame(out, remote)
            }
            if (destination.exists()) destination.delete()
            require(tmp.renameTo(destination)) { "could not install landmark checkpoint" }
        }.isSuccess
        if (ok) lastSavedAtMs = now else runCatching { tmp.delete() }
    }

    private fun writeFrame(out: DataOutputStream, frame: CapturedFrame) {
        out.writeLong(frame.timestampNs)
        out.writeLong(frame.burstId)
        out.writeInt(frame.burstSequence)
        repeat(3) { out.writeFloat(frame.pose.t.getOrElse(it) { 0f }) }
        repeat(4) { out.writeFloat(frame.pose.q.getOrElse(it) { if (it == 3) 1f else 0f }) }
        with(frame.intrinsics) {
            out.writeFloat(fx); out.writeFloat(fy); out.writeFloat(cx); out.writeFloat(cy)
            out.writeInt(width); out.writeInt(height)
        }
        val jpeg = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        require(jpeg.size in 1..MAX_JPEG)
        out.writeInt(jpeg.size)
        out.write(jpeg)
        val points = frame.metricPoints.take(MAX_POINTS)
        out.writeInt(points.size)
        points.forEach { p -> repeat(5) { out.writeFloat(p.getOrElse(it) { 0f }) } }
        writeSensors(out, frame.sensors)
    }

    private fun readFrame(input: DataInputStream): CapturedFrame {
        val timestampNs = input.readLong()
        val burstId = input.readLong()
        val burstSequence = input.readInt()
        val pose = PosePacket(
            FloatArray(3) { input.readFloat() },
            FloatArray(4) { input.readFloat() },
        )
        val intrinsics = IntrinsicsPacket(
            input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
            input.readInt(), input.readInt(),
        )
        val jpegSize = input.readInt()
        require(jpegSize in 1..MAX_JPEG)
        val jpeg = ByteArray(jpegSize)
        input.readFully(jpeg)
        val pointCount = input.readInt()
        require(pointCount in 0..MAX_POINTS)
        val points = ArrayList<FloatArray>(pointCount)
        repeat(pointCount) { points += FloatArray(5) { input.readFloat() } }
        return CapturedFrame(
            timestampNs = timestampNs,
            pose = pose,
            intrinsics = intrinsics,
            jpegBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
            metricPoints = points,
            sensors = readSensors(input),
            burstId = burstId,
            burstSequence = burstSequence,
        )
    }

    private fun writeSensors(out: DataOutputStream, s: SensorSnapshot) {
        out.writeLong(s.elapsedRealtimeNs)
        out.writeFloat(s.headingDeg); out.writeFloat(s.pitchDeg); out.writeFloat(s.rollDeg)
        out.writeFloat(s.orientationQuality)
        out.writeDouble(s.latitudeDeg); out.writeDouble(s.longitudeDeg); out.writeDouble(s.altitudeM)
        out.writeFloat(s.horizontalAccuracyM); out.writeFloat(s.verticalAccuracyM); out.writeFloat(s.pressureHpa)
        repeat(3) { out.writeFloat(s.gyroRadS.getOrElse(it) { Float.NaN }) }
    }

    private fun readSensors(input: DataInputStream) = SensorSnapshot(
        elapsedRealtimeNs = input.readLong(),
        headingDeg = input.readFloat(),
        pitchDeg = input.readFloat(),
        rollDeg = input.readFloat(),
        orientationQuality = input.readFloat(),
        latitudeDeg = input.readDouble(),
        longitudeDeg = input.readDouble(),
        altitudeM = input.readDouble(),
        horizontalAccuracyM = input.readFloat(),
        verticalAccuracyM = input.readFloat(),
        pressureHpa = input.readFloat(),
        gyroRadS = FloatArray(3) { input.readFloat() },
    )

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (t.size < 16 || t.take(16).any { !it.isFinite() }) return null
        val out = doubleArrayOf(
            t[0], t[4], t[8], 0.0,
            t[1], t[5], t[9], 0.0,
            t[2], t[6], t[10], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val tx = t[3]; val ty = t[7]; val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return if (out.all { it.isFinite() }) out else null
    }
}
