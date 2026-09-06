package com.sirpaul.spatialnomap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
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
    private enum class PeerAction { CREATE, SCAN, JOIN }

    private lateinit var root: FrameLayout
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: TargetOverlayView
    private lateinit var renderer: ArRenderer
    private lateinit var transport: WifiAwarePeerTransport
    private lateinit var coordinator: AlignmentCoordinator

    private lateinit var hudView: LinearLayout
    private lateinit var setupShell: ScrollView
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
    private var arTrackingStable = false
    private var cameraChoices: List<ArCameraCatalog.Choice> = emptyList()
    private var activeCameraIndex = -1
    private var activeRoomCode: String? = null
    private var bannerSerial = 0L
    private var lastTransportError = ""
    private var lastArError = ""
    private var permissionRequestInFlight = false
    private var initialPermissionPromptAttempted = false
    private var pendingPeerAction: PeerAction? = null
    private var pendingJoinCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

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
                if (event.action == MotionEvent.ACTION_UP && setupShell.visibility != View.VISIBLE) {
                    renderer.queueTap(event.x, event.y)
                    true
                } else {
                    false
                }
            }
        }

        root = FrameLayout(this)
        root.addView(surface, matchParent())
        root.addView(overlay, matchParent())

        hudView = buildHud()
        root.addView(
            hudView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        setupCard = buildSetupCard()
        setupShell = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(
                setupCard,
                ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        root.addView(
            setupShell,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(82)
            },
        )

        clearButton = buildClearButton()
        root.addView(
            clearButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(18)
                bottomMargin = dp(14)
            },
        )

        cameraButton = buildCameraButton()
        root.addView(
            cameraButton,
            FrameLayout.LayoutParams(dp(76), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(18)
                bottomMargin = dp(14)
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

        installSafeAreaHandling()
        setContentView(root)
        root.requestApplyInsets()
        refreshCapabilities()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun installSafeAreaHandling() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())

            (hudView.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.topMargin = safe.top
                it.leftMargin = safe.left
                it.rightMargin = safe.right
                hudView.layoutParams = it
            }
            (setupShell.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = safe.left + dp(14)
                it.rightMargin = safe.right + dp(14)
                it.bottomMargin = safe.bottom + dp(76)
                setupShell.layoutParams = it
            }
            (clearButton.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = safe.left + dp(18)
                it.bottomMargin = safe.bottom + dp(12)
                clearButton.layoutParams = it
            }
            (cameraButton.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.rightMargin = safe.right + dp(18)
                it.bottomMargin = safe.bottom + dp(12)
                cameraButton.layoutParams = it
            }
            (banner.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = safe.left + dp(18)
                it.rightMargin = safe.right + dp(18)
                it.topMargin = safe.top + dp(88)
                banner.layoutParams = it
            }

            // Cap the connection sheet so it always fits above HUD + bottom
            // controls on short displays. Its contents remain scrollable.
            root.post {
                if (!isFinishing && root.height > 0) {
                    val maxHeight = (root.height - safe.top - safe.bottom - dp(190)).coerceAtLeast(dp(220))
                    val lp = setupShell.layoutParams as FrameLayout.LayoutParams
                    lp.height = maxHeight.coerceAtMost(dp(340))
                    setupShell.layoutParams = lp
                }
            }
            insets
        }
    }

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
                if (setupShell.visibility == View.VISIBLE) hideSetup() else showSetup()
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
            setPadding(dp(18), dp(14), dp(18), dp(14))
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
            textSize = 19f
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
        card.addView(usernameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(primaryButton("CREATE") {
            beginPeerAction(PeerAction.CREATE)
        }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { rightMargin = dp(6) })
        actions.addView(secondaryButton("JOIN NEARBY") {
            beginPeerAction(PeerAction.SCAN)
        }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { leftMargin = dp(6) })
        card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(47)).apply { topMargin = dp(9) })

        setupStatus = TextView(this).apply {
            setTextColor(0xffaebbc4.toInt())
            textSize = 12f
            text = "Create on one phone, Join Nearby on the other."
            maxLines = 5
            setPadding(dp(2), dp(8), dp(2), dp(5))
            setOnClickListener {
                val missing = peerPermissionConstants()
                if (missing.isNotEmpty() && !permissionRequestInFlight) {
                    requestRuntimePermissions(missing)
                } else if (!transport.capabilities().locationEnabled) {
                    openLocationSettings()
                }
            }
        }
        card.addView(setupStatus)

        roomList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(
            ScrollView(this).apply {
                isFillViewport = false
                addView(roomList)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)),
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
            maxLines = 6
            setTextIsSelectable(true)
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
            showBanner("Camera selector", "ARCore has not exposed a compatible tracking camera yet")
            return
        }
        if (cameraChoices.size == 1) {
            val only = cameraChoices.first()
            showBanner(
                "${only.label} AR camera",
                "${only.detail}. Samsung may expose more lenses to the Camera app than ARCore exposes for tracking.",
            )
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
        arTrackingStable = false
        renderArReadiness()

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
            coordinator.onCameraChanged("camera ${choice.cameraId}")
            haptic()
            showBanner(
                "Camera ${choice.label}",
                "${choice.detail} • depth ${configured.depthMode} • geometry reset; re-aligning both phones",
            )
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
                showErrorBanner("Camera switch failed — previous camera restored", t)
            } else {
                showErrorBanner("AR camera recovery — recreating session", t)
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

        if (!initialPermissionPromptAttempted) {
            initialPermissionPromptAttempted = true
            if (!requestInitialPermissionsIfNeeded()) startArIfPossible()
        } else {
            startArIfPossible()
        }

        refreshCapabilities()
        resumePendingPeerActionIfReady()
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
            arTrackingStable = false
            renderArReadiness()
            setTechnicalStatus("Move the phone slightly to initialize tracking")
        } catch (t: Throwable) {
            renderer.detachSession()
            disposeArSession()
            arTrackingStable = false
            syncPill.text = "AR ERROR"
            syncPill.background = rounded(0xd0581f27.toInt(), 18f)
            setTechnicalStatus("AR start failed: ${errorText(t)}")
            showErrorBanner("ARCore failed", t)
            scheduleArRetry(immediate = false)
        } finally {
            arStarting = false
        }
    }

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
        arTrackingStable = false
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
        arTrackingStable = false
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

    private fun requestInitialPermissionsIfNeeded(): Boolean {
        val missing = allRuntimePermissionConstants()
        if (missing.isEmpty()) return false
        requestRuntimePermissions(missing)
        return true
    }

    private fun ensureCameraPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        if (!permissionRequestInFlight) requestRuntimePermissions(listOf(Manifest.permission.CAMERA))
        return false
    }

    private fun ensurePeerPermissions(): Boolean {
        val needed = peerPermissionConstants()
        if (needed.isEmpty()) return true
        if (!permissionRequestInFlight) requestRuntimePermissions(needed)
        return false
    }

    private fun allRuntimePermissionConstants(): List<String> {
        val needed = ArrayList<String>(4)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        appendPeerPermissions(needed)
        return needed.distinct()
    }

    private fun peerPermissionConstants(): List<String> {
        val needed = ArrayList<String>(4)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        appendPeerPermissions(needed)
        return needed.distinct()
    }

    private fun appendPeerPermissions(out: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            out += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val coarseMissing =
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        val fineMissing =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

        // Android 12+ requires FINE and COARSE to be requested together. Even if
        // coarse was already granted, include both when upgrading to Precise.
        if (coarseMissing || fineMissing) {
            out += Manifest.permission.ACCESS_COARSE_LOCATION
            out += Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun requestRuntimePermissions(permissions: List<String>) {
        if (permissions.isEmpty() || permissionRequestInFlight) return
        permissionRequestInFlight = true
        requestPermissions(permissions.distinct().toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        permissionRequestInFlight = false

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startArIfPossible()
        }
        refreshCapabilities()

        val missing = peerPermissionLabels()
        if (missing.isNotEmpty()) {
            val preciseMissing =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            val suffix = if (preciseMissing) {
                " Select Precise location; this Galaxy Wi-Fi Aware/RTT stack rejects discovery without it."
            } else {
                ""
            }
            val text = "Still missing: ${missing.joinToString()}.${suffix} Tap this setup status to retry."
            setupStatus.text = text
            showBanner("Permission required", text, 8500L)
            return
        }

        resumePendingPeerActionIfReady()
    }

    private fun peerPermissionLabels(): List<String> {
        val missing = ArrayList<String>(4)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing += "Camera"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) missing += "Nearby devices"
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "Location"
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "Precise location"
        }
        return missing
    }

    private fun beginPeerAction(action: PeerAction, joinCode: String? = null) {
        pendingPeerAction = action
        pendingJoinCode = joinCode
        if (!ensurePeerPermissions()) return

        if (!transport.capabilities().locationEnabled) {
            setupStatus.text = "Location services are OFF. Tap here or enable Location, then return to Spatial Sync."
            showBanner(
                "Location required",
                "Samsung Wi-Fi Aware/RTT is unavailable while Location services are off. Opening Location settings…",
                7000L,
            )
            openLocationSettings()
            return
        }

        executePeerAction(action, joinCode)
        pendingPeerAction = null
        pendingJoinCode = null
    }

    private fun resumePendingPeerActionIfReady() {
        val action = pendingPeerAction ?: return
        if (permissionRequestInFlight || !ensurePeerPermissions()) return
        if (!transport.capabilities().locationEnabled) return
        executePeerAction(action, pendingJoinCode)
        pendingPeerAction = null
        pendingJoinCode = null
    }

    private fun executePeerAction(action: PeerAction, joinCode: String?) {
        saveUsername()
        when (action) {
            PeerAction.CREATE -> {
                roomList.removeAllViews()
                val code = generateRoomCode()
                activeRoomCode = code
                setupStatus.text = "Creating $code…"
                runCatching { transport.createRoom(username(), code) }
                    .onSuccess {
                        transportPill.text = "HOST • $code"
                        hideSetup()
                        haptic()
                        showBanner("Space $code starting", "Waiting for Wi-Fi Aware publish confirmation…")
                    }
                    .onFailure {
                        activeRoomCode = null
                        setupStatus.text = errorText(it)
                        showErrorBanner("Create space failed", it)
                    }
            }
            PeerAction.SCAN -> {
                roomList.removeAllViews()
                setupStatus.text = "Scanning nearby rooms…"
                runCatching { transport.scanRooms(username()) }
                    .onFailure {
                        setupStatus.text = errorText(it)
                        showErrorBanner("Nearby scan failed", it)
                    }
            }
            PeerAction.JOIN -> {
                val code = joinCode ?: return
                runCatching { transport.joinRoom(code) }
                    .onSuccess {
                        activeRoomCode = code
                        transportPill.text = "LINKING • $code"
                        hideSetup()
                        haptic()
                    }
                    .onFailure {
                        setupStatus.text = errorText(it)
                        showErrorBanner("Join failed", it)
                    }
            }
        }
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .onFailure { showErrorBanner("Could not open Location settings", it) }
    }

    override fun onTransportStatus(text: String) {
        runOnUiThread {
            setupStatus.text = text
            if (!coordinator.quality().bothReady) detailPill.text = text
            val lower = text.lowercase()
            val isError = listOf("failed", "unavailable", "lost", "exception", "permission", "denied").any { it in lower }
            if (isError && text != lastTransportError) {
                lastTransportError = text
                showBanner("Direct link", text, 8500L)
            }
            if ("room " in lower && "ready" in lower) {
                showBanner("Room ready", text, 3500L)
            }
        }
    }

    override fun onRoomFound(room: WifiAwarePeerTransport.NearbyRoom) {
        runOnUiThread {
            val tagValue = "room:${room.code}"
            roomList.findViewWithTag<View>(tagValue)?.let { roomList.removeView(it) }
            val distance = room.distanceM?.let { " • %.1f m".format(it) } ?: ""
            val button = secondaryButton("${room.username}   ${room.code}$distance") {
                beginPeerAction(PeerAction.JOIN, room.code)
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
            renderArReadiness()
            showBanner("Peer disconnected", "$reason • tap the connection chip to reconnect", 6000L)
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
                else -> renderArReadiness()
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
        val missing = peerPermissionLabels()
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
        val permText = if (missing.isEmpty()) "Perm ✓" else "Perm ! ${missing.joinToString()}"
        val locationText = if (caps.locationEnabled) "Location ✓" else "Location OFF"
        setupStatus.text = "$awareText • $rttText • $permText • $locationText • OpenCV ${if (openCvReady) "✓" else "✕"}"
        if (!openCvReady) showBanner("Alignment unavailable", "OpenCV failed to initialize on this build", 8000L)
    }

    private fun setTechnicalStatus(text: String) {
        runOnUiThread {
            if (!coordinator.quality().bothReady) detailPill.text = text
            when {
                text == "AR tracking" -> {
                    if (!arTrackingStable) {
                        arTrackingStable = true
                        renderArReadiness()
                    }
                }
                text.startsWith("AR PAUSED") -> {
                    if (arTrackingStable) {
                        arTrackingStable = false
                        renderArReadiness()
                    }
                }
                text.startsWith("AR frame error") && text != lastArError -> {
                    lastArError = text
                    showBanner("AR frame error", text.removePrefix("AR frame error: "), 8500L)
                }
            }
        }
    }

    private fun renderArReadiness() {
        if (peerConnectedOnce || coordinator.quality().localReady) return
        when {
            !renderer.sessionResumed -> {
                syncPill.text = "AR STARTING"
                syncPill.background = rounded(0xc91b2025.toInt(), 18f)
            }
            arTrackingStable -> {
                syncPill.text = "AR READY"
                syncPill.background = rounded(0xc928343b.toInt(), 18f)
            }
            else -> {
                syncPill.text = "AR ACQUIRING"
                syncPill.background = rounded(0xc9443818.toInt(), 18f)
            }
        }
    }

    private fun hideSetup() {
        if (setupShell.visibility != View.VISIBLE) return
        setupShell.animate()
            .alpha(0f)
            .translationY(dp(18).toFloat())
            .setDuration(160)
            .withEndAction { setupShell.visibility = View.GONE }
            .start()
    }

    private fun showSetup() {
        setupShell.visibility = View.VISIBLE
        setupShell.alpha = 0f
        setupShell.translationY = dp(18).toFloat()
        setupShell.animate().alpha(1f).translationY(0f).setDuration(160).start()
        refreshCapabilities()
    }

    private fun showBanner(title: String, subtitle: String, durationMs: Long = 4000L) {
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
        }, durationMs)
    }

    private fun showErrorBanner(title: String, t: Throwable) {
        val text = errorText(t)
        setupStatus.text = text
        showBanner(title, text, 9500L)
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

    private fun errorText(t: Throwable): String {
        val parts = ArrayList<String>(3)
        var current: Throwable? = t
        repeat(3) {
            val c = current ?: return@repeat
            val item = buildString {
                append(c.javaClass.simpleName)
                c.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
            if (item !in parts) parts += item
            current = c.cause
        }
        return parts.joinToString(" <- ").ifBlank { t.javaClass.name }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val PREFS = "spatial-v2"
        private const val REQ_PERMISSIONS = 42
        private const val MAX_AR_RETRIES = 2
    }
}
