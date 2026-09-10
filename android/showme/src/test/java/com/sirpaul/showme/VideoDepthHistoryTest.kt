package com.sirpaul.showme

import com.sirpaul.spatialnomap.IntrinsicsPacket
import com.sirpaul.spatialnomap.PosePacket
import org.junit.Assert.*
import org.junit.Test

class VideoDepthHistoryTest {
    private fun frame(id: Long, time: Long): VideoDepthFrame {
        val depth=DepthRaster(20,20,ShortArray(400){2000},ByteArray(400){255.toByte()})
        return VideoDepthFrame(id,3,time,PosePacket(floatArrayOf(1f,2f,3f),floatArrayOf(0f,0f,0f,1f)),
            IntrinsicsPacket(500f,500f,320f,240f,640,480),90,depth,null,
            floatArrayOf(0f,0f,640f,0f,0f,480f),emptyList(),emptyList(),480,672,640)
    }
    @Test fun historyIsBoundedAndExpiresInsteadOfUsingAnotherFramesDepth() {
        var time=1000L;val history=VideoDepthHistory{time}
        for(id in 1..121)history.add(frame(id.toLong(),time))
        assertNull(history.get(1L));assertNotNull(history.get(121L))
        time+=4001L;assertNull(history.get(121L))
    }
    @Test fun resettingHistoryCannotLeaveOldVideoIdentitiesPlaceable() {
        val history=VideoDepthHistory{1000L};history.add(frame(11L,1000L));history.clear()
        assertNull(history.get(11L))
    }
    @Test fun lowConfidenceRawDepthIsRejected() {
        val raster=DepthRaster(2,2,shortArrayOf(2000,2000,2000,0),byteArrayOf(127,128.toByte(),255.toByte(),255.toByte()))
        assertEquals(0,raster.depth(0,0));assertEquals(2000,raster.depth(1,0))
        assertEquals(2000,raster.depth(0,1));assertEquals(0,raster.depth(1,1))
        assertEquals(0,raster.depth(-1,0))
    }
    @Test fun copiedDepthUsesItsOwnPoseAndImageMapping() {
        val f=frame(10L,1000L);val support=f.supports().first()
        val u=support[0];val v=support[1]
        assertEquals(1f+(u-320f)/500f*2f,support[2],.0001f)
        assertEquals(2f-(v-240f)/500f*2f,support[3],.0001f)
        assertEquals(1f,support[4],.0001f)
    }
}
