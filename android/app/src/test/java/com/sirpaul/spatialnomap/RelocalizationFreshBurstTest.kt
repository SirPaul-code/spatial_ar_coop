package com.sirpaul.spatialnomap

import org.junit.Assert.assertNull
import org.junit.Test

class RelocalizationFreshBurstTest {
    @Test
    fun freshAcquisitionBurstAlwaysWinsOverCachedRelocalization() {
        val old = frame(burstId = 0L, sequence = -1)
        val fresh = frame(burstId = 0x1234L, sequence = 2)
        val checkpoint = RelocalizationEngine.Checkpoint(
            oldLocal = old,
            oldRemote = old,
            oldLocalFromOldRemote = doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        // This must return before touching the expensive visual solver. A stale
        // landmark cache is never allowed to serialize the first fresh room solve.
        assertNull(RelocalizationEngine.relocalize(checkpoint, fresh, fresh))
    }

    private fun frame(burstId: Long, sequence: Int) = CapturedFrame(
        timestampNs = 1L,
        pose = PosePacket(
            t = floatArrayOf(0f, 0f, 0f),
            q = floatArrayOf(0f, 0f, 0f, 1f),
        ),
        intrinsics = IntrinsicsPacket(
            fx = 500f,
            fy = 500f,
            cx = 320f,
            cy = 240f,
            width = 640,
            height = 480,
        ),
        jpegBase64 = "",
        metricPoints = emptyList(),
        burstId = burstId,
        burstSequence = sequence,
    )
}
