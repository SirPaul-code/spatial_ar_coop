package com.sirpaul.spatialarcoop.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure image-space detection used before Android/ARCore objects enter the pipeline. */
data class DetectionCandidate2D(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height
}

data class TrackedDetection2D(
    val temporalId: String,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confirmed: Boolean,
    val hitCount: Int
)

/**
 * Lightweight ByteTrack-style front-end for detector output.
 *
 * New objects need a class-specific high-confidence observation. Once an object exists, lower
 * confidence observations are allowed to maintain the same image-space identity. This is useful
 * for chickens behind wire mesh and for partial occlusion, while preventing isolated low-score
 * detections from becoming networked 3D tracks.
 */
class TemporalDetectionTracker(private val userThreshold: Float) {
    private data class State(
        val id: String,
        val label: String,
        var confidence: Float,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        var lastSeenAtMs: Long,
        var hitCount: Int
    ) {
        fun box() = DetectionCandidate2D(label, confidence, left, top, right, bottom)
    }

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(candidates: List<DetectionCandidate2D>, capturedAtMs: Long): List<TrackedDetection2D> {
        expire(capturedAtMs)
        val available = states.values.toMutableSet()
        val outputs = mutableListOf<TrackedDetection2D>()

        candidates.sortedByDescending { it.confidence }.forEach { candidate ->
            if (candidate.confidence < maintainThreshold(candidate.label)) return@forEach

            val match = available
                .asSequence()
                .filter { it.label == candidate.label }
                .map { state -> state to associationCost(state.box(), candidate) }
                .filter { (_, cost) -> cost.isFinite() }
                .minByOrNull { it.second }
                ?.first

            val state = if (match != null) {
                available.remove(match)
                updateState(match, candidate, capturedAtMs)
                match
            } else {
                if (candidate.confidence < spawnThreshold(candidate.label)) return@forEach
                State(
                    id = "d${nextId++}",
                    label = candidate.label,
                    confidence = candidate.confidence,
                    left = candidate.left,
                    top = candidate.top,
                    right = candidate.right,
                    bottom = candidate.bottom,
                    lastSeenAtMs = capturedAtMs,
                    hitCount = 1
                ).also { states[it.id] = it }
            }

            outputs += state.toOutput()
        }

        expire(capturedAtMs)
        return outputs
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun updateState(state: State, candidate: DetectionCandidate2D, capturedAtMs: Long) {
        val old = state.box()
        val normalizedMotion = normalizedCenterDistance(old, candidate)
        val alpha = when {
            normalizedMotion > 0.9f -> 0.82f
            normalizedMotion > 0.4f -> 0.66f
            else -> 0.48f
        }
        state.left = lerp(state.left, candidate.left, alpha)
        state.top = lerp(state.top, candidate.top, alpha)
        state.right = lerp(state.right, candidate.right, alpha)
        state.bottom = lerp(state.bottom, candidate.bottom, alpha)
        state.confidence = state.confidence * 0.35f + candidate.confidence * 0.65f
        state.lastSeenAtMs = capturedAtMs
        state.hitCount += 1
    }

    private fun State.toOutput() = TrackedDetection2D(
        temporalId = id,
        label = label,
        confidence = confidence,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        confirmed = hitCount >= confirmationHits(label),
        hitCount = hitCount
    )

    private fun associationCost(a: DetectionCandidate2D, b: DetectionCandidate2D): Float {
        val areaRatio = max(a.area, b.area) / min(a.area, b.area).coerceAtLeast(1f)
        if (!areaRatio.isFinite() || areaRatio > MAX_AREA_RATIO) return Float.POSITIVE_INFINITY

        val iou = intersectionOverUnion(a, b)
        val center = normalizedCenterDistance(a, b)
        val centerLimit = if (a.label == "bird") BIRD_CENTER_LIMIT else GENERAL_CENTER_LIMIT
        if (iou < MIN_ASSOCIATION_IOU && center > centerLimit) return Float.POSITIVE_INFINITY

        val iouCost = 1f - iou
        val centerCost = (center / centerLimit).coerceIn(0f, 1.5f)
        return iouCost * 0.68f + centerCost * 0.32f
    }

    private fun normalizedCenterDistance(a: DetectionCandidate2D, b: DetectionCandidate2D): Float {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        val distance = sqrt(dx * dx + dy * dy)
        val scale = max(
            sqrt(a.width * a.width + a.height * a.height),
            sqrt(b.width * b.width + b.height * b.height)
        ).coerceAtLeast(MIN_NORMALIZATION_PIXELS)
        return distance / scale
    }

    private fun intersectionOverUnion(a: DetectionCandidate2D, b: DetectionCandidate2D): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val union = a.area + b.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun spawnThreshold(label: String): Float = when (label) {
        // COCO "bird" is deliberately permissive: temporal confirmation, not a single low score,
        // is what promotes a chicken into the spatial pipeline.
        "bird" -> minOf(userThreshold, BIRD_SPAWN_THRESHOLD)
        "person", "car" -> maxOf(userThreshold, PERSON_CAR_SPAWN_THRESHOLD)
        else -> maxOf(userThreshold, OTHER_SPAWN_THRESHOLD)
    }

    private fun maintainThreshold(label: String): Float = when (label) {
        "bird" -> BIRD_MAINTAIN_THRESHOLD
        "person", "car" -> PERSON_CAR_MAINTAIN_THRESHOLD
        else -> OTHER_MAINTAIN_THRESHOLD
    }

    private fun confirmationHits(label: String): Int = when (label) {
        "bird" -> 2
        else -> 2
    }

    private fun expire(nowMs: Long) {
        states.entries.removeIf { nowMs - it.value.lastSeenAtMs > STATE_RETENTION_MS }
    }

    private fun lerp(a: Float, b: Float, alpha: Float): Float = a + (b - a) * alpha

    companion object {
        private const val BIRD_SPAWN_THRESHOLD = 0.18f
        private const val PERSON_CAR_SPAWN_THRESHOLD = 0.56f
        private const val OTHER_SPAWN_THRESHOLD = 0.48f
        private const val BIRD_MAINTAIN_THRESHOLD = 0.10f
        private const val PERSON_CAR_MAINTAIN_THRESHOLD = 0.30f
        private const val OTHER_MAINTAIN_THRESHOLD = 0.24f
        private const val MIN_ASSOCIATION_IOU = 0.06f
        private const val GENERAL_CENTER_LIMIT = 1.25f
        private const val BIRD_CENTER_LIMIT = 1.75f
        private const val MAX_AREA_RATIO = 4.5f
        private const val MIN_NORMALIZATION_PIXELS = 24f
        private const val STATE_RETENTION_MS = 850L
    }
}
