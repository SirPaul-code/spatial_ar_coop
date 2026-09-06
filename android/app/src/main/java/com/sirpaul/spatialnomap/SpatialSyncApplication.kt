package com.sirpaul.spatialnomap

import android.app.Application

class SpatialSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        sensorFusion = SpatialSensorFusion(this).also { it.start() }
    }

    companion object {
        @Volatile private var sensorFusion: SpatialSensorFusion? = null

        fun sensorSnapshot(): SensorSnapshot = sensorFusion?.snapshot() ?: SensorSnapshot()
        fun sensorSummary(): String = sensorFusion?.summary() ?: "sensors —"
    }
}
