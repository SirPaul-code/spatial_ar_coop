package com.sirpaul.spatialarcoop.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArSessionStateMachineTest {
    @Test fun startAndRunEnablesRendering() {
        val machine = ArSessionStateMachine()
        assertTrue(machine.beginStart())
        assertEquals(ArSessionState.STARTING, machine.current())
        assertFalse(machine.canRender())
        assertTrue(machine.markRunning())
        assertTrue(machine.canRender())
    }

    @Test fun failureHardGatesRendererAndNeedsExplicitRetry() {
        val machine = ArSessionStateMachine()
        machine.beginStart()
        assertTrue(machine.fail())
        assertEquals(ArSessionState.FAILED, machine.current())
        assertFalse(machine.canRender())
        assertFalse(machine.fail())
        assertFalse(machine.beginStart())
        assertTrue(machine.beginRetryStart())
        assertTrue(machine.markRunning())
        assertTrue(machine.canRender())
    }

    @Test fun explicitRetryOnlyWorksFromFailed() {
        val machine = ArSessionStateMachine()
        assertFalse(machine.beginRetryStart())
        assertTrue(machine.beginStart())
        assertFalse(machine.beginRetryStart())
        assertTrue(machine.fail())
        assertTrue(machine.beginRetryStart())
        assertFalse(machine.beginRetryStart())
    }

    @Test fun pauseIsIdempotentAndStopsRendering() {
        val machine = ArSessionStateMachine()
        machine.beginStart(); machine.markRunning()
        assertTrue(machine.beginPause())
        assertFalse(machine.canRender())
        machine.markPaused()
        machine.markPaused()
        assertEquals(ArSessionState.PAUSED, machine.current())
    }

    @Test fun closingWinsAndCannotRestart() {
        val machine = ArSessionStateMachine()
        machine.beginStart(); machine.markRunning()
        assertTrue(machine.beginClosing())
        assertFalse(machine.beginClosing())
        assertFalse(machine.canRender())
        assertFalse(machine.beginStart())
        assertFalse(machine.beginRetryStart())
        machine.markClosed()
        assertEquals(ArSessionState.CLOSED, machine.current())
        assertFalse(machine.beginClosing())
    }
}
