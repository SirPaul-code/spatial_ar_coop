from pathlib import Path

path = Path('android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt')
text = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f'missing exact patch marker:\n{old[:180]}')
    text = text.replace(old, new, 1)


def replace_region(start_marker: str, end_marker: str, replacement: str) -> None:
    global text
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f'missing region start: {start_marker}')
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'missing region end: {end_marker}')
    text = text[:start] + replacement + text[end:]


replace_once(
    'class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer, RealtimeListener {\n'
    '    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)\n',
    'class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer, RealtimeListener {\n'
    '    private data class PendingDetection(val detections: List<Detection2D>, val inferenceMs: Long)\n'
    '    private enum class ArSessionProfile { STANDARD, COMPATIBILITY }\n'
)

replace_once(
    '    private var reportButton: Button? = null\n'
    '    private var finishSetupButton: Button? = null\n\n'
    '    @Volatile private var session: Session? = null\n'
    '    private val arState = ArSessionStateMachine()\n'
    '    private val closing = AtomicBoolean(false)\n'
    '    @Volatile private var glResumed = false\n',
    '    private var reportButton: Button? = null\n'
    '    private var finishSetupButton: Button? = null\n'
    '    private var retryArButton: Button? = null\n\n'
    '    @Volatile private var session: Session? = null\n'
    '    private val arState = ArSessionStateMachine()\n'
    '    private val closing = AtomicBoolean(false)\n'
    '    private val arStartScheduled = AtomicBoolean(false)\n'
    '    private val cameraPermissionRequestInFlight = AtomicBoolean(false)\n'
    '    private val sessionCloseInFlight = AtomicBoolean(false)\n'
    '    @Volatile private var activityResumed = false\n'
    '    @Volatile private var glResumed = false\n'
    '    @Volatile private var arProfile = ArSessionProfile.STANDARD\n'
    '    private var compatibilityFallbackUsed = false\n'
)

replace_region(
    '    private val cameraPermissionLauncher = registerForActivityResult',
    '\n\n    override fun onCreate',
    '''    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
'''
)

replace_once(
    '        errorActions.addView(action("Retry AR") { retryArSession() })\n',
    '        retryArButton = action("Retry AR") { retryArSession() }.also(errorActions::addView)\n'
)

replace_once(
    '''    override fun onResume() {
        super.onResume()
        if (closing.get()) return
        displayRotation.onResume()
        resumeArSession()
    }
''',
    '''    override fun onResume() {
        super.onResume()
        if (closing.get()) return
        activityResumed = true
        displayRotation.onResume()
        scheduleArStart()
    }
'''
)

replace_region(
    '    private fun createConfiguredSession()',
    '    private fun resumeGlIfNeeded()',
    '''    private fun createConfiguredSession(profile: ArSessionProfile): Session {
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
                        // Keep Cloud Anchors, which are required for shared localization, but remove
                        // optional camera/depth features that have caused resume-time failures on
                        // some current Samsung/Android combinations. The detector can still fall
                        // back to hit/ground estimation when Depth is disabled.
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
                onState = ::showDetail
            )
            session = created
            spatialApp.logger.info(
                "ARCore session configured",
                mapOf("mapId" to mapId, "profile" to profile.name, "cloudAnchors" to BuildConfig.CLOUD_ANCHORS_CONFIGURED)
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
        // If a permission request has just completed, let its callback own the delayed start. This
        // prevents onResume + ActivityResult from racing two Session.resume() calls.
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

'''
)

replace_region(
    '    private fun failArSession',
    '    override fun onPause()',
    '''    private fun failArSession(error: Throwable, phase: String) {
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
        retryArButton?.isEnabled = !sessionCloseInFlight.get()
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

'''
)

replace_once(
    '''    override fun onPause() {
        if (!closing.get()) {
''',
    '''    override fun onPause() {
        activityResumed = false
        if (!closing.get()) {
'''
)

replace_once(
    '''        arState.beginClosing()
        spatialApp.logger.info("AR teardown begin", mapOf("mapId" to mapId, "mode" to mode.name))
''',
    '''        activityResumed = false
        arStartScheduled.set(false)
        arState.beginClosing()
        retryArButton?.isEnabled = false
        spatialApp.logger.info("AR teardown begin", mapOf("mapId" to mapId, "mode" to mode.name))
'''
)

replace_region(
    '    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray)',
    '    private fun publishClientPose',
    '''    private fun updateProjectedTracks(cameraSite: FloatArray, worldFromSite: FloatArray) {
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
                sourceId = track.sourceId,
                bounds = projectTrackBounds(track, worldFromSite)
            )
        }
        overlay.updateTracks(projected)
    }

    /**
     * Project a class-sized, viewer-facing AR box from the shared 3D ground/contact position.
     * The source phone still shows the detector's raw image bounding box; this box is the stable
     * spatial representation that every localized participant can render from their own viewpoint.
     */
    private fun projectTrackBounds(track: SpatialTrack, worldFromSite: FloatArray): RectF? {
        val (heightMeters, aspect) = when (track.label.lowercase()) {
            "person" -> 1.72f to 0.40f
            "car" -> 1.50f to 1.85f
            // EfficientDet/COCO reports chickens as bird. A ~45 cm field box works well for the
            // chicken-sized targets this project is intended to visualize.
            "bird" -> 0.45f to 1.05f
            "dog" -> 0.70f to 1.25f
            "cat" -> 0.42f to 1.05f
            else -> 0.65f to 0.85f
        }
        val topSite = floatArrayOf(track.position[0], track.position[1] + heightMeters, track.position[2])
        val feetWorld = PoseMath.transformPoint(worldFromSite, track.position)
        val topWorld = PoseMath.transformPoint(worldFromSite, topSite)
        val feet = PoseMath.projectToScreen(viewProjectionMatrix, feetWorld, viewportWidth, viewportHeight) ?: return null
        val top = PoseMath.projectToScreen(viewProjectionMatrix, topWorld, viewportWidth, viewportHeight) ?: return null
        val rawHeight = kotlin.math.abs(feet.y - top.y)
        if (!rawHeight.isFinite() || rawHeight < 1f) return null
        val pixelHeight = rawHeight.coerceIn(14f, viewportHeight * 0.92f)
        val pixelWidth = (pixelHeight * aspect).coerceIn(12f, viewportWidth * 0.92f)
        val centerX = (feet.x + top.x) * 0.5f
        val topY = minOf(feet.y, top.y)
        val bottomY = maxOf(feet.y, top.y)
        return RectF(centerX - pixelWidth / 2f, topY, centerX + pixelWidth / 2f, bottomY)
    }

'''
)

replace_once(
    '        private const val AUTO_GROUND_INTERVAL_MS = 1_000L\n',
    '        private const val AUTO_GROUND_INTERVAL_MS = 1_000L\n'
    '        private const val PERMISSION_SETTLE_DELAY_MS = 450L\n'
    '        private const val ARCORE_RETRY_SETTLE_DELAY_MS = 550L\n'
)

path.write_text(text, encoding='utf-8')
print('patched ArActivity.kt')
