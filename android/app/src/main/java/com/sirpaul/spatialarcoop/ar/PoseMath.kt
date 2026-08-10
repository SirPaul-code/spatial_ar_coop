package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Pose
import kotlin.math.sqrt

/** Column-major rigid-transform utilities matching ARCore/OpenGL conventions. */
object PoseMath {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun translation(x: Float, y: Float, z: Float): FloatArray = identity().also {
        it[12] = x
        it[13] = y
        it[14] = z
    }

    fun poseToMatrix(pose: Pose): FloatArray = FloatArray(16).also { pose.toMatrix(it, 0) }

    /**
     * Builds a gravity-aligned site frame at the camera position. Site -Z points in the camera's
     * horizontal look direction, +X points right, and +Y points up. This is the manual-alignment
     * fallback used when Cloud Anchors are unavailable.
     */
    fun horizontalOrigin(cameraPose: Pose): FloatArray = horizontalOrigin(poseToMatrix(cameraPose))

    internal fun horizontalOrigin(worldFromCamera: FloatArray): FloatArray {
        val forward = normalize(floatArrayOf(-worldFromCamera[8], 0f, -worldFromCamera[10]))
        val right = normalize(floatArrayOf(-forward[2], 0f, forward[0]))
        val backward = floatArrayOf(-forward[0], 0f, -forward[2])
        return floatArrayOf(
            right[0], 0f, right[2], 0f,
            0f, 1f, 0f, 0f,
            backward[0], 0f, backward[2], 0f,
            worldFromCamera[12], worldFromCamera[13], worldFromCamera[14], 1f
        )
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16)
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                var value = 0f
                for (index in 0..3) {
                    value += a[row + index * 4] * b[index + column * 4]
                }
                result[row + column * 4] = value
            }
        }
        return result
    }

    /** Inverse for a rigid transform (orthonormal rotation + translation). */
    fun rigidInverse(matrix: FloatArray): FloatArray {
        require(matrix.size == 16)
        val result = identity()
        // R^-1 = R^T.
        result[0] = matrix[0]
        result[1] = matrix[4]
        result[2] = matrix[8]
        result[4] = matrix[1]
        result[5] = matrix[5]
        result[6] = matrix[9]
        result[8] = matrix[2]
        result[9] = matrix[6]
        result[10] = matrix[10]
        val tx = matrix[12]
        val ty = matrix[13]
        val tz = matrix[14]
        result[12] = -(result[0] * tx + result[4] * ty + result[8] * tz)
        result[13] = -(result[1] * tx + result[5] * ty + result[9] * tz)
        result[14] = -(result[2] * tx + result[6] * ty + result[10] * tz)
        return result
    }

    fun transformPoint(matrix: FloatArray, point: FloatArray): FloatArray {
        require(matrix.size == 16 && point.size >= 3)
        val x = point[0]
        val y = point[1]
        val z = point[2]
        return floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12],
            matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13],
            matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
        )
    }

    fun transformDirection(matrix: FloatArray, direction: FloatArray): FloatArray {
        require(matrix.size == 16 && direction.size >= 3)
        val x = direction[0]
        val y = direction[1]
        val z = direction[2]
        return floatArrayOf(
            matrix[0] * x + matrix[4] * y + matrix[8] * z,
            matrix[1] * x + matrix[5] * y + matrix[9] * z,
            matrix[2] * x + matrix[6] * y + matrix[10] * z
        )
    }

    fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
        if (length < 1e-6f) return floatArrayOf(0f, 0f, -1f)
        return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
    }

    fun distance(a: FloatArray, b: FloatArray): Float {
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    fun translationOf(matrix: FloatArray): FloatArray = floatArrayOf(matrix[12], matrix[13], matrix[14])

    /** Convert the rotation part of a column-major matrix to an [x,y,z,w] quaternion. */
    fun quaternionOf(matrix: FloatArray): FloatArray {
        val m00 = matrix[0]
        val m11 = matrix[5]
        val m22 = matrix[10]
        val trace = m00 + m11 + m22
        val result = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            result[3] = 0.25f * s
            result[0] = (matrix[6] - matrix[9]) / s
            result[1] = (matrix[8] - matrix[2]) / s
            result[2] = (matrix[1] - matrix[4]) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            result[3] = (matrix[6] - matrix[9]) / s
            result[0] = 0.25f * s
            result[1] = (matrix[4] + matrix[1]) / s
            result[2] = (matrix[8] + matrix[2]) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            result[3] = (matrix[8] - matrix[2]) / s
            result[0] = (matrix[4] + matrix[1]) / s
            result[1] = 0.25f * s
            result[2] = (matrix[9] + matrix[6]) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            result[3] = (matrix[1] - matrix[4]) / s
            result[0] = (matrix[8] + matrix[2]) / s
            result[1] = (matrix[9] + matrix[6]) / s
            result[2] = 0.25f * s
        }
        val length = sqrt(result.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        for (index in result.indices) result[index] /= length
        return result
    }

    fun projectToScreen(
        viewProjection: FloatArray,
        worldPoint: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int
    ): ScreenProjection? {
        require(viewProjection.size == 16)
        val x = worldPoint[0]
        val y = worldPoint[1]
        val z = worldPoint[2]
        val clipX = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12]
        val clipY = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13]
        val clipZ = viewProjection[2] * x + viewProjection[6] * y + viewProjection[10] * z + viewProjection[14]
        val clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15]
        if (!clipW.isFinite() || clipW <= 1e-5f) return null
        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        val ndcZ = clipZ / clipW
        return ScreenProjection(
            x = (ndcX * 0.5f + 0.5f) * viewportWidth,
            y = (0.5f - ndcY * 0.5f) * viewportHeight,
            depth = ndcZ,
            onScreen = ndcX in -1f..1f && ndcY in -1f..1f && ndcZ in -1.2f..1.2f
        )
    }
}

data class ScreenProjection(val x: Float, val y: Float, val depth: Float, val onScreen: Boolean)
