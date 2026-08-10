package com.sirpaul.spatialarcoop.data

import android.content.Context
import com.sirpaul.spatialarcoop.BuildConfig
import java.util.UUID

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("spatial_ar_coop", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString("server_url", BuildConfig.DEFAULT_SERVER_URL)?.trimEnd('/') ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) { preferences.edit().putString("server_url", value.trim().trimEnd('/')).apply() }

    var apiToken: String
        get() = preferences.getString("api_token", BuildConfig.DEFAULT_API_TOKEN) ?: ""
        set(value) { preferences.edit().putString("api_token", value.trim()).apply() }

    val deviceId: String
        get() {
            preferences.getString("device_id", null)?.let { return it }
            val value = "android-${UUID.randomUUID()}"
            preferences.edit().putString("device_id", value).apply()
            return value
        }

    var detectorThreshold: Float
        get() = preferences.getFloat("detector_threshold", 0.48f)
        set(value) { preferences.edit().putFloat("detector_threshold", value.coerceIn(0.1f, 0.95f)).apply() }
}
