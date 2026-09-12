package com.sirpaul.spatialarcoop.stablear

import android.content.Context
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.ar.PoseMath
import com.sirpaul.stablear.arcore.ArCoreAdapter
import com.sirpaul.stablear.arcore.CameraSample
import com.sirpaul.stablear.arcore.ObservationContext
import com.sirpaul.stablear.arcore.worldFromCv
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.core.V3
import com.sirpaul.stablear.core.VisualObservation
import com.sirpaul.stablear.nativevision.XFeatView
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

internal data class StableArRefinedMarker(
    val markerId: String,
    val sitePosition: FloatArray,
    val expiresAtMs: Long,
    val broadcast: Boolean,
    val acceptedCorrection: Boolean
)

internal data class StableArRuntimeStatus(
    val attachments: Int,
    val learnedBackendActive: Boolean,
    val acceptedCorrections: Int,
    val lastMethod: String,
    val benchmarkPath: String?
)

/**
 * Bridges the independently buildable StableAR SDK into Spatial AR's shared SITE coordinate frame.
 * Cloud Anchors/networking remain host concerns. StableAR owns only local static-material attachment.
 *
 * ARCoreAdapter calls stay on the GL/render thread. LiteRT/OpenCV work stays on one bounded worker.
 */
internal class StableArCoordinator(
    context: Context,
    session: Session,
    benchmarkEnabled: Boolean,
    benchmarkPhase: String,
    private val onRefinedMarker: (StableArRefinedMarker) -> Unit
) : AutoCloseable {
    private data class MarkerState(
        val markerId: String,
        var expiresAtMs: Long,
        val broadcast: Boolean,
        var lastMethod: String = "ROOT",
        var lastLatencyMs: Double? = null,
        var visualVisible: Boolean = false
    )

    private data class RemoteSeed(
        val markerId: String,
        val sitePosition: FloatArray,
        val expiresAtMs: Long
    )

    private data class VisionHint(val predicted: V2?, val view: XFeatView)
    private data class VisionResult(
        val lifecycle: Long,
        val context: ObservationContext,
        val match: StableArVisionMatch?,
        val latencyMs: Double
    )

    private val appContext = context.applicationContext
    private val adapter = ArCoreAdapter(session)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "stablear-vision") }
    private var workerBackend: StableArVisionBackend? = null // worker-thread only
    private val busy = AtomicBoolean(false)
    private val lifecycle = AtomicLong(1L)
    private val answers = ConcurrentLinkedQueue<VisionResult>()
    private val remoteQueue = ConcurrentLinkedQueue<RemoteSeed>()
    private val pendingRemote = linkedMapOf<String, RemoteSeed>() // GL thread only
    private val states = linkedMapOf<Long, MarkerState>() // GL thread only
    private val markerToAttachment = linkedMapOf<String, Long>() // GL thread only
    private val benchmark = StableArBenchmarkRecorder(appContext, benchmarkEnabled, benchmarkPhase)
    private var benchmarkAttachmentId: Long? = null
    private var corrections = 0
    private var learnedActive = false
    private var lastMethod = "idle"
    private var closed = false

    private fun backend(): StableArVisionBackend {
        check(Thread.currentThread().name == "stablear-vision")
        return workerBackend ?: StableArVisionBackend(appContext).also { workerBackend = it }
    }

    /** Called from the realtime callback; actual AR state changes are deferred to the GL owner. */
    fun queueRemoteMarker(markerId: String, sitePosition: FloatArray, expiresAtMs: Long) {
        if (closed || markerId.isBlank() || sitePosition.size < 3) return
        remoteQueue.add(RemoteSeed(markerId, sitePosition.copyOf(3), expiresAtMs))
    }

    /**
     * Seed a local StableAR attachment from Spatial AR's already-estimated shared SITE point.
     * The SDK uses the projected material pixel as immutable identity and the host point only as
     * the initial metric depth prior. This deliberately avoids StableAR Lab's strict placement gate.
     */
    fun placeFromSitePoint(
        frame: Frame,
        worldFromSite: FloatArray,
        sitePoint: FloatArray,
        markerId: String,
        expiresAtMs: Long,
        conditionalSigmaM: Double = 0.08
    ): Long? {
        if (closed || frame.camera.trackingState != TrackingState.TRACKING || sitePoint.size < 3) return null
        markerToAttachment[markerId]?.let { return it }
        val sample = adapter.capture(frame) ?: return null
        val gray = sample.gray ?: return null
        val world = PoseMath.transformPoint(worldFromSite, sitePoint)
        val cv = frame.camera.worldFromCv().inverse().point(V3(world[0].toDouble(), world[1].toDouble(), world[2].toDouble()))
        val pixel = sample.ref.intrinsics.project(cv) ?: return null
        val placement = adapter.placeWithDepthPrior(sample, pixel, cv.z, conditionalSigmaM) ?: return null
        val id = placement.attachment.id
        states[id] = MarkerState(markerId, expiresAtMs, broadcast = true)
        markerToAttachment[markerId] = id
        if (benchmarkAttachmentId == null) {
            benchmarkAttachmentId = id
            benchmark.recordRoot(gray, sample.ref.intrinsics, pixel)
        }
        worker.execute {
            runCatching { backend().add(id, gray.timestampNs, gray.bytes, gray.width, gray.height, pixel) }
        }
        currentSitePoint(id, worldFromSite)?.let { refined ->
            onRefinedMarker(StableArRefinedMarker(markerId, refined, expiresAtMs, true, false))
        }
        return id
    }

    fun onFrame(frame: Frame, worldFromSite: FloatArray) {
        if (closed) return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            adapter.trackingLost()
            return
        }
        drainRemoteQueue()
        drainVisionResults(worldFromSite)
        removeExpired()

        if (states.isEmpty() && pendingRemote.isEmpty()) return
        val sample = adapter.capture(frame) ?: return
        seedVisibleRemoteMarkers(frame, sample, worldFromSite)
        updateRefinedMarkerOutputs(worldFromSite)
        recordBenchmark(frame, sample)
        scheduleVision(frame, sample)
    }

    private fun drainRemoteQueue() {
        while (true) {
            val value = remoteQueue.poll() ?: break
            val existing = markerToAttachment[value.markerId]
            if (existing != null) {
                states[existing]?.expiresAtMs = value.expiresAtMs
            } else {
                pendingRemote[value.markerId] = value
            }
        }
    }

    private fun seedVisibleRemoteMarkers(frame: Frame, sample: CameraSample, worldFromSite: FloatArray) {
        val gray = sample.gray ?: return
        if (pendingRemote.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = pendingRemote.iterator()
        while (iterator.hasNext()) {
            val (_, seed) = iterator.next()
            if (seed.expiresAtMs <= now) {
                iterator.remove()
                continue
            }
            val world = PoseMath.transformPoint(worldFromSite, seed.sitePosition)
            val cv = frame.camera.worldFromCv().inverse().point(V3(world[0].toDouble(), world[1].toDouble(), world[2].toDouble()))
            val pixel = sample.ref.intrinsics.project(cv) ?: continue
            if (!sample.ref.intrinsics.contains(pixel) || cv.z !in 0.15..8.0) continue

            // A remote SITE prediction may contain Cloud-Anchor/global alignment error. Seed a local
            // visual identity only when ARCore depth near the predicted pixel agrees with its depth.
            val nearby = sample.depth.asSequence()
                .filter { (it.pixel - pixel).norm() <= 28.0 }
                .minByOrNull { (it.pixel - pixel).norm() }
                ?: continue
            if (abs(nearby.z - cv.z) > max(0.08, cv.z * 0.08)) continue

            val placement = adapter.placeWithDepthPrior(
                sample = sample,
                pixel = pixel,
                depthM = cv.z,
                conditionalSigmaM = max(0.04, abs(nearby.z - cv.z) + 0.02),
                evidence = setOf(nearby.evidence)
            ) ?: continue
            val id = placement.attachment.id
            states[id] = MarkerState(seed.markerId, seed.expiresAtMs, broadcast = false)
            markerToAttachment[seed.markerId] = id
            worker.execute {
                runCatching { backend().add(id, gray.timestampNs, gray.bytes, gray.width, gray.height, pixel) }
            }
            iterator.remove()
        }
    }

    private fun scheduleVision(frame: Frame, sample: CameraSample) {
        val gray = sample.gray ?: return
        if (states.isEmpty() || !busy.compareAndSet(false, true)) return
        val contexts = states.keys.mapNotNull { adapter.context(it, sample, frame) }
        if (contexts.isEmpty()) {
            busy.set(false)
            return
        }
        val hints = contexts.associate { context ->
            val snapshot = adapter.engine.snapshot(context.id)
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
            } else 1.0
            context.id to VisionHint(
                predicted,
                XFeatView(direction?.x, direction?.y, direction?.z, rawScale.coerceIn(.5, 2.0), 1.0)
            )
        }
        val generation = lifecycle.get()
        worker.execute {
            try {
                val vision = backend()
                vision.beginFrame(gray.timestampNs, gray.bytes, gray.width, gray.height)
                learnedActive = vision.learnedBackendActive
                contexts.forEach { context ->
                    val hint = hints[context.id] ?: VisionHint(null, XFeatView())
                    val start = System.nanoTime()
                    val match = vision.track(context.id, hint.predicted, hint.view)
                    val latency = (System.nanoTime() - start) / 1e6
                    if (generation == lifecycle.get()) answers.add(VisionResult(generation, context, match, latency))
                }
            } catch (_: Throwable) {
                // Product tracking must survive learned/fallback frontend failures.
            } finally {
                busy.set(false)
            }
        }
    }

    private fun drainVisionResults(worldFromSite: FloatArray) {
        val resolutions = mutableListOf<Triple<Long, Long, Boolean>>()
        while (true) {
            val result = answers.poll() ?: break
            val staged = result.match?.templateCandidateToken ?: 0L
            if (result.lifecycle != lifecycle.get()) {
                if (staged > 0) resolutions += Triple(result.context.id, staged, false)
                continue
            }
            val state = states[result.context.id] ?: run {
                if (staged > 0) resolutions += Triple(result.context.id, staged, false)
                continue
            }
            val match = result.match
            state.lastLatencyMs = result.latencyMs
            if (match == null) {
                state.visualVisible = false
                adapter.engine.visibility(result.context.id, false)
                continue
            }
            state.visualVisible = true
            state.lastMethod = match.image.method
            lastMethod = match.image.method
            val c = result.context
            val image = match.image
            val observation = VisualObservation(
                c.frame.id,
                c.root.epoch,
                c.root.anchorId,
                c.generation,
                c.frame.cameraTimestampNs,
                c.frame.capturedNs,
                c.cameraInAnchor,
                c.frame.intrinsics,
                image.pixel,
                image.inliers,
                image.forwardBackwardPx,
                image.medianReprojectionPx,
                image.sigmaPx
            )
            val decision = adapter.observe(c.id, observation)
            if (decision.accepted) {
                corrections++
                currentSitePoint(c.id, worldFromSite)?.let { site ->
                    onRefinedMarker(
                        StableArRefinedMarker(
                            markerId = state.markerId,
                            sitePosition = site,
                            expiresAtMs = state.expiresAtMs,
                            broadcast = state.broadcast,
                            acceptedCorrection = true
                        )
                    )
                }
            }
            if (staged > 0) resolutions += Triple(c.id, staged, decision.accepted)
        }
        if (resolutions.isNotEmpty()) {
            val copy = resolutions.toList()
            worker.execute {
                copy.forEach { (id, token, accepted) -> runCatching { backend().resolveTemplate(id, token, accepted) } }
            }
        }
    }

    private fun updateRefinedMarkerOutputs(worldFromSite: FloatArray) {
        // Remote annotations are never written back to the canonical shared point. Their StableAR
        // correction is local to this viewer. Creator-owned markers may publish accepted corrections.
        states.forEach { (id, state) ->
            if (!state.broadcast) {
                currentSitePoint(id, worldFromSite)?.let { site ->
                    onRefinedMarker(StableArRefinedMarker(state.markerId, site, state.expiresAtMs, false, false))
                }
            }
        }
    }

    private fun currentSitePoint(id: Long, worldFromSite: FloatArray): FloatArray? {
        val world = adapter.worldPoint(id) ?: return null
        return PoseMath.transformPoint(
            PoseMath.rigidInverse(worldFromSite),
            floatArrayOf(world.x.toFloat(), world.y.toFloat(), world.z.toFloat())
        )
    }

    private fun recordBenchmark(frame: Frame, sample: CameraSample) {
        val id = benchmarkAttachmentId ?: return
        val gray = sample.gray ?: return
        val cameraFromWorld = frame.camera.worldFromCv().inverse()
        fun project(world: V3?): V2? = world?.let { sample.ref.intrinsics.project(cameraFromWorld.point(it)) }
        val stock = project(adapter.initialWorldPoint(id))
        val stable = project(adapter.worldPoint(id))
        val state = states[id]
        benchmark.record(
            gray = gray,
            intrinsics = sample.ref.intrinsics,
            stock = stock,
            stable = stable,
            stableMethod = state?.lastMethod ?: "ROOT",
            stableLatencyMs = state?.lastLatencyMs,
            acceptedCorrections = corrections,
            trackingState = frame.camera.trackingState.name
        )
    }

    private fun removeExpired() {
        val now = System.currentTimeMillis()
        val expired = states.filterValues { it.expiresAtMs <= now }.keys.toList()
        expired.forEach { id ->
            val state = states.remove(id) ?: return@forEach
            markerToAttachment.remove(state.markerId)
            adapter.remove(id)
            worker.execute { runCatching { backend().remove(id) } }
            if (benchmarkAttachmentId == id) benchmarkAttachmentId = null
        }
        pendingRemote.entries.removeAll { it.value.expiresAtMs <= now }
    }

    /** Clear pending geometric transactions when host ARCore tracking pauses. GL owner thread only. */
    fun trackingLost() { adapter.trackingLost() }

    fun status(): StableArRuntimeStatus = StableArRuntimeStatus(
        attachments = states.size,
        learnedBackendActive = learnedActive,
        acceptedCorrections = corrections,
        lastMethod = lastMethod,
        benchmarkPath = benchmark.sessionPath
    )

    override fun close() {
        if (closed) return
        closed = true
        lifecycle.incrementAndGet()
        answers.clear()
        remoteQueue.clear()
        pendingRemote.clear()
        states.clear()
        markerToAttachment.clear()
        adapter.close()
        worker.execute {
            runCatching { workerBackend?.close() }
            workerBackend = null
        }
        worker.shutdown()
        benchmark.close()
    }
}
