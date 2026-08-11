package com.sirpaul.spatialarcoop.ar

import java.util.concurrent.atomic.AtomicReference

enum class ArSessionState {
    NEW,
    STARTING,
    RUNNING,
    PAUSING,
    PAUSED,
    FAILED,
    CLOSING,
    CLOSED
}

/**
 * Cross-thread lifecycle gate shared by the Activity/UI thread and the GL renderer.
 * ARCore Session.update() is legal only while [canRender] is true.
 *
 * A FAILED session never starts again through the normal Activity onResume path. Recovery from
 * FAILED is intentionally explicit via [beginRetryStart] after the old native Session has fully
 * closed. This prevents permission/lifecycle callbacks from creating rapid overlapping Sessions.
 */
class ArSessionStateMachine(initial: ArSessionState = ArSessionState.NEW) {
    private val state = AtomicReference(initial)

    fun current(): ArSessionState = state.get()
    fun canRender(): Boolean = state.get() == ArSessionState.RUNNING

    /** Normal lifecycle start/resume. FAILED requires [beginRetryStart]. */
    fun beginStart(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.STARTING || current == ArSessionState.RUNNING ||
                current == ArSessionState.FAILED || current == ArSessionState.CLOSING ||
                current == ArSessionState.CLOSED
            ) return false
            if (state.compareAndSet(current, ArSessionState.STARTING)) return true
        }
    }

    /** Explicit recovery path used only after a failed Session has been fully closed. */
    fun beginRetryStart(): Boolean = state.compareAndSet(ArSessionState.FAILED, ArSessionState.STARTING)

    fun markRunning(): Boolean = state.compareAndSet(ArSessionState.STARTING, ArSessionState.RUNNING)

    fun beginPause(): Boolean = state.compareAndSet(ArSessionState.RUNNING, ArSessionState.PAUSING)

    fun markPaused() {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.FAILED || current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return
            if (current == ArSessionState.PAUSED) return
            if (state.compareAndSet(current, ArSessionState.PAUSED)) return
        }
    }

    /** Returns true only for the first transition into FAILED, preventing log/UI spam. */
    fun fail(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.FAILED || current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return false
            if (state.compareAndSet(current, ArSessionState.FAILED)) return true
        }
    }

    /** Returns true only to the caller that owns teardown. */
    fun beginClosing(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return false
            if (state.compareAndSet(current, ArSessionState.CLOSING)) return true
        }
    }

    fun markClosed() {
        state.set(ArSessionState.CLOSED)
    }
}
