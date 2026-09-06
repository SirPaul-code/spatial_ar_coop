package com.sirpaul.spatialnomap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

sealed class WireMessage {
    data class Hello(val username: String, val deviceModel: String) : WireMessage()
    data class Frame(val frame: CapturedFrame) : WireMessage()
    data class Poi(val id: Long, val owner: String, val pointWorld: FloatArray, val createdAtMs: Long) : WireMessage()
    data object ClearPoi : WireMessage()
    data class Range(val distanceM: Float, val stdDevM: Float, val samples: Int) : WireMessage()
    data class Quality(val confidence: Float, val stableCount: Int, val ready: Boolean) : WireMessage()
    data class ResetAlignment(val reason: String) : WireMessage()
}

object PeerProtocol {
    private const val MAGIC = 0x53505632
    private const val VERSION = 4
    private const val MAX_PAYLOAD = 8 * 1024 * 1024
    private const val T_HELLO = 1
    private const val T_FRAME = 2
    private const val T_POI = 3
    private const val T_CLEAR = 4
    private const val T_RANGE = 5
    private const val T_QUALITY = 6
    private const val T_RESET_ALIGNMENT = 7

    fun write(output: OutputStream, message: WireMessage) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            when (message) {
                is WireMessage.Hello -> {
                    out.writeUTF(message.username.take(48))
                    out.writeUTF(message.deviceModel.take(64))
                }
                is WireMessage.Frame -> writeFrame(out, message.frame)
                is WireMessage.Poi -> {
                    out.writeLong(message.id)
                    out.writeUTF(message.owner.take(48))
                    repeat(3) { out.writeFloat(message.pointWorld.getOrElse(it) { 0f }) }
                    out.writeLong(message.createdAtMs)
                }
                WireMessage.ClearPoi -> Unit
                is WireMessage.Range -> {
                    out.writeFloat(message.distanceM)
                    out.writeFloat(message.stdDevM)
                    out.writeInt(message.samples)
                }
                is WireMessage.Quality -> {
                    out.writeFloat(message.confidence)
                    out.writeInt(message.stableCount)
                    out.writeBoolean(message.ready)
                }
                is WireMessage.ResetAlignment -> out.writeUTF(message.reason.take(128))
            }
        }

        val bytes = payload.toByteArray()
        require(bytes.size <= MAX_PAYLOAD) { "wire payload too large: ${bytes.size}" }
        val type = when (message) {
            is WireMessage.Hello -> T_HELLO
            is WireMessage.Frame -> T_FRAME
            is WireMessage.Poi -> T_POI
            WireMessage.ClearPoi -> T_CLEAR
            is WireMessage.Range -> T_RANGE
            is WireMessage.Quality -> T_QUALITY
            is WireMessage.ResetAlignment -> T_RESET_ALIGNMENT
        }

        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeByte(type)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun read(input: InputStream): WireMessage? {
        val source = DataInputStream(input)
        val magic = try {
            source.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(magic == MAGIC) { "bad wire magic" }
        val version = source.readUnsignedByte()
        require(version == VERSION) { "unsupported wire version $version" }
        val type = source.readUnsignedByte()
        val size = source.readInt()
        require(size in 0..MAX_PAYLOAD) { "invalid payload size $size" }
        val payload = ByteArray(size)
        source.readFully(payload)

        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            return when (type) {
                T_HELLO -> WireMessage.Hello(data.readUTF(), data.readUTF())
                T_FRAME -> WireMessage.Frame(readFrame(data))
                T_POI -> WireMessage.Poi(
                    data.readLong(),
                    data.readUTF(),
                    floatArrayOf(data.readFloat(), data.readFloat(), data.readFloat()),
                    data.readLong(),
                )
                T_CLEAR -> WireMessage.ClearPoi
                T_RANGE -> WireMessage.Range(data.readFloat(), data.readFloat(), data.readInt())
                T_QUALITY -> WireMessage.Quality(data.readFloat(), data.readInt(), data.readBoolean())
                T_RESET_ALIGNMENT -> WireMessage.ResetAlignment(data.readUTF())
                else -> throw IllegalArgumentException("unknown wire type $type")
            }
        }
    }

    private fun writeFrame(out: DataOutputStream, frame: CapturedFrame) {
        out.writeLong(frame.timestampNs)
        repeat(3) { out.writeFloat(frame.pose.t.getOrElse(it) { 0f }) }
        repeat(4) { out.writeFloat(frame.pose.q.getOrElse(it) { if (it == 3) 1f else 0f }) }

        val k = frame.intrinsics
        out.writeFloat(k.fx)
        out.writeFloat(k.fy)
        out.writeFloat(k.cx)
        out.writeFloat(k.cy)
        out.writeInt(k.width)
        out.writeInt(k.height)

        val jpeg = Base64.getDecoder().decode(frame.jpegBase64)
        out.writeInt(jpeg.size)
        out.write(jpeg)

        val points = frame.metricPoints.take(2500)
        out.writeInt(points.size)
        for (p in points) repeat(5) { out.writeFloat(p.getOrElse(it) { 0f }) }

        writeSensors(out, frame.sensors)
    }

    private fun readFrame(input: DataInputStream): CapturedFrame {
        val timestampNs = input.readLong()
        val t = FloatArray(3) { input.readFloat() }
        val q = FloatArray(4) { input.readFloat() }
        val intrinsics = IntrinsicsPacket(
            input.readFloat(),
            input.readFloat(),
            input.readFloat(),
            input.readFloat(),
            input.readInt(),
            input.readInt(),
        )

        val jpegSize = input.readInt()
        require(jpegSize in 1..MAX_PAYLOAD) { "invalid jpeg size $jpegSize" }
        val jpeg = ByteArray(jpegSize)
        input.readFully(jpeg)

        val count = input.readInt()
        require(count in 0..2500) { "invalid point count $count" }
        val points = ArrayList<FloatArray>(count)
        repeat(count) { points += FloatArray(5) { input.readFloat() } }

        return CapturedFrame(
            timestampNs = timestampNs,
            pose = PosePacket(t, q),
            intrinsics = intrinsics,
            jpegBase64 = Base64.getEncoder().encodeToString(jpeg),
            metricPoints = points,
            sensors = readSensors(input),
        )
    }

    private fun writeSensors(out: DataOutputStream, s: SensorSnapshot) {
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
}
