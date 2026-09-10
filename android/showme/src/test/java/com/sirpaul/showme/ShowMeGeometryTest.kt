package com.sirpaul.showme

import com.sirpaul.spatialnomap.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ShowMeGeometryTest {
    private fun frame(pose: PosePacket = PosePacket(FloatArray(3), floatArrayOf(0f,0f,0f,1f)), supports: Boolean = true): CapturedFrame {
        val k=IntrinsicsPacket(500f,500f,320f,240f,640,480)
        val points=ArrayList<FloatArray>()
        if(supports) for(v in 230..250 step 3) for(u in 308..332 step 3) {
            val z=2f
            val world=ShowMeGeometry.toWorld(pose,floatArrayOf((u-k.cx)/k.fx*z,-(v-k.cy)/k.fy*z,-z))
            points.add(floatArrayOf(u.toFloat(),v.toFloat(),world[0],world[1],world[2]))
        }
        return CapturedFrame(1L,pose,k,"",points)
    }
    @Test fun rotationsRoundTripEveryQuadrant() {
        for(rotation in listOf(0,90,180,270)) for(x in listOf(0f,.15f,.5f,1f)) for(y in listOf(0f,.25f,.8f,1f)) {
            val upright=ShowMeGeometry.rawToUpright(x,y,rotation)
            val original=ShowMeGeometry.uprightToRaw(upright[0],upright[1],rotation)
            assertEquals(x,original[0],.00001f);assertEquals(y,original[1],.00001f)
        }
    }
    @Test fun ninetyDegreesMatchesCpuCameraRotation() {
        assertArrayEquals(floatArrayOf(1f,0f),ShowMeGeometry.rawToUpright(0f,0f,90),.00001f)
        assertArrayEquals(floatArrayOf(0f,1f),ShowMeGeometry.rawToUpright(1f,1f,90),.00001f)
    }
    @Test fun quaternionWorldAndCameraTransformRoundTrip() {
        val angle=(Math.PI/4).toFloat()
        val pose=PosePacket(floatArrayOf(3f,1f,-5f),floatArrayOf(0f,sin(angle),0f,cos(angle)))
        val point=floatArrayOf(.3f,-.2f,-2f)
        assertArrayEquals(point,ShowMeGeometry.toCamera(pose,ShowMeGeometry.toWorld(pose,point)),.00001f)
    }
    @Test fun historicalDepthTargetsTheCapturedWorldNotTheCurrentCamera() {
        val historic=frame(PosePacket(floatArrayOf(3f,1f,5f),floatArrayOf(0f,0f,0f,1f)))
        val pinned=ShowMeGeometry.pointAt(historic,320f,240f)!!
        assertArrayEquals(floatArrayOf(3f,1f,3f),pinned,.001f)
        val moved=historic.copy(pose=PosePacket(floatArrayOf(4f,1f,5f),floatArrayOf(0f,0f,0f,1f)))
        // The same pinned world point moves left on screen after the camera moves right.
        val screen=ShowMeGeometry.project(moved,pinned)!!
        assertTrue(screen[0]<320f)
        assertArrayEquals(pinned,ShowMeGeometry.pointAt(historic,320f,240f),.001f)
    }
    @Test fun inverseDepthFitPlacesTheExactRayRatherThanNearestSample() {
        val f=frame();val point=ShowMeGeometry.pointAt(f,319.3f,240.2f)!!
        val pixel=ShowMeGeometry.project(f,point)!!
        assertEquals(319.3f,pixel[0],.001f);assertEquals(240.2f,pixel[1],.001f)
        assertEquals(-2f,point[2],.001f)
    }
    @Test fun missingDepthAndOutOfBoundsFailClosed() {
        assertNull(ShowMeGeometry.pointAt(frame(supports=false),320f,240f))
        assertNull(ShowMeGeometry.pointAt(frame(),-1f,240f))
        assertNull(ShowMeGeometry.pointAt(frame(),Float.NaN,240f))
        assertNull(ShowMeGeometry.pointAt(frame(),10f,10f))
    }
    @Test fun competingForegroundAndBackgroundAtTheClickAreRejected() {
        val f=frame();val cloud=ArrayList<FloatArray>()
        repeat(10){i->
            val u=320f+(i%5-2)*.5f;val v=240f+(i/5)*.5f
            val z=if(i%2==0)1f else 4f
            cloud.add(floatArrayOf(u,v,(u-320f)/500f*z,-(v-240f)/500f*z,-z))
        }
        assertNull(ShowMeGeometry.pointAt(f.copy(metricPoints=cloud),320f,240f))
    }
    @Test fun pointsBehindTheCameraAreNeverProjected() {
        assertNull(ShowMeGeometry.project(frame(),floatArrayOf(0f,0f,1f)))
    }
}
