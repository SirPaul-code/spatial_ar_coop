package com.sirpaul.showme

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Protocol validation is independent of camera, transport and account/licensing providers. */
data class DrawCommand(val requestId:String,val frameId:Long,val tool:String,val points:List<Uv>,val color:String,val label:String)
object SessionPolicy {
    val tools=setOf("pin","pointer","arrow","pen","circle")
    val colors=setOf("mint","amber","coral","blue")
    const val MAX_FRAME_AGE_MS=45_000L
    const val MAX_ANNOTATIONS=48
    const val MAX_POINTS=64
    fun token():String=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    fun tokenMatches(expected:String,supplied:String):Boolean = supplied.length==expected.length && MessageDigest.isEqual(expected.toByteArray(),supplied.toByteArray())
    fun fresh(createdMs:Long,nowMs:Long)=nowMs>=createdMs && nowMs-createdMs<=MAX_FRAME_AGE_MS
    fun parseDraw(json:JSONObject):DrawCommand {
        val id=json.getString("requestId"); require(id.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Invalid request ID" }
        val frameId=json.getString("frameId").toLong(); require(frameId>0) { "Invalid frame" }
        val tool=json.getString("tool"); require(tool in tools) { "Unknown tool" }
        val array=json.getJSONArray("points"); require(array.length() in 1..MAX_POINTS) { "Stroke is too large" }
        val points=(0 until array.length()).map { i ->
            val p=array.getJSONArray(i); require(p.length()==2)
            val x=p.getDouble(0); val y=p.getDouble(1)
            require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) { "Point outside image" }
            Uv(x.toFloat(),y.toFloat())
        }
        require(if(tool=="pin" || tool=="pointer") points.size==1 else points.size>=2) { "Incomplete shape" }
        val color=json.optString("color","mint"); require(color in colors)
        return DrawCommand(id,frameId,tool,points,color,json.optString("label").take(80).filter { it>=' ' })
    }
}

class RequestLedger(private val capacity:Int=256) {
    private val replies=LinkedHashMap<String,String>()
    @Synchronized fun begin(id:String):Boolean {
        if(replies.containsKey(id)) return false
        replies[id]="pending"
        while(replies.size>capacity) replies.remove(replies.keys.first())
        return true
    }
    @Synchronized fun finish(id:String,reply:String) { replies[id]=reply }
    @Synchronized fun reply(id:String)=replies[id]
}
