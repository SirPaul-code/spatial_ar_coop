package com.sirpaul.spatialarcoop.net

import com.sirpaul.spatialarcoop.data.AnchorDefinition
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.data.MapStatus
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

class MapApiClient(
    private val serverUrl: String,
    private val apiToken: String,
    private val logger: FileLogger,
    private val client: OkHttpClient = sharedClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val binaryMediaType = "application/octet-stream".toMediaType()

    fun listMaps(): List<MapDefinition> {
        val json = executeJson(Request.Builder().url(url("/api/v1/maps")).get())
        val array = json.optJSONArray("maps") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(MapDefinition.fromServer(it, serverUrl)) }
            }
        }
    }

    fun getMap(mapId: String): MapDefinition =
        MapDefinition.fromServer(executeJson(Request.Builder().url(url("/api/v1/maps/$mapId")).get()), serverUrl)

    fun createMap(map: MapDefinition, deviceId: String): MapDefinition {
        val request = Request.Builder()
            .url(url("/api/v1/maps"))
            .post(map.toCreateJson(deviceId).toString().toRequestBody(jsonMediaType))
        return MapDefinition.fromServer(executeJson(request), serverUrl)
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
        return MapDefinition.fromServer(executeJson(request), serverUrl)
    }

    fun deleteMap(mapId: String) {
        execute(Request.Builder().url(url("/api/v1/maps/$mapId")).delete()).close()
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

    fun ensureMap(map: MapDefinition, deviceId: String): MapDefinition {
        return try {
            getMap(map.id)
        } catch (error: MapApiException) {
            if (error.statusCode != 404) throw error
            try {
                createMap(map, deviceId)
            } catch (createError: MapApiException) {
                if (createError.statusCode == 409 || createError.apiCode == "MAP_EXISTS") getMap(map.id)
                else throw createError
            }
        }
    }

    private fun executeJson(builder: Request.Builder): JSONObject = execute(builder).use { response ->
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun execute(builder: Request.Builder): okhttp3.Response {
        if (apiToken.isNotBlank()) builder.header("Authorization", "Bearer $apiToken")
        val request = builder.header("User-Agent", "SpatialArCoop-Android/0.1").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val error = parsed?.optJSONObject("error")
            val nested = error?.optJSONObject("error") ?: error
            val code = nested?.optString("code")?.takeIf(String::isNotBlank)
            val message = nested?.optString("message")?.takeIf(String::isNotBlank)
                ?: "HTTP ${response.code} ${response.message}"
            response.close()
            logger.warn("Map API request failed", mapOf("url" to request.url.toString(), "status" to response.code, "code" to code, "message" to message))
            throw MapApiException(response.code, code, message)
        }
        return response
    }

    private fun url(path: String): String = serverUrl.trimEnd('/') + path

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
