package com.sirpaul.spatialarcoop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenIndicatorMathTest {
    @Test fun frontRightPointsRight() {
        val value = OffscreenIndicatorMath.direction(floatArrayOf(5f, 0f, -2f))
        assertTrue(value.dx > 0.9f)
        assertTrue(kotlin.math.abs(value.dy) < 0.1f)
    }

    @Test fun frontLeftPointsLeft() {
        val value = OffscreenIndicatorMath.direction(floatArrayOf(-5f, 0f, -2f))
        assertTrue(value.dx < -0.9f)
    }

    @Test fun directlyBehindStillProducesAHorizontalDirection() {
        val value = OffscreenIndicatorMath.direction(floatArrayOf(0f, 0f, 3f))
        assertTrue(kotlin.math.abs(value.dx) > 0.9f)
    }

    @Test fun objectAbovePointsTowardTopEdge() {
        val value = OffscreenIndicatorMath.direction(floatArrayOf(0.2f, 5f, -2f))
        assertTrue(value.dy < -0.9f)
    }

    @Test fun edgeIntersectionUsesViewportRectangleNotInscribedCircle() {
        val point = OffscreenIndicatorMath.edgePoint(1000f, 500f, 40f, OffscreenDirection(1f, 0.2f))
        assertEquals(960f, point.x, 0.01f)
        assertTrue(point.y in 40f..460f)
    }
}
