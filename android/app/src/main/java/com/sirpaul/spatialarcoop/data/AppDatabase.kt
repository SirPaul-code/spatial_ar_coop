package com.sirpaul.spatialarcoop.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "spatial-ar-coop.db", null, SCHEMA_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE maps (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              server_url TEXT NOT NULL,
              server_id TEXT NOT NULL DEFAULT '',
              access_key TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL,
              root_anchor_id TEXT,
              ground_y REAL,
              anchor_ttl_days INTEGER NOT NULL,
              min_anchor_spacing REAL NOT NULL,
              auto_anchor INTEGER NOT NULL,
              server_chunk_count INTEGER NOT NULL DEFAULT -1,
              server_point_count INTEGER NOT NULL DEFAULT -1,
              server_scan_bytes INTEGER NOT NULL DEFAULT -1,
              updated_at INTEGER NOT NULL,
              sync_pending INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE anchors (
              map_id TEXT NOT NULL,
              id TEXT NOT NULL,
              cloud_id TEXT NOT NULL,
              matrix TEXT NOT NULL,
              status TEXT NOT NULL,
              feature_quality TEXT NOT NULL,
              last_error TEXT,
              updated_at INTEGER NOT NULL,
              sync_pending INTEGER NOT NULL,
              PRIMARY KEY (map_id, id),
              FOREIGN KEY (map_id) REFERENCES maps(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE chunks (
              map_id TEXT NOT NULL,
              id TEXT NOT NULL,
              file_path TEXT NOT NULL,
              point_count INTEGER NOT NULL,
              status TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              next_attempt_at INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              PRIMARY KEY (map_id, id),
              FOREIGN KEY (map_id) REFERENCES maps(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX chunks_due ON chunks(status, next_attempt_at)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    /**
     * Migrations are deliberately additive. Local maps/chunks are the recovery source of truth and
     * must never be reset merely because the presentation or authorization model gained metadata.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 2) {
            db.execSQL("ALTER TABLE maps ADD COLUMN server_chunk_count INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE maps ADD COLUMN server_point_count INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE maps ADD COLUMN server_scan_bytes INTEGER NOT NULL DEFAULT -1")
            version = 2
        }
        if (version < 3) {
            db.execSQL("ALTER TABLE maps ADD COLUMN server_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE maps ADD COLUMN access_key TEXT NOT NULL DEFAULT ''")
            version = 3
        }
        check(version == newVersion) {
            "Missing non-destructive database migration from schema $version to $newVersion"
        }
    }

    /**
     * Inserts or updates a local map without SQLite REPLACE semantics. REPLACE deletes the old row
     * first, which would cascade-delete every saved anchor and scan chunk.
     */
    @Synchronized
    fun upsertMap(map: MapDefinition) {
        writeMapRow(map)
        map.anchors.forEach(::upsertAnchor)
    }

    /** Merge a server snapshot while preserving newer unsynchronized local edits and credentials. */
    @Synchronized
    fun mergeServerMap(remote: MapDefinition) {
        val local = getMap(remote.id)
        val merged = when {
            local == null -> remote
            local.syncPending -> local.copy(
                serverUrl = remote.serverUrl,
                serverId = remote.serverId.ifBlank { local.serverId },
                accessKey = remote.accessKey.ifBlank { local.accessKey },
                serverChunkCount = remote.serverChunkCount,
                serverPointCount = remote.serverPointCount,
                serverScanBytes = remote.serverScanBytes,
                anchors = emptyList()
            )
            else -> remote.copy(
                serverId = remote.serverId.ifBlank { local.serverId },
                accessKey = remote.accessKey.ifBlank { local.accessKey },
                rootAnchorId = remote.rootAnchorId ?: local.rootAnchorId,
                groundY = remote.groundY ?: local.groundY,
                syncPending = local.syncPending,
                updatedAtMs = maxOf(remote.updatedAtMs, local.updatedAtMs),
                anchors = emptyList()
            )
        }
        writeMapRow(merged)
        remote.anchors.forEach(::mergeServerAnchor)
    }

    private fun writeMapRow(map: MapDefinition) {
        val values = ContentValues().apply {
            put("id", map.id)
            put("name", map.name)
            put("server_url", map.serverUrl.trimEnd('/'))
            put("server_id", map.serverId)
            put("access_key", map.accessKey)
            put("status", map.status.name)
            put("root_anchor_id", map.rootAnchorId)
            if (map.groundY == null) putNull("ground_y") else put("ground_y", map.groundY)
            put("anchor_ttl_days", map.anchorTtlDays)
            put("min_anchor_spacing", map.minAnchorSpacingMeters)
            put("auto_anchor", if (map.autoAnchor) 1 else 0)
            put("server_chunk_count", map.serverChunkCount ?: UNKNOWN_SERVER_COUNT)
            put("server_point_count", map.serverPointCount ?: UNKNOWN_SERVER_COUNT)
            put("server_scan_bytes", map.serverScanBytes ?: UNKNOWN_SERVER_COUNT.toLong())
            put("updated_at", map.updatedAtMs)
            put("sync_pending", if (map.syncPending) 1 else 0)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "maps", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) {
            val updateValues = ContentValues(values).apply { remove("id") }
            writableDatabase.update("maps", updateValues, "id=?", arrayOf(map.id))
        }
    }

    @Synchronized
    fun upsertAnchor(anchor: AnchorDefinition) {
        writeAnchorRow(anchor)
    }

    private fun mergeServerAnchor(remote: AnchorDefinition) {
        val local = getAnchor(remote.mapId, remote.id)
        if (local?.syncPending == true) return
        writeAnchorRow(remote)
    }

    private fun writeAnchorRow(anchor: AnchorDefinition) {
        val values = ContentValues().apply {
            put("map_id", anchor.mapId)
            put("id", anchor.id)
            put("cloud_id", anchor.cloudAnchorId)
            put("matrix", anchor.siteFromAnchor.joinToString(","))
            put("status", anchor.status.name)
            put("feature_quality", anchor.featureQuality.name)
            put("last_error", anchor.lastError)
            put("updated_at", anchor.updatedAtMs)
            put("sync_pending", if (anchor.syncPending) 1 else 0)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "anchors", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) {
            val updateValues = ContentValues(values).apply {
                remove("map_id")
                remove("id")
            }
            writableDatabase.update(
                "anchors", updateValues, "map_id=? AND id=?", arrayOf(anchor.mapId, anchor.id)
            )
        }
    }

    @Synchronized
    fun listMaps(): List<MapDefinition> = readableDatabase.query(
        "maps", null, null, null, null, null, "updated_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(mapFromCursor(cursor)) } }

    @Synchronized
    fun getMap(mapId: String): MapDefinition? = readableDatabase.query(
        "maps", null, "id=?", arrayOf(mapId), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) mapFromCursor(cursor) else null }

    private fun mapFromCursor(cursor: Cursor): MapDefinition {
        val mapId = cursor.string("id")
        return MapDefinition(
            id = mapId,
            name = cursor.string("name"),
            serverUrl = cursor.string("server_url"),
            serverId = cursor.string("server_id"),
            accessKey = cursor.string("access_key"),
            status = enumValueOr(cursor.string("status"), MapStatus.MAPPING),
            rootAnchorId = cursor.nullableString("root_anchor_id"),
            groundY = cursor.nullableFloat("ground_y"),
            anchorTtlDays = cursor.int("anchor_ttl_days"),
            minAnchorSpacingMeters = cursor.float("min_anchor_spacing"),
            autoAnchor = cursor.int("auto_anchor") != 0,
            anchors = getAnchors(mapId),
            serverChunkCount = cursor.nullableServerCount("server_chunk_count")?.toInt(),
            serverPointCount = cursor.nullableServerCount("server_point_count")?.toInt(),
            serverScanBytes = cursor.nullableServerCount("server_scan_bytes"),
            updatedAtMs = cursor.long("updated_at"),
            syncPending = cursor.int("sync_pending") != 0
        )
    }

    @Synchronized
    fun getAnchor(mapId: String, anchorId: String): AnchorDefinition? = readableDatabase.query(
        "anchors", null, "map_id=? AND id=?", arrayOf(mapId, anchorId), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) anchorFromCursor(cursor) else null }

    @Synchronized
    fun getAnchors(mapId: String): List<AnchorDefinition> = readableDatabase.query(
        "anchors", null, "map_id=?", arrayOf(mapId), null, null, "updated_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(anchorFromCursor(cursor)) } }

    private fun anchorFromCursor(cursor: Cursor): AnchorDefinition {
        val matrix = cursor.string("matrix").split(',').mapNotNull(String::toFloatOrNull).toFloatArray()
        return AnchorDefinition(
            mapId = cursor.string("map_id"),
            id = cursor.string("id"),
            cloudAnchorId = cursor.string("cloud_id"),
            siteFromAnchor = if (matrix.size == 16) matrix else identityMatrix(),
            status = enumValueOr(cursor.string("status"), AnchorStatus.PENDING),
            featureQuality = enumValueOr(cursor.string("feature_quality"), FeatureQuality.UNKNOWN),
            lastError = cursor.nullableString("last_error"),
            updatedAtMs = cursor.long("updated_at"),
            syncPending = cursor.int("sync_pending") != 0
        )
    }

    @Synchronized
    fun updateMapRuntime(mapId: String, rootAnchorId: String? = null, groundY: Float? = null, status: MapStatus? = null) {
        val values = ContentValues().apply {
            rootAnchorId?.let { put("root_anchor_id", it) }
            groundY?.let { put("ground_y", it) }
            status?.let { put("status", it.name) }
            put("updated_at", System.currentTimeMillis())
            put("sync_pending", 1)
        }
        writableDatabase.update("maps", values, "id=?", arrayOf(mapId))
    }

    @Synchronized
    fun markMapSynced(mapId: String) {
        writableDatabase.update("maps", ContentValues().apply { put("sync_pending", 0) }, "id=?", arrayOf(mapId))
    }

    @Synchronized
    fun pendingMaps(): List<MapDefinition> = readableDatabase.query(
        "maps", null, "sync_pending=1", null, null, null, "updated_at ASC", "20"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(mapFromCursor(cursor)) } }

    @Synchronized
    fun pendingAnchors(): List<AnchorDefinition> = readableDatabase.query(
        "anchors", null, "sync_pending=1", null, null, null, "updated_at ASC", "100"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(anchorFromCursor(cursor)) } }

    @Synchronized
    fun markAnchorSynced(mapId: String, anchorId: String) {
        writableDatabase.update(
            "anchors", ContentValues().apply { put("sync_pending", 0) },
            "map_id=? AND id=?", arrayOf(mapId, anchorId)
        )
    }

    @Synchronized
    fun enqueueChunk(record: ScanChunkRecord) {
        val values = ContentValues().apply {
            put("map_id", record.mapId)
            put("id", record.id)
            put("file_path", record.filePath)
            put("point_count", record.pointCount)
            put("status", record.status.name)
            put("attempts", record.attempts)
            put("next_attempt_at", record.nextAttemptAtMs)
            put("created_at", record.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("chunks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Restores a durable file into the upload queue without re-uploading already synced chunks. */
    @Synchronized
    fun recoverChunk(record: ScanChunkRecord) {
        if (!chunkExists(record.mapId, record.id)) {
            enqueueChunk(record)
            return
        }
        val values = ContentValues().apply {
            put("file_path", record.filePath)
            put("point_count", record.pointCount)
            put("created_at", record.createdAtMs)
        }
        val existing = listChunks(record.mapId).firstOrNull { it.id == record.id }
        if (existing?.status != ChunkStatus.UPLOADED) {
            values.put("status", ChunkStatus.PENDING.name)
            values.put("next_attempt_at", 0L)
        }
        writableDatabase.update(
            "chunks", values, "map_id=? AND id=?", arrayOf(record.mapId, record.id)
        )
    }

    @Synchronized
    fun chunkExists(mapId: String, chunkId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM chunks WHERE map_id=? AND id=? LIMIT 1", arrayOf(mapId, chunkId)
    ).use { it.moveToFirst() }

    @Synchronized
    fun listChunks(mapId: String): List<ScanChunkRecord> = readableDatabase.query(
        "chunks", null, "map_id=?", arrayOf(mapId), null, null, "created_at ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(chunkFromCursor(cursor)) } }

    @Synchronized
    fun dueChunks(nowMs: Long = System.currentTimeMillis(), limit: Int = 20): List<ScanChunkRecord> = readableDatabase.query(
        "chunks", null, "status!=? AND next_attempt_at<=?", arrayOf(ChunkStatus.UPLOADED.name, nowMs.toString()),
        null, null, "created_at ASC", limit.toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(chunkFromCursor(cursor)) } }

    private fun chunkFromCursor(cursor: Cursor): ScanChunkRecord = ScanChunkRecord(
        mapId = cursor.string("map_id"),
        id = cursor.string("id"),
        filePath = cursor.string("file_path"),
        pointCount = cursor.int("point_count"),
        status = enumValueOr(cursor.string("status"), ChunkStatus.PENDING),
        attempts = cursor.int("attempts"),
        nextAttemptAtMs = cursor.long("next_attempt_at"),
        createdAtMs = cursor.long("created_at")
    )

    @Synchronized
    fun markChunkUploaded(mapId: String, chunkId: String) {
        writableDatabase.update(
            "chunks", ContentValues().apply { put("status", ChunkStatus.UPLOADED.name) },
            "map_id=? AND id=?", arrayOf(mapId, chunkId)
        )
    }

    @Synchronized
    fun blockMissingChunk(record: ScanChunkRecord) {
        writableDatabase.update(
            "chunks",
            ContentValues().apply {
                put("status", ChunkStatus.FAILED.name)
                put("attempts", record.attempts + 1)
                put("next_attempt_at", Long.MAX_VALUE)
            },
            "map_id=? AND id=?", arrayOf(record.mapId, record.id)
        )
    }

    @Synchronized
    fun markChunkFailed(record: ScanChunkRecord, errorDelayMs: Long) {
        writableDatabase.update(
            "chunks",
            ContentValues().apply {
                put("status", ChunkStatus.FAILED.name)
                put("attempts", record.attempts + 1)
                put("next_attempt_at", System.currentTimeMillis() + errorDelayMs)
            },
            "map_id=? AND id=?", arrayOf(record.mapId, record.id)
        )
    }

    @Synchronized
    fun recoverInterruptedAnchors(mapId: String) {
        val values = ContentValues().apply {
            put("status", AnchorStatus.NEEDS_RESCAN.name)
            put("last_error", "App stopped before Cloud Anchor hosting completed")
            put("updated_at", System.currentTimeMillis())
            put("sync_pending", 1)
        }
        writableDatabase.update(
            "anchors", values,
            "map_id=? AND status IN (?,?)",
            arrayOf(mapId, AnchorStatus.PENDING.name, AnchorStatus.HOSTING.name)
        )
    }

    @Synchronized
    fun deleteMap(mapId: String) {
        writableDatabase.delete("maps", "id=?", arrayOf(mapId))
    }

    @Synchronized
    fun chunkCounts(mapId: String): Pair<Int, Int> = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(point_count),0) FROM chunks WHERE map_id=?", arrayOf(mapId)
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) to cursor.getInt(1) }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String): String = getString(column(name))
    private fun Cursor.nullableString(name: String): String? = column(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(name: String): Int = getInt(column(name))
    private fun Cursor.long(name: String): Long = getLong(column(name))
    private fun Cursor.float(name: String): Float = getFloat(column(name))
    private fun Cursor.nullableFloat(name: String): Float? = column(name).let { if (isNull(it)) null else getFloat(it) }
    private fun Cursor.nullableServerCount(name: String): Long? = long(name).takeIf { it >= 0L }

    private fun identityMatrix(): FloatArray = FloatArray(16).also { for (index in 0 until 16 step 5) it[index] = 1f }

    companion object {
        private const val SCHEMA_VERSION = 3
        private const val UNKNOWN_SERVER_COUNT = -1
    }
}
