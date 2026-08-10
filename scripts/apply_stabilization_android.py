#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
AR = ROOT / "android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt"


def sub_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return updated


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one literal match, got {count}")
    return text.replace(old, new, 1)


state_file = ROOT / "android/app/src/main/java/com/sirpaul/spatialarcoop/ar/ArSessionStateMachine.kt"
state_file.write_text(r'''package com.sirpaul.spatialarcoop.ar

import java.util.concurrent.atomic.AtomicReference

enum class ArSessionState {
    NEW,
    STARTING,
    RUNNING,
    PAUSING,
    PAUSED,
    FAILED,
    CLOSING,
    CLOSED
}

/**
 * Small cross-thread lifecycle gate shared by the Activity/UI thread and the GL renderer.
 * ARCore Session.update() is legal only while [canRender] is true.
 */
class ArSessionStateMachine(initial: ArSessionState = ArSessionState.NEW) {
    private val state = AtomicReference(initial)

    fun current(): ArSessionState = state.get()
    fun canRender(): Boolean = state.get() == ArSessionState.RUNNING

    fun beginStart(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.STARTING || current == ArSessionState.RUNNING ||
                current == ArSessionState.CLOSING || current == ArSessionState.CLOSED
            ) return false
            if (state.compareAndSet(current, ArSessionState.STARTING)) return true
        }
    }

    fun markRunning(): Boolean = state.compareAndSet(ArSessionState.STARTING, ArSessionState.RUNNING)

    fun beginPause(): Boolean = state.compareAndSet(ArSessionState.RUNNING, ArSessionState.PAUSING)

    fun markPaused() {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.FAILED || current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return
            if (current == ArSessionState.PAUSED) return
            if (state.compareAndSet(current, ArSessionState.PAUSED)) return
        }
    }

    /** Returns true only for the first transition into FAILED, preventing log/UI spam. */
    fun fail(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.FAILED || current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return false
            if (state.compareAndSet(current, ArSessionState.FAILED)) return true
        }
    }

    /** Returns true only to the caller that owns teardown. */
    fun beginClosing(): Boolean {
        while (true) {
            val current = state.get()
            if (current == ArSessionState.CLOSING || current == ArSessionState.CLOSED) return false
            if (state.compareAndSet(current, ArSessionState.CLOSING)) return true
        }
    }

    fun markClosed() {
        state.set(ArSessionState.CLOSED)
    }
}
''', encoding="utf-8")

theme_file = ROOT / "android/app/src/main/java/com/sirpaul/spatialarcoop/ui/FieldTheme.kt"
theme_file.write_text(r'''package com.sirpaul.spatialarcoop.ui

import android.graphics.Color

/** Quiet field-tool palette. Camera/map content remains the visual hero. */
object FieldTheme {
    val background: Int = Color.parseColor("#17181A")
    val surface: Int = Color.parseColor("#222428")
    val surfaceRaised: Int = Color.parseColor("#2A2D31")
    val textPrimary: Int = Color.parseColor("#F2EFE8")
    val textSecondary: Int = Color.parseColor("#B9B3A9")
    val accent: Int = Color.parseColor("#D59A4A")
    val statusBlue: Int = Color.parseColor("#7895B2")
    val error: Int = Color.parseColor("#D36B60")
    val divider: Int = Color.parseColor("#3A3C41")
}
''', encoding="utf-8")

test_file = ROOT / "android/app/src/test/java/com/sirpaul/spatialarcoop/ar/ArSessionStateMachineTest.kt"
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text(r'''package com.sirpaul.spatialarcoop.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArSessionStateMachineTest {
    @Test fun startAndRunEnablesRendering() {
        val machine = ArSessionStateMachine()
        assertTrue(machine.beginStart())
        assertEquals(ArSessionState.STARTING, machine.current())
        assertFalse(machine.canRender())
        assertTrue(machine.markRunning())
        assertTrue(machine.canRender())
    }

    @Test fun failureHardGatesRendererAndCanRetry() {
        val machine = ArSessionStateMachine()
        machine.beginStart()
        assertTrue(machine.fail())
        assertEquals(ArSessionState.FAILED, machine.current())
        assertFalse(machine.canRender())
        assertFalse(machine.fail())
        assertTrue(machine.beginStart())
        assertTrue(machine.markRunning())
        assertTrue(machine.canRender())
    }

    @Test fun pauseIsIdempotentAndStopsRendering() {
        val machine = ArSessionStateMachine()
        machine.beginStart(); machine.markRunning()
        assertTrue(machine.beginPause())
        assertFalse(machine.canRender())
        machine.markPaused()
        machine.markPaused()
        assertEquals(ArSessionState.PAUSED, machine.current())
    }

    @Test fun closingWinsAndCannotRestart() {
        val machine = ArSessionStateMachine()
        machine.beginStart(); machine.markRunning()
        assertTrue(machine.beginClosing())
        assertFalse(machine.beginClosing())
        assertFalse(machine.canRender())
        assertFalse(machine.beginStart())
        machine.markClosed()
        assertEquals(ArSessionState.CLOSED, machine.current())
        assertFalse(machine.beginClosing())
    }
}
''', encoding="utf-8")

text = AR.read_text(encoding="utf-8")

text = replace_once(text,
    "import android.opengl.GLSurfaceView\nimport android.os.Bundle\n",
    "import android.opengl.GLSurfaceView\nimport android.os.Build\nimport android.os.Bundle\nimport android.view.View\n",
    "android imports")
text = replace_once(text,
    "import androidx.activity.result.contract.ActivityResultContracts\n",
    "import androidx.activity.addCallback\nimport androidx.activity.result.contract.ActivityResultContracts\n",
    "activity callback import")
text = replace_once(text,
    "import com.google.ar.core.exceptions.CameraNotAvailableException\nimport com.google.ar.core.exceptions.NotYetAvailableException\n",
    "import com.google.ar.core.exceptions.CameraNotAvailableException\nimport com.google.ar.core.exceptions.FatalException\nimport com.google.ar.core.exceptions.NotYetAvailableException\nimport com.google.ar.core.exceptions.SessionPausedException\n",
    "arcore exception imports")
text = replace_once(text,
    "import com.sirpaul.spatialarcoop.ar.CameraBackgroundRenderer\n",
    "import com.sirpaul.spatialarcoop.ar.ArSessionState\nimport com.sirpaul.spatialarcoop.ar.ArSessionStateMachine\nimport com.sirpaul.spatialarcoop.ar.CameraBackgroundRenderer\n",
    "state imports")
text = replace_once(text,
    "import com.sirpaul.spatialarcoop.ui.ProjectedBox\n",
    "import com.sirpaul.spatialarcoop.ui.FieldTheme\nimport com.sirpaul.spatialarcoop.ui.ProjectedBox\n",
    "theme import")

text = replace_once(text,
    "    private lateinit var backgroundRenderer: CameraBackgroundRenderer\n\n    private var session: Session? = null\n",
    "    private lateinit var backgroundRenderer: CameraBackgroundRenderer\n    private lateinit var arErrorPanel: LinearLayout\n    private lateinit var arErrorText: TextView\n    private var reportButton: Button? = null\n\n    @Volatile private var session: Session? = null\n    private val arState = ArSessionStateMachine()\n    private val closing = AtomicBoolean(false)\n    @Volatile private var glResumed = false\n",
    "lifecycle fields")
text = replace_once(text,
    "    private var detector: ObjectDetectorEngine? = null\n    private var realtime: RealtimeClient? = null\n",
    "    private var detector: ObjectDetectorEngine? = null\n    @Volatile private var reporting = false\n    private var realtime: RealtimeClient? = null\n",
    "reporting field")

text = replace_once(text,
    "        configureModeComponents()\n        spatialApp.logger.info(\"AR mode opened\", mapOf(\"mapId\" to mapId, \"mode\" to mode.name))\n",
    "        configureModeComponents()\n        onBackPressedDispatcher.addCallback(this) { finishSafely() }\n        spatialApp.logger.info(\"AR mode opened\", mapOf(\"mapId\" to mapId, \"mode\" to mode.name))\n",
    "back dispatcher")

text = sub_once(text,
    r"    private fun configureModeComponents\(\) \{.*?\n    \}\n\n    private fun buildUi\(\): FrameLayout \{",
    r'''    private fun configureModeComponents() {
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
            apiToken = spatialApp.preferences.apiToken,
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

    private fun buildUi(): FrameLayout {''',
    "configure components")

text = sub_once(text,
    r"    private fun buildUi\(\): FrameLayout \{.*?\n        return root\n    \}\n\n    override fun onStart\(\)",
    r'''    private fun buildUi(): FrameLayout {
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

    override fun onStart()''',
    "build UI")

text = sub_once(text,
    r"    override fun onStart\(\) \{.*?\n    override fun onSurfaceCreated\(gl: GL10\?, config: EGLConfig\?\) \{",
    r'''    override fun onStart() {
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {''',
    "lifecycle block")

text = sub_once(text,
    r"    override fun onDrawFrame\(gl: GL10\?\) \{.*?\n    \}\n\n    private fun renderArFrame\(frame: Frame\) \{",
    r'''    override fun onDrawFrame(gl: GL10?) {
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

    private fun renderArFrame(frame: Frame) {''',
    "render gate")

text = replace_once(text,
    """        when (mode) {
            ArMode.MAP -> updateMapping(frame, worldFromSite, map)
            ArMode.SENSOR -> updateSensor(frame, worldFromSite, map)
            ArMode.VIEWER -> overlay.updateLocalBoxes(emptyList())
        }
""",
    """        when (mode) {
            ArMode.MAP -> updateMapping(frame, worldFromSite, map)
            ArMode.SENSOR -> updateSensor(frame, worldFromSite, map)
            ArMode.LIVE -> if (reporting) updateSensor(frame, worldFromSite, map) else overlay.updateLocalBoxes(emptyList())
            ArMode.VIEWER -> overlay.updateLocalBoxes(emptyList())
        }
""",
    "mode frame switch")

text = replace_once(text,
    "    private fun projectDetectionBoxes(frame: Frame, detections: List<Detection2D>): List<ProjectedBox> {\n",
    r'''    private fun ensureDetector() {
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
''',
    "report helpers")

text = replace_once(text,
    """                detailText.text = when (mode) {
                    ArMode.MAP -> "${featureQuality.name} features • $hosted anchors • $pending pending/rescan"
                    ArMode.SENSOR -> "$latestLocalTrackCount tracks • ${latestInferenceMs} ms inference • person / car / bird / dog / cat"
                    ArMode.VIEWER -> "Rendering cooperative tracks through occluders"
                }
""",
    """                detailText.text = when (mode) {
                    ArMode.MAP -> "${featureQuality.name.lowercase()} features · $hosted anchors · $pending pending/rescan"
                    ArMode.SENSOR -> "$latestLocalTrackCount tracks · ${latestInferenceMs} ms inference"
                    ArMode.LIVE -> if (reporting) "$latestLocalTrackCount live tracks · reporting" else "Observing shared tracks · reporting off"
                    ArMode.VIEWER -> "Observing shared tracks"
                }
""",
    "hud details")
text = replace_once(text,
    "            stateText.text = \"${mode.name} • $localization • ${frame.camera.trackingState}\"\n",
    "            stateText.text = \"${currentMap()?.name ?: mapId} · ${localization.lowercase().replaceFirstChar { it.uppercase() }} · ${frame.camera.trackingState.name.lowercase()}\"\n",
    "hud title")
text = replace_once(text,
    "            networkText.text = if (connected) \"Server: connected\" else \"Server: $detail\"\n            networkText.setTextColor(if (connected) Color.rgb(117, 231, 176) else Color.rgb(255, 184, 92))\n",
    "            networkText.text = if (connected) \"Server connected\" else \"Server: $detail\"\n            networkText.setTextColor(if (connected) FieldTheme.statusBlue else FieldTheme.accent)\n            if (connected && mode == ArMode.LIVE) realtime?.sendStatus(if (reporting) \"reporting\" else \"observing\")\n",
    "network status")
text = replace_once(text,
    "        setTextColor(Color.WHITE)\n        setOnClickListener { block() }\n",
    "        setTextColor(FieldTheme.textPrimary)\n        setOnClickListener { block() }\n",
    "action color")
text = replace_once(text,
    "enum class ArMode { MAP, SENSOR, VIEWER }",
    "enum class ArMode { MAP, SENSOR, VIEWER, LIVE }",
    "live enum")

AR.write_text(text, encoding="utf-8")
print("Android AR stabilization patch applied")
