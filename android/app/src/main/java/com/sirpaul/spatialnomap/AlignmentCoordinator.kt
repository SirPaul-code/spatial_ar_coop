package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        val createdAtMs: Long,
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
    @Volatile private var coarseTransform: DoubleArray? = null
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
    @Volatile private var trackingLostAtMs = 0L
    @Volatile private var lastSolveStartedMs = 0L
    @Volatile private var lastTransformBroadcastAtMs = 0L
    @Volatile private var lastBroadcastTransform: DoubleArray? = null

    fun onConnected() = resetAlignment(clearFrames = true, clearPoi = false)

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
        synchronized(frameLock) {
            localFrames.addLast(frame)
            while (localFrames.size > KEYFRAME_WINDOW) localFrames.removeFirst()
        }
        transport.sendFrame(frame)
        updateFusionSeed()
        tryAdoptOrVerifyPeerTransform()
        maybeSolve()
    }

    fun onRemoteFrame(frame: CapturedFrame) {
        synchronized(frameLock) {
            remoteFrames.addLast(frame)
            while (remoteFrames.size > KEYFRAME_WINDOW) remoteFrames.removeFirst()
        }
        updateFusionSeed()
        tryAdoptOrVerifyPeerTransform()
        maybeSolve()
    }

    /** Precise Wi-Fi Aware RTT range. Always wins over BLE while fresh. */
    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (distanceM.isFinite() && distanceM in 0.05f..250f) {
            latestRangeM = distanceM
            latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
            latestRangeSource = "RTT"
            lastRttAtMs = System.currentTimeMillis()
            updateFusionSeed()
            tryAdoptOrVerifyPeerTransform()
            emitQuality()
        }
    }

    /**
     * Optional BLE RSSI fallback. It never replaces recent RTT and its large
     * uncertainty prevents it from masquerading as a precise position sensor.
     */
    fun onBleRange(distanceM: Float, stdDevM: Float, rssiDbm: Int) {
        if (!distanceM.isFinite() || distanceM !in 0.05f..30f) return
        val now = System.currentTimeMillis()
        if (now - lastRttAtMs <= RTT_FRESH_MS) return
        latestRangeM = distanceM
        latestRangeStdM = max(if (stdDevM.isFinite()) stdDevM else 1.0f, 0.75f)
        latestRangeSource = "BLE"
        updateFusionSeed()
        tryAdoptOrVerifyPeerTransform()
        emitQuality()
    }

    fun onPeerQuality(message: WireMessage.Quality) {
        peerReady = message.ready
        if (!message.ready) {
            peerTransformVerified = false
            pendingPeerTransform = null
        } else if (message.senderFromPeer != null) {
            pendingPeerTransform = message
            tryAdoptOrVerifyPeerTransform()
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

    fun onTrackingState(tracking: Boolean) {
        val now = System.currentTimeMillis()
        if (!tracking) {
            if (trackingLostAtMs == 0L) trackingLostAtMs = now
            return
        }
        val lostAt = trackingLostAtMs
        trackingLostAtMs = 0L
        if (lostAt != 0L && now - lostAt > 3500L) {
            resetAlignment(clearFrames = true, clearPoi = false)
        }
    }

    fun close() {
        solveExecutor.shutdownNow()
    }

    private fun maybeSolve() {
        if (!transport.connected) return
        val pairs = buildFramePairs()
        if (pairs.isEmpty()) return

        val now = System.currentTimeMillis()
        val minInterval = if (lockedTransform == null) 320L else 1250L
        if (now - lastSolveStartedMs < minInterval || !solving.compareAndSet(false, true)) return
        lastSolveStartedMs = now
        val serial = solveSerial.incrementAndGet()

        solveExecutor.execute {
            var best: AlignmentEngine.Result? = null
            var bestPair: FramePair? = null
            try {
                for (pair in pairs.take(MAX_PAIR_ATTEMPTS)) {
                    val result = try {
                        AlignmentEngine.solve(pair.remote, pair.local)
                    } catch (_: Throwable) {
                        null
                    } ?: continue

                    if (best == null || resultScore(result) > resultScore(best!!)) {
                        best = result
                        bestPair = pair
                    }
                    if (isSingleFrameLockQuality(result)) break
                }
                if (serial == solveSerial.get()) acceptResult(best)
            } finally {
                solving.set(false)
                if (serial == solveSerial.get()) {
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
     * Search a short history rather than matching only latest/latest. Two people
     * are never perfectly synchronized in how they move their phones; keeping
     * six keyframes per side means overlap found a few seconds ago remains useful.
     */
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
                val support = min(local.metricPoints.size, remote.metricPoints.size).coerceAtMost(1800) / 1800.0

                val headingScore = if (local.sensors.hasHeading && remote.sensors.hasHeading) {
                    val d = abs(FusionMath.angleDeltaDeg(
                        local.sensors.headingDeg.toDouble(),
                        remote.sensors.headingDeg.toDouble(),
                    ))
                    (1.0 - d / 180.0).coerceIn(0.0, 1.0)
                } else 0.35

                val pitchScore = if (local.sensors.pitchDeg.isFinite() && remote.sensors.pitchDeg.isFinite()) {
                    val d = abs(local.sensors.pitchDeg - remote.sensors.pitchDeg).toDouble()
                    (1.0 - d / 90.0).coerceIn(0.0, 1.0)
                } else 0.5

                val motionScore = min(frameMotionQuality(local), frameMotionQuality(remote))
                val score = recency * 0.45 + support * 0.80 + headingScore * 0.28 +
                    pitchScore * 0.18 + motionScore * 0.34
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
        if (result == null) {
            // A failed current pair must not erase previously accumulated evidence.
            emitQuality()
            return
        }

        var confidence = result.confidence
        var rangeDelta: Float? = null
        latestRangeM?.let { range ->
            val delta = abs(result.predictedDeviceDistanceM.toFloat() - range)
            rangeDelta = delta
            val baseAllowance = if (latestRangeSource == "BLE") 3.5f else 2.25f
            val allowed = max(baseAllowance, range * 0.55f + (latestRangeStdM ?: 0.25f) * 3f)
            if (delta > allowed) confidence *= if (latestRangeSource == "BLE") 0.82f else 0.62f
        }

        val headingOk = result.sensorPriorConfidence < 0.45f ||
            !result.headingResidualDeg.isFinite() || result.headingResidualDeg <= 42.0
        val gravityOk = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 20.0
        val passes = result.inliers >= 7 &&
            result.correspondences >= 7 &&
            result.medianReprojectionPx <= 4.8 &&
            result.imageCoverage >= 0.055 &&
            confidence >= 0.11f &&
            headingOk && gravityOk

        localConfidence = confidence
        var newlyLocked = false
        if (passes) {
            candidateHistory.addLast(
                Candidate(
                    transform = result.transformLocalFromRemote.copyOf(),
                    confidence = confidence,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            while (candidateHistory.size > CANDIDATE_WINDOW) candidateHistory.removeFirst()

            val cluster = strongestCluster(candidateHistory)
            stableCount = cluster.size
            val sensorBacked = result.sensorPriorConfidence >= 0.42f &&
                result.headingResidualDeg.isFinite() && result.headingResidualDeg <= 24.0 &&
                (!result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 8.0)
            val singleStrong = isSingleFrameLockQuality(result) && confidence >= 0.24f
            val sensorSingle = sensorBacked && result.inliers >= 10 &&
                result.medianReprojectionPx <= 3.9 && result.imageCoverage >= 0.065 && confidence >= 0.17f
            val enoughCluster = cluster.size >= if (sensorBacked) 2 else 3

            if (lockedTransform == null && (singleStrong || sensorSingle || enoughCluster)) {
                lockedTransform = consensusMedoid(if (cluster.isNotEmpty()) cluster else candidateHistory.toList())
                newlyLocked = true
            } else if (lockedTransform != null && cluster.size >= 2) {
                lockedTransform = consensusMedoid(cluster)
            }
        }

        val ready = lockedTransform != null
        transport.sendQuality(confidence, stableCount, ready)
        if (ready) {
            broadcastLockedTransform(
                confidence = confidence,
                inliers = result.inliers,
                medianReprojectionPx = result.medianReprojectionPx.toFloat(),
                source = if (newlyLocked) "VISION+IMU+RADIO" else "REFINED",
                force = newlyLocked,
            )
        }
        tryAdoptOrVerifyPeerTransform()

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
            fusionSource = when {
                ready && peerTransformVerified -> "BIDIRECTIONAL VERIFIED"
                ready -> "VISION+IMU+RADIO"
                else -> coarseSource
            },
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    @Synchronized private fun tryAdoptOrVerifyPeerTransform() {
        val message = pendingPeerTransform ?: return
        val peerSenderFromLocal = message.senderFromPeer ?: return
        if (peerSenderFromLocal.size < 16 || message.confidence < 0.14f) return
        if (!isRigidTransform(peerSenderFromLocal)) return

        val localFromPeerSender = invertRigid(peerSenderFromLocal) ?: return
        if (!isRigidTransform(localFromPeerSender)) return

        val gravityTilt = FusionMath.gravityTiltDeg(localFromPeerSender)
        if (gravityTilt.isFinite() && gravityTilt > PEER_MAX_GRAVITY_TILT_DEG) return

        val local: CapturedFrame?
        val remote: CapturedFrame?
        synchronized(frameLock) {
            local = localFrames.peekLast()
            remote = remoteFrames.peekLast()
        }

        if (local != null && remote != null) {
            val yawPrior = FusionMath.yawPrior(remote, local)
            val headingResidual = FusionMath.yawResidualDeg(localFromPeerSender, yawPrior)
            if (yawPrior != null && yawPrior.confidence >= 0.45f &&
                headingResidual.isFinite() && headingResidual > PEER_MAX_HEADING_DELTA_DEG
            ) return

            latestRangeM?.let { range ->
                val remoteCameraInLocal = AlignmentEngine.transformPoint(localFromPeerSender, remote.pose.t)
                val lc = local.pose.t
                val dx = remoteCameraInLocal[0] - lc[0]
                val dy = remoteCameraInLocal[1] - lc[1]
                val dz = remoteCameraInLocal[2] - lc[2]
                val predicted = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                val delta = abs(predicted - range)
                val base = if (latestRangeSource == "BLE") 4.0f else 2.5f
                val allowed = max(base, range * 0.65f + (latestRangeStdM ?: 0.3f) * 3.5f)
                if (delta > allowed) return
            }
        }

        val existing = lockedTransform
        if (existing == null) {
            // The sender already passed visual+sensor gates. Once the reciprocal
            // transform passes local physical checks, a second independent 6DoF
            // solve is not required just to unlock the UI.
            lockedTransform = localFromPeerSender.copyOf()
            localConfidence = (message.confidence * 0.94f).coerceIn(0.14f, 0.96f)
            stableCount = max(stableCount, 1)
            coarseSource = "PEER:${message.transformSource.ifBlank { "FUSED" }}"
            peerTransformVerified = true
            transport.sendQuality(localConfidence, stableCount, true)
        } else {
            val (translationDelta, rotationDelta) = AlignmentEngine.transformDelta(existing, localFromPeerSender)
            if (translationDelta <= PEER_VERIFY_TRANSLATION_M && rotationDelta <= PEER_VERIFY_ROTATION_DEG) {
                peerTransformVerified = true
                if (message.confidence > localConfidence + 0.08f) {
                    lockedTransform = localFromPeerSender.copyOf()
                    localConfidence = (message.confidence * 0.96f).coerceAtMost(0.98f)
                }
            } else {
                peerTransformVerified = false
            }
        }

        peerReady = true
        pendingPeerTransform = null
        emitQuality()
    }

    private fun broadcastLockedTransform(
        confidence: Float,
        inliers: Int,
        medianReprojectionPx: Float,
        source: String,
        force: Boolean,
    ) {
        val transform = lockedTransform ?: return
        val now = System.currentTimeMillis()
        val previous = lastBroadcastTransform
        val materiallyChanged = previous == null || run {
            val (dt, dr) = AlignmentEngine.transformDelta(previous, transform)
            dt > 0.08 || dr > 1.5
        }
        if (!force && !materiallyChanged && now - lastTransformBroadcastAtMs < 3000L) return

        lastBroadcastTransform = transform.copyOf()
        lastTransformBroadcastAtMs = now
        transport.sendAlignmentTransform(
            senderFromPeer = transform,
            confidence = confidence,
            inliers = inliers,
            medianReprojectionPx = medianReprojectionPx,
            source = source,
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
        val best = listOfNotNull(gnss, colocated).maxByOrNull { it.confidence } ?: return
        coarseTransform = best.transformLocalFromRemote
        coarseConfidence = best.confidence
        coarseSource = best.source

        // Only genuinely informative outdoor GNSS can establish a transform by
        // itself. Indoor GNSS, RTT and BLE remain priors and never fake 6DoF.
        if (best.source == "GNSS+COMPASS" && best.confidence >= 0.64f) {
            lockedTransform = best.transformLocalFromRemote.copyOf()
            localConfidence = best.confidence
            stableCount = 1
            transport.sendQuality(best.confidence, 1, true)
            broadcastLockedTransform(
                confidence = best.confidence,
                inliers = 0,
                medianReprojectionPx = Float.NaN,
                source = "GNSS+COMPASS",
                force = true,
            )
        }
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
        val headingAcceptable = result.sensorPriorConfidence < 0.45f ||
            (result.headingResidualDeg.isFinite() && result.headingResidualDeg <= 28.0)
        val gravityAcceptable = !result.gravityTiltDeg.isFinite() || result.gravityTiltDeg <= 8.0
        return result.inliers >= 16 &&
            result.correspondences >= 16 &&
            result.medianReprojectionPx <= 3.3 &&
            result.imageCoverage >= 0.095 &&
            result.confidence >= 0.24f &&
            headingAcceptable && gravityAcceptable
    }

    private fun resultScore(result: AlignmentEngine.Result): Double {
        val headingBonus = if (result.headingResidualDeg.isFinite()) {
            (1.0 - result.headingResidualDeg / 90.0).coerceIn(0.0, 1.0) * result.sensorPriorConfidence
        } else 0.0
        val gravityBonus = if (result.gravityTiltDeg.isFinite()) {
            (1.0 - result.gravityTiltDeg / 25.0).coerceIn(0.0, 1.0) * 0.65
        } else 0.0
        return result.confidence * 4.0 +
            min(result.inliers, 40) / 20.0 +
            min(result.imageCoverage, 0.30) * 3.0 +
            headingBonus + gravityBonus
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
        coarseTransform = null
        coarseConfidence = 0f
        coarseSource = "NONE"
        candidateHistory.clear()
        stableCount = 0
        localConfidence = 0f
        peerReady = false
        peerTransformVerified = false
        pendingPeerTransform = null
        trackingLostAtMs = 0L
        lastSolveStartedMs = 0L
        lastTransformBroadcastAtMs = 0L
        lastBroadcastTransform = null
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
                lockedTransform != null && coarseSource.startsWith("PEER:") -> coarseSource
                lockedTransform != null -> "VISION+IMU+RADIO"
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

    /** Inverse of a rigid row-major 4x4 transform [R t; 0 1]. */
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
        private const val KEYFRAME_WINDOW = 6
        private const val MAX_PAIR_ATTEMPTS = 4
        private const val CANDIDATE_WINDOW = 14
        private const val CLUSTER_TRANSLATION_M = 0.70
        private const val CLUSTER_ROTATION_DEG = 12.0
        private const val ROTATION_TO_METERS_WEIGHT = 0.015
        private const val RTT_FRESH_MS = 5000L
        private const val PEER_MAX_GRAVITY_TILT_DEG = 18.0
        private const val PEER_MAX_HEADING_DELTA_DEG = 48.0
        private const val PEER_VERIFY_TRANSLATION_M = 0.90
        private const val PEER_VERIFY_ROTATION_DEG = 16.0
    }
}
