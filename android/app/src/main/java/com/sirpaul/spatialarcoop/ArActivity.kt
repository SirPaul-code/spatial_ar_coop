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
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
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
import com.sirpaul.spatialarcoop.ar.RemoteTrackStore
import com.sirpaul.spatialarcoop.ar.SpatialEstimator
import com.sirpaul.spatialarcoop.data.AnchorStatus
import com.sirpaul.spatialarcoop.data.FeatureQuality
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.data.MapStatus
import com.sirpaul.spatialarcoop.data.SpatialTrack
import com.sirpaul.spatialarcoop.net.RealtimeClient
import com.sirpaul.spatialarcoop.net.RealtimeListener
import com.sirpaul.spatialarcoop.net.UploadScheduler
import com.sirpaul.spatialarcoop.ui.FieldTheme
import com.sirpaul.spatialarcoop.ui.ProjectedBox
import com.sirpaul.spatialarcoop.ui.ProjectedTrack
import com.sirpaul.spatialarcoop.ui.ScanOverlayState
import com.sirpaul.spatialarcoop.ui.SpatialOverlayView
import com.sirpaul.spatialarcoop.util.Diagnostics
import com.sirpaul.spatialarcoop.vision.Detection2D
import com.sirpaul.spatialarcoop.vision.DetectionTracker
import com.sirpaul.spatialarcoop.vision.ObjectDetectorEngine
import com.sirpaul.spatialarcoop.vision.SpatialObservation
import com.sirpaul.spatialarcoop.vision.YuvFrame
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer, RealtimeListener {
    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)

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

    @Volatile private var session: Session? = null
    private val arState = ArSessionStateMachine()
    private val closing = AtomicBoolean(false)
    @Volatile private var glResumed = false
    private var installRequested = false
    private var textureBound = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var resolveLastAttemptMs = 0L
    private var lastHudAtMs = 0L
    private var lastPoseSentAtMs = 0L
    private var lastDetectionCaptureAtMs = 0L
    private var lastDetectorStatusAtMs = 0L
    private var latestInferenceMs = 0L
    private var latestLocalTrackCount = 0
    private var lastFeatureQualityAtMs = 0L
    private var lastScanHudAtMs = 0L
    private var lastMapRefreshAtMs = 0L
    private var featureQuality = FeatureQuality.UNKNOWN
    private var mappingFinished = false
    private var sequence = 0L

    private val cachedMap = AtomicReference<MapDefinition?>(null)
    private val manualWorldFromSite = AtomicReference<FloatArray?>(null)
    @Volatile private var manualAlignmentOverride = false
    private val pendingDetection = AtomicReference<PendingDetection?>(null)
    private val remoteTracks = RemoteTrackStore()
    private lateinit var localTracker: DetectionTracker
    private var detector: ObjectDetectorEngine? = null
    @Volatile private var reporting = false
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
        if (granted) resumeArSession() else {
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
            // Keep the proven wire protocol compatible: LIVE observes like every client and is
            // allowed to publish the same track messages as the legacy SENSOR role.
            ArMode.LIVE -> "sensor"
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
        if (mode == ArMode.SENSOR) {
            reporting = true
            ensureDetector()
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
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.argb(218, 23, 24, 26))
        }
        actions.addView(action("Back") { finishSafely() })
        when (mode) {
            ArMode.MAP -> {
                actions.addView(action("Align origin") { requestManualAlign.set(true) })
                actions.addView(action("Add anchor") { requestHost.set(true) })
                actions.addView(action("Retry nearby") { requestRetryAnchor.set(true) })
                actions.addView(action("Set ground") { requestGround.set(true) })
                actions.addView(action("Finish map") { requestFinish.set(true) })
            }
            ArMode.LIVE -> {
                reportButton = action("Start reporting") { setReporting(!reporting) }.also(actions::addView)
                actions.addView(action("Re-localize") { requestRelocalize.set(true) })
                if (!BuildConfig.CLOUD_ANCHORS_CONFIGURED) {
                    actions.addView(action("Align fallback") { requestManualAlign.set(true) })
                }
            }
            ArMode.SENSOR, ArMode.VIEWER -> {
                actions.addView(action("Align fallback") { requestManualAlign.set(true) })
                actions.addView(action("Re-localize") { requestRelocalize.set(true) })
            }
        }
        actions.addView(action("Mark") { requestMarker.set(true) })
        actions.addView(action("Diagnostics") { Diagnostics.shareLogs(this, spatialApp.logger) })
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
        errorActions.addView(action("Retry AR") { retryArSession() })
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
        displayRotation.onResume()
        resumeArSession()
    }

    private fun createConfiguredSession(): Session {
        val created = Session(this)
        try {
            val config = Config(created).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = Config.FocusMode.AUTO
                cloudAnchorMode = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) Config.CloudAnchorMode.ENABLED else Config.CloudAnchorMode.DISABLED
                depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            }
            created.configure(config)
            cloudAnchors = CloudAnchorCoordinator(
                session = created,
                mapId = mapId,
                database = spatialApp.database,
                logger = spatialApp.logger,
                scheduleUpload = { UploadScheduler.enqueue(this) },
                onState = ::showDetail
            )
            session = created
            return created
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        }
    }

    private fun resumeArSession() {
        if (closing.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
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
            if (!arState.beginStart()) return
            val active = session ?: createConfiguredSession()
            active.resume()
            textureBound = false
            if (!arState.markRunning()) {
                runCatching { active.pause() }
                return
            }
            hideArFailure()
            resumeGlIfNeeded()
            showDetail("AR session running · looking for shared anchor")
        } catch (error: Throwable) {
            failArSession(error, "resume")
        }
    }

    private fun retryArSession() {
        if (closing.get() || arState.current() != ArSessionState.FAILED) return
        hideArFailure()
        resumeArSession()
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
            closeSessionAsync(failedSession)
            showArFailure(error)
        }
    }

    private fun showArFailure(error: Throwable) {
        val category = when (error) {
            is FatalException -> "Google Play Services for AR returned an internal session error. A fresh session is required."
            is CameraNotAvailableException -> "The camera is unavailable to ARCore. Close other camera users and retry."
            is SessionPausedException -> "The AR session became paused unexpectedly. A fresh session will be created."
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
        detailText.text = "Retry AR or share diagnostics for the complete error."
    }

    private fun hideArFailure() {
        runOnUiThread { arErrorPanel.visibility = View.GONE }
    }

    private fun closeSessionAsync(value: Session?) {
        if (value == null) return
        Thread({ runCatching { value.close() } }, "spatial-arcore-close").start()
    }

    override fun onPause() {
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
        arState.beginClosing()
        spatialApp.logger.info("AR teardown begin", mapOf("mapId" to mapId, "mode" to mode.name))
        pauseGlIfNeeded()
        runCatching { displayRotation.onPause() }
        pointRecorder?.flush()
        pointRecorder?.stop()
        pointRecorder = null
        stopDetector()
        realtime?.close()
        realtime = null
        cloudAnchors?.close()
        cloudAnchors = null
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

        var map = currentMap() ?: return
        if (requestManualAlign.getAndSet(false)) {
            manualWorldFromSite.set(PoseMath.horizontalOrigin(camera.pose))
            manualAlignmentOverride = mode != ArMode.MAP
            resolveLastAttemptMs = System.currentTimeMillis()
            showDetail(
                if (mode == ArMode.MAP) {
                    "Site origin aligned. Keep this physical spot and facing direction as the manual fallback."
                } else {
                    "Manual site alignment active. Tracking now uses this marked origin and heading."
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
        }

        val cloudWorldFromSite = cloudAnchors?.currentWorldFromSite()
        val manualAlignment = manualWorldFromSite.get()
        var worldFromSite = if (manualAlignmentOverride) manualAlignment else cloudWorldFromSite ?: manualAlignment
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
            val instruction = when {
                mode == ArMode.MAP && !hosted -> "Stand at the chosen map origin, face a repeatable direction, then tap ALIGN ORIGIN"
                BuildConfig.CLOUD_ANCHORS_CONFIGURED && hosted -> "Point at a mapped anchor area and move slowly, or use ALIGN HERE at the saved origin"
                else -> "Stand at the saved physical origin, face the saved direction, then tap ALIGN HERE"
            }
            updateHud(frame, null, instruction)
            overlay.updateTracks(emptyList())
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
        if (!mappingFinished) cloudAnchors?.considerAutoHost(frame.camera.pose, worldFromSite, map)
        if (now - lastScanHudAtMs >= SCAN_HUD_INTERVAL_MS) {
            lastScanHudAtMs = now
            val (chunks, points) = spatialApp.database.chunkCounts(mapId)
            updateScanOverlay(chunks, points)
        }
    }

    private fun updateSensor(frame: Frame, worldFromSite: FloatArray, map: MapDefinition) {
        pendingDetection.getAndSet(null)?.let { pending ->
            val observations = pending.detections.mapNotNull { detection ->
                SpatialEstimator.estimate(frame, detection, worldFromSite, map.groundY)?.let { estimate ->
                    SpatialObservation(
                        label = detection.label,
                        confidence = detection.confidence,
                        position = estimate.sitePosition,
                        observedAtMs = detection.capturedAtMs,
                        uncertaintyMeters = estimate.uncertaintyMeters
                    )
                }
            }
            val tracks = localTracker.update(observations)
            remoteTracks.update(tracks)
            realtime?.sendTracks(sequence++, tracks)
            latestInferenceMs = pending.inferenceMs
            latestLocalTrackCount = tracks.size
            val now = System.currentTimeMillis()
            if (now - lastDetectorStatusAtMs >= DETECTOR_STATUS_INTERVAL_MS) {
                lastDetectorStatusAtMs = now
                realtime?.sendStatus("detecting", "${tracks.size} tracks, ${pending.inferenceMs}ms inference")
            }
            overlay.updateLocalBoxes(projectDetectionBoxes(frame, pending.detections))
        }

        val now = System.currentTimeMillis()
        if (now - lastDetectionCaptureAtMs >= DETECTION_INTERVAL_MS) {
            val image = try {
                frame.acquireCameraImage()
            } catch (_: NotYetAvailableException) {
                null
            }
            if (image != null) {
                try {
                    val yuv = YuvFrame.copyOf(image)
                    val cameraId = session?.cameraConfig?.cameraId
                    val rotation = if (cameraId == null) {
                        0
                    } else {
                        runCatching { displayRotation.cameraSensorToDisplayRotation(cameraId) }.getOrDefault(0)
                    }
                    if (detector?.submit(yuv, rotation, now) == true) lastDetectionCaptureAtMs = now
                } finally {
                    image.close()
                }
            }
        }
    }

    private fun ensureDetector() {
        if (detector != null) return
        detector = ObjectDetectorEngine(
            context = this,
            threshold = spatialApp.preferences.detectorThreshold,
            logger = spatialApp.logger,
            onResult = { values, inferenceMs -> pendingDetection.set(PendingDetection(values, inferenceMs)) },
            onError = { message -> showDetail("Detector error: $message") }
        )
    }

    private fun stopDetector() {
        pendingDetection.set(null)
        detector?.close()
        detector = null
        latestLocalTrackCount = 0
        latestInferenceMs = 0
        if (::overlay.isInitialized) overlay.updateLocalBoxes(emptyList())
    }

    private fun setReporting(enabled: Boolean) {
        if (mode != ArMode.LIVE) return
        reporting = enabled
        if (enabled) ensureDetector() else stopDetector()
        reportButton?.text = if (enabled) "Stop reporting" else "Start reporting"
        realtime?.sendStatus(if (enabled) "reporting" else "observing", if (enabled) "object detection enabled" else "object detection disabled")
        showDetail(if (enabled) "Reporting enabled · detections are shared with this place" else "Observing · reporting is off")
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

    private fun handleRequests(frame: Frame, cameraSite: FloatArray, worldFromSite: FloatArray, map: MapDefinition) {
        if (requestHost.getAndSet(false) && mode == ArMode.MAP) {
            cloudAnchors?.host(frame.camera.pose, worldFromSite, map, forced = true)
        }
        if (requestRetryAnchor.getAndSet(false) && mode == ArMode.MAP) {
            cloudAnchors?.retryNearestFailed(frame.camera.pose, worldFromSite, map)
        }
        if (requestGround.getAndSet(false) && mode == ArMode.MAP) {
            val point = SpatialEstimator.centerGroundPoint(frame, worldFromSite, null)
            if (point == null) showDetail("No ground hit; scan the floor and try again")
            else {
                spatialApp.database.updateMapRuntime(mapId, groundY = point[1])
                currentMap(forceRefresh = true)
                UploadScheduler.enqueue(this)
                showDetail("Ground plane set to site Y ${"%.2f".format(point[1])} m")
            }
        }
        if (requestMarker.getAndSet(false)) {
            val point = SpatialEstimator.centerGroundPoint(frame, worldFromSite, map.groundY)
                ?: floatArrayOf(cameraSite[0], cameraSite[1], cameraSite[2] - 3f)
            val id = "m-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4)}"
            realtime?.sendManualMarker(id, "marker", point)
            remoteTracks.addMarker(id, "marker", point, System.currentTimeMillis() + 60_000L)
            showDetail("Shared marker placed")
        }
        if (requestFinish.getAndSet(false) && mode == ArMode.MAP) {
            pointRecorder?.flush()
            mappingFinished = true
            spatialApp.database.updateMapRuntime(mapId, status = MapStatus.READY)
            currentMap(forceRefresh = true)
            UploadScheduler.enqueue(this)
            showDetail("Map marked READY; saved chunks continue uploading")
        }
    }

    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray) {
        val now = System.currentTimeMillis()
        val projected = remoteTracks.snapshot(now).mapNotNull { track ->
            val world = PoseMath.transformPoint(worldFromSite, track.position)
            val screen = PoseMath.projectToScreen(viewProjectionMatrix, world, viewportWidth, viewportHeight) ?: return@mapNotNull null
            ProjectedTrack(
                key = track.key,
                label = track.label,
                confidence = track.confidence,
                x = screen.x,
                y = screen.y,
                onScreen = screen.onScreen,
                distanceMeters = PoseMath.distance(cameraSite, track.position),
                uncertaintyMeters = track.uncertaintyMeters,
                ageMs = (now - track.serverReceivedAtMs).coerceAtLeast(0L),
                sourceId = track.sourceId
            )
        }
        overlay.updateTracks(projected)
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
        val pending = anchors.count { it.status == AnchorStatus.HOSTING || it.status == AnchorStatus.NEEDS_RESCAN }
        val localization = when {
            worldFromSite != null -> "LOCALIZED"
            cloudAnchors?.isResolving == true -> "RESOLVING"
            else -> "NOT LOCALIZED"
        }
        runOnUiThread {
            stateText.text = "${currentMap()?.name ?: mapId} · ${localization.lowercase().replaceFirstChar { it.uppercase() }} · ${frame.camera.trackingState.name.lowercase()}"
            overrideDetail?.let { detailText.text = it } ?: run {
                detailText.text = when (mode) {
                    ArMode.MAP -> "${featureQuality.name.lowercase()} features · $hosted anchors · $pending pending/rescan"
                    ArMode.SENSOR -> "$latestLocalTrackCount tracks · ${latestInferenceMs} ms inference"
                    ArMode.LIVE -> if (reporting) "$latestLocalTrackCount live tracks · reporting" else "Observing shared tracks · reporting off"
                    ArMode.VIEWER -> "Observing shared tracks"
                }
            }
        }
    }

    private fun updateScanOverlay(chunks: Int, points: Int) {
        val map = currentMap() ?: return
        val hosted = map.anchors.count { it.status == AnchorStatus.HOSTED }
        val pending = map.anchors.count { it.status == AnchorStatus.HOSTING || it.status == AnchorStatus.NEEDS_RESCAN }
        overlay.updateScanState(ScanOverlayState(chunks, points, featureQuality.name, hosted, pending), visible = mode == ArMode.MAP)
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
        runOnUiThread {
            networkText.text = if (connected) "Server connected" else "Server: $detail"
            networkText.setTextColor(if (connected) FieldTheme.statusBlue else FieldTheme.accent)
            if (connected && mode == ArMode.LIVE) realtime?.sendStatus(if (reporting) "reporting" else "observing")
        }
    }

    override fun onTracks(tracks: List<SpatialTrack>, replaceSnapshot: Boolean) {
        if (replaceSnapshot) remoteTracks.replaceAll(tracks) else remoteTracks.update(tracks)
    }

    override fun onTracksExpired(trackKeys: List<String>) = remoteTracks.remove(trackKeys)

    override fun onManualMarker(id: String, label: String, position: FloatArray, expiresAtMs: Long) {
        remoteTracks.addMarker(id, label, position, expiresAtMs)
    }

    override fun onPresence(clientId: String, action: String, role: String) {
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
        private const val DETECTION_INTERVAL_MS = 120L
        private const val POSE_INTERVAL_MS = 500L
        private const val DETECTOR_STATUS_INTERVAL_MS = 1_500L
        private const val FEATURE_QUALITY_INTERVAL_MS = 500L
        private const val SCAN_HUD_INTERVAL_MS = 500L
        private const val MAP_CACHE_INTERVAL_MS = 500L
        private const val HUD_INTERVAL_MS = 350L
        private const val RESOLVE_RETRY_MS = 15_000L
    }
}

