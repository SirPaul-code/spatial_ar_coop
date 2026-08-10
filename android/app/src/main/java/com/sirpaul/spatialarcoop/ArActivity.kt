package com.sirpaul.spatialarcoop

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.google.ar.core.exceptions.NotYetAvailableException
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

    private var session: Session? = null
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
        spatialApp.logger.info("AR mode opened", mapOf("mapId" to mapId, "mode" to mode.name))
    }

    private fun configureModeComponents() {
        val map = currentMap() ?: return
        realtime = RealtimeClient(
            serverUrl = map.serverUrl,
            apiToken = spatialApp.preferences.apiToken,
            mapId = map.id,
            clientId = spatialApp.preferences.deviceId,
            role = mode.name.lowercase().replace("map", "mapper"),
            logger = spatialApp.logger,
            listener = this
        )
        if (mode == ArMode.SENSOR) {
            detector = ObjectDetectorEngine(
                context = this,
                threshold = spatialApp.preferences.detectorThreshold,
                logger = spatialApp.logger,
                onResult = { values, inferenceMs -> pendingDetection.set(PendingDetection(values, inferenceMs)) },
                onError = { message -> showDetail("Detector error: $message") }
            )
        }
        if (mode == ArMode.MAP) {
            val (chunks, points) = spatialApp.database.chunkCounts(mapId)
            updateScanOverlay(chunks, points)
        }
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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
            setPadding(dp(14), dp(12), dp(14), dp(10))
            setBackgroundColor(Color.argb(196, 4, 10, 9))
        }
        stateText = TextView(this).apply {
            text = "${mode.name} • $mapId"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        detailText = TextView(this).apply {
            text = "Initializing ARCore…"
            setTextColor(Color.rgb(183, 204, 195))
            textSize = 12f
        }
        networkText = TextView(this).apply {
            text = "Server: disconnected"
            setTextColor(Color.rgb(255, 184, 92))
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
            setBackgroundColor(Color.argb(205, 4, 10, 9))
        }
        actions.addView(action("BACK") { finish() })
        when (mode) {
            ArMode.MAP -> {
                actions.addView(action("ALIGN ORIGIN") { requestManualAlign.set(true) })
                actions.addView(action("ADD ANCHOR") { requestHost.set(true) })
                actions.addView(action("RETRY NEARBY") { requestRetryAnchor.set(true) })
                actions.addView(action("SET GROUND") { requestGround.set(true) })
                actions.addView(action("FINISH MAP") { requestFinish.set(true) })
            }
            ArMode.SENSOR, ArMode.VIEWER -> {
                actions.addView(action("ALIGN HERE") { requestManualAlign.set(true) })
                actions.addView(action("RELOCALIZE") { requestRelocalize.set(true) })
            }
        }
        actions.addView(action("MARK") { requestMarker.set(true) })
        actions.addView(action("LOGS") { Diagnostics.shareLogs(this, spatialApp.logger) })
        scroll.addView(actions)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    override fun onStart() {
        super.onStart()
        UploadScheduler.enqueue(this)
        realtime?.connect()
    }

    override fun onStop() {
        realtime?.close()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        displayRotation.onResume()
        resumeArSession()
        glSurface.onResume()
    }

    private fun resumeArSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    showDetail("Install or update Google Play Services for AR")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                null -> error("ARCore installation check returned no status")
            }
            val active = session ?: Session(this).also { created ->
                val config = Config(created).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    cloudAnchorMode = if (BuildConfig.CLOUD_ANCHORS_CONFIGURED) Config.CloudAnchorMode.ENABLED else Config.CloudAnchorMode.DISABLED
                    depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                }
                created.configure(config)
                session = created
                cloudAnchors = CloudAnchorCoordinator(
                    session = created,
                    mapId = mapId,
                    database = spatialApp.database,
                    logger = spatialApp.logger,
                    scheduleUpload = { UploadScheduler.enqueue(this) },
                    onState = ::showDetail
                )
            }
            active.resume()
            textureBound = false
            showDetail("AR session running")
        } catch (error: Exception) {
            spatialApp.logger.error("ARCore session failed", error)
            showDetail("ARCore error: ${error.message}")
        }
    }

    override fun onPause() {
        displayRotation.onPause()
        pointRecorder?.flush()
        glSurface.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        pointRecorder?.stop()
        detector?.close()
        realtime?.close()
        cloudAnchors?.close()
        val closingSession = session
        session = null
        if (closingSession != null) {
            Thread({ runCatching { closingSession.close() } }, "spatial-arcore-close").start()
        }
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
        val active = session ?: return
        if (!::backgroundRenderer.isInitialized) return
        try {
            if (!textureBound) {
                active.setCameraTextureNames(intArrayOf(backgroundRenderer.textureId))
                textureBound = true
            }
            displayRotation.updateSessionIfNeeded(active)
            val frame = active.update()
            backgroundRenderer.updateDisplayGeometry(frame)
            backgroundRenderer.draw()
            renderArFrame(frame)
        } catch (error: CameraNotAvailableException) {
            spatialApp.logger.error("Camera unavailable", error)
            showDetail("Camera unavailable; reopen the AR screen")
        } catch (error: Throwable) {
            spatialApp.logger.error("AR frame failed", error)
            showDetail("AR frame error: ${error.message}")
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
            stateText.text = "${mode.name} • $localization • ${frame.camera.trackingState}"
            overrideDetail?.let { detailText.text = it } ?: run {
                detailText.text = when (mode) {
                    ArMode.MAP -> "${featureQuality.name} features • $hosted anchors • $pending pending/rescan"
                    ArMode.SENSOR -> "$latestLocalTrackCount tracks • ${latestInferenceMs} ms inference • person / car / bird / dog / cat"
                    ArMode.VIEWER -> "Rendering cooperative tracks through occluders"
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
            networkText.text = if (connected) "Server: connected" else "Server: $detail"
            networkText.setTextColor(if (connected) Color.rgb(117, 231, 176) else Color.rgb(255, 184, 92))
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
        setTextColor(Color.WHITE)
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
