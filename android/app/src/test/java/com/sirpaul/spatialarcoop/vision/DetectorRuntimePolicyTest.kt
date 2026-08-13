package com.sirpaul.spatialarcoop.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DetectorRuntimePolicyTest {
    @Test
    fun lowRamStartsOnLite0Cpu() {
        val policy = DetectorRuntimePolicy(lowRamDevice = true)
        assertEquals(DetectorModelProfile.LITE0, policy.profile.model)
        assertEquals(DetectorDelegateProfile.CPU, policy.profile.delegate)
    }

    @Test
    fun sustainedLatencyDowngradesLite2() {
        val policy = DetectorRuntimePolicy(
            lowRamDevice = false,
            initialProfile = DetectorRuntimeProfile(DetectorModelProfile.LITE2, DetectorDelegateProfile.CPU),
            switchCooldownMs = 0
        )
        var switched: DetectorRuntimeProfile? = null
        repeat(10) { index -> switched = policy.observeLatency(280, index * 1000L) ?: switched }
        assertNotNull(switched)
        assertEquals(DetectorModelProfile.LITE0, policy.profile.model)
    }

    @Test
    fun gpuFailureImmediatelyFallsBackToCpu() {
        val policy = DetectorRuntimePolicy(lowRamDevice = false, switchCooldownMs = 999_999)
        val fallback = assertNotNull(policy.gpuFailure(1L))
        assertEquals(DetectorDelegateProfile.CPU, fallback.delegate)
    }
}
