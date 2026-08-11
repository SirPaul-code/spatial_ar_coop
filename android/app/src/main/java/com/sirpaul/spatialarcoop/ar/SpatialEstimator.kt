package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.vision.CaptureGeometry
import com.sirpaul.spatialarcoop.vision.Detection2D
import com.sirpaul.spatialarcoop.vision.SpatialAssociationHints


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
        // The local UI may show a first-frame candidate immediately, but a networked 3D object is
        // only allowed after the temporal detector has seen a consistent image-space identity.
        if (!detection.temporallyConfirmed) return null
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val geometry = detection.captureGeometry ?: currentGeometry(frame)

        // Every class currently shared by this app represents a ground/contact target in the field
        // workflow. Prefer the ray through the detector's feet/wheels at the detector capture pose.
        // Depth hits on a moving object/background are useful evidence, but they are not a stable
        // definition of the object's shared site position and were the main source of 3D jumps.
        if (groundY != null && detection.label in GROUND_CONTACT_LABELS) {
            val ground = intersectGround(detection.rawBottomCenter, geometry, siteFromWorld, groundY)
            if (ground != null) {
                if (!hasPlausibleApparentScale(detection, ground, worldFromSite, geometry)) return null
                return EstimatedPosition(
                    sitePosition = SpatialAssociationHints.attach(ground, detection.temporalId),
                    uncertaintyMeters = if (detection.captureGeometry != null) 0.28f else 0.40f,
                    method = if (detection.captureGeometry != null) "ground-capture" else "ground-current"
                )
            }
        }

        // Fallback for maps that do not yet have a saved floor. Prefer a real upward-facing plane
        // over a DepthPoint because the shared position is the contact point, not an arbitrary
        // surface point on the target or background.
        val image = detection.rawBottomCenter
        val view = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, image, Coordinates2d.VIEW, view)
        val bestHit = frame.hitTest(view[0], view[1])
            .filter { hit ->
                when (val trackable = hit.trackable) {
                    is Plane -> trackable.trackingState == TrackingState.TRACKING &&
                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        trackable.isPoseInPolygon(hit.hitPose)
                    is DepthPoint -> true
                    else -> false
                }
            }
            .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))
            ?: return null

        val site = PoseMath.transformPoint(siteFromWorld, bestHit.hitPose.translation)
        if (!hasPlausibleApparentScale(detection, site, worldFromSite, geometry)) return null
        val (uncertainty, method) = when (bestHit.trackable) {
            is Plane -> 0.42f to "plane-contact"
            is DepthPoint -> 0.68f to "depth-fallback"
            else -> 0.80f to "hit-fallback"
        }
        return EstimatedPosition(
            SpatialAssociationHints.attach(site, detection.temporalId),
            uncertainty,
            method
        )
    }

    fun centerGroundPoint(frame: Frame, worldFromSite: FloatArray, groundY: Float?): FloatArray? {
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        return if (groundY != null) {
            intersectGround(
                floatArrayOf(dimensions[0] * 0.5f, dimensions[1] * 0.58f),
                currentGeometry(frame),
                PoseMath.rigidInverse(worldFromSite),
                groundY
            )
        } else {
            val view = floatArrayOf(0.5f * dimensions[0], 0.58f * dimensions[1])
            val output = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, view, Coordinates2d.VIEW, output)
            frame.hitTest(output[0], output[1])
                .firstOrNull { hit ->
                    val plane = hit.trackable as? Plane
                    plane != null && plane.trackingState == TrackingState.TRACKING &&
                        plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(hit.hitPose)
                }
                ?.hitPose
                ?.translation
                ?.let { PoseMath.transformPoint(PoseMath.rigidInverse(worldFromSite), it) }
        }
    }

    private fun currentGeometry(frame: Frame): CaptureGeometry {
        val intrinsics = frame.camera.imageIntrinsics
        return CaptureGeometry(
            worldFromCamera = PoseMath.poseToMatrix(frame.camera.pose),
            focalLength = intrinsics.focalLength.copyOf(),
            principalPoint = intrinsics.principalPoint.copyOf()
        )
    }

    private fun intersectGround(
        imagePixel: FloatArray,
        geometry: CaptureGeometry,
        siteFromWorld: FloatArray,
        groundY: Float
    ): FloatArray? {
        val focal = geometry.focalLength
        val principal = geometry.principalPoint
        if (focal.size < 2 || principal.size < 2 || focal[0] <= 0f || focal[1] <= 0f) return null
        val cameraDirection = PoseMath.normalize(
            floatArrayOf(
                (imagePixel[0] - principal[0]) / focal[0],
                -(imagePixel[1] - principal[1]) / focal[1],
                -1f
            )
        )
        val worldDirection = PoseMath.transformDirection(geometry.worldFromCamera, cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        // Near-horizon rays amplify a few pixels of detector noise into many meters of position
        // error. Refuse them instead of publishing a wildly unstable shared track.
        if (siteDirection[1] > -MIN_DOWNWARD_RAY_COMPONENT) return null
        val worldOrigin = PoseMath.translationOf(geometry.worldFromCamera)
        val siteOrigin = PoseMath.transformPoint(siteFromWorld, worldOrigin)
        val distance = (groundY - siteOrigin[1]) / siteDirection[1]
        if (!distance.isFinite() || distance !in MIN_GROUND_RANGE_METERS..MAX_GROUND_RANGE_METERS) return null
        return floatArrayOf(
            siteOrigin[0] + siteDirection[0] * distance,
            groundY,
            siteOrigin[2] + siteDirection[2] * distance
        )
    }

    private fun hasPlausibleApparentScale(
        detection: Detection2D,
        sitePosition: FloatArray,
        worldFromSite: FloatArray,
        geometry: CaptureGeometry
    ): Boolean {
        val focalY = geometry.focalLength.getOrNull(1) ?: return true
        if (focalY <= 0f) return true
        val box = detection.rawBoundingBox
        if (box.height() < 3f) return false

        // A clipped box is not a reliable full-height measurement, so do not reject it on scale.
        val margin = 3f
        val clipped = box.top <= margin || box.bottom >= detection.rawImageHeight - margin
        if (clipped) return true

        val worldPoint = PoseMath.transformPoint(worldFromSite, sitePosition)
        val cameraFromWorld = PoseMath.rigidInverse(geometry.worldFromCamera)
        val cameraPoint = PoseMath.transformPoint(cameraFromWorld, worldPoint)
        val opticalDepth = -cameraPoint[2]
        if (!opticalDepth.isFinite() || opticalDepth !in 0.20f..MAX_GROUND_RANGE_METERS) return false

        val apparentHeightMeters = box.height() * opticalDepth / focalY
        val range = plausibleHeightRange(detection.label)
        return apparentHeightMeters.isFinite() && apparentHeightMeters in range
    }

    private fun plausibleHeightRange(label: String): ClosedFloatingPointRange<Float> = when (label) {
        "person" -> 0.55f..2.80f
        "car" -> 0.45f..3.20f
        // COCO bird includes chickens. Keep this deliberately broad for crouching/occluded birds.
        "bird" -> 0.06f..1.25f
        "dog" -> 0.12f..1.70f
        "cat" -> 0.06f..1.05f
        else -> 0.05f..3.50f
    }

    private fun priority(trackable: Any): Int = when (trackable) {
        is Plane -> 0
        is DepthPoint -> 1
        else -> 2
    }

    private val GROUND_CONTACT_LABELS = setOf("person", "car", "bird", "dog", "cat")
    private const val MIN_DOWNWARD_RAY_COMPONENT = 0.035f
    private const val MIN_GROUND_RANGE_METERS = 0.20f
    private const val MAX_GROUND_RANGE_METERS = 45f
}
