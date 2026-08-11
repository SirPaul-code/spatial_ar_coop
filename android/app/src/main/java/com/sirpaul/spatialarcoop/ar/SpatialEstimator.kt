package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
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
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val ground = groundY?.let {
            intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it)
        }

        val view = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            detection.rawBottomCenter,
            Coordinates2d.VIEW,
            view
        )
        val bestHit = frame.hitTest(view[0], view[1])
            .filter { hit ->
                when (val trackable = hit.trackable) {
                    is DepthPoint -> true
                    is Plane -> trackable.trackingState == TrackingState.TRACKING && trackable.isPoseInPolygon(hit.hitPose)
                    is Point -> trackable.trackingState == TrackingState.TRACKING
                    else -> false
                }
            }
            .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))

        if (bestHit != null) {
            val site = PoseMath.transformPoint(siteFromWorld, bestHit.hitPose.translation)
            return when (bestHit.trackable) {
                is DepthPoint -> {
                    if (ground != null && usesGroundContact(detection.label)) {
                        if (PoseMath.distance(site, ground) <= 2.0f) {
                            EstimatedPosition(
                                sitePosition = floatArrayOf(
                                    site[0] * 0.60f + ground[0] * 0.40f,
                                    ground[1],
                                    site[2] * 0.60f + ground[2] * 0.40f
                                ),
                                uncertaintyMeters = 0.28f,
                                method = "depth+ground"
                            )
                        } else {
                            EstimatedPosition(ground, 0.52f, "ground-ray")
                        }
                    } else {
                        EstimatedPosition(site, 0.30f, "depth")
                    }
                }
                is Plane -> {
                    if (ground != null && usesGroundContact(detection.label)) {
                        if (PoseMath.distance(site, ground) <= 1.5f) {
                            EstimatedPosition(
                                sitePosition = floatArrayOf(
                                    site[0] * 0.35f + ground[0] * 0.65f,
                                    ground[1],
                                    site[2] * 0.35f + ground[2] * 0.65f
                                ),
                                uncertaintyMeters = 0.38f,
                                method = "plane+ground"
                            )
                        } else {
                            EstimatedPosition(ground, 0.55f, "ground-ray")
                        }
                    } else {
                        EstimatedPosition(site, 0.45f, "plane")
                    }
                }
                is Point -> {
                    // Feature points behind moving objects caused the field-test duplicate tracks.
                    if (usesGroundContact(detection.label)) {
                        ground?.let { EstimatedPosition(it, 0.60f, "ground-ray") }
                    } else {
                        EstimatedPosition(site, 0.75f, "feature-point")
                    }
                }
                else -> null
            }
        }

        // Keep the accurate local 2D box, but do not invent a networked 3D position from class size.
        return ground?.let { EstimatedPosition(it, 0.60f, "ground-ray") }
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

    private fun usesGroundContact(label: String): Boolean = when (label.lowercase()) {
        "person", "car", "bird", "dog", "cat" -> true
        else -> false
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
        if (!distance.isFinite() || distance !in 0.15f..60f) return null
        return floatArrayOf(
            siteOrigin[0] + siteDirection[0] * distance,
            groundY,
            siteOrigin[2] + siteDirection[2] * distance
        )
    }

    private fun priority(trackable: Any): Int = when (trackable) {
        is DepthPoint -> 0
        is Plane -> 1
        is Point -> 2
        else -> 3
    }
}
