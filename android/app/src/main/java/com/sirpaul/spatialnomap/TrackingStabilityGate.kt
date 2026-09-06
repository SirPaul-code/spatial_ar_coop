package com.sirpaul.spatialnomap

/**
 * Converts noisy frame-by-frame ARCore tracking state into a stable UI state.
 * Geometry still receives the raw tracking state every frame; this gate is only
 * for user-facing readiness so a one-frame PAUSED/TRACKING wobble cannot make
 * the HUD flash between AR ACQUIRING and AR READY.
 */
class TrackingStabilityGate(
    private val acquireMs: Long = 300L,
    private val lossMs: Long = 1000L,
) {
    private var stableTracking = false
    private var rawTracking: Boolean? = null
    private var rawSinceMs = 0L

    /** Returns a new stable state when one is reached, otherwise null. */
    fun update(isTracking: Boolean, nowMs: Long): Boolean? {
        if (rawTracking != isTracking) {
            rawTracking = isTracking
            rawSinceMs = nowMs
        }

        if (stableTracking == isTracking) return null
        val requiredMs = if (isTracking) acquireMs else lossMs
        if (nowMs - rawSinceMs < requiredMs) return null

        stableTracking = isTracking
        return stableTracking
    }

    fun reset() {
        stableTracking = false
        rawTracking = null
        rawSinceMs = 0L
    }

    fun isStableTracking(): Boolean = stableTracking
}
