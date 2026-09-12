package com.sirpaul.spatialarcoop.stablear

import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.ar.PoseMath

/** Host-side initial placement only; StableAR takes over material-point refinement after this seed. */
internal object StableArTapPlacement {
    fun resolveSitePoint(frame: Frame, worldFromSite: FloatArray, viewX: Float, viewY: Float): FloatArray? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val hit = runCatching {
            frame.hitTest(viewX, viewY)
                .filter { value ->
                    when (val trackable = value.trackable) {
                        is DepthPoint -> trackable.trackingState == TrackingState.TRACKING
                        is Plane -> trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(value.hitPose)
                        is Point -> trackable.trackingState == TrackingState.TRACKING
                        else -> false
                    }
                }
                .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))
        }.getOrNull() ?: return null
        return PoseMath.transformPoint(PoseMath.rigidInverse(worldFromSite), hit.hitPose.translation)
    }

    private fun priority(value: Any): Int = when (value) {
        is DepthPoint -> 0
        is Plane -> 1
        is Point -> 2
        else -> 3
    }
}
