package com.sirpaul.spatialnomap

import android.app.Application

class SpatialSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        sensorFusion = SpatialSensorFusion(this).also { it.start() }
        performanceGovernor = RuntimePerformanceGovernor(this)
    }

    companion object {
        @Volatile private var sensorFusion: SpatialSensorFusion? = null
        @Volatile private var performanceGovernor: RuntimePerformanceGovernor? = null

        fun sensorSnapshot(): SensorSnapshot = sensorFusion?.snapshot() ?: SensorSnapshot()
        fun sensorSummary(): String = sensorFusion?.summary() ?: "sensors —"

        fun captureBudget(locked: Boolean): RuntimePerformanceGovernor.CaptureBudget =
            performanceGovernor?.captureBudget(locked)
                ?: RuntimePerformanceGovernor.CaptureBudget(
                    intervalNs = if (locked) 2_000_000_000L else 520_000_000L,
                    maxWidth = if (locked) 896 else 960,
                    tier = RuntimePerformanceGovernor.Tier.FULL,
                )

        fun performanceSummary(): String = performanceGovernor?.summary() ?: "compute —"
    }
}
