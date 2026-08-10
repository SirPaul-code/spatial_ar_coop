package com.sirpaul.spatialarcoop.ar

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.data.AppDatabase
import com.sirpaul.spatialarcoop.data.ChunkStatus
import com.sirpaul.spatialarcoop.data.ScanChunkCodec
import com.sirpaul.spatialarcoop.data.ScanChunkRecord
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.util.FileLogger
import java.io.File
import java.util.UUID
import kotlin.math.floor

class PointCloudRecorder(
    private val context: Context,
    private val mapId: String,
    private val database: AppDatabase,
    private val logger: FileLogger,
    private val onProgress: (chunkCount: Int, pointCount: Int) -> Unit
) {
    private data class VoxelKey(val x: Int, val y: Int, val z: Int)
    private data class PointSample(val x: Float, val y: Float, val z: Float, val confidence: Float)

    private val points = LinkedHashMap<VoxelKey, PointSample>()
    private var lastCaptureAtMs = 0L
    private var chunkStartedAtMs = 0L
    private var stopped = false

    @Synchronized
    fun capture(frame: Frame, siteFromWorld: FloatArray) {
        if (stopped || frame.camera.trackingState != TrackingState.TRACKING) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureAtMs < CAPTURE_INTERVAL_MS) return
        lastCaptureAtMs = now
        if (chunkStartedAtMs == 0L) chunkStartedAtMs = now

        runCatching {
            frame.acquirePointCloud().use { pointCloud ->
                val buffer = pointCloud.points
                buffer.rewind()
                while (buffer.remaining() >= 4 && points.size < MAX_POINTS_PER_CHUNK) {
                    val world = floatArrayOf(buffer.get(), buffer.get(), buffer.get())
                    val confidence = buffer.get()
                    if (confidence < MIN_CONFIDENCE) continue
                    val site = PoseMath.transformPoint(siteFromWorld, world)
                    val key = VoxelKey(
                        floor(site[0] / VOXEL_METERS).toInt(),
                        floor(site[1] / VOXEL_METERS).toInt(),
                        floor(site[2] / VOXEL_METERS).toInt()
                    )
                    val existing = points[key]
                    if (existing == null || confidence > existing.confidence) {
                        points[key] = PointSample(site[0], site[1], site[2], confidence)
                    }
                }
            }
        }.onFailure { logger.warn("Point cloud capture failed", mapOf("mapId" to mapId, "error" to it.message)) }

        if (points.size >= MAX_POINTS_PER_CHUNK || now - chunkStartedAtMs >= MAX_CHUNK_AGE_MS) {
            flush()
        }
    }

    @Synchronized
    fun flush() {
        if (points.isEmpty()) return
        val capturedAtMs = System.currentTimeMillis()
        val chunkId = "${capturedAtMs}-${UUID.randomUUID().toString().take(8)}"
        val mapDir = File(context.filesDir, "maps/$mapId/chunks").apply { mkdirs() }
        val finalFile = File(mapDir, "$chunkId.sac.gz")
        val temporary = File(mapDir, "$chunkId.tmp")
        val snapshot = points.values.toList()

        runCatching {
            val raw = ScanChunkCodec.encode(snapshot.map { floatArrayOf(it.x, it.y, it.z, it.confidence) }, capturedAtMs)
            ScanChunkCodec.writeGzipAtomically(raw, temporary, finalFile)
            database.enqueueChunk(
                ScanChunkRecord(
                    mapId = mapId,
                    id = chunkId,
                    filePath = finalFile.absolutePath,
                    pointCount = snapshot.size,
                    status = ChunkStatus.PENDING,
                    attempts = 0,
                    nextAttemptAtMs = 0,
                    createdAtMs = capturedAtMs
                )
            )
            points.clear()
            chunkStartedAtMs = 0L
            val (chunks, totalPoints) = database.chunkCounts(mapId)
            onProgress(chunks, totalPoints)
            UploadScheduler.enqueue(context)
            logger.info(
                "Scan chunk stored",
                mapOf("mapId" to mapId, "chunkId" to chunkId, "pointCount" to snapshot.size, "bytes" to finalFile.length())
            )
        }.onFailure { error ->
            // A valid final or temporary file is already durable. Drop the in-memory snapshot so
            // it is not duplicated under another chunk ID; ScanRecovery will register/promote it.
            val durableFile = listOf(finalFile, temporary).firstOrNull { candidate ->
                candidate.exists() && runCatching {
                    ScanChunkCodec.readMetadata(candidate).pointCount == snapshot.size
                }.getOrDefault(false)
            }
            if (durableFile != null) {
                points.clear()
                chunkStartedAtMs = 0L
                runCatching { UploadScheduler.enqueue(context) }
                    .onFailure { scheduleError ->
                        logger.warn(
                            "Could not schedule recovered scan upload",
                            mapOf("mapId" to mapId, "chunkId" to chunkId, "error" to scheduleError.message)
                        )
                    }
            }
            logger.error(
                "Scan chunk persistence failed",
                error,
                mapOf(
                    "mapId" to mapId,
                    "chunkId" to chunkId,
                    "recoverableFile" to durableFile?.absolutePath,
                    "temporaryPreserved" to temporary.exists(),
                    "finalExists" to finalFile.exists(),
                    "inMemorySnapshotRetained" to (durableFile == null)
                )
            )
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        flush()
    }

    companion object {
        private const val CAPTURE_INTERVAL_MS = 350L
        private const val MAX_CHUNK_AGE_MS = 3_500L
        private const val MAX_POINTS_PER_CHUNK = 1_500
        private const val MIN_CONFIDENCE = 0.25f
        private const val VOXEL_METERS = 0.08f

        internal fun encodeChunk(samples: List<FloatArray>, capturedAtMs: Long): ByteArray =
            ScanChunkCodec.encode(samples, capturedAtMs)
    }
}
