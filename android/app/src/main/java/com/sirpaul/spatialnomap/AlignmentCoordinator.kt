package com.sirpaul.spatialnomap

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

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
    ) { val bothReady: Boolean get() = localReady && peerReady }

    interface Listener {
        fun onAlignmentQuality(quality: Quality)
        fun onRemotePoi(pointLocal: FloatArray?, owner: String, confidence: Float)
        fun onPoiCleared()
    }

    private val solveExecutor = Executors.newSingleThreadExecutor()
    private val solving = AtomicBoolean(false)
    private val localFrame = AtomicReference<CapturedFrame?>(null)
    private val remoteFrame = AtomicReference<CapturedFrame?>(null)
    private val solveSerial = AtomicLong(0)
    private val stableHistory = ArrayDeque<DoubleArray>()

    @Volatile private var lockedTransform: DoubleArray? = null
    @Volatile private var candidateTransform: DoubleArray? = null
    @Volatile private var stableCount = 0
    @Volatile private var localConfidence = 0f
    @Volatile private var peerReady = false
    @Volatile private var latestRangeM: Float? = null
    @Volatile private var latestRangeStdM: Float? = null
    @Volatile private var latestQuality = Quality()
    @Volatile private var pendingPoi: WireMessage.Poi? = null
    @Volatile private var trackingLostAtMs = 0L
    @Volatile private var lastSolveStartedMs = 0L

    fun onConnected() = resetAlignment()
    fun onDisconnected() { resetAlignment(); peerReady = false; emitQuality() }

    fun onLocalFrame(frame: CapturedFrame) {
        localFrame.set(frame)
        transport.sendFrame(frame)
        maybeSolve()
    }

    fun onRemoteFrame(frame: CapturedFrame) { remoteFrame.set(frame); maybeSolve() }

    fun onRange(distanceM: Float, stdDevM: Float, samples: Int) {
        if (distanceM.isFinite() && distanceM in 0.05f..250f) {
            latestRangeM = distanceM
            latestRangeStdM = if (stdDevM.isFinite()) stdDevM else null
            emitQuality()
        }
    }

    fun onPeerQuality(message: WireMessage.Quality) { peerReady = message.ready; emitQuality() }
    fun onRemotePoi(message: WireMessage.Poi) { pendingPoi = message; publishPendingPoiIfPossible() }

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
        if (lostAt != 0L && now - lostAt > 3000L) resetAlignment()
    }

    fun close() { solveExecutor.shutdownNow() }

    private fun maybeSolve() {
        if (!transport.connected) return
        val remote = remoteFrame.get() ?: return
        val local = localFrame.get() ?: return
        val now = System.currentTimeMillis()
        if (now - lastSolveStartedMs < 550L || !solving.compareAndSet(false, true)) return
        lastSolveStartedMs = now
        val serial = solveSerial.incrementAndGet()
        solveExecutor.execute {
            try {
                val result = AlignmentEngine.solve(remote, local)
                if (serial == solveSerial.get()) acceptResult(result)
            } catch (_: Throwable) {
                if (serial == solveSerial.get()) acceptResult(null)
            } finally {
                solving.set(false)
                if (remoteFrame.get() !== remote || localFrame.get() !== local) maybeSolve()
            }
        }
    }

    @Synchronized private fun acceptResult(result: AlignmentEngine.Result?) {
        if (result == null) {
            if (lockedTransform == null) {
                stableCount = 0
                stableHistory.clear()
                localConfidence = 0f
                transport.sendQuality(0f, 0, false)
                emitQuality()
            }
            return
        }

        var confidence = result.confidence
        var rangeDelta: Float? = null
        latestRangeM?.let { range ->
            val delta = kotlin.math.abs(result.predictedDeviceDistanceM.toFloat() - range)
            rangeDelta = delta
            val allowed = max(1.5f, range * 0.35f + (latestRangeStdM ?: 0f) * 2f)
            if (delta > allowed) confidence *= 0.35f
        }

        val passes = result.inliers >= 10 && result.correspondences >= 10 &&
            result.medianReprojectionPx <= 3.5 && result.imageCoverage >= 0.12 && confidence >= 0.22f

        if (!passes) {
            if (lockedTransform == null) {
                stableCount = 0
                stableHistory.clear()
                localConfidence = confidence
                transport.sendQuality(confidence, 0, false)
            }
            latestQuality = Quality(confidence, result.inliers, result.correspondences, result.medianReprojectionPx,
                result.imageCoverage, stableCount, lockedTransform != null, peerReady, latestRangeM, rangeDelta)
            listener.onAlignmentQuality(latestQuality)
            return
        }

        val candidate = result.transformLocalFromRemote
        val previous = candidateTransform
        if (previous == null) {
            candidateTransform = candidate
            stableHistory.clear()
            stableHistory.addLast(candidate.copyOf())
            stableCount = 1
        } else {
            val (translationDelta, rotationDeltaDeg) = AlignmentEngine.transformDelta(previous, candidate)
            if (translationDelta <= 0.40 && rotationDeltaDeg <= 8.0) {
                candidateTransform = candidate
                stableHistory.addLast(candidate.copyOf())
                while (stableHistory.size > CONSENSUS_WINDOW) stableHistory.removeFirst()
                stableCount += 1
            } else {
                candidateTransform = candidate
                stableHistory.clear()
                stableHistory.addLast(candidate.copyOf())
                stableCount = 1
            }
        }

        if (stableCount >= REQUIRED_STABLE_SOLVES && confidence >= 0.28f) {
            lockedTransform = consensusMedoid(stableHistory)
        }
        localConfidence = confidence
        val ready = lockedTransform != null
        transport.sendQuality(confidence, stableCount, ready)
        latestQuality = Quality(confidence, result.inliers, result.correspondences, result.medianReprojectionPx,
            result.imageCoverage, stableCount, ready, peerReady, latestRangeM, rangeDelta)
        listener.onAlignmentQuality(latestQuality)
        publishPendingPoiIfPossible()
    }

    private fun consensusMedoid(history: Collection<DoubleArray>): DoubleArray {
        if (history.size <= 1) return history.first().copyOf()
        val transforms = history.toList()
        var bestIndex = 0
        var bestCost = Double.POSITIVE_INFINITY
        for (i in transforms.indices) {
            var cost = 0.0
            for (j in transforms.indices) {
                if (i == j) continue
                val (translation, rotationDeg) = AlignmentEngine.transformDelta(transforms[i], transforms[j])
                cost += translation + rotationDeg * ROTATION_TO_METERS_WEIGHT
            }
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = i
            }
        }
        return transforms[bestIndex].copyOf()
    }

    private fun publishPendingPoiIfPossible() {
        val poi = pendingPoi ?: return
        val transform = lockedTransform ?: return
        val p = AlignmentEngine.transformPoint(transform, poi.pointWorld)
        listener.onRemotePoi(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()), poi.owner, localConfidence)
    }

    @Synchronized private fun resetAlignment() {
        solveSerial.incrementAndGet()
        lockedTransform = null
        candidateTransform = null
        stableHistory.clear()
        stableCount = 0
        localConfidence = 0f
        peerReady = false
        latestQuality = Quality(rangeM = latestRangeM)
        if (transport.connected) transport.sendQuality(0f, 0, false)
        listener.onAlignmentQuality(latestQuality)
    }

    private fun emitQuality() {
        latestQuality = latestQuality.copy(confidence = localConfidence, stableCount = stableCount,
            localReady = lockedTransform != null, peerReady = peerReady, rangeM = latestRangeM)
        listener.onAlignmentQuality(latestQuality)
    }

    companion object {
        private const val REQUIRED_STABLE_SOLVES = 3
        private const val CONSENSUS_WINDOW = 7
        private const val ROTATION_TO_METERS_WEIGHT = 0.015
    }
}
