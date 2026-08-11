package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.SpatialTrack
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.sqrt

/**
 * Compatibility bridge between SpatialEstimator and SpatialObservation.
 *
 * ArActivity currently constructs SpatialObservation from an EstimatedPosition without explicitly
 * copying Detection2D.temporalId. The estimator returns a unique FloatArray instance for every
 * accepted measurement, so an identity-keyed weak map can carry that hint across the immediately
 * following constructor call without changing the wire protocol or retaining positions long-term.
 */
internal object SpatialAssociationHints {
    private val hints = Collections.synchronizedMap(WeakHashMap<FloatArray, String>())

    fun attach(position: FloatArray, key: String?): FloatArray {
        if (!key.isNullOrBlank()) hints[position] = key
        return position
    }

    fun consume(position: FloatArray): String? = hints.remove(position)
}

data class SpatialObservation(
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val observedAtMs: Long,
    val uncertaintyMeters: Float = 0.35f,
    val associationKey: String? = SpatialAssociationHints.consume(position)
)

/**
 * Multi-object constant-velocity tracker in the shared site frame.
 *
 * Spatial measurements in mobile AR are not Gaussian-perfect: a depth/plane/contact estimate can
 * occasionally jump by metres. The tracker therefore treats association and measurement acceptance
 * as separate decisions. A visually plausible re-acquisition may keep an identity alive while an
 * implausible 3D sample is rejected instead of becoming a new published ghost track.
 */
class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var associationKey: String?,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
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
        return states.values.filter { it.hitCount >= CONFIRMATION_HITS }.map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        return states.values.filter { it.hitCount >= CONFIRMATION_HITS }.map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun bestSpatialCandidate(unmatched: Set<State>, observation: SpatialObservation): State? =
        unmatched
            .asSequence()
            .filter { it.label == observation.label }
            .map { it to predictedDistance(it, observation) }
            .filter { (state, distance) -> distance <= associationRadius(state, observation) }
            .minByOrNull { it.second }
            ?.first

    /**
     * If normal gating misses, re-use an existing same-class identity only when the match is
     * unambiguous. This prevents one parked car/person from becoming t1/t3/t6 after a bad depth
     * sample, without collapsing several chickens standing close together into one track.
     */
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
            // Keep the identity but reject the impossible spatial sample. Repeated rejects also kill
            // velocity so a stale motion estimate cannot walk the marker away on its own.
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
        state.lastSeenAtMs = observation.observedAtMs
        state.hitCount += 1
    }

    private fun positionAlpha(residualDistance: Float, apparentSpeed: Float, uncertaintyMeters: Float): Float {
        if (residualDistance < POSITION_DEADBAND_METERS) return 0.08f
        val quality = (1f - (uncertaintyMeters / 1.2f)).coerceIn(0f, 1f)
        val motionBoost = (apparentSpeed / 4f).coerceIn(0f, 1f) * 0.28f
        return (0.20f + quality * 0.18f + motionBoost).coerceIn(0.18f, 0.62f)
    }

    private fun measurementGate(state: State, observation: SpatialObservation, dt: Float): Float {
        val base = when (observation.label) {
            "car" -> 1.10f
            "person" -> 0.75f
            "bird" -> 0.45f
            else -> 0.65f
        }
        val uncertainty = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.8f)
        val motionAllowance = maxSpeed(observation.label) * dt * 0.45f
        val maxGate = when (observation.label) {
            "car" -> 4.0f
            "person" -> 2.2f
            "bird" -> 1.4f
            else -> 2.0f
        }
        return (base + uncertainty * 1.4f + motionAllowance).coerceAtMost(maxGate)
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
            "car" -> 1.20f
            "person" -> 0.90f
            "bird" -> 0.48f
            else -> 0.75f
        }
        val uncertaintyAllowance = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.4f)
        return (base + ageSeconds * 0.55f + uncertaintyAllowance).coerceAtMost(2.8f)
    }

    private fun reacquireRadius(label: String): Float = when (label) {
        "car" -> 6.0f
        "person" -> 3.0f
        "bird" -> 1.25f
        else -> 2.0f
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
            observedAtMs = nowMs
        )
    }

    companion object {
        private const val POSITION_DEADBAND_METERS = 0.07f
        private const val STATIONARY_RESIDUAL_METERS = 0.18f
        private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.20f
        private const val MAX_UNCERTAINTY_METERS = 3.0f
        private const val MAX_PREDICTION_SECONDS = 0.30f
        private const val TRACK_TIMEOUT_MS = 1_500L
        private const val CONFIRMATION_HITS = 2
        private const val REACQUIRE_RATIO = 0.72f
        private const val REACQUIRE_MARGIN_METERS = 0.18f
    }
}
