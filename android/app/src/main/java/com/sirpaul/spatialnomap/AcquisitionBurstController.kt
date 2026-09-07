package com.sirpaul.spatialnomap

import java.util.Locale

/**
 * Lightweight peer-synchronised acquisition burst controller.
 *
 * Both phones exchange Hello immediately after the direct TCP channel comes up.
 * Once both user/device identities are known, each side derives the same burst id
 * from the sorted identity pair and starts a short high-rate registration burst.
 * The phones do not need synchronised monotonic clocks: sequence numbers are local
 * relative indices and the coordinator can still prefer equal/adjacent sequence
 * frames while its existing relative-recency pairing remains a fallback.
 */
object AcquisitionBurstController {
    data class Tag(val burstId: Long = 0L, val sequence: Int = -1, val progress: Float = 0f)

    private const val BURST_FRAMES = 12
    private const val BURST_INTERVAL_NS = 250_000_000L
    private const val RETRY_AFTER_NS = 5_000_000_000L

    private var localIdentity = ""
    private var peerIdentity = ""
    private var burstId = 0L
    private var burstStartedNs = 0L
    private var generation = 0
    private var ready = false
    private var locked = false

    @Synchronized fun observeHello(local: Boolean, username: String, deviceModel: String) {
        val token = "${username.trim().lowercase(Locale.US)}|${deviceModel.trim().lowercase(Locale.US)}"
        if (local) localIdentity = token else peerIdentity = token
        if (localIdentity.isNotBlank() && peerIdentity.isNotBlank()) {
            val stablePair = listOf(localIdentity, peerIdentity).sorted().joinToString("<->")
            if (!ready) {
                generation += 1
                burstId = fnv1a64("$stablePair#$generation")
                burstStartedNs = System.nanoTime()
                ready = true
                locked = false
            }
        }
    }

    @Synchronized fun observeQuality(local: Boolean, isReady: Boolean) {
        if (local && isReady) locked = true
        if (locked) return
        if (!ready || burstStartedNs == 0L) return
        val now = System.nanoTime()
        val burstDuration = BURST_FRAMES * BURST_INTERVAL_NS
        if (now - burstStartedNs > burstDuration + RETRY_AFTER_NS) {
            generation += 1
            val stablePair = listOf(localIdentity, peerIdentity).sorted().joinToString("<->")
            burstId = fnv1a64("$stablePair#$generation")
            burstStartedNs = now
        }
    }

    @Synchronized fun tagForCapture(nowNs: Long = System.nanoTime()): Tag {
        if (!ready || locked || burstStartedNs == 0L) return Tag()
        val elapsed = (nowNs - burstStartedNs).coerceAtLeast(0L)
        val sequence = (elapsed / BURST_INTERVAL_NS).toInt()
        if (sequence !in 0 until BURST_FRAMES) return Tag(burstId, -1, 1f)
        return Tag(
            burstId = burstId,
            sequence = sequence,
            progress = ((sequence + 1).toFloat() / BURST_FRAMES).coerceIn(0f, 1f),
        )
    }

    @Synchronized fun currentProgress(): Float = tagForCapture().progress

    @Synchronized fun reset() {
        localIdentity = ""
        peerIdentity = ""
        burstId = 0L
        burstStartedNs = 0L
        ready = false
        locked = false
    }

    private fun fnv1a64(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (b in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
