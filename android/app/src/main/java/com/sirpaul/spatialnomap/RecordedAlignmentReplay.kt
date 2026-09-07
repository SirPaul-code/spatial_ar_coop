package com.sirpaul.spatialnomap

import android.util.Base64
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

/**
 * Decoder for AlignmentSessionRecorder frames.spv6 files.
 *
 * Kept independent from Android UI so future solver regression tests can feed the
 * exact physical-phone frames back through AlignmentEngine instead of reproducing
 * difficult scenes manually.
 */
object RecordedAlignmentReplay {
    data class RecordedFrame(
        val remote: Boolean,
        val frame: CapturedFrame,
    )

    fun read(file: File, limit: Int = Int.MAX_VALUE): List<RecordedFrame> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<RecordedFrame>()
        DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { input ->
            while (out.size < limit) {
                val magic = try { input.readInt() } catch (_: EOFException) { break }
                require(magic == FRAME_MAGIC) { "bad replay frame magic" }
                val remote = input.readUnsignedByte() == 1
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
                require(jpegSize in 1..16 * 1024 * 1024) { "bad replay jpeg size $jpegSize" }
                val jpeg = ByteArray(jpegSize)
                input.readFully(jpeg)
                val pointCount = input.readInt()
                require(pointCount in 0..8000) { "bad replay point count $pointCount" }
                val points = ArrayList<FloatArray>(pointCount)
                repeat(pointCount) { points += FloatArray(5) { input.readFloat() } }
                val sensors = SensorSnapshot(
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
                out += RecordedFrame(
                    remote = remote,
                    frame = CapturedFrame(
                        timestampNs = timestampNs,
                        pose = pose,
                        intrinsics = intrinsics,
                        jpegBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                        metricPoints = points,
                        sensors = sensors,
                        burstId = burstId,
                        burstSequence = burstSequence,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Finds the best local/remote pair from a recording by burst id/sequence first,
     * then by list proximity. This is intentionally small and deterministic so it
     * can become a JVM regression-test helper later.
     */
    fun candidatePairs(frames: List<RecordedFrame>, maxPairs: Int = 24): List<Pair<CapturedFrame, CapturedFrame>> {
        val locals = frames.filter { !it.remote }.map { it.frame }
        val remotes = frames.filter { it.remote }.map { it.frame }
        if (locals.isEmpty() || remotes.isEmpty()) return emptyList()
        return buildList {
            for (local in locals) {
                val ranked = remotes.sortedBy { remote ->
                    when {
                        local.burstId != 0L && local.burstId == remote.burstId &&
                            local.burstSequence >= 0 && remote.burstSequence >= 0 ->
                            kotlin.math.abs(local.burstSequence - remote.burstSequence)
                        else -> 1000 + kotlin.math.abs(locals.indexOf(local) - remotes.indexOf(remote))
                    }
                }
                ranked.take(2).forEach { add(it to local) }
                if (size >= maxPairs) break
            }
        }.take(maxPairs)
    }

    private const val FRAME_MAGIC = 0x53524636
}
