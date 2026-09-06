package com.sirpaul.spatialnomap

import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

object FrameCapture {
    /**
     * Captures the CPU Y plane as a compact grayscale JPEG. Copying is row based
     * for the normal pixelStride=1 path and resize/JPEG are native OpenCV calls;
     * this avoids the old AR render-thread loop that created one ARGB Int per
     * source pixel every ~650 ms.
     */
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
            val yBuffer = yPlane.buffer.duplicate()
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val base = yBuffer.position()
            val grayBytes = ByteArray(srcW * srcH)

            if (pixelStride == 1) {
                for (row in 0 until srcH) {
                    yBuffer.position(base + row * rowStride)
                    yBuffer.get(grayBytes, row * srcW, srcW)
                }
            } else {
                for (row in 0 until srcH) {
                    val rowBase = base + row * rowStride
                    val dstBase = row * srcW
                    for (x in 0 until srcW) {
                        grayBytes[dstBase + x] = yBuffer.get(rowBase + x * pixelStride)
                    }
                }
            }

            val src = Mat(srcH, srcW, CvType.CV_8UC1)
            val resized = Mat()
            val encoded = MatOfByte()
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 72)
            try {
                src.put(0, 0, grayBytes)
                val output = if (dstW != srcW || dstH != srcH) {
                    Imgproc.resize(
                        src,
                        resized,
                        Size(dstW.toDouble(), dstH.toDouble()),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                    resized
                } else {
                    src
                }
                if (!Imgcodecs.imencode(".jpg", output, encoded, params)) return null
                val jpeg = encoded.toArray()

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
                    jpegBase64 = Base64.getEncoder().encodeToString(jpeg),
                    metricPoints = metric,
                )
            } finally {
                src.release()
                resized.release()
                encoded.release()
                params.release()
            }
        }
    }
}
