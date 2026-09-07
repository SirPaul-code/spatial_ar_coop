package com.sirpaul.spatialnomap

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Protects ARCore VIO from thermal starvation while keeping registration quality
 * as the primary objective. Spatial Sync is deliberately bandwidth-heavy: on a
 * cool device we capture high-resolution registration frames at several Hz and
 * only back off when Android reports real thermal pressure.
 *
 * Locked mode deliberately remains high-rate too. Besides making background
 * relocalisation more resilient, these frames drive the live shared-world actor
 * visualization and preserve enough temporal density for motion trails/replay.
 */
class RuntimePerformanceGovernor(context: Context) {
    data class CaptureBudget(
        val intervalNs: Long,
        val maxWidth: Int,
        val tier: Tier,
    )

    enum class Tier { FULL, WARM, HOT, CRITICAL }

    private val power = context.getSystemService(PowerManager::class.java)

    @Volatile private var tier = Tier.FULL
    @Volatile private var lastHeadroom = Float.NaN
    @Volatile private var lastStatus = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var nextSampleAtMs = 0L

    fun captureBudget(locked: Boolean): CaptureBudget {
        sampleIfDue()
        return when (tier) {
            Tier.FULL -> CaptureBudget(250_000_000L, if (locked) 1152 else 1280, tier)
            Tier.WARM -> CaptureBudget(350_000_000L, if (locked) 960 else 1152, tier)
            Tier.HOT -> CaptureBudget(500_000_000L, if (locked) 896 else 960, tier)
            Tier.CRITICAL -> CaptureBudget(800_000_000L, if (locked) 704 else 800, tier)
        }
    }

    fun summary(): String {
        sampleIfDue()
        val headroom = if (lastHeadroom.isFinite()) "%.2f".format(lastHeadroom) else "—"
        return "compute ${tier.name.lowercase()} • thermal $headroom"
    }

    private fun sampleIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextSampleAtMs) return
        synchronized(this) {
            if (now < nextSampleAtMs) return
            nextSampleAtMs = now + THERMAL_SAMPLE_MS

            val status = if (Build.VERSION.SDK_INT >= 29) {
                runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                    .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
            } else {
                PowerManager.THERMAL_STATUS_NONE
            }
            val headroom = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { power?.getThermalHeadroom(0) ?: Float.NaN }.getOrDefault(Float.NaN)
            } else {
                Float.NaN
            }
            lastStatus = status
            lastHeadroom = headroom

            val requested = when {
                status >= PowerManager.THERMAL_STATUS_CRITICAL -> Tier.CRITICAL
                status >= PowerManager.THERMAL_STATUS_SEVERE -> Tier.HOT
                status >= PowerManager.THERMAL_STATUS_MODERATE -> Tier.WARM
                headroom.isFinite() && headroom >= 1.0f -> Tier.HOT
                headroom.isFinite() && headroom >= 0.82f -> Tier.WARM
                else -> Tier.FULL
            }

            tier = when {
                requested.ordinal > tier.ordinal -> requested
                requested.ordinal < tier.ordinal -> Tier.entries[tier.ordinal - 1]
                else -> tier
            }
        }
    }

    companion object {
        private const val THERMAL_SAMPLE_MS = 12_000L
    }
}
