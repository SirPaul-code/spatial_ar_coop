package com.sirpaul.spatialnomap

import kotlin.math.abs

/**
 * Rebuilds a shared-world transform after one/both ARCore local origins changed.
 *
 * Let:
 *   oldT = oldLocalFromOldRemote (the previously verified room transform)
 *   A    = newLocalFromOldLocal  (solved by matching old local keyframe -> current local frame)
 *   B    = newRemoteFromOldRemote (solved by matching old remote keyframe -> current remote frame)
 *
 * Then the new room transform is:
 *   newLocalFromNewRemote = A * oldT * inverse(B)
 *
 * AlignmentEngine supplies A/B using exactly the same visual+metric validation as
 * normal registration. This makes recorded/cached room landmarks useful after an
 * ARCore world reset without any Cloud Anchor or pre-scanned external map.
 */
object RelocalizationEngine {
    data class Checkpoint(
        val oldLocal: CapturedFrame,
        val oldRemote: CapturedFrame,
        val oldLocalFromOldRemote: DoubleArray,
    )

    data class Result(
        val newLocalFromNewRemote: DoubleArray,
        val localRecovery: AlignmentEngine.Result,
        val remoteRecovery: AlignmentEngine.Result,
        val confidence: Float,
    )

    fun relocalize(
        checkpoint: Checkpoint,
        currentLocal: CapturedFrame,
        currentRemote: CapturedFrame,
    ): Result? {
        if (!isRigid(checkpoint.oldLocalFromOldRemote)) return null

        // AlignmentEngine.solve(remote, local) returns localFromRemote.
        val newLocalFromOldLocal = AlignmentEngine.solve(checkpoint.oldLocal, currentLocal) ?: return null
        val newRemoteFromOldRemote = AlignmentEngine.solve(checkpoint.oldRemote, currentRemote) ?: return null

        if (!acceptableRecovery(newLocalFromOldLocal) || !acceptableRecovery(newRemoteFromOldRemote)) return null
        val oldRemoteFromNewRemote = invertRigid(newRemoteFromOldRemote.transformLocalFromRemote) ?: return null
        val newTransform = AlignmentEngine.multiply4(
            newLocalFromOldLocal.transformLocalFromRemote,
            AlignmentEngine.multiply4(checkpoint.oldLocalFromOldRemote, oldRemoteFromNewRemote),
        )
        if (!isRigid(newTransform)) return null

        // The transform must continue to respect gravity. A room relocalization that
        // flips/upends the world is worse than simply falling back to fresh alignment.
        val gravity = FusionMath.gravityTiltDeg(newTransform)
        if (gravity.isFinite() && gravity > MAX_GRAVITY_TILT_DEG) return null

        val confidence = minOf(
            newLocalFromOldLocal.confidence,
            newRemoteFromOldRemote.confidence,
        ).coerceIn(0f, 1f)
        if (confidence < MIN_RECOVERY_CONFIDENCE) return null

        return Result(
            newLocalFromNewRemote = newTransform,
            localRecovery = newLocalFromOldLocal,
            remoteRecovery = newRemoteFromOldRemote,
            confidence = confidence,
        )
    }

    private fun acceptableRecovery(result: AlignmentEngine.Result): Boolean {
        if (result.inliers < MIN_RECOVERY_INLIERS || result.correspondences < MIN_RECOVERY_INLIERS) return false
        if (!result.medianReprojectionPx.isFinite() || result.medianReprojectionPx > MAX_RECOVERY_REPROJECTION_PX) return false
        if (result.imageCoverage < MIN_RECOVERY_COVERAGE) return false
        if (result.metricPairs >= 4) {
            if (!result.medianMetricResidualM.isFinite() || result.medianMetricResidualM > MAX_RECOVERY_METRIC_RESIDUAL_M) {
                return false
            }
        }
        return true
    }

    private fun isRigid(t: DoubleArray): Boolean {
        if (t.size < 16 || t.take(16).any { !it.isFinite() }) return false
        if (abs(t[12]) > 1e-4 || abs(t[13]) > 1e-4 || abs(t[14]) > 1e-4 || abs(t[15] - 1.0) > 1e-4) return false
        val det = t[0] * (t[5] * t[10] - t[6] * t[9]) -
            t[1] * (t[4] * t[10] - t[6] * t[8]) +
            t[2] * (t[4] * t[9] - t[5] * t[8])
        return det in 0.95..1.05
    }

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (!isRigid(t)) return null
        val out = doubleArrayOf(
            t[0], t[4], t[8], 0.0,
            t[1], t[5], t[9], 0.0,
            t[2], t[6], t[10], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val tx = t[3]
        val ty = t[7]
        val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return out
    }

    private const val MIN_RECOVERY_INLIERS = 10
    private const val MAX_RECOVERY_REPROJECTION_PX = 3.5
    private const val MIN_RECOVERY_COVERAGE = 0.045
    private const val MAX_RECOVERY_METRIC_RESIDUAL_M = 0.25
    private const val MIN_RECOVERY_CONFIDENCE = 0.10f
    private const val MAX_GRAVITY_TILT_DEG = 12.0
}
