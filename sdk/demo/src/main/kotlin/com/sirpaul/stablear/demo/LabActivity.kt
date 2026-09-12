package com.sirpaul.stablear.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.google.ar.core.*

/** Deliberately single-device and offline; the SDK is not coupled to a call or room. */
class LabActivity : Activity() {
    private lateinit var view: GLSurfaceView
    private lateinit var renderer: LabRenderer
    private lateinit var status: TextView
    private var session: Session?=null
    private var installRequested=false
    private var permissionRequested=false
    private var pendingExport=""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { System.loadLibrary("opencv_java4") } catch(t: UnsatisfiedLinkError) {
            AlertDialog.Builder(this).setMessage("OpenCV could not load: ${t.message}").setPositiveButton("Close") { _,_ -> finish() }.show(); return
        }
        val layout=FrameLayout(this)
        view=LabSurfaceView(this) { x,y -> renderer.tap(x,y) }.apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true }
        status=TextView(this).apply { textSize=15f; setPadding(24,20,24,20); setBackgroundColor(0xCC14202B.toInt()) }
        renderer=LabRenderer(applicationContext,{ message -> runOnUiThread { status.text=message } }, { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation })
        view.setRenderer(renderer)
        layout.addView(view)
        layout.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val tools=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setBackgroundColor(0xCC14202B.toInt()) }
        fun button(label: String,action: ()->Unit) { tools.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("Clear") { renderer.clear() }
        button("Export metrics") {
            pendingExport=renderer.exportMetrics()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"stablear-metrics.json")
            },9)
        }
        button("Licences") { showNotices() }
        layout.addView(tools,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(layout)
        status.setText(R.string.startup_status)
        showNotices()
    }
    private fun showNotices() {
        val notices=runCatching { assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("Third-party notices are available in sdk/compliance. Do not redistribute this research build as a commercial release.")
        AlertDialog.Builder(this).setTitle("ARCore and third-party notices")
            .setMessage("This application runs on Google Play Services for AR (ARCore), provided by Google LLC. ARCore use is subject to Google's Terms of Service and Privacy Policy.\n\nThe application does not upload camera images or collect analytics. Google Play Services for AR has its own data processing.\n\n"+notices)
            .setPositiveButton("Close",null)
            .setNeutralButton("Privacy") { _,_ -> startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://policies.google.com/privacy"))) }
            .setNegativeButton("Google terms") { _,_ -> startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://policies.google.com/terms"))) }.show()
    }
    override fun onResume() {
        super.onResume(); if(!::renderer.isInitialized) return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            if(!permissionRequested) { permissionRequested=true; requestPermissions(arrayOf(Manifest.permission.CAMERA),1) }
            else status.setText(R.string.camera_permission_needed)
            return
        }
        try {
            if(session==null) {
                if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested=true; return }
                session=Session(this).also { s ->
                    s.configure(Config(s).apply {
                        depthMode=if(s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                        focusMode=Config.FocusMode.AUTO
                        updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE
                    })
                }
            }
            session!!.resume(); renderer.session=session; renderer.running=true; view.onResume()
        } catch(e: Exception) { status.text=getString(R.string.ar_unavailable,e.javaClass.simpleName,e.message.orEmpty()) }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==1 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) onResume()
    }
    override fun onPause() {
        if(::renderer.isInitialized) {
            renderer.running=false; renderer.invalidate()
            view.queueEvent { renderer.releaseArOnOwnerThread() }
            view.onPause(); session?.pause()
        }
        super.onPause()
    }
    override fun onDestroy() {
        if(::renderer.isInitialized) renderer.shutdown()
        session?.close(); session=null
        super.onDestroy()
    }
    @Deprecated("Activity callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==9 && resultCode==RESULT_OK) data?.data?.let { uri ->
            try { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExport) } }
            catch(e: Exception) { status.text=getString(R.string.export_failed,e.message.orEmpty()) }
        }
        pendingExport=""
    }
}
