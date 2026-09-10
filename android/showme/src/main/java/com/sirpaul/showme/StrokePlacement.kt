package com.sirpaul.showme

import org.json.JSONObject

/** Prepared on the API/vision worker. Only ARCore anchor creation remains on GL. */
data class PreparedStroke(val frame: FramePacket, val world: List<FloatArray>, val pixels: List<FloatArray>)
object StrokePlacement {
    /**
     * Convert helper coordinates into the exact historical camera image.
     *
     * StableAR pins deliberately set requireMetricDepth=false: their metric Z is owned by the
     * SDK's exact-frame SurfaceFitter on the AR owner thread. The placeholder world value is never
     * rendered for a StableAR pin; StableArPlacementMath gives a one-point pin a zero local offset.
     * Legacy mode and multi-point tools retain the existing ShowMe reconstruction for now.
     */
    fun prepare(body: JSONObject, packet: FramePacket, requireMetricDepth: Boolean = true): PreparedStroke? {
        val points=body.getJSONArray("points")
        val k=packet.capture.intrinsics
        val imagePoints=ArrayList<FloatArray>(points.length())
        val world=ArrayList<FloatArray>(points.length())
        val depthlessStablePin=!requireMetricDepth && body.optString("tool")=="pin" && points.length()==1
        if(!requireMetricDepth && !depthlessStablePin)return null
        for(i in 0 until points.length()) {
            val point=points.getJSONArray(i)
            val raw=ShowMeGeometry.uprightToRaw(point.getDouble(0).toFloat(),point.getDouble(1).toFloat(),packet.rotation)
            val u=(raw[0]*k.width).coerceIn(0f,k.width-1f)
            val v=(raw[1]*k.height).coerceIn(0f,k.height-1f)
            imagePoints+=floatArrayOf(u,v)
            if(depthlessStablePin) {
                // Only a shape placeholder. StableAR's root is the complete world geometry for a pin.
                world+=floatArrayOf(0f,0f,0f)
                continue
            }
            val p=ShowMeGeometry.pointAt(packet.capture,u,v) ?: return null
            if(world.isNotEmpty()&&ShowMeGeometry.distance(world.first(),p)>2.5f)return null
            // The initial reconstruction must reproduce the user's historical pixel.
            val reprojected=ShowMeGeometry.project(packet.capture,p) ?: return null
            if(kotlin.math.abs(reprojected[0]-u)>.25f||kotlin.math.abs(reprojected[1]-v)>.25f)return null
            world+=p
        }
        return PreparedStroke(packet,world,imagePoints)
    }
}
