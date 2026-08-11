package com.sirpaul.spatialarcoop.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class OffscreenDirection(val dx: Float, val dy: Float)
data class EdgePoint(val x: Float, val y: Float)

/** Pure camera-space math for edge indicators. Camera forward is OpenGL -Z, +X right, +Y up. */
object OffscreenIndicatorMath {
    fun direction(cameraPoint: FloatArray): OffscreenDirection {
        require(cameraPoint.size >= 3)
        val x = cameraPoint[0]
        val y = cameraPoint[1]
        val z = cameraPoint[2]
        val horizontalDistance = sqrt(x * x + z * z).coerceAtLeast(1e-4f)
        val bearing = atan2(x, -z)
        val elevation = atan2(y, horizontalDistance)

        var dx = (bearing / (PI.toFloat() / 2f)).coerceIn(-1f, 1f)
        var dy = (-elevation / (PI.toFloat() / 4f)).coerceIn(-1f, 1f)

        // Directly behind has no unique left/right solution. Pick the signed turn direction; for
        // exactly zero X use the right edge consistently instead of collapsing the vector to zero.
        if (z >= 0f && abs(dx) < 0.05f) dx = if (x < 0f) -1f else 1f
        if (abs(dx) < 0.02f && abs(dy) < 0.02f) dx = 1f

        val scale = max(abs(dx), abs(dy)).coerceAtLeast(1e-4f)
        return OffscreenDirection(dx / scale, dy / scale)
    }

    fun edgePoint(
        viewportWidth: Float,
        viewportHeight: Float,
        margin: Float,
        direction: OffscreenDirection
    ): EdgePoint {
        val centerX = viewportWidth * 0.5f
        val centerY = viewportHeight * 0.5f
        val halfWidth = (centerX - margin).coerceAtLeast(1f)
        val halfHeight = (centerY - margin).coerceAtLeast(1f)
        val dx = direction.dx
        val dy = direction.dy
        val scale = minOf(
            if (abs(dx) > 1e-5f) halfWidth / abs(dx) else Float.POSITIVE_INFINITY,
            if (abs(dy) > 1e-5f) halfHeight / abs(dy) else Float.POSITIVE_INFINITY
        )
        return EdgePoint(
            x = (centerX + dx * scale).coerceIn(margin, viewportWidth - margin),
            y = (centerY + dy * scale).coerceIn(margin, viewportHeight - margin)
        )
    }
}
