package com.sirpaul.spatialarcoop

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.net.MapApiClient
import com.sirpaul.spatialarcoop.net.MapApiException
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.util.Diagnostics
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var mapsContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        renderMaps()
    }

    override fun onResume() {
        super.onResume()
        renderMaps()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(7, 17, 14)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(this).apply {
            text = "SPATIAL AR COOP"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Cooperative perception demo: map a site, publish detections, and render shared tracks through occluders."
            textSize = 14f
            setTextColor(Color.rgb(178, 196, 188))
            setPadding(0, dp(6), 0, dp(18))
        })

        val settings = card()
        val settingsBody = vertical(dp(14))
        settings.addView(settingsBody)
        settingsBody.addView(sectionTitle("SERVER"))
        serverInput = EditText(this).apply {
            setText(spatialApp.preferences.serverUrl)
            hint = "http://192.168.1.10:8080"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        tokenInput = EditText(this).apply {
            setText(spatialApp.preferences.apiToken)
            hint = "API token (optional)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        settingsBody.addView(serverInput)
        settingsBody.addView(tokenInput)
        val settingsActions = horizontal()
        settingsActions.addView(actionButton("SAVE") { saveSettings() }, weightParams())
        settingsActions.addView(actionButton("TEST + SYNC", ::refreshFromServer), weightParams())
        settingsBody.addView(settingsActions)
        settingsBody.addView(TextView(this).apply {
            text = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) {
                "Cloud Anchors: configured in this build"
            } else {
                "Cloud Anchors: NOT configured. Set ARCORE_API_KEY and rebuild; local scanning still works."
            }
            textSize = 12f
            setTextColor(if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) Color.rgb(117, 231, 176) else Color.rgb(255, 184, 92))
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(settings, marginParams(bottom = 14))

        val mapHeader = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        mapHeader.addView(sectionTitle("MAPS"), weightParams())
        mapHeader.addView(actionButton("NEW MAP", ::showCreateMapDialog), wrapParams())
        content.addView(mapHeader)
        status = TextView(this).apply {
            text = "Local-first: scans are saved before upload."
            textSize = 12f
            setTextColor(Color.rgb(156, 178, 168))
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(status)
        mapsContainer = vertical(0)
        content.addView(mapsContainer)

        val tools = card()
        val toolsBody = vertical(dp(14))
        tools.addView(toolsBody)
        toolsBody.addView(sectionTitle("DIAGNOSTICS"))
        val toolButtons = horizontal()
        toolButtons.addView(actionButton("SERVER PANEL") {
            saveSettings()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spatialApp.preferences.serverUrl))) }
                .onFailure { showStatus("Could not open server URL: ${it.message}", true) }
        }, weightParams())
        toolButtons.addView(actionButton("SHARE LOGS") { Diagnostics.shareLogs(this, spatialApp.logger) }, weightParams())
        toolsBody.addView(toolButtons)
        toolsBody.addView(TextView(this).apply {
            text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE}"
            setTextColor(Color.GRAY)
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(tools, marginParams(top = 14))
        content.addView(TextView(this).apply {
            text = "This application runs on Google Play Services for AR (ARCore), which is provided by Google LLC and governed by the Google Privacy Policy. Camera inference stays on device. Review the repository privacy and safety notes before distributing the app."
            setTextColor(Color.rgb(127, 145, 137))
            textSize = 10f
            setPadding(0, dp(14), 0, 0)
        })
        return root
    }

    private fun saveSettings(): Boolean {
        val url = serverInput.text.toString().trim().trimEnd('/')
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            showStatus("Server URL must start with http:// or https://", true)
            return false
        }
        spatialApp.preferences.serverUrl = url
        spatialApp.preferences.apiToken = tokenInput.text.toString()
        val reboundMaps = spatialApp.database.updateAllMapServerUrls(url)
        if (reboundMaps > 0) UploadScheduler.enqueue(this)
        spatialApp.logger.info("Server settings saved", mapOf("serverUrl" to url, "reboundMaps" to reboundMaps))
        showStatus(
            if (reboundMaps > 0) "Server saved • $reboundMaps local map(s) rebound for retry" else "Server settings saved",
            false
        )
        return true
    }

    private fun refreshFromServer() {
        if (!saveSettings()) return
        val url = spatialApp.preferences.serverUrl
        showStatus("Connecting to $url…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(url, spatialApp.preferences.apiToken, spatialApp.logger)
                api.listMaps().also { maps -> maps.forEach(spatialApp.database::mergeServerMap) }
            }.onSuccess { maps ->
                runOnUiThread {
                    showStatus("Server online • ${maps.size} map(s) synchronized", false)
                    renderMaps()
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Map refresh failed", mapOf("error" to error.message, "serverUrl" to url))
                runOnUiThread { showStatus("Server unavailable: ${error.message}. Local maps remain usable.", true) }
            }
        }
    }

    private fun showCreateMapDialog() {
        if (!saveSettings()) return
        val input = EditText(this).apply {
            hint = "Back yard / Airsoft field"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New spatial map")
            .setMessage("The scan is saved in small atomic chunks. Successful anchors and uploaded chunks survive retries and app restarts.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ -> createMap(input.text.toString()) }
            .show()
    }

    private fun createMap(nameRaw: String) {
        val name = nameRaw.trim().ifBlank { "Untitled map" }
        val slug = name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(42)
            .ifBlank { "map" }
        val id = "$slug-${UUID.randomUUID().toString().take(6)}"
        val map = MapDefinition(
            id = id,
            name = name,
            serverUrl = spatialApp.preferences.serverUrl,
            syncPending = true
        )
        spatialApp.database.upsertMap(map)
        UploadScheduler.enqueue(this)
        spatialApp.logger.info("Local map created", mapOf("mapId" to id, "name" to name))
        renderMaps()
        launchMap(map.id, ArMode.MAP)
    }

    private fun renderMaps() {
        if (!::mapsContainer.isInitialized) return
        mapsContainer.removeAllViews()
        val maps = spatialApp.database.listMaps()
        if (maps.isEmpty()) {
            mapsContainer.addView(TextView(this).apply {
                text = "No maps yet. Create one, then walk the site while the app stores point-cloud chunks and hosts visual anchors."
                setTextColor(Color.rgb(168, 185, 177))
                textSize = 14f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }
        maps.forEach { map -> mapsContainer.addView(mapCard(map), marginParams(bottom = 10)) }
    }

    private fun mapCard(map: MapDefinition): MaterialCardView {
        val card = card()
        val body = vertical(dp(14))
        card.addView(body)
        body.addView(TextView(this).apply {
            text = map.name
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        val (chunks, points) = spatialApp.database.chunkCounts(map.id)
        val hosted = map.anchors.count { it.status.name == "HOSTED" }
        val needsRescan = map.anchors.count { it.status.name == "NEEDS_RESCAN" }
        body.addView(TextView(this).apply {
            text = "${map.status} • $points points in $chunks chunks • $hosted hosted anchors${if (needsRescan > 0) " • $needsRescan rescan" else ""}\n${map.id}"
            setTextColor(Color.rgb(167, 190, 180))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
        val firstRow = horizontal()
        firstRow.addView(actionButton(if (map.anchors.isEmpty()) "MAP" else "RESUME MAP") { launchMap(map.id, ArMode.MAP) }, weightParams())
        firstRow.addView(actionButton("SENSOR") { launchMap(map.id, ArMode.SENSOR) }, weightParams())
        firstRow.addView(actionButton("VIEWER") { launchMap(map.id, ArMode.VIEWER) }, weightParams())
        body.addView(firstRow)
        val secondRow = horizontal()
        secondRow.addView(actionButton("DELETE MAP") { showDeleteMapDialog(map) }, weightParams())
        body.addView(secondRow)
        return card
    }

    private fun showDeleteMapDialog(map: MapDefinition) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${map.name}?")
            .setMessage(
                "This removes local scan chunks, anchor metadata, and pending uploads. " +
                    "Deleting from the server also disconnects active clients on this map. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Local only") { _, _ -> deleteLocalMap(map) }
            .setPositiveButton("Server + local") { _, _ -> deleteMapEverywhere(map) }
            .show()
    }

    private fun deleteMapEverywhere(map: MapDefinition) {
        showStatus("Deleting ${map.name} from server…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(map.serverUrl, spatialApp.preferences.apiToken, spatialApp.logger)
                try {
                    api.deleteMap(map.id)
                } catch (error: MapApiException) {
                    if (error.statusCode != 404) throw error
                }
            }.onSuccess {
                runOnUiThread { deleteLocalMap(map, serverDeleted = true) }
            }.onFailure { error ->
                spatialApp.logger.warn(
                    "Server map deletion failed",
                    mapOf("mapId" to map.id, "serverUrl" to map.serverUrl, "error" to error.message)
                )
                runOnUiThread {
                    showStatus("Server deletion failed: ${error.message}. Local data was preserved.", true)
                }
            }
        }
    }

    private fun deleteLocalMap(map: MapDefinition, serverDeleted: Boolean = false) {
        val directory = File(filesDir, "maps/${map.id}")
        val filesDeleted = !directory.exists() || directory.deleteRecursively()
        if (!filesDeleted) {
            showStatus(
                if (serverDeleted) {
                    "Server map was deleted, but some local files could not be removed."
                } else {
                    "Could not remove all local map files; the database entry was preserved."
                },
                true
            )
            return
        }
        spatialApp.database.deleteMap(map.id)
        spatialApp.logger.warn(
            "Map deleted locally",
            mapOf("mapId" to map.id, "serverDeleted" to serverDeleted)
        )
        showStatus(
            if (serverDeleted) "Map deleted from server and device" else "Local map deleted; server copy was preserved",
            false
        )
        renderMaps()
    }

    private fun launchMap(mapId: String, mode: ArMode) {
        startActivity(Intent(this, ArActivity::class.java).apply {
            putExtra(ArActivity.EXTRA_MAP_ID, mapId)
            putExtra(ArActivity.EXTRA_MODE, mode.name)
        })
    }

    private fun showStatus(message: String, error: Boolean) {
        status.text = message
        status.setTextColor(if (error) Color.rgb(255, 142, 126) else Color.rgb(117, 231, 176))
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(12).toFloat()
        setCardBackgroundColor(Color.rgb(16, 31, 27))
        strokeColor = Color.rgb(38, 66, 57)
        strokeWidth = dp(1)
    }

    private fun vertical(padding: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }

    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(117, 231, 176))
        setPadding(0, 0, 0, dp(6))
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setOnClickListener { action() }
        setTextColor(Color.WHITE)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun wrapParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }
}

enum class ArMode { MAP, SENSOR, VIEWER }
