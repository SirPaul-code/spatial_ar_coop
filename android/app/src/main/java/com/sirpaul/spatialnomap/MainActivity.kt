package com.sirpaul.spatialnomap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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

    private var session: Session? = null
    private var installRequested = false
    private var surfaceResumed = false
    private var peerConnectedOnce = false
    private var openCvReady = false
    private var arStarting = false
    private var cameraChoices: List<ArCameraCatalog.Choice> = emptyList()
    private var activeCameraIndex = 0
    private val random = SecureRandom()

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
                } else false
            }
        }

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildHud(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        setupCard = buildSetupCard()
        root.addView(setupCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(22)
            rightMargin = dp(22)
        })

        clearButton = buildClearButton()
        root.addView(clearButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(30)
        })

        cameraButton = buildCameraButton()
        root.addView(cameraButton, FrameLayout.LayoutParams(dp(72), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(18)
            bottomMargin = dp(30)
        })

        banner = buildBanner()
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            topMargin = dp(88)
        })

        setContentView(root)
        refreshCapabilities()
    }

    private fun buildHud(): LinearLayout {
        val out = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transportPill = pill("OFFLINE", 0xcc1d2228.toInt()).apply {
            setOnClickListener { if (setupCard.visibility == View.VISIBLE) hideSetup() else showSetup() }
        }
        syncPill = pill("NOT SYNCED", 0xcc1d2228.toInt())
        row.addView(transportPill, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        row.addView(syncPill, LinearLayout.LayoutParams(0, dp(42), 1f))
        detailPill = TextView(this).apply {
            setTextColor(0xffd6dde4.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            text = "Direct peer AR • no AP • no cloud"
            background = rounded(0x99111418.toInt(), 18f)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        out.addView(row)
        out.addView(detailPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(7) })
        return out
    }

    private fun buildSetupCard(): LinearLayout {
        val prefs = getSharedPreferences("spatial-v2", MODE_PRIVATE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            background = rounded(0xf0161b20.toInt(), 28f, 0x334fffc3, 1)
        }
        card.addView(TextView(this).apply {
            text = "SPATIAL SYNC"
            setTextColor(Color.WHITE)
            textSize = 27f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Two phones. One shared point. No router."
            setTextColor(0xff9eabb5.toInt())
            textSize = 14f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
            bottomMargin = dp(18)
        })

        usernameEdit = EditText(this).apply {
            hint = "Username"
            setSingleLine(true)
            setText(prefs.getString("username", Build.MODEL.replace("SM-", "Galaxy ")))
            setTextColor(Color.WHITE)
            setHintTextColor(0xff6f7b84.toInt())
            textSize = 16f
            background = rounded(0xff22292f.toInt(), 18f, 0x334fffc3, 1)
            setPadding(dp(16), 0, dp(16), 0)
        }
        card.addView(usernameEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        card.addView(primaryButton("CREATE SPACE") {
            if (!ensurePeerPermissions()) return@primaryButton
            saveUsername()
            roomList.removeAllViews()
            val code = generateRoomCode()
            setupStatus.text = "Creating room $code…"
            runCatching { transport.createRoom(username(), code) }
                .onSuccess { hideSetup(); showBanner("Space $code created", "Waiting for a nearby phone") }
                .onFailure { showBanner("Create space failed", "${it.javaClass.simpleName}: ${it.message}") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(14) })

        card.addView(secondaryButton("JOIN NEARBY") {
            if (!ensurePeerPermissions()) return@secondaryButton
            saveUsername()
            roomList.removeAllViews()
            setupStatus.text = "Scanning nearby rooms…"
            runCatching { transport.scanRooms(username()) }
                .onFailure { showBanner("Nearby scan failed", "${it.javaClass.simpleName}: ${it.message}") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })

        setupStatus = TextView(this).apply {
            setTextColor(0xffb9c5cd.toInt())
            textSize = 13f
            text = "Choose CREATE on one phone and JOIN NEARBY on the other."
            setPadding(dp(2), dp(14), dp(2), dp(8))
        }
        card.addView(setupStatus)
        roomList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(ScrollView(this).apply { addView(roomList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(144)))
        return card
    }

    private fun buildBanner(): LinearLayout {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0xee1a2126.toInt(), 24f, 0x664fffc3, 1)
            alpha = 0f
            visibility = View.GONE
        }
        bannerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bannerSubtitle = TextView(this).apply {
            setTextColor(0xffaebbc4.toInt())
            textSize = 13f
        }
        view.addView(bannerTitle)
        view.addView(bannerSubtitle)
        return view
    }

    private fun buildClearButton() = TextView(this).apply {
        text = "CLEAR POI"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(22), 0, dp(22), 0)
        background = rounded(0xd91b2228.toInt(), 24f, 0x445f6a72, 1)
        visibility = View.GONE
        setOnClickListener {
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
        background = rounded(0xd91b2228.toInt(), 24f, 0x445f6a72, 1)
        setOnClickListener { showCameraMenu(this) }
    }

    private fun showCameraMenu(anchor: View) {
        if (cameraChoices.isEmpty()) {
            showBanner("Camera selector", "ARCore exposes only the current compatible camera on this device")
            return
        }
        val menu = PopupMenu(this, anchor)
        cameraChoices.forEachIndexed { index, choice ->
            val suffix = if (index == activeCameraIndex) " ✓" else ""
            menu.menu.add(0, index, index, "${choice.label}  [AR camera ${choice.cameraId}]$suffix")
        }
        menu.setOnMenuItemClickListener {
            switchArCamera(it.itemId)
            true
        }
        menu.show()
    }

    private fun switchArCamera(index: Int) {
        val s = session ?: return
        val choice = cameraChoices.getOrNull(index) ?: return
        if (index == activeCameraIndex) return
        try {
            renderer.sessionResumed = false
            s.pause()
            s.cameraConfig = choice.config
            val config = buildArConfig(s)
            s.configure(config)
            s.resume()
            renderer.session = s
            renderer.sessionResumed = true
            activeCameraIndex = index
            cameraButton.text = choice.label
            coordinator.onCameraChanged()
            renderer.setRemoteTarget(null)
            showBanner("Camera ${choice.label}", "AR tracking restarted for this lens")
        } catch (t: Throwable) {
            renderer.sessionResumed = false
            showBanner("Camera switch failed", "${t.javaClass.simpleName}: ${t.message}")
            runCatching { s.resume(); renderer.sessionResumed = true }
        }
    }

    private fun primaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(0xff071713.toInt())
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        stateListAnimator = null
        background = rounded(0xff4fffc3.toInt(), 18f)
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        stateListAnimator = null
        background = rounded(0xff252d33.toInt(), 18f, 0x445f6a72, 1)
        setOnClickListener { action() }
    }

    private fun pill(label: String, color: Int) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = rounded(color, 20f)
    }

    private fun rounded(fill: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
    }

    override fun onResume() {
        super.onResume()
        startArIfPossible()
    }

    @Synchronized private fun startArIfPossible() {
        if (arStarting || renderer.sessionResumed) return
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
                cameraChoices = runCatching { ArCameraCatalog(this, s).choices }.getOrDefault(emptyList())
                val preferredId = getSharedPreferences("spatial-v2", MODE_PRIVATE).getString("camera_id", null)
                val preferredIndex = cameraChoices.indexOfFirst { it.cameraId == preferredId }.takeIf { it >= 0 } ?: 0
                cameraChoices.getOrNull(preferredIndex)?.let {
                    runCatching { s.cameraConfig = it.config }
                    activeCameraIndex = preferredIndex
                    cameraButton.text = it.label
                }

                val config = buildArConfig(s)
                s.configure(config)
                session = s
                setTechnicalStatus("AR configured • depth=${config.depthMode}")
            }

            val s = session ?: return
            s.resume()
            renderer.session = s
            renderer.sessionResumed = true
            if (!surfaceResumed) {
                surface.onResume()
                surfaceResumed = true
            }
            setTechnicalStatus("AR tracking starting…")
        } catch (t: Throwable) {
            renderer.sessionResumed = false
            renderer.session = null
            setTechnicalStatus("AR start failed: ${t.javaClass.simpleName}: ${t.message}")
            showBanner("ARCore failed", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            arStarting = false
        }
    }

    private fun buildArConfig(s: Session) = Config(s).apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        focusMode = Config.FocusMode.AUTO
        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
    }

    override fun onPause() {
        renderer.sessionResumed = false
        if (surfaceResumed) {
            surface.onPause()
            surfaceResumed = false
        }
        runCatching { session?.pause() }
        super.onPause()
    }

    override fun onDestroy() {
        coordinator.close()
        transport.close()
        renderer.detachSession()
        session?.close()
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.NEARBY_WIFI_DEVICES
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_PEER)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
            detailPill.text = text
        }
    }

    override fun onRoomFound(room: WifiAwarePeerTransport.NearbyRoom) {
        runOnUiThread {
            val tagValue = "room:${room.code}"
            roomList.findViewWithTag<View>(tagValue)?.let { roomList.removeView(it) }
            val distance = room.distanceM?.let { " • %.1f m".format(it) } ?: ""
            val button = secondaryButton("${room.username}   ${room.code}$distance") {
                runCatching { transport.joinRoom(room.code) }
                    .onSuccess { hideSetup() }
                    .onFailure { showBanner("Join failed", "${it.javaClass.simpleName}: ${it.message}") }
            }.apply { tag = tagValue }
            roomList.addView(button, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(7) })
            setupStatus.text = "Nearby room found. Tap to join."
        }
    }

    override fun onConnected(peerUsername: String) {
        runOnUiThread {
            transportPill.text = "DIRECT • $peerUsername"
            transportPill.background = rounded(0xdd163b31.toInt(), 20f, 0x664fffc3, 1)
            clearButton.visibility = View.VISIBLE
            if (!peerConnectedOnce) {
                peerConnectedOnce = true
                coordinator.onConnected()
                hideSetup()
                showBanner("Connected to $peerUsername", "Direct Wi‑Fi Aware link • no access point")
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
            transportPill.text = "OFFLINE"
            transportPill.background = rounded(0xcc1d2228.toInt(), 20f)
            syncPill.text = "NOT SYNCED"
            syncPill.background = rounded(0xcc1d2228.toInt(), 20f)
            showBanner("Peer disconnected", reason)
        }
    }

    override fun onWireMessage(message: WireMessage) {
        when (message) {
            is WireMessage.Frame -> coordinator.onRemoteFrame(message.frame)
            is WireMessage.Poi -> coordinator.onRemotePoi(message)
            WireMessage.ClearPoi -> coordinator.clearPoi(false)
            is WireMessage.Quality -> coordinator.onPeerQuality(message)
            is WireMessage.Range -> coordinator.onRange(message.distanceM, message.stdDevM, message.samples)
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
                    syncPill.background = rounded(0xdd164739.toInt(), 20f, 0x884fffc3.toInt(), 1)
                    detailPill.text = "READY — tap a physical point to sync POI$range"
                }
                quality.localReady -> {
                    syncPill.text = "LOCKED LOCAL • WAIT PEER"
                    syncPill.background = rounded(0xdd5a4719.toInt(), 20f)
                    detailPill.text = "Keep both phones on the same textured area for one more moment$range"
                }
                else -> {
                    syncPill.text = if (quality.inliers > 0) "ALIGNING • ${quality.inliers} INLIERS" else "ALIGNING"
                    syncPill.background = rounded(0xdd413718.toInt(), 20f)
                    detailPill.text = "Look at the same static scene and move both phones slightly$range"
                }
            }
        }
    }

    override fun onRemotePoi(pointLocal: FloatArray?, owner: String, confidence: Float) {
        runOnUiThread {
            renderer.setRemoteTarget(pointLocal, owner, confidence)
            if (pointLocal != null) showBanner("POI added from $owner", "Follow the edge arrow until the marker enters view")
        }
    }

    override fun onPoiCleared() {
        runOnUiThread { renderer.setRemoteTarget(null) }
    }

    private fun refreshCapabilities() {
        val caps = transport.capabilities()
        val awareText = if (caps.awareSupported) "Aware ✓" else "Aware ✕"
        val rttText = if (caps.rttSupported) "RTT ✓" else "RTT —"
        setupStatus.text = "$awareText   •   $rttText   •   OpenCV ${if (openCvReady) "✓" else "✕"}"
        if (!openCvReady) showBanner("Alignment unavailable", "OpenCV failed to initialize on this build")
    }

    private fun setTechnicalStatus(text: String) {
        runOnUiThread { if (!coordinator.quality().bothReady) detailPill.text = text }
    }

    private fun hideSetup() {
        if (setupCard.visibility != View.VISIBLE) return
        setupCard.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(180).withEndAction {
            setupCard.visibility = View.GONE
        }.start()
    }

    private fun showSetup() {
        setupCard.visibility = View.VISIBLE
        setupCard.alpha = 0f
        setupCard.scaleX = 0.97f
        setupCard.scaleY = 0.97f
        setupCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun showBanner(title: String, subtitle: String) {
        banner.animate().cancel()
        bannerTitle.text = title
        bannerSubtitle.text = subtitle
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -dp(14).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(180).start()
        banner.postDelayed({
            if (!isFinishing) banner.animate().alpha(0f).translationY(-dp(10).toFloat()).setDuration(220).withEndAction {
                banner.visibility = View.GONE
            }.start()
        }, 3200L)
    }

    private fun saveUsername() {
        getSharedPreferences("spatial-v2", MODE_PRIVATE).edit().putString("username", username()).apply()
    }

    private fun username() = usernameEdit.text?.toString()?.trim().orEmpty().ifBlank { Build.MODEL }.take(32)

    private fun generateRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION") private fun displayRotation(): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val REQ_CAMERA = 41
        private const val REQ_PEER = 42
    }
}
