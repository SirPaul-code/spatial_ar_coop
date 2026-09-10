package com.sirpaul.showme

import org.json.JSONObject

/** Small, bounded SCTP messages prevent a frozen image from becoming one huge data-channel send. */
class ControlFragments(private val clock: ()->Long = ::monotonicMs) {
    private data class Parts(val total:Int,val chunks:Array<String?>,val created:Long,var length:Int=0)
    private val active=LinkedHashMap<String,Parts>()
    @Synchronized fun accept(text:String): String? {
        require(text.length<=48_000)
        val obj=JSONObject(text);val id=obj.getString("id");val index=obj.getInt("i");val total=obj.getInt("n")
        require(id.matches(Regex("[A-Za-z0-9-]{8,80}"))&&total in 1..256&&index in 0 until total)
        val chunk=obj.getString("text");require(chunk.length<=8000)
        val now=clock();active.entries.removeIf { now-it.value.created>12_000L }
        val entry=active[id] ?: run { require(active.size<4);Parts(total,arrayOfNulls(total),now).also { active[id]=it } }
        require(entry.total==total)
        if(entry.chunks[index]!=null){require(entry.chunks[index]==chunk);return null}
        entry.length+=chunk.length;require(entry.length<=2_048_000)
        entry.chunks[index]=chunk
        if(entry.chunks.any { it==null })return null
        active.remove(id);return entry.chunks.joinToString("") { it!! }
    }
    @Synchronized fun clear(){active.clear()}
    companion object {
        fun split(id:String,message:String):List<String> {
            require(message.length<=2_048_000)
            val chunks=message.chunked(8000)
            return chunks.mapIndexed { i,text->JSONObject().put("id",id).put("i",i).put("n",chunks.size).put("text",text).toString() }
        }
    }
}
