package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Precision-first shared-world coordinator.
 *
 * Both phones must now obtain their own visual/metric solution. A transform sent
 * by the peer is evidence to compare against, never an authority that can create a
 * lock by itself. RTT is a hard sanity gate while fresh, and matched depth from both
 * cameras is an independent 3D consistency gate. Once a POI exists the canonical
 * transform is frozen so background refinement cannot drag a physical marker.
 */
class AlignmentCoordinator(
    private val transport: WifiAwarePeerTransport,
    private val listener: Listener,
) {
    data class Quality(
        val confidence: Float = 0f,
        val inliers: Int = 0,
        val correspondences: Int = 0,
        val medianReprojectionPx: Double = Double.NaN,
        val imageCoverage: Double = 0.0,
        val stableCount: Int = 0,
        val localReady: Boolean = false,
        val peerReady: Boolean = false,
        val peerTransformVerified: Boolean = false,
        val rangeM: Float? = null,
        val rangeDeltaM: Float? = null,
        val rangeSource: String = "NONE",
        val headingResidualDeg: Double = Double.NaN,
        val gravityTiltDeg: Double = Double.NaN,
        val sensorPriorConfidence: Float = 0f,
        val fusionSource: String = "VISION",
        val fusionSeedConfidence: Float = 0f,
        val keyframesLocal: Int = 0,
        val keyframesRemote: Int = 0,
        val metricPairs: Int = 0,
        val metricInliers: Int = 0,
        val medianMetricResidualM: Double = Double.NaN,
    ) {
        val bothReady: Boolean get() = localReady && peerReady && peerTransformVerified
    }

    interface Listener {
        fun onAlignmentQuality(quality: Quality)
        fun onRemotePoi(id: Long, pointLocal: FloatArray?, owner: String, confidence: Float)
        fun onPoiCleared()
    }

    private data class Candidate(
        val transform: DoubleArray,
        val confidence: Float,
    )

    private data class FramePair(
        val remote: CapturedFrame,
        val local: CapturedFrame,
        val score: Double,
    )

    private val solveExecutor = Executors.newSingleThreadExecutor()
    private val solving = AtomicBoolean(false)
    private val solveSerial = AtomicLong(0)
    private val frameLock = Any()
    private val localFrames = ArrayDeque<CapturedFrame>()
    private val remoteFrames = ArrayDeque<CapturedFrame>()
    private val candidateHistory = ArrayDeque<Candidate>()
    private val refinementHistory = ArrayDeque<Candidate>()

    @Volatile private var lockedTransform: DoubleArray? = null
    @Volatile private var coarseConfidence = 0f
    @Volatile private var coarseSource = "NONE"
    @Volatile private var stableCount = 0
    @Volatile private var localConfidence = 0f
    @Volatile private var peerReady = false
    @Volatile private var peerTransformVerified = false
    @Volatile private var pendingPeerTransform: WireMessage.Quality? = null
    @Volatile private var latestRangeM: Float? = null
    @Volatile private var latestRangeStdM: Float? = null
    @Volatile private var latestRangeSource = "NONE"
    @Volatile private var lastRttAtMs = 0L
    @Volatile private var lastRangeAtMs = 0L
    @Volatile private var latestQuality = Quality()
    @Volatile private var pendingPoi: WireMessage.Poi? = null
    @Volatile private var activePoi = false
    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastSolveStartedMs = 0L
    @Volatile private var lastTransformBroadcastAtMs = 0L
    @Volatile private var lastLockInliers = 0
    @Volatile private var lastLockReprojectionPx = Float.NaN
    @Volatile private var lastLockSource = "VISION"

    fun onConnected() {
        connectedAtMs = System.currentTimeMillis()
        resetAlignment(clearFrames = true, clearPoi = false)
    }

    fun onDisconnected() {
        resetAlignment(clearFrames = true, clearPoi = true)
        peerReady = false
        peerTransformVerified = false
        emitQuality()
    }

    fun onCameraChanged(reason: String = "camera changed") {
        resetAlignment(clearFrames = true, clearPoi = true)
        transport.sendAlignmentReset(reason)
    }

    fun onPeerAlignmentReset(reason: String = "peer AR state changed") {
        resetAlignment(clearFrames = true, clearPoi = true)
    }

    fun onLocalFrame(frame: CapturedFrame) {
        synchronized(frameLock) { addDiverseKeyframe(localFrames, frame) }
        transport.sendFrame(frame)
        updateFusionSeed()
        tryVerifyPeerTransform()
        maybeResendLockedTransform()
        maybeSolve()
    }

    fun onRemoteFrame(frame: CapturedFrame) {
        synchronized(frameLock) { addDiverseKeyframe(remoteFrames, frame) }
        updateFusionSeed()
        tryVerifyPeerTransform()
        maybeResendLockedTransform()
        maybeSolve()
    }

    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (distanceM.isFinite() && distanceM in 0.05f..250f) {
            latestRangeM = distanceM
            latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
            latestRangeSource = "RTT"
            val now = System.currentTimeMillis()
            lastRttAtMs = now
            lastRangeAtMs = now
            updateFusionSeed()
            emitQuality()
        }
    }

    fun onBleRange(distanceM: Float, stdDevM: Float, rssiDbm: Int) {
        if (!distanceM.isFinite() || distanceM !in 0.05f..30f) return
        val now = System.currentTimeMillis()
        if (now - lastRttAtMs <= RTT_FRESH_MS) return
        latestRangeM = distanceM
        latestRangeStdM = max(if (stdDevM.isFinite()) stdDevM else 1.0f, 0.75f)
        latestRangeSource = "BLE"
        lastRangeAtMs = now
        updateFusionSeed()
        emitQuality()
    }

    fun onPeerQuality(message: WireMessage.Quality) {
        if (message.senderFromPeer != null) {
            peerReady = true
            pendingPeerTransform = message
            if (message.transformSource != "PEER_ACK" && !activePoi) {
                peerTransformVerified = false
            }
            tryVerifyPeerTransform()
        } else if (message.ready) {
            peerReady = true
        } else if (!peerTransformVerified) {
            peerReady = false
        }
        emitQuality()
    }

    fun onRemotePoi(message: WireMessage.Poi) {
        pendingPoi = message
        activePoi = true
        publishPendingPoiIfPossible()
    }

    fun clearPoi(sendToPeer: Boolean = true) {
        pendingPoi = null
        activePoi = false
        if (sendToPeer) transport.sendClearPoi()
        listener.onPoiCleared()
    }

    fun sendPoi(id: Long, pointLocalWorld: FloatArray, owner: String): Boolean {
        if (!canPlacePoi()) return false
        transport.sendPoi(id, owner, pointLocalWorld)
        activePoi = true
        return true
    }

    fun sendPoi(pointLocalWorld: FloatArray, owner: String): Boolean =
        sendPoi(System.nanoTime(), pointLocalWorld, owner)

    fun canPlacePoi(): Boolean = transport.connected && latestQuality.bothReady
    fun quality(): Quality = latestQuality
    fun onTrackingState(tracking: Boolean) = Unit

    fun close() {
        solveExecutor.shutdownNow()
    }

    private fun addDiverseKeyframe(window: ArrayDeque<CapturedFrame>, frame: CapturedFrame) {
        val last = window.peekLast()
        if (last == null) {
            window.addLast(frame)
            return
        }

        val motionQuality = frameMotionQuality(frame)
        val lastMotionQuality = frameMotionQuality(last)
        val (translationM, rotationDeg) = cameraPoseDelta(last.pose, frame.pose)
        val lastNs = frameClockNs(last)
        val nowNs = frameClockNs(frame)
        val elapsedNs = if (lastNs > 0L && nowNs > lastNs) nowNs - lastNs else Long.MAX_VALUE
        val materiallyBetterDepth = frame.metricPoints.size >= max(64, (last.metricPoints.size * 1.20).toInt())
        val calmer = motionQuality > lastMotionQuality + 0.14
        val spatiallyNew = translationM >= KEYFRAME_TRANSLATION_M || rotationDeg >= KEYFRAME_ROTATION_DEG
        val temporallyNew = elapsedNs >= KEYFRAME_MAX_INTERVAL_NS
        val motionUsable = motionQuality >= MIN_KEYFRAME_MOTION_QUALITY

        if (motionUsable && (spatiallyNew || temporallyNew)) {
            window.addLast(frame)
        } else if (motionUsable && (materiallyBetterDepth || calmer)) {
            window.removeLast()
            window.addLast(frame)
        }
        while (window.size > KEYFRAME_WINDOW) window.removeFirst()
    }

    private fun frameClockNs(frame: CapturedFrame): Long {
        val sensorNs = frame.sensors.elapsedRealtimeNs
        return if (sensorNs > 0L) sensorNs else frame.timestampNs
    }

    private fun cameraPoseDelta(a: PosePacket, b: PosePacket): Pair<Double, Double> {
        val dx = b.t.getOrElse(0) { 0f } - a.t.getOrElse(0) { 0f }
        val dy = b.t.getOrElse(1) { 0f } - a.t.getOrElse(1) { 0f }
        val dz = b.t.getOrElse(2) { 0f } - a.t.getOrElse(2) { 0f }
        val translation = sqrt((dx * dx + dy * dy + dz * dz).toDouble())

        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until 4) {
            val qa = a.q.getOrElse(i) { if (i == 3) 1f else 0f }.toDouble()
            val qb = b.q.getOrElse(i) { if (i == 3) 1f else 0f }.toDouble()
            dot += qa * qb
            na += qa * qa
            nb += qb * qb
        }
        val denom = sqrt(na * nb)
        val normalizedDot = if (denom > 1e-9) abs(dot / denom).coerceIn(0.0, 1.0) else 1.0
        val rotationDeg = Math.toDegrees(2.0 * acos(normalizedDot))
        return Pair(translation, rotationDeg)
    }

    private fun maybeSolve() {
        if (!transport.connected) return
        val currentLock = lockedTransform
        val refining = currentLock != null

        // The host is canonical for ongoing refinement, but BOTH phones solve the
        // initial transform independently so READY cannot come from algebraic ACKs.
        if (refining && !transport.isHostRole) return
        if (refining && activePoi) return

        val now = System.currentTimeMillis()
        if (!refining && !transport.isHostRole && pendingPeerTransform == null &&
            now - connectedAtMs < CLIENT_INITIAL_SOLVE_DELAY_MS
        ) return

        val pairs = buildFramePairs()
        if (pairs.isEmpty()) return
        val minInterval = if (refining) REFINEMENT_SOLVE_INTERVAL_MS else SOLVE_MIN_INTERVAL_MS
        if (now - lastSolveStartedMs < minInterval || !solving.compareAndSet(false, true)) return
        lastSolveStartedMs = now
        val serial = solveSerial.incrementAndGet()

        solveExecutor.execute {
            var best: AlignmentEngine.Result? = null
            try {
                val attempts = if (refining) REFINEMENT_PAIR_ATTEMPTS else MAX_PAIR_ATTEMPTS
                for (pair in pairs.take(attempts)) {
                    val result = solveVisionAuthoritative(pair) ?: continue
                    if (best == null || resultScore(result) > resultScore(best!!)) best = result
                    if (!refining && isSingleFrameLockQuality(result)) break
                    if (refining && isStrongRefinementQuality(result)) break
                }
                if (serial == solveSerial.get()) {
                    if (refining) acceptRefinement(best) else acceptInitialResult(best)
                }
            } finally {
                solving.set(false)
                if (!refining && serial == solveSerial.get() && lockedTransform == null) maybeSolve()
            }
        }
    }

    private fun solveVisionAuthoritative(pair: FramePair): AlignmentEngine.Result? {
        val remoteVision = pair.remote.copy(
            sensors = pair.remote.sensors.copy(headingDeg = Float.NaN, orientationQuality = 0f),
        )
        val localVision = pair.local.copy(
            sensors = pair.local.sensors.copy(headingDeg = Float.NaN, orientationQuality = 0f),
        )
        val raw = try {
            AlignmentEngine.solve(remoteVision, localVision)
        } catch (_: Throwable) {
            null
        } ?: return null

        val yawPrior = FusionMath.yawPrior(pair.remote, pair.local)
        return raw.copy(
            headingResidualDeg = FusionMath.yawResidualDeg(raw.transformLocalFromRemote, yawPrior),
            sensorPriorConfidence = yawPrior?.confidence ?: 0f,
        )
    }

    private fun buildFramePairs(): List<FramePair> {
        val locals: List<CapturedFrame>
        val remotes: List<CapturedFrame>
        synchronized(frameLock) {
            locals = localFrames.toList()
            remotes = remoteFrames.toList()
        }
        if (locals.isEmpty() || remotes.isEmpty()) return emptyList()

        val out = ArrayList<FramePair>(locals.size * remotes.size)
        for ((li, local) in locals.withIndex()) {
            val localAge = (li + 1).toDouble() / locals.size
            for ((ri, remote) in remotes.withIndex()) {
                val remoteAge = (ri + 1).toDouble() / remotes.size
                val recency = localAge + remoteAge
                val temporalAffinity = (1.0 - abs(localAge - remoteAge)).coerceIn(0.0, 1.0)
                val support = min(min(remote.metricPoints.size, local.metricPoints.size), 6000) / 6000.0
                val motionScore = min(frameMotionQuality(local), frameMotionQuality(remote))
                val score = recency * 0.38 + temporalAffinity * 0.78 + support * 1.15 + motionScore * 0.48
                out += FramePair(remote, local, score)
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun frameMotionQuality(frame: CapturedFrame): Double {
        val g = frame.sensors.gyroRadS
        if (g.size < 3 || !g[0].isFinite() || !g[1].isFinite() || !g[2].isFinite()) return 0.55
        val magnitude = sqrt(
            g[0].toDouble() * g[0] + g[1].toDouble() * g[1] + g[2].toDouble() * g[2],
        )
        return (1.0 / (1.0 + magnitude * 0.45)).coerceIn(0.12, 1.0)
    }

    @Synchronized private fun acceptInitialResult(result: AlignmentEngine.Result?) {
        if (lockedTransform != null) return
        if (result == null) {
            emitQuality()
            return
        }

        val confidence = max(result.confidence, geometricConfidence(result))
        val rangeDelta = latestRangeM?.let { range -> abs(result.predictedDeviceDistanceM.toFloat() - range) }
        val gravityOk = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= MAX_VISUAL_GRAVITY_TILT_DEG
        val rangeOk = isRangeCompatible(result)
        val metricOk = isMetricConsistent(result)
        val passes = result.inliers >= MIN_LOCK_INLIERS &&
            result.correspondences >= MIN_LOCK_CORRESPONDENCES &&
            result.medianReprojectionPx <= MAX_LOCK_REPROJECTION_PX &&
            result.imageCoverage >= MIN_LOCK_COVERAGE &&
            confidence >= MIN_LOCK_CONFIDENCE && gravityOk && rangeOk && metricOk

        localConfidence = confidence
        if (passes) {
            candidateHistory.addLast(Candidate(result.transformLocalFromRemote.copyOf(), confidence))
            while (candidateHistory.size > CANDIDATE_WINDOW) candidateHistory.removeFirst()

            val cluster = strongestCluster(candidateHistory, CLUSTER_TRANSLATION_M, CLUSTER_ROTATION_DEG)
            stableCount = cluster.size
            val singleStrong = isSingleFrameLockQuality(result)
            val robustCandidate = result.inliers >= 10 && confidence >= 0.14f && metricOk && rangeOk
            val requiredConsensus = if (robustCandidate) 2 else 3
            val clustered = cluster.size >= requiredConsensus

            if (singleStrong || clustered) {
                lockedTransform = consensusMedoid(if (cluster.isNotEmpty()) cluster else candidateHistory.toList())
                lastLockInliers = result.inliers
                lastLockReprojectionPx = result.medianReprojectionPx.toFloat()
                lastLockSource = if (transport.isHostRole) "HOST_VISUAL_2SIDED" else "CLIENT_VISUAL_2SIDED"
            }
        }

        val ready = lockedTransform != null
        latestQuality = Quality(
            confidence = confidence,
            inliers = result.inliers,
            correspondences = result.correspondences,
            medianReprojectionPx = result.medianReprojectionPx,
            imageCoverage = result.imageCoverage,
            stableCount = stableCount,
            localReady = ready,
            peerReady = peerReady,
            peerTransformVerified = peerTransformVerified,
            rangeM = latestRangeM,
            rangeDeltaM = rangeDelta,
            rangeSource = latestRangeSource,
            headingResidualDeg = result.headingResidualDeg,
            gravityTiltDeg = result.gravityTiltDeg,
            sensorPriorConfidence = result.sensorPriorConfidence,
            fusionSource = if (ready) lastLockSource else coarseSource,
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
            metricPairs = result.metricPairs,
            metricInliers = result.metricInliers,
            medianMetricResidualM = result.medianMetricResidualM,
        )

        transport.sendQuality(confidence, stableCount, ready)
        if (ready) {
            broadcastLockedTransform(force = true)
            tryVerifyPeerTransform()
        }
        emitQuality()
    }

    @Synchronized private fun acceptRefinement(result: AlignmentEngine.Result?) {
        val current = lockedTransform ?: return
        if (!transport.isHostRole || result == null || activePoi) return
        if (!isRefinementQuality(result) || !isRangeCompatible(result) || !isMetricConsistent(result)) return

        val confidence = max(result.confidence, geometricConfidence(result))
        val measurement = result.transformLocalFromRemote
        val (translationDelta, rotationDelta) = AlignmentEngine.transformDelta(current, measurement)
        if (translationDelta > REFINEMENT_ABSOLUTE_MAX_TRANSLATION_M || rotationDelta > REFINEMENT_ABSOLUTE_MAX_ROTATION_DEG) {
            return
        }

        refinementHistory.addLast(Candidate(measurement.copyOf(), confidence))
        while (refinementHistory.size > REFINEMENT_HISTORY) refinementHistory.removeFirst()

        var updated: DoubleArray? = null
        if (translationDelta <= REFINEMENT_DIRECT_TRANSLATION_M && rotationDelta <= REFINEMENT_DIRECT_ROTATION_DEG) {
            val alpha = if (isStrongRefinementQuality(result)) 0.16 else 0.10
            updated = smoothRigid(current, measurement, alpha)
        } else {
            val cluster = strongestCluster(
                refinementHistory,
                REFINEMENT_CLUSTER_TRANSLATION_M,
                REFINEMENT_CLUSTER_ROTATION_DEG,
            )
            if (cluster.size >= 3) {
                val consensus = consensusMedoid(cluster)
                val (consensusT, consensusR) = AlignmentEngine.transformDelta(current, consensus)
                if (consensusT <= REFINEMENT_CONSENSUS_MAX_TRANSLATION_M && consensusR <= REFINEMENT_CONSENSUS_MAX_ROTATION_DEG) {
                    updated = smoothRigid(current, consensus, 0.25)
                }
            }
        }

        if (updated == null) return
        val (appliedT, appliedR) = AlignmentEngine.transformDelta(current, updated)
        if (appliedT < 0.003 && appliedR < 0.05) return

        lockedTransform = updated
        localConfidence = max(localConfidence * 0.992f, confidence * 0.98f).coerceIn(0.10f, 0.99f)
        lastLockInliers = result.inliers
        lastLockReprojectionPx = result.medianReprojectionPx.toFloat()
        lastLockSource = "HOST_REFINED_2SIDED"
        peerTransformVerified = false

        latestQuality = latestQuality.copy(
            confidence = localConfidence,
            inliers = result.inliers,
            correspondences = result.correspondences,
            medianReprojectionPx = result.medianReprojectionPx,
            imageCoverage = result.imageCoverage,
            localReady = true,
            peerReady = peerReady,
            peerTransformVerified = false,
            rangeDeltaM = latestRangeM?.let { abs(result.predictedDeviceDistanceM.toFloat() - it) },
            gravityTiltDeg = result.gravityTiltDeg,
            fusionSource = lastLockSource,
            metricPairs = result.metricPairs,
            metricInliers = result.metricInliers,
            medianMetricResidualM = result.medianMetricResidualM,
        )
        broadcastLockedTransform(force = true)
        tryVerifyPeerTransform()
        emitQuality()
    }

    private fun isRangeCompatible(result: AlignmentEngine.Result): Boolean {
        val range = latestRangeM ?: return true
        val now = System.currentTimeMillis()
        if (lastRangeAtMs <= 0L || now - lastRangeAtMs > RANGE_GATE_FRESH_MS) return true
        val delta = abs(result.predictedDeviceDistanceM - range.toDouble())
        val std = latestRangeStdM?.takeIf { it.isFinite() && it > 0f }
        val allowed = when (latestRangeSource) {
            "RTT" -> max(RTT_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 0.15) * 4.0 + 0.30)
            "BLE" -> max(BLE_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 1.0) * 2.0 + 0.75)
            else -> return true
        }
        return delta <= allowed
    }

    private fun isMetricConsistent(result: AlignmentEngine.Result): Boolean {
        if (result.metricPairs < MIN_METRIC_PAIRS_FOR_LOCK) return true
        if (!result.medianMetricResidualM.isFinite()) return false
        val required = max(MIN_METRIC_INLIERS_FOR_LOCK, kotlin.math.ceil(result.metricPairs * 0.60).toInt())
        return result.metricInliers >= required && result.medianMetricResidualM <= MAX_METRIC_RESIDUAL_M
    }

    private fun isRefinementQuality(result: AlignmentEngine.Result): Boolean {
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityOk = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 8.0
        return result.inliers >= 12 && result.correspondences >= 12 &&
            result.medianReprojectionPx <= 2.8 && result.imageCoverage >= 0.08 &&
            confidence >= 0.16f && gravityOk && isMetricConsistent(result)
    }

    private fun isStrongRefinementQuality(result: AlignmentEngine.Result): Boolean {
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityOk = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 6.0
        return result.inliers >= 18 && result.correspondences >= 18 &&
            result.medianReprojectionPx <= 2.2 && result.imageCoverage >= 0.12 &&
            confidence >= 0.24f && gravityOk && isMetricConsistent(result)
    }

    private fun geometricConfidence(result: AlignmentEngine.Result): Float {
        if (result.correspondences <= 0 || !result.medianReprojectionPx.isFinite()) return 0f
        val ratio = (result.inliers.toDouble() / result.correspondences).coerceIn(0.0, 1.0)
        val support = min(1.0, result.inliers / 20.0)
        val coverage = min(1.0, result.imageCoverage / 0.14)
        val reprojection = exp(-result.medianReprojectionPx / 4.5)
        var confidence = ratio * support * coverage * reprojection
        if (result.metricPairs >= 4 && result.medianMetricResidualM.isFinite()) {
            val metricRatio = result.metricInliers.toDouble() / result.metricPairs
            confidence *= 0.70 + 0.30 * (metricRatio * exp(-result.medianMetricResidualM / 0.18))
        }
        return confidence.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * A peer transform is accepted only after this phone has independently solved
     * the same SE(3). We then harmonize the two estimates within a tight gate and
     * ACK the harmonized result. No peer message can manufacture READY by itself.
     */
    @Synchronized private fun tryVerifyPeerTransform() {
        val message = pendingPeerTransform ?: return
        val peerSenderFromLocal = message.senderFromPeer ?: return
        if (peerSenderFromLocal.size < 16 || message.confidence < MIN_PEER_TRANSFORM_CONFIDENCE) return
        if (!isRigidTransform(peerSenderFromLocal)) return

        val localFromPeerSender = invertRigid(peerSenderFromLocal) ?: return
        if (!isRigidTransform(localFromPeerSender)) return

        val gravityTilt = FusionMath.gravityTiltDeg(localFromPeerSender)
        if (gravityTilt.isFinite() && gravityTilt > PEER_MAX_GRAVITY_TILT_DEG) return

        val isAck = message.transformSource == "PEER_ACK"
        val hasVisualEvidence = message.transformInliers >= MIN_LOCK_INLIERS &&
            (!message.transformMedianReprojectionPx.isFinite() || message.transformMedianReprojectionPx <= MAX_LOCK_REPROJECTION_PX.toFloat())
        if (!isAck && !hasVisualEvidence) return

        val existing = lockedTransform ?: return
        val (translationDelta, rotationDelta) = AlignmentEngine.transformDelta(existing, localFromPeerSender)
        if (translationDelta > PEER_VERIFY_TRANSLATION_M || rotationDelta > PEER_VERIFY_ROTATION_DEG) {
            peerTransformVerified = false
            return
        }

        // Both transforms are independently justified. Before a POI exists, blend
        // them so each direction converges instead of preserving two slightly
        // different coordinate mappings.
        if (!activePoi) {
            lockedTransform = smoothRigid(existing, localFromPeerSender, if (isAck) 0.25 else 0.50)
        }
        peerReady = true
        peerTransformVerified = true
        pendingPeerTransform = null

        if (!isAck) {
            transport.sendAlignmentTransform(
                senderFromPeer = lockedTransform ?: existing,
                confidence = localConfidence.coerceAtLeast(MIN_PEER_TRANSFORM_CONFIDENCE),
                inliers = lastLockInliers,
                medianReprojectionPx = lastLockReprojectionPx,
                source = "PEER_ACK",
            )
            transport.sendQuality(localConfidence, stableCount, true)
        }
    }

    private fun maybeResendLockedTransform() {
        if (lockedTransform == null || peerTransformVerified) return
        val now = System.currentTimeMillis()
        if (now - lastTransformBroadcastAtMs >= TRANSFORM_RETRY_MS) broadcastLockedTransform(force = true)
    }

    private fun broadcastLockedTransform(force: Boolean) {
        val transform = lockedTransform ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastTransformBroadcastAtMs < 2500L) return
        if (force && lastTransformBroadcastAtMs != 0L && now - lastTransformBroadcastAtMs < 450L) return

        lastTransformBroadcastAtMs = now
        transport.sendAlignmentTransform(
            senderFromPeer = transform,
            confidence = localConfidence.coerceAtLeast(MIN_PEER_TRANSFORM_CONFIDENCE),
            inliers = lastLockInliers,
            medianReprojectionPx = lastLockReprojectionPx,
            source = lastLockSource,
        )
    }

    @Synchronized private fun updateFusionSeed() {
        if (lockedTransform != null) return
        val local: CapturedFrame
        val remote: CapturedFrame
        synchronized(frameLock) {
            local = localFrames.peekLast() ?: return
            remote = remoteFrames.peekLast() ?: return
        }

        val gnss = FusionMath.bootstrapFromGnss(remote, local)
        val colocated = FusionMath.bootstrapFromCoLocation(
            remote = remote,
            local = local,
            rangeM = latestRangeM,
            rangeStdM = latestRangeStdM,
            rangeSource = latestRangeSource,
        )
        val best = listOfNotNull(gnss, colocated).maxByOrNull { it.confidence } ?: run {
            coarseConfidence = 0f
            coarseSource = "VISION"
            return
        }
        coarseConfidence = best.confidence
        coarseSource = best.source
    }

    private fun strongestCluster(
        history: Collection<Candidate>,
        translationThreshold: Double,
        rotationThresholdDeg: Double,
    ): List<Candidate> {
        if (history.isEmpty()) return emptyList()
        val all = history.toList()
        var best = listOf(all.last())
        var bestScore = -1.0
        for (seed in all) {
            val cluster = all.filter { candidate ->
                val (translation, rotation) = AlignmentEngine.transformDelta(seed.transform, candidate.transform)
                translation <= translationThreshold && rotation <= rotationThresholdDeg
            }
            val score = cluster.size * 10.0 + cluster.sumOf { it.confidence.toDouble() }
            if (score > bestScore) {
                best = cluster
                bestScore = score
            }
        }
        return best
    }

    private fun isSingleFrameLockQuality(result: AlignmentEngine.Result): Boolean {
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityAcceptable = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 7.0
        return result.inliers >= 12 && result.correspondences >= 12 &&
            result.medianReprojectionPx <= 3.0 && result.imageCoverage >= 0.07 &&
            confidence >= 0.16f && gravityAcceptable && isRangeCompatible(result) && isMetricConsistent(result)
    }

    private fun resultScore(result: AlignmentEngine.Result): Double {
        if (!isRangeCompatible(result) || !isMetricConsistent(result)) return -1_000_000.0
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityBonus = if (result.gravityTiltDeg.isFinite()) {
            (1.0 - result.gravityTiltDeg / 20.0).coerceIn(0.0, 1.0) * 0.70
        } else 0.0
        val metricBonus = if (result.metricPairs >= 4 && result.medianMetricResidualM.isFinite()) {
            (1.0 - result.medianMetricResidualM / 0.30).coerceIn(0.0, 1.0) * 1.2
        } else 0.0
        return confidence * 4.5 + min(result.inliers, 50) / 18.0 +
            min(result.imageCoverage, 0.35) * 3.4 + gravityBonus + metricBonus
    }

    private fun consensusMedoid(history: Collection<Candidate>): DoubleArray {
        if (history.size <= 1) return history.first().transform.copyOf()
        val candidates = history.toList()
        var bestIndex = 0
        var bestCost = Double.POSITIVE_INFINITY
        for (i in candidates.indices) {
            var cost = 0.0
            for (j in candidates.indices) {
                if (i == j) continue
                val (translation, rotationDeg) = AlignmentEngine.transformDelta(
                    candidates[i].transform,
                    candidates[j].transform,
                )
                cost += translation + rotationDeg * ROTATION_TO_METERS_WEIGHT
            }
            cost /= max(0.25f, candidates[i].confidence).toDouble()
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = i
            }
        }
        return candidates[bestIndex].transform.copyOf()
    }

    private fun smoothRigid(a: DoubleArray, b: DoubleArray, alpha: Double): DoubleArray {
        val qA = rotationMatrixToQuaternion(a)
        val qB = rotationMatrixToQuaternion(b)
        val q = slerp(qA, qB, alpha.coerceIn(0.0, 1.0))
        val out = quaternionToMatrix(q)
        out[3] = a[3] + (b[3] - a[3]) * alpha
        out[7] = a[7] + (b[7] - a[7]) * alpha
        out[11] = a[11] + (b[11] - a[11]) * alpha
        return out
    }

    private fun rotationMatrixToQuaternion(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[5] + m[10]
        val q = DoubleArray(4)
        if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            q[3] = 0.25 * s
            q[0] = (m[9] - m[6]) / s
            q[1] = (m[2] - m[8]) / s
            q[2] = (m[4] - m[1]) / s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1.0 + m[0] - m[5] - m[10]) * 2.0
            q[3] = (m[9] - m[6]) / s
            q[0] = 0.25 * s
            q[1] = (m[1] + m[4]) / s
            q[2] = (m[2] + m[8]) / s
        } else if (m[5] > m[10]) {
            val s = sqrt(1.0 + m[5] - m[0] - m[10]) * 2.0
            q[3] = (m[2] - m[8]) / s
            q[0] = (m[1] + m[4]) / s
            q[1] = 0.25 * s
            q[2] = (m[6] + m[9]) / s
        } else {
            val s = sqrt(1.0 + m[10] - m[0] - m[5]) * 2.0
            q[3] = (m[4] - m[1]) / s
            q[0] = (m[2] + m[8]) / s
            q[1] = (m[6] + m[9]) / s
            q[2] = 0.25 * s
        }
        return normalizeQuaternion(q)
    }

    private fun quaternionToMatrix(qIn: DoubleArray): DoubleArray {
        val q = normalizeQuaternion(qIn)
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy), 0.0,
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx), 0.0,
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy), 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    private fun normalizeQuaternion(q: DoubleArray): DoubleArray {
        val n = sqrt(q.sumOf { it * it })
        if (n < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        return DoubleArray(4) { q[it] / n }
    }

    private fun slerp(aIn: DoubleArray, bIn: DoubleArray, alpha: Double): DoubleArray {
        val a = normalizeQuaternion(aIn)
        var b = normalizeQuaternion(bIn)
        var dot = a.indices.sumOf { a[it] * b[it] }
        if (dot < 0.0) {
            b = DoubleArray(4) { -b[it] }
            dot = -dot
        }
        if (dot > 0.9995) {
            return normalizeQuaternion(DoubleArray(4) { a[it] + alpha * (b[it] - a[it]) })
        }
        val theta0 = acos(dot.coerceIn(-1.0, 1.0))
        val theta = theta0 * alpha
        val sinTheta0 = sin(theta0)
        if (abs(sinTheta0) < 1e-9) return a
        val s0 = cos(theta) - dot * sin(theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0
        return normalizeQuaternion(DoubleArray(4) { s0 * a[it] + s1 * b[it] })
    }

    private fun publishPendingPoiIfPossible() {
        val poi = pendingPoi ?: return
        val transform = lockedTransform ?: return
        val p = AlignmentEngine.transformPoint(transform, poi.pointWorld)
        listener.onRemotePoi(
            poi.id,
            floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()),
            poi.owner,
            localConfidence,
        )
    }

    @Synchronized private fun resetAlignment(clearFrames: Boolean, clearPoi: Boolean) {
        solveSerial.incrementAndGet()
        if (clearFrames) {
            synchronized(frameLock) {
                localFrames.clear()
                remoteFrames.clear()
            }
        }
        lockedTransform = null
        coarseConfidence = 0f
        coarseSource = "NONE"
        candidateHistory.clear()
        refinementHistory.clear()
        stableCount = 0
        localConfidence = 0f
        peerReady = false
        peerTransformVerified = false
        pendingPeerTransform = null
        connectedAtMs = System.currentTimeMillis()
        lastSolveStartedMs = 0L
        lastTransformBroadcastAtMs = 0L
        lastLockInliers = 0
        lastLockReprojectionPx = Float.NaN
        lastLockSource = "VISION"
        latestQuality = Quality(rangeM = latestRangeM, rangeSource = latestRangeSource)
        if (clearPoi) {
            pendingPoi = null
            activePoi = false
            listener.onPoiCleared()
        }
        if (transport.connected) transport.sendQuality(0f, 0, false)
        listener.onAlignmentQuality(latestQuality)
    }

    private fun emitQuality() {
        latestQuality = latestQuality.copy(
            confidence = localConfidence,
            stableCount = stableCount,
            localReady = lockedTransform != null,
            peerReady = peerReady,
            peerTransformVerified = peerTransformVerified,
            rangeM = latestRangeM,
            rangeSource = latestRangeSource,
            fusionSource = when {
                lockedTransform != null && peerTransformVerified -> "BIDIRECTIONAL VISUAL VERIFIED"
                lockedTransform != null -> lastLockSource
                else -> coarseSource
            },
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    private fun isRigidTransform(t: DoubleArray): Boolean {
        if (t.size < 16 || !t.take(16).all { it.isFinite() }) return false
        if (abs(t[12]) > 1e-5 || abs(t[13]) > 1e-5 || abs(t[14]) > 1e-5 || abs(t[15] - 1.0) > 1e-4) return false

        val c0 = doubleArrayOf(t[0], t[4], t[8])
        val c1 = doubleArrayOf(t[1], t[5], t[9])
        val c2 = doubleArrayOf(t[2], t[6], t[10])
        fun norm(c: DoubleArray) = sqrt(c.sumOf { it * it })
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        if (abs(norm(c0) - 1.0) > 0.05 || abs(norm(c1) - 1.0) > 0.05 || abs(norm(c2) - 1.0) > 0.05) return false
        if (abs(dot(c0, c1)) > 0.05 || abs(dot(c0, c2)) > 0.05 || abs(dot(c1, c2)) > 0.05) return false
        return true
    }

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (!isRigidTransform(t)) return null
        val out = doubleArrayOf(
            t[0], t[4], t[8], 0.0,
            t[1], t[5], t[9], 0.0,
            t[2], t[6], t[10], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val tx = t[3]; val ty = t[7]; val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return out
    }

    companion object {
        private const val KEYFRAME_WINDOW = 16
        private const val KEYFRAME_TRANSLATION_M = 0.055
        private const val KEYFRAME_ROTATION_DEG = 3.5
        private const val KEYFRAME_MAX_INTERVAL_NS = 900_000_000L
        private const val MIN_KEYFRAME_MOTION_QUALITY = 0.44
        private const val SOLVE_MIN_INTERVAL_MS = 220L
        private const val REFINEMENT_SOLVE_INTERVAL_MS = 1_200L
        private const val MAX_PAIR_ATTEMPTS = 8
        private const val REFINEMENT_PAIR_ATTEMPTS = 4
        private const val CANDIDATE_WINDOW = 18
        private const val REFINEMENT_HISTORY = 10
        private const val CLUSTER_TRANSLATION_M = 0.22
        private const val CLUSTER_ROTATION_DEG = 4.0
        private const val REFINEMENT_DIRECT_TRANSLATION_M = 0.05
        private const val REFINEMENT_DIRECT_ROTATION_DEG = 1.0
        private const val REFINEMENT_CLUSTER_TRANSLATION_M = 0.08
        private const val REFINEMENT_CLUSTER_ROTATION_DEG = 1.5
        private const val REFINEMENT_CONSENSUS_MAX_TRANSLATION_M = 0.20
        private const val REFINEMENT_CONSENSUS_MAX_ROTATION_DEG = 2.5
        private const val REFINEMENT_ABSOLUTE_MAX_TRANSLATION_M = 0.30
        private const val REFINEMENT_ABSOLUTE_MAX_ROTATION_DEG = 4.0
        private const val ROTATION_TO_METERS_WEIGHT = 0.015
        private const val RTT_FRESH_MS = 5000L
        private const val RANGE_GATE_FRESH_MS = 8_000L
        private const val CLIENT_INITIAL_SOLVE_DELAY_MS = 750L
        private const val TRANSFORM_RETRY_MS = 650L
        private const val MAX_VISUAL_GRAVITY_TILT_DEG = 12.0
        private const val PEER_MAX_GRAVITY_TILT_DEG = 12.0
        private const val MIN_PEER_TRANSFORM_CONFIDENCE = 0.10f
        private const val PEER_VERIFY_TRANSLATION_M = 0.20
        private const val PEER_VERIFY_ROTATION_DEG = 3.0
        private const val MIN_LOCK_INLIERS = 8
        private const val MIN_LOCK_CORRESPONDENCES = 8
        private const val MAX_LOCK_REPROJECTION_PX = 4.0
        private const val MIN_LOCK_COVERAGE = 0.05
        private const val MIN_LOCK_CONFIDENCE = 0.10f
        private const val MIN_METRIC_PAIRS_FOR_LOCK = 5
        private const val MIN_METRIC_INLIERS_FOR_LOCK = 4
        private const val MAX_METRIC_RESIDUAL_M = 0.24
        private const val RTT_MIN_RANGE_TOLERANCE_M = 0.70
        private const val BLE_MIN_RANGE_TOLERANCE_M = 2.0
    }
}
