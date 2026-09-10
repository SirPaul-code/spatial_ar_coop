package com.sirpaul.showme

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FieldRegressionTest {
    @Test fun circleAnchorUsesCentroidAndWholeShapePreservesItsCentre() {
        val circle=(0 until 32).map { i->val a=i*2*PI/32;floatArrayOf(1f+.03f*cos(a).toFloat(),2f+.03f*sin(a).toFloat(),-1f) }
        val center=StrokeGeometry.center(circle)
        assertArrayEquals(floatArrayOf(1f,2f,-1f),center,.00001f)
        assertTrue(StrokeGeometry.shapeCompatible(circle,circle.map{floatArrayOf(it[0]+.005f,it[1]-.004f,it[2])}))
        assertFalse(StrokeGeometry.shapeCompatible(circle,circle.map{floatArrayOf(it[0]*2,it[1]*2,it[2])}))
    }
    @Test fun correspondenceRequiresEveryVertexAndRejectsNonFiniteGeometry() {
        val points=listOf(floatArrayOf(1f,2f,-1f),floatArrayOf(2f,2f,-1f))
        assertFalse(StrokeGeometry.shapeCompatible(points,points.take(1)))
        assertFalse(StrokeGeometry.shapeCompatible(points,listOf(points[0],floatArrayOf(Float.NaN,2f,-1f))))
    }
    @Test fun nativeFragmentsMatchBrowserProtocolAndReassembleInReverseOrder() {
        val payload="frame".repeat(20000);val fragments=ControlFragments.split("request001",payload)
        assertTrue(JSONObject(fragments[0]).has("i"));assertTrue(JSONObject(fragments[0]).has("n"));assertTrue(JSONObject(fragments[0]).has("text"))
        val assembler=ControlFragments();var complete:String?=null
        fragments.reversed().forEach { assembler.accept(it)?.let{value->complete=value} }
        assertEquals(payload,complete)
    }
    @Test fun staleOrOversizedFragmentsCannotAllocateAnUnboundedMessage() {
        var now=0L;val assembler=ControlFragments{now}
        val first=JSONObject().put("id","request001").put("i",0).put("n",2).put("text","a").toString()
        assertNull(assembler.accept(first));now=13000L
        assertNull(assembler.accept(JSONObject(first).put("i",1).put("text","b").toString()))
        try{assembler.accept(JSONObject(first).put("n",1000000).toString());fail("Unbounded count accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun telemetryMeasuresActualWorkInsteadOfClaimingThirtyFps() {
        val telemetry=FrameTelemetry();repeat(20){telemetry.frame(18f,2f)};repeat(5){telemetry.frame(160f,20f)}
        val snapshot=telemetry.snapshot()
        assertTrue(snapshot.toString().contains("160"))
    }
}
