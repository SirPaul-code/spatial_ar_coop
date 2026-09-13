package com.sirpaul.spatialnomap

import android.app.Application
import android.content.Context

class SpatialSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        sensorFusion = SpatialSensorFusion(this).also { it.start() }
        performanceGovernor = RuntimePerformanceGovernor(this)
        AlignmentSessionRecorder.init(this)
        SharedLandmarkCache.init(this)
        AlignmentQualityPoller.register(this)
        BirdEyeWorldController.register(this)
    }

    companion object {
        @Volatile private var appContext: Context? = null
        @Volatile private var sensorFusion: SpatialSensorFusion? = null
        @Volatile private var performanceGovernor: RuntimePerformanceGovernor? = null

        fun applicationContext(): Context? = appContext
        fun sensorSnapshot(): SensorSnapshot = sensorFusion?.snapshot() ?: SensorSnapshot()
        fun sensorSummary(): String = sensorFusion?.summary() ?: "sensors —"

        fun captureBudget(locked: Boolean): RuntimePerformanceGovernor.CaptureBudget =
            performanceGovernor?.captureBudget(locked)
                ?: RuntimePerformanceGovernor.CaptureBudget(
                    intervalNs = 250_000_000L,
                    maxWidth = if (locked) 1152 else 1280,
                    tier = RuntimePerformanceGovernor.Tier.FULL,
                )

        fun performanceSummary(): String = performanceGovernor?.summary() ?: "compute —"
    }
}
