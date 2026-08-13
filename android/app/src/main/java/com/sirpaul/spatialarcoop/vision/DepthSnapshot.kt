package com.sirpaul.spatialarcoop.vision

import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.floor

data class DepthContactSample(
    val opticalDepthMeters: Float,
    val confidence: Float,
    val source: String
)

class DepthSnapshot private constructor(
    private val imageToTexture: FloatArray,
    private val raw: DepthLayer?,
    private val dense: DepthLayer?
) {
    private data class DepthLayer(
        val width: Int,
        val height: Int,
        val millimeters: IntArray,
        val confidence: ByteArray?,
        val source: String
    )

    fun sample(imagePixel: FloatArray): DepthContactSample? {
        if (imagePixel.size < 2) return null
        val uv = imageToUv(imagePixel[0], imagePixel[1]) ?: return null
        return raw?.let { sampleLayer(it, uv, radius = 5, minimumConfidence = 0.32f) }
            ?: dense?.let { sampleLayer(it, uv, radius = 2, minimumConfidence = 0.0f) }
    }

    private fun imageToUv(x: Float, y: Float): FloatArray? {
        if (imageToTexture.size != 6) return null
        val u = imageToTexture[0] * x + imageToTexture[1] * y + imageToTexture[2]
        val v = imageToTexture[3] * x + imageToTexture[4] * y + imageToTexture[5]
        if (!u.isFinite() || !v.isFinite() || u < 0f || v < 0f || u > 1f || v > 1f) return null
        return floatArrayOf(u, v)
    }

    private fun sampleLayer(
        layer: DepthLayer,
        uv: FloatArray,
        radius: Int,
        minimumConfidence: Float
    ): DepthContactSample? {
        val centerX = floor(uv[0] * layer.width).toInt().coerceIn(0, layer.width - 1)
        val centerY = floor(uv[1] * layer.height).toInt().coerceIn(0, layer.height - 1)
        val samples = mutableListOf<Pair<Int, Float>>()
        for (dy in -radius..radius) {
            val y = centerY + dy
            if (y !in 0 until layer.height) continue
            for (dx in -radius..radius) {
                val x = centerX + dx
                if (x !in 0 until layer.width) continue
                val index = y * layer.width + x
                val millimeters = layer.millimeters[index]
                if (millimeters !in MIN_DEPTH_MM..MAX_DEPTH_MM) continue
                val confidence = layer.confidence?.get(index)?.toInt()?.and(0xff)?.div(255f) ?: DENSE_CONFIDENCE
                if (confidence < minimumConfidence) continue
                val distancePenalty = 1f / (1f + 0.18f * (kotlin.math.abs(dx) + kotlin.math.abs(dy)))
                samples += millimeters to (confidence * distancePenalty)
            }
        }
        if (samples.isEmpty()) return null
        val ordered = samples.sortedBy { it.first }
        val totalWeight = ordered.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-4f)
        var accumulated = 0f
        var medianMm = ordered[ordered.lastIndex].first
        for ((millimeters, weight) in ordered) {
            accumulated += weight
            if (accumulated >= totalWeight * 0.5f) {
                medianMm = millimeters
                break
            }
        }
        val confidence = (samples.sumOf { it.second.toDouble() }.toFloat() / samples.size).coerceIn(0f, 1f)
        return DepthContactSample(medianMm / 1000f, confidence, layer.source)
    }

    companion object {
        private const val MIN_DEPTH_MM = 180
        private const val MAX_DEPTH_MM = 65_000
        private const val DENSE_CONFIDENCE = 0.58f

        fun capture(frame: Frame): DepthSnapshot? {
            val dimensions = frame.camera.imageIntrinsics.imageDimensions
            val transform = captureImageToTexture(frame, dimensions[0], dimensions[1]) ?: return null
            val raw = captureRaw(frame)
            val dense = captureDense(frame)
            if (raw == null && dense == null) return null
            return DepthSnapshot(transform, raw, dense)
        }

        private fun captureImageToTexture(frame: Frame, imageWidth: Int, imageHeight: Int): FloatArray? {
            if (imageWidth <= 0 || imageHeight <= 0) return null
            val input = floatArrayOf(
                0f, 0f,
                imageWidth.toFloat(), 0f,
                0f, imageHeight.toFloat()
            )
            val output = FloatArray(6)
            return runCatching {
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_PIXELS,
                    input,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    output
                )
                val u0 = output[0]
                val v0 = output[1]
                val ux = output[2]
                val vx = output[3]
                val uy = output[4]
                val vy = output[5]
                floatArrayOf(
                    (ux - u0) / imageWidth,
                    (uy - u0) / imageHeight,
                    u0,
                    (vx - v0) / imageWidth,
                    (vy - v0) / imageHeight,
                    v0
                ).takeIf { coefficients -> coefficients.all(Float::isFinite) }
            }.getOrNull()
        }

        private fun captureRaw(frame: Frame): DepthLayer? {
            var depth: Image? = null
            var confidence: Image? = null
            return try {
                depth = frame.acquireRawDepthImage16Bits()
                confidence = frame.acquireRawDepthConfidenceImage()
                val depthValues = copyDepth(depth)
                val confidenceValues = copyConfidence(confidence, depth.width, depth.height)
                DepthLayer(depth.width, depth.height, depthValues, confidenceValues, "raw-depth")
            } catch (_: NotYetAvailableException) {
                null
            } catch (_: IllegalStateException) {
                null
            } finally {
                runCatching { confidence?.close() }
                runCatching { depth?.close() }
            }
        }

        private fun captureDense(frame: Frame): DepthLayer? {
            var depth: Image? = null
            return try {
                depth = frame.acquireDepthImage16Bits()
                DepthLayer(depth.width, depth.height, copyDepth(depth), null, "dense-depth")
            } catch (_: NotYetAvailableException) {
                null
            } catch (_: IllegalStateException) {
                null
            } finally {
                runCatching { depth?.close() }
            }
        }

        private fun copyDepth(image: Image): IntArray {
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
            return IntArray(image.width * image.height) { index ->
                val x = index % image.width
                val y = index / image.width
                val byteIndex = x * plane.pixelStride + y * plane.rowStride
                buffer.getShort(byteIndex).toInt() and 0xffff
            }
        }

        private fun copyConfidence(image: Image, expectedWidth: Int, expectedHeight: Int): ByteArray? {
            if (image.width != expectedWidth || image.height != expectedHeight) return null
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            return ByteArray(image.width * image.height) { index ->
                val x = index % image.width
                val y = index / image.width
                val byteIndex = x * plane.pixelStride + y * plane.rowStride
                buffer.get(byteIndex)
            }
        }
    }
}
