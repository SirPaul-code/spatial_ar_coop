package com.sirpaul.spatialnomap

import android.content.Context
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.sirpaul.stablear.arcore.ArCoreAdapter
import com.sirpaul.stablear.arcore.CameraSample
import com.sirpaul.stablear.arcore.ObservationContext
import com.sirpaul.stablear.arcore.worldFromCv
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.core.V3
import com.sirpaul.stablear.core.VisualObservation
import com.sirpaul.stablear.nativevision.XFeatView
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Full StableAR material-attachment host integration for Spatial Sync.
 *
 * ARCore remains the global VIO/world owner. StableAR owns only static material
 * attachment evidence and bounded local correction. Cross-device SE(3), transport,
 * peer confirmation and dynamic vehicles deliberately stay in product code.
 *
 * All ArCoreAdapter calls stay on the GL/render owner thread. LiteRT/OpenCV work is
 * confined to one bounded worker. Remote targets use the legacy visual+metric
 * resolver only once to establish a trustworthy local material point; after that
 * StableAR owns ongoing attachment verification on this phone.
 */
internal class StableArSpatialRuntime(
    context: Context,
    private val coordinator: AlignmentCoordinator,
) {
    private data class Binding(
        val targetId: Long,
        val attachmentId: Long,
        val local: Boolean,
        var owner: String,
        var visionReady: Boolean = false,
        var lastSharedPoint: FloatArray? = null,
        var lastSharedMs: Long = 0L,
    )

    private data class PendingPromotion(
        val targetId: Long,
        val worldPoint: FloatArray,
        val owner: String,
    )

    private data class RootResult(
        val lifecycle: Long,
        val targetId: Long,
        val attachmentId: Long,
        val added: Boolean,
    )

    private data class VisionResult(
        val lifecycle: Long,
        val context: ObservationContext,
        val match: StableArVisionMatch?,
    )

    private data class VisionHint(
        val predicted: V2?,
        val view: XFeatView,
    )

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stablear-material-vision").apply { isDaemon = true }
    }
    private val frontend = StableArSurfaceFrontend(appContext)
    private val busy = AtomicBoolean(false)
    private val detachRequested = AtomicBoolean(false)
    private val lifecycle = AtomicLong(1L)
    private val rootResults = ConcurrentLinkedQueue<RootResult>()
    private val visionResults = ConcurrentLinkedQueue<VisionResult>()

    // GL-thread-owned state below.
    private var ownerThreadId = Long.MIN_VALUE
    private var boundSession: Session? = null
    private var adapter: ArCoreAdapter? = null
    private val bindings = LinkedHashMap<Long, Binding>()
    private val attachmentToTarget = LinkedHashMap<Long, Long>()
    private val pendingPromotions = LinkedHashMap<Long, PendingPromotion>()
    private var lastVisionFrameNs = 0L
    private var closed = false

    fun bind(session: Session) {
        owner()
        if (closed) return
        if (detachRequested.getAndSet(false) || boundSession !== session) {
            releaseAdapterOnOwnerThread()
            boundSession = session
            adapter = ArCoreAdapter(session)
        }
    }

    fun requestDetach() {
        detachRequested.set(true)
        lifecycle.incrementAndGet()
    }

    fun clearOnOwnerThread() {
        owner()
        if (closed) return
        lifecycle.incrementAndGet()
        bindings.clear()
        attachmentToTarget.clear()
        pendingPromotions.clear()
        rootResults.clear()
        visionResults.clear()
        runCatching { adapter?.reset() }
        worker.execute { runCatching { frontend.clear() } }
    }

    fun trackingLost() {
        owner()
        runCatching { adapter?.trackingLost() }
    }

    fun hasAttachment(targetId: Long): Boolean {
        owner()
        return bindings[targetId]?.visionReady == true
    }

    fun removeTarget(targetId: Long) {
        owner()
        pendingPromotions.remove(targetId)
        val binding = bindings.remove(targetId) ?: return
        attachmentToTarget.remove(binding.attachmentId)
        runCatching { adapter?.remove(binding.attachmentId) }
        worker.execute { runCatching { frontend.remove(binding.attachmentId) } }
    }

    /**
     * Create the local manual target from the exact tap exposure. StableAR's own
     * depth/surface fitter is preferred. If it cannot bootstrap, the already-vetted
     * ARCore/metric world hit supplies only the initial camera-Z depth prior.
     */
    fun attachLocal(
        targetId: Long,
        frame: Frame,
        camera: Camera,
        imagePixel: FloatArray,
        worldPoint: FloatArray,
        ownerName: String,
    ): Boolean {
        owner()
        if (closed || imagePixel.size < 2 || worldPoint.size < 3) return false
        val sdk = adapter ?: return false
        removeTarget(targetId)
        val sample = sdk.capture(frame, copyGray = true) ?: return false
        val pixel = V2(imagePixel[0].toDouble(), imagePixel[1].toDouble())
        val placement = sdk.place(sample, pixel)
            ?: placeWithWorldPrior(sdk, sample, camera, pixel, worldPoint)
            ?: return false
        val gray = sample.gray
        if (gray == null) {
            sdk.remove(placement.attachment.id)
            return false
        }
        val binding = Binding(
            targetId = targetId,
            attachmentId = placement.attachment.id,
            local = true,
            owner = ownerName,
            lastSharedPoint = worldPoint.copyOf(3),
        )
        bindings[targetId] = binding
        attachmentToTarget[binding.attachmentId] = targetId
        enqueueRoot(binding, gray.timestampNs, gray.bytes, gray.width, gray.height, pixel)
        return true
    }

    /**
     * A remote phone's image cannot become a local StableAR root because its ARCore
     * pose belongs to another world. The old resolver is therefore retained only as
     * a bootstrap proof. Once it verifies a local world point, queue promotion from
     * the next exact local ARCore exposure.
     */
    fun promoteRemote(targetId: Long, worldPoint: FloatArray, ownerName: String) {
        owner()
        if (closed || worldPoint.size < 3 || bindings[targetId]?.visionReady == true) return
        pendingPromotions[targetId] = PendingPromotion(targetId, worldPoint.copyOf(3), ownerName)
    }

    fun onFrame(frame: Frame, camera: Camera) {
        owner()
        if (closed) return
        if (detachRequested.getAndSet(false)) {
            releaseAdapterOnOwnerThread()
            return
        }
        val sdk = adapter ?: return

        drainRootResults(sdk)
        drainVisionResults(sdk)

        val ready = bindings.values.any { it.visionReady }
        val needPromotion = pendingPromotions.isNotEmpty()
        val due = frame.timestamp > 0L && frame.timestamp - lastVisionFrameNs >= VISION_INTERVAL_NS
        if (!needPromotion && (!ready || !due)) return

        val sample = sdk.capture(frame, copyGray = true) ?: return
        promotePendingTargets(sdk, sample, camera)
        drainRootResults(sdk)

        if (!due) return
        val gray = sample.gray ?: return
        val contexts = bindings.values
            .asSequence()
            .filter { it.visionReady }
            .mapNotNull { binding -> sdk.context(binding.attachmentId, sample, frame) }
            .toList()
        if (contexts.isEmpty() || !busy.compareAndSet(false, true)) return

        lastVisionFrameNs = frame.timestamp
        val hints = contexts.associate { context -> context.id to buildHint(sdk, context) }
        val generation = lifecycle.get()
        worker.execute {
            try {
                frontend.beginFrame(gray.timestampNs, gray.bytes, gray.width, gray.height)
                contexts.forEach { observationContext ->
                    if (generation != lifecycle.get()) return@forEach
                    val hint = hints[observationContext.id] ?: VisionHint(null, XFeatView())
                    val match = runCatching {
                        frontend.track(observationContext.id, hint.predicted, hint.view)
                    }.getOrNull()
                    visionResults.add(VisionResult(generation, observationContext, match))
                }
            } finally {
                busy.set(false)
            }
        }
    }

    fun worldPoint(targetId: Long): FloatArray? {
        owner()
        val binding = bindings[targetId]?.takeIf { it.visionReady } ?: return null
        val point = runCatching { adapter?.worldPoint(binding.attachmentId) }.getOrNull() ?: return null
        return floatArrayOf(point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
    }

    /** Worker resources can be shut down from Activity teardown; ARCore state is owner-thread released later. */
    fun shutdownVision() {
        if (closed) return
        closed = true
        detachRequested.set(true)
        lifecycle.incrementAndGet()
        worker.execute { runCatching { frontend.close() } }
        worker.shutdown()
    }

    private fun promotePendingTargets(sdk: ArCoreAdapter, sample: CameraSample, camera: Camera) {
        if (pendingPromotions.isEmpty()) return
        val iterator = pendingPromotions.entries.iterator()
        while (iterator.hasNext()) {
            val (_, pending) = iterator.next()
            val existing = bindings[pending.targetId]
            if (existing?.visionReady == true) {
                iterator.remove()
                continue
            }
            // Root capture is already queued; do not create a second attachment while it is pending.
            if (existing != null) continue
            val cameraPoint = camera.worldFromCv().inverse().point(pending.worldPoint.v3())
            val pixel = sample.ref.intrinsics.project(cameraPoint) ?: continue
            if (!sample.ref.intrinsics.contains(pixel) || cameraPoint.z !in MIN_STABLE_DEPTH_M..MAX_STABLE_DEPTH_M) continue

            val placement = sdk.place(sample, pixel)
                ?: sdk.placeWithDepthPrior(sample, pixel, cameraPoint.z, HOST_DEPTH_PRIOR_SIGMA_M)
                ?: continue
            val gray = sample.gray
            if (gray == null) {
                sdk.remove(placement.attachment.id)
                continue
            }
            val binding = Binding(
                targetId = pending.targetId,
                attachmentId = placement.attachment.id,
                local = false,
                owner = pending.owner,
            )
            bindings[pending.targetId] = binding
            attachmentToTarget[binding.attachmentId] = pending.targetId
            enqueueRoot(binding, gray.timestampNs, gray.bytes, gray.width, gray.height, pixel)
            iterator.remove()
        }
    }

    private fun enqueueRoot(
        binding: Binding,
        frameId: Long,
        gray: ByteArray,
        width: Int,
        height: Int,
        pixel: V2,
    ) {
        val generation = lifecycle.get()
        worker.execute {
            val added = runCatching {
                frontend.add(binding.attachmentId, frameId, gray, width, height, pixel)
            }.getOrDefault(false)
            rootResults.add(RootResult(generation, binding.targetId, binding.attachmentId, added))
        }
    }

    private fun drainRootResults(sdk: ArCoreAdapter) {
        while (true) {
            val result = rootResults.poll() ?: break
            val binding = bindings[result.targetId]
            if (result.lifecycle != lifecycle.get() || binding?.attachmentId != result.attachmentId) {
                worker.execute { runCatching { frontend.remove(result.attachmentId) } }
                continue
            }
            if (result.added) {
                binding.visionReady = true
            } else {
                bindings.remove(result.targetId)
                attachmentToTarget.remove(result.attachmentId)
                sdk.remove(result.attachmentId)
                worker.execute { runCatching { frontend.remove(result.attachmentId) } }
            }
        }
    }

    private fun drainVisionResults(sdk: ArCoreAdapter) {
        val templateResolutions = ArrayList<Triple<Long, Long, Boolean>>()
        while (true) {
            val result = visionResults.poll() ?: break
            val staged = result.match?.templateCandidateToken ?: 0L
            val binding = attachmentToTarget[result.context.id]?.let(bindings::get)
            if (result.lifecycle != lifecycle.get() || binding == null || !binding.visionReady) {
                if (staged > 0L) templateResolutions += Triple(result.context.id, staged, false)
                continue
            }
            val visual = result.match
            if (visual == null) {
                sdk.engine.visibility(result.context.id, false)
                continue
            }
            val match = visual.image
            val context = result.context
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
            if (decision.accepted && binding.local) publishLocalCorrection(sdk, binding)
        }
        if (templateResolutions.isNotEmpty()) {
            val copy = templateResolutions.toList()
            worker.execute {
                copy.forEach { (id, token, accepted) ->
                    runCatching { frontend.resolveTemplate(id, token, accepted) }
                }
            }
        }
    }

    private fun publishLocalCorrection(sdk: ArCoreAdapter, binding: Binding) {
        val p = sdk.worldPoint(binding.attachmentId) ?: return
        val point = floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        val previous = binding.lastSharedPoint
        val now = System.currentTimeMillis()
        if (previous != null && distance(previous, point) < RESHARE_MIN_M) return
        if (now - binding.lastSharedMs < RESHARE_INTERVAL_MS) return
        if (coordinator.sendPoi(binding.targetId, point, binding.owner)) {
            binding.lastSharedPoint = point
            binding.lastSharedMs = now
        }
    }

    private fun buildHint(sdk: ArCoreAdapter, context: ObservationContext): VisionHint {
        val snapshot = sdk.engine.snapshot(context.id)
        val point = snapshot?.pointInAnchor()
        val pointNow = point?.let { context.cameraInAnchor.inverse().point(it) }
        val pointRoot = point?.let { context.root.cameraInAnchor.inverse().point(it) }
        val predicted = pointNow?.let { context.frame.intrinsics.project(it) }
        val direction = point?.let { p ->
            val targetToCamera = context.cameraInAnchor.t - p
            if (targetToCamera.norm() > 1e-6) targetToCamera.unit() else null
        }
        val rawScale = if (pointNow != null && pointRoot != null && pointNow.z > .05 && pointRoot.z > .05) {
            pointRoot.z / pointNow.z
        } else {
            1.0
        }
        return VisionHint(
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

    private fun placeWithWorldPrior(
        sdk: ArCoreAdapter,
        sample: CameraSample,
        camera: Camera,
        pixel: V2,
        worldPoint: FloatArray,
    ) = camera.worldFromCv().inverse().point(worldPoint.v3()).let { cameraPoint ->
        if (cameraPoint.z !in MIN_STABLE_DEPTH_M..MAX_STABLE_DEPTH_M) null
        else sdk.placeWithDepthPrior(sample, pixel, cameraPoint.z, HOST_DEPTH_PRIOR_SIGMA_M)
    }

    private fun releaseAdapterOnOwnerThread() {
        owner()
        lifecycle.incrementAndGet()
        bindings.clear()
        attachmentToTarget.clear()
        pendingPromotions.clear()
        rootResults.clear()
        visionResults.clear()
        runCatching { adapter?.close() }
        adapter = null
        boundSession = null
        lastVisionFrameNs = 0L
        worker.execute { runCatching { frontend.clear() } }
    }

    private fun owner() {
        val current = Thread.currentThread().id
        if (ownerThreadId == Long.MIN_VALUE) ownerThreadId = current
        check(ownerThreadId == current) { "StableArSpatialRuntime must stay on the AR render thread" }
    }

    private fun FloatArray.v3() = V3(
        getOrElse(0) { 0f }.toDouble(),
        getOrElse(1) { 0f }.toDouble(),
        getOrElse(2) { 0f }.toDouble(),
    )

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        // Core currently supports material depth only in this bounded interval.
        private const val MIN_STABLE_DEPTH_M = 0.15
        private const val MAX_STABLE_DEPTH_M = 8.0
        // Conditional host seed model, not a calibrated ARCore covariance claim.
        private const val HOST_DEPTH_PRIOR_SIGMA_M = 0.03
        private const val VISION_INTERVAL_NS = 120_000_000L
        private const val RESHARE_MIN_M = 0.010f
        private const val RESHARE_INTERVAL_MS = 500L
    }
}
