package com.sirpaul.spatialarcoop.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import kotlin.math.roundToInt

data class YuvPlaneData(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)

data class YuvFrame(
    val width: Int,
    val height: Int,
    val y: YuvPlaneData,
    val u: YuvPlaneData,
    val v: YuvPlaneData
) {
    companion object {
        fun copyOf(image: Image): YuvFrame {
            require(image.format == android.graphics.ImageFormat.YUV_420_888)
            fun plane(index: Int): YuvPlaneData {
                val source = image.planes[index]
                val buffer = source.buffer.duplicate().apply { rewind() }
                return YuvPlaneData(ByteArray(buffer.remaining()).also(buffer::get), source.rowStride, source.pixelStride)
            }
            return YuvFrame(image.width, image.height, plane(0), plane(1), plane(2))
        }
    }
}

object YuvFrameConverter {
    fun toBitmap(frame: YuvFrame): Bitmap {
        val pixels = IntArray(frame.width * frame.height)
        var output = 0
        for (row in 0 until frame.height) {
            val chromaRow = row / 2
            for (column in 0 until frame.width) {
                val chromaColumn = column / 2
                val yValue = frame.y.bytes[row * frame.y.rowStride + column * frame.y.pixelStride].toInt() and 0xff
                val uValue = frame.u.bytes[chromaRow * frame.u.rowStride + chromaColumn * frame.u.pixelStride].toInt() and 0xff
                val vValue = frame.v.bytes[chromaRow * frame.v.rowStride + chromaColumn * frame.v.pixelStride].toInt() and 0xff
                val y = (yValue - 16).coerceAtLeast(0)
                val u = uValue - 128
                val v = vValue - 128
                val r = (1.164f * y + 1.596f * v).roundToInt().coerceIn(0, 255)
                val g = (1.164f * y - 0.392f * u - 0.813f * v).roundToInt().coerceIn(0, 255)
                val b = (1.164f * y + 2.017f * u).roundToInt().coerceIn(0, 255)
                pixels[output++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    fun rotate(bitmap: Bitmap, clockwiseDegrees: Int): Bitmap {
        val normalized = ((clockwiseDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Maps a coordinate from the clockwise-rotated image back to the raw sensor image. */
    fun rotatedToRaw(x: Float, y: Float, rawWidth: Int, rawHeight: Int, clockwiseDegrees: Int): FloatArray {
        return when (((clockwiseDegrees % 360) + 360) % 360) {
            0 -> floatArrayOf(x, y)
            90 -> floatArrayOf(y, rawHeight - 1f - x)
            180 -> floatArrayOf(rawWidth - 1f - x, rawHeight - 1f - y)
            270 -> floatArrayOf(rawWidth - 1f - y, x)
            else -> error("Rotation must be 0, 90, 180, or 270")
        }
    }
}
