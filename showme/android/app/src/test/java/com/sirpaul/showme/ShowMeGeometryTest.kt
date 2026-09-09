package com.sirpaul.showme
import com.sirpaul.spatialnomap.*
import org.junit.Assert.*
import org.junit.Test

class ShowMeGeometryTest {
    @Test fun rotationsRoundTripWithoutMirroring() {
        for(rotation in listOf(0,90,180,270)) for(p in listOf(Uv(0f,0f),Uv(1f,1f),Uv(.22f,.81f))) {
            val raw=SurfaceGeometry.rawPixel(p,1280,720,rotation)
            val restored=SurfaceGeometry.displayPixel(raw,1280,720,rotation)
            assertEquals(p.x,restored.x,1e-6f); assertEquals(p.y,restored.y,1e-6f)
        }
    }
    @Test fun historicalDepthResolvesExactCameraRay() {
        val k=IntrinsicsPacket(500f,500f,400f,300f,800,600)
        val metric=ArrayList<FloatArray>()
        for(y in 285..315 step 5) for(x in 385..415 step 5) metric+=floatArrayOf(x.toFloat(),y.toFloat(),(x-400)/500f*2f,-(y-300)/500f*2f,-2f)
        val frame=CapturedFrame(1,PosePacket(FloatArray(3),floatArrayOf(0f,0f,0f,1f)),k,"",metric)
        val hit=SurfaceGeometry.resolve(frame,Uv(400f,300f))!!
        assertEquals(0f,hit.world[0],.001f); assertEquals(0f,hit.world[1],.001f); assertEquals(-2f,hit.world[2],.005f)
        assertEquals("DEPTH",hit.source)
    }
    @Test fun noDepthDoesNotInventADistance() {
        val frame=CapturedFrame(1,PosePacket(FloatArray(3),floatArrayOf(0f,0f,0f,1f)),IntrinsicsPacket(500f,500f,400f,300f,800,600),"",emptyList())
        assertNull(SurfaceGeometry.resolve(frame,Uv(400f,300f)))
        assertNull(SurfaceGeometry.resolve(frame,Uv(-1f,300f)))
    }
    @Test fun quaternionTransformAndInverseAgree() {
        val pose=PosePacket(floatArrayOf(5f,-2f,8f),floatArrayOf(0f,.70710677f,0f,.70710677f))
        val m=SurfaceGeometry.matrix(pose); val point=floatArrayOf(.1f,1.8f,-2.4f)
        assertArrayEquals(point,SurfaceGeometry.transform(SurfaceGeometry.inverse(m),SurfaceGeometry.transform(m,point)),.00001f)
    }
    @Test fun expiredOrFutureFramesAreRejected() {
        assertTrue(SessionPolicy.fresh(100,45_100)); assertFalse(SessionPolicy.fresh(100,45_101)); assertFalse(SessionPolicy.fresh(100,99))
    }
}
