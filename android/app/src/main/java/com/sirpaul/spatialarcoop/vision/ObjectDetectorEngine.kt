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

data class CaptureGeometry(
    val siteFromCamera: FloatArray?,
    val focalLength: FloatArray,
    val principalPoint: FloatArray
)

data class Detection2D(
    val label: String,
    val confidence: Float,
    val rawBoundingBox: RectF,
    val rawBottomCenter: FloatArray,
    val capturedAtMs: Long,
    val rawImageWidth: Int,
    val rawImageHeight: Int,
    val temporalId: String? = null,
    val temporallyConfirmed: Boolean = true,
    val captureGeometry: CaptureGeometry? = null
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
    private val temporalTracker = TemporalDetectionTracker(threshold)
    @Volatile private var closed = false
    private var detector: ObjectDetector? = null

    fun submit(
        frame: YuvFrame,
        rotationDegrees: Int,
        capturedAtMs: Long,
        captureGeometry: CaptureGeometry? = null
    ): Boolean {
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

                val rawCandidates = result.detections().mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                    val label = category.categoryName().lowercase()
                    if (label !in ALLOWED_LABELS) return@mapNotNull null

                    val box = detection.boundingBox()
                    val corners = arrayOf(
                        YuvFrameConverter.rotatedToRaw(box.left, box.top, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.right, box.top, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.right, box.bottom, frame.width, frame.height, rotationDegrees),
                        YuvFrameConverter.rotatedToRaw(box.left, box.bottom, frame.width, frame.height, rotationDegrees)
                    )
                    DetectionCandidate2D(
                        label = label,
                        confidence = category.score(),
                        left = corners.minOf { it[0] },
                        top = corners.minOf { it[1] },
                        right = corners.maxOf { it[0] },
                        bottom = corners.maxOf { it[1] }
                    )
                }

                val candidates = suppressOverlaps(rawCandidates)
                val tracked = temporalTracker.update(candidates, capturedAtMs)
                val detections = tracked.asSequence()
                    .filter { it.confirmed }
                    .map { detection ->
                        val rawBox = RectF(detection.left, detection.top, detection.right, detection.bottom)
                        Detection2D(
                            label = detection.label,
                            confidence = detection.confidence,
                            rawBoundingBox = rawBox,
                            rawBottomCenter = floatArrayOf(
                                rawBox.centerX(),
                                rawBox.bottom - rawBox.height() * BOTTOM_CENTER_INSET
                            ),
                            capturedAtMs = capturedAtMs,
                            rawImageWidth = frame.width,
                            rawImageHeight = frame.height,
                            temporalId = detection.temporalId,
                            temporallyConfirmed = true,
                            captureGeometry = captureGeometry
                        )
                    }
                    .toList()
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
            .setModelAssetPath("efficientdet-lite2.tflite")
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(modelThreshold)
            .setCategoryAllowlist(ALLOWED_LABELS.toList())
            .setMaxResults(MAX_RESULTS)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        return ObjectDetector.createFromOptions(appContext, options).also {
            detector = it
            logger.info(
                "Object detector initialized",
                mapOf(
                    "model" to "EfficientDet-Lite2 int8",
                    "userThreshold" to threshold,
                    "modelFloor" to modelThreshold,
                    "temporalHysteresis" to true,
                    "categoryAllowlist" to ALLOWED_LABELS.joinToString(),
                    "maxResults" to MAX_RESULTS
                )
            )
        }
    }

    private fun suppressOverlaps(values: List<DetectionCandidate2D>): List<DetectionCandidate2D> {
        val kept = mutableListOf<DetectionCandidate2D>()
        values.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                if (existing.label != candidate.label) return@any false
                val overlap = overlapMetrics(existing, candidate)
                overlap.iou >= nmsThreshold(candidate.label) ||
                    (candidate.label in CONTAINMENT_SUPPRESSED_LABELS && overlap.smallerCoverage >= CONTAINMENT_THRESHOLD)
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private data class OverlapMetrics(val iou: Float, val smallerCoverage: Float)

    private fun overlapMetrics(a: DetectionCandidate2D, b: DetectionCandidate2D): OverlapMetrics {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return OverlapMetrics(0f, 0f)
        val union = a.area + b.area - intersection
        val smaller = minOf(a.area, b.area).coerceAtLeast(1f)
        return OverlapMetrics(
            iou = if (union > 0f) intersection / union else 0f,
            smallerCoverage = intersection / smaller
        )
    }

    private fun nmsThreshold(label: String): Float = when (label) {
        "person", "car" -> 0.35f
        "bird" -> 0.55f
        else -> 0.45f
    }

    override fun close() {
        if (closed) return
        closed = true
        temporalTracker.clear()
        executor.execute {
            detector?.close()
            detector = null
        }
        executor.shutdown()
    }

    companion object {
        private val ALLOWED_LABELS = linkedSetOf("person", "car", "bird", "dog", "cat")
        private val CONTAINMENT_SUPPRESSED_LABELS = setOf("person", "car")
        private const val CONTAINMENT_THRESHOLD = 0.72f
        private const val MIN_MODEL_SCORE_THRESHOLD = 0.10f
        private const val BOTTOM_CENTER_INSET = 0.04f
        private const val MAX_RESULTS = 40
    }
}
