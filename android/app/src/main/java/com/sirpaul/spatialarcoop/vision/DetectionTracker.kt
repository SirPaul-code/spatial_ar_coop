package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.SpatialTrack
import kotlin.math.sqrt

data class SpatialObservation(
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val observedAtMs: Long,
    val uncertaintyMeters: Float = 0.35f
)

class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
        var observedAtMs: Long
    )

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<SpatialObservation>, nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        val unmatched = states.values.toMutableSet()
        observations.sortedByDescending { it.confidence }.forEach { observation ->
            val candidate = unmatched
                .filter { it.label == observation.label }
                .minByOrNull { predictedDistance(it, observation) }
                ?.takeIf { predictedDistance(it, observation) <= associationRadius(it, observation) }
            if (candidate == null) {
                val id = "t${nextId++}"
                states[id] = State(
                    id = id,
                    label = observation.label,
                    confidence = observation.confidence,
                    position = observation.position.copyOf(),
                    velocity = floatArrayOf(0f, 0f, 0f),
                    uncertaintyMeters = observation.uncertaintyMeters,
                    observedAtMs = observation.observedAtMs
                )
            } else {
                unmatched.remove(candidate)
                applyObservation(candidate, observation)
            }
        }
        expire(nowMs)
        return states.values.map(::toPublicTrack)
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        return states.values.map(::toPublicTrack)
    }

    private fun applyObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.observedAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(1.5f)
        val predicted = FloatArray(3) { index -> state.position[index] + state.velocity[index] * dt }
        val residual = FloatArray(3) { index -> observation.position[index] - predicted[index] }
        state.position = FloatArray(3) { index -> predicted[index] + POSITION_ALPHA * residual[index] }
        state.velocity = FloatArray(3) { index ->
            val updated = state.velocity[index] + VELOCITY_BETA / dt * residual[index]
            updated.coerceIn(-MAX_SPEED_METERS_PER_SECOND, MAX_SPEED_METERS_PER_SECOND)
        }
        state.confidence = state.confidence * 0.35f + observation.confidence * 0.65f
        state.uncertaintyMeters = state.uncertaintyMeters * 0.4f + observation.uncertaintyMeters * 0.6f
        state.observedAtMs = observation.observedAtMs
    }

    private fun predictedDistance(state: State, observation: SpatialObservation): Float {
        val dt = ((observation.observedAtMs - state.observedAtMs).coerceAtLeast(0L) / 1000f).coerceAtMost(1.5f)
        var squared = 0f
        for (index in 0..2) {
            val delta = observation.position[index] - (state.position[index] + state.velocity[index] * dt)
            squared += delta * delta
        }
        return sqrt(squared)
    }

    private fun associationRadius(state: State, observation: SpatialObservation): Float {
        val ageSeconds = ((observation.observedAtMs - state.observedAtMs).coerceAtLeast(0L) / 1000f)
        return (BASE_ASSOCIATION_METERS + ageSeconds * 1.25f + state.uncertaintyMeters + observation.uncertaintyMeters)
            .coerceAtMost(4f)
    }

    private fun expire(nowMs: Long) {
        states.entries.removeIf { nowMs - it.value.observedAtMs > TRACK_TIMEOUT_MS }
    }

    private fun toPublicTrack(state: State): SpatialTrack = SpatialTrack(
        key = "$sourceId:${state.id}",
        id = state.id,
        sourceId = sourceId,
        label = state.label,
        confidence = state.confidence,
        position = state.position.copyOf(),
        velocity = state.velocity.copyOf(),
        uncertaintyMeters = state.uncertaintyMeters,
        observedAtMs = state.observedAtMs
    )

    companion object {
        private const val POSITION_ALPHA = 0.68f
        private const val VELOCITY_BETA = 0.24f
        private const val BASE_ASSOCIATION_METERS = 0.8f
        private const val MAX_SPEED_METERS_PER_SECOND = 18f
        private const val TRACK_TIMEOUT_MS = 1_400L
    }
}
