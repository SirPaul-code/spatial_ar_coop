package com.sirpaul.stablear.demo

import android.content.Context
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.nativevision.XFeatView
import com.sirpaul.stablear.vision.ImageMatch
import com.sirpaul.stablear.vision.LocalSurfaceTracker

internal data class DemoVisionMatch(
    val image: ImageMatch,
    val templateCandidateToken: Long = 0L,
)

/** Reference integration for the lab app: XFeat/LiteRT first, LK/ORB fallback. */
internal class DemoVisionBackend(private val context: Context) : AutoCloseable {
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
    fun add(id: Long, frameId: Long, gray: ByteArray, width: Int, height: Int, pixel: V2): Boolean {
        require(frameId > 0)
        val classical = fallback.add(id, gray, width, height, pixel)
        var learnedAdded = false
        val ml = learned()
        if (ml != null) {
            try {
                // Never synthesize visual frame identities: this is the actual CPU-image timestamp
                // belonging to the placement exposure retained by the ARCore adapter.
                if (ml.beginFrame(frameId, gray, width, height)) {
                    learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                }
                currentGray?.let { live ->
                    if (currentFrameId != Long.MIN_VALUE && currentFrameId != frameId) {
                        ml.beginFrame(currentFrameId, live, currentWidth, currentHeight)
                    }
                }
            } catch (_: Throwable) {
                disableLearned(ml)
            }
        }
        if (learnedAdded || classical) ids += id
        return learnedAdded || classical
    }

    fun track(id: Long, predicted: V2?): DemoVisionMatch? {
        if (predicted != null) {
            val ml = xfeat
            if (ml != null) {
                try {
                    ml.track(id, predicted.x, predicted.y)?.let { m ->
                        // Snapshot the exact current patch now, while this inference map is still current.
                        // The token is inert until the AR thread independently accepts the observation.
                        val token = ml.stageTemplate(
                            id,
                            m.x,
                            m.y,
                            XFeatView(quality = m.meanReliability.coerceIn(0.0, 1.0)),
                        )
                        return DemoVisionMatch(
                            ImageMatch(
                                V2(m.x, m.y),
                                m.inliers,
                                m.medianReprojectionPx,
                                m.medianReprojectionPx,
                                "XFEAT_LITERT",
                                m.sigmaPx,
                            ),
                            token,
                        )
                    }
                } catch (_: Throwable) {
                    disableLearned(ml)
                }
            }
        }
        return fallback.track(id, predicted)?.let { DemoVisionMatch(it) }
    }

    /** Must run on the same vision worker that owns the LiteRT/XFeat tracker. */
    fun resolveTemplate(id: Long, token: Long, accepted: Boolean) {
        if (token <= 0) return
        val ml = xfeat ?: return
        try {
            if (accepted) ml.commitStagedTemplate(id, token)
            else ml.discardStagedTemplate(id, token)
        } catch (_: Throwable) {
            disableLearned(ml)
        }
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
}
