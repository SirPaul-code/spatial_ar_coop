package com.sirpaul.spatialnomap

import kotlin.math.ceil
import kotlin.math.max

/**
 * One place for the safety gates that decide whether a shared-world transform is
 * trustworthy enough to expose as LOCKED. These gates intentionally fail closed:
 * visual-only or two-point scale evidence can never authorize a shared POI.
 */
object TransformSafetyPolicy {
    data class Evidence(
        val visualCandidates: Int,
        val visualInliers: Int,
        val medianReprojectionPx: Double,
        val imageCoverage: Double,
        val metricPairs: Int,
        val metricInliers: Int,
        val medianMetricResidualM: Double,
        val metricSupportSpanM: Double,
        val gravityTiltDeg: Double,
    )

    fun metricEvidencePasses(
        metricPairs: Int,
        metricInliers: Int,
        medianMetricResidualM: Double,
    ): Boolean {
        if (metricPairs < MIN_METRIC_PAIRS) return false
        if (!medianMetricResidualM.isFinite()) return false
        val required = max(MIN_METRIC_INLIERS, ceil(metricPairs * MIN_METRIC_INLIER_RATIO).toInt())
        return metricInliers >= required && medianMetricResidualM <= MAX_MEDIAN_METRIC_RESIDUAL_M
    }

    fun passes(evidence: Evidence): Boolean {
        if (evidence.visualCandidates < MIN_VISUAL_CANDIDATES) return false
        if (evidence.visualInliers < MIN_VISUAL_INLIERS) return false
        if (evidence.visualInliers.toDouble() / evidence.visualCandidates < MIN_VISUAL_INLIER_RATIO) return false
        if (!evidence.medianReprojectionPx.isFinite() || evidence.medianReprojectionPx > MAX_MEDIAN_REPROJECTION_PX) return false
        if (evidence.imageCoverage < MIN_IMAGE_COVERAGE) return false
        if (!metricEvidencePasses(evidence.metricPairs, evidence.metricInliers, evidence.medianMetricResidualM)) return false
        if (!evidence.metricSupportSpanM.isFinite() || evidence.metricSupportSpanM < MIN_METRIC_SUPPORT_SPAN_M) return false
        if (evidence.gravityTiltDeg.isFinite() && evidence.gravityTiltDeg > MAX_GRAVITY_TILT_DEG) return false
        return true
    }

    const val MIN_VISUAL_CANDIDATES = 8
    const val MIN_VISUAL_INLIERS = 8
    const val MIN_VISUAL_INLIER_RATIO = 0.55
    const val MAX_MEDIAN_REPROJECTION_PX = 4.0
    const val MIN_IMAGE_COVERAGE = 0.04

    const val MIN_METRIC_PAIRS = 5
    const val MIN_METRIC_INLIERS = 4
    const val MIN_METRIC_INLIER_RATIO = 0.70
    const val MAX_MEDIAN_METRIC_RESIDUAL_M = 0.18
    const val MIN_METRIC_SUPPORT_SPAN_M = 0.12

    const val MAX_GRAVITY_TILT_DEG = 12.0
}
