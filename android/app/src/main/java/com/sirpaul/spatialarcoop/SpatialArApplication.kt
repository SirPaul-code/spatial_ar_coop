package com.sirpaul.spatialarcoop

import android.app.Application
import com.sirpaul.spatialarcoop.ar.SamsungArCoreSensorKeepalive
import com.sirpaul.spatialarcoop.data.AppDatabase
import com.sirpaul.spatialarcoop.data.AppPreferences
import com.sirpaul.spatialarcoop.data.ScanRecovery
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.util.FileLogger
import java.util.concurrent.Executors

class SpatialArApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase(this) }
    val preferences: AppPreferences by lazy { AppPreferences(this) }
    val logger: FileLogger by lazy { FileLogger(this) }
    private val recoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spatial-recovery").apply { isDaemon = true }
    }
    private var samsungArCoreSensorKeepalive: SamsungArCoreSensorKeepalive? = null

    override fun onCreate() {
        super.onCreate()
        // ARCore 1.54 has an upstream Samsung/Android 16 Session.resume() regression where native
        // uncalibrated IMU registration can fail. Install the narrowly-scoped lifecycle workaround
        // before any AR Activity can create or resume an ARCore Session.
        samsungArCoreSensorKeepalive = SamsungArCoreSensorKeepalive.installIfNeeded(this, logger)
        recoveryExecutor.execute {
            runCatching { ScanRecovery.reconcile(this, database, logger) }
                .onSuccess { summary ->
                    logger.info(
                        "Local recovery pass complete",
                        mapOf(
                            "recoveredTemporaryFiles" to summary.recoveredTemporaryFiles,
                            "requeuedChunks" to summary.requeuedChunks,
                            "invalidFiles" to summary.invalidFiles,
                            "missingPendingFiles" to summary.missingPendingFiles
                        )
                    )
                    UploadScheduler.enqueue(this)
                }
                .onFailure { logger.error("Local recovery pass failed", it) }
        }
    }
}

val android.content.Context.spatialApp: SpatialArApplication
    get() = applicationContext as SpatialArApplication
