package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.vision.Detection2D
import kotlin.math.abs
import kotlin.math.max

data class EstimatedPosition(val sitePosition: FloatArray, val uncertaintyMeters: Float, val method: String)

object SpatialEstimator {
    fun estimate(frame: Frame, detection: Detection2D, worldFromSite: FloatArray, groundY: Float?): EstimatedPosition? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val siteOrigin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val monocular = estimateFromBoundingBoxSize(frame, detection, siteFromWorld, groundY)
        val view = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, detection.rawBottomCenter, Coordinates2d.VIEW, view)
        val bestHit = frame.hitTest(view[0], view[1])
            .filter { hit -> when (val t = hit.trackable) {
                is DepthPoint -> true
                is Plane -> t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hit.hitPose)
                is Point -> t.trackingState == TrackingState.TRACKING
                else -> false
            }}
            .minWithOrNull(compareBy({ priority(it.trackable) }, { it.distance }))
        val hit = bestHit?.let { result ->
            val site = PoseMath.transformPoint(siteFromWorld, result.hitPose.translation)
            val (u, method) = when (result.trackable) {
                is DepthPoint -> 0.28f to "depth"
                is Plane -> 0.38f to "plane"
                else -> 0.75f to "feature-point"
            }
            EstimatedPosition(site, u, method)
        }
        val ground = groundY?.let { y -> intersectGround(frame, detection.rawBottomCenter, siteFromWorld, y)?.let { EstimatedPosition(it, 0.55f, "ground-ray") } }
        if (ground != null && plausibleRange(siteOrigin, ground, monocular)) {
            if (hit != null && plausibleRange(siteOrigin, hit, monocular) && PoseMath.distance(hit.sitePosition, ground.sitePosition) < 1.6f) {
                return EstimatedPosition(
                    floatArrayOf(hit.sitePosition[0] * 0.35f + ground.sitePosition[0] * 0.65f, ground.sitePosition[1], hit.sitePosition[2] * 0.35f + ground.sitePosition[2] * 0.65f),
                    max(hit.uncertaintyMeters, ground.uncertaintyMeters), "${hit.method}+ground"
                )
            }
            return ground
        }
        if (hit != null && plausibleRange(siteOrigin, hit, monocular)) {
            val position = if (groundY != null) floatArrayOf(hit.sitePosition[0], groundY, hit.sitePosition[2]) else hit.sitePosition
            return EstimatedPosition(position, hit.uncertaintyMeters, hit.method)
        }
        if (monocular != null) return monocular
        if (ground != null && PoseMath.distance(siteOrigin, ground.sitePosition) <= MAX_UNCHECKED_GROUND_RANGE_METERS) {
            return ground.copy(uncertaintyMeters = 2.5f, method = "ground-ray-unchecked")
        }
        return null
    }

    fun centerGroundPoint(frame: Frame, worldFromSite: FloatArray, groundY: Float?): FloatArray? {
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        return if (groundY != null) {
            intersectGround(frame, floatArrayOf(dimensions[0] * 0.5f, dimensions[1] * 0.58f), PoseMath.rigidInverse(worldFromSite), groundY)
        } else {
            val view = floatArrayOf(0.5f * dimensions[0], 0.58f * dimensions[1])
            val output = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, view, Coordinates2d.VIEW, output)
            frame.hitTest(output[0], output[1]).firstOrNull()?.hitPose?.translation?.let { PoseMath.transformPoint(PoseMath.rigidInverse(worldFromSite), it) }
        }
    }

    private fun plausibleRange(origin: FloatArray, candidate: EstimatedPosition, reference: EstimatedPosition?): Boolean {
        val candidateRange = PoseMath.distance(origin, candidate.sitePosition)
        if (!candidateRange.isFinite() || candidateRange !in 0.25f..MAX_TARGET_RANGE_METERS) return false
        if (reference == null) return candidateRange <= MAX_UNCHECKED_HIT_RANGE_METERS
        val referenceRange = PoseMath.distance(origin, reference.sitePosition)
        if (!referenceRange.isFinite() || referenceRange <= 0.2f) return true
        return abs(candidateRange - referenceRange) <= max(1.8f, referenceRange * 0.60f)
    }

    private fun estimateFromBoundingBoxSize(frame: Frame, detection: Detection2D, siteFromWorld: FloatArray, groundY: Float?): EstimatedPosition? {
        val physicalExtent = when (detection.label.lowercase()) {
            "person" -> 1.70f; "car" -> 1.82f; "bird" -> 0.42f; "dog" -> 0.70f; "cat" -> 0.42f; else -> 0.65f
        }
        val pixelExtent = maxOf(detection.rawBoundingBox.width(), detection.rawBoundingBox.height())
        if (!pixelExtent.isFinite() || pixelExtent < 8f) return null
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength; val principal = intrinsics.principalPoint
        if (focal[0] <= 0f || focal[1] <= 0f) return null
        val opticalDepth = (((focal[0] + focal[1]) * 0.5f) * physicalExtent / pixelExtent).coerceIn(0.45f, MAX_TARGET_RANGE_METERS)
        val p = detection.rawBottomCenter
        val cameraDirection = PoseMath.normalize(floatArrayOf((p[0] - principal[0]) / focal[0], -(p[1] - principal[1]) / focal[1], -1f))
        val worldDirection = PoseMath.transformDirection(PoseMath.poseToMatrix(frame.camera.pose), cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        val origin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val rayLength = (opticalDepth / abs(cameraDirection[2]).coerceAtLeast(0.20f)).coerceIn(0.45f, MAX_TARGET_RANGE_METERS)
        val position = floatArrayOf(origin[0] + siteDirection[0] * rayLength, origin[1] + siteDirection[1] * rayLength, origin[2] + siteDirection[2] * rayLength)
        if (groundY != null) position[1] = groundY
        return EstimatedPosition(position, (0.65f + opticalDepth * 0.18f).coerceIn(0.8f, 6f), "monocular-class-size")
    }

    private fun intersectGround(frame: Frame, imagePixel: FloatArray, siteFromWorld: FloatArray, groundY: Float): FloatArray? {
        val intrinsics = frame.camera.imageIntrinsics; val focal = intrinsics.focalLength; val principal = intrinsics.principalPoint
        if (focal[0] <= 0f || focal[1] <= 0f) return null
        val cameraDirection = PoseMath.normalize(floatArrayOf((imagePixel[0] - principal[0]) / focal[0], -(imagePixel[1] - principal[1]) / focal[1], -1f))
        val worldDirection = PoseMath.transformDirection(PoseMath.poseToMatrix(frame.camera.pose), cameraDirection)
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromWorld, worldDirection))
        if (abs(siteDirection[1]) < 0.02f) return null
        val origin = PoseMath.transformPoint(siteFromWorld, frame.camera.pose.translation)
        val distance = (groundY - origin[1]) / siteDirection[1]
        if (!distance.isFinite() || distance !in 0.15f..MAX_TARGET_RANGE_METERS) return null
        return floatArrayOf(origin[0] + siteDirection[0] * distance, groundY, origin[2] + siteDirection[2] * distance)
    }

    private fun priority(trackable: Any): Int = when (trackable) { is DepthPoint -> 0; is Plane -> 1; is Point -> 2; else -> 3 }
    private const val MAX_TARGET_RANGE_METERS = 35f
    private const val MAX_UNCHECKED_HIT_RANGE_METERS = 18f
    private const val MAX_UNCHECKED_GROUND_RANGE_METERS = 18f
}
