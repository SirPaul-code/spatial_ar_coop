package com.sirpaul.spatialnomap

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure policy helpers for room-level dynamic vehicle tracks.
 *
 * Two phones can detect the same physical vehicle at nearly the same time. The
 * detector-local track id is not a room id, so the renderer uses a stable shared id
 * and resolves duplicate ownership deterministically. The unsigned-lowest id wins;
 * therefore both peers reach the same answer without another protocol round-trip.
 */
object VehicleTrackPolicy {
    fun winnerId(a: Long, b: Long): Long =
        if (java.lang.Long.compareUnsigned(a, b) <= 0) a else b

    fun predict(
        position: FloatArray,
        velocity: FloatArray,
        lastSeenMs: Long,
        nowMs: Long,
        maxCoastMs: Long,
    ): FloatArray {
        val ageMs = (nowMs - lastSeenMs).coerceAtLeast(0L).coerceAtMost(maxCoastMs)
        val dt = ageMs / 1000f
        return FloatArray(3) { i ->
            position.getOrElse(i) { 0f } + velocity.getOrElse(i) { 0f } * dt
        }
    }

    fun associationGateM(
        velocity: FloatArray,
        lastSeenMs: Long,
        nowMs: Long,
        baseM: Float = 1.8f,
        maxM: Float = 4.5f,
    ): Float {
        val ageSeconds = ((nowMs - lastSeenMs).coerceAtLeast(0L) / 1000f).coerceAtMost(3f)
        val speed = speedMps(velocity)
        return min(maxM, baseM + speed * ageSeconds + ageSeconds * 0.35f)
    }

    fun samePhysicalVehicle(
        existingPosition: FloatArray,
        existingVelocity: FloatArray,
        existingLastSeenMs: Long,
        observation: FloatArray,
        nowMs: Long,
        maxCoastMs: Long,
        baseGateM: Float = 1.8f,
        maxGateM: Float = 4.5f,
    ): Boolean {
        val predicted = predict(existingPosition, existingVelocity, existingLastSeenMs, nowMs, maxCoastMs)
        val gate = associationGateM(existingVelocity, existingLastSeenMs, nowMs, baseGateM, maxGateM)
        return distance(predicted, observation) <= gate
    }

    fun speedMps(velocity: FloatArray): Float {
        val x = velocity.getOrElse(0) { 0f }
        val y = velocity.getOrElse(1) { 0f }
        val z = velocity.getOrElse(2) { 0f }
        val speed = sqrt(x * x + y * y + z * z)
        return if (speed.isFinite()) speed else 0f
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
