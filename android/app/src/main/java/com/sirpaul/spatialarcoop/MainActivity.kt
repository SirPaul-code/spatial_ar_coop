package com.sirpaul.spatialarcoop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        handleJoinIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinIntent(intent)
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
            text = "Private self-hosted places and live cooperative AR"
            textSize = 14f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(24))
        })

        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "My places"
            textSize = 19f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }, weightParams())
        header.addView(actionButton("Sync owner", primary = false) { refreshFromServer(silent = false) }, wrapParams())
        header.addView(actionButton("New place", primary = true, action = ::showCreateMapDialog), wrapParams(left = 8))
        content.addView(header)

        status = TextView(this).apply {
            text = "Each place is private to its server and map key. No global server or map directory is used."
            textSize = 12f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(7), 0, dp(8))
        }
        content.addView(status)
        content.addView(actionButton("Join a shared place", primary = false, action = ::showJoinInviteDialog), marginParams(bottom = 12))

        mapsContainer = vertical(0)
        content.addView(mapsContainer)

        val settingsToggle = actionButton("Server owner & diagnostics", primary = false) {
            settingsVisible = !settingsVisible
            settingsBody.visibility = if (settingsVisible) View.VISIBLE else View.GONE
        }
        content.addView(settingsToggle, marginParams(top = 20))

        settingsBody = vertical(dp(16)).apply {
            visibility = View.GONE
            background = solidBackground(FieldTheme.surface, radius = 6)
        }
        settingsBody.addView(TextView(this).apply {
            text = "Owner connection"
            textSize = 15f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        })
        settingsBody.addView(TextView(this).apply {
            text = "This profile is used only to create/list/delete maps you own. Joined places keep their own server URL and map key."
            textSize = 11f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(6))
        })
        serverInput = EditText(this).apply {
            setText(spatialApp.preferences.serverUrl)
            hint = "https://server.tailnet.ts.net"
            setTextColor(FieldTheme.textPrimary)
            setHintTextColor(FieldTheme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        tokenInput = EditText(this).apply {
            setText(spatialApp.preferences.apiToken)
            hint = "sar_admin_… (owner token)"
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
            text = "Camera inference stays on device. Servers receive map metadata, sparse diagnostic geometry, participant poses and compact object tracks — not camera video."
            setTextColor(FieldTheme.textSecondary)
            textSize = 10f
            setPadding(0, dp(20), 0, 0)
        })
        return root
    }

    private fun saveSettings(showMessage: Boolean): Boolean {
        if (!::serverInput.isInitialized) return true
        val url = serverInput.text.toString().trim().trimEnd('/')
        if (!validServerUrl(url)) {
            if (showMessage) showStatus("Server URL must start with http:// or https://", true)
            return false
        }
        spatialApp.preferences.serverUrl = url
        spatialApp.preferences.apiToken = tokenInput.text.toString().trim()
        spatialApp.logger.info("Owner server settings saved", mapOf("serverUrl" to url))
        if (showMessage) showStatus("Owner connection saved. Existing joined places were not changed.", false)
        return true
    }

    private fun refreshFromServer(silent: Boolean) {
        val url = if (::serverInput.isInitialized) serverInput.text.toString().trim().trimEnd('/') else spatialApp.preferences.serverUrl
        if (!validServerUrl(url)) return
        if (::serverInput.isInitialized && !saveSettings(showMessage = false)) return
        if (!silent) showStatus("Checking owner server…", false)
        val adminToken = spatialApp.preferences.apiToken
        executor.execute {
            runCatching {
                val api = MapApiClient(url, adminToken, spatialApp.logger)
                val info = api.getServerInfo()
                val maps = if (adminToken.isBlank()) emptyList() else api.listMaps()
                maps.forEach(spatialApp.database::mergeServerMap)
                info to maps
            }.onSuccess { (info, maps) ->
                runOnUiThread {
                    val detail = if (adminToken.isBlank()) {
                        "Server ${info.serverName} · ${shortId(info.serverId)} online · add owner token to list maps"
                    } else {
                        "Server ${info.serverName} · ${shortId(info.serverId)} · ${maps.size} owner place(s)"
                    }
                    showStatus(detail, false)
                    renderMaps()
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Owner map refresh failed", mapOf("error" to error.message, "serverUrl" to url))
                if (!silent) runOnUiThread { showStatus("Server check failed: ${error.message}. Local places are preserved.", true) }
            }
        }
    }

    private fun showCreateMapDialog() {
        if (!saveSettings(showMessage = false)) {
            settingsVisible = true
            settingsBody.visibility = View.VISIBLE
            showStatus("Set a valid owner server URL before creating a place.", true)
            return
        }
        if (spatialApp.preferences.apiToken.isBlank()) {
            settingsVisible = true
            settingsBody.visibility = View.VISIBLE
            showStatus("Creating a place requires this server's owner admin token.", true)
            return
        }
        val input = EditText(this).apply {
            hint = "Back garden / Workshop"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New private place")
            .setMessage("The server creates an independent random map key. Only people you share that map invite with can open the place.")
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
        val url = spatialApp.preferences.serverUrl.trimEnd('/')
        val token = spatialApp.preferences.apiToken
        showStatus("Creating $name on owner server…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(url, token, spatialApp.logger)
                val info = api.getServerInfo()
                val draft = MapDefinition(id = id, name = name, serverUrl = url, serverId = info.serverId)
                val remote = api.createMap(draft, spatialApp.preferences.deviceId)
                check(remote.serverId == info.serverId) { "Server identity changed while creating the map" }
                check(remote.accessKey.startsWith("sar_map_")) { "Server did not return a per-map access key" }
                remote
            }.onSuccess { map ->
                spatialApp.database.upsertMap(map.copy(syncPending = false))
                spatialApp.logger.info("Private map created", mapOf("mapId" to map.id, "serverId" to map.serverId))
                runOnUiThread {
                    showStatus("${map.name} created · private map key saved on this device", false)
                    renderMaps()
                    launchMap(map.id, ArMode.MAP)
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Map creation failed", mapOf("mapId" to id, "serverUrl" to url, "error" to error.message))
                runOnUiThread { showStatus("Could not create place: ${error.message}", true) }
            }
        }
    }

    private fun showJoinInviteDialog() {
        val input = EditText(this).apply {
            hint = "spatialar://join?…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Join shared place")
            .setMessage("Paste the invite link from the place owner. It grants access only to that map, not to the owner's other maps.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Join") { _, _ -> importInvite(input.text.toString()) }
            .show()
    }

    private fun handleJoinIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("spatialar", ignoreCase = true)) {
            importInvite(intent.data.toString())
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun importInvite(raw: String) {
        val parsed = runCatching { parseInvite(raw) }.getOrElse {
            showStatus("Invalid invite: ${it.message}", true)
            return
        }
        showStatus("Verifying ${shortId(parsed.serverId)} and opening ${parsed.mapId}…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(parsed.serverUrl, parsed.mapKey, spatialApp.logger)
                val info = api.getServerInfo()
                check(info.serverId == parsed.serverId) {
                    "Server identity mismatch: invite is for ${parsed.serverId}, endpoint reports ${info.serverId}"
                }
                val remote = api.getMap(parsed.mapId)
                check(remote.id == parsed.mapId) { "Server returned a different map" }
                remote.copy(
                    serverUrl = parsed.serverUrl,
                    serverId = parsed.serverId,
                    accessKey = parsed.mapKey,
                    syncPending = false
                )
            }.onSuccess { map ->
                spatialApp.database.mergeServerMap(map)
                spatialApp.database.upsertMap(map)
                spatialApp.logger.info("Map invite joined", mapOf("mapId" to map.id, "serverId" to map.serverId))
                runOnUiThread {
                    showStatus("Joined ${map.name} on ${shortId(map.serverId)}", false)
                    renderMaps()
                    if (map.status == MapStatus.READY) launchMap(map.id, ArMode.LIVE)
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Map invite rejected", mapOf("serverId" to parsed.serverId, "mapId" to parsed.mapId, "error" to error.message))
                runOnUiThread { showStatus("Could not join invite: ${error.message}", true) }
            }
        }
    }

    private data class ParsedInvite(val serverUrl: String, val serverId: String, val mapId: String, val mapKey: String)

    private fun parseInvite(raw: String): ParsedInvite {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme.equals("spatialar", ignoreCase = true) && uri.host.equals("join", ignoreCase = true)) {
            "expected spatialar://join link"
        }
        val serverId = uri.getQueryParameter("serverId").orEmpty()
        val mapId = uri.getQueryParameter("mapId").orEmpty()
        val mapKey = uri.getQueryParameter("key").orEmpty()
        val serverUrl = uri.getQueryParameter("url")?.trim()?.trimEnd('/')
            ?.takeIf(::validServerUrl)
            ?: spatialApp.preferences.serverUrl.trim().trimEnd('/').takeIf(::validServerUrl)
            ?: error("invite has no server URL and no valid owner/default server is configured")
        require(serverId.matches(Regex("[a-zA-Z0-9._-]{4,96}"))) { "invalid serverId" }
        require(mapId.matches(Regex("[a-zA-Z0-9._-]{1,96}"))) { "invalid mapId" }
        require(mapKey.startsWith("sar_map_") && mapKey.length >= 32) { "invalid map key" }
        return ParsedInvite(serverUrl, serverId, mapId, mapKey)
    }

    private fun renderMaps() {
        if (!::mapsContainer.isInitialized) return
        mapsContainer.removeAllViews()
        val maps = spatialApp.database.listMaps()
        if (maps.isEmpty()) {
            mapsContainer.addView(TextView(this).apply {
                text = "No places on this device. Create one on your server or join an invite."
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
                append(" · ${shortId(map.serverId.ifBlank { "legacy" })} · ${map.id}")
            }
            setTextColor(if (needsAttention > 0) FieldTheme.accent else FieldTheme.textSecondary)
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        })

        val readyForLive = map.status == MapStatus.READY && (hosted > 0 || !BuildConfig.CLOUD_ANCHORS_CONFIGURED)
        val actions = horizontal()
        val live = actionButton("Live AR session", primary = true) { launchMap(map.id, ArMode.LIVE) }.apply {
            isEnabled = readyForLive
            alpha = if (readyForLive) 1f else 0.45f
        }
        actions.addView(live, weightParams())
        actions.addView(actionButton("Manage & share", primary = false) { showManageMapDialog(map) }, weightParams(left = 10))
        body.addView(actions)
        if (!readyForLive) {
            body.addView(TextView(this).apply {
                text = when {
                    map.status != MapStatus.READY -> "Complete Map setup before starting Live AR."
                    hosted == 0 -> "This build expects a hosted Cloud Anchor for a ready shared place."
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
        val items = arrayOf("Continue Map setup", "Share invite", "Rotate invite key…", "Delete map…")
        AlertDialog.Builder(this)
            .setTitle(map.name)
            .setMessage("Map setup changes shared geometry. Sharing exposes only this place's map key. Rotating it revokes old invites.")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchMap(map.id, ArMode.MAP)
                    1 -> shareMap(map)
                    2 -> confirmRotateMapKey(map)
                    3 -> showDeleteMapDialog(map)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun shareMap(map: MapDefinition) {
        if (map.serverId.isBlank() || map.accessKey.isBlank()) {
            showStatus("This legacy/local place has no share key yet. Sync it from the owner server first.", true)
            return
        }
        val link = buildInviteLink(map)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spatial AR invite", link))
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Spatial AR invite: ${map.name}")
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(share, "Share private place invite"))
        showStatus("Invite copied. It grants access only to ${map.name}.", false)
    }

    private fun buildInviteLink(map: MapDefinition): String = Uri.Builder()
        .scheme("spatialar")
        .authority("join")
        .appendQueryParameter("url", map.serverUrl.trimEnd('/'))
        .appendQueryParameter("serverId", map.serverId)
        .appendQueryParameter("mapId", map.id)
        .appendQueryParameter("key", map.accessKey)
        .build()
        .toString()

    private fun confirmRotateMapKey(map: MapDefinition) {
        if (spatialApp.preferences.apiToken.isBlank()) {
            showStatus("Rotating an invite requires the owner admin token in Server owner settings.", true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Revoke old invites?")
            .setMessage("This creates a new key for ${map.name}. Existing saved copies using the old key will lose server access until they receive the new invite.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rotate key") { _, _ -> rotateMapKey(map) }
            .show()
    }

    private fun rotateMapKey(map: MapDefinition) {
        showStatus("Rotating invite key for ${map.name}…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(map.serverUrl, spatialApp.preferences.apiToken, spatialApp.logger)
                val info = api.getServerInfo()
                if (map.serverId.isNotBlank()) check(info.serverId == map.serverId) { "Owner token points to the wrong server" }
                val invite = api.rotateMapKey(map.id)
                check(invite.serverId == info.serverId) { "Server identity changed while rotating key" }
                map.copy(serverId = invite.serverId, accessKey = invite.mapKey, syncPending = false)
            }.onSuccess { updated ->
                spatialApp.database.upsertMap(updated)
                runOnUiThread {
                    renderMaps()
                    showStatus("Old invites revoked. Share the new invite with active participants.", false)
                    shareMap(updated)
                }
            }.onFailure { error ->
                runOnUiThread { showStatus("Could not rotate invite: ${error.message}", true) }
            }
        }
    }

    private fun showDeleteMapDialog(map: MapDefinition) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${map.name}?")
            .setMessage(
                "Local deletion removes this device's scan chunks, anchor metadata and pending uploads. " +
                    "Server + local requires the owner admin token and deletes the shared server copy. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Local only") { _, _ -> deleteLocalMap(map) }
            .setPositiveButton("Server + local") { _, _ -> deleteMapEverywhere(map) }
            .show()
    }

    private fun deleteMapEverywhere(map: MapDefinition) {
        if (spatialApp.preferences.apiToken.isBlank()) {
            showStatus("Server deletion requires the owner admin token.", true)
            return
        }
        showStatus("Deleting ${map.name} from server…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(map.serverUrl, spatialApp.preferences.apiToken, spatialApp.logger)
                val info = api.getServerInfo()
                if (map.serverId.isNotBlank()) check(info.serverId == map.serverId) { "Owner token points to the wrong server" }
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

    private fun validServerUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    private fun shortId(value: String): String = when {
        value.length <= 18 -> value
        else -> value.take(9) + "…" + value.takeLast(6)
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
