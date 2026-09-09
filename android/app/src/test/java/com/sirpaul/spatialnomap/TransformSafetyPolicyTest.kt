package com.sirpaul.spatialnomap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformSafetyPolicyTest {
    @Test
    fun `visual-only evidence can never authorize lock`() {
        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = 20,
            visualInliers = 18,
            medianReprojectionPx = 1.1,
            imageCoverage = 0.20,
            metricPairs = 2,
            metricInliers = 2,
            medianMetricResidualM = 0.02,
            metricSupportSpanM = 1.0,
            gravityTiltDeg = 1.0,
        )
        assertFalse(TransformSafetyPolicy.passes(evidence))
    }

    @Test
    fun `catastrophic metric contradiction is rejected even with good image fit`() {
        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = 24,
            visualInliers = 20,
            medianReprojectionPx = 1.4,
            imageCoverage = 0.18,
            metricPairs = 9,
            metricInliers = 1,
            medianMetricResidualM = 4.7,
            metricSupportSpanM = 1.4,
            gravityTiltDeg = 2.0,
        )
        assertFalse(TransformSafetyPolicy.passes(evidence))
    }

    @Test
    fun `small repeated patch is not enough to prove a world transform`() {
        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = 18,
            visualInliers = 16,
            medianReprojectionPx = 1.2,
            imageCoverage = 0.08,
            metricPairs = 8,
            metricInliers = 8,
            medianMetricResidualM = 0.04,
            metricSupportSpanM = 0.04,
            gravityTiltDeg = 1.0,
        )
        assertFalse(TransformSafetyPolicy.passes(evidence))
    }

    @Test
    fun `broad visual and metric agreement authorizes lock`() {
        val evidence = TransformSafetyPolicy.Evidence(
            visualCandidates = 22,
            visualInliers = 18,
            medianReprojectionPx = 1.7,
            imageCoverage = 0.16,
            metricPairs = 10,
            metricInliers = 9,
            medianMetricResidualM = 0.07,
            metricSupportSpanM = 1.2,
            gravityTiltDeg = 2.5,
        )
        assertTrue(TransformSafetyPolicy.passes(evidence))
    }
}
