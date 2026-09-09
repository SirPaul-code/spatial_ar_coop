package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.LinkedHashMap
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
 * High-assurance shared-world coordinator.
 *
 * LOCKED is a safety state, not merely "a solver returned a matrix". A local solve
 * must carry cross-device metric evidence and its proposed transform is independently
 * re-checked against fresh mutual visual features + depth. A transform received from
 * the peer is never adopted from sender metadata alone: the receiver proves it in its
 * own camera/depth observations before ACKing it.
 *
 * Once verified, the transform remains static. A low-rate watchdog may invalidate a
 * contradicted transform, but it never nudges/moves an existing world. This prevents
 * a valid local POI from being projected tens of metres away on the peer while the UI
 * incorrectly says LOCKED.
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
        val lockValidationFailures: Int = 0,
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
    private val poiLock = Any()
    private val localFrames = ArrayDeque<CapturedFrame>()
    private val remoteFrames = ArrayDeque<CapturedFrame>()
    private val remoteStaticPois = LinkedHashMap<Long, WireMessage.Poi>()
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
    @Volatile private var activePoi = false
    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastSolveStartedMs = 0L
    @Volatile private var lastTransformBroadcastAtMs = 0L
    @Volatile private var lastPeerVerificationAttemptMs = 0L
    @Volatile private var lastWatchdogStartedMs = 0L
    @Volatile private var lastLockInliers = 0
    @Volatile private var lastLockReprojectionPx = Float.NaN
    @Volatile private var lastLockSource = "VISION"
    @Volatile private var lastAgreement = SceneAgreement(Double.NaN, Double.NaN, Double.NaN, 0)
    @Volatile private var peerBootstrapFailures = 0
    @Volatile private var lockValidationFailures = 0
    @Volatile private var rangeContradictions = 0

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
        AcquisitionBurstController.reacquire()
        resetAlignment(clearFrames = true, clearPoi = true)
        transport.sendAlignmentReset(reason)
    }

    fun onPeerAlignmentReset(reason: String = "peer AR state changed") {
        AcquisitionBurstController.reacquire()
        resetAlignment(clearFrames = true, clearPoi = true)
    }

    fun onLocalFrame(frame: CapturedFrame) {
        synchronized(frameLock) { addDiverseKeyframe(localFrames, frame) }
        transport.sendFrame(frame)
        updateFusionSeed()
        tryVerifyPeerTransform()
        maybeResendLockedTransform()
        if (lockedTransform == null) maybeSolve() else maybeValidateLockedTransform()
    }

    fun onRemoteFrame(frame: CapturedFrame) {
        synchronized(frameLock) { addDiverseKeyframe(remoteFrames, frame) }
        updateFusionSeed()
        tryVerifyPeerTransform()
        maybeResendLockedTransform()
        if (lockedTransform == null) maybeSolve() else maybeValidateLockedTransform()
    }

    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (!distanceM.isFinite() || distanceM !in 0.05f..250f) return
        latestRangeM = distanceM
        latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
        latestRangeSource = "RTT"
        val now = System.currentTimeMillis()
        lastRttAtMs = now
        lastRangeAtMs = now
        updateFusionSeed()
        verifyLockedRangeOrInvalidate()
        tryVerifyPeerTransform()
        emitQuality()
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
        verifyLockedRangeOrInvalidate()
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
        if (isDynamicVehicleOwner(message.owner)) {
            publishDynamicPoi(message)
            return
        }
        synchronized(poiLock) { remoteStaticPois[message.id] = message }
        activePoi = true
        publishPendingPoisIfPossible()
    }

    fun clearPoi(sendToPeer: Boolean = true) {
        synchronized(poiLock) { remoteStaticPois.clear() }
        activePoi = false
        if (sendToPeer) transport.sendClearPoi()
        listener.onPoiCleared()
    }

    fun sendPoi(id: Long, pointLocalWorld: FloatArray, owner: String): Boolean {
        if (!canPlacePoi()) return false
        transport.sendPoi(id, owner, pointLocalWorld)
        if (!isDynamicVehicleOwner(owner)) activePoi = true
        return true
    }

    fun sendPoi(pointLocalWorld: FloatArray, owner: String): Boolean =
        sendPoi(System.nanoTime(), pointLocalWorld, owner)

    fun canPlacePoi(): Boolean = transport.connected && latestQuality.bothReady
    fun isPeerConnected(): Boolean = transport.connected
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

        val sameBurst = frame.burstId != 0L && frame.burstId == last.burstId
        val newBurstSequence = frame.burstId != 0L && frame.burstSequence >= 0 &&
            (!sameBurst || frame.burstSequence != last.burstSequence)
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

        if (newBurstSequence || (motionUsable && (spatiallyNew || temporallyNew))) {
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
        return Pair(translation, Math.toDegrees(2.0 * acos(normalizedDot)))
    }

    private fun maybeSolve() {
        if (!transport.connected || lockedTransform != null) return
        val now = System.currentTimeMillis()
        if (!transport.isHostRole && pendingPeerTransform == null &&
            now - connectedAtMs < CLIENT_HOST_SOLVE_GRACE_MS
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
                    if (isVeryStrongResult(result)) break
                }
                if (serial == solveSerial.get()) acceptInitialResult(best)
            } finally {
                solving.set(false)
                if (serial == solveSerial.get() && lockedTransform == null) maybeSolve()
            }
        }
    }

    /**
     * Every solver candidate is independently proved before it can enter consensus.
     * This deliberately costs another feature pass during acquisition; correctness is
     * more important than compute, and the check disappears once the world is locked.
     */
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

        if (!isMetricConsistent(raw)) return null
        val proof = try {
            SharedTransformVerifier.verify(remoteVision, localVision, raw.transformLocalFromRemote)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!proof.passed || !isPredictedRangeCompatible(proof.predictedDeviceDistanceM)) return null

        val yawPrior = FusionMath.yawPrior(pair.remote, pair.local)
        val evidence = proof.evidence
        return raw.copy(
            inliers = min(raw.inliers, evidence.visualInliers),
            correspondences = min(raw.correspondences, evidence.visualCandidates),
            medianReprojectionPx = max(raw.medianReprojectionPx, evidence.medianReprojectionPx),
            imageCoverage = min(raw.imageCoverage, evidence.imageCoverage),
            predictedDeviceDistanceM = proof.predictedDeviceDistanceM,
            headingResidualDeg = FusionMath.yawResidualDeg(raw.transformLocalFromRemote, yawPrior),
            sensorPriorConfidence = yawPrior?.confidence ?: 0f,
            gravityTiltDeg = evidence.gravityTiltDeg,
            metricPairs = evidence.metricPairs,
            metricInliers = evidence.metricInliers,
            medianMetricResidualM = evidence.medianMetricResidualM,
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
                val burstAffinity = burstPairAffinity(local, remote)
                val score = recency * 0.38 + temporalAffinity * 0.78 + support * 1.15 +
                    motionScore * 0.48 + burstAffinity
                out += FramePair(remote, local, score)
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun burstPairAffinity(local: CapturedFrame, remote: CapturedFrame): Double {
        if (local.burstId == 0L || local.burstId != remote.burstId) return 0.0
        if (local.burstSequence < 0 || remote.burstSequence < 0) return 0.35
        return when (abs(local.burstSequence - remote.burstSequence)) {
            0 -> 4.0
            1 -> 2.4
            2 -> 1.1
            else -> 0.25
        }
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
            result.medianReprojectionPx.isFinite() && result.medianReprojectionPx <= MAX_LOCK_REPROJECTION_PX &&
            result.imageCoverage >= MIN_LOCK_COVERAGE && confidence >= MIN_LOCK_CONFIDENCE &&
            gravityOk && rangeOk && metricOk

        localConfidence = confidence
        if (passes) {
            candidateHistory.addLast(Candidate(result.transformLocalFromRemote.copyOf(), confidence))
            while (candidateHistory.size > CANDIDATE_WINDOW) candidateHistory.removeFirst()

            val cluster = strongestCluster(candidateHistory, CLUSTER_TRANSLATION_M, CLUSTER_ROTATION_DEG)
            stableCount = cluster.size
            val requiredConsensus = if (isVeryStrongResult(result)) 2 else 3
            if (cluster.size >= requiredConsensus) {
                lockedTransform = consensusMedoid(cluster)
                lastLockInliers = result.inliers
                lastLockReprojectionPx = result.medianReprojectionPx.toFloat()
                lastLockSource = if (transport.isHostRole) "HOST_VERIFIED_METRIC" else "CLIENT_VERIFIED_METRIC"
                lockValidationFailures = 0
                rangeContradictions = 0
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
            lockValidationFailures = lockValidationFailures,
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
            "RTT" -> max(RTT_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 0.12) * 3.5 + 0.18)
            "BLE" -> max(BLE_MIN_RANGE_TOLERANCE_M, (std?.toDouble() ?: 1.0) * 1.8 + 0.60)
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
            "RTT" -> max(RTT_CANDIDATE_TOLERANCE_M, (std?.toDouble() ?: 0.12) * 4.0 + 0.22)
            "BLE" -> max(BLE_CANDIDATE_TOLERANCE_M, (std?.toDouble() ?: 1.0) * 2.0 + 0.75)
            else -> return true
        }
        return abs(predicted - range.toDouble()) <= allowed
    }

    private fun isMetricConsistent(result: AlignmentEngine.Result): Boolean =
        TransformSafetyPolicy.metricEvidencePasses(
            result.metricPairs,
            result.metricInliers,
            result.medianMetricResidualM,
        )

    private fun geometricConfidence(result: AlignmentEngine.Result): Float {
        if (result.correspondences <= 0 || !result.medianReprojectionPx.isFinite()) return 0f
        val ratio = (result.inliers.toDouble() / result.correspondences).coerceIn(0.0, 1.0)
        val support = min(1.0, result.inliers / 20.0)
        val coverage = min(1.0, result.imageCoverage / 0.14)
        val reprojection = exp(-result.medianReprojectionPx / 4.5)
        var confidence = ratio * support * coverage * reprojection
        if (result.metricPairs >= TransformSafetyPolicy.MIN_METRIC_PAIRS &&
            result.medianMetricResidualM.isFinite()
        ) {
            val metricRatio = result.metricInliers.toDouble() / result.metricPairs
            confidence *= 0.65 + 0.35 * (metricRatio * exp(-result.medianMetricResidualM / 0.16))
        }
        return confidence.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Process transforms from the peer. A bootstrap proposal is only scheduled for
     * independent verification; it is never adopted directly from sender claims.
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

        val isAck = message.transformSource == "PEER_ACK"
        if (lockedTransform == null) {
            if (isAck) return
            val bootstrapMetadataOk = message.confidence >= PEER_BOOTSTRAP_MIN_CONFIDENCE &&
                message.transformInliers >= PEER_BOOTSTRAP_MIN_INLIERS &&
                message.transformMedianReprojectionPx.isFinite() &&
                message.transformMedianReprojectionPx <= PEER_BOOTSTRAP_MAX_REPROJECTION_PX
            if (!bootstrapMetadataOk) return
            schedulePeerBootstrapVerification(message, peerCandidate)
            return
        }

        val existing = lockedTransform ?: return
        val agreement = sceneAgreement(existing, peerCandidate)
        lastAgreement = agreement

        if (isAck) {
            if (!ackAgreementAcceptable(agreement)) return
            peerReady = true
            peerTransformVerified = true
            pendingPeerTransform = null
            peerBootstrapFailures = 0
            lastLockSource = if (transport.isHostRole) "HOST_VERIFIED_ACK" else "VERIFIED_ACK"
            emitQuality()
            publishPendingPoisIfPossible()
            return
        }

        val hasVisualEvidence = message.transformInliers >= MIN_LOCK_INLIERS &&
            message.transformMedianReprojectionPx.isFinite() &&
            message.transformMedianReprojectionPx <= MAX_PEER_REPROJECTION_PX
        if (!hasVisualEvidence || !peerAgreementAcceptable(agreement, message.confidence)) return
        peerReady = true

        if (transport.isHostRole) {
            pendingPeerTransform = null
            peerTransformVerified = false
            lastLockSource = "HOST_CANONICAL_WAIT_ACK"
            broadcastLockedTransform(force = true)
            emitQuality()
            return
        }

        // Both sides solved independently and strongly agree. The peer candidate may
        // now replace the client's equivalent transform because the host is canonical.
        lockedTransform = peerCandidate.copyOf()
        localConfidence = min(0.99f, max(localConfidence, message.confidence * 0.97f))
        stableCount = max(stableCount, 2)
        lastLockInliers = message.transformInliers
        lastLockReprojectionPx = message.transformMedianReprojectionPx
        lastLockSource = "HOST_CANONICAL_AGREED"
        peerTransformVerified = true
        pendingPeerTransform = null
        peerBootstrapFailures = 0

        transport.sendAlignmentTransform(
            senderFromPeer = lockedTransform ?: peerCandidate,
            confidence = localConfidence.coerceAtLeast(MIN_PEER_TRANSFORM_CONFIDENCE),
            inliers = lastLockInliers,
            medianReprojectionPx = lastLockReprojectionPx,
            source = "PEER_ACK",
        )
        transport.sendQuality(localConfidence, stableCount, true)
        emitQuality()
        publishPendingPoisIfPossible()
    }

    private fun schedulePeerBootstrapVerification(
        message: WireMessage.Quality,
        peerCandidate: DoubleArray,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastPeerVerificationAttemptMs < PEER_VERIFY_INTERVAL_MS) return
        val pairs = buildFramePairs().take(PEER_VERIFY_PAIR_ATTEMPTS)
        if (pairs.isEmpty() || !solving.compareAndSet(false, true)) return
        lastPeerVerificationAttemptMs = now
        val serial = solveSerial.incrementAndGet()

        solveExecutor.execute {
            var best: SharedTransformVerifier.Verification? = null
            var conclusiveFailures = 0
            try {
                for (pair in pairs) {
                    val proof = runCatching {
                        SharedTransformVerifier.verify(pair.remote, pair.local, peerCandidate)
                    }.getOrNull() ?: continue
                    if (best == null || proof.score > best!!.score) best = proof
                    if (proof.passed && isPredictedRangeCompatible(proof.predictedDeviceDistanceM)) break
                    conclusiveFailures += 1
                }

                synchronized(this) {
                    if (serial != solveSerial.get() || lockedTransform != null || pendingPeerTransform !== message) {
                        return@synchronized
                    }
                    val proof = best
                    if (proof != null && proof.passed && isPredictedRangeCompatible(proof.predictedDeviceDistanceM)) {
                        adoptVerifiedPeerBootstrap(message, peerCandidate, proof)
                    } else if (conclusiveFailures > 0) {
                        peerBootstrapFailures += 1
                        if (peerBootstrapFailures >= PEER_BOOTSTRAP_REJECT_COUNT) {
                            rejectPeerBootstrap("peer transform contradicted by local visual/depth evidence")
                        }
                    }
                }
            } finally {
                solving.set(false)
                if (serial == solveSerial.get() && lockedTransform == null) maybeSolve()
            }
        }
    }

    @Synchronized private fun adoptVerifiedPeerBootstrap(
        message: WireMessage.Quality,
        peerCandidate: DoubleArray,
        proof: SharedTransformVerifier.Verification,
    ) {
        if (lockedTransform != null || pendingPeerTransform !== message) return
        solveSerial.incrementAndGet()
        val evidence = proof.evidence
        lockedTransform = peerCandidate.copyOf()
        localConfidence = min(0.99f, max(localConfidence, message.confidence * 0.97f))
        stableCount = max(stableCount, 1)
        lastLockInliers = evidence.visualInliers
        lastLockReprojectionPx = evidence.medianReprojectionPx.toFloat()
        lastLockSource = "PEER_BOOTSTRAP_LOCALLY_VERIFIED"
        peerReady = true
        peerTransformVerified = true
        pendingPeerTransform = null
        peerBootstrapFailures = 0
        lockValidationFailures = 0
        rangeContradictions = 0

        latestQuality = latestQuality.copy(
            confidence = localConfidence,
            inliers = evidence.visualInliers,
            correspondences = evidence.visualCandidates,
            medianReprojectionPx = evidence.medianReprojectionPx,
            imageCoverage = evidence.imageCoverage,
            localReady = true,
            peerReady = true,
            peerTransformVerified = true,
            gravityTiltDeg = evidence.gravityTiltDeg,
            fusionSource = lastLockSource,
            metricPairs = evidence.metricPairs,
            metricInliers = evidence.metricInliers,
            medianMetricResidualM = evidence.medianMetricResidualM,
            lockValidationFailures = 0,
        )

        transport.sendAlignmentTransform(
            senderFromPeer = lockedTransform ?: peerCandidate,
            confidence = localConfidence.coerceAtLeast(MIN_PEER_TRANSFORM_CONFIDENCE),
            inliers = lastLockInliers,
            medianReprojectionPx = lastLockReprojectionPx,
            source = "PEER_ACK",
        )
        transport.sendQuality(localConfidence, stableCount, true)
        emitQuality()
        publishPendingPoisIfPossible()
    }

    @Synchronized private fun rejectPeerBootstrap(reason: String) {
        pendingPeerTransform = null
        peerTransformVerified = false
        peerReady = false
        peerBootstrapFailures = 0
        candidateHistory.clear()
        stableCount = 0
        AcquisitionBurstController.reacquire()
        transport.sendAlignmentReset(reason)
        emitQuality()
    }

    /**
     * A verified transform is immutable. The watchdog only proves it or revokes it.
     * No shared view -> inconclusive -> keep the lock. Shared view that repeatedly
     * contradicts the transform -> fail closed and reacquire.
     */
    private fun maybeValidateLockedTransform() {
        val transform = lockedTransform ?: return
        if (!transport.connected || !peerTransformVerified) return
        val now = System.currentTimeMillis()
        if (now - lastWatchdogStartedMs < LOCK_WATCHDOG_INTERVAL_MS) return
        val pairs = buildFramePairs().take(LOCK_WATCHDOG_PAIR_ATTEMPTS)
        if (pairs.isEmpty() || !solving.compareAndSet(false, true)) return
        lastWatchdogStartedMs = now
        val serial = solveSerial.get()
        val snapshot = transform

        solveExecutor.execute {
            var sawConclusive = false
            var passed = false
            try {
                for (pair in pairs) {
                    val proof = runCatching {
                        SharedTransformVerifier.verify(pair.remote, pair.local, snapshot)
                    }.getOrNull() ?: continue
                    sawConclusive = true
                    if (proof.passed && isPredictedRangeCompatible(proof.predictedDeviceDistanceM)) {
                        passed = true
                        break
                    }
                }
                synchronized(this) {
                    if (serial != solveSerial.get() || lockedTransform !== snapshot || !peerTransformVerified) {
                        return@synchronized
                    }
                    when {
                        passed -> {
                            lockValidationFailures = 0
                            rangeContradictions = 0
                            emitQuality()
                        }
                        sawConclusive -> {
                            lockValidationFailures += 1
                            emitQuality()
                            if (lockValidationFailures >= LOCK_WATCHDOG_FAILURES_TO_RESET) {
                                invalidateSharedLock("shared transform failed repeated visual/depth validation")
                            }
                        }
                    }
                }
            } finally {
                solving.set(false)
            }
        }
    }

    @Synchronized private fun verifyLockedRangeOrInvalidate() {
        val transform = lockedTransform ?: return
        if (!peerTransformVerified || latestRangeSource != "RTT") return
        if (candidateRangeCompatible(transform)) {
            rangeContradictions = 0
            return
        }
        rangeContradictions += 1
        if (rangeContradictions >= RANGE_CONTRADICTIONS_TO_RESET) {
            invalidateSharedLock("shared transform contradicts fresh Wi-Fi RTT range")
        }
    }

    @Synchronized private fun invalidateSharedLock(reason: String) {
        if (lockedTransform == null) return
        AcquisitionBurstController.reacquire()
        resetAlignment(clearFrames = true, clearPoi = true)
        transport.sendAlignmentReset(reason)
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
        if (!agreement.medianM.isFinite() || !agreement.p90M.isFinite() || !agreement.rotationDeg.isFinite()) return false
        val strong = localConfidence >= 0.16f && peerConfidence >= 0.16f
        return when {
            agreement.samples >= 120 -> {
                val medianLimit = if (strong) PEER_SCENE_MEDIAN_STRONG_M else PEER_SCENE_MEDIAN_M
                val p90Limit = if (strong) PEER_SCENE_P90_STRONG_M else PEER_SCENE_P90_M
                val rotLimit = if (strong) PEER_SCENE_ROTATION_STRONG_DEG else PEER_SCENE_ROTATION_DEG
                agreement.medianM <= medianLimit && agreement.p90M <= p90Limit && agreement.rotationDeg <= rotLimit
            }
            agreement.samples >= 20 -> agreement.medianM <= PEER_SCENE_MEDIAN_SPARSE_M &&
                agreement.p90M <= PEER_SCENE_P90_SPARSE_M && agreement.rotationDeg <= PEER_SCENE_ROTATION_SPARSE_DEG
            else -> agreement.medianM <= PEER_FALLBACK_TRANSLATION_M && agreement.rotationDeg <= PEER_FALLBACK_ROTATION_DEG
        }
    }

    private fun ackAgreementAcceptable(agreement: SceneAgreement): Boolean {
        if (!agreement.medianM.isFinite() || !agreement.rotationDeg.isFinite()) return false
        return if (agreement.samples >= 20) {
            agreement.medianM <= ACK_SCENE_MEDIAN_M && agreement.p90M <= ACK_SCENE_P90_M &&
                agreement.rotationDeg <= ACK_SCENE_ROTATION_DEG
        } else {
            agreement.medianM <= ACK_FALLBACK_TRANSLATION_M && agreement.rotationDeg <= ACK_SCENE_ROTATION_DEG
        }
    }

    private fun relativeRotationDeg(a: DoubleArray, b: DoubleArray): Double {
        val trace =
            a[0] * b[0] + a[4] * b[4] + a[8] * b[8] +
                a[1] * b[1] + a[5] * b[5] + a[9] * b[9] +
                a[2] * b[2] + a[6] * b[6] + a[10] * b[10]
        return Math.toDegrees(acos(((trace - 1.0) * 0.5).coerceIn(-1.0, 1.0)))
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

    private fun isVeryStrongResult(result: AlignmentEngine.Result): Boolean {
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityAcceptable = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 6.0
        return result.inliers >= 12 && result.correspondences >= 12 &&
            result.medianReprojectionPx.isFinite() && result.medianReprojectionPx <= 2.8 &&
            result.imageCoverage >= 0.065 && confidence >= 0.15f && gravityAcceptable &&
            result.metricPairs >= 7 && result.metricInliers >= 6 &&
            result.medianMetricResidualM.isFinite() && result.medianMetricResidualM <= 0.14 &&
            isRangeCompatible(result)
    }

    private fun resultScore(result: AlignmentEngine.Result): Double {
        if (!isRangeCompatible(result) || !isMetricConsistent(result)) return -1_000_000.0
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityBonus = if (result.gravityTiltDeg.isFinite()) {
            (1.0 - result.gravityTiltDeg / 18.0).coerceIn(0.0, 1.0) * 0.70
        } else 0.0
        val metricBonus = if (result.medianMetricResidualM.isFinite()) {
            (1.0 - result.medianMetricResidualM / 0.22).coerceIn(0.0, 1.0) * 1.5
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

    private fun publishDynamicPoi(message: WireMessage.Poi) {
        val transform = lockedTransform ?: return
        if (!peerTransformVerified) return
        val p = AlignmentEngine.transformPoint(transform, message.pointWorld)
        if (!p.all { it.isFinite() }) return
        listener.onRemotePoi(
            message.id,
            floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()),
            message.owner,
            localConfidence,
        )
    }

    private fun publishPendingPoisIfPossible() {
        val transform = lockedTransform ?: return
        if (!peerTransformVerified) return
        val pois = synchronized(poiLock) { remoteStaticPois.values.toList() }
        for (poi in pois) {
            val p = AlignmentEngine.transformPoint(transform, poi.pointWorld)
            if (!p.all { it.isFinite() }) continue
            listener.onRemotePoi(
                poi.id,
                floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()),
                poi.owner,
                localConfidence,
            )
        }
    }

    private fun isDynamicVehicleOwner(owner: String): Boolean = owner.startsWith(AUTO_CAR_PREFIX)

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
        lastPeerVerificationAttemptMs = 0L
        lastWatchdogStartedMs = 0L
        lastLockInliers = 0
        lastLockReprojectionPx = Float.NaN
        lastLockSource = "VISION"
        lastAgreement = SceneAgreement(Double.NaN, Double.NaN, Double.NaN, 0)
        peerBootstrapFailures = 0
        lockValidationFailures = 0
        rangeContradictions = 0
        latestQuality = Quality(rangeM = latestRangeM, rangeSource = latestRangeSource)
        if (clearPoi) {
            synchronized(poiLock) { remoteStaticPois.clear() }
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
            fusionSource = if (lockedTransform != null) lastLockSource else coarseSource,
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
            peerAgreementMedianM = agreement.medianM,
            peerAgreementP90M = agreement.p90M,
            peerAgreementRotationDeg = agreement.rotationDeg,
            lockValidationFailures = lockValidationFailures,
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoisIfPossible()
    }

    private fun isRigidTransform(t: DoubleArray): Boolean {
        if (t.size < 16 || !t.take(16).all { it.isFinite() }) return false
        if (abs(t[12]) > 1e-5 || abs(t[13]) > 1e-5 || abs(t[14]) > 1e-5 || abs(t[15] - 1.0) > 1e-4) return false
        val c0 = doubleArrayOf(t[0], t[4], t[8])
        val c1 = doubleArrayOf(t[1], t[5], t[9])
        val c2 = doubleArrayOf(t[2], t[6], t[10])
        fun norm(c: DoubleArray) = sqrt(c.sumOf { it * it })
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        if (abs(norm(c0) - 1.0) > 0.025 || abs(norm(c1) - 1.0) > 0.025 || abs(norm(c2) - 1.0) > 0.025) return false
        if (abs(dot(c0, c1)) > 0.025 || abs(dot(c0, c2)) > 0.025 || abs(dot(c1, c2)) > 0.025) return false
        val det = t[0] * (t[5] * t[10] - t[6] * t[9]) -
            t[1] * (t[4] * t[10] - t[6] * t[8]) +
            t[2] * (t[4] * t[9] - t[5] * t[8])
        return det in 0.985..1.015
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
        private const val AUTO_CAR_PREFIX = "AUTO:CAR:"

        private const val KEYFRAME_WINDOW = 18
        private const val KEYFRAME_TRANSLATION_M = 0.045
        private const val KEYFRAME_ROTATION_DEG = 3.0
        private const val KEYFRAME_MAX_INTERVAL_NS = 750_000_000L
        private const val MIN_KEYFRAME_MOTION_QUALITY = 0.42
        private const val SOLVE_MIN_INTERVAL_MS = 260L
        private const val MAX_PAIR_ATTEMPTS = 4
        private const val CANDIDATE_WINDOW = 12
        private const val CLUSTER_TRANSLATION_M = 0.12
        private const val CLUSTER_ROTATION_DEG = 2.5
        private const val ROTATION_TO_METERS_WEIGHT = 0.018

        private const val RTT_FRESH_MS = 5000L
        private const val RANGE_GATE_FRESH_MS = 8_000L
        private const val CLIENT_HOST_SOLVE_GRACE_MS = 2_500L
        private const val TRANSFORM_RETRY_MS = 650L

        private const val MAX_VISUAL_GRAVITY_TILT_DEG = 10.0
        private const val PEER_MAX_GRAVITY_TILT_DEG = 10.0
        private const val MIN_PEER_TRANSFORM_CONFIDENCE = 0.10f
        private const val MAX_PEER_REPROJECTION_PX = 4.0f
        private const val PEER_BOOTSTRAP_MIN_CONFIDENCE = 0.12f
        private const val PEER_BOOTSTRAP_MIN_INLIERS = 8
        private const val PEER_BOOTSTRAP_MAX_REPROJECTION_PX = 4.0f
        private const val PEER_VERIFY_INTERVAL_MS = 350L
        private const val PEER_VERIFY_PAIR_ATTEMPTS = 3
        private const val PEER_BOOTSTRAP_REJECT_COUNT = 2

        private const val LOCK_WATCHDOG_INTERVAL_MS = 1_800L
        private const val LOCK_WATCHDOG_PAIR_ATTEMPTS = 2
        private const val LOCK_WATCHDOG_FAILURES_TO_RESET = 3
        private const val RANGE_CONTRADICTIONS_TO_RESET = 2

        private const val MIN_LOCK_INLIERS = 8
        private const val MIN_LOCK_CORRESPONDENCES = 8
        private const val MAX_LOCK_REPROJECTION_PX = 4.0
        private const val MIN_LOCK_COVERAGE = 0.04
        private const val MIN_LOCK_CONFIDENCE = 0.09f

        private const val RTT_MIN_RANGE_TOLERANCE_M = 0.40
        private const val BLE_MIN_RANGE_TOLERANCE_M = 1.5
        private const val RTT_CANDIDATE_TOLERANCE_M = 0.50
        private const val BLE_CANDIDATE_TOLERANCE_M = 2.0

        private const val AGREEMENT_FRAME_COUNT = 4
        private const val MAX_AGREEMENT_SAMPLES = 1200
        private const val PEER_SCENE_MEDIAN_M = 0.12
        private const val PEER_SCENE_P90_M = 0.24
        private const val PEER_SCENE_ROTATION_DEG = 4.0
        private const val PEER_SCENE_MEDIAN_STRONG_M = 0.16
        private const val PEER_SCENE_P90_STRONG_M = 0.30
        private const val PEER_SCENE_ROTATION_STRONG_DEG = 5.0
        private const val PEER_SCENE_MEDIAN_SPARSE_M = 0.16
        private const val PEER_SCENE_P90_SPARSE_M = 0.30
        private const val PEER_SCENE_ROTATION_SPARSE_DEG = 5.0
        private const val PEER_FALLBACK_TRANSLATION_M = 0.28
        private const val PEER_FALLBACK_ROTATION_DEG = 5.0

        private const val ACK_SCENE_MEDIAN_M = 0.06
        private const val ACK_SCENE_P90_M = 0.12
        private const val ACK_FALLBACK_TRANSLATION_M = 0.12
        private const val ACK_SCENE_ROTATION_DEG = 2.0
    }
}
