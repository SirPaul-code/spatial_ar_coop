package com.sirpaul.spatialarcoop.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
 * Established objects are allowed to coast very briefly through detector misses. Motion is
 * predicted in image space and then corrected when the next detector result arrives. This keeps
 * boxes and downstream 3D association attached to moving people/cars instead of visibly snapping
 * behind them at inference cadence. Weak observations may maintain an existing identity but cannot
 * create a new object on their own.
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
        var velocityX: Float,
        var velocityY: Float,
        var velocityWidth: Float,
        var velocityHeight: Float,
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
        val outputs = linkedMapOf<String, TrackedDetection2D>()

        candidates.sortedByDescending { it.confidence }.forEach { candidate ->
            if (candidate.confidence < maintainThreshold(candidate.label)) return@forEach

            val match = available
                .asSequence()
                .filter { it.label == candidate.label }
                .map { state -> state to associationCost(predictedBox(state, capturedAtMs), candidate) }
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
                    velocityX = 0f,
                    velocityY = 0f,
                    velocityWidth = 0f,
                    velocityHeight = 0f,
                    lastSeenAtMs = capturedAtMs,
                    hitCount = 1,
                    strongHitCount = 1,
                    missedUpdates = 0
                ).also { states[it.id] = it }
            }

            outputs[state.id] = state.toOutput(state.box(), state.confidence)
        }

        // A detector miss should not instantly drop a confirmed physical object. Coast only for a
        // bounded window; confidence decays quickly and tentative tracks never coast visibly.
        states.values.forEach { state ->
            if (state.id in outputs || !isConfirmed(state)) return@forEach
            val ageMs = (capturedAtMs - state.lastSeenAtMs).coerceAtLeast(0L)
            if (state.missedUpdates > COAST_MAX_MISSES || ageMs > COAST_HOLD_MS) return@forEach
            val decay = COAST_CONFIDENCE_DECAY.pow(state.missedUpdates.coerceAtLeast(1))
            val coastConfidence = state.confidence * decay
            if (coastConfidence < maintainThreshold(state.label)) return@forEach
            outputs[state.id] = state.toOutput(predictedBox(state, capturedAtMs), coastConfidence)
        }

        expire(capturedAtMs)
        return outputs.values.toList()
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun updateState(state: State, candidate: DetectionCandidate2D, capturedAtMs: Long) {
        val old = state.box()
        val predicted = predictedBox(state, capturedAtMs)
        val dt = ((capturedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(0.6f)
        val normalizedMotion = normalizedCenterDistance(predicted, candidate)
        val alpha = when {
            normalizedMotion > 0.85f -> 0.86f
            normalizedMotion > 0.35f -> 0.72f
            else -> 0.54f
        }

        state.left = lerp(predicted.left, candidate.left, alpha)
        state.top = lerp(predicted.top, candidate.top, alpha)
        state.right = lerp(predicted.right, candidate.right, alpha)
        state.bottom = lerp(predicted.bottom, candidate.bottom, alpha)

        val measuredVx = (candidate.centerX - old.centerX) / dt
        val measuredVy = (candidate.centerY - old.centerY) / dt
        val measuredVw = (candidate.width - old.width) / dt
        val measuredVh = (candidate.height - old.height) / dt
        val velocityAlpha = if (normalizedMotion > 0.35f) 0.58f else 0.34f
        val maxCenterSpeed = max(old.width, old.height) * MAX_CENTER_SPEED_BOXES_PER_SECOND
        val maxSizeSpeed = max(old.width, old.height) * MAX_SIZE_SPEED_BOXES_PER_SECOND
        state.velocityX = lerp(state.velocityX, measuredVx.coerceIn(-maxCenterSpeed, maxCenterSpeed), velocityAlpha)
        state.velocityY = lerp(state.velocityY, measuredVy.coerceIn(-maxCenterSpeed, maxCenterSpeed), velocityAlpha)
        state.velocityWidth = lerp(state.velocityWidth, measuredVw.coerceIn(-maxSizeSpeed, maxSizeSpeed), velocityAlpha)
        state.velocityHeight = lerp(state.velocityHeight, measuredVh.coerceIn(-maxSizeSpeed, maxSizeSpeed), velocityAlpha)

        state.confidence = state.confidence * 0.35f + candidate.confidence * 0.65f
        state.lastSeenAtMs = capturedAtMs
        state.hitCount += 1
        state.missedUpdates = 0
        if (candidate.confidence >= spawnThreshold(candidate.label)) state.strongHitCount += 1
    }

    private fun predictedBox(state: State, atMs: Long): DetectionCandidate2D {
        val dt = ((atMs - state.lastSeenAtMs).coerceAtLeast(0L) / 1000f).coerceAtMost(MAX_IMAGE_PREDICTION_SECONDS)
        if (dt <= 0f) return state.box()
        val current = state.box()
        val centerX = current.centerX + state.velocityX * dt
        val centerY = current.centerY + state.velocityY * dt
        val width = (current.width + state.velocityWidth * dt).coerceIn(current.width * 0.72f, current.width * 1.38f)
        val height = (current.height + state.velocityHeight * dt).coerceIn(current.height * 0.72f, current.height * 1.38f)
        return DetectionCandidate2D(
            label = state.label,
            confidence = state.confidence,
            left = centerX - width * 0.5f,
            top = centerY - height * 0.5f,
            right = centerX + width * 0.5f,
            bottom = centerY + height * 0.5f
        )
    }

    private fun State.toOutput(box: DetectionCandidate2D, outputConfidence: Float) = TrackedDetection2D(
        temporalId = id,
        label = label,
        confidence = outputConfidence.coerceIn(0f, 1f),
        left = box.left,
        top = box.top,
        right = box.right,
        bottom = box.bottom,
        confirmed = isConfirmed(this),
        hitCount = hitCount
    )

    private fun isConfirmed(state: State): Boolean = when (state.label) {
        "person" -> state.hitCount >= PERSON_CONFIRMATION_HITS && state.strongHitCount >= 1
        "car" -> state.hitCount >= CAR_CONFIRMATION_HITS && state.strongHitCount >= 2
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
        return iouCost * 0.65f + centerCost * 0.35f
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
        "bird" -> maxOf(BIRD_SPAWN_THRESHOLD, minOf(userThreshold, BIRD_USER_THRESHOLD_CAP))
        // A person at demo distance often lands in the 0.4-0.6 range. Temporal confirmation still
        // prevents a one-frame weak hit from becoming shared state.
        "person" -> maxOf(minOf(userThreshold, PERSON_USER_THRESHOLD_CAP), PERSON_SPAWN_THRESHOLD)
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
        private const val BIRD_USER_THRESHOLD_CAP = 0.28f
        private const val PERSON_SPAWN_THRESHOLD = 0.44f
        private const val PERSON_USER_THRESHOLD_CAP = 0.48f
        private const val CAR_SPAWN_THRESHOLD = 0.52f
        private const val OTHER_SPAWN_THRESHOLD = 0.50f
        private const val BIRD_MAINTAIN_THRESHOLD = 0.10f
        private const val PERSON_CAR_MAINTAIN_THRESHOLD = 0.24f
        private const val OTHER_MAINTAIN_THRESHOLD = 0.26f
        private const val PERSON_CONFIRMATION_HITS = 2
        private const val CAR_CONFIRMATION_HITS = 3
        private const val BIRD_CONFIRMATION_HITS = 3
        private const val OTHER_CONFIRMATION_HITS = 2
        private const val TENTATIVE_MAX_MISSES = 1
        private const val COAST_MAX_MISSES = 4
        private const val COAST_HOLD_MS = 520L
        private const val COAST_CONFIDENCE_DECAY = 0.82f
        private const val MIN_ASSOCIATION_IOU = 0.05f
        private const val GENERAL_CENTER_LIMIT = 1.30f
        private const val BIRD_CENTER_LIMIT = 1.55f
        private const val MAX_AREA_RATIO = 4.8f
        private const val MIN_NORMALIZATION_PIXELS = 24f
        private const val STATE_RETENTION_MS = 1_200L
        private const val MAX_IMAGE_PREDICTION_SECONDS = 0.45f
        private const val MAX_CENTER_SPEED_BOXES_PER_SECOND = 7.5f
        private const val MAX_SIZE_SPEED_BOXES_PER_SECOND = 4.0f
    }
}
