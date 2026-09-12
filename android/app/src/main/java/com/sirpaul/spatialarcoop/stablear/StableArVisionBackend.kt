package com.sirpaul.spatialarcoop.stablear

import android.content.Context
import com.sirpaul.stablear.core.V2
import com.sirpaul.stablear.nativevision.XFeatLiteRtTracker
import com.sirpaul.stablear.nativevision.XFeatView
import com.sirpaul.stablear.vision.ImageMatch
import com.sirpaul.stablear.vision.LocalSurfaceTracker

internal data class StableArVisionMatch(
    val image: ImageMatch,
    val templateCandidateToken: Long = 0L
)

/**
 * Product-side adapter only. The actual matcher/geometry remains in the independently buildable SDK.
 * One instance must live on one bounded vision worker thread.
 */
internal class StableArVisionBackend(private val context: Context) : AutoCloseable {
    private val fallback: LocalSurfaceTracker
    private var xfeatAttempted = false
    private var xfeat: XFeatLiteRtTracker? = null
    private var currentFrameId = Long.MIN_VALUE
    private var currentGray: ByteArray? = null
    private var currentWidth = 0
    private var currentHeight = 0

    init {
        // The official OpenCV AAR is an implementation detail of the fallback SDK module.
        System.loadLibrary("opencv_java4")
        fallback = LocalSurfaceTracker()
    }

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

    fun add(id: Long, frameId: Long, gray: ByteArray, width: Int, height: Int, pixel: V2): Boolean {
        require(frameId > 0)
        val classical = fallback.add(id, gray, width, height, pixel)
        var learnedAdded = false
        val ml = learned()
        if (ml != null) {
            try {
                if (ml.beginFrame(frameId, gray, width, height)) learnedAdded = ml.addRoot(id, pixel.x, pixel.y)
                currentGray?.let { live ->
                    if (currentFrameId != Long.MIN_VALUE && currentFrameId != frameId) {
                        ml.beginFrame(currentFrameId, live, currentWidth, currentHeight)
                    }
                }
            } catch (_: Throwable) {
                disableLearned(ml)
            }
        }
        return learnedAdded || classical
    }

    fun track(id: Long, predicted: V2?, view: XFeatView = XFeatView()): StableArVisionMatch? {
        if (predicted != null) {
            val ml = xfeat
            if (ml != null) {
                try {
                    ml.track(id, predicted.x, predicted.y, view)?.let { match ->
                        val candidateView = view.copy(quality = match.meanReliability.coerceIn(0.0, 1.0))
                        val token = ml.stageTemplate(id, match.x, match.y, candidateView)
                        return StableArVisionMatch(
                            image = ImageMatch(
                                pixel = V2(match.x, match.y),
                                inliers = match.inliers,
                                medianReprojectionPx = match.consensusPxAtModelScale,
                                forwardBackwardPx = match.consensusPxAtModelScale,
                                method = "XFEAT_LITERT",
                                sigmaPx = match.sigmaPx
                            ),
                            templateCandidateToken = token
                        )
                    }
                } catch (_: Throwable) {
                    disableLearned(ml)
                }
            }
        }
        return fallback.track(id, predicted)?.let(::StableArVisionMatch)
    }

    fun resolveTemplate(id: Long, token: Long, accepted: Boolean) {
        if (token <= 0) return
        val ml = xfeat ?: return
        try {
            if (accepted) ml.commitStagedTemplate(id, token) else ml.discardStagedTemplate(id, token)
        } catch (_: Throwable) {
            disableLearned(ml)
        }
    }

    fun remove(id: Long) {
        fallback.remove(id)
        runCatching { xfeat?.remove(id) }
    }

    override fun close() {
        runCatching { xfeat?.close() }
        xfeat = null
        fallback.close()
    }

    private fun disableLearned(value: XFeatLiteRtTracker) {
        runCatching { value.close() }
        if (xfeat === value) xfeat = null
    }
}
