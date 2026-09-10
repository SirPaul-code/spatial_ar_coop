package com.sirpaul.showme

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import com.google.ar.core.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.opencv.android.OpenCVLoader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.concurrent.Executors

class ShowMeActivity : Activity() {
    private val state = ShowMeSession()
    private lateinit var renderer: ShowMeRenderer
    private lateinit var overlay: ShowMeOverlay
    private lateinit var gl: GLSurfaceView
    private lateinit var voice: RtcVoice
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var presence: TextView
    private lateinit var detail: TextView
    private lateinit var toast: TextView
    private lateinit var pauseButton: TextView
    private lateinit var voiceButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var ar: Session? = null
    private var server: LocalServer? = null
    private var tls: LocalTls? = null
    private var importedTls: LocalTls? = null
    private var invite = ""
    private var startedAt = 0L
    private var installRequested = false
    private var starting = false
    private var pendingSecure: Boolean? = null
    private var resumeSharing = false
    private var destroyed = false
    private val mint = 0xff8ff1c6.toInt()
    private val dark = 0xff0b1220.toInt()
    private val panel = 0xe9152031.toInt()
    private val muted = 0xffadbbce.toInt()
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            statusText.text = when { !state.active -> "CAMERA READY"; state.paused -> "VIDEO PAUSED"; !state.tracking -> "SCANNING"; else -> "LIVE / AR" }
            presence.text = if (state.hasHelper()) "${state.helperName} is connected" else if (state.active) "Waiting for your helper" else "Show someone exactly where"
            val seconds = if (startedAt == 0L) 0 else (monotonicMs() - startedAt) / 1000
            detail.text = if (state.active) "%02d:%02d  /  %d annotations  /  %s".format(seconds / 60, seconds % 60, state.annotationCount,
                if (state.secure) "LOCAL HTTPS" else "LOCAL WI-FI") else "${state.annotationCount} annotations / ${BuildConfig.VERSION_NAME}"
            pauseButton.text = if (state.paused) "Resume video" else "Pause video"
            voiceButton.text = if (state.voiceEnabled) "Voice on" else "Enable voice"
            if (state.active && !state.authorized(state.token)) endSession()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        voice = RtcVoice(this, state)
        state.hostName = Build.MODEL
        root = FrameLayout(this).apply { setBackgroundColor(dark) }
        overlay = ShowMeOverlay(this)
        renderer = ShowMeRenderer(state, overlay, ::notice)
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
        }
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        buildChrome()
        buildHome()
        setContentView(root)
        if (!OpenCVLoader.initLocal()) notice("Computer vision could not initialize on this device.")
        handler.post(tick)
    }
    private fun buildChrome() {
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(20), d(20), d(16)); background = rounded(panel, 24) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("ShowMe", 27f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        statusText = label("CAMERA READY", 10f, mint, true).apply { setPadding(d(12), d(9), d(12), d(9)); background = rounded(0xff213b39.toInt(), 20) }
        heading.addView(statusText)
        top.addView(heading)
        presence = label("Show someone exactly where", 14f, Color.WHITE).apply { setPadding(0, d(10), 0, d(4)) }
        detail = label("Local camera assistance", 10f, muted)
        top.addView(presence); top.addView(detail)
        val topParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(d(14), d(44), d(14), 0) }
        root.addView(top, topParams)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), d(12), d(12), d(12)); background = rounded(panel, 24) }
        val actions = LinearLayout(this)
        actions.addView(button("Invite", true) { inviteDialog() }, LinearLayout.LayoutParams(0, d(48), 1f))
        actions.addView(button("Undo") { renderer.action("undo") }, LinearLayout.LayoutParams(0, d(48), 1f).apply { marginStart = d(7) })
        actions.addView(button("Clear") { AlertDialog.Builder(this).setTitle("Clear annotations?").setMessage("Remove all current drawings from the physical scene.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> renderer.action("clear") }.show() }, LinearLayout.LayoutParams(0, d(48), 1f).apply { marginStart = d(7) })
        bottom.addView(actions)
        val second = LinearLayout(this).apply { setPadding(0, d(8), 0, 0) }
        pauseButton = button("Pause video") {
            if (!state.active) { startLocal(false); return@button }
            state.paused = !state.paused
            if (state.paused) state.frames.clear()
        }
        voiceButton = button("Enable voice") { toggleVoice() }
        second.addView(pauseButton, LinearLayout.LayoutParams(0, d(42), 1f))
        second.addView(voiceButton, LinearLayout.LayoutParams(0, d(42), 1f))
        second.addView(button("End") { if (state.active) AlertDialog.Builder(this).setTitle("End this session?").setMessage("The invite link will stop working. Your local annotations remain until cleared.")
            .setNegativeButton("Keep sharing", null).setPositiveButton("End session") { _, _ -> endSession() }.show() }, LinearLayout.LayoutParams(0, d(42), 0.65f))
        bottom.addView(second)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(14), 0, d(14), d(28)) })
        toast = label("", 13f, Color.WHITE).apply { visibility = View.GONE; gravity = Gravity.CENTER; setPadding(d(18), d(12), d(18), d(12)); background = rounded(0xf0203045.toInt(), 16) }
        root.addView(toast, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(d(24), 0, d(24), d(162)) })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars())
            topParams.topMargin = safe.top + d(12); top.layoutParams = topParams
            (bottom.layoutParams as FrameLayout.LayoutParams).also { it.bottomMargin = safe.bottom + d(12); bottom.layoutParams = it }
            insets
        }
    }
    private fun buildHome() {
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(30), d(40), d(30), d(40)); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(dark, 0xff16372f.toInt(), dark))
        }
        home.addView(label("ShowMe", 34f, mint, true))
        home.addView(label("A little direction.\nRight where it matters.", 35f, Color.WHITE, true).apply { setPadding(0, d(35), 0, d(18)) })
        home.addView(label("Share your camera. A helper opens a link and pins, draws or points into your world. The guidance stays on the surface as you move.", 16f, muted).apply { setLineSpacing(d(4).toFloat(), 1f) })
        home.addView(label("01  START A SESSION\n02  SHARE THE LINK\n03  FOLLOW THE GUIDANCE", 11f, mint, true).apply { setPadding(0, d(28), 0, d(25)); setLineSpacing(d(9).toFloat(), 1f) })
        home.addView(button("Start local session", true) { startLocal(false) }, LinearLayout.LayoutParams(-1, d(56)))
        home.addView(button("Secure session / voice setup") { secureOptions() }, LinearLayout.LayoutParams(-1, d(48)).apply { topMargin = d(10) })
        home.addView(label("LOCAL PREVIEW  /  No account or cloud server\nBoth devices must be on the same Wi-Fi or hotspot. Ordinary HTTP is for trusted local networks; two-way browser voice needs trusted HTTPS.", 11f, muted).apply { setPadding(0, d(20), 0, 0); setLineSpacing(d(3).toFloat(), 1f) })
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
    }
    private fun secureOptions() {
        AlertDialog.Builder(this).setTitle("Local HTTPS and voice").setMessage(
            "Quick local mode works immediately for video and drawing. Browsers require trusted HTTPS for the helper's microphone.\n\nSecure mode creates a private session certificate. Your browser may show a certificate warning or require explicit trust before allowing voice. For managed testing you can import a trusted PKCS12 certificate. No browser security settings are disabled.")
            .setPositiveButton("Start local HTTPS") { _, _ -> startLocal(true) }
            .setNeutralButton("Import .p12") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 73) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun startLocal(secure: Boolean) {
        if (starting || state.active) { if (state.active) inviteDialog(); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingSecure = secure; requestPermissions(arrayOf(Manifest.permission.CAMERA), 71); return
        }
        resumeAr()
        if (ar == null || !renderer.resumed) { pendingSecure = secure; notice("Finish AR camera setup, then start the session."); return }
        val addresses = localAddresses()
        if (addresses.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Connect to Wi-Fi or a hotspot").setMessage("ShowMe hosts this session on your phone. Connect both devices to the same Wi-Fi, or enable a phone hotspot and connect the helper to it.")
                .setPositiveButton("Network settings") { _, _ -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.setNegativeButton("Cancel", null).show()
            return
        }
        if (addresses.size == 1) launchServer(addresses.first(), secure)
        else AlertDialog.Builder(this).setTitle("Choose the local network address").setItems(addresses.toTypedArray()) { _, index -> launchServer(addresses[index], secure) }.show()
    }
    private fun launchServer(address: String, secure: Boolean) {
        starting = true; notice("Preparing your private session...")
        io.execute {
            try {
                val identity = if (secure) importedTls ?: LocalTls.create(address) else null
                state.begin(); state.secure = secure
                val local = LocalServer(this, address, 0, state, voice, identity)
                local.start(5000, true)
                if (destroyed) { local.stop(); state.end(); return@execute }
                server = local; tls = identity
                invite = "${if (secure) "https" else "http"}://$address:${local.listeningPort}/#${state.token}"
                startedAt = monotonicMs()
                runOnUiThread { renderer.action("clear"); home.visibility = View.GONE; inviteDialog() }
            } catch (t: Throwable) {
                state.end(); notice("Could not start the local session: ${t.message ?: t.javaClass.simpleName}")
            } finally { starting = false }
        }
    }
    private fun inviteDialog() {
        if (invite.isBlank() || !state.active) { startLocal(false); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24), d(12), d(24), d(8)) }
        val qr = MultiFormatWriter().encode(invite, BarcodeFormat.QR_CODE, 480, 480)
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(480 * 480) { index -> if (qr[index % 480, index / 480]) dark else Color.WHITE }
        bitmap.setPixels(pixels, 0, 480, 0, 0, 480, 480)
        column.addView(ImageView(this).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(d(220), d(220)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(label("The helper only needs a browser.\nUse the same Wi-Fi or hotspot.", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, d(15), 0, d(12)) })
        column.addView(label(invite, 11f, muted).apply { setTextIsSelectable(true) })
        if (state.secure) column.addView(label("Local certificate SHA-256:\n${tls?.fingerprint}\nTrust only the certificate matching this fingerprint. Microphone availability is checked by the browser.", 10f, muted).apply { setPadding(0, d(14), 0, 0); setTextIsSelectable(true) })
        AlertDialog.Builder(this).setTitle("Invite your helper").setView(column)
            .setPositiveButton("Share link") { _, _ -> shareInvite() }
            .setNeutralButton("Copy") { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShowMe invite", invite)); notice("Invite copied") }
            .setNegativeButton("Done", null).show()
    }
    private fun shareInvite() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join me on ShowMe")
            putExtra(Intent.EXTRA_TEXT, "Help me with ShowMe. Open this link while connected to the same Wi-Fi or hotspot:\n$invite")
        }, "Invite with"))
    }
    private fun toggleVoice() {
        if (!state.active) { notice("Start a session first"); return }
        if (state.voiceEnabled) { state.voiceEnabled = false; voice.setMuted(true); voice.disconnect(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 72); return
        }
        state.voiceEnabled = true; voice.setMuted(false)
        notice("Voice enabled. The helper can now connect audio in the browser.")
    }
    private fun endSession() {
        state.end(); voice.disconnect(); state.voiceEnabled = false
        invite = ""; startedAt = 0L
        val old = server; server = null
        io.execute { old?.stop() }
        notice("Sharing ended. Local annotations are still available.")
    }
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .filterNot { it.name.startsWith("rmnet") || it.name.startsWith("tun") || it.name.startsWith("ccmni") }
            .sortedBy { if (it.name.contains("wlan") || it.name.startsWith("ap") || it.name.contains("wifi")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())
    private fun resumeAr() {
        if (renderer.resumed || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true; return
            }
            val session = ar ?: Session(this).also { created ->
                val camera = created.getSupportedCameraConfigs(CameraConfigFilter(created)).filter {
                    it.imageSize.width <= 1920 && it.imageSize.height <= 1920
                }.maxByOrNull { it.imageSize.width * it.imageSize.height }
                if (camera != null) created.cameraConfig = camera
                val config = Config(created).apply {
                    focusMode = Config.FocusMode.AUTO
                    depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                }
                created.configure(config)
                if (config.depthMode == Config.DepthMode.DISABLED) notice("Depth is unavailable; only sufficiently mapped surfaces can accept drawings.")
                val manager = getSystemService(CameraManager::class.java)
                renderer.imageRotation = manager.getCameraCharacteristics(created.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                ar = created
            }
            session.resume(); renderer.arSession = session; renderer.resumed = true; gl.onResume()
        } catch (t: Throwable) { notice("AR camera setup: ${t.message ?: t.javaClass.simpleName}") }
    }
    override fun onResume() {
        super.onResume(); resumeAr()
        if (resumeSharing && state.active) { state.paused = false; voice.setMuted(!state.voiceEnabled) }
        resumeSharing = false
    }
    override fun onPause() {
        resumeSharing = state.active && !state.paused
        if (state.active) { state.paused = true; state.frames.clear(); voice.setMuted(true) }
        renderer.resumed = false; gl.onPause(); runCatching { ar?.pause() }
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null)
        state.end(); server?.stop(); voice.close(); renderer.close()
        runCatching { ar?.close() }; io.shutdownNow()
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) { notice("Permission was not granted. You can enable it in Android settings."); return }
        if (code == 71) { resumeAr(); val secure = pendingSecure ?: false; pendingSecure = null; startLocal(secure) }
        if (code == 72 && state.active) toggleVoice()
    }
    @Deprecated("Android legacy activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 73 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val password = EditText(this).apply { hint = "Certificate password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Import trusted PKCS12").setView(password)
            .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ ->
                io.execute {
                    try {
                        val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 1_048_576); out.write(buffer, 0, n) }; out.toByteArray()
                        }
                        val pass = password.text.toString().toCharArray()
                        val store = KeyStore.getInstance("PKCS12"); store.load(bytes.inputStream(), pass)
                        importedTls = LocalTls.fromStore(store, pass); pass.fill('\u0000')
                        notice("Certificate loaded for this app session. Start a secure session next.")
                    } catch (_: Exception) { notice("Certificate import failed. Check the file and password.") }
                }
            }.show()
    }
    private fun notice(message: String) {
        if (destroyed) return
        runOnUiThread {
            if (home.visibility == View.VISIBLE) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            toast.text = message; toast.visibility = View.VISIBLE
            handler.postDelayed({ if (toast.text.toString() == message) toast.visibility = View.GONE }, 5000L)
        }
    }
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): TextView = label(text, 13f, if (primary) dark else Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(d(10), d(10), d(10), d(10)); minHeight = d(44)
        background = rounded(if (primary) mint else 0xff223046.toInt(), 14)
        isClickable = true; isFocusable = true; contentDescription = text
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); action() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = d(radius).toFloat() }
    private fun d(value: Int) = (value * resources.displayMetrics.density).toInt()
}
