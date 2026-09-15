package com.sirpaul.spatialnomap

import kotlin.math.abs
import kotlin.math.sqrt

object VehicleGeometryPolicy {
    data class HalfExtents(val length: Float, val width: Float, val height: Float)

    fun halfExtents(label: String): HalfExtents = when (label.uppercase()) {
        "BUS" -> HalfExtents(4.8f, 1.15f, 1.45f)
        "TRUCK" -> HalfExtents(3.2f, 1.05f, 1.20f)
        else -> HalfExtents(2.15f, 0.90f, 0.78f)
    }

    /**
     * A sparse depth cloud cannot tell a vehicle's longitudinal axis reliably: a rear
     * bumper makes PCA point sideways. Use the camera-to-target bearing instead. A
     * strongly wide silhouette is treated as a side view and rotates the axis 90°.
     * The axis is a line, so forward/backward (180°) are intentionally equivalent.
     */
    fun observedAxis(cameraWorld: FloatArray, centerWorld: FloatArray, imageAspect: Float): FloatArray {
        val dx = centerWorld.getOrElse(0) { 0f } - cameraWorld.getOrElse(0) { 0f }
        val dz = centerWorld.getOrElse(2) { 0f } - cameraWorld.getOrElse(2) { 0f }
        val n = sqrt(dx * dx + dz * dz)
        val toward = if (n.isFinite() && n > 1e-4f) floatArrayOf(dx / n, 0f, dz / n)
        else floatArrayOf(1f, 0f, 0f)
        return if (imageAspect >= SIDE_VIEW_ASPECT) {
            floatArrayOf(-toward[2], 0f, toward[0])
        } else toward
    }

    fun stabilizeAxis(
        previous: FloatArray?,
        observed: FloatArray?,
        velocity: FloatArray,
        velocityHeadingMinMps: Float = 1.0f,
    ): FloatArray {
        val speed = horizontalSpeed(velocity)
        val velocityAxis = horizontalUnit(velocity)
        var candidate = if (velocityAxis != null && speed >= velocityHeadingMinMps) {
            velocityAxis
        } else {
            horizontalUnit(observed ?: FloatArray(0))
                ?: horizontalUnit(previous ?: FloatArray(0))
                ?: floatArrayOf(1f, 0f, 0f)
        }
        val prior = horizontalUnit(previous ?: FloatArray(0))
        if (prior != null) {
            if (dot(candidate, prior) < 0f) candidate = floatArrayOf(-candidate[0], 0f, -candidate[2])
            // When nearly stationary, strongly prefer temporal yaw continuity.
            val freshWeight = if (speed >= velocityHeadingMinMps) 0.72f else 0.18f
            candidate = horizontalUnit(floatArrayOf(
                prior[0] * (1f - freshWeight) + candidate[0] * freshWeight,
                0f,
                prior[2] * (1f - freshWeight) + candidate[2] * freshWeight,
            )) ?: candidate
        }
        return candidate
    }

    fun horizontalSpeed(v: FloatArray): Float {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val s = sqrt(x * x + z * z)
        return if (s.isFinite()) s else 0f
    }

    fun horizontalUnit(v: FloatArray): FloatArray? {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val n = sqrt(x * x + z * z)
        if (!n.isFinite() || n < 1e-4f) return null
        return floatArrayOf(x / n, 0f, z / n)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } + a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    private const val SIDE_VIEW_ASPECT = 1.85f
}
