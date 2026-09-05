package com.sirpaul.spatialnomap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : Activity(), NetworkClient.Callbacks {
    private lateinit var surface: GLSurfaceView
    private lateinit var overlay: TargetOverlayView
    private lateinit var renderer: ArRenderer
    private lateinit var network: NetworkClient
    private lateinit var ranger: WifiAwareRttRanger
    private lateinit var statusView: TextView
    private lateinit var roleButton: Button
    private lateinit var serverEdit: EditText
    private lateinit var roomEdit: EditText

    private var session: Session? = null
    private var installRequested = false
    private var role = "A"
    private var surfaceResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        network = NetworkClient(this)
        overlay = TargetOverlayView(this)
        renderer = ArRenderer(network, overlay, ::setStatus, ::displayRotation)
        ranger = WifiAwareRttRanger(this, network, ::setStatus)

        surface = GLSurfaceView(this).apply {
            setPreserveEGLContextOnPause(true)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP && role == "A") {
                    renderer.queueTap(event.x, event.y)
                    true
                } else false
            }
        }

        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildControls(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
    }

    private fun buildControls(): LinearLayout {
        val prefs = getSharedPreferences("poc", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 14)
            setBackgroundColor(0xaa111111.toInt())
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Starting ARCore..."
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roleButton = Button(this).apply {
            text = "ROLE A (tap target)"
            setOnClickListener {
                role = if (role == "A") "B" else "A"
                renderer.role = role
                text = if (role == "A") "ROLE A (tap target)" else "ROLE B (receiver)"
                network.disconnect()
                ranger.stop()
                setStatus("role changed to $role; press CONNECT")
            }
        }
        serverEdit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0xffaaaaaa.toInt())
            hint = "server, e.g. 192.168.1.10:8000"
            setSingleLine(true)
            setText(prefs.getString("server", "192.168.1.10:8000"))
        }
        roomEdit = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0xffaaaaaa.toInt())
            hint = "room"
            setSingleLine(true)
            setText(prefs.getString("room", "field-test"))
        }
        val connect = Button(this).apply {
            text = "CONNECT"
            setOnClickListener {
                if (!ensurePermissions()) return@setOnClickListener
                prefs.edit().putString("server", serverEdit.text.toString()).putString("room", roomEdit.text.toString()).apply()
                network.connect(serverEdit.text.toString(), roomEdit.text.toString(), role)
                ranger.start(role)
            }
        }
        val clear = Button(this).apply {
            text = "CLEAR"
            setOnClickListener { network.clearTarget(); renderer.setRemoteTarget(null, "") }
        }
        row1.addView(roleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(connect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row1.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(statusView)
        panel.addView(serverEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(roomEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(row1)
        return panel
    }

    override fun onResume() {
        super.onResume()
        startArIfPossible()
    }

    private fun startArIfPossible() {
        if (!ensurePermissions(cameraOnly = true)) return
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
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                }
                s.configure(cfg)
                session = s
                renderer.session = s
                setStatus("ARCore ready; depth=${cfg.depthMode}")
            }
            session?.resume()
            if (!surfaceResumed) {
                surface.onResume()
                surfaceResumed = true
            }
        } catch (t: Throwable) {
            setStatus("ARCore start failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onPause() {
        if (surfaceResumed) {
            surface.onPause()
            surfaceResumed = false
        }
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        ranger.stop()
        network.disconnect()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun ensurePermissions(cameraOnly: Boolean = false): Boolean {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (!cameraOnly) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), if (cameraOnly) REQ_CAMERA else REQ_ALL)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Camera / nearby-device permissions are required for the requested path", Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == REQ_CAMERA) startArIfPossible()
    }

    override fun onNetworkStatus(text: String) = setStatus(text)

    override fun onRemoteTarget(pointWb: FloatArray?, detail: String) {
        renderer.setRemoteTarget(pointWb, detail)
    }

    private fun setStatus(text: String) {
        runOnUiThread { if (::statusView.isInitialized) statusView.text = text }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0

    companion object {
        private const val REQ_CAMERA = 41
        private const val REQ_ALL = 42
    }
}
