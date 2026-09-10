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
import android.net.Uri
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
import org.opencv.core.Core
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Executors

/** User-facing call controls. Hosting setup is a one-time activation, not call UI. */
class ShowMeActivity : Activity() {
    private val state = ShowMeSession()
    private lateinit var renderer: ShowMeRenderer
    private lateinit var gl: GLSurfaceView
    private lateinit var voice: RtcVoice
    private lateinit var root: FrameLayout
    private lateinit var layout: LinearLayout
    private lateinit var home: ScrollView
    private lateinit var statusText: TextView
    private lateinit var detail: TextView
    private lateinit var noticeText: TextView
    private lateinit var micButton: TextView
    private lateinit var pauseButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var startButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val settings by lazy { getSharedPreferences("showme-service", MODE_PRIVATE) }
    private var ar: Session? = null
    private var server: LocalServer? = null
    private var remote: RemoteHostConnection? = null
    private var invite = ""
    private var startedAt = 0L
    private var installRequested = false
    @Volatile private var starting = false
    private var pendingLocal: Boolean? = null
    private var resumeSharing = false
    private var foreground = false
    private var destroyed = false
    private var micMuted = false
    private var pendingApproval: Pair<String,String>? = null
    private val ink = 0xff15191c.toInt()
    private val paper = 0xfff5f4ef.toInt()
    private val accent = 0xffa7ebc9.toInt()
    private val grey = 0xffa9afb2.toInt()
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            val elapsed = if (startedAt == 0L) 0L else (monotonicMs() - startedAt) / 1000
            statusText.text = when {
                !state.active -> "Camera"
                state.paused -> "Camera paused"
                state.hasHelper() -> state.helperName.ifBlank { "Connected" }
                else -> "Waiting for helper"
            }
            detail.text = if (state.active) String.format(Locale.US, "%02d:%02d  \u00b7  %d marks  \u00b7  %.0f fps", elapsed/60,elapsed%60,state.annotationCount,state.captureFps)
                else "${state.annotationCount} marks"
            micButton.text = if (micMuted || !state.voiceEnabled) "Unmute" else "Mute"
            pauseButton.text = if (state.paused) "Resume" else "Pause"
            shareButton.text = if (state.active) "Invite" else "New call"
            startButton.isEnabled = !starting
            if (state.active && !state.authorized(state.token)) endSession()
            if (state.active && state.videoState == "CONNECTED" && !state.hasHelper()) voice.disconnect()
            handler.postDelayed(this, 750L)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        voice = RtcVoice(this,state)
        val api = SessionApi(state,voice)
        voice.controlHandler = api::handle
        state.hostName = "ShowMe camera"
        root = FrameLayout(this).apply { setBackgroundColor(ink) }
        layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(ink) }
        root.addView(layout,FrameLayout.LayoutParams(-1,-1))
        buildCallUi()
        buildHome()
        setContentView(root)
        // A background verifier must not fan out onto every CPU core used by video.
        if (OpenCVLoader.initLocal()) Core.setNumThreads(1) else notice("Computer vision could not start on this device.")
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            layout.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            home.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            insets
        }
        root.requestApplyInsets()
        handler.post(tick)
        receiveActivation(intent)
    }
    private fun buildCallUi() {
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(d(18),d(8),d(10),d(8)) }
        val heading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        statusText=label("Camera",17f,Color.WHITE,true)
        detail=label("Ready",11f,grey).apply { setPadding(0,d(4),0,0) }
        heading.addView(statusText);heading.addView(detail)
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("More") { callMenu() },LinearLayout.LayoutParams(d(64),d(48)))
        layout.addView(header,LinearLayout.LayoutParams(-1,-2))
        val cameraArea=FrameLayout(this)
        val overlay=ShowMeOverlay(this)
        renderer=ShowMeRenderer(state,overlay,::notice,voice)
        gl=GLSurfaceView(this).apply { setEGLContextClientVersion(2);preserveEGLContextOnPause=true;setRenderer(renderer) }
        cameraArea.addView(gl,FrameLayout.LayoutParams(-1,-1))
        cameraArea.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        noticeText=label("",13f,Color.WHITE).apply {
            setPadding(d(14),d(10),d(14),d(10));background=rounded(0xeb252c30.toInt(),6);visibility=View.GONE
        }
        cameraArea.addView(noticeText,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(d(12),0,d(12),d(12)) })
        layout.addView(cameraArea,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(d(12),d(8),d(12),d(10)) }
        val editing=LinearLayout(this)
        shareButton=button("Invite",true) { if(state.active)inviteDialog() else startCall(false) }
        addEqual(editing,shareButton)
        addEqual(editing,button("Undo") { renderer.action("undo") })
        addEqual(editing,button("Clear") { AlertDialog.Builder(this).setTitle("Remove all marks?")
            .setNegativeButton("Cancel",null).setPositiveButton("Remove") { _,_->renderer.action("clear") }.show() })
        controls.addView(editing)
        val callControls=LinearLayout(this).apply { setPadding(0,d(6),0,0) }
        micButton=button("Mute") { toggleMic() }
        pauseButton=button("Pause") {
            if(state.active){state.paused=!state.paused;voice.setMuted(micMuted||state.paused)
                if(state.paused){state.frames.clear();state.videoFrames.clear()}}
        }
        addEqual(callControls,micButton);addEqual(callControls,pauseButton)
        addEqual(callControls,button("End call",false,0xfff3aaa1.toInt()) {
            if(state.active)AlertDialog.Builder(this).setTitle("End this call?").setMessage("The invitation stops working. Your marks remain in this camera session.")
                .setNegativeButton("Keep call",null).setPositiveButton("End call") { _,_->endSession() }.show()
            else home.visibility=View.VISIBLE
        })
        controls.addView(callControls);layout.addView(controls,LinearLayout.LayoutParams(-1,-2))
    }
    private fun buildHome() {
        home=ScrollView(this).apply { isFillViewport=true;setBackgroundColor(paper) }
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(d(28),d(24),d(28),d(24)) }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        top.addView(label("ShowMe",25f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("Settings",false,ink) { settingsMenu() })
        content.addView(top)
        content.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        content.addView(label("Show the problem.\nGet a hand.",34f,ink,true).apply { setPadding(0,d(52),0,d(20)) })
        content.addView(label("Let someone see through your camera, talk you through it, and mark the exact place to look.",16f,0xff596064.toInt()).apply { setLineSpacing(d(4).toFloat(),1f) })
        content.addView(label("They open a link. You keep the camera.",13f,0xff596064.toInt()).apply { setPadding(0,d(30),0,d(36)) })
        startButton=button("Start a call",true) { startCall(false) }
        content.addView(startButton,LinearLayout.LayoutParams(-1,d(56)))
        content.addView(label("Your microphone and camera are shared only during a call.",11f,0xff747a7c.toInt()).apply { setPadding(0,d(16),0,d(24));gravity=Gravity.CENTER })
        content.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        content.addView(label("SHOWME  /  ${BuildConfig.VERSION_NAME}",10f,0xff85898a.toInt()))
        home.addView(content,FrameLayout.LayoutParams(-1,-1));root.addView(home,FrameLayout.LayoutParams(-1,-1))
    }
    private fun addEqual(row:LinearLayout,view:View){row.addView(view,LinearLayout.LayoutParams(0,d(46),1f).apply{setMargins(d(3),0,d(3),0)})}
    private fun settingsMenu() {
        val configured=settings.getString("origin","").orEmpty().isNotBlank()
        AlertDialog.Builder(this).setTitle("ShowMe").setItems(arrayOf(if(configured)"Replace service activation" else "Activate Internet calls","Use local Wi-Fi","About this build")) { _,which->
            when(which){0->activationDialog();1->startCall(true);2->AlertDialog.Builder(this).setTitle("ShowMe")
                .setMessage("${BuildConfig.VERSION_NAME}\n\nInternet calls use encrypted WebRTC media, with relay fallback. Surface marks stay in this AR camera session. This build still needs physical-device acceptance testing.")
                .setPositiveButton("Done",null).show()}
        }.show()
    }
    private fun callMenu() {
        AlertDialog.Builder(this).setTitle("Call").setItems(arrayOf("Copy invitation","Connection details","Return to start")){_,which->
            when(which){0->copyInvite();1->AlertDialog.Builder(this).setTitle("Connection details")
                .setMessage("${if(state.internet)"Internet" else "Local Wi-Fi"}\nWebRTC: ${state.videoState}\nCapture: %.1f fps\n%s".format(Locale.US,state.captureFps,state.telemetry.snapshot().toString(2)))
                .setPositiveButton("Done",null).show();2->{if(state.active)endSession();home.visibility=View.VISIBLE}}
        }.show()
    }
    private fun startCall(local:Boolean) {
        if(starting)return
        if(state.active){inviteDialog();return}
        if(!local&&settings.getString("origin","").isNullOrBlank()){activationDialog();return}
        pendingLocal=local
        val permissions=listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(permissions.isNotEmpty()) {requestPermissions(permissions.toTypedArray(),71);return}
        continueStart(local)
    }
    private fun continueStart(local:Boolean) {
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){notice("Camera permission is needed to share your view.");return}
        resumeAr()
        if(ar==null||!renderer.resumed){pendingLocal=local;notice("Finish camera setup, then tap Start a call.");return}
        pendingLocal=null
        state.voiceEnabled=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        micMuted=!state.voiceEnabled;voice.setMuted(micMuted)
        starting=true
        if(local){launchLocal();return}
        notice("Starting your call...")
        val connection=RemoteHostConnection(state,voice,{id,name->runOnUiThread { requestApproval(id,name) }},{text->notice(text)})
        remote=connection
        connection.start(settings.getString("origin","")!!,settings.getString("key","")!!,"ShowMe camera",onReady={url->
            runOnUiThread {
                if(destroyed){connection.stop();return@runOnUiThread}
                invite=url;starting=false;startedAt=monotonicMs();renderer.action("clear");home.visibility=View.GONE
                state.paused=!foreground;inviteDialog()
            }
        },onFailure={message->runOnUiThread {starting=false;connection.stop(false);remote=null;notice(message)}})
    }
    private fun requestApproval(id:String,name:String) {
        if(destroyed||!state.active)return
        if(!foreground){pendingApproval=id to name;return}
        AlertDialog.Builder(this).setTitle("Let $name join?").setMessage("They will see your camera, hear you when unmuted, and add marks to your view.")
            .setNegativeButton("Decline") {_,_->remote?.approve(id,false)}.setPositiveButton("Allow") {_,_->remote?.approve(id,true)}
            .setOnCancelListener {remote?.approve(id,false)}.show()
    }
    private fun launchLocal() {
        val addresses=runCatching {NetworkInterface.getNetworkInterfaces().toList().filter {it.isUp&&!it.isLoopback}
            .filterNot {it.name.startsWith("rmnet")||it.name.startsWith("tun")||it.name.startsWith("ccmni")}
            .sortedBy {if(it.name.contains("wlan")||it.name.startsWith("ap"))0 else 1}
            .flatMap {it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().filter {it.isSiteLocalAddress}
            .mapNotNull {it.hostAddress}.distinct()}.getOrDefault(emptyList())
        if(addresses.isEmpty()){starting=false;AlertDialog.Builder(this).setTitle("Connect to Wi-Fi")
            .setMessage("For local mode, both devices must be on the same Wi-Fi or hotspot.").setPositiveButton("Settings"){_,_->startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}
            .setNegativeButton("Cancel",null).show();return}
        io.execute {
            try {
                state.begin();state.secure=false;state.internet=false
                // Listen on all local interfaces; pick Wi-Fi first for the shared invitation.
                val local=LocalServer(this,"0.0.0.0",0,state,voice)
                local.start(5000,true)
                if(destroyed){local.stop();state.end();return@execute}
                server=local
                runOnUiThread {invite="http://${addresses.first()}:${local.listeningPort}/#${state.token}";startedAt=monotonicMs()
                    home.visibility=View.GONE;renderer.action("clear");state.paused=!foreground;inviteDialog()}
            }catch(e:Exception){state.end();notice(e.message?:"Could not start local sharing.")}
            finally{starting=false}
        }
    }
    private fun inviteDialog() {
        if(invite.isBlank()||!state.active)return
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(d(22),d(12),d(22),d(8))}
        val qr=MultiFormatWriter().encode(invite,BarcodeFormat.QR_CODE,360,360)
        val bitmap=Bitmap.createBitmap(360,360,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(360*360){i->if(qr[i%360,i/360])Color.BLACK else Color.WHITE}
        bitmap.setPixels(pixels,0,360,0,0,360,360)
        column.addView(ImageView(this).apply {setImageBitmap(bitmap)},LinearLayout.LayoutParams(d(190),d(190)).apply {gravity=Gravity.CENTER_HORIZONTAL})
        column.addView(label(if(state.internet)"Open in any browser. You approve who joins." else "Open on the same Wi-Fi or hotspot. Browser microphone needs Internet mode.",13f,Color.WHITE)
            .apply {setPadding(0,d(18),0,d(12));gravity=Gravity.CENTER})
        AlertDialog.Builder(this).setTitle("Invite someone").setView(column).setPositiveButton("Share link"){_,_->shareInvite()}
            .setNeutralButton("Copy link"){_,_->copyInvite()}.setNegativeButton("Done",null).show()
    }
    private fun copyInvite(){if(invite.isBlank())return;getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShowMe",invite));notice("Invitation copied")}
    private fun shareInvite(){if(invite.isBlank())return;startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"Join my ShowMe call");putExtra(Intent.EXTRA_TEXT,"Join my ShowMe call${if(state.internet)"" else " on the same Wi-Fi"}:\n$invite")
    },"Share invitation"))}
    private fun toggleMic(){
        if(!state.active)return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),72);return}
        micMuted=!micMuted;state.voiceEnabled=true;voice.setMuted(micMuted)
    }
    private fun endSession(){
        remote?.stop();remote=null;state.end();voice.disconnect();state.voiceEnabled=false
        invite="";startedAt=0L;val old=server;server=null;io.execute{old?.stop()}
        notice("Call ended. Marks remain until you clear them.")
    }
    private fun activationDialog(){
        if(state.active){notice("End your current call before changing its service.");return}
        val input=EditText(this).apply {hint="Paste your activation link";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setPadding(d(20),d(12),d(20),d(12))}
        AlertDialog.Builder(this).setTitle("Activate Internet calls").setMessage("One-time setup for this installation. Paste the activation link printed by your ShowMe deployment, or open that link on this phone. Your helper never needs this step.")
            .setView(input).setPositiveButton("Continue"){_,_->parseActivation(input.text.toString())}
            .setNeutralButton("Local Wi-Fi instead"){_,_->startCall(true)}.setNegativeButton("Cancel",null).show()
    }
    private fun receiveActivation(intent:Intent?){val uri=intent?.data?:return;if(uri.scheme=="showme"&&uri.host=="connect")parseActivation(uri.toString())}
    private fun parseActivation(value:String){
        try {
            require(!state.active)
            val uri=Uri.parse(value.trim())
            val origin=if(uri.scheme=="showme")uri.getQueryParameter("server") ?: error("Missing service") else "${uri.scheme}://${uri.encodedAuthority}"
            val endpoint=Uri.parse(origin)
            require(endpoint.scheme=="https"&&!endpoint.host.isNullOrBlank()&&endpoint.userInfo==null&&endpoint.query==null&&endpoint.fragment==null)
            require(endpoint.path.isNullOrEmpty()||endpoint.path=="/")
            val key=Uri.parse("https://activation.invalid/?${uri.encodedFragment ?: ""}").getQueryParameter("key")?:error("Missing activation key")
            require(key.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            AlertDialog.Builder(this).setTitle("Use this ShowMe service?").setMessage("${endpoint.host}\n\nUse only a service you trust. It will connect your calls and issue temporary relay credentials.")
                .setNegativeButton("Cancel",null).setPositiveButton("Activate"){_,_->settings.edit().putString("origin",origin.trimEnd('/')).putString("key",key).apply();notice("Internet calls activated. Tap Start a call.")}.show()
        }catch(_:Exception){notice("Invalid activation link. Copy the complete link, including the part after #.")}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);receiveActivation(intent)}
    private fun resumeAr(){
        if(renderer.resumed||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return
        try {
            if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;return}
            val session=ar?:Session(this).also { created->
                val configs=created.getSupportedCameraConfigs(CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
                val camera=configs.filter{it.imageSize.width<=1280&&it.imageSize.height<=1280}.maxByOrNull{it.imageSize.width*it.imageSize.height}
                    ?:configs.minByOrNull{it.imageSize.width*it.imageSize.height}
                if(camera!=null)created.cameraConfig=camera
                created.configure(Config(created).apply {focusMode=Config.FocusMode.AUTO;depthMode=if(created.isDepthModeSupported(Config.DepthMode.AUTOMATIC))Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED;cloudAnchorMode=Config.CloudAnchorMode.DISABLED})
                renderer.imageRotation=getSystemService(CameraManager::class.java).getCameraCharacteristics(created.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)?:90
                ar=created
            }
            session.resume();renderer.arSession=session;renderer.resumed=true;gl.onResume()
        }catch(e:Exception){notice("Camera setup: ${e.message?:e.javaClass.simpleName}")}
    }
    override fun onResume(){super.onResume();foreground=true;resumeAr()
        if(resumeSharing&&state.active){state.paused=false;voice.setMuted(micMuted)};resumeSharing=false
        pendingApproval?.let{pendingApproval=null;requestApproval(it.first,it.second)}
    }
    override fun onPause(){
        foreground=false;resumeSharing=state.active&&!state.paused
        if(state.active){state.paused=true;state.frames.clear();state.videoFrames.clear();voice.setMuted(true)}
        renderer.resumed=false;gl.onPause();runCatching{ar?.pause()};super.onPause()
    }
    override fun onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);remote?.stop();state.end();server?.stop()
        gl.queueEvent {renderer.releaseGlResources()};voice.close();renderer.close();runCatching{ar?.close()};io.shutdown();super.onDestroy()}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==71){val local=pendingLocal?:false;pendingLocal=null;continueStart(local)}
        if(code==72&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){micMuted=false;state.voiceEnabled=true;voice.setMuted(false);voice.disconnect();notice("Microphone enabled. Reconnecting audio.")}
    }
    private fun notice(message:String){if(destroyed)return;runOnUiThread {
        if(!::noticeText.isInitialized)return@runOnUiThread
        if(home.visibility==View.VISIBLE)Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        noticeText.text=message;noticeText.visibility=View.VISIBLE;handler.postDelayed({if(noticeText.text.toString()==message)noticeText.visibility=View.GONE},4500)
    }}
    private fun label(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {text=value;textSize=size;setTextColor(color);typeface=Typeface.create(if(bold)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)}
    private fun button(value:String,primary:Boolean=false,color:Int=Color.WHITE,action:()->Unit)=label(value,13f,if(primary)ink else color,true).apply {
        gravity=Gravity.CENTER;setPadding(d(10),d(10),d(10),d(10));minHeight=d(44);background=rounded(if(primary)accent else Color.TRANSPARENT,6)
        isClickable=true;isFocusable=true;contentDescription=value;setOnClickListener {performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);action()}
    }
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=d(radius).toFloat()}
    private fun d(value:Int)=(value*resources.displayMetrics.density).toInt()
}
