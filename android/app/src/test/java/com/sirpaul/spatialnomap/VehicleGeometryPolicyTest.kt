package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleGeometryPolicyTest {
    @Test fun classPriorsStayPhysical() {
        val car = VehicleGeometryPolicy.halfExtents("CAR")
        assertEquals(2.15f, car.length, 0.001f)
        assertEquals(0.90f, car.width, 0.001f)
        assertEquals(0.78f, car.height, 0.001f)
    }

    @Test fun rearViewUsesBearingButWideSideViewRotatesNinetyDegrees() {
        val camera = floatArrayOf(0f, 1f, 0f)
        val target = floatArrayOf(0f, 1f, -10f)
        val rear = VehicleGeometryPolicy.observedAxis(camera, target, 1.3f)
        val side = VehicleGeometryPolicy.observedAxis(camera, target, 2.6f)
        val dot = rear[0] * side[0] + rear[2] * side[2]
        assertTrue(kotlin.math.abs(dot) < 0.05f)
    }

    @Test fun movingVelocityTakesYawAuthority() {
        val axis = VehicleGeometryPolicy.stabilizeAxis(
            previous = floatArrayOf(1f, 0f, 0f),
            observed = floatArrayOf(1f, 0f, 0f),
            velocity = floatArrayOf(0f, 0f, 12f),
            velocityHeadingMinMps = 1f,
        )
        assertTrue(kotlin.math.abs(axis[2]) > 0.65f)
    }
}
