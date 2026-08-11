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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sirpaul.spatialarcoop.data.AnchorStatus
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.data.MapStatus
import com.sirpaul.spatialarcoop.net.MapApiClient
import com.sirpaul.spatialarcoop.net.MapApiException
import com.sirpaul.spatialarcoop.ui.FieldTheme
import com.sirpaul.spatialarcoop.ui.QrTools
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

    private val placeScanner by lazy {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
    }

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
            text = "Private self-hosted places · scan a place QR to join"
            textSize = 14f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(20))
        })

        val joinRow = horizontal()
        joinRow.addView(actionButton("Scan place QR", primary = true, action = ::scanPlaceQr), weightParams())
        joinRow.addView(actionButton("Paste invite", primary = false, action = ::showPasteInviteDialog), weightParams(left = 8))
        content.addView(joinRow)
        content.addView(TextView(this).apply {
            text = "A place QR gives this phone access to one shared map only. It never contains the server owner token."
            textSize = 11f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(7), 0, dp(22))
        })

        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Places on this phone"
            textSize = 19f
            setTextColor(FieldTheme.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }, weightParams())
        header.addView(actionButton("Refresh", primary = false) { refreshFromServer(silent = false) }, wrapParams())
        header.addView(actionButton("New place", primary = true, action = ::showCreateMapDialog), wrapParams(left = 8))
        content.addView(header)

        status = TextView(this).apply {
            text = "Ready. Joined places keep their own server address and private map key."
            textSize = 12f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(7), 0, dp(8))
        }
        content.addView(status)

        mapsContainer = vertical(0)
        content.addView(mapsContainer)

        val settingsToggle = actionButton("Owner server & diagnostics", primary = false) {
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
            text = "Only the server owner needs this admin token. It can create and list every map on that server. Participants join individual places with QR codes instead."
            textSize = 11f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(6))
        })
        serverInput = EditText(this).apply {
            setText(spatialApp.preferences.serverUrl)
            hint = "http://100.x.y.z:8080 or https://server.tailnet.ts.net"
            setTextColor(FieldTheme.textPrimary)
            setHintTextColor(FieldTheme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        tokenInput = EditText(this).apply {
            setText(spatialApp.preferences.apiToken)
            hint = "sar_admin_… (owner only)"
            setTextColor(FieldTheme.textPrimary)
            setHintTextColor(FieldTheme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        settingsBody.addView(serverInput)
        settingsBody.addView(tokenInput)
        settingsBody.addView(actionButton("Save & verify owner server", primary = true) {
            if (saveSettings(showMessage = false)) refreshFromServer(silent = false)
        }, marginParams(top = 8))

        settingsBody.addView(TextView(this).apply {
            text = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) {
                "Cloud Anchor credentials are configured in this build."
            } else {
                "Cloud Anchors are disabled in this build. Manual shared-origin fallback remains available for development."
            }
            textSize = 12f
            setTextColor(if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) FieldTheme.statusBlue else FieldTheme.accent)
            setPadding(0, dp(12), 0, dp(8))
        })
        val diagnosticsActions = horizontal()
        diagnosticsActions.addView(actionButton("Open operator dashboard", primary = false) { openOwnerDashboard() }, weightParams())
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

    private fun scanPlaceQr() {
        showStatus("Opening QR scanner…", false)
        placeScanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue?.trim().orEmpty()
                if (raw.isBlank()) showStatus("The QR code did not contain a Spatial AR invite.", true)
                else importInvite(raw)
            }
            .addOnCanceledListener { showStatus("QR scan cancelled.", false) }
            .addOnFailureListener { error ->
                spatialApp.logger.warn("Place QR scanner failed", mapOf("error" to error.message))
                showStatus("Could not open QR scanner: ${error.message}", true)
            }
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
        if (showMessage) showStatus("Owner connection saved. Joined places were not changed.", false)
        return true
    }

    private fun refreshFromServer(silent: Boolean) {
        val url = if (::serverInput.isInitialized) serverInput.text.toString().trim().trimEnd('/') else spatialApp.preferences.serverUrl
        if (!validServerUrl(url)) return
        if (::serverInput.isInitialized && !saveSettings(showMessage = false)) return
        val adminToken = spatialApp.preferences.apiToken
        if (!silent) {
            showStatus(
                if (adminToken.isBlank()) "Checking server identity…" else "Checking owner server and refreshing owned maps…",
                false
            )
        }
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
                        "${info.serverName} · ${shortId(info.serverId)} is online. Add its owner token only if this phone manages that server."
                    } else {
                        "Owner verified · ${info.serverName} · ${maps.size} owned place(s) refreshed"
                    }
                    showStatus(detail, false)
                    renderMaps()
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Owner map refresh failed", mapOf("error" to error.message, "serverUrl" to url))
                if (!silent) runOnUiThread {
                    val help = if (error is MapApiException && error.statusCode == 401) {
                        "Owner verification failed. This field needs the sar_admin_… server token, not a sar_map_… place key."
                    } else {
                        "Server check failed: ${error.message}. Local/joined places are preserved."
                    }
                    showStatus(help, true)
                }
            }
        }
    }

    private fun showCreateMapDialog() {
        if (!saveSettings(showMessage = false)) {
            revealOwnerSettings("Set a valid owner server URL before creating a place.")
            return
        }
        if (spatialApp.preferences.apiToken.isBlank()) {
            revealOwnerSettings("Creating a place requires this server's sar_admin_… owner token.")
            return
        }
        val input = EditText(this).apply {
            hint = "Back garden / Workshop"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New private place")
            .setMessage("The server creates a separate private map key automatically. After setup, show the place QR to participants.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create & start setup") { _, _ -> createMap(input.text.toString()) }
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
        showStatus("Creating $name on the owner server…", false)
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
                    showStatus("${map.name} created · starting guided map setup…", false)
                    renderMaps()
                    launchMap(map.id, ArMode.MAP)
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Map creation failed", mapOf("mapId" to id, "serverUrl" to url, "error" to error.message))
                runOnUiThread { showStatus("Could not create place: ${error.message}", true) }
            }
        }
    }

    private fun showPasteInviteDialog() {
        val input = EditText(this).apply {
            hint = "spatialar://join?…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Paste place invite")
            .setMessage("Usually you can just scan the owner's QR. Paste is available for links received in chat or email.")
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
            showStatus("Invalid place invite: ${it.message}", true)
            return
        }
        showStatus("Verifying server ${shortId(parsed.serverId)} and opening ${parsed.mapId}…", false)
        executor.execute {
            runCatching {
                val api = MapApiClient(parsed.serverUrl, parsed.mapKey, spatialApp.logger)
                val info = api.getServerInfo()
                check(info.serverId == parsed.serverId) {
                    "Server identity mismatch: invite expects ${parsed.serverId}, endpoint reports ${info.serverId}"
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
                    showStatus("Joined ${map.name} · this phone can access only that shared place", false)
                    renderMaps()
                    if (map.status == MapStatus.READY) prepareAndLaunch(map, ArMode.LIVE)
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
            "expected a spatialar://join QR/link"
        }
        val serverId = uri.getQueryParameter("serverId").orEmpty()
        val mapId = uri.getQueryParameter("mapId").orEmpty()
        val mapKey = uri.getQueryParameter("key").orEmpty()
        val serverUrl = uri.getQueryParameter("url")?.trim()?.trimEnd('/')
            ?.takeIf(::validServerUrl)
            ?: spatialApp.preferences.serverUrl.trim().trimEnd('/').takeIf(::validServerUrl)
            ?: error("invite has no usable server URL")
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
                text = "No places on this phone yet. Scan a place QR, or configure an owner server and create one."
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
                MapStatus.MAPPING -> "Setup in progress"
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
                if (map.serverId.isNotBlank()) append(" · ${shortId(map.serverId)}")
            }
            setTextColor(if (needsAttention > 0) FieldTheme.accent else FieldTheme.textSecondary)
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        })

        val readyForLive = map.status == MapStatus.READY && (hosted > 0 || !BuildConfig.CLOUD_ANCHORS_CONFIGURED)
        val actions = horizontal()
        val live = actionButton("Live AR", primary = true) { prepareAndLaunch(map, ArMode.LIVE) }.apply {
            isEnabled = readyForLive
            alpha = if (readyForLive) 1f else 0.45f
        }
        actions.addView(live, weightParams())
        actions.addView(actionButton(if (map.status == MapStatus.READY) "Share & manage" else "Continue setup", primary = false) {
            if (map.status == MapStatus.READY) showManageMapDialog(map) else prepareAndLaunch(map, ArMode.MAP)
        }, weightParams(left = 10))
        body.addView(actions)
        if (!readyForLive) {
            body.addView(TextView(this).apply {
                text = when {
                    map.status != MapStatus.READY -> "Map setup is not finished yet. Open Continue setup and follow the on-camera progress."
                    hosted == 0 -> "A hosted Cloud Anchor is still required before shared Live AR is ready."
                    else -> "Map setup needs attention."
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
                "${nf.format(localPoints)} local points · $localChunks local chunks · waiting for server refresh"
            } else {
                "Server geometry not refreshed · no local scan cache"
            }
        }
        if (localChunks == 0 && serverChunks > 0) {
            return "${nf.format(serverPoints)} points on server · $serverChunks chunks · geometry is not cached on this phone"
        }
        if (localChunks > 0) {
            return "${nf.format(serverPoints)} server points · $serverChunks server chunks · ${nf.format(localPoints)} local points"
        }
        return "${nf.format(serverPoints)} points on server · $serverChunks scan chunks"
    }

    private fun showManageMapDialog(map: MapDefinition) {
        val content = vertical(dp(14))
        content.addView(TextView(this).apply {
            text = "Share this place"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FieldTheme.textPrimary)
        })
        content.addView(TextView(this).apply {
            text = "Anyone who scans this QR gets access to ${map.name} only. They do not receive the server owner token or any other map."
            textSize = 12f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(5), 0, dp(10))
        })

        val invite = if (map.serverId.isNotBlank() && map.accessKey.startsWith("sar_map_")) buildInviteLink(map) else null
        if (invite != null) {
            content.addView(ImageView(this).apply {
                setImageBitmap(QrTools.encode(invite, dp(300)))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "QR invite for ${map.name}"
                setBackgroundColor(android.graphics.Color.rgb(242, 239, 232))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(316)))
            content.addView(TextView(this).apply {
                text = "Map key ${maskKey(map.accessKey)} · ${shortId(map.serverId)}"
                textSize = 10f
                setTextColor(FieldTheme.textSecondary)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(8))
            })
            val shareActions = horizontal()
            shareActions.addView(actionButton("Share invite", primary = true) { shareMap(map) }, weightParams())
            shareActions.addView(actionButton("Copy link", primary = false) { copyInvite(map) }, weightParams(left = 8))
            content.addView(shareActions)
        } else {
            content.addView(TextView(this).apply {
                text = "This local/legacy map does not have a share key on this phone yet. Refresh it from the owner server first."
                textSize = 12f
                setTextColor(FieldTheme.accent)
                setPadding(0, dp(8), 0, dp(10))
            })
        }

        content.addView(divider(), marginParams(top = 12, bottom = 8))
        content.addView(TextView(this).apply {
            text = "Map maintenance"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(FieldTheme.textPrimary)
        })
        content.addView(TextView(this).apply {
            text = "Setup changes shared geometry and Cloud Anchors. Rotate invite only when you intentionally want old participant links to stop working."
            textSize = 11f
            setTextColor(FieldTheme.textSecondary)
            setPadding(0, dp(4), 0, dp(8))
        })
        content.addView(actionButton("Update map setup", primary = false) { prepareAndLaunch(map, ArMode.MAP) })
        content.addView(actionButton("Revoke old invites & create new QR", primary = false) { confirmRotateMapKey(map) }, marginParams(top = 7))
        content.addView(actionButton("Delete place…", primary = false) { showDeleteMapDialog(map) }, marginParams(top = 7))

        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(map.name)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun shareMap(map: MapDefinition) {
        if (map.serverId.isBlank() || map.accessKey.isBlank()) {
            showStatus("This place has no share key yet. Refresh it from the owner server first.", true)
            return
        }
        val link = buildInviteLink(map)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Spatial AR place: ${map.name}")
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(share, "Share private place invite"))
    }

    private fun copyInvite(map: MapDefinition) {
        val link = buildInviteLink(map)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spatial AR invite", link))
        showStatus("${map.name} invite copied. It grants access to this place only.", false)
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
            revealOwnerSettings("Revoking an invite requires the sar_admin_… owner token for this server.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Revoke old participant access?")
            .setMessage("This immediately disconnects clients using the old ${map.name} key. A new QR will be generated and must be shared with participants again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Revoke & rotate") { _, _ -> rotateMapKey(map) }
            .show()
    }

    private fun rotateMapKey(map: MapDefinition) {
        showStatus("Rotating ${map.name} invite…", false)
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
                    showStatus("Old ${updated.name} invites revoked · new QR ready", false)
                    showManageMapDialog(updated)
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
                "Local deletion removes this phone's scan cache and pending uploads. " +
                    "Server + local requires the owner token and removes the shared copy for everyone."
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Local only") { _, _ -> deleteLocalMap(map) }
            .setPositiveButton("Server + local") { _, _ -> deleteMapEverywhere(map) }
            .show()
    }

    private fun deleteMapEverywhere(map: MapDefinition) {
        if (spatialApp.preferences.apiToken.isBlank()) {
            revealOwnerSettings("Server deletion requires the sar_admin_… owner token.")
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
        showStatus(if (serverDeleted) "Place deleted from server and this phone" else "Local copy deleted; server copy was preserved", false)
        renderMaps()
    }

    private fun prepareAndLaunch(map: MapDefinition, mode: ArMode) {
        val label = if (mode == ArMode.MAP) "map setup" else "Live AR"
        showStatus("${map.name}: checking server before $label…", false)
        executor.execute {
            runCatching {
                val credential = map.accessKey.ifBlank { spatialApp.preferences.apiToken }
                val api = MapApiClient(map.serverUrl, credential, spatialApp.logger)
                val info = api.getServerInfo()
                if (map.serverId.isNotBlank()) check(info.serverId == map.serverId) { "This URL now belongs to a different Spatial AR server" }
                val remote = api.getMap(map.id).copy(
                    serverUrl = map.serverUrl,
                    serverId = info.serverId,
                    accessKey = map.accessKey.ifBlank { map.accessKey },
                    syncPending = false
                )
                val merged = if (map.accessKey.isNotBlank()) remote.copy(accessKey = map.accessKey) else remote
                spatialApp.database.mergeServerMap(merged)
                if (merged.accessKey.isNotBlank()) spatialApp.database.upsertMap(merged)
                merged
            }.onSuccess {
                runOnUiThread {
                    showStatus("${map.name}: server synchronized · starting $label…", false)
                    launchMap(map.id, mode)
                }
            }.onFailure { error ->
                spatialApp.logger.warn("Pre-session map sync failed", mapOf("mapId" to map.id, "mode" to mode.name, "error" to error.message))
                runOnUiThread {
                    if (mode == ArMode.MAP) {
                        showStatus("Server unavailable · setup will continue local-first and retry uploads automatically.", true)
                        launchMap(map.id, mode)
                    } else {
                        showStatus("Live AR needs the shared server now: ${error.message}", true)
                    }
                }
            }
        }
    }

    private fun openOwnerDashboard() {
        if (!saveSettings(showMessage = false)) return
        val token = spatialApp.preferences.apiToken
        if (token.isBlank()) {
            revealOwnerSettings("The operator dashboard is owner-only. Enter this server's sar_admin_… token first.")
            return
        }
        val url = spatialApp.preferences.serverUrl.trimEnd('/') + "/#admin=" + Uri.encode(token)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showStatus("Could not open dashboard: ${it.message}", true) }
    }

    private fun revealOwnerSettings(message: String) {
        settingsVisible = true
        if (::settingsBody.isInitialized) settingsBody.visibility = View.VISIBLE
        showStatus(message, true)
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

    private fun maskKey(value: String): String = when {
        value.length < 18 -> "configured"
        else -> value.take(8) + "…" + value.takeLast(5)
    }

    private fun showStatus(message: String, error: Boolean) {
        if (!::status.isInitialized) return
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
