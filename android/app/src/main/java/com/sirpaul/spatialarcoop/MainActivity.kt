package com.sirpaul.spatialarcoop

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sirpaul.spatialarcoop.data.AnchorStatus
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.data.MapStatus
import com.sirpaul.spatialarcoop.net.MapApiClient
import com.sirpaul.spatialarcoop.net.MapApiException
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.ui.FieldTheme
import com.sirpaul.spatialarcoop.util.Diagnostics
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var mapsContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var settingsBody: LinearLayout
    private var settingsVisible = false
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        renderMaps()
    }

    override fun onResume() {
        super.onResume()
        renderMaps()
        if (firstResume) {
            firstResume = false
            refreshFromServer(silent = true)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = ScrollView(this).apply {
            setBackgroundColor(FieldTheme.background)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(36))
        }
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(this).apply {
            text = "Spatial AR"
            textSize = 30f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Shared places and live cooperative AR"
            textSize = 14f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(24))
        })

        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Shared places"
            textSize = 19f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }, weightParams())
        header.addView(actionButton("Sync", primary = false) { refreshFromServer(silent = false) }, wrapParams())
        header.addView(actionButton("New place", primary = true, action = ::showCreateMapDialog), wrapParams(left = 8))
        content.addView(header)

        status = TextView(this).apply {
            text = "Scans are stored on this device first and synchronize when the server is available."
            textSize = 12f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(7), 0, dp(12))
        }
        content.addView(status)

        mapsContainer = vertical(0)
        content.addView(mapsContainer)

        val settingsToggle = actionButton("Server & diagnostics", primary = false) {
            settingsVisible = !settingsVisible
            settingsBody.visibility = if (settingsVisible) View.VISIBLE else View.GONE
        }
        content.addView(settingsToggle, marginParams(top = 20))

        settingsBody = vertical(dp(16)).apply {
            visibility = View.GONE
            background = solidBackground(FieldTheme.surface, radius = 6)
        }
        settingsBody.addView(TextView(this).apply {
            text = "Connection"
            textSize = 15f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        })
        serverInput = EditText(this).apply {
            setText(spatialApp.preferences.serverUrl)
            hint = "http://100.x.x.x:8080"
            setTextColor(FieldTheme.textPrimary)
            setHintTextColor(FieldTheme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        tokenInput = EditText(this).apply {
            setText(spatialApp.preferences.apiToken)
            hint = "API token (optional)"
            setTextColor(FieldTheme.textPrimary)
            setHintTextColor(FieldTheme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        settingsBody.addView(serverInput)
        settingsBody.addView(tokenInput)
        val connectionActions = horizontal()
        connectionActions.addView(actionButton("Save", primary = true) { saveSettings(showMessage = true) }, weightParams())
        connectionActions.addView(actionButton("Test & sync", primary = false) { refreshFromServer(silent = false) }, weightParams(left = 8))
        settingsBody.addView(connectionActions)

        settingsBody.addView(TextView(this).apply {
            text = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) {
                "Cloud Anchor credentials are configured in this build."
            } else {
                "Cloud Anchors are disabled in this build. Manual shared-origin alignment remains available for development."
            }
            textSize = 12f
            setTextColor(if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) FieldTheme.statusBlue else FieldTheme.accent)
            setPadding(0, dp(12), 0, dp(8))
        })
        val diagnosticsActions = horizontal()
        diagnosticsActions.addView(actionButton("Operator dashboard", primary = false) {
            saveSettings(showMessage = false)
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spatialApp.preferences.serverUrl))) }
                .onFailure { showStatus("Could not open dashboard: ${it.message}", true) }
        }, weightParams())
        diagnosticsActions.addView(actionButton("Share diagnostics", primary = false) {
            Diagnostics.shareLogs(this, spatialApp.logger)
        }, weightParams(left = 8))
        settingsBody.addView(diagnosticsActions)
        settingsBody.addView(TextView(this).apply {
            text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}"
            setTextColor(FieldTheme.textSecondary)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(settingsBody, marginParams(top = 8))

        content.addView(TextView(this).apply {
            text = "Camera inference stays on device. The server receives map metadata, sparse diagnostic geometry, participant poses and compact object tracks — not camera video."
            setTextColor(FieldTheme.textSecondary)
            textSize = 10f
            setPadding(0, dp(20), 0, 0)
        })
        return root
    }

    private fun saveSettings(showMessage: Boolean): Boolean {
        if (!::serverInput.isInitialized) return true
        val url = serverInput.text.toString().trim().trimEnd('/')
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            if (showMessage) showStatus("Server URL must start with http:// or https://", true)
            return false
        }
        spatialApp.preferences.serverUrl = url
        spatialApp.preferences.apiToken = tokenInput.text.toString()
        val reboundMaps = spatialApp.database.updateAllMapServerUrls(url)
        if (reboundMaps > 0) UploadScheduler.enqueue(this)
        spatialApp.logger.info("Server settings saved", mapOf("serverUrl" to url, "reboundMaps" to reboundMaps))
        if (showMessage) showStatus(if (reboundMaps > 0) "Connection saved · $reboundMaps local place(s) queued for sync" else "Connection saved", false)
        return true
    }

    private fun refreshFromServer(silent: Boolean) {
        val url = if (::serverInput.isInitialized) serverInput.text.toString().trim().trimEnd('/') else spatialApp.preferences.serverUrl
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        if (::serverInput.isInitialized && !saveSettings(showMessage = false)) return
        if (!silent) showStatus("Synchronizing shared places…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(url, spatialApp.preferences.apiToken, spatialApp.logger)
                api.listMaps().also { maps -> maps.forEach(spatialApp.database::mergeServerMap) }
            }.onSuccess { maps ->
                runOnUiThread {
                    showStatus("Server online · ${maps.size} shared place(s)", false)
                    renderMaps()
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Map refresh failed", mapOf("error" to error.message, "serverUrl" to url))
                if (!silent) runOnUiThread { showStatus("Server unavailable: ${error.message}. Local data is preserved.", true) }
            }
        }
    }

    private fun showCreateMapDialog() {
        if (!saveSettings(showMessage = false)) {
            settingsVisible = true
            settingsBody.visibility = View.VISIBLE
            showStatus("Set a valid server URL before creating a place.", true)
            return
        }
        val input = EditText(this).apply {
            hint = "Back garden / Workshop"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New shared place")
            .setMessage("Map setup stores scan chunks locally first. Finished chunks and hosted anchors survive app restarts and network loss.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ -> createMap(input.text.toString()) }
            .show()
    }

    private fun createMap(nameRaw: String) {
        val name = nameRaw.trim().ifBlank { "Untitled place" }
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
                text = "No shared places yet. Create one and complete Map setup before starting a live session."
                setTextColor(FieldTheme.textSecondary)
                textSize = 14f
                setPadding(0, dp(18), 0, dp(18))
            })
            return
        }
        maps.forEachIndexed { index, map ->
            if (index > 0) mapsContainer.addView(divider())
            mapsContainer.addView(mapRow(map))
        }
    }

    private fun mapRow(map: MapDefinition): View {
        val body = vertical(0).apply { setPadding(0, dp(16), 0, dp(17)) }
        val titleRow = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = map.name
            setTextColor(FieldTheme.textPrimary)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }, weightParams())
        titleRow.addView(TextView(this).apply {
            text = when (map.status) {
                MapStatus.READY -> "Ready"
                MapStatus.MAPPING -> "Map setup"
                MapStatus.ARCHIVED -> "Archived"
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (map.status == MapStatus.READY) FieldTheme.statusBlue else FieldTheme.accent)
        })
        body.addView(titleRow)

        val (localChunks, localPoints) = spatialApp.database.chunkCounts(map.id)
        val serverChunks = map.serverChunkCount
        val serverPoints = map.serverPointCount
        val hosted = map.anchors.count { it.status == AnchorStatus.HOSTED }
        val needsAttention = map.anchors.count { it.status == AnchorStatus.FAILED || it.status == AnchorStatus.NEEDS_RESCAN }
        body.addView(TextView(this).apply {
            text = geometrySummary(serverPoints, serverChunks, localPoints, localChunks)
            setTextColor(FieldTheme.textSecondary)
            textSize = 12f
            setPadding(0, dp(6), 0, dp(3))
        })
        body.addView(TextView(this).apply {
            text = buildString {
                append("$hosted hosted anchor")
                if (hosted != 1) append('s')
                if (needsAttention > 0) append(" · $needsAttention need attention")
                append(" · ${map.id}")
            }
            setTextColor(if (needsAttention > 0) FieldTheme.accent else FieldTheme.textSecondary)
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        })

        val readyForLive = map.status == MapStatus.READY && hosted > 0
        val actions = horizontal()
        val live = actionButton("Live AR session", primary = true) { launchMap(map.id, ArMode.LIVE) }.apply {
            isEnabled = readyForLive
            alpha = if (readyForLive) 1f else 0.45f
        }
        actions.addView(live, weightParams())
        actions.addView(actionButton("Manage map", primary = false) { showManageMapDialog(map) }, weightParams(left = 10))
        body.addView(actions)
        if (!readyForLive) {
            body.addView(TextView(this).apply {
                text = when {
                    map.status != MapStatus.READY -> "Complete Map setup before starting Live AR."
                    hosted == 0 -> "This place has no hosted Cloud Anchor yet. Open Manage map to add one."
                    else -> "Map setup is incomplete."
                }
                setTextColor(FieldTheme.accent)
                textSize = 11f
                setPadding(0, dp(8), 0, 0)
            })
        }
        return body
    }

    private fun geometrySummary(serverPoints: Int?, serverChunks: Int?, localPoints: Int, localChunks: Int): String {
        val nf = NumberFormat.getIntegerInstance()
        if (serverPoints == null || serverChunks == null) {
            return if (localChunks > 0) {
                "${nf.format(localPoints)} local points · $localChunks local scan chunks · server count not synchronized"
            } else {
                "Server geometry not synchronized · no local scan cache"
            }
        }
        if (localChunks == 0 && serverChunks > 0) {
            return "${nf.format(serverPoints)} points on server · $serverChunks scan chunks · not cached on this device"
        }
        if (localChunks > 0) {
            return "${nf.format(serverPoints)} server points · $serverChunks server chunks · ${nf.format(localPoints)} local points in $localChunks chunks"
        }
        return "${nf.format(serverPoints)} points on server · $serverChunks scan chunks"
    }

    private fun showManageMapDialog(map: MapDefinition) {
        val items = arrayOf("Continue Map setup", "Delete map…")
        AlertDialog.Builder(this)
            .setTitle(map.name)
            .setMessage("Map setup is the advanced owner workflow for scanning, anchors, ground calibration and recovery.")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchMap(map.id, ArMode.MAP)
                    1 -> showDeleteMapDialog(map)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeleteMapDialog(map: MapDefinition) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${map.name}?")
            .setMessage(
                "Local deletion removes this device's scan chunks, anchor metadata and pending uploads. " +
                    "Server + local also deletes the shared server copy. This cannot be undone."
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
                spatialApp.logger.warn("Server map deletion failed", mapOf("mapId" to map.id, "serverUrl" to map.serverUrl, "error" to error.message))
                runOnUiThread { showStatus("Server deletion failed: ${error.message}. Local data was preserved.", true) }
            }
        }
    }

    private fun deleteLocalMap(map: MapDefinition, serverDeleted: Boolean = false) {
        val directory = File(filesDir, "maps/${map.id}")
        val filesDeleted = !directory.exists() || directory.deleteRecursively()
        if (!filesDeleted) {
            showStatus(if (serverDeleted) "Server copy was deleted, but some local scan files remain." else "Could not remove all local scan files; database entry was preserved.", true)
            return
        }
        spatialApp.database.deleteMap(map.id)
        spatialApp.logger.warn("Map deleted locally", mapOf("mapId" to map.id, "serverDeleted" to serverDeleted))
        showStatus(if (serverDeleted) "Place deleted from server and this device" else "Local copy deleted; server copy was preserved", false)
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
        status.setTextColor(if (error) FieldTheme.error else FieldTheme.statusBlue)
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(if (primary) FieldTheme.background else FieldTheme.textPrimary)
        background = solidBackground(if (primary) FieldTheme.accent else FieldTheme.surfaceRaised, radius = 5)
        minHeight = dp(48)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { action() }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(FieldTheme.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun solidBackground(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun vertical(padding: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
    }

    private fun horizontal(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun weightParams(left: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(left), 0, 0, 0) }
    private fun wrapParams(left: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(left), 0, 0, 0) }
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(top), 0, dp(bottom)) }
}

enum class ArMode { MAP, SENSOR, VIEWER, LIVE }
