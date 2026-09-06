package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        val rangeM: Float? = null,
        val rangeDeltaM: Float? = null,
        val headingResidualDeg: Double = Double.NaN,
        val sensorPriorConfidence: Float = 0f,
        val fusionSource: String = "VISION",
        val fusionSeedConfidence: Float = 0f,
        val keyframesLocal: Int = 0,
        val keyframesRemote: Int = 0,
    ) {
        val bothReady: Boolean get() = localReady && peerReady
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
    @Volatile private var latestRangeM: Float? = null
    @Volatile private var latestRangeStdM: Float? = null
    @Volatile private var latestQuality = Quality()
    @Volatile private var pendingPoi: WireMessage.Poi? = null
    @Volatile private var trackingLostAtMs = 0L
    @Volatile private var lastSolveStartedMs = 0L

    fun onConnected() = resetAlignment(clearFrames = true, clearPoi = false)

    fun onDisconnected() {
        resetAlignment(clearFrames = true, clearPoi = true)
        peerReady = false
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
        maybeSolve()
    }

    fun onRemoteFrame(frame: CapturedFrame) {
        synchronized(frameLock) {
            remoteFrames.addLast(frame)
            while (remoteFrames.size > KEYFRAME_WINDOW) remoteFrames.removeFirst()
        }
        updateFusionSeed()
        maybeSolve()
    }

    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (distanceM.isFinite() && distanceM in 0.05f..250f) {
            latestRangeM = distanceM
            latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
            updateFusionSeed()
            emitQuality()
        }
    }

    fun onPeerQuality(message: WireMessage.Quality) {
        peerReady = message.ready
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
                if (serial == solveSerial.get()) acceptResult(best, bestPair)
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
                    // Same feature can be viewed from different angles, so heading
                    // is only a ranking hint, never a hard exclusion.
                    (1.0 - d / 180.0).coerceIn(0.0, 1.0)
                } else 0.35
                val score = recency * 0.55 + support * 0.75 + headingScore * 0.35
                out += FramePair(remote, local, score)
            }
        }
        return out.sortedByDescending { it.score }
    }

    @Synchronized private fun acceptResult(
        result: AlignmentEngine.Result?,
        pair: FramePair?,
    ) {
        if (result == null) {
            // A failed pair no longer destroys previous good evidence. With two
            // moving phones, latest/latest is often simply the wrong temporal pair.
            emitQuality()
            return
        }

        var confidence = result.confidence
        var rangeDelta: Float? = null
        latestRangeM?.let { range ->
            val delta = abs(result.predictedDeviceDistanceM.toFloat() - range)
            rangeDelta = delta
            val allowed = max(2.25f, range * 0.55f + (latestRangeStdM ?: 0.25f) * 3f)
            if (delta > allowed) confidence *= 0.62f
        }

        val headingOk = result.sensorPriorConfidence < 0.45f ||
            !result.headingResidualDeg.isFinite() || result.headingResidualDeg <= 42.0
        val passes = result.inliers >= 7 &&
            result.correspondences >= 7 &&
            result.medianReprojectionPx <= 4.8 &&
            result.imageCoverage >= 0.055 &&
            confidence >= 0.11f &&
            headingOk

        localConfidence = confidence
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
                result.headingResidualDeg.isFinite() && result.headingResidualDeg <= 24.0
            val singleStrong = isSingleFrameLockQuality(result) && confidence >= 0.24f
            val sensorSingle = sensorBacked && result.inliers >= 10 &&
                result.medianReprojectionPx <= 3.9 && result.imageCoverage >= 0.065 && confidence >= 0.17f
            val enoughCluster = cluster.size >= if (sensorBacked) 2 else 3

            if (lockedTransform == null && (singleStrong || sensorSingle || enoughCluster)) {
                lockedTransform = consensusMedoid(if (cluster.isNotEmpty()) cluster else candidateHistory.toList())
            } else if (lockedTransform != null && cluster.size >= 2) {
                // Slow refinement after lock without making the UI flap.
                lockedTransform = consensusMedoid(cluster)
            }
        }

        val ready = lockedTransform != null
        transport.sendQuality(confidence, stableCount, ready)
        latestQuality = Quality(
            confidence = confidence,
            inliers = result.inliers,
            correspondences = result.correspondences,
            medianReprojectionPx = result.medianReprojectionPx,
            imageCoverage = result.imageCoverage,
            stableCount = stableCount,
            localReady = ready,
            peerReady = peerReady,
            rangeM = latestRangeM,
            rangeDeltaM = rangeDelta,
            headingResidualDeg = result.headingResidualDeg,
            sensorPriorConfidence = result.sensorPriorConfidence,
            fusionSource = if (ready) "VISION+SENSORS" else coarseSource,
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
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
        )
        val best = listOfNotNull(gnss, colocated).maxByOrNull { it.confidence } ?: return
        coarseTransform = best.transformLocalFromRemote
        coarseConfidence = best.confidence
        coarseSource = best.source

        // Only exceptionally strong GNSS is allowed to become an actual lock.
        // Normal indoor GNSS and scalar RTT remain priors, never fake precision.
        if (best.source == "GNSS+COMPASS" && best.confidence >= 0.64f) {
            lockedTransform = best.transformLocalFromRemote.copyOf()
            localConfidence = best.confidence
            stableCount = 1
            transport.sendQuality(best.confidence, 1, true)
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
        return result.inliers >= 16 &&
            result.correspondences >= 16 &&
            result.medianReprojectionPx <= 3.3 &&
            result.imageCoverage >= 0.095 &&
            result.confidence >= 0.24f &&
            headingAcceptable
    }

    private fun resultScore(result: AlignmentEngine.Result): Double {
        val headingBonus = if (result.headingResidualDeg.isFinite()) {
            (1.0 - result.headingResidualDeg / 90.0).coerceIn(0.0, 1.0) * result.sensorPriorConfidence
        } else 0.0
        return result.confidence * 4.0 +
            min(result.inliers, 40) / 20.0 +
            min(result.imageCoverage, 0.30) * 3.0 +
            headingBonus
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
        trackingLostAtMs = 0L
        lastSolveStartedMs = 0L
        latestQuality = Quality(rangeM = latestRangeM)
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
            rangeM = latestRangeM,
            fusionSource = if (lockedTransform != null) "VISION+SENSORS" else coarseSource,
            fusionSeedConfidence = coarseConfidence,
            keyframesLocal = synchronized(frameLock) { localFrames.size },
            keyframesRemote = synchronized(frameLock) { remoteFrames.size },
        )
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    companion object {
        private const val KEYFRAME_WINDOW = 6
        private const val MAX_PAIR_ATTEMPTS = 4
        private const val CANDIDATE_WINDOW = 14
        private const val CLUSTER_TRANSLATION_M = 0.70
        private const val CLUSTER_ROTATION_DEG = 12.0
        private const val ROTATION_TO_METERS_WEIGHT = 0.015
    }
}
