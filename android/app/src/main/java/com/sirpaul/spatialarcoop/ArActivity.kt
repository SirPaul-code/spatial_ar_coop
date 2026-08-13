package com.sirpaul.spatialarcoop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.sirpaul.spatialarcoop.ar.ArSessionState
import com.sirpaul.spatialarcoop.ar.ArSessionStateMachine
import com.sirpaul.spatialarcoop.ar.CameraBackgroundRenderer
import com.sirpaul.spatialarcoop.ar.CloudAnchorCoordinator
import com.sirpaul.spatialarcoop.ar.DisplayRotationHelper
import com.sirpaul.spatialarcoop.ar.PointCloudRecorder
import com.sirpaul.spatialarcoop.ar.PoseMath
import com.sirpaul.spatialarcoop.ar.PoseSkeletonBuilder
import com.sirpaul.spatialarcoop.ar.RemoteTrackStore
import com.sirpaul.spatialarcoop.ar.SpatialEstimator
import com.sirpaul.spatialarcoop.ar.TerrainHeightMap
import com.sirpaul.spatialarcoop.data.AnchorStatus
import com.sirpaul.spatialarcoop.data.FeatureQuality
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.data.MapStatus
import com.sirpaul.spatialarcoop.data.SpatialTrack
import com.sirpaul.spatialarcoop.net.MapApiClient
import com.sirpaul.spatialarcoop.net.RealtimeClient
import com.sirpaul.spatialarcoop.net.RealtimeListener
import com.sirpaul.spatialarcoop.net.RemoteClientPose
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.ui.FieldTheme
import com.sirpaul.spatialarcoop.ui.OffscreenIndicatorMath
import com.sirpaul.spatialarcoop.ui.ProjectedBox
import com.sirpaul.spatialarcoop.ui.ProjectedCuboid
import com.sirpaul.spatialarcoop.ui.ProjectedJoint
import com.sirpaul.spatialarcoop.ui.ProjectedPose
import com.sirpaul.spatialarcoop.ui.ProjectedParticipant
import com.sirpaul.spatialarcoop.ui.ProjectedParticipantGizmo
import com.sirpaul.spatialarcoop.ui.ProjectedPoint
import com.sirpaul.spatialarcoop.ui.ProjectedSkeleton
import com.sirpaul.spatialarcoop.ui.ProjectedTrack
import com.sirpaul.spatialarcoop.ui.ScanOverlayState
import com.sirpaul.spatialarcoop.ui.SpatialOverlayView
import com.sirpaul.spatialarcoop.util.Diagnostics
import com.sirpaul.spatialarcoop.vision.CaptureGeometry
import com.sirpaul.spatialarcoop.vision.DepthSnapshot
import com.sirpaul.spatialarcoop.vision.Detection2D
import com.sirpaul.spatialarcoop.vision.DetectorRuntimeState
import com.sirpaul.spatialarcoop.vision.DetectionTracker
import com.sirpaul.spatialarcoop.vision.ObjectDetectorEngine
import com.sirpaul.spatialarcoop.vision.SpatialObservation
import com.sirpaul.spatialarcoop.vision.YuvFrame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer, RealtimeListener {
    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)
    private enum class ArSessionProfile { STANDARD, COMPATIBILITY }

    private lateinit var mapId: String
    private lateinit var mode: ArMode
    private lateinit var glSurface: GLSurfaceView
    private lateinit var overlay: SpatialOverlayView
    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var networkText: TextView
    private lateinit var displayRotation: DisplayRotationHelper
    private lateinit var backgroundRenderer: CameraBackgroundRenderer
    private lateinit var arErrorPanel: LinearLayout
    private lateinit var arErrorText: TextView
    private var reportButton: Button? = null
    private var participantsButton: Button? = null
    private var finishSetupButton: Button? = null
    private var retryArButton: Button? = null

    @Volatile private var session: Session? = null
    private val arState = ArSessionStateMachine()
    private val closing = AtomicBoolean(false)
    private val arStartScheduled = AtomicBoolean(false)
    private val cameraPermissionRequestInFlight = AtomicBoolean(false)
    private val sessionCloseInFlight = AtomicBoolean(false)
    @Volatile private var activityResumed = false
    @Volatile private var glResumed = false
    @Volatile private var arProfile = ArSessionProfile.STANDARD
    private var compatibilityFallbackUsed = false
    private var installRequested = false
    private var textureBound = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var resolveLastAttemptMs = 0L
    private var lastHudAtMs = 0L
    private var lastPoseSentAtMs = 0L
    private var lastDetectionCaptureAtMs = 0L
    private var lastTrackPublishAtMs = 0L
    private var lastDetectorStatusAtMs = 0L
    private var latestInferenceMs = 0L
    private var latestLocalTrackCount = 0
    private var latestSpatializedCount = 0
    private var latestPoseCount = 0
    private var latestSentTrackCount = 0
    @Volatile private var lastAckAccepted = -1
    @Volatile private var lastAckSequence = -1L
    @Volatile private var lastAckAtMs = 0L
    private var lastFeatureQualityAtMs = 0L
    private var lastScanHudAtMs = 0L
    private var lastMapRefreshAtMs = 0L
    private var featureQuality = FeatureQuality.UNKNOWN
    private var mappingFinished = false
    private var setupOriginAutoEstablished = false
    private var lastAutoGroundAttemptMs = 0L
    @Volatile private var realtimeConnected = false
    @Volatile private var localizationDetail = ""
    private var sequence = 0L

    private val cachedMap = AtomicReference<MapDefinition?>(null)
    private val manualWorldFromSite = AtomicReference<FloatArray?>(null)
    @Volatile private var manualAlignmentOverride = false
    private val pendingDetection = AtomicReference<PendingDetection?>(null)
    private val remoteTracks = RemoteTrackStore()
    private val remoteClientPoses = ConcurrentHashMap<String, RemoteClientPose>()
    private val terrainModel = AtomicReference<TerrainHeightMap?>(null)
    private val terrainLoadStarted = AtomicBoolean(false)
    private val terrainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "spatial-terrain-loader") }
    @Volatile private var detectorRuntimeState: DetectorRuntimeState? = null
    private lateinit var localTracker: DetectionTracker
    private var detector: ObjectDetectorEngine? = null
    @Volatile private var reporting = false
    @Volatile private var showParticipants = false
    @Volatile private var latestDetectionCount = 0
    private var realtime: RealtimeClient? = null
    private var cloudAnchors: CloudAnchorCoordinator? = null
    private var pointRecorder: PointCloudRecorder? = null

    private val requestHost = AtomicBoolean(false)
    private val requestRetryAnchor = AtomicBoolean(false)
    private val requestGround = AtomicBoolean(false)
    private val requestMarker = AtomicBoolean(false)
    private val requestFinish = AtomicBoolean(false)
    private val requestRelocalize = AtomicBoolean(false)
    private val requestManualAlign = AtomicBoolean(false)

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var viewProjectionMatrix = PoseMath.identity()

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionRequestInFlight.set(false)
        if (granted) {
            // Do not call Session.resume() from the permission-result dispatch itself. On some
            // Android 16/Samsung builds that callback can race the Activity's next onResume and
            // camera-service settling. Schedule exactly one normal lifecycle start instead.
            showDetail("Camera permission granted · starting AR…")
            scheduleArStart(PERMISSION_SETTLE_DELAY_MS)
        } else {
            spatialApp.logger.warn("Camera permission denied")
            showDetail("Camera permission is required")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mapId = intent.getStringExtra(EXTRA_MAP_ID) ?: run { finish(); return }
        mode = runCatching { ArMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: ArMode.VIEWER.name) }
            .getOrDefault(ArMode.VIEWER)
        if (mode == ArMode.MAP) spatialApp.database.recoverInterruptedAnchors(mapId)
        val loadedMap = spatialApp.database.getMap(mapId)
        if (loadedMap == null) {
            spatialApp.logger.error("AR activity opened with missing map", fields = mapOf("mapId" to mapId))
            finish()
            return
        }
        cachedMap.set(loadedMap)
        lastMapRefreshAtMs = System.currentTimeMillis()
        localTracker = DetectionTracker(spatialApp.preferences.deviceId)
        displayRotation = DisplayRotationHelper(this)
        setContentView(buildUi())
        configureModeComponents()
        onBackPressedDispatcher.addCallback(this) { finishSafely() }
        spatialApp.logger.info("AR mode opened", mapOf("mapId" to mapId, "mode" to mode.name))
    }

    private fun configureModeComponents() {
        val map = currentMap() ?: return
        val wireRole = when (mode) {
            ArMode.MAP -> "mapper"
            ArMode.SENSOR -> "sensor"
            ArMode.VIEWER -> "viewer"
            ArMode.LIVE -> "participant"
        }
        realtime = RealtimeClient(
            serverUrl = map.serverUrl,
            apiToken = map.accessKey.ifBlank { spatialApp.preferences.apiToken },
            mapId = map.id,
            clientId = spatialApp.preferences.deviceId,
            role = wireRole,
            logger = spatialApp.logger,
            listener = this
        )
        if (mode == ArMode.SENSOR || mode == ArMode.LIVE) {
            loadTerrainAsync(map)
            reporting = true
            ensureDetector()
            if (mode == ArMode.LIVE) {
                spatialApp.logger.info("Live automatic detection enabled", mapOf("mapId" to mapId))
            }
        }
        if (mode == ArMode.MAP) {
            val (chunks, points) = spatialApp.database.chunkCounts(mapId)
            updateScanOverlay(chunks, points)
        }
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(FieldTheme.background) }
        glSurface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(this@ArActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glSurface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = SpatialOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(Color.argb(210, 23, 24, 26))
        }
        stateText = TextView(this).apply {
            text = "${currentMap()?.name ?: mapId} · Starting AR"
            setTextColor(FieldTheme.textPrimary)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        detailText = TextView(this).apply {
            text = "Preparing camera and localization…"
            setTextColor(FieldTheme.textSecondary)
            textSize = 12f
        }
        networkText = TextView(this).apply {
            text = "Server disconnected"
            setTextColor(FieldTheme.accent)
            textSize = 11f
        }
        top.addView(stateText)
        top.addView(detailText)
        top.addView(networkText)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.argb(224, 23, 24, 26))
        }
        actions.addView(action("Back") { finishSafely() })
        when (mode) {
            ArMode.MAP -> {
                finishSetupButton = action("Finish setup") {
                    showDetail("Checking map readiness…")
                    requestFinish.set(true)
                }.also { button ->
                    button.isEnabled = false
                    button.alpha = 0.48f
                    actions.addView(button)
                }
                actions.addView(action("More") { showMapSetupMenu() })
            }
            ArMode.LIVE -> {
                participantsButton = action("Clients: Off") {
                    setParticipantOverlayVisible(!showParticipants)
                }.also(actions::addView)
                actions.addView(action("More") { showLiveMenu() })
            }
            ArMode.SENSOR, ArMode.VIEWER -> actions.addView(action("More") { showLiveMenu() })
        }
        scroll.addView(actions)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        arErrorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(FieldTheme.surfaceRaised)
            visibility = View.GONE
        }
        arErrorText = TextView(this).apply {
            setTextColor(FieldTheme.textPrimary)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        arErrorPanel.addView(arErrorText, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT))
        val errorActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        retryArButton = action("Retry AR") { retryArSession() }.also(errorActions::addView)
        errorActions.addView(action("Back") { finishSafely() })
        errorActions.addView(action("Diagnostics") { Diagnostics.shareLogs(this, spatialApp.logger) })
        arErrorPanel.addView(errorActions)
        root.addView(arErrorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    override fun onStart() {
        super.onStart()
        if (closing.get()) return
        UploadScheduler.enqueue(this)
        realtime?.connect()
    }

    override fun onStop() {
        realtime?.close()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (closing.get()) return
        activityResumed = true
        displayRotation.onResume()
        scheduleArStart()
    }

    private fun createConfiguredSession(profile: ArSessionProfile): Session {
        val created = Session(this)
        try {
            val config = Config(created).apply {
                cloudAnchorMode = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) Config.CloudAnchorMode.ENABLED else Config.CloudAnchorMode.DISABLED
                when (profile) {
                    ArSessionProfile.STANDARD -> {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
                        depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    }
                    ArSessionProfile.COMPATIBILITY -> {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        depthMode = Config.DepthMode.DISABLED
                    }
                }
            }
            created.configure(config)
            cloudAnchors = CloudAnchorCoordinator(
                session = created,
                mapId = mapId,
                database = spatialApp.database,
                logger = spatialApp.logger,
                scheduleUpload = { UploadScheduler.enqueue(this) },
                onState = { message ->
                    localizationDetail = message
                    showDetail(message)
                }
            )
            session = created
            spatialApp.logger.info(
                "ARCore session configured",
                mapOf(
                    "mapId" to mapId,
                    "profile" to profile.name,
                    "cloudAnchors" to BuildConfig.CLOUD_ANCHORS_CONFIGURED,
                    "depthSupported" to created.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
                    "depthEnabled" to (config.depthMode == Config.DepthMode.AUTOMATIC)
                )
            )
            return created
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        }
    }

    private fun scheduleArStart(delayMs: Long = 0L) {
        if (closing.get() || !activityResumed) return
        if (!arStartScheduled.compareAndSet(false, true)) return
        glSurface.postDelayed({
            arStartScheduled.set(false)
            if (!closing.get() && activityResumed) resumeArSession(explicitRetry = false)
        }, delayMs.coerceAtLeast(0L))
    }

    private fun resumeArSession(explicitRetry: Boolean = false) {
        if (closing.get() || !activityResumed) return
        if (sessionCloseInFlight.get()) {
            showDetail("Cleaning up the previous AR session…")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (cameraPermissionRequestInFlight.compareAndSet(false, true)) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }
        if (cameraPermissionRequestInFlight.get()) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    showDetail("Finish Google Play Services for AR installation, then return here")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                null -> error("ARCore installation check returned no status")
            }
            val accepted = if (explicitRetry) arState.beginRetryStart() else arState.beginStart()
            if (!accepted) return
            val active = session ?: createConfiguredSession(arProfile)
            active.resume()
            textureBound = false
            if (!arState.markRunning()) {
                runCatching { active.pause() }
                return
            }
            hideArFailure()
            retryArButton?.isEnabled = true
            resumeGlIfNeeded()
            showDetail(
                when (mode) {
                    ArMode.MAP -> "AR ready · preparing shared coordinates…"
                    ArMode.LIVE -> "AR ready · resolving this place…"
                    else -> "AR ready · resolving shared location…"
                }
            )
        } catch (error: Throwable) {
            failArSession(error, "resume")
        }
    }

    private fun retryArSession() {
        if (closing.get() || !activityResumed || arState.current() != ArSessionState.FAILED) return
        if (sessionCloseInFlight.get()) {
            retryArButton?.isEnabled = false
            showDetail("Cleaning up the previous AR session · retry will be available when it is closed")
            return
        }
        hideArFailure()
        resumeArSession(explicitRetry = true)
    }

    private fun resumeGlIfNeeded() {
        if (glResumed || closing.get() || !arState.canRender()) return
        glSurface.onResume()
        glResumed = true
    }

    private fun pauseGlIfNeeded() {
        if (!glResumed) return
        glResumed = false
        glSurface.onPause()
    }

    private fun failArSession(error: Throwable, phase: String) {
        if (!arState.fail()) return
        textureBound = false
        val failedSession = session
        session = null
        val failedCoordinator = cloudAnchors
        cloudAnchors = null
        val profileAtFailure = arProfile
        val availability = runCatching { ArCoreApk.getInstance().checkAvailability(this).name }.getOrDefault("unknown")
        val arcoreVersion = runCatching { packageManager.getPackageInfo("com.google.ar.core", 0).versionName }.getOrNull()
        spatialApp.logger.error(
            "ARCore session failed",
            error,
            mapOf(
                "phase" to phase,
                "exception" to error.javaClass.name,
                "message" to error.message,
                "arState" to arState.current().name,
                "profile" to profileAtFailure.name,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "arcoreAvailability" to availability,
                "arcoreVersion" to arcoreVersion,
                "cloudAnchorsConfigured" to BuildConfig.CLOUD_ANCHORS_CONFIGURED
            )
        )
        runOnUiThread {
            pauseGlIfNeeded()
            failedCoordinator?.close()
            runCatching { failedSession?.pause() }
            val autoCompatibility = error is FatalException &&
                phase == "resume" &&
                profileAtFailure == ArSessionProfile.STANDARD &&
                !compatibilityFallbackUsed

            retryArButton?.isEnabled = false
            showArFailure(error)
            if (autoCompatibility) {
                detailText.text = "ARCore returned an internal camera/session error · cleaning up before one compatibility retry"
            } else if (failedSession != null) {
                detailText.text = "Cleaning up the failed AR session…"
            }

            closeSessionAsync(failedSession) {
                if (closing.get()) return@closeSessionAsync
                if (autoCompatibility && activityResumed) {
                    compatibilityFallbackUsed = true
                    arProfile = ArSessionProfile.COMPATIBILITY
                    arErrorPanel.visibility = View.GONE
                    detailText.text = "Retrying AR with a compatibility camera profile · Cloud Anchors stay enabled"
                    glSurface.postDelayed({
                        if (!closing.get() && activityResumed && arState.current() == ArSessionState.FAILED) {
                            retryArSession()
                        }
                    }, ARCORE_RETRY_SETTLE_DELAY_MS)
                } else {
                    retryArButton?.isEnabled = true
                    showArFailure(error)
                }
            }
        }
    }

    private fun showArFailure(error: Throwable) {
        val category = when (error) {
            is FatalException -> "Google Play Services for AR returned an internal session error. The failed native session has been discarded."
            is CameraNotAvailableException -> "The camera is unavailable to ARCore. Close other camera users and retry."
            is SessionPausedException -> "The AR session became paused unexpectedly. A fresh session is required."
            is SecurityException -> "Camera access was rejected by Android. Check camera permission and retry."
            is IllegalStateException -> "ARCore rejected the current lifecycle state. Retry creates a clean session."
            else -> when (error.javaClass.simpleName) {
                "UnavailableArcoreNotInstalledException" -> "Google Play Services for AR is not installed."
                "UnavailableApkTooOldException" -> "Google Play Services for AR must be updated."
                "UnavailableSdkTooOldException" -> "This app build is too old for the installed ARCore service."
                "UnavailableDeviceNotCompatibleException" -> "ARCore reports that this device/configuration is unsupported."
                "UnavailableUserDeclinedInstallationException" -> "ARCore installation was declined."
                else -> "AR session could not start (${error.javaClass.simpleName})."
            }
        }
        arErrorText.text = "AR session could not start\n\n$category"
        arErrorPanel.visibility = View.VISIBLE
        stateText.text = "${currentMap()?.name ?: mapId} · AR unavailable"
        if (!sessionCloseInFlight.get()) {
            detailText.text = if (arProfile == ArSessionProfile.COMPATIBILITY) {
                "Compatibility profile also failed · Retry AR or share diagnostics"
            } else {
                "Retry AR or share diagnostics for the complete error"
            }
        }
    }

    private fun hideArFailure() {
        runOnUiThread { arErrorPanel.visibility = View.GONE }
    }

    private fun closeSessionAsync(value: Session?, onClosed: () -> Unit = {}) {
        if (value == null) {
            onClosed()
            return
        }
        if (!sessionCloseInFlight.compareAndSet(false, true)) {
            spatialApp.logger.warn("ARCore close already in progress", mapOf("mapId" to mapId))
            return
        }
        Thread({
            val failure = runCatching { value.close() }.exceptionOrNull()
            runOnUiThread {
                sessionCloseInFlight.set(false)
                if (failure != null) {
                    spatialApp.logger.warn("ARCore session close failed", mapOf("mapId" to mapId, "error" to failure.message))
                } else {
                    spatialApp.logger.info("ARCore session closed", mapOf("mapId" to mapId))
                }
                onClosed()
            }
        }, "spatial-arcore-close").start()
    }

    override fun onPause() {
        activityResumed = false
        if (!closing.get()) {
            arState.beginPause()
            pauseGlIfNeeded()
            displayRotation.onPause()
            pointRecorder?.flush()
            runCatching { session?.pause() }
            arState.markPaused()
        }
        super.onPause()
    }

    private fun finishSafely() {
        teardown(finishActivity = true)
    }

    private fun teardown(finishActivity: Boolean) {
        if (!closing.compareAndSet(false, true)) {
            if (finishActivity && !isFinishing) finish()
            return
        }
        activityResumed = false
        arStartScheduled.set(false)
        arState.beginClosing()
        retryArButton?.isEnabled = false
        spatialApp.logger.info("AR teardown begin", mapOf("mapId" to mapId, "mode" to mode.name))
        pauseGlIfNeeded()
        runCatching { displayRotation.onPause() }
        pointRecorder?.flush()
        pointRecorder?.stop()
        pointRecorder = null
        stopDetector()
        realtime?.close()
        realtime = null
        remoteClientPoses.clear()
        if (::overlay.isInitialized) overlay.updateParticipants(emptyList())
        cloudAnchors?.close()
        cloudAnchors = null
        terrainModel.set(null)
        terrainExecutor.shutdownNow()
        val closingSession = session
        session = null
        runCatching { closingSession?.pause() }
        closeSessionAsync(closingSession)
        arState.markClosed()
        spatialApp.logger.info("AR teardown complete", mapOf("mapId" to mapId, "mode" to mode.name))
        if (finishActivity && !isFinishing) finish()
    }

    override fun onDestroy() {
        teardown(finishActivity = false)
        super.onDestroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = CameraBackgroundRenderer(this).also { it.createOnGlThread() }
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        displayRotation.onSurfaceChanged(viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!arState.canRender()) return
        val active = session ?: return
        if (!::backgroundRenderer.isInitialized) return
        try {
            if (!textureBound) {
                active.setCameraTextureNames(intArrayOf(backgroundRenderer.textureId))
                textureBound = true
            }
            displayRotation.updateSessionIfNeeded(active)
            if (!arState.canRender()) return
            val frame = active.update()
            backgroundRenderer.updateDisplayGeometry(frame)
            backgroundRenderer.draw()
            renderArFrame(frame)
        } catch (error: SessionPausedException) {
            failArSession(error, "frame-update-paused")
        } catch (error: CameraNotAvailableException) {
            failArSession(error, "frame-camera")
        } catch (error: FatalException) {
            failArSession(error, "frame-fatal")
        } catch (error: Throwable) {
            failArSession(error, "frame")
        }
    }

    private fun renderArFrame(frame: Frame) {
        val camera = frame.camera
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.05f, 150f)
        viewProjectionMatrix = PoseMath.multiply(projectionMatrix, viewMatrix)
        if (camera.trackingState != TrackingState.TRACKING) {
            updateHud(frame, null, "Tracking ${camera.trackingState}: ${camera.trackingFailureReason}")
            return
        }

        if ((mode == ArMode.LIVE || mode == ArMode.SENSOR) && reporting) {
            captureDetectorFrame(frame)
            pendingDetection.get()?.let { pending ->
                overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))
                overlay.updateLocalPoses(projectDetectionPoses(frame, pending.detections))
            }
        }

        var map = currentMap() ?: return
        val hostedBefore = map.anchors.any { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }

        if (mode == ArMode.MAP && manualWorldFromSite.get() == null && cloudAnchors?.currentWorldFromSite() == null && !hostedBefore) {
            val (existingChunks, _) = spatialApp.database.chunkCounts(mapId)
            if (existingChunks == 0 && map.anchors.isEmpty()) {
                val automaticOrigin = PoseMath.horizontalOrigin(camera.pose)
                if (manualWorldFromSite.compareAndSet(null, automaticOrigin)) {
                    setupOriginAutoEstablished = true
                    resolveLastAttemptMs = System.currentTimeMillis()
                    showDetail("Shared origin ready · walk slowly around the area; scanning is automatic")
                    spatialApp.logger.info("Map origin established automatically", mapOf("mapId" to mapId))
                }
            }
        }

        if (requestManualAlign.getAndSet(false)) {
            manualWorldFromSite.set(PoseMath.horizontalOrigin(camera.pose))
            manualAlignmentOverride = mode != ArMode.MAP
            setupOriginAutoEstablished = false
            resolveLastAttemptMs = System.currentTimeMillis()
            showDetail(
                if (mode == ArMode.MAP) {
                    "Shared origin re-established here. Only use this recovery action at the original map start position and heading."
                } else {
                    "Fallback alignment active from this position and heading."
                }
            )
            spatialApp.logger.info(
                "Manual site alignment set",
                mapOf("mapId" to mapId, "mode" to mode.name, "override" to manualAlignmentOverride)
            )
        }
        if (requestRelocalize.getAndSet(false)) {
            manualWorldFromSite.set(null)
            manualAlignmentOverride = false
            cloudAnchors?.resetReference()
            resolveLastAttemptMs = 0L
            localizationDetail = "Re-localizing · point around a mapped anchor area and move slowly"
            showDetail(localizationDetail)
        }

        val cloudWorldFromSite = cloudAnchors?.currentWorldFromSite()
        val manualAlignment = manualWorldFromSite.get()
        val worldFromSite = if (manualAlignmentOverride) manualAlignment else cloudWorldFromSite ?: manualAlignment
        if (worldFromSite == null) {
            val hosted = map.anchors.any { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }
            val now = System.currentTimeMillis()
            if (hosted && now - resolveLastAttemptMs > RESOLVE_RETRY_MS) {
                resolveLastAttemptMs = now
                cloudAnchors?.resolveMap(map)
            }
        }
        if (worldFromSite == null) {
            val hosted = map.anchors.any { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }
            val (existingChunks, _) = spatialApp.database.chunkCounts(mapId)
            val instruction = when {
                mode == ArMode.MAP && !hosted && existingChunks > 0 ->
                    "This unfinished map has no usable Cloud Anchor. Stand at its original start position and use More → Re-establish shared origin."
                mode == ArMode.MAP -> "Preparing shared coordinates · hold the phone steady and look around"
                BuildConfig.CLOUD_ANCHORS_CONFIGURED && hosted ->
                    "Resolving shared location · point around the mapped area and move slowly"
                else -> "Shared location unavailable · use More → Align fallback at the saved physical origin"
            }
            updateHud(frame, null, if (mode == ArMode.LIVE) null else instruction)
            overlay.updateTracks(emptyList())
            overlay.updateParticipants(emptyList())
            return
        }

        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val cameraSite = PoseMath.transformPoint(siteFromWorld, camera.pose.translation)
        map = currentMap() ?: map

        handleRequests(frame, cameraSite, worldFromSite, map)
        when (mode) {
            ArMode.MAP -> updateMapping(frame, worldFromSite, map)
            ArMode.SENSOR -> updateSensor(frame, worldFromSite, map)
            ArMode.LIVE -> if (reporting) updateSensor(frame, worldFromSite, map) else overlay.updateLocalBoxes(emptyList())
            ArMode.VIEWER -> overlay.updateLocalBoxes(emptyList())
        }
        updateProjectedTracks(cameraSite, worldFromSite)
        updateProjectedParticipants(cameraSite, worldFromSite)
        publishClientPose(frame, siteFromWorld)
        updateHud(frame, worldFromSite, null)
    }

    private fun updateMapping(frame: Frame, worldFromSite: FloatArray, map: MapDefinition) {
        if (!mappingFinished) {
            if (pointRecorder == null) {
                pointRecorder = PointCloudRecorder(
                    context = this,
                    mapId = mapId,
                    database = spatialApp.database,
                    logger = spatialApp.logger,
                    onProgress = ::updateScanOverlay
                )
            }
            pointRecorder?.capture(frame, PoseMath.rigidInverse(worldFromSite))
        }
        val now = System.currentTimeMillis()
        if (now - lastFeatureQualityAtMs >= FEATURE_QUALITY_INTERVAL_MS) {
            lastFeatureQualityAtMs = now
            featureQuality = cloudAnchors?.featureQuality(frame.camera.pose) ?: FeatureQuality.UNKNOWN
        }

        if (!mappingFinished && map.groundY == null && now - lastAutoGroundAttemptMs >= AUTO_GROUND_INTERVAL_MS) {
            lastAutoGroundAttemptMs = now
            val candidate = SpatialEstimator.centerGroundPoint(frame, worldFromSite, null)
            if (candidate != null && candidate[1] in -3.0f..-0.25f) {
                spatialApp.database.updateMapRuntime(mapId, groundY = candidate[1])
                currentMap(forceRefresh = true)
                UploadScheduler.enqueue(this)
                showDetail("Floor detected automatically · keep walking slowly to map the area")
                spatialApp.logger.info("Ground plane detected automatically", mapOf("mapId" to mapId, "groundY" to candidate[1]))
            }
        }

        if (!mappingFinished) cloudAnchors?.considerAutoHost(frame.camera.pose, worldFromSite, currentMap() ?: map)
        if (now - lastScanHudAtMs >= SCAN_HUD_INTERVAL_MS) {
            lastScanHudAtMs = now
            val (chunks, points) = spatialApp.database.chunkCounts(mapId)
            updateScanOverlay(chunks, points)
        }
    }

    private fun updateSensor(frame: Frame, worldFromSite: FloatArray, map: MapDefinition) {
        pendingDetection.getAndSet(null)?.let { pending ->
            val rejected = linkedMapOf<String, Int>()
            val observations = pending.detections.mapNotNull { detection ->
                val attempt = SpatialEstimator.estimateDetailed(
                    frame, detection, worldFromSite, map.groundY, terrainModel.get()
                )
                val estimate = attempt.estimate
                if (estimate == null) {
                    val reason = attempt.rejectionReason ?: "unknown"
                    rejected[reason] = (rejected[reason] ?: 0) + 1
                    null
                } else {
                    val poseJoints = detection.captureGeometry?.let { geometry ->
                        PoseSkeletonBuilder.build(detection, estimate.sitePosition, geometry)
                    }.orEmpty()
                    SpatialObservation(
                        label = detection.label,
                        confidence = detection.confidence,
                        position = estimate.sitePosition,
                        observedAtMs = detection.capturedAtMs,
                        uncertaintyMeters = estimate.uncertaintyMeters,
                        associationKey = detection.temporalId,
                        extentMeters = estimate.extentMeters,
                        yawRadians = estimate.yawRadians,
                        requiredHits = estimate.requiredHits,
                        poseJoints = poseJoints,
                        spatialMethod = estimate.method,
                        terrainY = estimate.terrainY,
                        depthConfidence = estimate.depthConfidence
                    )
                }
            }
            val tracks = localTracker.update(observations)
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            val publishSequence = sequence++
            val queued = realtime?.sendTracks(publishSequence, tracks) == true
            val now = System.currentTimeMillis()
            lastTrackPublishAtMs = now
            latestInferenceMs = pending.inferenceMs
            latestSpatializedCount = observations.size
            latestLocalTrackCount = tracks.size
            latestSentTrackCount = if (queued) tracks.size else 0
            if (now - lastDetectorStatusAtMs >= DETECTOR_STATUS_INTERVAL_MS) {
                lastDetectorStatusAtMs = now
                val methods = observations.groupingBy { it.spatialMethod }.eachCount()
                    .entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }
                realtime?.sendStatus(
                    "detecting",
                    "${pending.detections.size} detected, ${observations.size} spatialized, ${tracks.size} active, ${pending.inferenceMs}ms${if (methods.isBlank()) "" else ", $methods"}"
                )
                if (rejected.isNotEmpty()) {
                    spatialApp.logger.debug(
                        "Spatialization rejected detections",
                        mapOf("mapId" to mapId, "reasons" to rejected.entries.joinToString { "${it.key}=${it.value}" })
                    )
                }
            }
            overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))
            overlay.updateLocalPoses(projectDetectionPoses(frame, pending.detections))
        }

        val now = System.currentTimeMillis()
        if (now - lastTrackPublishAtMs >= TRACK_PUBLISH_INTERVAL_MS) {
            val tracks = localTracker.current(now)
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            val queued = realtime?.sendTracks(sequence++, tracks) == true
            latestLocalTrackCount = tracks.size
            latestSentTrackCount = if (queued) tracks.size else 0
            lastTrackPublishAtMs = now
        }
    }

    private fun loadTerrainAsync(map: MapDefinition) {
        if (!terrainLoadStarted.compareAndSet(false, true)) return
        terrainExecutor.execute {
            try {
                val credential = map.accessKey.ifBlank { spatialApp.preferences.apiToken }
                val preview = MapApiClient(map.serverUrl, credential, spatialApp.logger)
                    .getPointCloudPreview(map.id, TERRAIN_POINT_LIMIT)
                val model = TerrainHeightMap.fromPoints(preview.points, map.groundY)
                if (!closing.get()) terrainModel.set(model)
                spatialApp.logger.info(
                    "Terrain model loaded",
                    mapOf(
                        "mapId" to map.id,
                        "sourcePoints" to preview.sampledPoints,
                        "serverPoints" to preview.totalPoints,
                        "cells" to (model?.cellCount ?: 0),
                        "elevation" to model?.elevationRangeMeters?.let { "${it.start}..${it.endInclusive}" }
                    )
                )
            } catch (error: Throwable) {
                spatialApp.logger.warn(
                    "Terrain model unavailable; spatializer will use depth/legacy fallbacks",
                    mapOf("mapId" to map.id, "error" to (error.message ?: error.javaClass.simpleName))
                )
            }
        }
    }

    private fun captureDetectorFrame(frame: Frame) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionCaptureAtMs < DETECTION_INTERVAL_MS) return
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            null
        } ?: return
        try {
            val yuv = YuvFrame.copyOf(image)
            val cameraId = session?.cameraConfig?.cameraId
            val rotation = if (cameraId == null) {
                0
            } else {
                runCatching { displayRotation.cameraSensorToDisplayRotation(cameraId) }.getOrDefault(0)
            }
            val intrinsics = frame.camera.imageIntrinsics
            val manualAlignment = manualWorldFromSite.get()
            val captureWorldFromSite = if (manualAlignmentOverride) {
                manualAlignment
            } else {
                cloudAnchors?.currentWorldFromSite() ?: manualAlignment
            }
            val siteFromCamera = captureWorldFromSite?.let { worldFromSite ->
                PoseMath.multiply(
                    PoseMath.rigidInverse(worldFromSite),
                    PoseMath.poseToMatrix(frame.camera.pose)
                )
            }
            val captureGeometry = CaptureGeometry(
                siteFromCamera = siteFromCamera,
                focalLength = intrinsics.focalLength.copyOf(),
                principalPoint = intrinsics.principalPoint.copyOf(),
                depthSnapshot = if (siteFromCamera != null) DepthSnapshot.capture(frame) else null
            )
            if (detector?.submit(yuv, rotation, now, captureGeometry) == true) lastDetectionCaptureAtMs = now
        } finally {
            image.close()
        }
    }

    private fun ensureDetector() {
        if (detector != null) return
        detector = ObjectDetectorEngine(
            context = this,
            threshold = spatialApp.preferences.detectorThreshold,
            logger = spatialApp.logger,
            onResult = { values, inferenceMs ->
                latestDetectionCount = values.size
                latestPoseCount = values.count { detection ->
                    detection.label.equals("person", true) && detection.poseLandmarks.size >= 8
                }
                latestInferenceMs = inferenceMs
                pendingDetection.set(PendingDetection(values, inferenceMs))
            },
            onError = { message -> showDetail("Detector error: $message") },
            onRuntimeState = { state -> detectorRuntimeState = state }
        )
    }

    private fun stopDetector() {
        pendingDetection.set(null)
        detector?.close()
        detector = null
        detectorRuntimeState = null
        latestLocalTrackCount = 0
        latestSpatializedCount = 0
        latestSentTrackCount = 0
        latestDetectionCount = 0
        latestPoseCount = 0
        latestInferenceMs = 0
        lastAckAccepted = -1
        lastAckSequence = -1L
        lastAckAtMs = 0L
        if (::overlay.isInitialized) {
            overlay.updateLocalBoxes(emptyList())
            overlay.updateLocalPoses(emptyList())
        }
    }

    private fun setReporting(enabled: Boolean) {
        if (mode != ArMode.LIVE) return
        reporting = enabled
        if (enabled) {
            lastTrackPublishAtMs = 0L
            ensureDetector()
        } else {
            stopDetector()
            localTracker.clear()
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, emptyList())
            realtime?.sendTracks(sequence++, emptyList())
            lastTrackPublishAtMs = 0L
        }
        reportButton?.text = if (enabled) "Stop reporting" else "Start reporting"
        realtime?.sendStatus(if (enabled) "reporting" else "observing", if (enabled) "object detection enabled" else "object detection disabled")
        showDetail(if (enabled) "Reporting enabled · detections are shared with this place" else "Observing · reporting is off")
    }

    private fun setParticipantOverlayVisible(enabled: Boolean) {
        if (mode != ArMode.LIVE) return
        showParticipants = enabled
        participantsButton?.text = if (enabled) "Clients: On" else "Clients: Off"
        if (!enabled && ::overlay.isInitialized) overlay.updateParticipants(emptyList())
        showDetail(
            if (enabled) {
                "Participant gizmos on · localized phones appear through walls with direction arrows"
            } else {
                "Participant gizmos hidden · object sharing remains active"
            }
        )
    }

    private fun showMapSetupMenu() {
        val labels = arrayOf(
            "Re-establish shared origin",
            "Host Cloud Anchor here now",
            "Retry nearest failed anchor",
            "Set floor from camera center",
            "Place shared test marker",
            "Share diagnostics"
        )
        AlertDialog.Builder(this)
            .setTitle("Map setup · advanced")
            .setMessage("Scanning, anchor placement and floor detection are automatic. Use these recovery tools only when the guided status asks for them.")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        showDetail("Recovery: stand at the original map start position and heading; re-establishing origin…")
                        requestManualAlign.set(true)
                    }
                    1 -> {
                        showDetail("Manual Cloud Anchor request · keep the phone steady in a visually detailed area")
                        requestHost.set(true)
                    }
                    2 -> {
                        showDetail("Looking for the nearest failed anchor to retry…")
                        requestRetryAnchor.set(true)
                    }
                    3 -> {
                        showDetail("Look at the floor near the center of the camera while it is detected…")
                        requestGround.set(true)
                    }
                    4 -> {
                        showDetail("Placing a temporary shared test marker…")
                        requestMarker.set(true)
                    }
                    5 -> Diagnostics.shareLogs(this, spatialApp.logger)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLiveMenu() {
        val labels = buildList {
            add("Re-localize with saved Cloud Anchors")
            if (!BuildConfig.CLOUD_ANCHORS_CONFIGURED) add("Align fallback at saved origin")
            add("Place shared test marker")
            add("Share diagnostics")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Live AR · tools")
            .setItems(labels) { _, which ->
                val selected = labels[which]
                when {
                    selected.startsWith("Re-localize") -> {
                        showDetail("Re-localizing · move slowly while looking around the mapped area")
                        requestRelocalize.set(true)
                    }
                    selected.startsWith("Align fallback") -> {
                        showDetail("Fallback alignment: stand at the saved physical origin and face the saved heading")
                        requestManualAlign.set(true)
                    }
                    selected.startsWith("Place") -> requestMarker.set(true)
                    selected.startsWith("Share diagnostics") -> Diagnostics.shareLogs(this, spatialApp.logger)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun projectDetectionBoxes(frame: Frame, detections: List<Detection2D>): List<ProjectedBox> {
        return detections.mapNotNull { detection ->
            val box = detection.rawBoundingBox
            val input = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
            val output = FloatArray(8)
            runCatching {
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.VIEW, output)
                ProjectedBox(
                    detection.label,
                    detection.confidence,
                    RectF(
                        output.filterIndexed { index, _ -> index % 2 == 0 }.minOrNull() ?: 0f,
                        output.filterIndexed { index, _ -> index % 2 == 1 }.minOrNull() ?: 0f,
                        output.filterIndexed { index, _ -> index % 2 == 0 }.maxOrNull() ?: 0f,
                        output.filterIndexed { index, _ -> index % 2 == 1 }.maxOrNull() ?: 0f
                    )
                )
            }.getOrNull()
        }
    }


    private fun projectDetectionPoses(frame: Frame, detections: List<Detection2D>): List<ProjectedPose> {
        return detections.mapNotNull { detection ->
            if (!detection.label.equals("person", true) || detection.poseLandmarks.size < 6) return@mapNotNull null
            val input = FloatArray(detection.poseLandmarks.size * 2)
            detection.poseLandmarks.forEachIndexed { index, joint ->
                input[index * 2] = joint.x
                input[index * 2 + 1] = joint.y
            }
            val output = FloatArray(input.size)
            runCatching {
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.VIEW, output)
                ProjectedPose(
                    detection.poseLandmarks.mapIndexed { index, joint ->
                        ProjectedJoint(joint.index, output[index * 2], output[index * 2 + 1], joint.confidence)
                    }
                )
            }.getOrNull()
        }
    }

    private fun handleRequests(frame: Frame, cameraSite: FloatArray, worldFromSite: FloatArray, map: MapDefinition) {
        if (requestHost.getAndSet(false) && mode == ArMode.MAP) {
            cloudAnchors?.host(frame.camera.pose, worldFromSite, map, forced = true)
        }
        if (requestRetryAnchor.getAndSet(false) && mode == ArMode.MAP) {
            cloudAnchors?.retryNearestFailed(frame.camera.pose, worldFromSite, map)
        }
        if (requestGround.getAndSet(false) && mode == ArMode.MAP) {
            val point = SpatialEstimator.centerGroundPoint(frame, worldFromSite, null)
            if (point == null) showDetail("Floor not found · point the camera at a visible floor area and try again")
            else {
                spatialApp.database.updateMapRuntime(mapId, groundY = point[1])
                currentMap(forceRefresh = true)
                UploadScheduler.enqueue(this)
                showDetail("Floor saved · object positions can now use ground projection")
            }
        }
        if (requestMarker.getAndSet(false)) {
            val point = SpatialEstimator.centerGroundPoint(frame, worldFromSite, map.groundY)
                ?: floatArrayOf(cameraSite[0], cameraSite[1], cameraSite[2] - 3f)
            val id = "m-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}"
            realtime?.sendManualMarker(id, "marker", point)
            remoteTracks.addMarker(id, "marker", point, System.currentTimeMillis() + 60_000L)
            showDetail("Shared test marker placed for 60 seconds")
        }
        if (requestFinish.getAndSet(false) && mode == ArMode.MAP) {
            val latest = currentMap(forceRefresh = true) ?: map
            val (ready, reason) = setupReadiness(latest)
            if (!ready) {
                showDetail(reason)
            } else {
                pointRecorder?.flush()
                pointRecorder?.stop()
                pointRecorder = null
                mappingFinished = true
                spatialApp.database.updateMapRuntime(mapId, status = MapStatus.READY)
                currentMap(forceRefresh = true)
                UploadScheduler.enqueue(this)
                realtime?.sendStatus("map_ready", "setup complete; final uploads queued")
                runOnUiThread {
                    stateText.text = "${latest.name} · Setup complete"
                    detailText.text = "Saved locally · final server sync is queued automatically"
                    Toast.makeText(this, "${latest.name} is ready for Live AR", Toast.LENGTH_LONG).show()
                    window.decorView.postDelayed({ if (!closing.get()) finishSafely() }, 900L)
                }
            }
        }
    }

    private fun setupReadiness(map: MapDefinition): Pair<Boolean, String> {
        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val hosted = map.anchors.count { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }
        val geometryReady = chunks >= MIN_SETUP_CHUNKS && points >= MIN_SETUP_POINTS
        val anchorReady = !BuildConfig.CLOUD_ANCHORS_CONFIGURED || hosted > 0
        val ready = geometryReady && anchorReady
        val reason = when {
            !geometryReady -> "Keep scanning · move slowly around the area until at least $MIN_SETUP_POINTS points are captured ($points now)"
            !anchorReady -> "Keep mapping a visually detailed area · waiting for the first Cloud Anchor to finish hosting"
            else -> "Ready to finish setup"
        }
        return ready to reason
    }

    private fun updateFinishButton(map: MapDefinition, chunks: Int, points: Int) {
        if (mode != ArMode.MAP) return
        val hosted = map.anchors.count { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }
        val ready = chunks >= MIN_SETUP_CHUNKS && points >= MIN_SETUP_POINTS &&
            (!BuildConfig.CLOUD_ANCHORS_CONFIGURED || hosted > 0)
        runOnUiThread {
            finishSetupButton?.isEnabled = ready
            finishSetupButton?.alpha = if (ready) 1f else 0.48f
            finishSetupButton?.text = if (ready) "Finish setup" else "Keep scanning"
        }
    }

    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray) {
        val now = System.currentTimeMillis()
        val localDeviceId = spatialApp.preferences.deviceId
        val projected = remoteTracks.snapshot(now)
            .asSequence()
            .filter { track -> track.sourceId == "marker" || track.sourceId != localDeviceId }
            .map { track ->
                val world = PoseMath.transformPoint(worldFromSite, track.position)
                val cameraPoint = PoseMath.transformPoint(viewMatrix, world)
                val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)
                val direction = OffscreenIndicatorMath.direction(cameraPoint)
                val onScreen = screen?.onScreen == true
                val skeleton = if (onScreen && track.label.equals("person", true) && track.poseJoints.isNotEmpty()) {
                    projectTrackSkeleton(track, worldFromSite)
                } else null
                ProjectedTrack(
                    key = track.key,
                    label = track.label,
                    confidence = track.confidence,
                    x = screen?.x ?: viewportWidth * 0.5f,
                    y = screen?.y ?: viewportHeight * 0.5f,
                    onScreen = onScreen,
                    distanceMeters = PoseMath.distance(cameraSite, track.position),
                    uncertaintyMeters = track.uncertaintyMeters,
                    ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),
                    sourceId = track.sourceId,
                    cuboid = if (onScreen && skeleton == null && track.sourceId != "marker") projectTrackCuboid(track, worldFromSite) else null,
                    skeleton = skeleton,
                    offscreenDx = direction.dx,
                    offscreenDy = direction.dy
                )
            }
            .toList()
        overlay.updateTracks(projected)
    }

    private fun updateProjectedParticipants(cameraSite: FloatArray, worldFromSite: FloatArray) {
        if (mode != ArMode.LIVE || !showParticipants) {
            overlay.updateParticipants(emptyList())
            return
        }
        val now = System.currentTimeMillis()
        val localClientId = spatialApp.preferences.deviceId
        val projected = buildList {
            remoteClientPoses.values.forEach { remote ->
                val ageMs = (now - remote.serverReceivedAtMs).coerceAtLeast(0L)
                if (ageMs > PARTICIPANT_POSE_TTL_MS) {
                    remoteClientPoses.remove(remote.clientId, remote)
                    return@forEach
                }
                if (remote.clientId == localClientId || remote.position.size < 3 || remote.rotation.size < 4) return@forEach

                val world = PoseMath.transformPoint(worldFromSite, remote.position)
                val cameraPoint = PoseMath.transformPoint(viewMatrix, world)
                val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)
                val direction = OffscreenIndicatorMath.direction(cameraPoint)
                val onScreen = screen?.onScreen == true
                add(
                    ProjectedParticipant(
                        clientId = remote.clientId,
                        role = remote.role,
                        x = screen?.x ?: viewportWidth * 0.5f,
                        y = screen?.y ?: viewportHeight * 0.5f,
                        onScreen = onScreen,
                        distanceMeters = PoseMath.distance(cameraSite, remote.position),
                        ageMs = ageMs,
                        tracking = remote.tracking,
                        offscreenDx = direction.dx,
                        offscreenDy = direction.dy,
                        gizmo = if (onScreen) projectParticipantGizmo(remote, worldFromSite) else null
                    )
                )
            }
        }
        overlay.updateParticipants(projected)
    }

    private fun projectParticipantGizmo(remote: RemoteClientPose, worldFromSite: FloatArray): ProjectedParticipantGizmo? {
        val siteFromClient = runCatching { PoseMath.poseToMatrix(Pose(remote.position, remote.rotation)) }.getOrNull()
            ?: return null
        val worldFromClient = PoseMath.multiply(worldFromSite, siteFromClient)
        fun projectLocal(localPoint: FloatArray): ProjectedPoint? {
            val world = PoseMath.transformPoint(worldFromClient, localPoint)
            val projected = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)
                ?: return null
            return ProjectedPoint(projected.x, projected.y)
        }
        val right = projectLocal(floatArrayOf(PARTICIPANT_GIZMO_AXIS_METERS, 0f, 0f))
        val up = projectLocal(floatArrayOf(0f, PARTICIPANT_GIZMO_AXIS_METERS, 0f))
        val forward = projectLocal(floatArrayOf(0f, 0f, -PARTICIPANT_GIZMO_FORWARD_METERS))
        return if (right == null && up == null && forward == null) null else {
            ProjectedParticipantGizmo(right = right, up = up, forward = forward)
        }
    }


    private fun projectTrackSkeleton(track: SpatialTrack, worldFromSite: FloatArray): ProjectedSkeleton? {
        if (!track.label.equals("person", true) || track.poseJoints.size < 6) return null
        val joints = track.poseJoints.mapNotNull { joint ->
            if (joint.offsetMeters.size < 3) return@mapNotNull null
            val site = floatArrayOf(
                track.position[0] + joint.offsetMeters[0],
                track.position[1] + joint.offsetMeters[1],
                track.position[2] + joint.offsetMeters[2]
            )
            val world = PoseMath.transformPoint(worldFromSite, site)
            val projected = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)
                ?: return@mapNotNull null
            ProjectedJoint(joint.index, projected.x, projected.y, joint.confidence)
        }
        return joints.takeIf { it.size >= 6 }?.let(::ProjectedSkeleton)
    }

    private fun projectTrackCuboid(track: SpatialTrack, worldFromSite: FloatArray): ProjectedCuboid? {
        if (track.extentMeters.size < 3) return null
        val width = track.extentMeters[0].coerceIn(0.05f, 12f)
        val height = track.extentMeters[1].coerceIn(0.05f, 8f)
        val depth = track.extentMeters[2].coerceIn(0.05f, 15f)
        val halfWidth = width * 0.5f
        val halfDepth = depth * 0.5f
        val cosYaw = kotlin.math.cos(track.yawRadians)
        val sinYaw = kotlin.math.sin(track.yawRadians)
        val local = arrayOf(
            floatArrayOf(-halfWidth, 0f, -halfDepth),
            floatArrayOf(halfWidth, 0f, -halfDepth),
            floatArrayOf(halfWidth, 0f, halfDepth),
            floatArrayOf(-halfWidth, 0f, halfDepth),
            floatArrayOf(-halfWidth, height, -halfDepth),
            floatArrayOf(halfWidth, height, -halfDepth),
            floatArrayOf(halfWidth, height, halfDepth),
            floatArrayOf(-halfWidth, height, halfDepth)
        )
        val points = local.map { corner ->
            val rotatedX = corner[0] * cosYaw - corner[2] * sinYaw
            val rotatedZ = corner[0] * sinYaw + corner[2] * cosYaw
            val site = floatArrayOf(
                track.position[0] + rotatedX,
                track.position[1] + corner[1],
                track.position[2] + rotatedZ
            )
            val world = PoseMath.transformPoint(worldFromSite, site)
            val projected = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight)
                ?: return null
            floatArrayOf(projected.x, projected.y)
        }
        return ProjectedCuboid(points)
    }

    private fun publishClientPose(frame: Frame, siteFromWorld: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastPoseSentAtMs < POSE_INTERVAL_MS) return
        lastPoseSentAtMs = now
        val siteFromCamera = PoseMath.multiply(siteFromWorld, PoseMath.poseToMatrix(frame.camera.pose))
        realtime?.sendClientPose(PoseMath.translationOf(siteFromCamera), PoseMath.quaternionOf(siteFromCamera), frame.camera.trackingState.name)
    }

    private fun updateHud(frame: Frame, worldFromSite: FloatArray?, overrideDetail: String?) {
        val now = System.currentTimeMillis()
        if (now - lastHudAtMs < HUD_INTERVAL_MS) return
        lastHudAtMs = now
        val map = currentMap() ?: return
        val anchors = map.anchors
        val hosted = anchors.count { it.status == AnchorStatus.HOSTED }
        val hosting = anchors.count { it.status == AnchorStatus.HOSTING }
        val failed = anchors.count { it.status == AnchorStatus.NEEDS_RESCAN || it.status == AnchorStatus.FAILED }
        val (chunks, points) = spatialApp.database.chunkCounts(mapId)
        val bufferedRemoteTracks = remoteTracks.snapshot(now).count { track ->
            track.sourceId != spatialApp.preferences.deviceId && track.sourceId != "marker"
        }
        val locationState = when {
            worldFromSite != null -> cloudAnchors?.currentReferenceId
                ?.let { "Localized · anchor ${it.takeLast(8)}" }
                ?: "Localized · manual"
            cloudAnchors?.isResolving == true -> "Localizing…"
            else -> "Waiting for location"
        }
        runOnUiThread {
            stateText.text = when (mode) {
                ArMode.MAP -> "${map.name} · Map setup · ${if (worldFromSite != null) "Scanning" else "Preparing"}"
                ArMode.LIVE -> "${map.name} · $locationState"
                else -> "${map.name} · $locationState"
            }
            overrideDetail?.let { detailText.text = it } ?: run {
                detailText.text = when (mode) {
                    ArMode.MAP -> buildString {
                        append("Move slowly · $points points in $chunks chunks · $hosted anchor")
                        if (hosted != 1) append('s')
                        if (hosting > 0) append(" · hosting $hosting")
                        if (failed > 0) append(" · $failed retry automatically when nearby")
                        append(" · features ${featureQuality.name.lowercase()}")
                        append(if (map.groundY != null) " · floor ready" else " · finding floor")
                    }
                    ArMode.SENSOR -> "$latestLocalTrackCount tracks · ${latestInferenceMs} ms inference"
                    ArMode.LIVE -> if (worldFromSite == null) {
                        val room = if (realtimeConnected) "room connected" else "room reconnecting"
                        val resolver = localizationDetail.ifBlank { "resolving saved Cloud Anchors" }
                        "Detector active · $latestDetectionCount visible · $latestPoseCount pose · $bufferedRemoteTracks remote buffered · $room · $resolver"
                    } else {
                        val ack = when {
                            !realtimeConnected -> "server offline"
                            lastAckAtMs > 0L && now - lastAckAtMs < 2_500L -> "server ✓$lastAckAccepted"
                            latestSentTrackCount > 0 -> "server awaiting ack"
                            else -> "server connected"
                        }
                        val runtime = detectorRuntimeState
                        val detectorText = runtime?.let {
                            "${it.profile.model.name.lowercase()}/${it.profile.delegate.name.lowercase()} · drop ${it.droppedFrames}"
                        } ?: "detector starting"
                        val terrainText = terrainModel.get()?.let { "terrain ${it.cellCount}" } ?: "terrain fallback"
                        "$latestDetectionCount detected · $latestPoseCount pose · $latestSpatializedCount spatialized · $latestLocalTrackCount active · $ack · $bufferedRemoteTracks remote · ${latestInferenceMs} ms · $detectorText · $terrainText"
                    }
                    ArMode.VIEWER -> if (worldFromSite == null) "Resolving shared location…" else "Observing shared tracks"
                }
            }
        }
        updateFinishButton(map, chunks, points)
    }

    private fun updateScanOverlay(chunks: Int, points: Int) {
        val map = currentMap() ?: return
        val hosted = map.anchors.count { it.status == AnchorStatus.HOSTED }
        val pending = map.anchors.count { it.status == AnchorStatus.HOSTING || it.status == AnchorStatus.NEEDS_RESCAN }
        overlay.updateScanState(ScanOverlayState(chunks, points, featureQuality.name, hosted, pending), visible = mode == ArMode.MAP)
        updateFinishButton(map, chunks, points)
    }

    private fun currentMap(forceRefresh: Boolean = false): MapDefinition? {
        val now = System.currentTimeMillis()
        val cached = cachedMap.get()
        if (!forceRefresh && cached != null && now - lastMapRefreshAtMs < MAP_CACHE_INTERVAL_MS) return cached
        return spatialApp.database.getMap(mapId).also { refreshed ->
            cachedMap.set(refreshed)
            lastMapRefreshAtMs = now
        }
    }

    private fun showDetail(message: String) {
        runOnUiThread { detailText.text = message }
    }

    override fun onConnectionState(connected: Boolean, detail: String) {
        realtimeConnected = connected
        if (!connected) {
            remoteClientPoses.clear()
            if (::overlay.isInitialized) overlay.updateParticipants(emptyList())
        }
        runOnUiThread {
            networkText.text = when {
                connected && mode == ArMode.MAP -> "Server connected · map sync is automatic"
                connected && mode == ArMode.LIVE -> "Server connected · automatic object sharing active"
                connected -> "Server connected · live sharing active"
                mode == ArMode.MAP -> "Server offline · scan is saved locally · upload retry automatic"
                else -> "Server reconnecting · shared tracks temporarily unavailable"
            }
            networkText.setTextColor(if (connected) FieldTheme.statusBlue else FieldTheme.accent)
            if (connected && mode == ArMode.LIVE) realtime?.sendStatus("detecting", "automatic object detection and sharing enabled")
        }
    }

    override fun onTracks(tracks: List<SpatialTrack>, replaceSnapshot: Boolean) {
        if (replaceSnapshot) remoteTracks.replaceAll(tracks) else remoteTracks.update(tracks)
    }

    override fun onTracksExpired(trackKeys: List<String>) = remoteTracks.remove(trackKeys)

    override fun onTrackAck(sequence: Long, accepted: Int, expired: Int, serverTimeMs: Long) {
        if (sequence < lastAckSequence) return
        lastAckSequence = sequence
        lastAckAccepted = accepted
        lastAckAtMs = System.currentTimeMillis()
        spatialApp.logger.debug(
            "Track batch acknowledged",
            mapOf("mapId" to mapId, "sequence" to sequence, "accepted" to accepted, "expired" to expired)
        )
    }

    override fun onManualMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long) {
        remoteTracks.addMarker(id, label, position, expiresAtMs)
    }

    override fun onClientPose(pose: RemoteClientPose) {
        if (pose.clientId.isBlank() || pose.clientId == spatialApp.preferences.deviceId) return
        remoteClientPoses[pose.clientId] = pose
    }

    override fun onPresence(clientId: String, action: String, role: String) {
        if (action == "left") remoteClientPoses.remove(clientId)
        spatialApp.logger.debug("Realtime presence", mapOf("clientId" to clientId, "action" to action, "role" to role))
    }

    private fun action(label: String, block: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(FieldTheme.textPrimary)
        setOnClickListener { block() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MAP_ID = "map_id"
        const val EXTRA_MODE = "mode"
        private const val DETECTION_INTERVAL_MS = 80L
        private const val TRACK_PUBLISH_INTERVAL_MS = 100L
        private const val POSE_INTERVAL_MS = 500L
        private const val PARTICIPANT_POSE_TTL_MS = 3_000L
        private const val PARTICIPANT_GIZMO_AXIS_METERS = 0.35f
        private const val PARTICIPANT_GIZMO_FORWARD_METERS = 0.55f
        private const val DETECTOR_STATUS_INTERVAL_MS = 1_500L
        private const val FEATURE_QUALITY_INTERVAL_MS = 500L
        private const val SCAN_HUD_INTERVAL_MS = 500L
        private const val MAP_CACHE_INTERVAL_MS = 500L
        private const val HUD_INTERVAL_MS = 350L
        private const val RESOLVE_RETRY_MS = 5_000L
        private const val AUTO_GROUND_INTERVAL_MS = 1_000L
        private const val PERMISSION_SETTLE_DELAY_MS = 450L
        private const val ARCORE_RETRY_SETTLE_DELAY_MS = 550L
        private const val MIN_SETUP_CHUNKS = 2
        private const val MIN_SETUP_POINTS = 1_000
        private const val TERRAIN_POINT_LIMIT = 50_000
    }
}
