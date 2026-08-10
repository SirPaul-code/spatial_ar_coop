package com.sirpaul.spatialarcoop.data

import org.json.JSONArray
import org.json.JSONObject

enum class MapStatus { MAPPING, READY, ARCHIVED }
enum class AnchorStatus { PENDING, HOSTING, HOSTED, FAILED, NEEDS_RESCAN }
enum class FeatureQuality { INSUFFICIENT, SUFFICIENT, GOOD, UNKNOWN }
enum class ChunkStatus { PENDING, UPLOADED, FAILED }

data class AnchorDefinition(
    val mapId: String,
    val id: String,
    val cloudAnchorId: String,
    val siteFromAnchor: FloatArray,
    val status: AnchorStatus,
    val featureQuality: FeatureQuality,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val syncPending: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("cloudAnchorId", cloudAnchorId)
        .put("siteFromAnchor", JSONArray(siteFromAnchor.map { it.toDouble() }))
        .put("status", status.name)
        .put("featureQuality", featureQuality.name)
        .put("lastError", lastError ?: JSONObject.NULL)
        .put("updatedAt", updatedAtMs)

    companion object {
        fun fromJson(mapId: String, json: JSONObject): AnchorDefinition {
            val matrixJson = json.optJSONArray("siteFromAnchor") ?: JSONArray()
            val matrix = FloatArray(16) { index -> matrixJson.optDouble(index, if (index % 5 == 0) 1.0 else 0.0).toFloat() }
            return AnchorDefinition(
                mapId = mapId,
                id = json.getString("id"),
                cloudAnchorId = json.optString("cloudAnchorId", ""),
                siteFromAnchor = matrix,
                status = enumValueOr(json.optString("status"), AnchorStatus.PENDING),
                featureQuality = enumValueOr(json.optString("featureQuality"), FeatureQuality.UNKNOWN),
                lastError = json.optString("lastError").takeIf { it.isNotBlank() && it != "null" },
                updatedAtMs = parseTime(json.opt("updatedAt")),
                syncPending = false
            )
        }
    }
}

data class MapDefinition(
    val id: String,
    val name: String,
    val serverUrl: String,
    val status: MapStatus = MapStatus.MAPPING,
    val rootAnchorId: String? = null,
    val groundY: Float? = null,
    val anchorTtlDays: Int = 1,
    val minAnchorSpacingMeters: Float = 3f,
    val autoAnchor: Boolean = true,
    val anchors: List<AnchorDefinition> = emptyList(),
    /** Server-side sparse geometry summary. Null means it has not been synchronized yet. */
    val serverChunkCount: Int? = null,
    val serverPointCount: Int? = null,
    val serverScanBytes: Long? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val syncPending: Boolean = true
) {
    fun toCreateJson(deviceId: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdBy", deviceId)
        .put("groundY", groundY ?: JSONObject.NULL)
        .put("settings", JSONObject()
            .put("anchorTtlDays", anchorTtlDays)
            .put("minAnchorSpacingMeters", minAnchorSpacingMeters)
            .put("autoAnchor", autoAnchor))

    companion object {
        fun fromServer(json: JSONObject, serverUrl: String): MapDefinition {
            val id = json.getString("id")
            val settings = json.optJSONObject("settings") ?: JSONObject()
            val scan = json.optJSONObject("scan")
            val anchorsJson = json.optJSONArray("anchors") ?: JSONArray()
            val anchors = buildList {
                for (index in 0 until anchorsJson.length()) {
                    anchorsJson.optJSONObject(index)?.let { add(AnchorDefinition.fromJson(id, it)) }
                }
            }
            return MapDefinition(
                id = id,
                name = json.optString("name", id),
                serverUrl = serverUrl,
                status = enumValueOr(json.optString("status"), MapStatus.MAPPING),
                rootAnchorId = json.optString("rootAnchorId").takeIf { it.isNotBlank() && it != "null" },
                groundY = if (json.has("groundY") && !json.isNull("groundY")) json.optDouble("groundY").toFloat() else null,
                anchorTtlDays = settings.optInt("anchorTtlDays", 1).coerceIn(1, 365),
                minAnchorSpacingMeters = settings.optDouble("minAnchorSpacingMeters", 3.0).toFloat(),
                autoAnchor = settings.optBoolean("autoAnchor", true),
                anchors = anchors,
                serverChunkCount = scan?.optInt("chunkCount", 0)?.coerceAtLeast(0),
                serverPointCount = scan?.optInt("pointCount", 0)?.coerceAtLeast(0),
                serverScanBytes = scan?.optLong("bytes", 0L)?.coerceAtLeast(0L),
                updatedAtMs = parseTime(json.opt("updatedAt")),
                syncPending = false
            )
        }
    }
}

data class ScanChunkRecord(
    val mapId: String,
    val id: String,
    val filePath: String,
    val pointCount: Int,
    val status: ChunkStatus,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val createdAtMs: Long
)

data class SpatialTrack(
    val key: String,
    val id: String,
    val sourceId: String,
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val velocity: FloatArray,
    val uncertaintyMeters: Float,
    val observedAtMs: Long,
    val serverReceivedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("confidence", confidence)
        .put("position", JSONArray(position.map { it.toDouble() }))
        .put("velocity", JSONArray(velocity.map { it.toDouble() }))
        .put("uncertaintyMeters", uncertaintyMeters)
        .put("observedAtMs", observedAtMs)

    companion object {
        fun fromJson(json: JSONObject, sourceOverride: String? = null): SpatialTrack {
            val sourceId = sourceOverride ?: json.optString("sourceId", "unknown")
            val id = json.optString("id", "unknown")
            return SpatialTrack(
                key = json.optString("key", "$sourceId:$id"),
                id = id,
                sourceId = sourceId,
                label = json.optString("label", "unknown"),
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                position = json.floatArray("position", 3),
                velocity = json.floatArray("velocity", 3),
                uncertaintyMeters = json.optDouble("uncertaintyMeters", 0.5).toFloat(),
                observedAtMs = json.optLong("observedAtMs", System.currentTimeMillis()),
                serverReceivedAtMs = System.currentTimeMillis()
            )
        }
    }
}

internal fun JSONObject.floatArray(name: String, size: Int): FloatArray {
    val array = optJSONArray(name) ?: JSONArray()
    return FloatArray(size) { index -> array.optDouble(index, 0.0).toFloat() }
}

internal inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

internal fun parseTime(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    else -> System.currentTimeMillis()
}
