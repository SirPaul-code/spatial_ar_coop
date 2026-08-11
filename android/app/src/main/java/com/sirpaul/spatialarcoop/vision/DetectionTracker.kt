package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.SpatialTrack
import com.sirpaul.spatialarcoop.data.defaultTrackExtent
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

data class SpatialObservation(
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val observedAtMs: Long,
    val uncertaintyMeters: Float = 0.35f,
    val associationKey: String? = null,
    val extentMeters: FloatArray = defaultTrackExtent(label),
    val yawRadians: Float = 0f,
    val requiredHits: Int = 2
)

class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var associationKey: String?,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
        var extentMeters: FloatArray,
        var yawRadians: Float,
        var requiredHits: Int,
        var lastSeenAtMs: Long,
        var hitCount: Int,
        var rejectedMeasurements: Int
    )

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<SpatialObservation>, nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        val unmatched = states.values.toMutableSet()

        observations.sortedByDescending { it.confidence }.forEach { observation ->
            val hinted = observation.associationKey?.let { key ->
                unmatched.firstOrNull { it.label == observation.label && it.associationKey == key }
            }
            val spatial = hinted ?: bestSpatialCandidate(unmatched, observation)
            val reacquired = spatial ?: bestConservativeReacquire(unmatched, observation)

            if (reacquired == null) {
                val id = "t${nextId++}"
                states[id] = State(
                    id = id,
                    label = observation.label,
                    associationKey = observation.associationKey,
                    confidence = observation.confidence,
                    position = observation.position.copyOf(),
                    velocity = floatArrayOf(0f, 0f, 0f),
                    uncertaintyMeters = observation.uncertaintyMeters,
                    extentMeters = observation.extentMeters.copyOf(),
                    yawRadians = normalizeAngle(observation.yawRadians),
                    requiredHits = observation.requiredHits.coerceIn(2, 6),
                    lastSeenAtMs = observation.observedAtMs,
                    hitCount = 1,
                    rejectedMeasurements = 0
                )
            } else {
                unmatched.remove(reacquired)
                applyObservation(reacquired, observation)
            }
        }

        expire(nowMs)
        return states.values.filter(::isPublishable).map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        return states.values.filter(::isPublishable).map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun isPublishable(state: State): Boolean = state.hitCount >= state.requiredHits

    private fun bestSpatialCandidate(unmatched: Set<State>, observation: SpatialObservation): State? =
        unmatched
            .asSequence()
            .filter { it.label == observation.label }
            .map { it to predictedDistance(it, observation) }
            .filter { (state, distance) -> distance <= associationRadius(state, observation) }
            .minByOrNull { it.second }
            ?.first

    private fun bestConservativeReacquire(unmatched: Set<State>, observation: SpatialObservation): State? {
        val candidates = unmatched
            .asSequence()
            .filter { it.label == observation.label }
            .map { it to predictedDistance(it, observation) }
            .filter { (_, distance) -> distance <= reacquireRadius(observation.label) }
            .sortedBy { it.second }
            .toList()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first().first
        val first = candidates[0]
        val second = candidates[1]
        return if (first.second + REACQUIRE_MARGIN_METERS < second.second * REACQUIRE_RATIO) first.first else null
    }

    private fun applyObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(1.5f)
        val predicted = predictedPosition(state, observation.observedAtMs)
        val residual = FloatArray(3) { index -> observation.position[index] - predicted[index] }
        val residualDistance = magnitude(residual)
        val gate = measurementGate(state, observation, dt)

        state.associationKey = observation.associationKey ?: state.associationKey
        state.confidence = state.confidence * 0.45f + observation.confidence * 0.55f

        if (residualDistance > gate) {
            state.position = predicted
            state.rejectedMeasurements += 1
            val damping = if (state.rejectedMeasurements >= 2) 0f else 0.25f
            state.velocity = FloatArray(3) { index -> state.velocity[index] * damping }
            state.uncertaintyMeters = (maxOf(state.uncertaintyMeters, observation.uncertaintyMeters) + 0.10f)
                .coerceAtMost(MAX_UNCERTAINTY_METERS)
            state.lastSeenAtMs = observation.observedAtMs
            return
        }

        state.rejectedMeasurements = 0
        val apparentSpeed = residualDistance / dt
        val alpha = positionAlpha(residualDistance, apparentSpeed, observation.uncertaintyMeters)
        val corrected = FloatArray(3) { index -> predicted[index] + alpha * residual[index] }

        val measuredVelocity = FloatArray(3) { index -> residual[index] / dt }
        val beta = if (apparentSpeed > 1.5f) 0.20f else 0.10f
        var velocity = FloatArray(3) { index ->
            state.velocity[index] * (1f - beta) + measuredVelocity[index] * beta
        }
        velocity = clampMagnitude(velocity, maxSpeed(observation.label))
        if (residualDistance < STATIONARY_RESIDUAL_METERS && magnitude(velocity) < STATIONARY_SPEED_METERS_PER_SECOND) {
            velocity = floatArrayOf(0f, 0f, 0f)
        }

        state.position = corrected
        state.velocity = velocity
        state.uncertaintyMeters = state.uncertaintyMeters * 0.55f + observation.uncertaintyMeters * 0.45f
        state.extentMeters = FloatArray(3) { index ->
            state.extentMeters.getOrElse(index) { observation.extentMeters[index] } * 0.78f +
                observation.extentMeters[index] * 0.22f
        }
        val motionYaw = if (observation.label == "car" && magnitude(velocity) > CAR_YAW_FROM_SPEED_METERS_PER_SECOND) {
            // Renderer convention: local +Z has shared-site bearing -yaw.
            -atan2(velocity[0], velocity[2])
        } else null
        state.yawRadians = blendAngle(state.yawRadians, motionYaw ?: observation.yawRadians, 0.16f)
        // A later high-quality ground/plane measurement may reduce the confirmation requirement of
        // a track that initially started from the conservative monocular fallback.
        state.requiredHits = min(state.requiredHits, observation.requiredHits.coerceIn(2, 6))
        state.lastSeenAtMs = observation.observedAtMs
        state.hitCount += 1
    }

    private fun positionAlpha(residualDistance: Float, apparentSpeed: Float, uncertaintyMeters: Float): Float {
        if (residualDistance < POSITION_DEADBAND_METERS) return 0.06f
        val quality = (1f - (uncertaintyMeters / 1.4f)).coerceIn(0f, 1f)
        val motionBoost = (apparentSpeed / 4f).coerceIn(0f, 1f) * 0.24f
        return (0.18f + quality * 0.18f + motionBoost).coerceIn(0.16f, 0.56f)
    }

    private fun measurementGate(state: State, observation: SpatialObservation, dt: Float): Float {
        val base = when (observation.label) {
            "car" -> 0.95f
            "person" -> 0.68f
            "bird" -> 0.40f
            else -> 0.58f
        }
        val uncertainty = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.8f)
        val motionAllowance = maxSpeed(observation.label) * dt * 0.40f
        val maxGate = when (observation.label) {
            "car" -> 3.2f
            "person" -> 1.8f
            "bird" -> 1.1f
            else -> 1.7f
        }
        return (base + uncertainty * 1.20f + motionAllowance).coerceAtMost(maxGate)
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
        val base = when (observation.label) {
            "car" -> 1.05f
            "person" -> 0.78f
            "bird" -> 0.42f
            else -> 0.68f
        }
        val uncertaintyAllowance = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.4f)
        return (base + ageSeconds * 0.50f + uncertaintyAllowance).coerceAtMost(2.5f)
    }

    private fun reacquireRadius(label: String): Float = when (label) {
        "car" -> 4.5f
        "person" -> 2.4f
        "bird" -> 1.0f
        else -> 1.7f
    }

    private fun maxSpeed(label: String): Float = when (label) {
        "car" -> 28f
        "person" -> 7f
        "bird" -> 12f
        "dog" -> 11f
        "cat" -> 9f
        else -> 10f
    }

    private fun clampMagnitude(vector: FloatArray, maximum: Float): FloatArray {
        val length = magnitude(vector)
        if (!length.isFinite() || length <= maximum || length <= 0f) return vector
        val scale = maximum / length
        return FloatArray(3) { index -> vector[index] * scale }
    }

    private fun magnitude(vector: FloatArray): Float {
        var squared = 0f
        for (value in vector) squared += value * value
        return sqrt(squared)
    }

    private fun blendAngle(current: Float, target: Float, alpha: Float): Float {
        val delta = normalizeAngle(target - current)
        return normalizeAngle(current + delta * alpha)
    }

    private fun normalizeAngle(value: Float): Float {
        var result = value
        val pi = PI.toFloat()
        while (result > pi) result -= 2f * pi
        while (result < -pi) result += 2f * pi
        return result
    }

    private fun expire(nowMs: Long) {
        states.entries.removeIf { nowMs - it.value.lastSeenAtMs > TRACK_TIMEOUT_MS }
    }

    private fun toPublicTrack(state: State, nowMs: Long): SpatialTrack {
        val ageMs = (nowMs - state.lastSeenAtMs).coerceAtLeast(0L)
        val confidenceDecay = (1f - (ageMs.toFloat() / TRACK_TIMEOUT_MS) * 0.58f).coerceIn(0.30f, 1f)
        return SpatialTrack(
            key = "$sourceId:${state.id}",
            id = state.id,
            sourceId = sourceId,
            label = state.label,
            confidence = state.confidence * confidenceDecay,
            position = predictedPosition(state, nowMs),
            velocity = state.velocity.copyOf(),
            uncertaintyMeters = state.uncertaintyMeters + (ageMs / 1000f) * 0.10f,
            observedAtMs = nowMs,
            extentMeters = state.extentMeters.copyOf(),
            yawRadians = state.yawRadians
        )
    }

    companion object {
        private const val POSITION_DEADBAND_METERS = 0.08f
        private const val STATIONARY_RESIDUAL_METERS = 0.20f
        private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.22f
        private const val CAR_YAW_FROM_SPEED_METERS_PER_SECOND = 1.0f
        private const val MAX_UNCERTAINTY_METERS = 3.0f
        private const val MAX_PREDICTION_SECONDS = 0.25f
        private const val TRACK_TIMEOUT_MS = 1_500L
        private const val REACQUIRE_RATIO = 0.70f
        private const val REACQUIRE_MARGIN_METERS = 0.20f
    }
}
