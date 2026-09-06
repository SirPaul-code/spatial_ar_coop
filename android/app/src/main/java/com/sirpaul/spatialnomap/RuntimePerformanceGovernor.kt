package com.sirpaul.spatialnomap

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Protects ARCore VIO from app-side CPU/thermal starvation.
 *
 * getThermalHeadroom() must not be polled aggressively. We sample at a safe
 * cadence and translate the device-specific thermal signal into conservative
 * capture/matcher budgets. ARCore camera/VIO remains untouched; only our own
 * CPU-heavy grayscale/depth/feature-matching workload is reduced.
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
            Tier.FULL -> if (locked) CaptureBudget(2_000_000_000L, 896, tier)
            else CaptureBudget(500_000_000L, 960, tier)

            Tier.WARM -> if (locked) CaptureBudget(3_000_000_000L, 800, tier)
            else CaptureBudget(650_000_000L, 896, tier)

            Tier.HOT -> if (locked) CaptureBudget(4_500_000_000L, 704, tier)
            else CaptureBudget(900_000_000L, 768, tier)

            Tier.CRITICAL -> if (locked) CaptureBudget(7_000_000_000L, 576, tier)
            else CaptureBudget(1_350_000_000L, 640, tier)
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

            // Escalate immediately. Recover one tier at a time to avoid thermal
            // oscillation when the device hovers around a firmware threshold.
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
