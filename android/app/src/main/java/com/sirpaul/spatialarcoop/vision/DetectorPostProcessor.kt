package com.sirpaul.spatialarcoop.vision

import android.graphics.RectF
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

internal object DetectorPostProcessor {
    val allowedLabels = linkedSetOf("person", "car", "bird", "dog", "cat")
    private val containmentSuppressedLabels = setOf("person", "car")

    fun candidates(
        result: ObjectDetectorResult,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int
    ): List<DetectionCandidate2D> {
        val raw = result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val label = category.categoryName().lowercase()
            if (label !in allowedLabels) return@mapNotNull null
            val box = detection.boundingBox()
            val corners = arrayOf(
                YuvFrameConverter.rotatedToRaw(box.left, box.top, rawWidth, rawHeight, rotationDegrees),
                YuvFrameConverter.rotatedToRaw(box.right, box.top, rawWidth, rawHeight, rotationDegrees),
                YuvFrameConverter.rotatedToRaw(box.right, box.bottom, rawWidth, rawHeight, rotationDegrees),
                YuvFrameConverter.rotatedToRaw(box.left, box.bottom, rawWidth, rawHeight, rotationDegrees)
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
        return suppressOverlaps(raw)
    }

    fun detections(
        tracked: List<TrackedDetection2D>,
        capturedAtMs: Long,
        rawWidth: Int,
        rawHeight: Int,
        captureGeometry: CaptureGeometry?,
        poses: Map<String, List<PoseLandmark2D>>
    ): List<Detection2D> = tracked.filter { it.confirmed }.map { detection ->
        val rawBox = RectF(detection.left, detection.top, detection.right, detection.bottom)
        val pose = poses[detection.temporalId].orEmpty()
        val contact = if (detection.label == "person") PersonPoseDetector.groundContact(pose) else null
        Detection2D(
            label = detection.label,
            confidence = detection.confidence,
            rawBoundingBox = rawBox,
            rawBottomCenter = contact ?: floatArrayOf(
                rawBox.centerX(),
                rawBox.bottom - rawBox.height() * BOTTOM_CENTER_INSET
            ),
            capturedAtMs = capturedAtMs,
            rawImageWidth = rawWidth,
            rawImageHeight = rawHeight,
            temporalId = detection.temporalId,
            temporallyConfirmed = true,
            captureGeometry = captureGeometry,
            poseLandmarks = pose
        )
    }

    private fun suppressOverlaps(values: List<DetectionCandidate2D>): List<DetectionCandidate2D> {
        val kept = mutableListOf<DetectionCandidate2D>()
        values.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                if (existing.label != candidate.label) return@any false
                val overlap = overlap(existing, candidate)
                overlap.iou >= nmsThreshold(candidate.label) ||
                    (candidate.label in containmentSuppressedLabels && overlap.smallerCoverage >= CONTAINMENT_THRESHOLD)
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private data class Overlap(val iou: Float, val smallerCoverage: Float)

    private fun overlap(a: DetectionCandidate2D, b: DetectionCandidate2D): Overlap {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return Overlap(0f, 0f)
        val union = a.area + b.area - intersection
        val smaller = minOf(a.area, b.area).coerceAtLeast(1f)
        return Overlap(if (union > 0f) intersection / union else 0f, intersection / smaller)
    }

    private fun nmsThreshold(label: String): Float = when (label) {
        "person", "car" -> 0.35f
        "bird" -> 0.55f
        else -> 0.45f
    }

    private const val CONTAINMENT_THRESHOLD = 0.72f
    private const val BOTTOM_CENTER_INSET = 0.04f
}
