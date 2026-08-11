package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.vision.Detection2D
import kotlin.math.abs


data class EstimatedPosition(
    val sitePosition: FloatArray,
    val uncertaintyMeters: Float,
    val method: String
)

object SpatialEstimator {
    fun estimate(
        frame: Frame,
        detection: Detection2D,
        worldFromSite: FloatArray,
        groundY: Float?
    ): EstimatedPosition? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val image = detection.rawBottomCenter
        val view = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, image, Coordinates2d.VIEW, view)
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)

        val bestHit = frame.hitTest(view[0], view[1])
            .filter { hit ->
                when (val trackable = hit.trackable) {
                    is DepthPoint -> true
                    is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        trackable.isPoseInPolygon(hit.hitPose)
                    else -> false
                }
            }
            .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))

        if (bestHit != null) {
            val site = PoseMath.transformPoint(siteFromWorld, bestHit.hitPose.translation)
            val (uncertainty, method) = when (bestHit.trackable) {
                is DepthPoint -> 0.25f to "depth"
                is Plane -> 0.32f to "plane"
                else -> 0.55f to "feature-point"
            }
            // Moving targets often create a depth hit on the target itself. Ground projection is
            // more stable for feet/wheels when both estimates agree reasonably well.
            val ground = groundY?.let { intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it) }
            if (ground != null && PoseMath.distance(site, ground) < 1.2f) {
                return EstimatedPosition(
                    sitePosition = floatArrayOf(site[0] * 0.45f + ground[0] * 0.55f, groundY, site[2] * 0.45f + ground[2] * 0.55f),
                    uncertaintyMeters = uncertainty,
                    method = "$method+ground"
                )
            }
            return EstimatedPosition(site, uncertainty, method)
        }

        val fallback = groundY?.let { intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it) }
        if (fallback != null) return EstimatedPosition(fallback, 0.65f, "ground-ray")

        // Do not invent a networked 3D point from class-size assumptions. Keep the accurate local
        // 2D detector box; publish a shared track only when Depth/plane/saved-ground supplies actual
        // spatial evidence.
        return null
    }

    fun centerGroundPoint(frame: Frame, worldFromSite: FloatArray, groundY: Float?): FloatArray? {
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        return if (groundY != null) {
            intersectGround(
                frame,
                floatArrayOf(dimensions[0] * 0.5f, dimensions[1] * 0.58f),
                PoseMath.rigidInverse(worldFromSite),
                groundY
            )
        } else {
            val view = floatArrayOf(0.5f * dimensions[0], 0.58f * dimensions[1])
            val output = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, view, Coordinates2d.VIEW, output)
            frame.hitTest(output[0], output[1]).firstOrNull()?.hitPose?.translation?.let {
                PoseMath.transformPoint(PoseMath.rigidInverse(worldFromSite), it)
            }
        }
    }

    private fun intersectGround(
        frame: Frame,
        imagePixel: FloatArray,
        siteFromWorld: FloatArray,
        groundY: Float
    ): FloatArray? {
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        if (focal[0] <= 0f || focal[1] <= 0f) return null
        val cameraDirection = PoseMath.normalize(
            floatArrayOf(
                (imagePixel[0] - principal[0]) / focal[0],
                -(imagePixel[1] - principal[1]) / focal[1],
                -1f
            )
        )
        val worldFromCamera = PoseMath.poseToMatrix(frame.camera.pose)
        val worldDirection = PoseMath.transformDirection(worldFromCamera, cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        if (abs(siteDirection[1]) < 0.015f) return null
        val siteOrigin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val distance = (groundY - siteOrigin[1]) / siteDirection[1]
        if (!distance.isFinite() || distance !in 0.15f..100f) return null
        return floatArrayOf(
            siteOrigin[0] + siteDirection[0] * distance,
            groundY,
            siteOrigin[2] + siteDirection[2] * distance
        )
    }

    private fun priority(trackable: Any): Int = when (trackable) {
        is DepthPoint -> 0
        is Plane -> 1
        else -> 2
    }
}
