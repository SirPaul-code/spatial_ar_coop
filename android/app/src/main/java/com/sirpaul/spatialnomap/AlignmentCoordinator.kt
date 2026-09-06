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
 * Establishes one canonical SE(3) transform between two independent ARCore worlds.
 *
 * The room host is the canonical solver. The joiner only solves as a fallback.
 * Once a visual lock is accepted it is authoritative until an explicit AR/session
 * reset: transient tracking, compass or radio noise cannot make LOCKED oscillate.
 *
 * Alignment works on a small spatially-diverse session micro-map rather than a
 * FIFO of near-identical camera frames. Diverse camera baselines give PnP/RANSAC
 * more viewpoint evidence and keep useful overlap alive while the users move.
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
    ) {
        val bothReady: Boolean get() = localReady && peerReady && peerTransformVerified
    }

    interface Listener {
        fun onAlignmentQuality(quality: Quality)
        fun onRemotePoi(pointLocal: FloatArray?, owner: String, confidence: Float)
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

    @Volatile private var lockedTransform: DoubleArray? = null
    @Volatile private var adoptedFromPeer = false
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
    @Volatile private var latestQuality = Quality()
    @Volatile private var pendingPoi: WireMessage.Poi? = null
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
        // Send the freshest captured frame even if it was too similar to retain as
        // a keyframe. The peer independently decides whether it improves its map.
        transport.sendFrame(frame)
        updateFusionSeed()
        tryAdoptOrVerifyPeerTransform()
        maybeResendLockedTransform()
        maybeSolve()
    }

    fun onRemoteFrame(frame: CapturedFrame) {
        synchronized(frameLock) { addDiverseKeyframe(remoteFrames, frame) }
        updateFusionSeed()
        tryAdoptOrVerifyPeerTransform()
        maybeResendLockedTransform()
        maybeSolve()
    }

    /** RTT/BLE are diagnostics and coarse priors, never a hard veto for visual 6DoF. */
    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (distanceM.isFinite() && distanceM in 0.05f..250f) {
            latestRangeM = distanceM
            latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
            latestRangeSource = "RTT"
            lastRttAtMs = System.currentTimeMillis()
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
        updateFusionSeed()
        emitQuality()
    }

    fun onPeerQuality(message: WireMessage.Quality) {
        if (message.senderFromPeer != null) {
            peerReady = true
            pendingPeerTransform = message
            tryAdoptOrVerifyPeerTransform()
        } else if (message.ready) {
            peerReady = true
        } else if (!peerTransformVerified) {
            peerReady = false
        }
        emitQuality()
    }

    fun onRemotePoi(message: WireMessage.Poi) {
        pendingPoi = message
        publishPendingPoiIfPossible()
    }

    fun clearPoi(sendToPeer: Boolean = true) {
        pendingPoi = null
        if (sendToPeer) transport.sendClearPoi()
        listener.onPoiCleared()
    }

    fun sendPoi(pointLocalWorld: FloatArray, owner: String): Boolean {
        if (!canPlacePoi()) return false
        transport.sendPoi(System.nanoTime(), owner, pointLocalWorld)
        return true
    }

    fun canPlacePoi(): Boolean = transport.connected && latestQuality.bothReady
    fun quality(): Quality = latestQuality

    /** Transient ARCore PAUSED never destroys a verified world lock. */
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

        val (translationM, rotationDeg) = cameraPoseDelta(last.pose, frame.pose)
        val lastNs = frameClockNs(last)
        val nowNs = frameClockNs(frame)
        val elapsedNs = if (lastNs > 0L && nowNs > lastNs) nowNs - lastNs else Long.MAX_VALUE
        val materiallyBetterDepth = frame.metricPoints.size >= max(32, (last.metricPoints.size * 1.30).toInt())
        val calmer = frameMotionQuality(frame) > frameMotionQuality(last) + 0.18
        val spatiallyNew = translationM >= KEYFRAME_TRANSLATION_M || rotationDeg >= KEYFRAME_ROTATION_DEG
        val temporallyNew = elapsedNs >= KEYFRAME_MAX_INTERVAL_NS

        if (spatiallyNew || temporallyNew) {
            window.addLast(frame)
        } else if (materiallyBetterDepth || calmer) {
            // Same viewpoint, better observation: replace instead of polluting the
            // micro-map with another near-duplicate frame.
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
        if (lockedTransform != null || adoptedFromPeer) return

        val now = System.currentTimeMillis()
        if (!transport.isHostRole && now - connectedAtMs < CLIENT_FALLBACK_SOLVE_DELAY_MS) return

        val pairs = buildFramePairs()
        if (pairs.isEmpty()) return
        if (now - lastSolveStartedMs < SOLVE_MIN_INTERVAL_MS || !solving.compareAndSet(false, true)) return
        lastSolveStartedMs = now
        val serial = solveSerial.incrementAndGet()

        solveExecutor.execute {
            var best: AlignmentEngine.Result? = null
            var bestPair: FramePair? = null
            try {
                for (pair in pairs.take(MAX_PAIR_ATTEMPTS)) {
                    val result = solveVisionAuthoritative(pair) ?: continue
                    if (best == null || resultScore(result) > resultScore(best!!)) {
                        best = result
                        bestPair = pair
                    }
                    if (isSingleFrameLockQuality(result)) break
                }
                if (serial == solveSerial.get()) acceptResult(best)
            } finally {
                solving.set(false)
                if (serial == solveSerial.get() && lockedTransform == null) {
                    val newer = synchronized(frameLock) {
                        val l = localFrames.peekLast()
                        val r = remoteFrames.peekLast()
                        bestPair == null || l !== bestPair?.local || r !== bestPair?.remote
                    }
                    if (newer) maybeSolve()
                }
            }
        }
    }

    /**
     * Magnetometers beside speakers, PCs and steel furniture are not trustworthy
     * enough to veto metric visual geometry. Solve without heading, then expose
     * compass residual only as diagnostics/prior telemetry.
     */
    private fun solveVisionAuthoritative(pair: FramePair): AlignmentEngine.Result? {
        val remoteVision = pair.remote.copy(
            sensors = pair.remote.sensors.copy(
                headingDeg = Float.NaN,
                orientationQuality = 0f,
            ),
        )
        val localVision = pair.local.copy(
            sensors = pair.local.sensors.copy(
                headingDeg = Float.NaN,
                orientationQuality = 0f,
            ),
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
            for ((ri, remote) in remotes.withIndex()) {
                val recency = (li + 1).toDouble() / locals.size + (ri + 1).toDouble() / remotes.size
                val support = min(remote.metricPoints.size, 1800) / 1800.0
                val motionScore = min(frameMotionQuality(local), frameMotionQuality(remote))
                val score = recency * 0.45 + support * 1.02 + motionScore * 0.48
                out += FramePair(remote, local, score)
            }
        }
        return out.sortedByDescending { it.score }
    }

    private fun frameMotionQuality(frame: CapturedFrame): Double {
        val g = frame.sensors.gyroRadS
        if (g.size < 3 || !g[0].isFinite() || !g[1].isFinite() || !g[2].isFinite()) return 0.55
        val magnitude = sqrt(
            g[0].toDouble() * g[0] +
                g[1].toDouble() * g[1] +
                g[2].toDouble() * g[2],
        )
        return (1.0 / (1.0 + magnitude * 0.45)).coerceIn(0.12, 1.0)
    }

    @Synchronized private fun acceptResult(result: AlignmentEngine.Result?) {
        if (lockedTransform != null || adoptedFromPeer) return
        if (result == null) {
            emitQuality()
            return
        }

        val confidence = max(result.confidence, geometricConfidence(result))
        val rangeDelta = latestRangeM?.let { range ->
            abs(result.predictedDeviceDistanceM.toFloat() - range)
        }
        val gravityOk = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= MAX_VISUAL_GRAVITY_TILT_DEG
        val passes = result.inliers >= MIN_LOCK_INLIERS &&
            result.correspondences >= MIN_LOCK_CORRESPONDENCES &&
            result.medianReprojectionPx <= MAX_LOCK_REPROJECTION_PX &&
            result.imageCoverage >= MIN_LOCK_COVERAGE &&
            confidence >= MIN_LOCK_CONFIDENCE &&
            gravityOk

        localConfidence = confidence
        if (passes) {
            candidateHistory.addLast(
                Candidate(
                    transform = result.transformLocalFromRemote.copyOf(),
                    confidence = confidence,
                ),
            )
            while (candidateHistory.size > CANDIDATE_WINDOW) candidateHistory.removeFirst()

            val cluster = strongestCluster(candidateHistory)
            stableCount = cluster.size
            val singleStrong = isSingleFrameLockQuality(result)
            val clustered = cluster.size >= 2 && result.inliers >= 9 && confidence >= 0.13f

            if (singleStrong || clustered) {
                lockedTransform = consensusMedoid(if (cluster.isNotEmpty()) cluster else candidateHistory.toList())
                adoptedFromPeer = false
                lastLockInliers = result.inliers
                lastLockReprojectionPx = result.medianReprojectionPx.toFloat()
                lastLockSource = if (transport.isHostRole) "HOST_VISUAL" else "CLIENT_FALLBACK_VISUAL"
            }
        }

        val ready = lockedTransform != null
        transport.sendQuality(confidence, stableCount, ready)
        if (ready) broadcastLockedTransform(force = true)

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
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    private fun geometricConfidence(result: AlignmentEngine.Result): Float {
        if (result.correspondences <= 0 || !result.medianReprojectionPx.isFinite()) return 0f
        val ratio = (result.inliers.toDouble() / result.correspondences).coerceIn(0.0, 1.0)
        val support = min(1.0, result.inliers / 18.0)
        val coverage = min(1.0, result.imageCoverage / 0.14)
        val reprojection = exp(-result.medianReprojectionPx / 5.0)
        return (ratio * support * coverage * reprojection).coerceIn(0.0, 1.0).toFloat()
    }

    @Synchronized private fun tryAdoptOrVerifyPeerTransform() {
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
            (!message.transformMedianReprojectionPx.isFinite() || message.transformMedianReprojectionPx <= 5.0f)
        if (!isAck && !hasVisualEvidence) return

        val existing = lockedTransform
        if (existing == null) {
            if (isAck) return
            adoptPeerTransform(localFromPeerSender, message, gravityTilt)
            return
        }

        if (isAck) {
            val (translationDelta, rotationDelta) = AlignmentEngine.transformDelta(existing, localFromPeerSender)
            if (translationDelta <= ACK_VERIFY_TRANSLATION_M && rotationDelta <= ACK_VERIFY_ROTATION_DEG) {
                peerReady = true
                peerTransformVerified = true
                pendingPeerTransform = null
                emitQuality()
                publishPendingPoiIfPossible()
            }
            return
        }

        if (!transport.isHostRole || adoptedFromPeer) {
            adoptPeerTransform(localFromPeerSender, message, gravityTilt)
            return
        }

        peerReady = true
        pendingPeerTransform = null
        peerTransformVerified = false
        broadcastLockedTransform(force = true)
        emitQuality()
    }

    private fun adoptPeerTransform(
        localFromPeerSender: DoubleArray,
        message: WireMessage.Quality,
        gravityTilt: Double,
    ) {
        lockedTransform = localFromPeerSender.copyOf()
        adoptedFromPeer = true
        localConfidence = (message.confidence * 0.97f).coerceIn(MIN_PEER_TRANSFORM_CONFIDENCE, 0.99f)
        stableCount = max(stableCount, 1)
        peerReady = true
        peerTransformVerified = true
        pendingPeerTransform = null
        lastLockInliers = message.transformInliers
        lastLockReprojectionPx = message.transformMedianReprojectionPx
        lastLockSource = "PEER:${message.transformSource.ifBlank { "VISUAL" }}"

        latestQuality = latestQuality.copy(
            confidence = localConfidence,
            inliers = message.transformInliers,
            medianReprojectionPx = message.transformMedianReprojectionPx.toDouble(),
            stableCount = stableCount,
            localReady = true,
            peerReady = true,
            peerTransformVerified = true,
            rangeM = latestRangeM,
            rangeSource = latestRangeSource,
            gravityTiltDeg = gravityTilt,
            fusionSource = lastLockSource,
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
        )

        transport.sendAlignmentTransform(
            senderFromPeer = localFromPeerSender,
            confidence = localConfidence,
            inliers = message.transformInliers,
            medianReprojectionPx = message.transformMedianReprojectionPx,
            source = "PEER_ACK",
        )
        transport.sendQuality(localConfidence, stableCount, true)
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    private fun maybeResendLockedTransform() {
        if (lockedTransform == null || peerTransformVerified || adoptedFromPeer) return
        val now = System.currentTimeMillis()
        if (now - lastTransformBroadcastAtMs >= TRANSFORM_RETRY_MS) {
            broadcastLockedTransform(force = true)
        }
    }

    private fun broadcastLockedTransform(force: Boolean) {
        val transform = lockedTransform ?: return
        if (adoptedFromPeer) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTransformBroadcastAtMs < 3000L) return
        if (force && lastTransformBroadcastAtMs != 0L && now - lastTransformBroadcastAtMs < 550L) return

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

    private fun strongestCluster(history: Collection<Candidate>): List<Candidate> {
        if (history.isEmpty()) return emptyList()
        val all = history.toList()
        var best = listOf(all.last())
        var bestScore = -1.0
        for (seed in all) {
            val cluster = all.filter { candidate ->
                val (translation, rotation) = AlignmentEngine.transformDelta(seed.transform, candidate.transform)
                translation <= CLUSTER_TRANSLATION_M && rotation <= CLUSTER_ROTATION_DEG
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
        val gravityAcceptable = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 8.0
        return result.inliers >= 14 &&
            result.correspondences >= 14 &&
            result.medianReprojectionPx <= 3.4 &&
            result.imageCoverage >= 0.085 &&
            confidence >= 0.19f &&
            gravityAcceptable
    }

    private fun resultScore(result: AlignmentEngine.Result): Double {
        val confidence = max(result.confidence, geometricConfidence(result))
        val gravityBonus = if (result.gravityTiltDeg.isFinite()) {
            (1.0 - result.gravityTiltDeg / 25.0).coerceIn(0.0, 1.0) * 0.65
        } else 0.0
        return confidence * 4.0 +
            min(result.inliers, 40) / 18.0 +
            min(result.imageCoverage, 0.30) * 3.2 +
            gravityBonus
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

    private fun publishPendingPoiIfPossible() {
        val poi = pendingPoi ?: return
        val transform = lockedTransform ?: return
        val p = AlignmentEngine.transformPoint(transform, poi.pointWorld)
        listener.onRemotePoi(
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
        adoptedFromPeer = false
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
        latestQuality = Quality(
            rangeM = latestRangeM,
            rangeSource = latestRangeSource,
        )
        if (clearPoi) {
            pendingPoi = null
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
                lockedTransform != null && peerTransformVerified -> "BIDIRECTIONAL VERIFIED"
                lockedTransform != null && adoptedFromPeer -> lastLockSource
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
        if (abs(norm(c0) - 1.0) > 0.08 || abs(norm(c1) - 1.0) > 0.08 || abs(norm(c2) - 1.0) > 0.08) return false
        if (abs(dot(c0, c1)) > 0.08 || abs(dot(c0, c2)) > 0.08 || abs(dot(c1, c2)) > 0.08) return false
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
        private const val KEYFRAME_WINDOW = 12
        private const val KEYFRAME_TRANSLATION_M = 0.08
        private const val KEYFRAME_ROTATION_DEG = 5.0
        private const val KEYFRAME_MAX_INTERVAL_NS = 1_500_000_000L
        private const val SOLVE_MIN_INTERVAL_MS = 330L
        private const val MAX_PAIR_ATTEMPTS = 7
        private const val CANDIDATE_WINDOW = 14
        private const val CLUSTER_TRANSLATION_M = 0.45
        private const val CLUSTER_ROTATION_DEG = 8.0
        private const val ROTATION_TO_METERS_WEIGHT = 0.015
        private const val RTT_FRESH_MS = 5000L
        private const val CLIENT_FALLBACK_SOLVE_DELAY_MS = 4500L
        private const val TRANSFORM_RETRY_MS = 750L
        private const val MAX_VISUAL_GRAVITY_TILT_DEG = 16.0
        private const val PEER_MAX_GRAVITY_TILT_DEG = 16.0
        private const val MIN_PEER_TRANSFORM_CONFIDENCE = 0.10f
        private const val ACK_VERIFY_TRANSLATION_M = 0.18
        private const val ACK_VERIFY_ROTATION_DEG = 3.5
        private const val MIN_LOCK_INLIERS = 8
        private const val MIN_LOCK_CORRESPONDENCES = 8
        private const val MAX_LOCK_REPROJECTION_PX = 4.6
        private const val MIN_LOCK_COVERAGE = 0.055
        private const val MIN_LOCK_CONFIDENCE = 0.11f
    }
}
