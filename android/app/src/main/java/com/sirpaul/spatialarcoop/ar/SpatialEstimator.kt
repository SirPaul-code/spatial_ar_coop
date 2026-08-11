package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.data.defaultTrackExtent
import com.sirpaul.spatialarcoop.vision.CaptureGeometry
import com.sirpaul.spatialarcoop.vision.Detection2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2


data class EstimatedPosition(
    val sitePosition: FloatArray,
    val uncertaintyMeters: Float,
    val method: String,
    val extentMeters: FloatArray,
    val yawRadians: Float,
    /** Noisy monocular/depth fallbacks must be stable for more frames before network publication. */
    val requiredHits: Int = 2
)

data class SpatialEstimateAttempt(
    val estimate: EstimatedPosition?,
    val rejectionReason: String? = null
)

object SpatialEstimator {
    fun estimate(
        frame: Frame,
        detection: Detection2D,
        worldFromSite: FloatArray,
        groundY: Float?
    ): EstimatedPosition? = estimateDetailed(frame, detection, worldFromSite, groundY).estimate

    fun estimateDetailed(
        frame: Frame,
        detection: Detection2D,
        worldFromSite: FloatArray,
        groundY: Float?
    ): SpatialEstimateAttempt {
        if (frame.camera.trackingState != TrackingState.TRACKING) return rejected("camera-not-tracking")
        if (!detection.temporallyConfirmed) return rejected("temporal-not-confirmed")
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val geometry = detection.captureGeometry?.let { captured ->
            if (captured.siteFromCamera == null) return rejected("capture-before-localization")
            captured
        } ?: currentGeometry(frame, worldFromSite)

        if (groundY != null && detection.label in GROUND_CONTACT_LABELS) {
            val ground = intersectGround(detection.rawBottomCenter, geometry, groundY)
            if (ground != null && hasPlausibleApparentScale(detection, ground, geometry)) {
                return accepted(
                    detection = detection,
                    position = ground,
                    geometry = geometry,
                    uncertainty = if (detection.captureGeometry != null) 0.22f else 0.36f,
                    method = if (detection.captureGeometry != null) "ground-capture-site" else "ground-current",
                    requiredHits = 2
                )
            }
        }

        // Maps without a usable saved floor may still have a real horizontal plane/depth sample.
        val image = detection.rawBottomCenter
        val view = FloatArray(2)
        val bestHit = runCatching {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, image, Coordinates2d.VIEW, view)
            frame.hitTest(view[0], view[1])
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
        }.getOrNull()

        if (bestHit != null) {
            val site = PoseMath.transformPoint(siteFromWorld, bestHit.hitPose.translation)
            if (hasPlausibleApparentScale(detection, site, geometry)) {
                val (uncertainty, method, requiredHits) = when (bestHit.trackable) {
                    is Plane -> Triple(0.44f, "plane-current-fallback", 2)
                    is DepthPoint -> Triple(0.72f, "depth-current-fallback", 3)
                    else -> Triple(0.90f, "hit-current-fallback", 3)
                }
                return accepted(detection, site, geometry, uncertainty, method, requiredHits)
            }
        }

        // Last resort for a *strong, temporally confirmed* detector track: estimate range from class
        // height at the exact capture pose. It has intentionally high uncertainty and requires four
        // consistent 3D observations before publication, so it fills real "bbox but no track" gaps
        // without letting a single classifier mistake become a shared ghost.
        if (detection.confidence >= monocularMinimumConfidence(detection.label)) {
            monocularContact(detection, geometry, groundY)?.let { position ->
                return accepted(
                    detection = detection,
                    position = position,
                    geometry = geometry,
                    uncertainty = 1.10f,
                    method = "monocular-capture-fallback",
                    requiredHits = 4
                )
            }
        }

        return rejected("no-stable-3d-solution")
    }

    fun centerGroundPoint(frame: Frame, worldFromSite: FloatArray, groundY: Float?): FloatArray? {
        val dimensions = frame.camera.imageIntrinsics.imageDimensions
        return if (groundY != null) {
            intersectGround(
                floatArrayOf(dimensions[0] * 0.5f, dimensions[1] * 0.58f),
                currentGeometry(frame, worldFromSite),
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

    private fun accepted(
        detection: Detection2D,
        position: FloatArray,
        geometry: CaptureGeometry,
        uncertainty: Float,
        method: String,
        requiredHits: Int
    ): SpatialEstimateAttempt {
        val extent = estimateExtent(detection, position, geometry)
        val yaw = estimateYaw(detection, position, geometry)
        return SpatialEstimateAttempt(
            EstimatedPosition(position, uncertainty, method, extent, yaw, requiredHits),
            null
        )
    }

    private fun rejected(reason: String) = SpatialEstimateAttempt(null, reason)

    private fun currentGeometry(frame: Frame, worldFromSite: FloatArray): CaptureGeometry {
        val intrinsics = frame.camera.imageIntrinsics
        val siteFromCamera = PoseMath.multiply(
            PoseMath.rigidInverse(worldFromSite),
            PoseMath.poseToMatrix(frame.camera.pose)
        )
        return CaptureGeometry(
            siteFromCamera = siteFromCamera,
            focalLength = intrinsics.focalLength.copyOf(),
            principalPoint = intrinsics.principalPoint.copyOf()
        )
    }

    private fun intersectGround(
        imagePixel: FloatArray,
        geometry: CaptureGeometry,
        groundY: Float
    ): FloatArray? {
        val siteFromCamera = geometry.siteFromCamera ?: return null
        val cameraDirection = cameraRay(imagePixel, geometry) ?: return null
        val siteDirection = PoseMath.normalize(PoseMath.transformDirection(siteFromCamera, cameraDirection))
        if (siteDirection[1] > -MIN_DOWNWARD_RAY_COMPONENT) return null
        val siteOrigin = PoseMath.translationOf(siteFromCamera)
        val distance = (groundY - siteOrigin[1]) / siteDirection[1]
        if (!distance.isFinite() || distance !in MIN_GROUND_RANGE_METERS..MAX_GROUND_RANGE_METERS) return null
        return floatArrayOf(
            siteOrigin[0] + siteDirection[0] * distance,
            groundY,
            siteOrigin[2] + siteDirection[2] * distance
        )
    }

    private fun monocularContact(
        detection: Detection2D,
        geometry: CaptureGeometry,
        groundY: Float?
    ): FloatArray? {
        val siteFromCamera = geometry.siteFromCamera ?: return null
        val focalY = geometry.focalLength.getOrNull(1) ?: return null
        if (focalY <= 0f || detection.rawBoundingBox.height() < 4f) return null
        val nominalHeight = nominalHeight(detection.label)
        val opticalDepth = focalY * nominalHeight / detection.rawBoundingBox.height()
        if (!opticalDepth.isFinite() || opticalDepth !in MIN_GROUND_RANGE_METERS..monocularMaxRange(detection.label)) return null
        val ray = cameraRay(detection.rawBottomCenter, geometry) ?: return null
        if (ray[2] >= -0.05f) return null
        val scale = opticalDepth / -ray[2]
        val cameraPoint = floatArrayOf(ray[0] * scale, ray[1] * scale, -opticalDepth)
        val sitePoint = PoseMath.transformPoint(siteFromCamera, cameraPoint)
        if (groundY != null) sitePoint[1] = groundY
        return sitePoint
    }

    private fun cameraRay(imagePixel: FloatArray, geometry: CaptureGeometry): FloatArray? {
        val focal = geometry.focalLength
        val principal = geometry.principalPoint
        if (focal.size < 2 || principal.size < 2 || focal[0] <= 0f || focal[1] <= 0f) return null
        return floatArrayOf(
            (imagePixel[0] - principal[0]) / focal[0],
            -(imagePixel[1] - principal[1]) / focal[1],
            -1f
        )
    }

    private fun hasPlausibleApparentScale(
        detection: Detection2D,
        sitePosition: FloatArray,
        geometry: CaptureGeometry
    ): Boolean {
        val siteFromCamera = geometry.siteFromCamera ?: return false
        val focalY = geometry.focalLength.getOrNull(1) ?: return true
        if (focalY <= 0f) return true
        val box = detection.rawBoundingBox
        if (box.height() < 3f) return false

        val margin = 3f
        val clipped = box.top <= margin || box.bottom >= detection.rawImageHeight - margin
        if (clipped) return true

        val cameraFromSite = PoseMath.rigidInverse(siteFromCamera)
        val cameraPoint = PoseMath.transformPoint(cameraFromSite, sitePosition)
        val opticalDepth = -cameraPoint[2]
        if (!opticalDepth.isFinite() || opticalDepth !in 0.20f..MAX_GROUND_RANGE_METERS) return false

        val apparentHeightMeters = box.height() * opticalDepth / focalY
        return apparentHeightMeters.isFinite() && apparentHeightMeters in plausibleHeightRange(detection.label)
    }

    private fun estimateExtent(
        detection: Detection2D,
        sitePosition: FloatArray,
        geometry: CaptureGeometry
    ): FloatArray {
        val prior = defaultTrackExtent(detection.label)
        val siteFromCamera = geometry.siteFromCamera ?: return prior
        val focalX = geometry.focalLength.getOrNull(0) ?: return prior
        val focalY = geometry.focalLength.getOrNull(1) ?: return prior
        if (focalX <= 0f || focalY <= 0f) return prior
        val cameraPoint = PoseMath.transformPoint(PoseMath.rigidInverse(siteFromCamera), sitePosition)
        val depth = -cameraPoint[2]
        if (!depth.isFinite() || depth <= 0.2f) return prior
        val measuredWidth = detection.rawBoundingBox.width() * depth / focalX
        val measuredHeight = detection.rawBoundingBox.height() * depth / focalY
        if (!measuredWidth.isFinite() || !measuredHeight.isFinite()) return prior
        return when (detection.label.lowercase()) {
            "person" -> floatArrayOf(
                blend(prior[0], measuredWidth.coerceIn(0.35f, 1.10f), 0.35f),
                blend(prior[1], measuredHeight.coerceIn(1.20f, 2.30f), 0.45f),
                prior[2]
            )
            "car" -> {
                val sideView = detection.rawBoundingBox.width() / detection.rawBoundingBox.height().coerceAtLeast(1f) >= CAR_SIDE_ASPECT
                floatArrayOf(
                    if (sideView) prior[0] else blend(prior[0], measuredWidth.coerceIn(1.35f, 2.60f), 0.35f),
                    blend(prior[1], measuredHeight.coerceIn(1.00f, 2.20f), 0.35f),
                    if (sideView) blend(prior[2], measuredWidth.coerceIn(2.8f, 5.8f), 0.30f) else prior[2]
                )
            }
            "bird" -> floatArrayOf(
                blend(prior[0], measuredWidth.coerceIn(0.15f, 1.05f), 0.35f),
                blend(prior[1], measuredHeight.coerceIn(0.15f, 0.85f), 0.35f),
                prior[2]
            )
            else -> prior
        }
    }

    private fun estimateYaw(detection: Detection2D, sitePosition: FloatArray, geometry: CaptureGeometry): Float {
        val siteFromCamera = geometry.siteFromCamera ?: return 0f
        val camera = PoseMath.translationOf(siteFromCamera)
        val bearing = atan2(sitePosition[0] - camera[0], sitePosition[2] - camera[2])
        val sideView = detection.label.equals("car", true) &&
            detection.rawBoundingBox.width() / detection.rawBoundingBox.height().coerceAtLeast(1f) >= CAR_SIDE_ASPECT
        val desiredDepthAxisBearing = if (sideView) bearing + (PI.toFloat() / 2f) else bearing
        // The cuboid renderer rotates local [x,z] as x'=x*cos-z*sin, z'=x*sin+z*cos.
        // Therefore the resulting +Z axis has site bearing -yaw.
        return normalizeAngle(-desiredDepthAxisBearing)
    }

    private fun normalizeAngle(value: Float): Float {
        var result = value
        val pi = PI.toFloat()
        while (result > pi) result -= 2f * pi
        while (result < -pi) result += 2f * pi
        return result
    }

    private fun blend(prior: Float, measured: Float, measuredWeight: Float): Float =
        prior * (1f - measuredWeight) + measured * measuredWeight

    private fun nominalHeight(label: String): Float = when (label.lowercase()) {
        "person" -> 1.72f
        "car" -> 1.50f
        "bird" -> 0.45f
        "dog" -> 0.70f
        "cat" -> 0.42f
        else -> 0.65f
    }

    private fun monocularMinimumConfidence(label: String): Float = when (label.lowercase()) {
        "person" -> 0.72f
        "car" -> 0.70f
        "bird" -> 0.35f
        else -> 0.65f
    }

    private fun monocularMaxRange(label: String): Float = when (label.lowercase()) {
        "bird" -> 16f
        "person" -> 28f
        "car" -> 40f
        else -> 25f
    }

    private fun plausibleHeightRange(label: String): ClosedFloatingPointRange<Float> = when (label) {
        "person" -> 0.70f..2.60f
        "car" -> 0.65f..2.50f
        "bird" -> 0.10f..0.95f
        "dog" -> 0.15f..1.45f
        "cat" -> 0.08f..0.90f
        else -> 0.08f..3.20f
    }

    private fun priority(trackable: Any): Int = when (trackable) {
        is Plane -> 0
        is DepthPoint -> 1
        else -> 2
    }

    private val GROUND_CONTACT_LABELS = setOf("person", "car", "bird", "dog", "cat")
    private const val CAR_SIDE_ASPECT = 1.55f
    private const val MIN_DOWNWARD_RAY_COMPONENT = 0.035f
    private const val MIN_GROUND_RANGE_METERS = 0.20f
    private const val MAX_GROUND_RANGE_METERS = 45f
}
