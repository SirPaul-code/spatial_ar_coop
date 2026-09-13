package com.sirpaul.spatialnomap

import android.util.Base64
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.nativevision.XFeatSparseFeature
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import kotlin.math.max

/**
 * Host-integration frontend that lets the two-phone ALIGNING state reuse the exact pinned
 * StableAR XFeat/LiteRT runtime instead of maintaining a completely separate visual frontend.
 *
 * Important ownership boundary: this class only proposes 2D<->2D correspondences. The existing
 * shared-world AlignmentEngine remains authoritative for essential/PnP/3D-3D geometry, metric
 * depth validation, gravity/range gates and peer transform confirmation. StableAR's local material
 * attachment solver remains a separate concern after a POI is created.
 */
object StableArAlignmentFrontend {
    data class Match(
        val remoteX: Double,
        val remoteY: Double,
        val localX: Double,
        val localY: Double,
        val cosine: Double,
        val margin: Double,
        val quality: Double,
    )

    private data class WorkerState(
        var attempted: Boolean = false,
        var tracker: XFeatLiteRtTracker? = null,
    )

    private data class GrayFrame(
        val width: Int,
        val height: Int,
        val bytes: ByteArray,
    )

    private val workerState = ThreadLocal.withInitial { WorkerState() }
    @Volatile private var latestStatus = "XFEAT_NOT_ATTEMPTED"

    fun status(): String = latestStatus

    fun match(remote: CapturedFrame, local: CapturedFrame): List<Match>? {
        val context = SpatialSyncApplication.applicationContext() ?: run {
            latestStatus = "XFEAT_NO_CONTEXT"
            return null
        }
        val state = workerState.get()
        if (!state.attempted) {
            state.attempted = true
            state.tracker = XFeatLiteRtTracker.tryCreate(context, preferGpu = true)
            latestStatus = if (state.tracker != null) "XFEAT_READY" else "XFEAT_UNAVAILABLE"
        }
        val tracker = state.tracker ?: return null
        val remoteGray = decodeGray(remote) ?: run {
            latestStatus = "XFEAT_REMOTE_DECODE_FAILED"
            return null
        }
        val localGray = decodeGray(local) ?: run {
            latestStatus = "XFEAT_LOCAL_DECODE_FAILED"
            return null
        }

        return try {
            val remoteId = safeFrameId(remote.timestampNs, 1L)
            val localId = safeFrameId(local.timestampNs, 2L)
            if (!tracker.beginFrame(remoteId, remoteGray.bytes, remoteGray.width, remoteGray.height)) {
                latestStatus = "XFEAT_REMOTE_INFERENCE_REJECTED"
                return null
            }
            val remoteFeatures = tracker.sparseFeatures(maxFeatures = MAX_FEATURES)
            if (!tracker.beginFrame(localId, localGray.bytes, localGray.width, localGray.height)) {
                latestStatus = "XFEAT_LOCAL_INFERENCE_REJECTED"
                return null
            }
            val localFeatures = tracker.sparseFeatures(maxFeatures = MAX_FEATURES)
            if (remoteFeatures.size < MIN_FEATURES || localFeatures.size < MIN_FEATURES) {
                latestStatus = "XFEAT_TOO_FEW_FEATURES:${remoteFeatures.size}/${localFeatures.size}"
                return null
            }

            val matches = mutualMatches(remoteFeatures, localFeatures)
            val distributed = spatiallyBalance(
                matches,
                remote.intrinsics.width.coerceAtLeast(remoteGray.width),
                remote.intrinsics.height.coerceAtLeast(remoteGray.height),
            )
            latestStatus = "XFEAT_MATCHES:${distributed.size}:${remoteFeatures.size}/${localFeatures.size}"
            distributed.takeIf { it.size >= MIN_MATCHES }
        } catch (t: Throwable) {
            // A learned frontend failure must never wedge ALIGNING. The caller immediately falls back
            // to the pre-existing SIFT frontend while all geometry/metric gates remain unchanged.
            latestStatus = "XFEAT_FAILED:${t.javaClass.simpleName}"
            runCatching { tracker.close() }
            state.tracker = null
            state.attempted = true
            null
        }
    }

    internal fun mutualMatches(
        remote: List<XFeatSparseFeature>,
        local: List<XFeatSparseFeature>,
    ): List<Match> {
        if (remote.isEmpty() || local.size < 2) return emptyList()

        val reverseBest = IntArray(local.size) { -1 }
        val reverseScore = DoubleArray(local.size) { Double.NEGATIVE_INFINITY }
        for (li in local.indices) {
            for (ri in remote.indices) {
                val score = cosine(remote[ri].descriptor, local[li].descriptor)
                if (score > reverseScore[li]) {
                    reverseScore[li] = score
                    reverseBest[li] = ri
                }
            }
        }

        val out = ArrayList<Match>()
        for (ri in remote.indices) {
            var bestIndex = -1
            var best = Double.NEGATIVE_INFINITY
            var second = Double.NEGATIVE_INFINITY
            for (li in local.indices) {
                val score = cosine(remote[ri].descriptor, local[li].descriptor)
                if (score > best) {
                    second = best
                    best = score
                    bestIndex = li
                } else if (score > second) {
                    second = score
                }
            }
            if (bestIndex < 0 || reverseBest[bestIndex] != ri) continue
            val margin = best - second
            if (best < MIN_COSINE || margin < MIN_MARGIN) continue
            val a = remote[ri]
            val b = local[bestIndex]
            val evidence = minOf(a.reliability, b.reliability) * minOf(a.detectorScore, b.detectorScore)
            val quality = best * (0.55 + 0.45 * evidence.coerceIn(0.0, 1.0))
            out += Match(a.x, a.y, b.x, b.y, best, margin, quality)
        }
        return out.sortedByDescending { it.quality }
    }

    private fun spatiallyBalance(matches: List<Match>, width: Int, height: Int): List<Match> {
        if (matches.size <= MIN_MATCHES || width <= 0 || height <= 0) return matches.take(MAX_MATCHES)
        val counts = IntArray(GRID_X * GRID_Y)
        val out = ArrayList<Match>(minOf(matches.size, MAX_MATCHES))
        for (m in matches) {
            val gx = ((m.remoteX / max(1.0, width.toDouble())) * GRID_X).toInt().coerceIn(0, GRID_X - 1)
            val gy = ((m.remoteY / max(1.0, height.toDouble())) * GRID_Y).toInt().coerceIn(0, GRID_Y - 1)
            val cell = gy * GRID_X + gx
            if (counts[cell] >= MAX_PER_CELL) continue
            counts[cell] += 1
            out += m
            if (out.size >= MAX_MATCHES) break
        }
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return Double.NEGATIVE_INFINITY
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i].toDouble()
        return dot
    }

    private fun safeFrameId(timestampNs: Long, salt: Long): Long {
        val positive = timestampNs and Long.MAX_VALUE
        return if (positive > 2L) positive else salt
    }

    private fun decodeGray(frame: CapturedFrame): GrayFrame? = try {
        val encoded = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        val source = MatOfByte(*encoded)
        val image = Imgcodecs.imdecode(source, Imgcodecs.IMREAD_GRAYSCALE)
        source.release()
        if (image.empty() || image.cols() <= 1 || image.rows() <= 1) {
            image.release()
            null
        } else {
            val bytes = ByteArray(image.cols() * image.rows())
            image.get(0, 0, bytes)
            val out = GrayFrame(image.cols(), image.rows(), bytes)
            image.release()
            out
        }
    } catch (_: Throwable) {
        null
    }

    private const val MAX_FEATURES = 384
    private const val MIN_FEATURES = 18
    private const val MIN_MATCHES = 10
    private const val MAX_MATCHES = 220
    private const val MIN_COSINE = 0.76
    private const val MIN_MARGIN = 0.035
    private const val GRID_X = 8
    private const val GRID_Y = 6
    private const val MAX_PER_CELL = 8
}
