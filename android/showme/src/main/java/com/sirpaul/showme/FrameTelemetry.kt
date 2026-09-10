package com.sirpaul.showme

import org.json.JSONObject

/** Bounded timing samples. Report measured stage latency, not a configured FPS target. */
class FrameTelemetry {
    private val frameMs = FloatArray(180)
    private val depthMs = FloatArray(180)
    private val copyMs = FloatArray(64)
    private var frames = 0
    private var copies = 0
    private var stalls = 0
    @Synchronized fun frame(total: Float, depth: Float) {
        frameMs[frames % frameMs.size] = total
        depthMs[frames % depthMs.size] = depth
        frames++
        if (total > 50f) stalls++
    }
    @Synchronized fun copy(duration: Float) { copyMs[copies++ % copyMs.size] = duration }
    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("frameP95Ms", percentile(frameMs,frames,.95f).toDouble())
        .put("depthP95Ms", percentile(depthMs,frames,.95f).toDouble())
        .put("lumaCopyP95Ms", percentile(copyMs,copies,.95f).toDouble())
        .put("framesOver50Ms", stalls)
    private fun percentile(values: FloatArray, count: Int, quantile: Float): Float {
        val n = minOf(count,values.size)
        if(n==0)return 0f
        val copy=values.copyOf(n);copy.sort()
        return copy[((n-1)*quantile).toInt()]
    }
}
