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
 * Pose Landmarker image points are used for articulation, while the absolute root remains the
 * application's AR/Cloud-Anchor spatial estimate. Every image ray is intersected with a vertical
 * plane through the person root that faces the source camera. This keeps the skeleton in the same
 * site frame as the rest of cooperative AR without treating MediaPipe's model coordinate system as
 * an absolute AR coordinate system.
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
        val normal = horizontalDirection(camera, rootSite) ?: return emptyList()
        val numerator = dot(
            floatArrayOf(rootSite[0] - camera[0], rootSite[1] - camera[1], rootSite[2] - camera[2]),
            normal
        )
        if (!numerator.isFinite() || numerator <= 0.10f) return emptyList()

        return detection.poseLandmarks
            .asSequence()
            .filter { it.index in SELECTED_JOINTS && it.confidence >= MIN_JOINT_CONFIDENCE }
            .mapNotNull { landmark ->
                val rayCamera = cameraRay(landmark, geometry) ?: return@mapNotNull null
                val raySite = PoseMath.normalize(PoseMath.transformDirection(siteFromCamera, rayCamera))
                val denominator = dot(raySite, normal)
                if (!denominator.isFinite() || abs(denominator) < MIN_PLANE_DENOMINATOR) return@mapNotNull null
                val distance = numerator / denominator
                if (!distance.isFinite() || distance !in MIN_RAY_METERS..MAX_RAY_METERS) return@mapNotNull null
                val point = floatArrayOf(
                    camera[0] + raySite[0] * distance,
                    camera[1] + raySite[1] * distance,
                    camera[2] + raySite[2] * distance
                )
                if (landmark.index in FOOT_JOINTS) {
                    // The shared root is the ground-contact point. Keep feet near that ground level
                    // even when 2D landmark noise briefly puts an ankle below the estimated floor.
                    point[1] = point[1].coerceIn(rootSite[1], rootSite[1] + MAX_FOOT_HEIGHT_METERS)
                }
                val offset = floatArrayOf(
                    point[0] - rootSite[0],
                    point[1] - rootSite[1],
                    point[2] - rootSite[2]
                )
                if (!plausibleOffset(offset)) return@mapNotNull null
                PoseJoint(landmark.index, offset, landmark.confidence.coerceIn(0f, 1f))
            }
            .distinctBy { it.index }
            .sortedBy { it.index }
            .toList()
            .takeIf { joints -> joints.size >= MIN_USEFUL_JOINTS }
            ?: emptyList()
    }

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

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } +
            a.getOrElse(1) { 0f } * b.getOrElse(1) { 0f } +
            a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    /** MediaPipe indices used by the shared stick figure. */
    val SELECTED_JOINTS: Set<Int> = linkedSetOf(
        0,          // nose / head reference
        11, 12,     // shoulders
        13, 14,     // elbows
        15, 16,     // wrists
        23, 24,     // hips
        25, 26,     // knees
        27, 28,     // ankles
        31, 32      // foot index
    )

    private val FOOT_JOINTS = setOf(27, 28, 31, 32)
    private const val MIN_JOINT_CONFIDENCE = 0.34f
    private const val MIN_USEFUL_JOINTS = 8
    private const val MIN_PLANE_DENOMINATOR = 0.06f
    private const val MIN_RAY_METERS = 0.25f
    private const val MAX_RAY_METERS = 45f
    private const val MAX_FOOT_HEIGHT_METERS = 0.28f
    private const val MIN_VERTICAL_OFFSET = -0.12f
    private const val MAX_VERTICAL_OFFSET = 2.55f
    private const val MAX_HORIZONTAL_OFFSET = 1.55f
}
