package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStabilityGateTest {
    @Test
    fun requiresStableTrackingBeforeReady() {
        val gate = TrackingStabilityGate(acquireMs = 300, lossMs = 1000)
        assertNull(gate.update(true, 0))
        assertNull(gate.update(true, 299))
        assertEquals(true, gate.update(true, 300))
        assertTrue(gate.isStableTracking())
    }

    @Test
    fun ignoresShortPausedFlicker() {
        val gate = TrackingStabilityGate(acquireMs = 300, lossMs = 1000)
        gate.update(true, 0)
        gate.update(true, 300)
        assertTrue(gate.isStableTracking())

        assertNull(gate.update(false, 350))
        assertNull(gate.update(false, 900))
        assertNull(gate.update(true, 950))
        assertTrue(gate.isStableTracking())
    }

    @Test
    fun reportsSustainedTrackingLoss() {
        val gate = TrackingStabilityGate(acquireMs = 300, lossMs = 1000)
        gate.update(true, 0)
        gate.update(true, 300)
        assertNull(gate.update(false, 400))
        assertNull(gate.update(false, 1399))
        assertEquals(false, gate.update(false, 1400))
        assertFalse(gate.isStableTracking())
    }
}
