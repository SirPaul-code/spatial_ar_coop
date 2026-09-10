package com.sirpaul.showme

import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sirpaul.stablear.arcore.ArCoreAdapter
import com.sirpaul.stablear.arcore.CameraSample
import com.sirpaul.stablear.arcore.ObservationContext
import com.sirpaul.stablear.core.LockState
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.core.V3
import com.sirpaul.stablear.core.VisualObservation
import com.sirpaul.stablear.vision.ImageMatch
import com.sirpaul.stablear.vision.LocalSurfaceTracker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

/**
 * Owner-thread bridge between ShowMe's existing ARCore/WebRTC pipeline and StableAR.
 *
 * ARCoreAdapter is created and touched only by the GL owner thread. OpenCV receives copied
 * immutable grayscale/context values on one bounded worker. A ShowMe video frame ID is never
 * treated as a StableAR FrameRef ID; the explicit registry below is the only handoff.
 *
 * IMPORTANT: the pixel transform used here is the same ShowMeGeometry.uprightToRaw() function
 * used by RtcVideoPipe when it renders the camera into the WebRTC frame. There is deliberately no
 * second rotation/crop implementation at the StableAR boundary.
 */
class StableArShowMeBridge(private val notice: (String) -> Unit) {
    data class Placement(
        val attachmentId: Long,
        val rootWorld: FloatArray,
        val sourceVideoFrameId: Long,
        val initialDepthM: Double,
        val mode: String,
    )

    private data class FrameValue(val sample: CameraSample, val clockwiseRotation: Int)
    private data class TrackResult(
        val lifecycle: Long,
        val showMeEpoch: Int,
        val context: ObservationContext,
        val match: ImageMatch?,
        val cvMs: Double,
    )
    private data class Diagnostic(
        val attachmentId: Long,
        val sourceVideoFrameId: Long,
        val sourceCapturedNs: Long,
        val initialDepthM: Double,
        val placementMode: String,
        var acceptedCorrections: Int = 0,
        var rejectedCorrections: Int = 0,
        var visualFailures: Int = 0,
        var lastMethod: String = "NONE",
        var lastResidualPx: Double = Double.NaN,
        var lastCvMs: Double = 0.0,
        var lastReason: String = "Placed from exact video frame",
    )

    private var ownerThreadId = 0L
    private var boundSession: Session? = null
    private var adapter: ArCoreAdapter? = null
    private val registry = StableArFrameRegistry<FrameValue>()
    // Do not touch OpenCV native code while ShowMeActivity is still constructing its UI.
    // OpenCVLoader.initLocal() runs later in onCreate, before any AR frame can reach this bridge.
    private val tracker by lazy(LazyThreadSafetyMode.NONE) { LocalSurfaceTracker() }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "ShowMe-StableAR-CV")
    }
    private val busy = AtomicBoolean(false)
    private val lifecycle = AtomicLong(1L)
    private val invalidateFrames = AtomicBoolean(false)
    private val results = ConcurrentLinkedQueue<TrackResult>()
    private val diagnostics = LinkedHashMap<Long, Diagnostic>()
    private var lastSample: CameraSample? = null
    private var lastCameraTimestampNs = 0L
    private var lastShowMeEpoch = 0
    private var lastTracking = false
    private var frozenSourceRefId: Long? = null
    private var lastDiagnosticsNs = 0L
    @Volatile private var diagnosticsText = "{}"

    private fun owner() {
        check(ownerThreadId != 0L && ownerThreadId == Thread.currentThread().id) {
            "StableArShowMeBridge must be used on the AR/GL owner thread"
        }
    }

    fun onSession(session: Session) {
        if (boundSession === session && adapter != null) return
        if (adapter != null) owner()
        val oldIds = diagnostics.keys.toList()
        lifecycle.incrementAndGet()
        results.clear()
        registry.clear()
        frozenSourceRefId = null
        lastSample = null
        lastCameraTimestampNs = 0L
        lastShowMeEpoch = 0
        lastTracking = false
        adapter?.close()
        diagnostics.clear()
        queueTrackerRemove(oldIds)
        ownerThreadId = Thread.currentThread().id
        boundSession = session
        adapter = ArCoreAdapter(session)
    }

    /** Called from any thread before/while the GL view is being paused. */
    fun invalidateExternalFramesAsync() {
        lifecycle.incrementAndGet()
        results.clear()
        invalidateFrames.set(true)
    }

    /** Keep attachments, but invalidate browser frame leases and stale worker observations. */
    private fun invalidateExternalFramesOnOwner() {
        owner()
        val source = frozenSourceRefId
        if (source != null) runCatching { adapter?.unfreeze(source) }
        frozenSourceRefId = null
        registry.clear()
        lastSample = null
        lastCameraTimestampNs = 0L
        results.clear()
    }

    fun onFrame(frame: Frame, showMeEpoch: Int, enabled: Boolean) {
        owner()
        if (invalidateFrames.getAndSet(false)) invalidateExternalFramesOnOwner()
        if (!enabled) return
        val sdk = adapter ?: return
        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        if (!tracking) {
            if (lastTracking) {
                lifecycle.incrementAndGet()
                results.clear()
                sdk.trackingLost()
            }
            lastTracking = false
            lastSample = null
            return
        }
        lastTracking = true
        drainResults(showMeEpoch)
        if (frame.timestamp <= 0L || frame.timestamp == lastCameraTimestampNs) return
        val sample = sdk.capture(frame) ?: return
        lastSample = sample
        lastCameraTimestampNs = frame.timestamp
        lastShowMeEpoch = showMeEpoch
        scheduleVisualChecks(sample, frame, showMeEpoch)
        updateDiagnostics()
    }

    /** Bind only when the video encoder is about to submit this exact ARCore exposure. */
    fun bindVideoFrame(videoFrameId: Long, showMeEpoch: Int, clockwiseRotation: Int, sourceTimestampNs: Long): Boolean {
        owner()
        val sample = lastSample ?: return false
        if (clockwiseRotation !in setOf(0, 90, 180, 270)) return false
        if (showMeEpoch != lastShowMeEpoch || sourceTimestampNs != lastCameraTimestampNs ||
            sample.ref.cameraTimestampNs != sourceTimestampNs) return false
        registry.bind(StableVideoBinding(videoFrameId, showMeEpoch, sample.ref.id, sample.ref.cameraTimestampNs,
            sample.ref.capturedNs, FrameValue(sample, clockwiseRotation)))
        return true
    }

    /** Freeze is marshalled from SessionApi to this owner thread. Repeated calls do not renew leases. */
    fun freeze(videoFrameId: Long, showMeEpoch: Int, currentShowMeEpoch: Int): Boolean {
        owner()
        if (showMeEpoch != currentShowMeEpoch) return false
        val sdk = adapter ?: return false
        val binding = registry.freeze(videoFrameId, showMeEpoch) ?: return false
        if (frozenSourceRefId == binding.sourceFrameId) return true
        if (frozenSourceRefId != null) {
            registry.unfreeze()
            return false
        }
        val held = sdk.freeze(binding.sourceFrameId)
        if (held == null || held != binding.value.sample.ref) {
            registry.unfreeze()
            return false
        }
        frozenSourceRefId = binding.sourceFrameId
        return true
    }

    fun unfreeze() {
        owner()
        val binding = registry.unfreeze()
        val source = frozenSourceRefId ?: binding?.sourceFrameId
        if (source != null) runCatching { adapter?.unfreeze(source) }
        frozenSourceRefId = null
    }

    /**
     * The production placement path. It cannot call the conservative SDK-only place() method.
     * Browser normalized coordinates are converted with the exact same rotation mapping used by
     * RtcVideoPipe, then handed to the interactive StableAR depth/layer resolver.
     */
    fun place(videoFrameId: Long, showMeEpoch: Int, normalizedRoot: DoubleArray): Placement? {
        owner()
        if (normalizedRoot.size != 2 || normalizedRoot.any { !it.isFinite() || it !in 0.0..1.0 }) {
            notice("StableAR placement rejected: invalid browser coordinates")
            return null
        }
        val sdk = adapter ?: run {
            notice("StableAR placement rejected: AR session is not ready")
            return null
        }
        val binding = registry.resolve(videoFrameId, showMeEpoch) ?: run {
            notice("StableAR placement rejected: exact video frame is no longer retained")
            return null
        }
        val sample = binding.value.sample
        if (sdk.history.get(binding.sourceFrameId) != sample.ref) {
            notice("StableAR placement rejected: exact AR frame lease expired")
            return null
        }

        // RtcVideoPipe uses ShowMeGeometry.uprightToRaw() before ARCore's IMAGE_PIXELS -> texture
        // transform. Reuse that SAME mapping here, so the pixel helper clicked is the pixel fitted.
        val raw = ShowMeGeometry.uprightToRaw(
            normalizedRoot[0].toFloat(), normalizedRoot[1].toFloat(), binding.value.clockwiseRotation)
        val k = sample.ref.intrinsics
        val x = (raw[0].toDouble() * k.width).coerceIn(0.0, k.width - 1.0)
        val y = (raw[1].toDouble() * k.height).coerceIn(0.0, k.height - 1.0)
        val sensorPixel = V2(x, y)

        val attempt = sdk.placeInteractive(sample, sensorPixel)
        val placed = attempt.placement ?: run {
            notice("StableAR placement rejected: ${attempt.reason}")
            return null
        }
        val world = sdk.worldPoint(placed.attachment.id) ?: run {
            sdk.remove(placed.attachment.id)
            notice("StableAR placement rejected: ARCore lost the new anchor immediately")
            return null
        }
        diagnostics[placed.attachment.id] = Diagnostic(
            placed.attachment.id,
            videoFrameId,
            sample.ref.capturedNs,
            placed.fit.depth,
            attempt.mode,
            lastReason = attempt.reason,
        )
        val gray = sample.gray
        if (gray != null && gray.width == sample.ref.intrinsics.width && gray.height == sample.ref.intrinsics.height) {
            val id = placed.attachment.id
            queueWorker {
                runCatching { tracker.add(id, gray.bytes, gray.width, gray.height, sensorPixel) }
            }
        } else {
            diagnostics[placed.attachment.id]?.lastReason =
                "${attempt.reason}; exact-size visual reference unavailable"
        }
        updateDiagnostics(force = true)
        notice("StableAR ${attempt.mode.lowercase(Locale.US)}: ${"%.2f".format(Locale.US, placed.fit.depth)} m")
        return Placement(placed.attachment.id, world.floatArray(), videoFrameId, placed.fit.depth, attempt.mode)
    }

    fun worldPoint(attachmentId: Long): FloatArray? {
        owner()
        return adapter?.worldPoint(attachmentId)?.floatArray()
    }

    fun isGeometrySupported(attachmentId: Long): Boolean {
        owner()
        return adapter?.engine?.snapshot(attachmentId)?.state == LockState.GEOMETRY_SUPPORTED
    }

    fun remove(attachmentId: Long) {
        owner()
        adapter?.remove(attachmentId)
        diagnostics.remove(attachmentId)
        queueWorker { tracker.remove(attachmentId) }
        updateDiagnostics(force = true)
    }

    fun clearAttachments() {
        owner()
        val ids = diagnostics.keys.toList()
        ids.forEach { adapter?.remove(it) }
        diagnostics.clear()
        ids.forEach { id -> queueWorker { tracker.remove(id) } }
        updateDiagnostics(force = true)
    }

    /** World/session replacement: no StableAR attachment or retained frame may survive it. */
    fun resetWorld() {
        owner()
        val ids = diagnostics.keys.toList()
        lifecycle.incrementAndGet()
        results.clear()
        registry.clear()
        frozenSourceRefId = null
        lastSample = null
        lastCameraTimestampNs = 0L
        lastShowMeEpoch = 0
        adapter?.reset()
        diagnostics.clear()
        queueTrackerRemove(ids)
        updateDiagnostics(force = true)
    }

    /** Remote session ended; marks stay, but browser frame/freeze authority is revoked. */
    fun endRemoteSession() {
        owner()
        lifecycle.incrementAndGet()
        results.clear()
        unfreeze()
        registry.clear()
        lastSample = null
        lastCameraTimestampNs = 0L
    }

    fun diagnosticsJson(): String = diagnosticsText

    /** Must be called via GLSurfaceView.queueEvent before the AR Session is finally closed. */
    fun releaseOnOwnerThread() {
        if (adapter == null) return
        owner()
        val ids = diagnostics.keys.toList()
        lifecycle.incrementAndGet()
        results.clear()
        registry.clear()
        adapter?.close()
        adapter = null
        boundSession = null
        diagnostics.clear()
        frozenSourceRefId = null
        lastSample = null
        lastCameraTimestampNs = 0L
        queueTrackerRemove(ids)
        diagnosticsText = "{}"
    }

    fun closeWorkers() {
        lifecycle.incrementAndGet()
        results.clear()
        if (!worker.isShutdown) {
            queueWorker { runCatching { tracker.close() } }
            worker.shutdown()
        }
    }

    private fun scheduleVisualChecks(sample: CameraSample, frame: Frame, showMeEpoch: Int) {
        val sdk = adapter ?: return
        val gray = sample.gray ?: return
        if (diagnostics.isEmpty() || gray.width != sample.ref.intrinsics.width || gray.height != sample.ref.intrinsics.height ||
            !busy.compareAndSet(false, true)) return
        val contexts = diagnostics.keys.mapNotNull { sdk.context(it, sample, frame) }
        if (contexts.isEmpty()) { busy.set(false); return }
        val predictions = contexts.associate { context ->
            val predicted = sdk.engine.snapshot(context.id)?.let { snapshot ->
                context.frame.intrinsics.project(context.cameraInAnchor.inverse().point(snapshot.pointInAnchor()))
            }
            context.id to predicted
        }
        val generation = lifecycle.get()
        queueWorker {
            try {
                if (generation != lifecycle.get()) return@queueWorker
                tracker.beginFrame(sample.ref.id, gray.bytes, gray.width, gray.height)
                for (context in contexts) {
                    if (generation != lifecycle.get()) break
                    val start = System.nanoTime()
                    val match = runCatching { tracker.track(context.id, predictions[context.id]) }.getOrNull()
                    val cvMs = (System.nanoTime() - start) / 1_000_000.0
                    if (generation == lifecycle.get()) results.add(TrackResult(generation, showMeEpoch, context, match, cvMs))
                }
            } finally { busy.set(false) }
        }
    }

    private fun drainResults(showMeEpoch: Int) {
        val sdk = adapter ?: return
        while (true) {
            val result = results.poll() ?: break
            if (result.lifecycle != lifecycle.get() || result.showMeEpoch != showMeEpoch) continue
            val context = result.context
            val diag = diagnostics[context.id] ?: continue
            val before = sdk.engine.snapshot(context.id) ?: continue
            if (before.generation != context.generation) continue
            diag.lastCvMs = result.cvMs
            val match = result.match
            if (match == null) {
                sdk.engine.visibility(context.id, false)
                diag.visualFailures++
                diag.lastMethod = "LOST"
                diag.lastResidualPx = Double.NaN
                diag.lastReason = "Visual evidence unavailable; world hypothesis retained"
                continue
            }
            if (before.state == LockState.OCCLUDED || before.state == LockState.LOST) sdk.engine.visibility(context.id, true)
            val observation = VisualObservation(context.frame.id, context.root.epoch, context.root.anchorId,
                context.generation, context.frame.cameraTimestampNs, context.frame.capturedNs,
                context.cameraInAnchor, context.frame.intrinsics, match.pixel, match.inliers,
                match.forwardBackwardPx, match.medianReprojectionPx)
            val decision = sdk.observe(context.id, observation)
            diag.lastMethod = match.method
            diag.lastResidualPx = match.medianReprojectionPx
            diag.lastReason = decision.reason
            if (decision.accepted) diag.acceptedCorrections++
            else if (!decision.reason.startsWith("Collecting")) diag.rejectedCorrections++
        }
    }

    private fun updateDiagnostics(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastDiagnosticsNs < 500_000_000L) return
        lastDiagnosticsNs = now
        val sdk = adapter
        val rows = JSONArray()
        diagnostics.values.forEach { d ->
            val snapshot = sdk?.engine?.snapshot(d.attachmentId)
            val current = sdk?.worldPoint(d.attachmentId)
            val initial = sdk?.initialWorldPoint(d.attachmentId)
            val displacement = if (current != null && initial != null) (current - initial).norm() else Double.NaN
            rows.put(JSONObject()
                .put("attachmentId", d.attachmentId)
                .put("frameId", d.sourceVideoFrameId)
                .put("generation", snapshot?.generation ?: JSONObject.NULL)
                .put("state", snapshot?.state?.name ?: "REMOVED")
                .put("placementMode", d.placementMode)
                .put("initialDepthM", d.initialDepthM)
                .put("currentDepthM", snapshot?.depthM ?: JSONObject.NULL)
                .put("correctionDisplacementM", if (displacement.isFinite()) displacement else JSONObject.NULL)
                .put("acceptedCorrections", d.acceptedCorrections)
                .put("rejectedCorrections", d.rejectedCorrections)
                .put("visualFailures", d.visualFailures)
                .put("visualMethod", d.lastMethod)
                .put("visualResidualPx", if (d.lastResidualPx.isFinite()) d.lastResidualPx else JSONObject.NULL)
                .put("frameAgeMs", ((now - d.sourceCapturedNs).coerceAtLeast(0L) / 1_000_000.0).roundToLong())
                .put("cvProcessingMs", d.lastCvMs)
                .put("reason", d.lastReason))
        }
        diagnosticsText = JSONObject().put("enabled", true).put("sdk", "0.1.0-research")
            .put("physicalAccuracyMeasured", false).put("attachments", rows).toString()
    }

    private fun queueTrackerRemove(ids: List<Long>) {
        if (ids.isEmpty()) return
        queueWorker { ids.forEach { tracker.remove(it) } }
    }

    private fun queueWorker(block: () -> Unit) {
        if (worker.isShutdown) return
        try { worker.execute(block) } catch (_: RejectedExecutionException) { }
    }

    private fun V3.floatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
}
