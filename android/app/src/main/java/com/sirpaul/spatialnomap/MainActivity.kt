package com.sirpaul.spatialnomap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import org.opencv.android.OpenCVLoader
import java.security.SecureRandom

class MainActivity : Activity(), WifiAwarePeerTransport.Callbacks, AlignmentCoordinator.Listener {
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: TargetOverlayView
    private lateinit var renderer: ArRenderer
    private lateinit var transport: WifiAwarePeerTransport
    private lateinit var coordinator: AlignmentCoordinator

    private lateinit var setupCard: LinearLayout
    private lateinit var usernameEdit: EditText
    private lateinit var roomList: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var transportPill: TextView
    private lateinit var syncPill: TextView
    private lateinit var detailPill: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSubtitle: TextView
    private lateinit var clearButton: TextView
    private lateinit var cameraButton: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    private var session: Session? = null
    private var installRequested = false
    private var surfaceResumed = false
    private var activityResumed = false
    private var peerConnectedOnce = false
    private var openCvReady = false
    private var arStarting = false
    private var arRetryCount = 0
    private var cameraChoices: List<ArCameraCatalog.Choice> = emptyList()
    private var activeCameraIndex = -1
    private var activeRoomCode: String? = null
    private var bannerSerial = 0L
    private var lastTransportError = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        openCvReady = OpenCVLoader.initLocal()
        transport = WifiAwarePeerTransport(this, this)
        coordinator = AlignmentCoordinator(transport, this)
        overlay = TargetOverlayView(this)
        renderer = ArRenderer(coordinator, overlay, ::username, ::setTechnicalStatus, ::displayRotation)

        surface = GLSurfaceView(this).apply {
            setPreserveEGLContextOnPause(true)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP && setupCard.visibility != View.VISIBLE) {
                    renderer.queueTap(event.x, event.y)
                    true
                } else {
                    false
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(surface, matchParent())
        root.addView(overlay, matchParent())
        root.addView(
            buildHud(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        setupCard = buildSetupCard()
        root.addView(
            setupCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(92)
            },
        )

        clearButton = buildClearButton()
        root.addView(
            clearButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(18)
                bottomMargin = dp(26)
            },
        )

        cameraButton = buildCameraButton()
        root.addView(
            cameraButton,
            FrameLayout.LayoutParams(dp(76), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(18)
                bottomMargin = dp(26)
            },
        )

        banner = buildBanner()
        root.addView(
            banner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = dp(94)
            },
        )

        setContentView(root)
        refreshCapabilities()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun buildHud(): LinearLayout {
        val out = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transportPill = pill("OFFLINE", 0xc91b2025.toInt()).apply {
            contentDescription = "Connection settings"
            setOnClickListener {
                haptic()
                if (setupCard.visibility == View.VISIBLE) hideSetup() else showSetup()
            }
        }
        syncPill = pill("AR STARTING", 0xc91b2025.toInt())
        row.addView(transportPill, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(7) })
        row.addView(syncPill, LinearLayout.LayoutParams(0, dp(38), 1f))

        detailPill = TextView(this).apply {
            setTextColor(0xffd8e0e5.toInt())
            textSize = 11.5f
            gravity = Gravity.CENTER
            maxLines = 1
            text = "Starting local AR…"
            background = rounded(0x8f101418.toInt(), 16f)
            setPadding(dp(11), dp(4), dp(11), dp(4))
        }
        out.addView(row)
        out.addView(
            detailPill,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(29)).apply { topMargin = dp(6) },
        )
        return out
    }

    private fun buildSetupCard(): LinearLayout {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = rounded(0xed11171c.toInt(), 24f, 0x554fffc3, 1)
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "SPATIAL SYNC"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(0xffaeb8bf.toInt())
            contentDescription = "Close setup"
            setOnClickListener { hideSetup() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(header)

        usernameEdit = EditText(this).apply {
            hint = "Username"
            setSingleLine(true)
            setText(prefs.getString("username", Build.MODEL.replace("SM-", "Galaxy ")))
            setTextColor(Color.WHITE)
            setHintTextColor(0xff6f7b84.toInt())
            textSize = 15f
            background = rounded(0xff20272d.toInt(), 15f, 0x334fffc3, 1)
            setPadding(dp(14), 0, dp(14), 0)
        }
        card.addView(usernameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(primaryButton("CREATE") {
            if (!ensurePeerPermissions()) return@primaryButton
            saveUsername()
            roomList.removeAllViews()
            val code = generateRoomCode()
            activeRoomCode = code
            setupStatus.text = "Creating $code…"
            runCatching { transport.createRoom(username(), code) }
                .onSuccess {
                    transportPill.text = "HOST • $code"
                    hideSetup()
                    haptic()
                    showBanner("Space $code created", "Camera stays live while we wait for the second phone")
                }
                .onFailure {
                    activeRoomCode = null
                    showBanner("Create space failed", errorText(it))
                }
        }, LinearLayout.LayoutParams(0, dp(49), 1f).apply { rightMargin = dp(6) })
        actions.addView(secondaryButton("JOIN NEARBY") {
            if (!ensurePeerPermissions()) return@secondaryButton
            saveUsername()
            roomList.removeAllViews()
            setupStatus.text = "Scanning nearby rooms…"
            runCatching { transport.scanRooms(username()) }
                .onFailure { showBanner("Nearby scan failed", errorText(it)) }
        }, LinearLayout.LayoutParams(0, dp(49), 1f).apply { leftMargin = dp(6) })
        card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(49)).apply { topMargin = dp(10) })

        setupStatus = TextView(this).apply {
            setTextColor(0xffaebbc4.toInt())
            textSize = 12.5f
            text = "Create on one phone, Join Nearby on the other."
            maxLines = 2
            setPadding(dp(2), dp(10), dp(2), dp(5))
        }
        card.addView(setupStatus)

        roomList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(roomList)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)),
        )
        return card
    }

    private fun buildBanner(): LinearLayout {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(12), dp(17), dp(12))
            background = rounded(0xee171e23.toInt(), 20f, 0x6655f0bd, 1)
            alpha = 0f
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }
        bannerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bannerSubtitle = TextView(this).apply {
            setTextColor(0xffb6c1c8.toInt())
            textSize = 12.5f
            maxLines = 3
        }
        view.addView(bannerTitle)
        view.addView(bannerSubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        return view
    }

    private fun buildClearButton() = TextView(this).apply {
        text = "CLEAR"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 12.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(18), 0, dp(18), 0)
        background = rounded(0xd5161c21.toInt(), 24f, 0x445f6a72, 1)
        visibility = View.GONE
        setOnClickListener {
            haptic()
            coordinator.clearPoi(true)
            renderer.setRemoteTarget(null)
            showBanner("POI cleared", "Removed from both devices")
        }
    }

    private fun buildCameraButton() = TextView(this).apply {
        text = "CAM"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = rounded(0xe0161c21.toInt(), 24f, 0x6655f0bd, 1)
        contentDescription = "Select AR camera"
        setOnClickListener {
            haptic()
            showCameraMenu(this)
        }
    }

    private fun showCameraMenu(anchor: View) {
        if (cameraChoices.isEmpty()) {
            showBanner("Camera selector", "ARCore did not expose another compatible tracking camera")
            return
        }
        if (cameraChoices.size == 1) {
            val only = cameraChoices.first()
            showBanner("${only.label} AR camera", "${only.detail}. Samsung may expose more lenses to the Camera app than ARCore exposes for tracking.")
            return
        }

        val menu = PopupMenu(this, anchor)
        cameraChoices.forEachIndexed { index, choice ->
            val selected = if (index == activeCameraIndex) "  ✓" else ""
            menu.menu.add(0, index, index, "${choice.label}   ${choice.imageWidth}×${choice.imageHeight}$selected")
        }
        menu.setOnMenuItemClickListener {
            switchArCamera(it.itemId)
            true
        }
        menu.show()
    }

    @Synchronized private fun switchArCamera(index: Int) {
        val s = session ?: return
        val choice = cameraChoices.getOrNull(index) ?: return
        if (index == activeCameraIndex || !activityResumed) return

        val oldConfig = runCatching { s.cameraConfig }.getOrNull()
        val oldIndex = activeCameraIndex
        var cameraConfigTouched = false

        renderer.detachSession()
        pauseSurfaceOnly()
        runCatching { s.pause() }

        try {
            s.cameraConfig = choice.config
            cameraConfigTouched = true
            val configured = configureBestAvailable(s)
            s.resume()
            renderer.session = s
            renderer.sessionResumed = true
            resumeSurfaceOnly()

            activeCameraIndex = index
            cameraButton.text = choice.label
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("camera_id", choice.cameraId).apply()

            // Camera images, intrinsics, metric depth supports and ARCore world
            // state all belong to the selected camera. Discard old frame pairs and
            // force both phones to solve a fresh SE(3) transform.
            coordinator.onCameraChanged("camera ${choice.cameraId}")
            haptic()
            showBanner("Camera ${choice.label}", "${choice.detail} • depth ${configured.depthMode} • re-aligning both phones")
        } catch (t: Throwable) {
            renderer.detachSession()
            val restored = runCatching {
                if (cameraConfigTouched && oldConfig != null) s.cameraConfig = oldConfig
                configureBestAvailable(s)
                s.resume()
                renderer.session = s
                renderer.sessionResumed = true
                resumeSurfaceOnly()
                activeCameraIndex = oldIndex
                cameraChoices.getOrNull(oldIndex)?.let { cameraButton.text = it.label }
            }.isSuccess

            coordinator.onCameraChanged("camera switch recovery")
            if (restored) {
                showBanner("Camera switch failed", "${errorText(t)} • restored previous AR camera")
            } else {
                showBanner("AR camera recovery", "${errorText(t)} • recreating AR session")
                disposeArSession()
                scheduleArRetry(immediate = true)
            }
        }
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(0xff061612.toInt())
        textSize = 13.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        background = rounded(0xff55f0bd.toInt(), 16f)
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        background = rounded(0xff242c32.toInt(), 16f, 0x445f6a72, 1)
        setOnClickListener { action() }
    }

    private fun pill(label: String, color: Int) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 11.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        maxLines = 1
        background = rounded(color, 18f)
    }

    private fun rounded(fill: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        startArIfPossible()
    }

    @Synchronized private fun startArIfPossible() {
        if (!activityResumed || arStarting || renderer.sessionResumed) return
        if (!ensureCameraPermission()) return
        arStarting = true

        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }

                val s = Session(this)
                session = s
                val catalog = ArCameraCatalog(this, s)
                cameraChoices = catalog.choices

                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val preferredId = prefs.getString("camera_id", null)
                val preferredIndex = catalog.indexForCameraId(preferredId)
                if (preferredIndex >= 0 && preferredId != catalog.defaultCameraId) {
                    runCatching { s.cameraConfig = cameraChoices[preferredIndex].config }
                }

                activeCameraIndex = catalog.indexForCameraId(s.cameraConfig.cameraId)
                if (activeCameraIndex < 0) activeCameraIndex = catalog.indexForCameraId(catalog.defaultCameraId)
                cameraChoices.getOrNull(activeCameraIndex)?.let { cameraButton.text = it.label }

                val configured = configureBestAvailable(s)
                setTechnicalStatus("AR configured • ${cameraButton.text} • depth ${configured.depthMode}")
            }

            val s = session ?: return
            s.resume()
            renderer.session = s
            renderer.sessionResumed = true
            resumeSurfaceOnly()
            arRetryCount = 0
            syncPill.text = "AR ACQUIRING"
            syncPill.background = rounded(0xc9443818.toInt(), 18f)
            setTechnicalStatus("Move the phone slightly to initialize tracking")
        } catch (t: Throwable) {
            renderer.detachSession()
            disposeArSession()
            syncPill.text = "AR ERROR"
            syncPill.background = rounded(0xd0581f27.toInt(), 18f)
            setTechnicalStatus("AR start failed: ${errorText(t)}")
            showBanner("ARCore failed", errorText(t))
            scheduleArRetry(immediate = false)
        } finally {
            arStarting = false
        }
    }

    /**
     * A camera config can change feature support. Always configure after choosing
     * the camera and gracefully fall back to AR without Depth if that exact camera
     * does not support automatic depth.
     */
    private fun configureBestAvailable(s: Session): Config {
        val wantsDepth = runCatching { s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
        val preferred = buildArConfig(s, enableDepth = wantsDepth)
        return try {
            s.configure(preferred)
            preferred
        } catch (depthFailure: Throwable) {
            if (!wantsDepth) throw depthFailure
            val fallback = buildArConfig(s, enableDepth = false)
            s.configure(fallback)
            fallback
        }
    }

    private fun buildArConfig(s: Session, enableDepth: Boolean) = Config(s).apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        focusMode = Config.FocusMode.AUTO
        depthMode = if (enableDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
    }

    private fun scheduleArRetry(immediate: Boolean) {
        if (!activityResumed || arRetryCount >= MAX_AR_RETRIES) return
        arRetryCount += 1
        mainHandler.removeCallbacks(arRetryRunnable)
        mainHandler.postDelayed(arRetryRunnable, if (immediate) 250L else 1200L * arRetryCount)
    }

    private val arRetryRunnable = Runnable {
        if (activityResumed && session == null) startArIfPossible()
    }

    private fun disposeArSession() {
        renderer.detachSession()
        pauseSurfaceOnly()
        val old = session
        session = null
        runCatching { old?.pause() }
        runCatching { old?.close() }
        cameraChoices = emptyList()
        activeCameraIndex = -1
        cameraButton.text = "CAM"
    }

    private fun pauseSurfaceOnly() {
        if (surfaceResumed) {
            surface.onPause()
            surfaceResumed = false
        }
    }

    private fun resumeSurfaceOnly() {
        if (!surfaceResumed && activityResumed) {
            surface.onResume()
            surfaceResumed = true
        }
    }

    override fun onPause() {
        activityResumed = false
        mainHandler.removeCallbacks(arRetryRunnable)
        renderer.sessionResumed = false
        pauseSurfaceOnly()
        runCatching { session?.pause() }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        coordinator.close()
        transport.close()
        renderer.detachSession()
        runCatching { session?.close() }
        session = null
        super.onDestroy()
    }

    private fun ensureCameraPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        return false
    }

    private fun ensurePeerPermissions(): Boolean {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_PEER)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            showBanner("Permission required", "Camera and Nearby devices are required for direct spatial sync")
            return
        }
        if (requestCode == REQ_CAMERA) startArIfPossible()
    }

    override fun onTransportStatus(text: String) {
        runOnUiThread {
            setupStatus.text = text
            if (!coordinator.quality().bothReady) detailPill.text = text
            val lower = text.lowercase()
            if (("failed" in lower || "unavailable" in lower || "lost" in lower) && text != lastTransportError) {
                lastTransportError = text
                showBanner("Direct link", text)
            }
        }
    }

    override fun onRoomFound(room: WifiAwarePeerTransport.NearbyRoom) {
        runOnUiThread {
            val tagValue = "room:${room.code}"
            roomList.findViewWithTag<View>(tagValue)?.let { roomList.removeView(it) }
            val distance = room.distanceM?.let { " • %.1f m".format(it) } ?: ""
            val button = secondaryButton("${room.username}   ${room.code}$distance") {
                runCatching { transport.joinRoom(room.code) }
                    .onSuccess {
                        activeRoomCode = room.code
                        transportPill.text = "LINKING • ${room.code}"
                        hideSetup()
                        haptic()
                    }
                    .onFailure { showBanner("Join failed", errorText(it)) }
            }.apply { tag = tagValue }
            roomList.addView(
                button,
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)).apply { bottomMargin = dp(6) },
            )
            setupStatus.text = "Nearby space found — tap it to connect."
        }
    }

    override fun onConnected(peerUsername: String) {
        runOnUiThread {
            transportPill.text = "DIRECT • $peerUsername"
            transportPill.background = rounded(0xd11a4b3c.toInt(), 18f, 0x6655f0bd, 1)
            clearButton.visibility = View.VISIBLE
            if (!peerConnectedOnce) {
                peerConnectedOnce = true
                coordinator.onConnected()
                hideSetup()
                haptic()
                showBanner("Connected to $peerUsername", "Direct Wi-Fi Aware link • no router • no server")
            }
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            if (!peerConnectedOnce) return@runOnUiThread
            peerConnectedOnce = false
            coordinator.onDisconnected()
            renderer.setRemoteTarget(null)
            clearButton.visibility = View.GONE
            transportPill.text = activeRoomCode?.let { "OFFLINE • $it" } ?: "OFFLINE"
            transportPill.background = rounded(0xc91b2025.toInt(), 18f)
            syncPill.text = if (renderer.sessionResumed) "AR READY" else "NOT SYNCED"
            syncPill.background = rounded(0xc91b2025.toInt(), 18f)
            showBanner("Peer disconnected", "$reason • tap the connection chip to reconnect")
        }
    }

    override fun onWireMessage(message: WireMessage) {
        when (message) {
            is WireMessage.Frame -> coordinator.onRemoteFrame(message.frame)
            is WireMessage.Poi -> coordinator.onRemotePoi(message)
            WireMessage.ClearPoi -> coordinator.clearPoi(false)
            is WireMessage.Quality -> coordinator.onPeerQuality(message)
            is WireMessage.Range -> coordinator.onRange(message.distanceM, message.stdDevM, message.samples)
            is WireMessage.ResetAlignment -> {
                coordinator.onPeerAlignmentReset(message.reason)
                runOnUiThread { showBanner("Peer AR changed", "Re-aligning geometry: ${message.reason}") }
            }
            is WireMessage.Hello -> Unit
        }
    }

    override fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        coordinator.onRange(distanceM, stdDevM, samples)
    }

    override fun onAlignmentQuality(quality: AlignmentCoordinator.Quality) {
        runOnUiThread {
            val range = quality.rangeM?.let { " • RTT %.2f m".format(it) } ?: ""
            when {
                quality.bothReady -> {
                    syncPill.text = "LOCKED • ${(quality.confidence * 100).toInt()}%"
                    syncPill.background = rounded(0xd11a4b3c.toInt(), 18f, 0x7755f0bd, 1)
                    detailPill.text = "READY — tap a physical point to sync POI$range"
                }
                quality.localReady -> {
                    syncPill.text = "LOCAL LOCK"
                    syncPill.background = rounded(0xcf584719.toInt(), 18f)
                    detailPill.text = "Hold the same textured area for the peer lock$range"
                }
                peerConnectedOnce -> {
                    syncPill.text = if (quality.inliers > 0) "ALIGN • ${quality.inliers}" else "ALIGNING"
                    syncPill.background = rounded(0xcf493b18.toInt(), 18f)
                    detailPill.text = "Point both phones at overlapping static detail and move slightly$range"
                }
                renderer.sessionResumed -> {
                    syncPill.text = "AR READY"
                    syncPill.background = rounded(0xc928343b.toInt(), 18f)
                }
            }
        }
    }

    override fun onRemotePoi(pointLocal: FloatArray?, owner: String, confidence: Float) {
        runOnUiThread {
            renderer.setRemoteTarget(pointLocal, owner, confidence)
            if (pointLocal != null) {
                haptic()
                showBanner("POI added from $owner", "Follow the edge arrow until the marker enters view")
            }
        }
    }

    override fun onPoiCleared() {
        runOnUiThread { renderer.setRemoteTarget(null) }
    }

    private fun refreshCapabilities() {
        val caps = transport.capabilities()
        val awareText = when {
            !caps.awareSupported -> "Aware ✕"
            caps.awareAvailable -> "Aware ✓"
            else -> "Aware unavailable"
        }
        val rttText = when {
            !caps.rttSupported -> "RTT —"
            caps.rttAvailable -> "RTT ✓"
            else -> "RTT idle"
        }
        setupStatus.text = "$awareText  •  $rttText  •  OpenCV ${if (openCvReady) "✓" else "✕"}"
        if (!openCvReady) showBanner("Alignment unavailable", "OpenCV failed to initialize on this build")
    }

    private fun setTechnicalStatus(text: String) {
        runOnUiThread {
            if (!coordinator.quality().bothReady) detailPill.text = text
            when {
                text == "AR tracking" && !peerConnectedOnce -> {
                    syncPill.text = "AR READY"
                    syncPill.background = rounded(0xc928343b.toInt(), 18f)
                }
                text.startsWith("AR PAUSED") -> {
                    syncPill.text = "AR ACQUIRING"
                }
            }
        }
    }

    private fun hideSetup() {
        if (setupCard.visibility != View.VISIBLE) return
        setupCard.animate()
            .alpha(0f)
            .translationY(dp(18).toFloat())
            .setDuration(160)
            .withEndAction { setupCard.visibility = View.GONE }
            .start()
    }

    private fun showSetup() {
        setupCard.visibility = View.VISIBLE
        setupCard.alpha = 0f
        setupCard.translationY = dp(18).toFloat()
        setupCard.animate().alpha(1f).translationY(0f).setDuration(160).start()
    }

    private fun showBanner(title: String, subtitle: String) {
        val serial = ++bannerSerial
        banner.animate().cancel()
        bannerTitle.text = title
        bannerSubtitle.text = subtitle
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dp(12).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(160).start()
        banner.postDelayed({
            if (!isFinishing && serial == bannerSerial) {
                banner.animate()
                    .alpha(0f)
                    .translationY(-dp(8).toFloat())
                    .setDuration(180)
                    .withEndAction { if (serial == bannerSerial) banner.visibility = View.GONE }
                    .start()
            }
        }, 3000L)
    }

    private fun haptic() {
        runCatching { surface.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }

    private fun saveUsername() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("username", username()).apply()
    }

    private fun username() = usernameEdit.text?.toString()?.trim().orEmpty().ifBlank { Build.MODEL }.take(32)

    private fun generateRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun errorText(t: Throwable): String =
        "${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val PREFS = "spatial-v2"
        private const val REQ_CAMERA = 41
        private const val REQ_PEER = 42
        private const val MAX_AR_RETRIES = 2
    }
}
