package com.sirpaul.stablear.demo

import android.content.Context
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.vision.LocalSurfaceTracker

/** Reference integration for the lab app: XFeat/LiteRT first, LK/ORB fallback. */
internal class DemoVisionBackend(private val context: Context) : AutoCloseable {
    data class Match(
        val pixel: V2,
        val inliers: Int,
        val reprojectionPx: Double,
        val forwardBackwardPx: Double,
        val sigmaPx: Double,
        val method: String,
        val score: Double? = null,
        val scoreMargin: Double? = null,
    )

    private val fallback = LocalSurfaceTracker()
    private val ids = linkedSetOf<Long>()
    private var xfeatAttempted = false
    private var xfeat: XFeatLiteRtTracker? = null
    private var currentFrameId = Long.MIN_VALUE
    private var currentGray: ByteArray? = null
    private var currentWidth = 0
    private var currentHeight = 0

    val learnedBackendActive: Boolean get() = xfeat != null

    private fun learned(): XFeatLiteRtTracker? {
        if (!xfeatAttempted) {
            xfeatAttempted = true
            xfeat = XFeatLiteRtTracker.tryCreate(context, preferGpu = true)
        }
        return xfeat
    }

    fun beginFrame(id: Long, gray: ByteArray, width: Int, height: Int) {
        fallback.beginFrame(id, gray, width, height)
        currentFrameId = id
        currentGray = gray
        currentWidth = width
        currentHeight = height
        val ml = learned() ?: return
        try {
            if (!ml.beginFrame(id, gray, width, height)) disableLearned(ml)
        } catch (_: Throwable) {
            disableLearned(ml)
        }
    }

    /** Captures immutable root evidence from the exact placement exposure for both backends. */
    fun add(id: Long, gray: ByteArray, width: Int, height: Int, pixel: V2): Boolean {
        val classical = fallback.add(id, gray, width, height, pixel)
        var learnedAdded = false
        val ml = learned()
        if (ml != null) {
            try {
                if (ml.beginFrame(rootFrameId(id, gray), gray, width, height)) {
                    learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                }
                currentGray?.let { live ->
                    if (currentFrameId != Long.MIN_VALUE) ml.beginFrame(currentFrameId, live, currentWidth, currentHeight)
                }
            } catch (_: Throwable) {
                disableLearned(ml)
            }
        }
        if (learnedAdded || classical) ids += id
        return learnedAdded || classical
    }

    fun track(id: Long, predicted: V2?): Match? {
        if (predicted != null) {
            val ml = xfeat
            if (ml != null) {
                try {
                    ml.track(id, predicted.x, predicted.y)?.let { m ->
                        return Match(
                            pixel = V2(m.x, m.y), inliers = m.inliers,
                            reprojectionPx = m.medianReprojectionPx,
                            forwardBackwardPx = m.medianReprojectionPx,
                            sigmaPx = m.sigmaPx, method = "XFEAT_LITERT",
                            score = m.score, scoreMargin = m.scoreMargin,
                        )
                    }
                } catch (_: Throwable) {
                    disableLearned(ml)
                }
            }
        }
        val m = fallback.track(id, predicted) ?: return null
        return Match(m.pixel, m.inliers, m.medianReprojectionPx, m.forwardBackwardPx, 1.0, m.method)
    }

    fun remove(id: Long) {
        ids -= id
        fallback.remove(id)
        runCatching { xfeat?.remove(id) }
    }

    fun clear() {
        ids.toList().forEach(::remove)
        runCatching { xfeat?.clear() }
    }

    override fun close() {
        clear()
        runCatching { xfeat?.close() }
        xfeat = null
        fallback.close()
    }

    private fun disableLearned(ml: XFeatLiteRtTracker) {
        runCatching { ml.close() }
        if (xfeat === ml) xfeat = null
    }

    private fun rootFrameId(id: Long, gray: ByteArray): Long =
        ((id shl 32) xor gray.contentHashCode().toLong()).and(Long.MAX_VALUE).coerceAtLeast(1L)
}
