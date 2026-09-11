package com.sirpaul.stablear.demo

import android.content.Context
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.vision.LocalSurfaceTracker

/**
 * Reference integration used by the lab app.
 *
 * XFeat/LiteRT is the preferred image-correspondence source. The established LK/ORB tracker stays
 * available as a deterministic fallback so a device without a supported LiteRT GPU can still run
 * the complete StableAR geometry pipeline.
 *
 * One instance is owned by the demo's single vision worker thread.
 */
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
            if (!ml.beginFrame(id, gray, width, height)) {
                ml.close()
                xfeat = null
            }
        } catch (_: Throwable) {
            runCatching { ml.close() }
            xfeat = null
        }
    }

    /** Captures both learned and classical immutable root evidence from the exact placement frame. */
    fun add(id: Long, gray: ByteArray, width: Int, height: Int, pixel: V2): Boolean {
        val classical = fallback.add(id, gray, width, height, pixel)
        val ml = learned()
        var learnedAdded = false
        if (ml != null) {
            try {
                // Placement can refer to a previously displayed frame, so never assume current ML
                // descriptors correspond to this root exposure.
                if (currentFrameId == gray.hashCode().toLong()) {
                    learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                } else if (ml.beginFrame(rootFrameId(id, gray), gray, width, height)) {
                    learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                    // Restore the latest live frame so subsequent track() uses the correct exposure.
                    currentGray?.let { live ->
                        if (currentFrameId != Long.MIN_VALUE) ml.beginFrame(currentFrameId, live, currentWidth, currentHeight)
                    }
                }
            } catch (_: Throwable) {
                runCatching { ml.close() }
                xfeat = null
            }
        }
        return learnedAdded || classical
    }

    fun track(id: Long, predicted: V2?): Match? {
        if (predicted != null) {
            val ml = xfeat
            if (ml != null) {
                try {
                    ml.track(id, predicted.x, predicted.y)?.let { m ->
                        return Match(
                            pixel = V2(m.x, m.y),
                            inliers = m.inliers,
                            reprojectionPx = m.medianReprojectionPx,
                            forwardBackwardPx = m.medianReprojectionPx,
                            sigmaPx = m.sigmaPx,
                            method = "XFEAT_LITERT",
                            score = m.score,
                            scoreMargin = m.scoreMargin,
                        )
                    }
                } catch (_: Throwable) {
                    runCatching { ml.close() }
                    xfeat = null
                }
            }
        }
        val m = fallback.track(id, predicted) ?: return null
        return Match(m.pixel, m.inliers, m.medianReprojectionPx, m.forwardBackwardPx, 1.0, m.method)
    }

    fun remove(id: Long) {
        fallback.remove(id)
        runCatching { xfeat?.remove(id) }
    }

    fun clear() {
        fallback.close()
        runCatching { xfeat?.clear() }
    }

    override fun close() {
        runCatching { xfeat?.close() }
        xfeat = null
        fallback.close()
    }

    private fun rootFrameId(id: Long, gray: ByteArray): Long {
        // Positive deterministic id local to this worker; it is only an inference-frame identity.
        return ((id shl 32) xor gray.contentHashCode().toLong()).and(Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
