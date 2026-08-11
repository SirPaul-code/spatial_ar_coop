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

/**
 * Lightweight multi-object alpha/beta tracker in the shared site frame.
 *
 * The internal position remains tied to the last real observation. Public tracks are predicted to
 * `nowMs` for a short bounded dropout window, which keeps birds/people visually continuous between
 * detector frames without pretending that a stale detection was actually re-observed.
 */
class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
        var lastSeenAtMs: Long
    )

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<SpatialObservation>, nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        val unmatched = states.values.toMutableSet()
        observations.sortedByDescending { it.confidence }.forEach { observation ->
            val candidate = unmatched
                .asSequence()
                .filter { it.label == observation.label }
                .map { it to predictedDistance(it, observation) }
                .filter { (state, distance) -> distance <= associationRadius(state, observation) }
                .minByOrNull { it.second }
                ?.first
            if (candidate == null) {
                val id = "t${nextId++}"
                states[id] = State(
                    id = id,
                    label = observation.label,
                    confidence = observation.confidence,
                    position = observation.position.copyOf(),
                    velocity = floatArrayOf(0f, 0f, 0f),
                    uncertaintyMeters = observation.uncertaintyMeters,
                    lastSeenAtMs = observation.observedAtMs
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

    private fun applyObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(1.5f)
        val predicted = predictedPosition(state, observation.observedAtMs)
        val residual = FloatArray(3) { index -> observation.position[index] - predicted[index] }
        state.position = FloatArray(3) { index -> predicted[index] + POSITION_ALPHA * residual[index] }
        state.velocity = FloatArray(3) { index ->
            val updated = state.velocity[index] + VELOCITY_BETA / dt * residual[index]
            updated.coerceIn(-MAX_SPEED_METERS_PER_SECOND, MAX_SPEED_METERS_PER_SECOND)
        }
        state.confidence = state.confidence * 0.35f + observation.confidence * 0.65f
        state.uncertaintyMeters = state.uncertaintyMeters * 0.4f + observation.uncertaintyMeters * 0.6f
        state.lastSeenAtMs = observation.observedAtMs
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
        return (base + ageSeconds * 1.1f + state.uncertaintyMeters + observation.uncertaintyMeters)
            .coerceAtMost(4f)
    }

    private fun expire(nowMs: Long) {
        states.entries.removeIf { nowMs - it.value.lastSeenAtMs > TRACK_TIMEOUT_MS }
    }

    private fun toPublicTrack(state: State, nowMs: Long): SpatialTrack {
        val ageMs = (nowMs - state.lastSeenAtMs).coerceAtLeast(0L)
        val confidenceDecay = (1f - (ageMs.toFloat() / TRACK_TIMEOUT_MS) * 0.55f).coerceIn(0.35f, 1f)
        return SpatialTrack(
            key = "$sourceId:${state.id}",
            id = state.id,
            sourceId = sourceId,
            label = state.label,
            confidence = state.confidence * confidenceDecay,
            position = predictedPosition(state, nowMs),
            velocity = state.velocity.copyOf(),
            uncertaintyMeters = state.uncertaintyMeters + (ageMs / 1000f) * 0.12f,
            // The published position is predicted to nowMs. lastSeen remains an internal expiry gate.
            observedAtMs = nowMs
        )
    }

    companion object {
        private const val POSITION_ALPHA = 0.68f
        private const val VELOCITY_BETA = 0.24f
        private const val BASE_ASSOCIATION_METERS = 0.8f
        private const val BIRD_ASSOCIATION_METERS = 0.45f
        private const val MAX_SPEED_METERS_PER_SECOND = 18f
        private const val MAX_PREDICTION_SECONDS = 1.5f
        private const val TRACK_TIMEOUT_MS = 2_200L
    }
}
