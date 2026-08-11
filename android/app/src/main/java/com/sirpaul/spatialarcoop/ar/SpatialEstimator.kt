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

        // The shared track position represents the object's contact point (feet/wheels). When map
        // setup has a saved ground plane, the bbox bottom-center ray is therefore the most stable
        // semantic measurement. A depth hit inside a moving silhouette can otherwise belong to
        // background geometry behind the person/car and create a plausible but wildly distant
        // 3D point, which fragments one real object into many tracker IDs.
        val groundContact = groundY?.let {
            intersectGround(frame, detection.rawBottomCenter, siteFromWorld, it)
        }
        if (groundContact != null) {
            return EstimatedPosition(groundContact, 0.38f, "ground-ray-primary")
        }

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
                is DepthPoint -> 0.28f to "depth"
                is Plane -> 0.40f to "horizontal-plane"
                else -> 0.75f to "hit"
            }
            return EstimatedPosition(site, uncertainty, method)
        }

        // Last-resort monocular estimate. It is intentionally marked with much larger uncertainty
        // than Depth/plane/ground hits, but keeps obvious people/cars/birds shareable when ARCore
        // has no hit at the moving object's contact point.
        return estimateFromBoundingBoxSize(frame, detection, siteFromWorld)
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

    private fun estimateFromBoundingBoxSize(
        frame: Frame,
        detection: Detection2D,
        siteFromWorld: FloatArray
    ): EstimatedPosition? {
        val physicalHeightMeters = when (detection.label.lowercase()) {
            "person" -> 1.70f
            "car" -> 1.50f
            "bird" -> 0.40f
            "dog" -> 0.65f
            "cat" -> 0.38f
            else -> 0.60f
        }
        val pixelExtent = detection.rawBoundingBox.height()
        if (!pixelExtent.isFinite() || pixelExtent < 6f) return null

        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        if (focal[0] <= 0f || focal[1] <= 0f) return null
        val focalPixels = (focal[0] + focal[1]) * 0.5f
        val opticalDepth = (focalPixels * physicalHeightMeters / pixelExtent).coerceIn(0.45f, 55f)
        if (!opticalDepth.isFinite()) return null

        // Position represents the object contact point used by the shared tracker, so project
        // the bbox bottom-center rather than its visual center.
        val centerX = detection.rawBottomCenter[0]
        val centerY = detection.rawBottomCenter[1]
        val cameraDirection = PoseMath.normalize(
            floatArrayOf(
                (centerX - principal[0]) / focal[0],
                -(centerY - principal[1]) / focal[1],
                -1f
            )
        )
        val worldFromCamera = PoseMath.poseToMatrix(frame.camera.pose)
        val worldDirection = PoseMath.transformDirection(worldFromCamera, cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        val siteOrigin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val rayLength = (opticalDepth / abs(cameraDirection[2]).coerceAtLeast(0.18f)).coerceIn(0.45f, 70f)
        val site = floatArrayOf(
            siteOrigin[0] + siteDirection[0] * rayLength,
            siteOrigin[1] + siteDirection[1] * rayLength,
            siteOrigin[2] + siteDirection[2] * rayLength
        )
        val uncertainty = (0.75f + opticalDepth * 0.28f).coerceIn(0.9f, 10f)
        return EstimatedPosition(site, uncertainty, "monocular-class-size")
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
