package com.sirpaul.spatialarcoop.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.spatialApp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext.spatialApp
        val preferences = app.preferences
        val database = app.database
        val logger = app.logger
        val clients = mutableMapOf<String, MapApiClient>()

        fun credentialFor(map: MapDefinition): String = map.accessKey.ifBlank {
            // Migration path for pre-v3 local databases owned by this device. A subsequent owner
            // refresh obtains and persists the per-map key; new/joined maps never use this fallback.
            preferences.apiToken
        }

        fun api(map: MapDefinition): MapApiClient {
            val credential = credentialFor(map)
            check(credential.isNotBlank()) { "Map ${map.id} has no access credential; refresh it as owner or re-join its invite" }
            val key = "${map.serverUrl.trimEnd('/')}|${credential.hashCode()}"
            return clients.getOrPut(key) { MapApiClient(map.serverUrl, credential, logger) }
        }

        var hadFailure = false

        database.pendingMaps().forEach { map ->
            runCatching {
                val remote = api(map).patchMap(map)
                database.mergeServerMap(remote.copy(accessKey = map.accessKey.ifBlank { credentialFor(map) }))
                database.markMapSynced(map.id)
            }.onFailure {
                hadFailure = true
                logger.warn("Map sync failed", mapOf("mapId" to map.id, "serverUrl" to map.serverUrl, "error" to it.message))
            }
        }

        database.pendingAnchors().forEach { anchor ->
            runCatching {
                val map = database.getMap(anchor.mapId) ?: error("Map ${anchor.mapId} is missing")
                api(map).upsertAnchor(anchor)
                database.markAnchorSynced(anchor.mapId, anchor.id)
            }.onFailure {
                hadFailure = true
                logger.warn("Anchor sync failed", mapOf("mapId" to anchor.mapId, "anchorId" to anchor.id, "error" to it.message))
            }
        }

        var processedChunks = 0
        while (processedChunks < MAX_CHUNKS_PER_RUN) {
            val due = database.dueChunks(limit = CHUNK_BATCH_SIZE)
            if (due.isEmpty()) break
            due.forEach { chunk ->
                processedChunks += 1
                val file = File(chunk.filePath)
                if (!file.exists()) {
                    hadFailure = true
                    logger.error(
                        "Pending scan chunk is missing",
                        fields = mapOf("mapId" to chunk.mapId, "chunkId" to chunk.id, "path" to chunk.filePath)
                    )
                    database.blockMissingChunk(chunk)
                    return@forEach
                }
                runCatching {
                    val map = database.getMap(chunk.mapId) ?: error("Map ${chunk.mapId} is missing")
                    api(map).uploadScanChunk(chunk.mapId, chunk.id, preferences.deviceId, file)
                    database.markChunkUploaded(chunk.mapId, chunk.id)
                }.onFailure {
                    hadFailure = true
                    val delay = min(15 * 60_000L, 1_000L shl min(chunk.attempts, 9))
                    database.markChunkFailed(chunk, delay)
                    logger.warn(
                        "Scan chunk upload failed",
                        mapOf(
                            "mapId" to chunk.mapId,
                            "chunkId" to chunk.id,
                            "attempt" to chunk.attempts + 1,
                            "error" to it.message
                        )
                    )
                }
            }
            if (due.size < CHUNK_BATCH_SIZE) break
        }

        logger.debug(
            "Upload worker complete",
            mapOf("processedChunks" to processedChunks, "hadFailure" to hadFailure)
        )
        return if (hadFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val CHUNK_BATCH_SIZE = 20
        private const val MAX_CHUNKS_PER_RUN = 200
    }
}

object UploadScheduler {
    private const val UNIQUE_WORK = "spatial-ar-coop-upload"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
