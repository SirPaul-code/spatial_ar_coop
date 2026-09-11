package com.sirpaul.stablear.nativevision

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object NativeXFeat {
    init { System.loadLibrary("stablear_vision_jni") }
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun addRoot(handle: Long, id: Long, descriptors: FloatArray, reliability: FloatArray, x: Double, y: Double): Boolean
    external fun addTemplate(handle: Long, id: Long, descriptors: FloatArray, reliability: FloatArray, x: Double, y: Double, hasViewDirection: Boolean, vx: Double, vy: Double, vz: Double, scale: Double, quality: Double): Boolean
    external fun beginFrame(handle: Long, frameId: Long, descriptors: FloatArray, reliability: FloatArray): Boolean
    external fun track(handle: Long, id: Long, predictedX: Double, predictedY: Double, hasViewDirection: Boolean, vx: Double, vy: Double, vz: Double, scale: Double, quality: Double): DoubleArray?
    external fun remove(handle: Long, id: Long)
    external fun clear(handle: Long)
}

data class XFeatView(
    val directionX: Double? = null,
    val directionY: Double? = null,
    val directionZ: Double? = null,
    val scale: Double = 1.0,
    val quality: Double = 1.0,
) {
    internal val hasDirection: Boolean
        get() = directionX != null && directionY != null && directionZ != null
}

data class XFeatImageMatch(
    val x: Double,
    val y: Double,
    val inliers: Int,
    val medianReprojectionPx: Double,
    val score: Double,
    val secondBestScore: Double,
    val scoreMargin: Double,
    val meanReliability: Double,
    val sigmaPx: Double,
    val templateSerial: Long,
    val method: NativeVisionMethod,
)

/**
 * Prototype Android learned-correspondence backend.
 *
 * One instance belongs to one bounded vision worker thread. The LiteRT model and tensor buffers are
 * created once and reused. This prototype intentionally uses the public FloatArray TensorBuffer API;
 * the shipping path should replace descriptor readback/JNI copies with native shared buffers.
 * XFeat only proposes image-space evidence. StableAR geometry remains the correction authority.
 */
class XFeatLiteRtTracker private constructor(context: Context, accelerator: Accelerator) : AutoCloseable {
    companion object {
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 480
        private const val DESCRIPTOR_ELEMENTS = 64 * 60 * 80
        private const val RELIABILITY_ELEMENTS = 60 * 80

        fun tryCreate(context: Context, preferGpu: Boolean = true): XFeatLiteRtTracker? {
            val app = context.applicationContext
            if (preferGpu) runCatching { return XFeatLiteRtTracker(app, Accelerator.GPU) }
            return runCatching { XFeatLiteRtTracker(app, Accelerator.CPU) }.getOrNull()
        }
    }

    private val owner = Thread.currentThread()
    private val model = CompiledModel.create(
        context.assets,
        "models/xfeat.tflite",
        CompiledModel.Options(accelerator),
        null,
    )
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val input = FloatArray(INPUT_WIDTH * INPUT_HEIGHT)
    private var handle = NativeXFeat.create().also { check(it != 0L) }
    private var descriptors = FloatArray(0)
    private var reliability = FloatArray(0)

    init {
        check(inputs.size == 1) { "XFeat expected one input, got ${inputs.size}" }
        check(outputs.size >= 3) { "XFeat expected at least three outputs, got ${outputs.size}" }
    }

    private fun h(): Long {
        check(Thread.currentThread() === owner) { "XFeatLiteRtTracker must stay on its worker thread" }
        check(handle != 0L) { "XFeatLiteRtTracker is closed" }
        return handle
    }

    /** Runs XFeat for a grayscale frame and makes its descriptor map the current matcher frame. */
    fun beginFrame(frameId: Long, gray: ByteArray, width: Int, height: Int): Boolean {
        require(frameId > 0 && width > 1 && height > 1 && gray.size == width * height)
        h()
        prepareInput(gray, width, height, input)
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        descriptors = outputs[0].readFloat()
        reliability = outputs[2].readFloat()
        check(descriptors.size == DESCRIPTOR_ELEMENTS) { "Unexpected XFeat descriptor count ${descriptors.size}" }
        check(reliability.size == RELIABILITY_ELEMENTS) { "Unexpected XFeat reliability count ${reliability.size}" }
        return NativeXFeat.beginFrame(handle, frameId, descriptors, reliability)
    }

    /** Captures the immutable root signature from the most recently inferred frame. */
    fun addRoot(id: Long, x: Double, y: Double): Boolean {
        require(id > 0 && x.isFinite() && y.isFinite())
        requireCurrentMap()
        return NativeXFeat.addRoot(h(), id, descriptors, reliability, x, y)
    }

    /** Explicit only: caller must admit templates after independent geometric/held-out acceptance. */
    fun addTemplate(id: Long, x: Double, y: Double, view: XFeatView = XFeatView()): Boolean {
        require(id > 0 && x.isFinite() && y.isFinite())
        requireCurrentMap()
        return NativeXFeat.addTemplate(
            h(), id, descriptors, reliability, x, y, view.hasDirection,
            view.directionX ?: 0.0, view.directionY ?: 0.0, view.directionZ ?: 0.0,
            view.scale, view.quality,
        )
    }

    fun track(id: Long, predictedX: Double, predictedY: Double, view: XFeatView = XFeatView()): XFeatImageMatch? {
        require(id > 0 && predictedX.isFinite() && predictedY.isFinite())
        val v = NativeXFeat.track(
            h(), id, predictedX, predictedY, view.hasDirection,
            view.directionX ?: 0.0, view.directionY ?: 0.0, view.directionZ ?: 0.0,
            view.scale, view.quality,
        ) ?: return null
        if (v.size != 11 || v.any { !it.isFinite() }) return null
        return XFeatImageMatch(
            x = v[0], y = v[1], inliers = v[2].toInt(), medianReprojectionPx = v[3],
            score = v[4], secondBestScore = v[5], scoreMargin = v[6], meanReliability = v[7],
            sigmaPx = v[8], templateSerial = v[9].toLong(),
            method = NativeVisionMethod.entries.getOrElse(v[10].toInt()) { NativeVisionMethod.NONE },
        )
    }

    fun remove(id: Long) { require(id > 0); NativeXFeat.remove(h(), id) }
    fun clear() { NativeXFeat.clear(h()); descriptors = FloatArray(0); reliability = FloatArray(0) }

    private fun requireCurrentMap() {
        check(descriptors.size == DESCRIPTOR_ELEMENTS && reliability.size == RELIABILITY_ELEMENTS) {
            "Call beginFrame before capturing XFeat templates"
        }
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (handle != 0L) {
            val old = handle
            handle = 0L
            NativeXFeat.destroy(old)
        }
        outputs.forEach { it.close() }
        inputs.forEach { it.close() }
        model.close()
    }
}

/** Mirrors stablear::vision::prepareXFeatInput for Android prototype parity. */
private fun prepareInput(gray: ByteArray, width: Int, height: Int, output: FloatArray) {
    var sum = 0.0
    var sum2 = 0.0
    for (oy in 0 until XFeatLiteRtTracker.INPUT_HEIGHT) {
        val sy = (oy + 0.5) * height / XFeatLiteRtTracker.INPUT_HEIGHT - 0.5
        val fy = floor(sy)
        val y0 = min(max(fy.toInt(), 0), height - 1)
        val y1 = min(y0 + 1, height - 1)
        val ty = min(max(sy - fy, 0.0), 1.0)
        for (ox in 0 until XFeatLiteRtTracker.INPUT_WIDTH) {
            val sx = (ox + 0.5) * width / XFeatLiteRtTracker.INPUT_WIDTH - 0.5
            val fx = floor(sx)
            val x0 = min(max(fx.toInt(), 0), width - 1)
            val x1 = min(x0 + 1, width - 1)
            val tx = min(max(sx - fx, 0.0), 1.0)
            fun p(x: Int, y: Int) = (gray[y * width + x].toInt() and 0xff).toDouble()
            val a = p(x0, y0) * (1.0 - tx) + p(x1, y0) * tx
            val b = p(x0, y1) * (1.0 - tx) + p(x1, y1) * tx
            val value = a * (1.0 - ty) + b * ty
            val i = oy * XFeatLiteRtTracker.INPUT_WIDTH + ox
            output[i] = value.toFloat()
            sum += value
            sum2 += value * value
        }
    }
    val n = output.size.toDouble()
    val mean = sum / n
    val variance = max(0.0, sum2 / n - mean * mean)
    val inverseStdDev = 1.0 / sqrt(variance + 1e-5)
    for (i in output.indices) output[i] = ((output[i] - mean) * inverseStdDev).toFloat()
}
