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
    /** Stable public identity returned by /api/v1/info. Used to detect wrong-server invites. */
    val serverId: String = "",
    /** Secret credential scoped to this map only. Never serialized into map metadata payloads. */
    val accessKey: String = "",
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
        fun fromServer(
            json: JSONObject,
            serverUrl: String,
            serverId: String = json.optString("serverId", ""),
            accessKey: String = json.optString("accessKey", "")
        ): MapDefinition {
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
                serverUrl = serverUrl.trimEnd('/'),
                serverId = serverId,
                accessKey = accessKey,
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

/**
 * One body joint relative to a SpatialTrack ground-contact root in shared-site meters.
 * The MediaPipe landmark index is retained so every client uses the same skeleton topology.
 */
data class PoseJoint(
    val index: Int,
    val offsetMeters: FloatArray,
    val confidence: Float
) {
    fun toJson(): JSONArray = JSONArray()
        .put(index)
        .put(offsetMeters.getOrElse(0) { 0f }.toDouble())
        .put(offsetMeters.getOrElse(1) { 0f }.toDouble())
        .put(offsetMeters.getOrElse(2) { 0f }.toDouble())
        .put(confidence.toDouble())

    companion object {
        fun fromJson(value: JSONArray): PoseJoint? {
            if (value.length() != 5) return null
            val index = value.optInt(0, -1)
            if (index !in 0..32) return null
            val offset = FloatArray(3) { component -> value.optDouble(component + 1, Double.NaN).toFloat() }
            if (offset.any { !it.isFinite() || it !in -4f..4f }) return null
            val confidence = value.optDouble(4, Double.NaN).toFloat()
            if (!confidence.isFinite()) return null
            return PoseJoint(index, offset, confidence.coerceIn(0f, 1f))
        }
    }
}

data class SpatialTrack(
    val key: String,
    val id: String,
    val sourceId: String,
    val label: String,
    val confidence: Float,
    /** Ground-contact center in shared site coordinates. */
    val position: FloatArray,
    val velocity: FloatArray,
    val uncertaintyMeters: Float,
    val observedAtMs: Long,
    /** Physical [width, height, depth] used to render a true shared 3D volume. */
    val extentMeters: FloatArray = defaultTrackExtent(label),
    /** Rotation around shared-site +Y. Zero means the object's depth axis follows site +Z. */
    val yawRadians: Float = 0f,
    /** Optional compact person skeleton; each joint is relative to position in shared-site meters. */
    val poseJoints: List<PoseJoint> = emptyList(),
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
        .put("extentMeters", JSONArray(extentMeters.map { it.toDouble() }))
        .put("yawRadians", yawRadians)
        .apply {
            if (poseJoints.isNotEmpty()) put("poseJoints", JSONArray(poseJoints.map(PoseJoint::toJson)))
        }

    companion object {
        fun fromJson(json: JSONObject, sourceOverride: String? = null): SpatialTrack {
            val sourceId = sourceOverride ?: json.optString("sourceId", "unknown")
            val id = json.optString("id", "unknown")
            val label = json.optString("label", "unknown")
            val parsedExtent = json.optionalFloatArray("extentMeters", 3)
            val poseJson = json.optJSONArray("poseJoints")
            val poseJoints = buildList {
                if (poseJson != null) {
                    for (index in 0 until minOf(poseJson.length(), MAX_POSE_JOINTS)) {
                        poseJson.optJSONArray(index)?.let(PoseJoint::fromJson)?.let(::add)
                    }
                }
            }
            return SpatialTrack(
                key = json.optString("key", "$sourceId:$id"),
                id = id,
                sourceId = sourceId,
                label = label,
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                position = json.floatArray("position", 3),
                velocity = json.floatArray("velocity", 3),
                uncertaintyMeters = json.optDouble("uncertaintyMeters", 0.5).toFloat(),
                observedAtMs = json.optLong("observedAtMs", System.currentTimeMillis()),
                extentMeters = parsedExtent?.takeIf { values -> values.all { it.isFinite() && it > 0f } }
                    ?: defaultTrackExtent(label),
                yawRadians = json.optDouble("yawRadians", 0.0).toFloat().takeIf(Float::isFinite) ?: 0f,
                poseJoints = if (label.equals("person", true)) poseJoints else emptyList(),
                serverReceivedAtMs = System.currentTimeMillis()
            )
        }

        private const val MAX_POSE_JOINTS = 24
    }
}

fun defaultTrackExtent(label: String): FloatArray = when (label.lowercase()) {
    "person" -> floatArrayOf(0.60f, 1.72f, 0.45f)
    "car" -> floatArrayOf(1.85f, 1.50f, 4.40f)
    "bird" -> floatArrayOf(0.45f, 0.45f, 0.55f)
    "dog" -> floatArrayOf(0.55f, 0.70f, 1.00f)
    "cat" -> floatArrayOf(0.35f, 0.42f, 0.65f)
    else -> floatArrayOf(0.65f, 0.65f, 0.65f)
}

internal fun JSONObject.floatArray(name: String, size: Int): FloatArray {
    val array = optJSONArray(name) ?: JSONArray()
    return FloatArray(size) { index -> array.optDouble(index, 0.0).toFloat() }
}

private fun JSONObject.optionalFloatArray(name: String, size: Int): FloatArray? {
    val array = optJSONArray(name) ?: return null
    if (array.length() != size) return null
    return FloatArray(size) { index -> array.optDouble(index, Double.NaN).toFloat() }
}

internal inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

internal fun parseTime(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    else -> System.currentTimeMillis()
}
