package com.sirpaul.spatialarcoop.ar

import com.sirpaul.spatialarcoop.data.PoseJoint
import com.sirpaul.spatialarcoop.vision.CaptureGeometry
import com.sirpaul.spatialarcoop.vision.Detection2D
import com.sirpaul.spatialarcoop.vision.PoseLandmark2D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converts selected MediaPipe image landmarks into a compact shared-site skeleton.
 *
 * The absolute root remains the AR/Cloud-Anchor spatial estimate. When ARCore depth is available,
 * each body landmark is independently lifted into shared 3D from the captured depth snapshot. That
 * preserves arm/leg motion in depth instead of forcing the whole body onto one flat billboard.
 * Noisy or missing joint depth falls back to the vertical root plane used by earlier builds.
 */
object PoseSkeletonBuilder {
    fun build(
        detection: Detection2D,
        rootSite: FloatArray,
        geometry: CaptureGeometry
    ): List<PoseJoint> {
        if (!detection.label.equals("person", true)) return emptyList()
        if (rootSite.size < 3) return emptyList()
        val siteFromCamera = geometry.siteFromCamera ?: return emptyList()
        val camera = PoseMath.translationOf(siteFromCamera)
        val planeNormal = horizontalDirection(camera, rootSite)
        val planeNumerator = planeNormal?.let { normal ->
            dot(
                floatArrayOf(rootSite[0] - camera[0], rootSite[1] - camera[1], rootSite[2] - camera[2]),
                normal
            )
        }

        return detection.poseLandmarks
            .asSequence()
            .filter { it.index in SELECTED_JOINTS && it.confidence >= MIN_JOINT_CONFIDENCE }
            .mapNotNull { landmark ->
                depthJoint(landmark, rootSite, geometry, siteFromCamera)
                    ?: planeJoint(landmark, rootSite, geometry, siteFromCamera, camera, planeNormal, planeNumerator)
            }
            .distinctBy { it.index }
            .sortedBy { it.index }
            .toList()
            .takeIf { joints -> joints.size >= MIN_USEFUL_JOINTS }
            ?: emptyList()
    }

    private fun depthJoint(
        landmark: PoseLandmark2D,
        rootSite: FloatArray,
        geometry: CaptureGeometry,
        siteFromCamera: FloatArray
    ): PoseJoint? {
        val sample = geometry.depthSnapshot?.sample(floatArrayOf(landmark.x, landmark.y)) ?: return null
        if (!sample.opticalDepthMeters.isFinite() || sample.opticalDepthMeters !in MIN_RAY_METERS..MAX_RAY_METERS) return null
        if (sample.confidence < MIN_DEPTH_JOINT_CONFIDENCE) return null
        val rayCamera = cameraRay(landmark, geometry) ?: return null
        // DepthSnapshot reports optical depth along camera -Z. The pinhole ray therefore scales
        // directly by optical depth because cameraRay() is expressed with z = -1.
        val pointCamera = floatArrayOf(
            rayCamera[0] * sample.opticalDepthMeters,
            rayCamera[1] * sample.opticalDepthMeters,
            -sample.opticalDepthMeters
        )
        val point = PoseMath.transformPoint(siteFromCamera, pointCamera)
        if (point.size < 3 || point.any { !it.isFinite() }) return null
        stabilizeFoot(point, landmark.index, rootSite)
        val offset = offsetFromRoot(point, rootSite)
        if (!plausibleOffset(offset) || distance(point, rootSite) > MAX_JOINT_ROOT_DISTANCE_METERS) return null
        val confidence = (landmark.confidence * (0.68f + 0.32f * sample.confidence.coerceIn(0f, 1f))).coerceIn(0f, 1f)
        return PoseJoint(landmark.index, offset, confidence)
    }

    private fun planeJoint(
        landmark: PoseLandmark2D,
        rootSite: FloatArray,
        geometry: CaptureGeometry,
        siteFromCamera: FloatArray,
        camera: FloatArray,
        normal: FloatArray?,
        numerator: Float?
    ): PoseJoint? {
        normal ?: return null
        val numeratorValue = numerator ?: return null
        if (!numeratorValue.isFinite() || numeratorValue <= 0.10f) return null
        val rayCamera = cameraRay(landmark, geometry) ?: return null
        val raySite = PoseMath.normalize(PoseMath.transformDirection(siteFromCamera, rayCamera))
        val denominator = dot(raySite, normal)
        if (!denominator.isFinite() || abs(denominator) < MIN_PLANE_DENOMINATOR) return null
        val distance = numeratorValue / denominator
        if (!distance.isFinite() || distance !in MIN_RAY_METERS..MAX_RAY_METERS) return null
        val point = floatArrayOf(
            camera[0] + raySite[0] * distance,
            camera[1] + raySite[1] * distance,
            camera[2] + raySite[2] * distance
        )
        stabilizeFoot(point, landmark.index, rootSite)
        val offset = offsetFromRoot(point, rootSite)
        if (!plausibleOffset(offset)) return null
        return PoseJoint(landmark.index, offset, (landmark.confidence * PLANE_FALLBACK_CONFIDENCE_SCALE).coerceIn(0f, 1f))
    }

    private fun stabilizeFoot(point: FloatArray, jointIndex: Int, rootSite: FloatArray) {
        if (jointIndex in FOOT_JOINTS) {
            point[1] = point[1].coerceIn(rootSite[1], rootSite[1] + MAX_FOOT_HEIGHT_METERS)
        }
    }

    private fun offsetFromRoot(point: FloatArray, rootSite: FloatArray): FloatArray = floatArrayOf(
        point[0] - rootSite[0],
        point[1] - rootSite[1],
        point[2] - rootSite[2]
    )

    private fun cameraRay(landmark: PoseLandmark2D, geometry: CaptureGeometry): FloatArray? {
        val focal = geometry.focalLength
        val principal = geometry.principalPoint
        if (focal.size < 2 || principal.size < 2 || focal[0] <= 0f || focal[1] <= 0f) return null
        return floatArrayOf(
            (landmark.x - principal[0]) / focal[0],
            -(landmark.y - principal[1]) / focal[1],
            -1f
        )
    }

    private fun horizontalDirection(camera: FloatArray, root: FloatArray): FloatArray? {
        val x = root[0] - camera[0]
        val z = root[2] - camera[2]
        val length = sqrt(x * x + z * z)
        if (!length.isFinite() || length < 0.15f) return null
        return floatArrayOf(x / length, 0f, z / length)
    }

    private fun plausibleOffset(offset: FloatArray): Boolean {
        if (offset.size < 3 || offset.any { !it.isFinite() }) return false
        if (offset[1] !in MIN_VERTICAL_OFFSET..MAX_VERTICAL_OFFSET) return false
        val horizontal = sqrt(offset[0] * offset[0] + offset[2] * offset[2])
        return horizontal <= MAX_HORIZONTAL_OFFSET
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } +
            a.getOrElse(1) { 0f } * b.getOrElse(1) { 0f } +
            a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    /** MediaPipe indices used by the shared stick figure. */
    val SELECTED_JOINTS: Set<Int> = linkedSetOf(
        0,
        11, 12,
        13, 14,
        15, 16,
        23, 24,
        25, 26,
        27, 28,
        31, 32
    )

    private val FOOT_JOINTS = setOf(27, 28, 31, 32)
    private const val MIN_JOINT_CONFIDENCE = 0.24f
    private const val MIN_DEPTH_JOINT_CONFIDENCE = 0.24f
    private const val MIN_USEFUL_JOINTS = 6
    private const val MIN_PLANE_DENOMINATOR = 0.06f
    private const val MIN_RAY_METERS = 0.25f
    private const val MAX_RAY_METERS = 45f
    private const val MAX_JOINT_ROOT_DISTANCE_METERS = 2.35f
    private const val MAX_FOOT_HEIGHT_METERS = 0.28f
    private const val MIN_VERTICAL_OFFSET = -0.12f
    private const val MAX_VERTICAL_OFFSET = 2.65f
    private const val MAX_HORIZONTAL_OFFSET = 1.75f
    private const val PLANE_FALLBACK_CONFIDENCE_SCALE = 0.90f
}
