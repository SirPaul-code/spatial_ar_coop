package com.sirpaul.showme

import org.json.JSONObject

/** Prepared on the API/vision worker. Only ARCore anchor creation remains on GL. */
data class PreparedStroke(val frame: FramePacket, val world: List<FloatArray>, val pixels: List<FloatArray>)
object StrokePlacement {
    fun prepare(body: JSONObject, packet: FramePacket): PreparedStroke? {
        val pixels=body.getJSONArray("points")
        val k=packet.capture.intrinsics
        val imagePoints=ArrayList<FloatArray>(pixels.length())
        val world=ArrayList<FloatArray>(pixels.length())
        for(i in 0 until pixels.length()) {
            val point=pixels.getJSONArray(i)
            val raw=ShowMeGeometry.uprightToRaw(point.getDouble(0).toFloat(),point.getDouble(1).toFloat(),packet.rotation)
            val u=(raw[0]*k.width).coerceIn(0f,k.width-1f)
            val v=(raw[1]*k.height).coerceIn(0f,k.height-1f)
            val p=ShowMeGeometry.pointAt(packet.capture,u,v) ?: return null
            if(world.isNotEmpty()&&ShowMeGeometry.distance(world.first(),p)>2.5f)return null
            // The initial reconstruction must reproduce the user's historical pixel.
            val reprojected=ShowMeGeometry.project(packet.capture,p) ?: return null
            if(kotlin.math.abs(reprojected[0]-u)>.25f||kotlin.math.abs(reprojected[1]-v)>.25f)return null
            world+=p;imagePoints+=floatArrayOf(u,v)
        }
        return PreparedStroke(packet,world,imagePoints)
    }
}
