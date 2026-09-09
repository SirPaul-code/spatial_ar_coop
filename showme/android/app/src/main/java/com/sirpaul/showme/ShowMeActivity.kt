package com.sirpaul.showme

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.google.ar.core.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject
import java.util.Locale

class ShowMeActivity : Activity(), LanSessionServer.Callbacks {
    private lateinit var gl:GLSurfaceView
    private lateinit var renderer:ShowMeRenderer
    private lateinit var voice:VoiceBridge
    private var ar:Session?=null
    @Volatile private var server:LanSessionServer?=null
    private var installRequested=false
    private var active=false
    private var foreground=false
    private var startedAt=0L
    private var address=""
    private var cameraRotation=90
    private lateinit var root:FrameLayout
    private lateinit var home:LinearLayout
    private lateinit var controls:LinearLayout
    private lateinit var state:TextView
    private lateinit var peer:TextView
    private lateinit var timer:TextView
    private lateinit var caption:TextView
    private lateinit var invite:TextView
    private lateinit var pause:TextView
    private lateinit var mic:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val mint=0xff70e4cf.toInt(); private val ink=0xff0b1018.toInt()
    private val ticker=object:Runnable { override fun run() { if(isDestroyed) return; if(active) { val seconds=(SystemClock.elapsedRealtime()-startedAt)/1000; timer.text=String.format(Locale.US,"LOCAL  %02d:%02d",seconds/60,seconds%60) }; handler.postDelayed(this,1000) } }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(true)
        buildUi()
        voice=VoiceBridge(this) { server?.send(it) }
        handler.post(ticker)
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),101)
    }
    private fun buildUi() {
        root=FrameLayout(this).apply { setBackgroundColor(ink) }
        gl=GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true }
        val overlay=DrawingOverlay(this)
        renderer=ShowMeRenderer(overlay,{server},{display?.rotation ?: Surface.ROTATION_0},{cameraRotation}) { text,count -> runOnUiThread { if(!isDestroyed) { state.text=text; if(active) invite.text="INVITE HELPER   /   $count MARKS" } } }
        gl.setRenderer(renderer); root.addView(gl,FrameLayout.LayoutParams(-1,-1)); root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val top=column().apply { setPadding(dp(22),dp(22),dp(22),dp(24)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xf00b1018.toInt(),0x000b1018)) }
        val brand=row(); brand.addView(label("ShowMe",29,true,mint),LinearLayout.LayoutParams(0,-2,1f)); timer=label("LOCAL PREVIEW",11,true,0xffb5c9cc.toInt()); brand.addView(timer); top.addView(brand)
        state=label("Preparing camera",12,false,0xffe7eff6.toInt()); top.addView(state,spaced())
        peer=label("You share the view. They point the way.",12,false,0xffadb9c9.toInt()); top.addView(peer,spaced())
        root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        home=column().apply { setPadding(dp(26),dp(26),dp(26),dp(28)); background=rounded(0xf50f1722.toInt(),28) }
        home.addView(label("Help that stays\nright where it belongs.",30,true))
        home.addView(label("Share your camera. A helper joins in their browser and draws directly into your world.",15,false,0xffb6c4d4.toInt()),spaced(18))
        listOf("01   Start a private local session","02   Send the link or show the QR code","03   Their marks stay on real surfaces").forEach { home.addView(label(it,13,false,0xffdce8f4.toInt()),spaced(14)) }
        home.addView(button("Start sharing",mint,ink) { startSession() },spaced(24,58))
        home.addView(label("First preview: both devices on the same Wi-Fi or hotspot. No account. No cloud upload.",12,false,0xff8d9bad.toInt()),spaced(14))
        root.addView(home,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(dp(12),0,dp(12),dp(12)) })
        controls=column().apply { visibility=View.GONE; setPadding(dp(16),dp(16),dp(16),dp(20)); background=rounded(0xf20e1620.toInt(),26) }
        invite=button("INVITE HELPER",0xff1d3038.toInt(),mint) { inviteDialog() }; controls.addView(invite,spaced(0,48))
        val actions=row()
        pause=button("Pause",0xff202c3b.toInt()) { renderer.sharingPaused=!renderer.sharingPaused; pause.text=if(renderer.sharingPaused) "Resume" else "Pause"; if(renderer.sharingPaused) { voice.setEnabled(false); mic.text="Mic off" }; server?.let { it.paused=renderer.sharingPaused; it.pauseReason=if(renderer.sharingPaused) "Camera owner paused sharing" else "Resuming camera"; it.status() } }
        mic=button("Mic off",0xff202c3b.toInt()) { toggleMicrophone() }
        val end=button("End",0xff49272f.toInt(),0xffffa3ac.toInt()) { AlertDialog.Builder(this).setTitle("End this session?").setMessage("The link expires and all marks are cleared.").setPositiveButton("End session") { _,_ -> endSession() }.setNegativeButton("Cancel",null).show() }
        listOf(pause,mic,end).forEach { actions.addView(it,LinearLayout.LayoutParams(0,dp(50),1f).apply { setMargins(dp(3),0,dp(3),0) }) }; controls.addView(actions,spaced(10))
        val secondary=row(); secondary.addView(button("Clear marks",Color.TRANSPARENT,0xffb8c9d8.toInt()) { AlertDialog.Builder(this).setTitle("Clear all marks?").setPositiveButton("Clear") { _,_ -> renderer.queue(JSONObject().put("type","clear")) }.setNegativeButton("Cancel",null).show() },LinearLayout.LayoutParams(0,dp(40),1f))
        secondary.addView(button("Send a note",Color.TRANSPARENT,0xffb8c9d8.toInt()) { val text=EditText(this).apply { hint="Message your helper" }; AlertDialog.Builder(this).setTitle("Send a note").setView(text).setPositiveButton("Send") { _,_ -> val message=text.text.toString().take(300); server?.send(JSONObject().put("type","chat").put("name","Camera owner").put("text",message)); caption.text="You: $message" }.setNegativeButton("Cancel",null).show() },LinearLayout.LayoutParams(0,dp(40),1f)); controls.addView(secondary)
        caption=label("Only approve someone you trust with this view.",12,false,0xffa8b9c9.toInt()); controls.addView(caption,spaced(6))
        root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(dp(12),0,dp(12),dp(12)) })
        setContentView(root)
    }
    private fun startSession() {
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA),101); return }
        if(ar==null) { resumeAr(); if(ar==null) return }
        val addresses=LanSessionServer.localAddresses()
        if(addresses.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Connect your devices first").setMessage("Use the same Wi-Fi, or turn on your phone's hotspot and connect the helper to it. Then start sharing.").setPositiveButton("Wi-Fi settings") { _,_ -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.setNeutralButton("Hotspot settings") { _,_ -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.setNegativeButton("Cancel",null).show(); return
        }
        if(active) return
        try {
            val service=LanSessionServer(this,this); service.start(); server=service; address=addresses.first()
            active=true; startedAt=SystemClock.elapsedRealtime(); renderer.sharing=true; renderer.sharingPaused=false
            home.visibility=View.GONE; controls.visibility=View.VISIBLE; pause.text="Pause"; peer.text="Waiting for your helper"; caption.text="Local session / link works on this Wi-Fi or hotspot"
            inviteDialog()
        } catch(_:Exception) { Toast.makeText(this,"Could not start local sharing. Check Wi-Fi and try again.",Toast.LENGTH_LONG).show() }
    }
    private fun endSession() {
        active=false; renderer.sharing=false; renderer.sharingPaused=false; renderer.clear()
        server?.close(); server=null; voice.setEnabled(false); mic.text="Mic off"
        controls.visibility=View.GONE; home.visibility=View.VISIBLE; timer.text="LOCAL PREVIEW"; peer.text="You share the view. They point the way."
    }
    private fun inviteDialog() {
        val s=server ?: return
        val addresses=s.addresses
        if(address !in addresses) address=addresses.firstOrNull() ?: address
        val link=s.link(address)
        val panel=column().apply { setPadding(dp(22),dp(16),dp(22),dp(18)) }
        val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        runCatching { val qr=MultiFormatWriter().encode(link,BarcodeFormat.QR_CODE,600,600); for(y in 0 until 600) for(x in 0 until 600) bitmap.setPixel(x,y,if(qr[x,y]) Color.BLACK else Color.WHITE) }
        panel.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription="QR code for this private session" },LinearLayout.LayoutParams(dp(220),dp(220)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        panel.addView(label("http://$address:${s.port}",15,true),spaced(12))
        panel.addView(label("The helper only needs a browser. They must be connected to your Wi-Fi or hotspot. You approve them before video is shared.",13,false,0xffb5c5d6.toInt()),spaced(10))
        if(addresses.size>1) panel.addView(button("Choose network address",0xff253142.toInt()) { AlertDialog.Builder(this).setTitle("Local address").setItems(addresses.toTypedArray()) { _,i -> address=addresses[i]; inviteDialog() }.show() },spaced(10,42))
        AlertDialog.Builder(this).setTitle("Invite someone to ShowMe").setView(panel)
            .setPositiveButton("Share link") { _,_ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,"Join my ShowMe camera session. Open this link on the same Wi-Fi or hotspot:\n$link") },"Invite your helper")) }
            .setNeutralButton("Copy link") { _,_ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShowMe private session",link)); Toast.makeText(this,"Link copied",Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Done",null).show()
    }
    private fun toggleMicrophone() {
        if(voice.enabled) { voice.setEnabled(false); mic.text="Mic off"; return }
        if(renderer.sharingPaused) { Toast.makeText(this,"Resume sharing first",Toast.LENGTH_SHORT).show(); return }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),102); return }
        voice.setEnabled(true); mic.text="Mic on"; caption.text="Helper can start audio. Browser talk requires HTTPS; local HTTP supports listening."
        server?.send(JSONObject().put("type","voiceAvailable"))
    }
    override fun onJoin(name:String,decide:(Boolean)->Unit) { runOnUiThread {
        if(!foreground || !active) { decide(false); return@runOnUiThread }
        AlertDialog.Builder(this).setTitle("$name wants to help").setMessage("Allow them to see your camera and place AR marks? Only approve someone you trust.")
            .setPositiveButton("Allow") { _,_ -> decide(true) }.setNegativeButton("Decline") { _,_ -> decide(false) }.setOnCancelListener { decide(false) }.show()
    } }
    override fun onPeer(connected:Boolean,name:String) { runOnUiThread {
        if(isDestroyed) return@runOnUiThread
        peer.text=if(connected) "$name connected / browser helper" else "Helper disconnected / marks retained"
        if(!connected) { voice.setEnabled(false); mic.text="Mic off" }
    } }
    override fun onCommand(message:JSONObject) {
        when(message.optString("type")) {
            "voiceOffer","voiceIce","voiceStop" -> voice.message(message)
            "chat" -> { val text=message.optString("text").take(300); runOnUiThread { caption.text="Helper: $text" }; server?.send(JSONObject().put("type","chat").put("name","Helper").put("text",text)) }
            else -> renderer.queue(message)
        }
    }
    private fun resumeAr() {
        if(!foreground || checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return
        if(!ShowMeApplication.openCvReady) { state.text="Computer vision failed to initialize"; return }
        try {
            if(ar==null) {
                if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested=true; return }
                ar=Session(this)
                val s=ar!!
                s.configure(Config(s).apply { focusMode=Config.FocusMode.AUTO; planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL; depthMode=if(s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED })
                val manager=getSystemService(CameraManager::class.java)
                val orientation=manager.getCameraCharacteristics(s.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val degrees=when(display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                cameraRotation=(orientation-degrees+360)%360
            }
            if(renderer.resumed) return
            ar!!.resume(); renderer.session=ar; renderer.resumed=true; gl.onResume()
        } catch(t:Exception) { state.text="Camera unavailable: ${t.javaClass.simpleName}"; Toast.makeText(this,"ARCore could not start. Check camera permission and Google Play Services for AR.",Toast.LENGTH_LONG).show() }
    }
    override fun onResume() { super.onResume(); foreground=true; resumeAr() }
    override fun onPause() {
        foreground=false
        if(active) { renderer.sharingPaused=true; pause.text="Resume"; server?.let { it.paused=true; it.pauseReason="Camera app is in the background"; it.status() }; voice.setEnabled(false); mic.text="Mic off" }
        renderer.resumed=false; gl.onPause(); runCatching { ar?.pause() }; super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); server?.close(); renderer.close(); voice.close(); runCatching { ar?.close() }; ar=null; super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED) { state.text="Permission denied. You can enable it in app settings."; return }
        if(requestCode==101) resumeAr() else if(requestCode==102 && active) toggleMicrophone()
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Int,bold:Boolean=false,color:Int=Color.WHITE)=TextView(this).apply { text=value; textSize=size.toFloat(); setTextColor(color); if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private fun button(value:String,color:Int,foreground:Int=Color.WHITE,action:()->Unit)=label(value,14,true,foreground).apply { gravity=Gravity.CENTER; setPadding(dp(12),dp(10),dp(12),dp(10)); background=rounded(color,16); isClickable=true; isFocusable=true; contentDescription=value; setOnClickListener { action() } }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun spaced(top:Int=6,height:Int=-2)=LinearLayout.LayoutParams(-1,if(height<0) height else dp(height)).apply { topMargin=dp(top) }
}
