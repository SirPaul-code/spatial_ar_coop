package com.sirpaul.spatialarcoop.net

import com.sirpaul.spatialarcoop.data.AnchorDefinition
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.util.FileLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class MapApiException(
    val statusCode: Int,
    val apiCode: String?,
    message: String
) : IOException(message)

data class ServerInfo(
    val serverId: String,
    val serverName: String,
    val protocolVersion: Int
)

data class MapInvite(
    val serverId: String,
    val serverName: String,
    val serverUrl: String,
    val mapId: String,
    val mapKey: String,
    val deepLink: String
)

class MapApiClient(
    private val serverUrl: String,
    /** May be an owner admin token or a single-map access key. */
    private val credential: String,
    private val logger: FileLogger,
    private val client: OkHttpClient = sharedClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val binaryMediaType = "application/octet-stream".toMediaType()
    private val normalizedServerUrl = serverUrl.trimEnd('/')

    fun getServerInfo(): ServerInfo {
        val json = executeJson(Request.Builder().url(url("/api/v1/info")).get())
        return ServerInfo(
            serverId = json.getString("serverId"),
            serverName = json.optString("serverName", "Spatial AR Server"),
            protocolVersion = json.optInt("protocolVersion", 1)
        )
    }

    /** Owner-only server listing. Participant credentials deliberately cannot call this. */
    fun listMaps(): List<MapDefinition> {
        val json = executeJson(Request.Builder().url(url("/api/v1/maps")).get())
        val serverId = json.optJSONObject("server")?.optString("serverId").orEmpty()
        val array = json.optJSONArray("maps") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    add(
                        MapDefinition.fromServer(
                            item,
                            normalizedServerUrl,
                            serverId = item.optString("serverId", serverId),
                            accessKey = item.optString("accessKey", "")
                        )
                    )
                }
            }
        }
    }

    fun getMap(mapId: String): MapDefinition {
        val json = executeJson(Request.Builder().url(url("/api/v1/maps/$mapId")).get())
        return MapDefinition.fromServer(
            json,
            normalizedServerUrl,
            serverId = json.optString("serverId", ""),
            accessKey = json.optString("accessKey", participantCredential())
        )
    }

    /** Owner-only creation. The returned map contains its newly generated per-map access key. */
    fun createMap(map: MapDefinition, deviceId: String): MapDefinition {
        val request = Request.Builder()
            .url(url("/api/v1/maps"))
            .post(map.toCreateJson(deviceId).toString().toRequestBody(jsonMediaType))
        val json = executeJson(request)
        return MapDefinition.fromServer(
            json,
            normalizedServerUrl,
            serverId = json.optString("serverId", ""),
            accessKey = json.optString("accessKey", "")
        )
    }

    fun patchMap(map: MapDefinition): MapDefinition {
        val payload = JSONObject()
            .put("name", map.name)
            .put("status", map.status.name)
            .put("rootAnchorId", map.rootAnchorId ?: JSONObject.NULL)
            .put("groundY", map.groundY ?: JSONObject.NULL)
            .put("settings", JSONObject()
                .put("anchorTtlDays", map.anchorTtlDays)
                .put("minAnchorSpacingMeters", map.minAnchorSpacingMeters)
                .put("autoAnchor", map.autoAnchor))
        val request = Request.Builder()
            .url(url("/api/v1/maps/${map.id}"))
            .patch(payload.toString().toRequestBody(jsonMediaType))
        val json = executeJson(request)
        return MapDefinition.fromServer(
            json,
            normalizedServerUrl,
            serverId = json.optString("serverId", map.serverId),
            accessKey = map.accessKey
        )
    }

    fun deleteMap(mapId: String) {
        execute(Request.Builder().url(url("/api/v1/maps/$mapId")).delete()).close()
    }

    fun getInvite(mapId: String): MapInvite {
        val json = executeJson(Request.Builder().url(url("/api/v1/maps/$mapId/invite")).get())
        return parseInvite(json.getJSONObject("invite"))
    }

    /** Owner-only revocation. The returned invite contains the replacement key. */
    fun rotateMapKey(mapId: String): MapInvite {
        val request = Request.Builder()
            .url(url("/api/v1/maps/$mapId/rotate-key"))
            .post(ByteArray(0).toRequestBody(null))
        return parseInvite(executeJson(request).getJSONObject("invite"))
    }

    fun upsertAnchor(anchor: AnchorDefinition) {
        val request = Request.Builder()
            .url(url("/api/v1/maps/${anchor.mapId}/anchors"))
            .post(anchor.toJson().toString().toRequestBody(jsonMediaType))
        executeJson(request)
    }

    fun uploadScanChunk(mapId: String, chunkId: String, deviceId: String, file: File) {
        val request = Request.Builder()
            .url(url("/api/v1/maps/$mapId/scan-chunks"))
            .header("X-Chunk-Id", chunkId)
            .header("X-Device-Id", deviceId)
            .post(file.asRequestBody(binaryMediaType))
        execute(request).close()
    }

    private fun parseInvite(json: JSONObject): MapInvite = MapInvite(
        serverId = json.getString("serverId"),
        serverName = json.optString("serverName", "Spatial AR Server"),
        serverUrl = json.optString("serverUrl", normalizedServerUrl).ifBlank { normalizedServerUrl }.trimEnd('/'),
        mapId = json.getString("mapId"),
        mapKey = json.getString("mapKey"),
        deepLink = json.optString("deepLink")
    )

    private fun participantCredential(): String =
        credential.takeIf { it.startsWith("sar_map_") }.orEmpty()

    private fun executeJson(builder: Request.Builder): JSONObject = execute(builder).use { response ->
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun execute(builder: Request.Builder): okhttp3.Response {
        if (credential.isNotBlank()) builder.header("Authorization", "Bearer $credential")
        val request = builder.header("User-Agent", "SpatialArCoop-Android/1.0").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val status = response.code
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val error = parsed?.optJSONObject("error")
            val nested = error?.optJSONObject("error") ?: error
            val code = nested?.optString("code")?.takeIf(String::isNotBlank)
            val message = nested?.optString("message")?.takeIf(String::isNotBlank)
                ?: "HTTP $status ${response.message}"
            response.close()
            logger.warn(
                "Map API request failed",
                mapOf("url" to request.url.toString(), "status" to status, "code" to code, "message" to message)
            )
            throw MapApiException(status, code, message)
        }
        return response
    }

    private fun url(path: String): String = normalizedServerUrl + path

    companion object {
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
