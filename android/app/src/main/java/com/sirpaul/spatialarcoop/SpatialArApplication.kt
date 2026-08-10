package com.sirpaul.spatialarcoop

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
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
