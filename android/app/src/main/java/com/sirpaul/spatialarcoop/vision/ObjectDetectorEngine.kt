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
                val candidates = result.detections().mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                    val label = category.categoryName().lowercase()
                    if (label !in ALLOWED_LABELS) return@mapNotNull null
                    // Chickens are represented by EfficientDet/COCO as "bird". Small birds are
                    // substantially harder than people/cars, so keep the user's normal threshold
                    // for other classes while allowing a modestly lower bird floor for recall.
                    val effectiveThreshold = if (label == "bird") minOf(threshold, BIRD_SCORE_THRESHOLD) else maxOf(threshold, GENERAL_SCORE_THRESHOLD)
                    if (category.score() < effectiveThreshold) return@mapNotNull null
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
                val detections = suppressOverlaps(candidates)
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
        val modelThreshold = minOf(threshold, MIN_MODEL_SCORE_THRESHOLD)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet-lite0.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(modelThreshold)
            .setMaxResults(MAX_RESULTS)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        return ObjectDetector.createFromOptions(appContext, options).also {
            detector = it
            logger.info(
                "Object detector initialized",
                mapOf(
                    "threshold" to threshold,
                    "birdThreshold" to minOf(threshold, BIRD_SCORE_THRESHOLD),
                    "generalThreshold" to maxOf(threshold, GENERAL_SCORE_THRESHOLD),
                    "maxResults" to MAX_RESULTS,
                    "labels" to ALLOWED_LABELS.joinToString()
                )
            )
        }
    }

    private fun suppressOverlaps(values: List<Detection2D>): List<Detection2D> {
        val kept = mutableListOf<Detection2D>()
        values.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                existing.label == candidate.label && intersectionOverUnion(existing.rawBoundingBox, candidate.rawBoundingBox) >= NMS_IOU_THRESHOLD
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
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

    private fun classScoreThreshold(label: String): Float = when (label) {
        "bird" -> BIRD_SCORE_THRESHOLD
        else -> GENERAL_SCORE_THRESHOLD
    }

    companion object {
        private val ALLOWED_LABELS = setOf("person", "car", "bird", "dog", "cat")
        private const val MIN_MODEL_SCORE_THRESHOLD = 0.25f
        private const val BIRD_SCORE_THRESHOLD = 0.25f
        private const val GENERAL_SCORE_THRESHOLD = 0.45f
        private const val NMS_IOU_THRESHOLD = 0.55f
        private const val MAX_RESULTS = 24
    }
}
