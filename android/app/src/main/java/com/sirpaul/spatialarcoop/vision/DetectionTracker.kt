package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.SpatialTrack
import kotlin.math.max
import kotlin.math.sqrt

data class SpatialObservation(
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val observedAtMs: Long,
    val uncertaintyMeters: Float = 0.35f,
    val imageBox: FloatArray? = null
)

class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
        var lastSeenAtMs: Long,
        var imageBox: FloatArray?
    )

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<SpatialObservation>, nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        val unmatched = states.values.toMutableSet()
        observations.sortedByDescending { it.confidence }.forEach { observation ->
            val candidate = unmatched.asSequence()
                .filter { it.label == observation.label }
                .mapNotNull { state -> associationScore(state, observation)?.let { state to it } }
                .minByOrNull { it.second }?.first
            if (candidate == null) {
                val id = "t${nextId++}"
                states[id] = State(
                    id, observation.label, observation.confidence, observation.position.copyOf(),
                    floatArrayOf(0f, 0f, 0f), observation.uncertaintyMeters,
                    observation.observedAtMs, observation.imageBox?.copyOf()
                )
            } else {
                unmatched.remove(candidate)
                applyObservation(candidate, observation)
            }
        }
        expire(nowMs)
        return states.values.map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        return states.values.map { toPublicTrack(it, nowMs) }
    }

    @Synchronized fun clear() { states.clear() }

    private fun associationScore(state: State, observation: SpatialObservation): Float? {
        val oldBox = validBox(state.imageBox)
        val newBox = validBox(observation.imageBox)
        val spatialDistance = predictedDistance(state, observation)
        if (oldBox != null && newBox != null) {
            val iou = boxIou(oldBox, newBox)
            val centerDistance = boxCenterDistance(oldBox, newBox)
            if (iou < MIN_IMAGE_IOU && centerDistance > MAX_IMAGE_CENTER_DISTANCE) return null
            val spatialPenalty = (spatialDistance / max(associationRadius(state, observation), 0.5f)).coerceAtMost(3f)
            return (1f - iou) * 0.55f + centerDistance * 1.15f + spatialPenalty * 0.10f
        }
        val radius = associationRadius(state, observation)
        if (spatialDistance > radius) return null
        return spatialDistance / max(radius, 0.1f)
    }

    private fun applyObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(1.5f)
        val predicted = predictedPosition(state, observation.observedAtMs)
        val residual = FloatArray(3) { index -> observation.position[index] - predicted[index] }
        val residualDistance = vectorLength(residual)
        val visualContinuity = strongVisualMatch(state.imageBox, observation.imageBox)
        val jumpGate = spatialJumpGate(state, observation, dt)
        if (visualContinuity && residualDistance > jumpGate) {
            state.position = FloatArray(3) { index -> predicted[index] + residual[index] * OUTLIER_POSITION_ALPHA }
            state.velocity = FloatArray(3) { index -> state.velocity[index] * OUTLIER_VELOCITY_DECAY }
            state.uncertaintyMeters = max(
                max(state.uncertaintyMeters, observation.uncertaintyMeters),
                (residualDistance * 0.22f).coerceAtMost(5f)
            )
        } else {
            state.position = FloatArray(3) { index -> predicted[index] + POSITION_ALPHA * residual[index] }
            state.velocity = FloatArray(3) { index ->
                (state.velocity[index] + VELOCITY_BETA / dt * residual[index])
                    .coerceIn(-MAX_SPEED_METERS_PER_SECOND, MAX_SPEED_METERS_PER_SECOND)
            }
            state.uncertaintyMeters = state.uncertaintyMeters * 0.4f + observation.uncertaintyMeters * 0.6f
        }
        state.confidence = state.confidence * 0.35f + observation.confidence * 0.65f
        state.lastSeenAtMs = observation.observedAtMs
        state.imageBox = observation.imageBox?.copyOf() ?: state.imageBox
    }

    private fun spatialJumpGate(state: State, observation: SpatialObservation, dt: Float): Float {
        val uncertainty = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceIn(0f, 2.2f)
        return (1.15f + uncertainty * 1.15f + dt * 3.0f).coerceIn(1.2f, 4.5f)
    }

    private fun strongVisualMatch(a: FloatArray?, b: FloatArray?): Boolean {
        val first = validBox(a) ?: return false
        val second = validBox(b) ?: return false
        return boxIou(first, second) >= STRONG_IMAGE_IOU || boxCenterDistance(first, second) <= STRONG_CENTER_DISTANCE
    }

    private fun predictedPosition(state: State, atMs: Long): FloatArray {
        val dt = ((atMs - state.lastSeenAtMs).coerceAtLeast(0L) / 1000f).coerceAtMost(MAX_PREDICTION_SECONDS)
        return FloatArray(3) { index -> state.position[index] + state.velocity[index] * dt }
    }

    private fun predictedDistance(state: State, observation: SpatialObservation): Float {
        val predicted = predictedPosition(state, observation.observedAtMs)
        var squared = 0f
        for (index in 0..2) {
            val delta = observation.position[index] - predicted[index]
            squared += delta * delta
        }
        return sqrt(squared)
    }

    private fun associationRadius(state: State, observation: SpatialObservation): Float {
        val ageSeconds = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(0L) / 1000f)
        val base = if (observation.label == "bird") BIRD_ASSOCIATION_METERS else BASE_ASSOCIATION_METERS
        return (base + ageSeconds * 1.1f + state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(4f)
    }

    private fun validBox(value: FloatArray?): FloatArray? = value?.takeIf {
        it.size == 4 && it.all(Float::isFinite) && it[2] > it[0] && it[3] > it[1]
    }

    private fun boxIou(a: FloatArray, b: FloatArray): Float {
        val left = maxOf(a[0], b[0]); val top = maxOf(a[1], b[1])
        val right = minOf(a[2], b[2]); val bottom = minOf(a[3], b[3])
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val areaA = maxOf(0f, a[2] - a[0]) * maxOf(0f, a[3] - a[1])
        val areaB = maxOf(0f, b[2] - b[0]) * maxOf(0f, b[3] - b[1])
        val union = areaA + areaB - intersection
        return if (union <= 1e-6f) 0f else intersection / union
    }

    private fun boxCenterDistance(a: FloatArray, b: FloatArray): Float {
        val dx = (a[0] + a[2] - b[0] - b[2]) * 0.5f
        val dy = (a[1] + a[3] - b[1] - b[3]) * 0.5f
        return sqrt(dx * dx + dy * dy)
    }

    private fun vectorLength(value: FloatArray): Float =
        sqrt(value.fold(0f) { sum, component -> sum + component * component })

    private fun expire(nowMs: Long) { states.entries.removeIf { nowMs - it.value.lastSeenAtMs > TRACK_TIMEOUT_MS } }

    private fun toPublicTrack(state: State, nowMs: Long): SpatialTrack {
        val ageMs = (nowMs - state.lastSeenAtMs).coerceAtLeast(0L)
        val confidenceDecay = (1f - (ageMs.toFloat() / TRACK_TIMEOUT_MS) * 0.55f).coerceIn(0.35f, 1f)
        return SpatialTrack(
            key = "$sourceId:${state.id}", id = state.id, sourceId = sourceId, label = state.label,
            confidence = state.confidence * confidenceDecay, position = predictedPosition(state, nowMs),
            velocity = state.velocity.copyOf(), uncertaintyMeters = state.uncertaintyMeters + (ageMs / 1000f) * 0.12f,
            observedAtMs = state.lastSeenAtMs
        )
    }

    companion object {
        private const val POSITION_ALPHA = 0.62f
        private const val VELOCITY_BETA = 0.18f
        private const val OUTLIER_POSITION_ALPHA = 0.10f
        private const val OUTLIER_VELOCITY_DECAY = 0.55f
        private const val BASE_ASSOCIATION_METERS = 0.9f
        private const val BIRD_ASSOCIATION_METERS = 0.55f
        private const val MIN_IMAGE_IOU = 0.06f
        private const val MAX_IMAGE_CENTER_DISTANCE = 0.26f
        private const val STRONG_IMAGE_IOU = 0.20f
        private const val STRONG_CENTER_DISTANCE = 0.12f
        private const val MAX_SPEED_METERS_PER_SECOND = 12f
        private const val MAX_PREDICTION_SECONDS = 1.0f
        private const val TRACK_TIMEOUT_MS = 1_500L
    }
}
