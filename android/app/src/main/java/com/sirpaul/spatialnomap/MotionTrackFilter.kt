package com.sirpaul.spatialnomap

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small constant-velocity alpha-beta filter for moving shared targets.
 *
 * Dynamic objects must not be ARCore anchors. This filter keeps their shared-world
 * position smooth without introducing a heavy dependency and also estimates
 * velocity so the renderer/room fan-out layer can coast briefly across detector
 * misses instead of making a vehicle marker visibly jump or disappear instantly.
 */
class MotionTrackFilter(
    private val alpha: Float = 0.55f,
    private val beta: Float = 0.18f,
    private val maxSpeedMps: Float = 80f,
) {
    data class State(
        val position: FloatArray,
        val velocity: FloatArray,
        val timestampMs: Long,
        val confidence: Float,
    )

    private var state: State? = null

    @Synchronized fun update(measurement: FloatArray, timestampMs: Long, confidence: Float): State {
        require(measurement.size >= 3)
        val previous = state
        if (previous == null || timestampMs <= previous.timestampMs) {
            return State(measurement.copyOf(3), FloatArray(3), timestampMs, confidence).also { state = it }
        }

        val dt = ((timestampMs - previous.timestampMs) / 1000f).coerceIn(0.02f, 2f)
        val predicted = FloatArray(3) { i -> previous.position[i] + previous.velocity[i] * dt }
        val residual = FloatArray(3) { i -> measurement[i] - predicted[i] }
        val nextPosition = FloatArray(3) { i -> predicted[i] + alpha * residual[i] }
        val nextVelocity = FloatArray(3) { i -> previous.velocity[i] + (beta / dt) * residual[i] }
        limitVelocity(nextVelocity)

        return State(
            position = nextPosition,
            velocity = nextVelocity,
            timestampMs = timestampMs,
            confidence = (previous.confidence * 0.35f + confidence * 0.65f).coerceIn(0f, 1f),
        ).also { state = it }
    }

    @Synchronized fun predict(timestampMs: Long): State? {
        val current = state ?: return null
        if (timestampMs <= current.timestampMs) return current.copy(
            position = current.position.copyOf(),
            velocity = current.velocity.copyOf(),
        )
        val dt = ((timestampMs - current.timestampMs) / 1000f).coerceIn(0f, 2f)
        return current.copy(
            position = FloatArray(3) { i -> current.position[i] + current.velocity[i] * dt },
            velocity = current.velocity.copyOf(),
            confidence = (current.confidence * (1f - minOf(0.55f, dt * 0.22f))).coerceAtLeast(0f),
            timestampMs = timestampMs,
        )
    }

    @Synchronized fun reset() {
        state = null
    }

    private fun limitVelocity(v: FloatArray) {
        val speed = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (!speed.isFinite() || speed <= maxSpeedMps || speed < 1e-5f) return
        val scale = max(0f, maxSpeedMps / speed)
        repeat(3) { v[it] *= scale }
    }
}
