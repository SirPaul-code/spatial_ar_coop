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
 * Acquisition and maintenance deliberately use different thresholds. A low-confidence detection
 * may maintain an already-established identity through wire mesh/partial occlusion, but it may not
 * create a new visible/shared object. This prevents persistent background clutter from eventually
 * becoming a stable false car/bird merely because it was misclassified several times.
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
        var hitCount: Int,
        var strongHitCount: Int,
        var missedUpdates: Int
    ) {
        fun box() = DetectionCandidate2D(label, confidence, left, top, right, bottom)
    }

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(candidates: List<DetectionCandidate2D>, capturedAtMs: Long): List<TrackedDetection2D> {
        expire(capturedAtMs)
        states.values.forEach { it.missedUpdates += 1 }
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
                val strong = candidate.confidence >= spawnThreshold(candidate.label)
                if (!strong) return@forEach
                State(
                    id = "d${nextId++}",
                    label = candidate.label,
                    confidence = candidate.confidence,
                    left = candidate.left,
                    top = candidate.top,
                    right = candidate.right,
                    bottom = candidate.bottom,
                    lastSeenAtMs = capturedAtMs,
                    hitCount = 1,
                    strongHitCount = 1,
                    missedUpdates = 0
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
            normalizedMotion > 0.9f -> 0.78f
            normalizedMotion > 0.4f -> 0.62f
            else -> 0.42f
        }
        state.left = lerp(state.left, candidate.left, alpha)
        state.top = lerp(state.top, candidate.top, alpha)
        state.right = lerp(state.right, candidate.right, alpha)
        state.bottom = lerp(state.bottom, candidate.bottom, alpha)
        state.confidence = state.confidence * 0.42f + candidate.confidence * 0.58f
        state.lastSeenAtMs = capturedAtMs
        state.hitCount += 1
        state.missedUpdates = 0
        if (candidate.confidence >= spawnThreshold(candidate.label)) state.strongHitCount += 1
    }

    private fun State.toOutput() = TrackedDetection2D(
        temporalId = id,
        label = label,
        confidence = confidence,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        confirmed = isConfirmed(this),
        hitCount = hitCount
    )

    private fun isConfirmed(state: State): Boolean = when (state.label) {
        "person", "car" -> state.hitCount >= PERSON_CAR_CONFIRMATION_HITS && state.strongHitCount >= 2
        "bird" -> state.hitCount >= BIRD_CONFIRMATION_HITS && state.strongHitCount >= 1
        else -> state.hitCount >= OTHER_CONFIRMATION_HITS && state.strongHitCount >= 1
    }

    private fun associationCost(a: DetectionCandidate2D, b: DetectionCandidate2D): Float {
        val areaRatio = max(a.area, b.area) / min(a.area, b.area).coerceAtLeast(1f)
        if (!areaRatio.isFinite() || areaRatio > MAX_AREA_RATIO) return Float.POSITIVE_INFINITY

        val iou = intersectionOverUnion(a, b)
        val center = normalizedCenterDistance(a, b)
        val centerLimit = if (a.label == "bird") BIRD_CENTER_LIMIT else GENERAL_CENTER_LIMIT
        if (iou < MIN_ASSOCIATION_IOU && center > centerLimit) return Float.POSITIVE_INFINITY

        val iouCost = 1f - iou
        val centerCost = (center / centerLimit).coerceIn(0f, 1.5f)
        return iouCost * 0.72f + centerCost * 0.28f
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
        "bird" -> maxOf(userThreshold, BIRD_SPAWN_THRESHOLD)
        "person" -> maxOf(userThreshold, PERSON_SPAWN_THRESHOLD)
        "car" -> maxOf(userThreshold, CAR_SPAWN_THRESHOLD)
        else -> maxOf(userThreshold, OTHER_SPAWN_THRESHOLD)
    }

    private fun maintainThreshold(label: String): Float = when (label) {
        "bird" -> BIRD_MAINTAIN_THRESHOLD
        "person", "car" -> PERSON_CAR_MAINTAIN_THRESHOLD
        else -> OTHER_MAINTAIN_THRESHOLD
    }

    private fun expire(nowMs: Long) {
        states.entries.removeIf { (_, state) ->
            val stale = nowMs - state.lastSeenAtMs > STATE_RETENTION_MS
            val tentativeLost = !isConfirmed(state) && state.missedUpdates > TENTATIVE_MAX_MISSES
            stale || tentativeLost
        }
    }

    private fun lerp(a: Float, b: Float, alpha: Float): Float = a + (b - a) * alpha

    companion object {
        private const val BIRD_SPAWN_THRESHOLD = 0.24f
        private const val PERSON_SPAWN_THRESHOLD = 0.60f
        private const val CAR_SPAWN_THRESHOLD = 0.52f
        private const val OTHER_SPAWN_THRESHOLD = 0.50f
        private const val BIRD_MAINTAIN_THRESHOLD = 0.10f
        private const val PERSON_CAR_MAINTAIN_THRESHOLD = 0.32f
        private const val OTHER_MAINTAIN_THRESHOLD = 0.28f
        private const val PERSON_CAR_CONFIRMATION_HITS = 3
        private const val BIRD_CONFIRMATION_HITS = 3
        private const val OTHER_CONFIRMATION_HITS = 2
        private const val TENTATIVE_MAX_MISSES = 1
        private const val MIN_ASSOCIATION_IOU = 0.08f
        private const val GENERAL_CENTER_LIMIT = 1.10f
        private const val BIRD_CENTER_LIMIT = 1.45f
        private const val MAX_AREA_RATIO = 3.8f
        private const val MIN_NORMALIZATION_PIXELS = 24f
        private const val STATE_RETENTION_MS = 1_200L
    }
}
