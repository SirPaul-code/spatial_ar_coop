package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Precision-first shared-world coordinator.
 *
 * Each phone still has to obtain an independently valid visual/metric solution,
 * but the room host is the single canonical world. Independent ARCore world-frame
 * estimates are NOT compared by raw matrix translation components: a small angular
 * difference around non-zero world origins can make those components differ by many
 * centimetres even when both transforms map the observed scene almost identically.
 *
 * Instead, the receiver compares how the two transforms map real metric scene points
 * from the peer world. A joiner may adopt the host transform only when:
 *  - its own independently solved transform is already strict/metric/range valid,
 *  - the host transform carries strong visual evidence,
 *  - fresh RTT/BLE range is physically compatible,
 *  - both transforms agree over actual observed 3D scene support.
 *
 * Once the joiner adopts the exact host transform it returns PEER_ACK. The host then
 * verifies that ACK against its canonical transform. The canonical transform is kept
 * static after initial lock; an active POI therefore cannot be dragged by refinement.
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
        val peerAgreementMedianM: Double = Double.NaN,
        val peerAgreementP90M: Double = Double.NaN,
        val peerAgreementRotationDeg: Double = Double.NaN,
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

    private data class SceneAgreement(
        val medianM: Double,
        val p90M: Double,
        val rotationDeg: Double,
        val samples: Int,
    )

    private val solveExecutor = Executors.newSingleThreadExecutor()
    private val solving = AtomicBoolean(false)
    private val solveSerial = AtomicLong(0)
    private val frameLock = Any()
    private val localFrames = ArrayDeque<CapturedFrame>()
    private val remoteFrames = ArrayDeque<CapturedFrame>()
    private val candidateHistory = ArrayDeque<Candidate>()

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
    @Volatile private var lastAgreement = SceneAgreement(Double.NaN, Double.NaN, Double.NaN, 0)

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
            tryVerifyPeerTransform()
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
        tryVerifyPeerTransform()
        emitQuality()
    }

    fun onPeerQuality(message: WireMessage.Quality) {
        if (message.senderFromPeer != null) {
            peerReady = true
            pendingPeerTransform = message
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

    /**
     * Each phone solves once. The host solution becomes canonical only after the
     * joiner has independently solved and physically compared the two mappings.
     * We intentionally do not keep changing the world transform after this point.
     */
    private fun maybeSolve() {
        if (!transport.connected || lockedTransform != null) return

        val now = System.currentTimeMillis()
        if (!transport.isHostRole && pendingPeerTransform == null &&
            now - connectedAtMs < CLIENT_INITIAL_SOLVE_DELAY_MS
        ) return

        val pairs = buildFramePairs()
        if (pairs.isEmpty()) return
        if (now - lastSolveStartedMs < SOLVE_MIN_INTERVAL_MS || !solving.compareAndSet(false, true)) return
        lastSolveStartedMs = now
        val serial = solveSerial.incrementAndGet()

        solveExecutor.execute {
            var best: AlignmentEngine.Result? = null
            try {
                for (pair in pairs.take(MAX_PAIR_ATTEMPTS)) {
                    val result = solveVisionAuthoritative(pair) ?: continue
                    if (best == null || resultScore(result) > resultScore(best!!)) best = result
                    if (isSingleFrameLockQuality(result)) break
                }
                if (serial == solveSerial.get()) acceptInitialResult(best)
            } finally {
                solving.set(false)
                if (serial == solveSerial.get() && lockedTransform == null) maybeSolve()
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

            if (singleStrong || cluster.size >= requiredConsensus) {
                lockedTransform = consensusMedoid(if (cluster.isNotEmpty()) cluster else candidateHistory.toList())
                lastLockInliers = result.inliers
                lastLockReprojectionPx = result.medianReprojectionPx.toFloat()
                lastLockSource = if (transport.isHostRole) "HOST_CANONICAL" else "CLIENT_INDEPENDENT"
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

    private fun isRangeCompatible(result: AlignmentEngine.Result): Boolean =
        isPredictedRangeCompatible(result.predictedDeviceDistanceM)

    private fun isPredictedRangeCompatible(predictedDistanceM: Double): Boolean {
        val range = latestRangeM ?: return true
        val now = System.currentTimeMillis()
        if (lastRangeAtMs <= 0L || now - lastRangeAtMs > RANGE_GATE_FRESH_MS) return true
        if (!predictedDistanceM.isFinite()) return false

        val delta = abs(predictedDistanceM - range.toDouble())
        val std = latestRangeStdM?.takeIf { it.isFinite() && it > 0f }
        val allowed = when (latestRangeSource) {
            "RTT" -> max(RTT_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 0.15) * 4.0 + 0.30)
            "BLE" -> max(BLE_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 1.0) * 2.0 + 0.75)
            else -> return true
        }
        return delta <= allowed
    }

    private fun candidateRangeCompatible(transformLocalFromRemote: DoubleArray): Boolean {
        val range = latestRangeM ?: return true
        val now = System.currentTimeMillis()
        if (lastRangeAtMs <= 0L || now - lastRangeAtMs > RANGE_GATE_FRESH_MS) return true

        val local: CapturedFrame
        val remote: CapturedFrame
        synchronized(frameLock) {
            local = localFrames.peekLast() ?: return true
            remote = remoteFrames.peekLast() ?: return true
        }
        val remoteCameraLocal = AlignmentEngine.transformPoint(transformLocalFromRemote, remote.pose.t)
        val lc = local.pose.t
        val dx = remoteCameraLocal[0] - lc.getOrElse(0) { 0f }
        val dy = remoteCameraLocal[1] - lc.getOrElse(1) { 0f }
        val dz = remoteCameraLocal[2] - lc.getOrElse(2) { 0f }
        val predicted = sqrt(dx * dx + dy * dy + dz * dz)
        if (!predicted.isFinite()) return false

        val std = latestRangeStdM?.takeIf { it.isFinite() && it > 0f }
        val allowed = when (latestRangeSource) {
            "RTT" -> max(RTT_CANDIDATE_TOLERANCE_M, (std?.toDouble() ?: 0.15) * 5.0 + 0.35)
            "BLE" -> max(BLE_CANDIDATE_TOLERANCE_M, (std?.toDouble() ?: 1.0) * 2.25 + 0.90)
            else -> return true
        }
        return abs(predicted - range.toDouble()) <= allowed
    }

    private fun isMetricConsistent(result: AlignmentEngine.Result): Boolean {
        if (result.metricPairs < MIN_METRIC_PAIRS_FOR_LOCK) return true
        if (!result.medianMetricResidualM.isFinite()) return false
        val required = max(MIN_METRIC_INLIERS_FOR_LOCK, kotlin.math.ceil(result.metricPairs * 0.60).toInt())
        return result.metricInliers >= required && result.medianMetricResidualM <= MAX_METRIC_RESIDUAL_M
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
     * Verification is performed in scene space. We transform the SAME remote ARCore
     * world points using both estimates and measure their separation where the scene
     * actually exists. This is the quantity that matters for a shared POI.
     */
    @Synchronized private fun tryVerifyPeerTransform() {
        val message = pendingPeerTransform ?: return
        val senderFromPeer = message.senderFromPeer ?: return
        if (senderFromPeer.size < 16 || message.confidence < MIN_PEER_TRANSFORM_CONFIDENCE) return
        if (!isRigidTransform(senderFromPeer)) return

        val peerCandidate = invertRigid(senderFromPeer) ?: return
        if (!isRigidTransform(peerCandidate)) return

        val gravityTilt = FusionMath.gravityTiltDeg(peerCandidate)
        if (gravityTilt.isFinite() && gravityTilt > PEER_MAX_GRAVITY_TILT_DEG) return
        if (!candidateRangeCompatible(peerCandidate)) return

        val existing = lockedTransform ?: return
        val agreement = sceneAgreement(existing, peerCandidate)
        lastAgreement = agreement

        val isAck = message.transformSource == "PEER_ACK"
        if (isAck) {
            if (!ackAgreementAcceptable(agreement)) return
            peerReady = true
            peerTransformVerified = true
            pendingPeerTransform = null
            lastLockSource = if (transport.isHostRole) "HOST_CANONICAL_VERIFIED" else "CANONICAL_VERIFIED"
            emitQuality()
            publishPendingPoiIfPossible()
            return
        }

        val hasVisualEvidence = message.transformInliers >= MIN_LOCK_INLIERS &&
            (!message.transformMedianReprojectionPx.isFinite() ||
                message.transformMedianReprojectionPx <= MAX_PEER_REPROJECTION_PX)
        if (!hasVisualEvidence) return
        if (!peerAgreementAcceptable(agreement, message.confidence)) return

        peerReady = true

        if (transport.isHostRole) {
            // The host NEVER adopts the joiner's world. Its independently solved
            // transform is the room canonical transform. Seeing a physically
            // compatible client estimate is enough to send the canonical mapping
            // again; the client must adopt it and ACK that exact mapping.
            pendingPeerTransform = null
            peerTransformVerified = false
            lastLockSource = "HOST_CANONICAL_WAIT_ACK"
            broadcastLockedTransform(force = true)
            emitQuality()
            return
        }

        // Joiner: its own strict solve has now independently validated the host
        // mapping over the observed 3D scene. Adopt the host transform EXACTLY so
        // both devices use one coordinate system rather than two noisy estimates.
        lockedTransform = peerCandidate.copyOf()
        localConfidence = min(
            0.99f,
            max(localConfidence, message.confidence * 0.97f),
        )
        stableCount = max(stableCount, 1)
        lastLockInliers = message.transformInliers
        lastLockReprojectionPx = message.transformMedianReprojectionPx
        lastLockSource = "HOST_CANONICAL_ADOPTED"
        peerTransformVerified = true
        pendingPeerTransform = null

        transport.sendAlignmentTransform(
            senderFromPeer = lockedTransform ?: peerCandidate,
            confidence = localConfidence.coerceAtLeast(MIN_PEER_TRANSFORM_CONFIDENCE),
            inliers = lastLockInliers,
            medianReprojectionPx = lastLockReprojectionPx,
            source = "PEER_ACK",
        )
        transport.sendQuality(localConfidence, stableCount, true)
        emitQuality()
        publishPendingPoiIfPossible()
    }

    private fun sceneAgreement(a: DoubleArray, b: DoubleArray): SceneAgreement {
        val distances = ArrayList<Double>(MAX_AGREEMENT_SAMPLES)
        val frames: List<CapturedFrame>
        synchronized(frameLock) {
            frames = remoteFrames.toList().takeLast(AGREEMENT_FRAME_COUNT)
        }

        for (frame in frames) {
            val points = frame.metricPoints
            if (points.isEmpty()) continue
            val remaining = MAX_AGREEMENT_SAMPLES - distances.size
            if (remaining <= 0) break
            val perFrameBudget = max(1, remaining / max(1, AGREEMENT_FRAME_COUNT))
            val step = max(1, points.size / perFrameBudget)
            var i = 0
            while (i < points.size && distances.size < MAX_AGREEMENT_SAMPLES) {
                val p = points[i]
                if (p.size >= 5 && p[2].isFinite() && p[3].isFinite() && p[4].isFinite()) {
                    val world = floatArrayOf(p[2], p[3], p[4])
                    val pa = AlignmentEngine.transformPoint(a, world)
                    val pb = AlignmentEngine.transformPoint(b, world)
                    val dx = pa[0] - pb[0]
                    val dy = pa[1] - pb[1]
                    val dz = pa[2] - pb[2]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d.isFinite()) distances += d
                }
                i += step
            }

            // Camera poses are particularly useful when depth is sparse because
            // they are stable metric points in the peer ARCore world too.
            val ca = AlignmentEngine.transformPoint(a, frame.pose.t)
            val cb = AlignmentEngine.transformPoint(b, frame.pose.t)
            val cdx = ca[0] - cb[0]
            val cdy = ca[1] - cb[1]
            val cdz = ca[2] - cb[2]
            val cd = sqrt(cdx * cdx + cdy * cdy + cdz * cdz)
            if (cd.isFinite()) distances += cd
        }

        val rotation = relativeRotationDeg(a, b)
        if (distances.isEmpty()) {
            val direct = AlignmentEngine.transformDelta(a, b).first
            return SceneAgreement(direct, direct, rotation, 0)
        }

        distances.sort()
        val median = distances[distances.size / 2]
        val p90Index = ((distances.size - 1) * 0.90).toInt().coerceIn(0, distances.lastIndex)
        return SceneAgreement(median, distances[p90Index], rotation, distances.size)
    }

    private fun peerAgreementAcceptable(agreement: SceneAgreement, peerConfidence: Float): Boolean {
        if (!agreement.medianM.isFinite() || !agreement.p90M.isFinite() || !agreement.rotationDeg.isFinite()) {
            return false
        }

        // With lots of real metric samples, scene-space agreement is substantially
        // more informative than raw SE(3) translation-vector equality. The relaxed
        // tier is allowed only when both independent solves were strong; catastrophic
        // transforms remain excluded by range, gravity, per-phone PnP and 3D gates.
        val strongLocal = localConfidence >= 0.16f
        val strongPeer = peerConfidence >= 0.16f
        val strong = strongLocal && strongPeer

        return when {
            agreement.samples >= 120 -> {
                val medianLimit = if (strong) PEER_SCENE_MEDIAN_STRONG_M else PEER_SCENE_MEDIAN_M
                val p90Limit = if (strong) PEER_SCENE_P90_STRONG_M else PEER_SCENE_P90_M
                val rotLimit = if (strong) PEER_SCENE_ROTATION_STRONG_DEG else PEER_SCENE_ROTATION_DEG
                agreement.medianM <= medianLimit && agreement.p90M <= p90Limit &&
                    agreement.rotationDeg <= rotLimit
            }
            agreement.samples >= 20 ->
                agreement.medianM <= PEER_SCENE_MEDIAN_SPARSE_M &&
                    agreement.p90M <= PEER_SCENE_P90_SPARSE_M &&
                    agreement.rotationDeg <= PEER_SCENE_ROTATION_SPARSE_DEG
            else ->
                agreement.medianM <= PEER_FALLBACK_TRANSLATION_M &&
                    agreement.rotationDeg <= PEER_FALLBACK_ROTATION_DEG
        }
    }

    private fun ackAgreementAcceptable(agreement: SceneAgreement): Boolean {
        if (!agreement.medianM.isFinite() || !agreement.rotationDeg.isFinite()) return false
        return if (agreement.samples >= 20) {
            agreement.medianM <= ACK_SCENE_MEDIAN_M &&
                agreement.p90M <= ACK_SCENE_P90_M &&
                agreement.rotationDeg <= ACK_SCENE_ROTATION_DEG
        } else {
            agreement.medianM <= ACK_FALLBACK_TRANSLATION_M &&
                agreement.rotationDeg <= ACK_SCENE_ROTATION_DEG
        }
    }

    private fun relativeRotationDeg(a: DoubleArray, b: DoubleArray): Double {
        // trace(Ra^T Rb) without constructing another matrix.
        val trace =
            a[0] * b[0] + a[4] * b[4] + a[8] * b[8] +
                a[1] * b[1] + a[5] * b[5] + a[9] * b[9] +
                a[2] * b[2] + a[6] * b[6] + a[10] * b[10]
        val cosTheta = ((trace - 1.0) * 0.5).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    private fun maybeResendLockedTransform() {
        if (lockedTransform == null || peerTransformVerified) return
        val now = System.currentTimeMillis()
        if (now - lastTransformBroadcastAtMs >= TRANSFORM_RETRY_MS) {
            broadcastLockedTransform(force = true)
        }
    }

    private fun broadcastLockedTransform(force: Boolean) {
        val transform = lockedTransform ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastTransformBroadcastAtMs < 2500L) return
        if (force && lastTransformBroadcastAtMs != 0L && now - lastTransformBroadcastAtMs < 420L) return

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
                val agreement = sceneAgreement(seed.transform, candidate.transform)
                agreement.medianM <= translationThreshold && agreement.rotationDeg <= rotationThresholdDeg
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
            confidence >= 0.16f && gravityAcceptable &&
            isRangeCompatible(result) && isMetricConsistent(result)
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
                val agreement = sceneAgreement(candidates[i].transform, candidates[j].transform)
                cost += agreement.medianM + agreement.rotationDeg * ROTATION_TO_METERS_WEIGHT
            }
            cost /= max(0.25f, candidates[i].confidence).toDouble()
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = i
            }
        }
        return candidates[bestIndex].transform.copyOf()
    }

    private fun publishPendingPoiIfPossible() {
        val poi = pendingPoi ?: return
        val transform = lockedTransform ?: return
        if (!peerTransformVerified) return
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
        lastAgreement = SceneAgreement(Double.NaN, Double.NaN, Double.NaN, 0)
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
        val agreement = lastAgreement
        latestQuality = latestQuality.copy(
            confidence = localConfidence,
            stableCount = stableCount,
            localReady = lockedTransform != null,
            peerReady = peerReady,
            peerTransformVerified = peerTransformVerified,
            rangeM = latestRangeM,
            rangeSource = latestRangeSource,
            fusionSource = when {
                lockedTransform != null && peerTransformVerified -> "HOST CANONICAL VERIFIED"
                lockedTransform != null -> lastLockSource
                else -> coarseSource
            },
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
            peerAgreementMedianM = agreement.medianM,
            peerAgreementP90M = agreement.p90M,
            peerAgreementRotationDeg = agreement.rotationDeg,
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    private fun isRigidTransform(t: DoubleArray): Boolean {
        if (t.size < 16 || !t.take(16).all { it.isFinite() }) return false
        if (abs(t[12]) > 1e-5 || abs(t[13]) > 1e-5 || abs(t[14]) > 1e-5 || abs(t[15] - 1.0) > 1e-4) {
            return false
        }

        val c0 = doubleArrayOf(t[0], t[4], t[8])
        val c1 = doubleArrayOf(t[1], t[5], t[9])
        val c2 = doubleArrayOf(t[2], t[6], t[10])
        fun norm(c: DoubleArray) = sqrt(c.sumOf { it * it })
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        if (abs(norm(c0) - 1.0) > 0.05 || abs(norm(c1) - 1.0) > 0.05 || abs(norm(c2) - 1.0) > 0.05) {
            return false
        }
        if (abs(dot(c0, c1)) > 0.05 || abs(dot(c0, c2)) > 0.05 || abs(dot(c1, c2)) > 0.05) {
            return false
        }
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
        val tx = t[3]
        val ty = t[7]
        val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return out
    }

    companion object {
        private const val KEYFRAME_WINDOW = 18
        private const val KEYFRAME_TRANSLATION_M = 0.055
        private const val KEYFRAME_ROTATION_DEG = 3.5
        private const val KEYFRAME_MAX_INTERVAL_NS = 900_000_000L
        private const val MIN_KEYFRAME_MOTION_QUALITY = 0.44
        private const val SOLVE_MIN_INTERVAL_MS = 240L
        private const val MAX_PAIR_ATTEMPTS = 8
        private const val CANDIDATE_WINDOW = 18
        private const val CLUSTER_TRANSLATION_M = 0.24
        private const val CLUSTER_ROTATION_DEG = 4.5
        private const val ROTATION_TO_METERS_WEIGHT = 0.015

        private const val RTT_FRESH_MS = 5000L
        private const val RANGE_GATE_FRESH_MS = 8_000L
        private const val CLIENT_INITIAL_SOLVE_DELAY_MS = 900L
        private const val TRANSFORM_RETRY_MS = 650L

        private const val MAX_VISUAL_GRAVITY_TILT_DEG = 12.0
        private const val PEER_MAX_GRAVITY_TILT_DEG = 12.0
        private const val MIN_PEER_TRANSFORM_CONFIDENCE = 0.10f
        private const val MAX_PEER_REPROJECTION_PX = 4.5f

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
        private const val RTT_CANDIDATE_TOLERANCE_M = 0.90
        private const val BLE_CANDIDATE_TOLERANCE_M = 2.5

        private const val AGREEMENT_FRAME_COUNT = 4
        private const val MAX_AGREEMENT_SAMPLES = 1200
        private const val PEER_SCENE_MEDIAN_M = 0.18
        private const val PEER_SCENE_P90_M = 0.34
        private const val PEER_SCENE_ROTATION_DEG = 6.0
        private const val PEER_SCENE_MEDIAN_STRONG_M = 0.26
        private const val PEER_SCENE_P90_STRONG_M = 0.50
        private const val PEER_SCENE_ROTATION_STRONG_DEG = 8.0
        private const val PEER_SCENE_MEDIAN_SPARSE_M = 0.24
        private const val PEER_SCENE_P90_SPARSE_M = 0.46
        private const val PEER_SCENE_ROTATION_SPARSE_DEG = 8.0
        private const val PEER_FALLBACK_TRANSLATION_M = 0.55
        private const val PEER_FALLBACK_ROTATION_DEG = 8.0

        private const val ACK_SCENE_MEDIAN_M = 0.08
        private const val ACK_SCENE_P90_M = 0.15
        private const val ACK_FALLBACK_TRANSLATION_M = 0.18
        private const val ACK_SCENE_ROTATION_DEG = 2.5
    }
}
