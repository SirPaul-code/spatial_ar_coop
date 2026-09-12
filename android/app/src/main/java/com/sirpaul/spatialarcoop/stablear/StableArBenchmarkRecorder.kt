package com.sirpaul.spatialarcoop.stablear

import android.content.Context
import com.sirpaul.stablear.arcore.GrayImage
import com.sirpaul.stablear.core.Intrinsics
import com.sirpaul.stablear.core.V2
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug/field evidence recorder. It is product tooling, not part of the sellable runtime AAR.
 *
 * Frames are downsampled to the XFeat model raster and stored as binary PGM so the offline ArUco
 * evaluator can establish ground truth without using StableAR's own correspondence output.
 */
internal class StableArBenchmarkRecorder(
    context: Context,
    private val enabled: Boolean,
    private val phase: String = "field"
) : AutoCloseable {
    companion object {
        private const val OUT_WIDTH = 640
        private const val OUT_HEIGHT = 480
        private const val MIN_INTERVAL_NS = 200_000_000L // 5 Hz keeps field sessions reasonably small.
        private const val MAX_PENDING_WRITES = 3
    }

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "stablear-benchmark") }
    private val pending = AtomicInteger(0)
    private val directory: File? = if (enabled) {
        val root = context.getExternalFilesDir("stablear-benchmark") ?: context.filesDir
        File(root, "session-${System.currentTimeMillis()}").apply { mkdirs() }
    } else null
    private var writer: BufferedWriter? = null
    private var lastTimestampNs = Long.MIN_VALUE
    @Volatile private var closed = false

    val sessionPath: String? get() = directory?.absolutePath

    init {
        if (enabled) {
            val csv = File(checkNotNull(directory), "frames.csv")
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(csv), Charsets.UTF_8)).also {
                it.write("timestamp_ns,phase,image_path,fx,fy,stock_x,stock_y,stock_valid,stable_x,stable_y,stable_valid,stable_method,stable_latency_ms,accepted_corrections,tracking_state\n")
                it.flush()
            }
        }
    }

    fun record(
        gray: GrayImage,
        intrinsics: Intrinsics,
        stock: V2?,
        stable: V2?,
        stableMethod: String,
        stableLatencyMs: Double?,
        acceptedCorrections: Int,
        trackingState: String
    ) {
        if (!enabled || closed || gray.timestampNs - lastTimestampNs < MIN_INTERVAL_NS) return
        if (pending.get() >= MAX_PENDING_WRITES) return
        lastTimestampNs = gray.timestampNs
        pending.incrementAndGet()
        worker.execute {
            try {
                val dir = directory ?: return@execute
                val name = "frame-${gray.timestampNs}.pgm"
                val image = resizeGray(gray.bytes, gray.width, gray.height, OUT_WIDTH, OUT_HEIGHT)
                FileOutputStream(File(dir, name)).use { out ->
                    out.write("P5\n$OUT_WIDTH $OUT_HEIGHT\n255\n".toByteArray(Charsets.US_ASCII))
                    out.write(image)
                }
                val scaledStock = stock?.let { scalePoint(it, gray.width, gray.height) }
                val scaledStable = stable?.let { scalePoint(it, gray.width, gray.height) }
                val sx = OUT_WIDTH.toDouble() / gray.width
                val sy = OUT_HEIGHT.toDouble() / gray.height
                synchronized(this) {
                    writer?.apply {
                        write(gray.timestampNs.toString())
                        write(",${csv(phase)},$name")
                        write(",${fmt(intrinsics.fx * sx)},${fmt(intrinsics.fy * sy)}")
                        write(",${fmtOrBlank(scaledStock?.x)},${fmtOrBlank(scaledStock?.y)},${scaledStock != null}")
                        write(",${fmtOrBlank(scaledStable?.x)},${fmtOrBlank(scaledStable?.y)},${scaledStable != null}")
                        write(",${csv(stableMethod)},${fmtOrBlank(stableLatencyMs)},$acceptedCorrections,${csv(trackingState)}\n")
                        flush()
                    }
                }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private fun scalePoint(point: V2, sourceWidth: Int, sourceHeight: Int) = V2(
        (point.x + 0.5) * OUT_WIDTH / sourceWidth - 0.5,
        (point.y + 0.5) * OUT_HEIGHT / sourceHeight - 0.5
    )

    private fun resizeGray(input: ByteArray, width: Int, height: Int, outWidth: Int, outHeight: Int): ByteArray {
        val output = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sy = ((y + 0.5) * height / outHeight - 0.5).toInt().coerceIn(0, height - 1)
            for (x in 0 until outWidth) {
                val sx = ((x + 0.5) * width / outWidth - 0.5).toInt().coerceIn(0, width - 1)
                output[y * outWidth + x] = input[sy * width + sx]
            }
        }
        return output
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
    private fun fmtOrBlank(value: Double?): String = value?.takeIf(Double::isFinite)?.let(::fmt) ?: ""
    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    override fun close() {
        if (closed) return
        closed = true
        worker.shutdown()
        runCatching { worker.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
    }
}
