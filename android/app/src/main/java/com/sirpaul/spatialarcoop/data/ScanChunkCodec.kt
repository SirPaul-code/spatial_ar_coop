package com.sirpaul.spatialarcoop.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ScanChunkMetadata(
    val pointCount: Int,
    val capturedAtMs: Long,
    val uncompressedBytes: Int
)

/** Stable, versioned codec used both by the recorder and crash-recovery pass. */
object ScanChunkCodec {
    private val magic = byteArrayOf('S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val headerBytes = 16
    private const val bytesPerPoint = 16
    private const val maxUncompressedBytes = 32 * 1024 * 1024

    fun encode(samples: List<FloatArray>, capturedAtMs: Long): ByteArray {
        require(samples.size <= (maxUncompressedBytes - headerBytes) / bytesPerPoint)
        val buffer = ByteBuffer.allocate(headerBytes + samples.size * bytesPerPoint).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic)
        buffer.putInt(samples.size)
        buffer.putLong(capturedAtMs)
        samples.forEach { values ->
            require(values.size >= 4)
            buffer.putFloat(values[0])
            buffer.putFloat(values[1])
            buffer.putFloat(values[2])
            buffer.putFloat(values[3])
        }
        return buffer.array()
    }

    fun writeGzipAtomically(raw: ByteArray, temporary: File, destination: File) {
        temporary.parentFile?.mkdirs()
        GZIPOutputStream(temporary.outputStream().buffered()).use { it.write(raw) }
        // Validate the complete gzip stream before replacing any previous destination.
        readMetadata(temporary)
        if (destination.exists() && !destination.delete()) {
            error("Could not replace ${destination.absolutePath}")
        }
        if (!temporary.renameTo(destination)) {
            // renameTo is atomic on the same Android filesDir filesystem. A copy fallback would
            // weaken crash-safety, so fail and leave the temporary file for the recovery pass.
            error("Could not atomically rename ${temporary.name} to ${destination.name}")
        }
    }

    fun readMetadata(file: File): ScanChunkMetadata = file.inputStream().buffered().use { input ->
        val output = ByteArrayOutputStream()
        GZIPInputStream(input).use { gzip ->
            val block = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = gzip.read(block)
                if (read < 0) break
                total += read
                require(total <= maxUncompressedBytes) { "Scan chunk exceeds $maxUncompressedBytes bytes" }
                output.write(block, 0, read)
            }
        }
        decodeMetadata(output.toByteArray())
    }

    fun gzip(raw: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray()
    }

    fun metadataFromGzip(bytes: ByteArray): ScanChunkMetadata {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            val block = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = gzip.read(block)
                if (read < 0) break
                total += read
                require(total <= maxUncompressedBytes) { "Scan chunk exceeds $maxUncompressedBytes bytes" }
                output.write(block, 0, read)
            }
        }
        return decodeMetadata(output.toByteArray())
    }

    private fun decodeMetadata(raw: ByteArray): ScanChunkMetadata {
        require(raw.size >= headerBytes) { "Scan chunk is shorter than the SAC1 header" }
        require(raw.copyOfRange(0, 4).contentEquals(magic)) { "Invalid SAC1 magic" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val pointCount = buffer.int
        val capturedAtMs = buffer.long
        require(pointCount >= 0) { "Negative point count" }
        val expected = headerBytes.toLong() + pointCount.toLong() * bytesPerPoint
        require(expected == raw.size.toLong()) {
            "SAC1 length mismatch: expected $expected, got ${raw.size}"
        }
        return ScanChunkMetadata(pointCount, capturedAtMs, raw.size)
    }
}
