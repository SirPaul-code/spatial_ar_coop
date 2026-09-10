package com.sirpaul.showme

import com.sirpaul.spatialnomap.CapturedFrame
import com.sirpaul.spatialnomap.IntrinsicsPacket
import com.sirpaul.spatialnomap.PosePacket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StableArPinDepthRegressionTest {
    @Test fun stablePinOffsetNeverCancelsSdkDepth() {
        // Deliberately make the old ShowMe reconstruction wildly different from StableAR's root.
        // A pin must render exactly at StableAR root, not at root + (legacy - root) == legacy.
        val legacyWorld=floatArrayOf(1.5f,-.8f,3.2f)
        val stableRoot=floatArrayOf(.12f,.04f,-.73f)
        val offsets=StableArPlacementMath.relativeOffsets(listOf(legacyWorld),stableRoot)!!
        assertEquals(1,offsets.size)
        assertArrayEquals(floatArrayOf(0f,0f,0f),offsets.single(),0f)
        val rendered=FloatArray(3){axis->stableRoot[axis]+offsets.single()[axis]}
        assertArrayEquals(stableRoot,rendered,0f)
    }

    @Test fun stablePinPreparationDoesNotRequireLegacyMetricDepth() {
        val capture=CapturedFrame(
            123_000_000L,
            PosePacket(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f,1f)),
            IntrinsicsPacket(500f,500f,320f,240f,640,480),
            "",
            emptyList(), // This is the regression: legacy ShowMe has zero usable depth supports.
        )
        val packet=FramePacket(77L,4,capture,byteArrayOf(),90,1000L,emptyList())
        val body=JSONObject()
            .put("tool","pin")
            .put("points",JSONArray().put(JSONArray().put(.2).put(.3)))

        assertNull("Legacy preparation must still fail without metric supports",
            StrokePlacement.prepare(body,packet,requireMetricDepth=true))

        val stable=StrokePlacement.prepare(body,packet,requireMetricDepth=false)
        assertNotNull("StableAR pin must reach the SDK even when legacy depth is unavailable",stable)
        assertEquals(1,stable!!.pixels.size)
        // rotation=90: upright(x=.2,y=.3) -> raw(x=.3,y=.8)
        assertEquals(192f,stable.pixels.single()[0],.001f)
        assertEquals(384f,stable.pixels.single()[1],.001f)
        assertArrayEquals(floatArrayOf(0f,0f,0f),stable.world.single(),0f)
    }

    @Test fun depthlessBypassIsPinOnly() {
        val capture=CapturedFrame(
            123_000_000L,
            PosePacket(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f,1f)),
            IntrinsicsPacket(500f,500f,320f,240f,640,480),
            "",
            emptyList(),
        )
        val packet=FramePacket(77L,4,capture,byteArrayOf(),0,1000L,emptyList())
        val body=JSONObject()
            .put("tool","arrow")
            .put("points",JSONArray()
                .put(JSONArray().put(.2).put(.3))
                .put(JSONArray().put(.4).put(.3)))
        assertNull(StrokePlacement.prepare(body,packet,requireMetricDepth=false))
    }
}
