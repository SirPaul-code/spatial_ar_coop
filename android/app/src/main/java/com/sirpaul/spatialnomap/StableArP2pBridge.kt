package com.sirpaul.spatialnomap

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sirpaul.stablear.arcore.ArCoreAdapter
import com.sirpaul.stablear.arcore.CameraSample
import com.sirpaul.stablear.arcore.ObservationContext
import com.sirpaul.stablear.arcore.worldFromCv
import com.sirpaul.stablear.core.LockState
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.core.V3
import com.sirpaul.stablear.core.VisualObservation
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.nativevision.XFeatView
import com.sirpaul.stablear.vision.ImageMatch
import com.sirpaul.stablear.vision.LocalSurfaceTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * StableAR owner-thread bridge for the direct Wi-Fi-Aware Spatial Sync runtime.
 *
 * This class deliberately knows nothing about rooms, Wi-Fi Aware, peer alignment, shared transforms,
 * vehicles, or the wire protocol.  The existing P2P runtime continues to own all of that. StableAR
 * receives only a local ARCore Session/Frame plus an already chosen static material point.
 *
 * The product target id and the StableAR attachment id are separate. StableAR's immutable root pixel
 * is captured from the exact ARCore exposure used when a local target is placed (or from the first
 * trusted local observation after a remote target has been visually bootstrapped).
 */
class StableArP2pBridge(private val context: Context) {
    data class Refinement(
        val productTargetId: Long,
        val worldPoint: FloatArray,
        val state: LockState,
    )

    private data class VisionHint(val predicted: V2?, val view: XFeatView)
    private data class VisionResult(
        val lifecycle: Long,
        val context: ObservationContext,
        val match: P2pVisionMatch?,
    )

    private var ownerThreadId = 0L
    private var boundSession: Session? = null
    private var adapter: ArCoreAdapter? = null
    private val productToAttachment = LinkedHashMap<Long, Long>()
    private val attachmentToProduct = LinkedHashMap<Long, Long>()
    private val visualRoots = ConcurrentHashMap.newKeySet<Long>()

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "SpatialSync-StableAR")
    }
    private var vision: P2pVisionBackend? = null
    private val busy = AtomicBoolean(false)
    private val lifecycle = AtomicLong(1L)
    private val resetRequested = AtomicBoolean(false)
    private val results = ConcurrentLinkedQueue<VisionResult>()
    private val refinements = ConcurrentLinkedQueue<Refinement>()
    private var lastCameraTimestampNs = 0L
    private var trackingWasGood = false

    private fun owner() {
        check(ownerThreadId != 0L && ownerThreadId == Thread.currentThread().id) {
            "StableArP2pBridge must run on the AR/GL owner thread"
        }
    }

    /** Bind/rebind only from ArRenderer's GL owner thread. */
    fun onSession(session: Session) {
        if (boundSession === session && adapter != null) return
        if (adapter != null) owner()
        ownerThreadId = Thread.currentThread().id
        val oldAttachmentIds = attachmentToProduct.keys.toList()
        lifecycle.incrementAndGet()
        results.clear()
        refinements.clear()
        visualRoots.clear()
        productToAttachment.clear()
        attachmentToProduct.clear()
        runCatching { adapter?.close() }
        adapter = ArCoreAdapter(session)
        boundSession = session
        lastCameraTimestampNs = 0L
        trackingWasGood = false
        queueWorker {
            val backend = backend()
            oldAttachmentIds.forEach(backend::remove)
        }
    }

    /** Safe from Activity/UI threads; actual ARCore state reset happens on the next GL-owner frame. */
    fun invalidateAsync() {
        lifecycle.incrementAndGet()
        results.clear()
        refinements.clear()
        resetRequested.set(true)
    }

    fun onFrame(frame: Frame) {
        owner()
        applyPendingReset()
        val sdk = adapter ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            if (trackingWasGood) {
                lifecycle.incrementAndGet()
                results.clear()
                sdk.trackingLost()
            }
            trackingWasGood = false
            return
        }
        trackingWasGood = true
        drainResults()

        if (productToAttachment.isEmpty() || busy.get() || frame.timestamp <= 0L ||
            frame.timestamp == lastCameraTimestampNs
        ) return

        val sample = sdk.capture(frame) ?: return
        lastCameraTimestampNs = frame.timestamp
        val gray = sample.gray ?: return
        val contexts = attachmentToProduct.keys.mapNotNull { sdk.context(it, sample, frame) }
        if (contexts.isEmpty() || !busy.compareAndSet(false, true)) return

        val hints = contexts.associate { context ->
            val snapshot = sdk.engine.snapshot(context.id)
            val point = snapshot?.pointInAnchor()
            val currentPoint = point?.let { context.cameraInAnchor.inverse().point(it) }
            val rootPoint = point?.let { context.root.cameraInAnchor.inverse().point(it) }
            val predicted = currentPoint?.let { context.frame.intrinsics.project(it) }
            val direction = point?.let { p ->
                val targetToCamera = context.cameraInAnchor.t - p
                if (targetToCamera.norm() > 1e-6) targetToCamera.unit() else null
            }
            val rawScale = if (currentPoint != null && rootPoint != null &&
                currentPoint.z > .05 && rootPoint.z > .05
            ) rootPoint.z / currentPoint.z else 1.0
            context.id to VisionHint(
                predicted,
                XFeatView(
                    direction?.x,
                    direction?.y,
                    direction?.z,
                    rawScale.coerceIn(.5, 2.0),
                    1.0,
                ),
            )
        }
        val generation = lifecycle.get()
        queueWorker {
            try {
                if (generation != lifecycle.get()) return@queueWorker
                val backend = backend()
                backend.beginFrame(gray.timestampNs, gray.bytes, gray.width, gray.height)
                for (context in contexts) {
                    if (generation != lifecycle.get()) break
                    val hint = hints[context.id] ?: VisionHint(null, XFeatView())
                    val match = runCatching {
                        backend.track(context.id, hint.predicted, hint.view)
                    }.getOrNull()
                    if (generation == lifecycle.get()) {
                        results.add(VisionResult(generation, context, match))
                    } else {
                        match?.templateCandidateToken?.takeIf { it > 0L }?.let { token ->
                            backend.resolveTemplate(context.id, token, false)
                        }
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Seed from the product's already trusted ARCore hit/depth point. StableAR does not replace the
     * P2P app's robust placement policy; it starts from exactly that point and owns subsequent local
     * material-lock refinement.
     */
    fun seedFromWorldPoint(
        frame: Frame,
        productTargetId: Long,
        worldPoint: FloatArray,
        exactImagePixel: FloatArray? = null,
    ): Boolean {
        owner()
        applyPendingReset()
        if (productToAttachment.containsKey(productTargetId)) return true
        if (worldPoint.size < 3 || !worldPoint.take(3).all(Float::isFinite)) return false
        val sdk = adapter ?: return false
        if (frame.camera.trackingState != TrackingState.TRACKING || frame.timestamp <= 0L) return false

        val sample = sdk.capture(frame) ?: return false
        lastCameraTimestampNs = frame.timestamp
        val world = V3(worldPoint[0].toDouble(), worldPoint[1].toDouble(), worldPoint[2].toDouble())
        val cameraPoint = frame.camera.worldFromCv().inverse().point(world)
        val depthM = cameraPoint.z
        if (!depthM.isFinite() || depthM !in .15..8.0) return false

        val pixel = exactImagePixel
            ?.takeIf { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
            ?.let { V2(it[0].toDouble(), it[1].toDouble()) }
            ?.takeIf(sample.ref.intrinsics::contains)
            ?: sample.ref.intrinsics.project(cameraPoint)
            ?: return false
        if (!sample.ref.intrinsics.contains(pixel)) return false

        // Conservative integration prior, not a claim that ARCore exposes calibrated covariance.
        val sigmaM = max(.015, min(.06, depthM * .015))
        val placed = sdk.placeWithDepthPrior(sample, pixel, depthM, sigmaM) ?: return false
        val attachmentId = placed.attachment.id
        productToAttachment[productTargetId] = attachmentId
        attachmentToProduct[attachmentId] = productTargetId

        val gray = sample.gray
        if (gray != null) {
            val generation = lifecycle.get()
            queueWorker {
                if (generation != lifecycle.get()) return@queueWorker
                val added = runCatching {
                    backend().add(
                        attachmentId,
                        gray.timestampNs,
                        gray.bytes,
                        gray.width,
                        gray.height,
                        pixel,
                    )
                }.getOrDefault(false)
                if (added && generation == lifecycle.get()) visualRoots += attachmentId
            }
        }
        return true
    }

    fun hasAttachment(productTargetId: Long): Boolean {
        owner()
        return productToAttachment.containsKey(productTargetId)
    }

    /** True only after XFeat or the classical fallback captured immutable root evidence. */
    fun visualTrackingActive(productTargetId: Long): Boolean {
        owner()
        val id = productToAttachment[productTargetId] ?: return false
        return id in visualRoots
    }

    fun worldPoint(productTargetId: Long): FloatArray? {
        owner()
        val attachmentId = productToAttachment[productTargetId] ?: return null
        val p = adapter?.worldPoint(attachmentId) ?: return null
        return floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
    }

    fun remove(productTargetId: Long) {
        owner()
        val attachmentId = productToAttachment.remove(productTargetId) ?: return
        attachmentToProduct.remove(attachmentId)
        visualRoots.remove(attachmentId)
        runCatching { adapter?.remove(attachmentId) }
        queueWorker { backend().remove(attachmentId) }
    }

    fun clearOnOwnerThread() {
        owner()
        val ids = attachmentToProduct.keys.toList()
        ids.forEach { runCatching { adapter?.remove(it) } }
        productToAttachment.clear()
        attachmentToProduct.clear()
        visualRoots.clear()
        results.clear()
        refinements.clear()
        queueWorker {
            val backend = backend()
            ids.forEach(backend::remove)
        }
    }

    fun drainRefinements(): List<Refinement> {
        owner()
        val out = ArrayList<Refinement>()
        while (true) out += refinements.poll() ?: break
        return out
    }

    fun closeWorkersAsync() {
        lifecycle.incrementAndGet()
        results.clear()
        refinements.clear()
        queueWorker {
            runCatching { vision?.close() }
            vision = null
        }
        worker.shutdown()
    }

    private fun applyPendingReset() {
        if (!resetRequested.getAndSet(false)) return
        owner()
        val ids = attachmentToProduct.keys.toList()
        runCatching { adapter?.reset() }
        productToAttachment.clear()
        attachmentToProduct.clear()
        visualRoots.clear()
        results.clear()
        refinements.clear()
        lastCameraTimestampNs = 0L
        trackingWasGood = false
        queueWorker {
            val backend = backend()
            ids.forEach(backend::remove)
        }
    }

    private fun drainResults() {
        val sdk = adapter ?: return
        val templateResolutions = ArrayList<Triple<Long, Long, Boolean>>()
        while (true) {
            val result = results.poll() ?: break
            val staged = result.match?.templateCandidateToken ?: 0L
            if (result.lifecycle != lifecycle.get()) {
                if (staged > 0L) templateResolutions += Triple(result.context.id, staged, false)
                continue
            }
            val context = result.context
            if (context.id !in attachmentToProduct) {
                if (staged > 0L) templateResolutions += Triple(context.id, staged, false)
                continue
            }
            val match = result.match?.image
            if (match == null) {
                sdk.engine.visibility(context.id, false)
                continue
            }
            val priorState = sdk.engine.snapshot(context.id)?.state
            if (priorState == LockState.OCCLUDED || priorState == LockState.LOST) {
                sdk.engine.visibility(context.id, true)
            }
            val observation = VisualObservation(
                context.frame.id,
                context.root.epoch,
                context.root.anchorId,
                context.generation,
                context.frame.cameraTimestampNs,
                context.frame.capturedNs,
                context.cameraInAnchor,
                context.frame.intrinsics,
                match.pixel,
                match.inliers,
                match.forwardBackwardPx,
                match.medianReprojectionPx,
                match.sigmaPx,
            )
            val decision = sdk.observe(context.id, observation)
            if (staged > 0L) templateResolutions += Triple(context.id, staged, decision.accepted)
            if (decision.accepted) {
                val productId = attachmentToProduct[context.id] ?: continue
                val point = sdk.worldPoint(context.id) ?: continue
                refinements.add(
                    Refinement(
                        productId,
                        floatArrayOf(point.x.toFloat(), point.y.toFloat(), point.z.toFloat()),
                        decision.snapshot?.state ?: LockState.GEOMETRY_SUPPORTED,
                    ),
                )
            }
        }
        if (templateResolutions.isNotEmpty()) {
            val resolutions = templateResolutions.toList()
            queueWorker {
                val backend = backend()
                resolutions.forEach { (id, token, accepted) ->
                    backend.resolveTemplate(id, token, accepted)
                }
            }
        }
    }

    private fun backend(): P2pVisionBackend {
        check(Thread.currentThread().name == "SpatialSync-StableAR") {
            "StableAR vision backend belongs to its bounded worker thread"
        }
        return vision ?: P2pVisionBackend(context.applicationContext).also { vision = it }
    }

    private fun queueWorker(block: () -> Unit) {
        try {
            if (!worker.isShutdown) worker.execute(block)
        } catch (_: RejectedExecutionException) {
            // Activity shutdown raced the final worker handoff.
        }
    }
}

private data class P2pVisionMatch(
    val image: ImageMatch,
    val templateCandidateToken: Long = 0L,
)

/** XFeat/LiteRT first, then the SDK's LK/ORB fallback. Geometry never runs here. */
private class P2pVisionBackend(private val context: Context) : AutoCloseable {
    private val fallback = LocalSurfaceTracker()
    private val ids = linkedSetOf<Long>()
    private var xfeatAttempted = false
    private var xfeat: XFeatLiteRtTracker? = null
    private var currentFrameId = Long.MIN_VALUE
    private var currentGray: ByteArray? = null
    private var currentWidth = 0
    private var currentHeight = 0

    private fun learned(): XFeatLiteRtTracker? {
        if (!xfeatAttempted) {
            xfeatAttempted = true
            xfeat = XFeatLiteRtTracker.tryCreate(context, preferGpu = true)
        }
        return xfeat
    }

    fun beginFrame(id: Long, gray: ByteArray, width: Int, height: Int) {
        fallback.beginFrame(id, gray, width, height)
        currentFrameId = id
        currentGray = gray
        currentWidth = width
        currentHeight = height
        val ml = learned() ?: return
        try {
            if (!ml.beginFrame(id, gray, width, height)) disableLearned(ml)
        } catch (_: Throwable) {
            disableLearned(ml)
        }
    }

    /** Capture immutable root evidence from the exact placement exposure for both backends. */
    fun add(id: Long, frameId: Long, gray: ByteArray, width: Int, height: Int, pixel: V2): Boolean {
        val classical = fallback.add(id, gray, width, height, pixel)
        var learnedAdded = false
        val ml = learned()
        if (ml != null) {
            try {
                if (ml.beginFrame(frameId, gray, width, height)) {
                    learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                }
                currentGray?.let { live ->
                    if (currentFrameId != Long.MIN_VALUE && currentFrameId != frameId) {
                        ml.beginFrame(currentFrameId, live, currentWidth, currentHeight)
                    }
                }
            } catch (_: Throwable) {
                disableLearned(ml)
            }
        }
        if (learnedAdded || classical) ids += id
        return learnedAdded || classical
    }

    fun track(id: Long, predicted: V2?, view: XFeatView): P2pVisionMatch? {
        if (predicted != null) {
            val ml = xfeat
            if (ml != null) {
                try {
                    ml.track(id, predicted.x, predicted.y, view)?.let { match ->
                        val candidateView = view.copy(quality = match.meanReliability.coerceIn(0.0, 1.0))
                        val token = ml.stageTemplate(id, match.x, match.y, candidateView)
                        return P2pVisionMatch(
                            ImageMatch(
                                V2(match.x, match.y),
                                match.inliers,
                                match.consensusPxAtModelScale,
                                match.consensusPxAtModelScale,
                                "XFEAT_LITERT",
                                match.sigmaPx,
                            ),
                            token,
                        )
                    }
                } catch (_: Throwable) {
                    disableLearned(ml)
                }
            }
        }
        return fallback.track(id, predicted)?.let(::P2pVisionMatch)
    }

    fun resolveTemplate(id: Long, token: Long, accepted: Boolean) {
        if (token <= 0L) return
        val ml = xfeat ?: return
        try {
            if (accepted) ml.commitStagedTemplate(id, token)
            else ml.discardStagedTemplate(id, token)
        } catch (_: Throwable) {
            disableLearned(ml)
        }
    }

    fun remove(id: Long) {
        ids -= id
        fallback.remove(id)
        runCatching { xfeat?.remove(id) }
    }

    override fun close() {
        ids.toList().forEach(::remove)
        runCatching { xfeat?.close() }
        xfeat = null
        fallback.close()
    }

    private fun disableLearned(ml: XFeatLiteRtTracker) {
        runCatching { ml.close() }
        if (xfeat === ml) xfeat = null
    }
}
