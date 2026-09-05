package com.sirpaul.spatialnomap

import android.graphics.Bitmap
import android.util.Base64
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object FrameCapture {
    fun capture(frame: Frame, camera: Camera, maxWidth: Int = 1280): CapturedFrame? {
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return null
        }
        image.use {
            val srcW = image.width
            val srcH = image.height
            val scale = min(1f, maxWidth.toFloat() / srcW.toFloat())
            val dstW = max(1, (srcW * scale).toInt())
            val dstH = max(1, (srcH * scale).toInt())
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val pixels = IntArray(dstW * dstH)
            for (dy in 0 until dstH) {
                val sy = min(srcH - 1, (dy / scale).toInt())
                for (dx in 0 until dstW) {
                    val sx = min(srcW - 1, (dx / scale).toInt())
                    val yValue = yBuffer.get(sy * rowStride + sx * pixelStride).toInt() and 0xff
                    pixels[dy * dstW + dx] = (0xff shl 24) or (yValue shl 16) or (yValue shl 8) or yValue
                }
            }
            val bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, bytes)
            bitmap.recycle()

            val intr = camera.imageIntrinsics
            val f = intr.focalLength
            val pp = intr.principalPoint
            val pose = camera.pose
            val metric = MetricSupportSampler.sample(frame, camera).map {
                floatArrayOf(it[0] * scale, it[1] * scale, it[2], it[3], it[4])
            }
            return CapturedFrame(
                timestampNs = frame.timestamp,
                pose = PosePacket(pose.translation, pose.rotationQuaternion),
                intrinsics = IntrinsicsPacket(
                    fx = f[0] * scale,
                    fy = f[1] * scale,
                    cx = pp[0] * scale,
                    cy = pp[1] * scale,
                    width = dstW,
                    height = dstH,
                ),
                jpegBase64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
                metricPoints = metric,
            )
        }
    }
}
