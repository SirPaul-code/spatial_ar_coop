package com.sirpaul.spatialarcoop.vision

import android.content.Context
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.sirpaul.spatialarcoop.util.FileLogger
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Detection2D(
    val label: String,
    val confidence: Float,
    val rawBoundingBox: RectF,
    val rawBottomCenter: FloatArray,
    val capturedAtMs: Long,
    val rawImageWidth: Int,
    val rawImageHeight: Int
)

class ObjectDetectorEngine(
    context: Context,
    private val threshold: Float,
    private val logger: FileLogger,
    private val onResult: (List<Detection2D>, inferenceMs: Long) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "spatial-object-detector") }
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var detector: ObjectDetector? = null

    fun submit(frame: YuvFrame, rotationDegrees: Int, capturedAtMs: Long): Boolean {
        if (closed || !busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val start = System.nanoTime()
                val rawBitmap = YuvFrameConverter.toBitmap(frame)
                val upright = YuvFrameConverter.rotate(rawBitmap, rotationDegrees)
                val image = BitmapImageBuilder(upright).build()
                val result = try {
                    detector().detect(image)
                } finally {
                    image.close()
                    if (upright !== rawBitmap) upright.recycle()
                    rawBitmap.recycle()
                }
                val detections = result.detections().mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                    val label = category.categoryName().lowercase()
                    if (label !in ALLOWED_LABELS || category.score() < threshold) return@mapNotNull null
                    val box = detection.boundingBox()
                    val bottomCenterRotated = floatArrayOf(box.centerX(), box.bottom - box.height() * 0.04f)
                    val rawBottomCenter = YuvFrameConverter.rotatedToRaw(
                        bottomCenterRotated[0], bottomCenterRotated[1], frame.width, frame.height, rotationDegrees
                    )
                    val corners = arrayOf(
                        YuvFrameConverter.rotatedToRaw(box.left, box.top, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.right, box.top, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.right, box.bottom, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.left, box.bottom, frame.width, frame.height, rotationDegrees)
                    )
                    Detection2D(
                        label = label,
                        confidence = category.score(),
                        rawBoundingBox = RectF(
                            corners.minOf { it[0] },
                            corners.minOf { it[1] },
                            corners.maxOf { it[0] },
                            corners.maxOf { it[1] }
                        ),
                        rawBottomCenter = rawBottomCenter,
                        capturedAtMs = capturedAtMs,
                        rawImageWidth = frame.width,
                        rawImageHeight = frame.height
                    )
                }
                onResult(detections, (System.nanoTime() - start) / 1_000_000L)
            } catch (error: Throwable) {
                logger.error("Object detection failed", error)
                onError(error.message ?: error.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    private fun detector(): ObjectDetector {
        detector?.let { return it }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet-lite0.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(threshold)
            .setMaxResults(8)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        return ObjectDetector.createFromOptions(appContext, options).also {
            detector = it
            logger.info("Object detector initialized", mapOf("threshold" to threshold, "labels" to ALLOWED_LABELS.joinToString()))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        executor.execute {
            detector?.close()
            detector = null
        }
        executor.shutdown()
    }

    companion object {
        private val ALLOWED_LABELS = setOf("person", "car", "bird", "dog", "cat")
    }
}
