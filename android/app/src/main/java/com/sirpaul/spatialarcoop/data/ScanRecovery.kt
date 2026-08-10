package com.sirpaul.spatialarcoop.data

import android.content.Context
import com.sirpaul.spatialarcoop.util.FileLogger
import java.io.File

data class ScanRecoverySummary(
    val recoveredTemporaryFiles: Int,
    val requeuedChunks: Int,
    val invalidFiles: Int,
    val missingPendingFiles: Int
)

object ScanRecovery {
    fun reconcile(context: Context, database: AppDatabase, logger: FileLogger): ScanRecoverySummary {
        var recoveredTemporaryFiles = 0
        var requeuedChunks = 0
        var invalidFiles = 0
        var missingPendingFiles = 0

        database.listMaps().forEach maps@ { map ->
            val chunkDirectory = File(context.filesDir, "maps/${map.id}/chunks")
            if (!chunkDirectory.exists()) return@maps

            chunkDirectory.listFiles { file -> file.isFile && file.extension == "tmp" }
                ?.forEach temporaryFiles@ { temporary ->
                    val chunkId = temporary.name.removeSuffix(".tmp")
                    val destination = File(chunkDirectory, "$chunkId.sac.gz")
                    runCatching {
                        val temporaryMetadata = ScanChunkCodec.readMetadata(temporary)
                        if (destination.exists()) {
                            val destinationMetadata = ScanChunkCodec.readMetadata(destination)
                            require(destinationMetadata == temporaryMetadata) {
                                "Temporary and final chunks with ID $chunkId have different metadata"
                            }
                            check(temporary.delete()) { "Could not remove duplicate temporary scan chunk" }
                        } else {
                            check(temporary.renameTo(destination)) {
                                "Could not promote recovered temporary scan chunk"
                            }
                        }
                        database.recoverChunk(
                            ScanChunkRecord(
                                mapId = map.id,
                                id = chunkId,
                                filePath = destination.absolutePath,
                                pointCount = temporaryMetadata.pointCount,
                                status = ChunkStatus.PENDING,
                                attempts = 0,
                                nextAttemptAtMs = 0L,
                                createdAtMs = temporaryMetadata.capturedAtMs
                            )
                        )
                        recoveredTemporaryFiles += 1
                        logger.info(
                            "Recovered interrupted scan chunk",
                            mapOf("mapId" to map.id, "chunkId" to chunkId)
                        )
                    }.onFailure { error ->
                        invalidFiles += 1
                        val quarantine = File(
                            chunkDirectory,
                            "${temporary.name}.invalid-${System.currentTimeMillis()}"
                        )
                        temporary.renameTo(quarantine)
                        logger.warn(
                            "Quarantined invalid temporary scan chunk",
                            mapOf("mapId" to map.id, "file" to temporary.name, "error" to error.message)
                        )
                    }
                }

            val records = database.listChunks(map.id).associateBy { it.id }
            chunkDirectory.listFiles { file -> file.isFile && file.name.endsWith(".sac.gz") }
                ?.forEach finalFiles@ { file ->
                    val chunkId = file.name.removeSuffix(".sac.gz")
                    val existing = records[chunkId]
                    runCatching {
                        val metadata = ScanChunkCodec.readMetadata(file)
                        val recovered = ScanChunkRecord(
                            mapId = map.id,
                            id = chunkId,
                            filePath = file.absolutePath,
                            pointCount = metadata.pointCount,
                            status = existing?.status ?: ChunkStatus.PENDING,
                            attempts = existing?.attempts ?: 0,
                            nextAttemptAtMs = if (existing?.status == ChunkStatus.UPLOADED) {
                                existing.nextAttemptAtMs
                            } else {
                                0L
                            },
                            createdAtMs = metadata.capturedAtMs
                        )
                        database.recoverChunk(recovered)
                        if (existing == null || existing.nextAttemptAtMs == Long.MAX_VALUE) {
                            requeuedChunks += 1
                            logger.info(
                                "Requeued durable scan chunk",
                                mapOf("mapId" to map.id, "chunkId" to chunkId)
                            )
                        }
                    }.onFailure { error ->
                        invalidFiles += 1
                        existing?.takeIf { it.status != ChunkStatus.UPLOADED }?.let(database::blockMissingChunk)
                        val quarantine = File(
                            chunkDirectory,
                            "${file.name}.invalid-${System.currentTimeMillis()}"
                        )
                        file.renameTo(quarantine)
                        logger.warn(
                            "Quarantined invalid stored scan chunk",
                            mapOf("mapId" to map.id, "file" to file.name, "error" to error.message)
                        )
                    }
                }

            database.listChunks(map.id)
                .filter {
                    it.status != ChunkStatus.UPLOADED &&
                        it.nextAttemptAtMs != Long.MAX_VALUE &&
                        !File(it.filePath).exists()
                }
                .forEach { chunk ->
                    database.blockMissingChunk(chunk)
                    missingPendingFiles += 1
                    logger.error(
                        "Pending scan chunk file is missing",
                        fields = mapOf("mapId" to map.id, "chunkId" to chunk.id, "path" to chunk.filePath)
                    )
                }
        }

        return ScanRecoverySummary(recoveredTemporaryFiles, requeuedChunks, invalidFiles, missingPendingFiles)
    }
}
