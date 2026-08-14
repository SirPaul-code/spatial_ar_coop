package com.sirpaul.spatialarcoop.vision

enum class DetectorModelProfile(val assetName: String, val displayName: String) {
    LITE0("efficientdet-lite0.tflite", "EfficientDet-Lite0"),
    LITE2("efficientdet-lite2.tflite", "EfficientDet-Lite2")
}

enum class DetectorDelegateProfile { CPU, GPU }

data class DetectorRuntimeProfile(
    val model: DetectorModelProfile,
    val delegate: DetectorDelegateProfile
)

data class DetectorRuntimeState(
    val profile: DetectorRuntimeProfile,
    val ewmaLatencyMs: Float,
    val submittedFrames: Long,
    val resultFrames: Long,
    val droppedFrames: Long,
    val switches: Int,
    val note: String = ""
)

class DetectorRuntimePolicy(
    private val lowRamDevice: Boolean,
    initialProfile: DetectorRuntimeProfile = initialProfile(lowRamDevice),
    private val switchCooldownMs: Long = 15_000L
) {
    var profile: DetectorRuntimeProfile = initialProfile
        private set
    var ewmaLatencyMs: Float = 0f
        private set
    var switches: Int = 0
        private set

    private var slowStreak = 0
    private var fastStreak = 0
    private var lastSwitchAtMs = Long.MIN_VALUE / 4

    fun observeLatency(latencyMs: Long, nowMs: Long): DetectorRuntimeProfile? {
        val value = latencyMs.coerceIn(1L, 5_000L).toFloat()
        ewmaLatencyMs = if (ewmaLatencyMs <= 0f) value else ewmaLatencyMs * 0.82f + value * 0.18f
        if (ewmaLatencyMs > slowLatencyThreshold()) {
            slowStreak += 1
            fastStreak = 0
        } else if (ewmaLatencyMs < fastLatencyThreshold()) {
            fastStreak += 1
            slowStreak = 0
        } else {
            slowStreak = (slowStreak - 1).coerceAtLeast(0)
            fastStreak = (fastStreak - 1).coerceAtLeast(0)
        }
        if (nowMs - lastSwitchAtMs < switchCooldownMs) return null

        if (profile.model == DetectorModelProfile.LITE2 && slowStreak >= SLOW_STREAK_TO_DOWNGRADE) {
            return switchTo(profile.copy(model = DetectorModelProfile.LITE0), nowMs)
        }
        if (!lowRamDevice && profile.model == DetectorModelProfile.LITE0 && fastStreak >= FAST_STREAK_TO_UPGRADE) {
            return switchTo(profile.copy(model = DetectorModelProfile.LITE2), nowMs)
        }
        return null
    }

    fun gpuFailure(nowMs: Long): DetectorRuntimeProfile? {
        if (profile.delegate != DetectorDelegateProfile.GPU) return null
        return switchTo(profile.copy(delegate = DetectorDelegateProfile.CPU), nowMs, ignoreCooldown = true)
    }

    private fun slowLatencyThreshold(): Float = when {
        lowRamDevice -> LOW_RAM_SLOW_LATENCY_MS
        profile.delegate == DetectorDelegateProfile.CPU -> CAPABLE_CPU_SLOW_LATENCY_MS
        else -> GPU_SLOW_LATENCY_MS
    }

    private fun fastLatencyThreshold(): Float = when {
        lowRamDevice -> LOW_RAM_FAST_LATENCY_MS
        profile.delegate == DetectorDelegateProfile.CPU -> CAPABLE_CPU_FAST_LATENCY_MS
        else -> GPU_FAST_LATENCY_MS
    }

    private fun switchTo(
        newProfile: DetectorRuntimeProfile,
        nowMs: Long,
        ignoreCooldown: Boolean = false
    ): DetectorRuntimeProfile? {
        if (newProfile == profile) return null
        if (!ignoreCooldown && nowMs - lastSwitchAtMs < switchCooldownMs) return null
        profile = newProfile
        switches += 1
        slowStreak = 0
        fastStreak = 0
        lastSwitchAtMs = nowMs
        return newProfile
    }

    companion object {
        private const val GPU_SLOW_LATENCY_MS = 185f
        private const val GPU_FAST_LATENCY_MS = 92f
        private const val LOW_RAM_SLOW_LATENCY_MS = 185f
        private const val LOW_RAM_FAST_LATENCY_MS = 92f
        private const val CAPABLE_CPU_SLOW_LATENCY_MS = 300f
        private const val CAPABLE_CPU_FAST_LATENCY_MS = 150f
        private const val SLOW_STREAK_TO_DOWNGRADE = 6
        private const val FAST_STREAK_TO_UPGRADE = 36

        fun initialProfile(lowRamDevice: Boolean): DetectorRuntimeProfile = if (lowRamDevice) {
            DetectorRuntimeProfile(DetectorModelProfile.LITE0, DetectorDelegateProfile.CPU)
        } else {
            DetectorRuntimeProfile(DetectorModelProfile.LITE2, DetectorDelegateProfile.GPU)
        }
    }
}
