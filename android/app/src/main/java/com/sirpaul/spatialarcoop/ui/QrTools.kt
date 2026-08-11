package com.sirpaul.spatialarcoop.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrTools {
    fun encode(value: String, sizePx: Int): Bitmap {
        require(value.isNotBlank()) { "QR payload must not be blank" }
        val size = sizePx.coerceIn(240, 1200)
        val matrix = MultiFormatWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.rgb(23, 24, 26) else Color.rgb(242, 239, 232)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
