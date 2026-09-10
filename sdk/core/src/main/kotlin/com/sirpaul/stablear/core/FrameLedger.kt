package com.sirpaul.stablear.core

/** All calls on the owner/render thread. Workers get immutable value snapshots only. */
interface AnchorHandle {
    val id: Long
    fun worldFromAnchor(): Rigid?
    fun close()
}
fun interface AnchorFactory { fun create(worldFromAnchor: Rigid): AnchorHandle }
data class FrameRef(val id: Long,val epoch: Long,val cameraTimestampNs: Long,val capturedNs: Long,
    val anchorId: Long,val anchorFromCamera: Rigid,val intrinsics: Intrinsics)

/** Bounded nearby anchor bank. History eviction cannot kill a frozen or attached reference. */
class FrameLedger(private val factory: AnchorFactory,private val clock: ()->Long,
    private val maxFrames: Int=120,private val historyNs: Long=4_000_000_000L,
    private val maxAnchors: Int=12,private val maxFreezeNs: Long=60_000_000_000L) : AutoCloseable {
    private data class Slot(val anchor: AnchorHandle,var lastUse: Long,var holds: Int=0)
    private data class Frozen(val frame: FrameRef,val deadline: Long)
    private val anchors=linkedMapOf<Long,Slot>()
    private val frames=linkedMapOf<Long,FrameRef>()
    private val frozen=linkedMapOf<Long,Frozen>()
    private var lastFrameId=0L
    var epoch=1L; private set
    init { require(maxFrames>0 && maxAnchors>0 && historyNs>0 && maxFreezeNs>0) }
    fun capture(worldFromCamera: Rigid,k: Intrinsics,timestampNs: Long): FrameRef? {
        require(timestampNs>0); prune()
        var slot=anchors.values.filter { it.anchor.worldFromAnchor()!=null }
            .minByOrNull { (it.anchor.worldFromAnchor()!!.t-worldFromCamera.t).norm() }
        if(slot==null || (slot.anchor.worldFromAnchor()!!.t-worldFromCamera.t).norm()>.75) {
            if(anchors.size>=maxAnchors) {
                val referenced=frames.values.map { it.anchorId }.toSet()+frozen.values.map { it.frame.anchorId }
                val old=anchors.values.filter { it.holds==0 && it.anchor.id !in referenced }.minByOrNull { it.lastUse }
                if(old!=null) { anchors.remove(old.anchor.id); old.anchor.close() }
            }
            if(anchors.size>=maxAnchors) return null
            val a=factory.create(worldFromCamera); slot=Slot(a,clock()); anchors[a.id]=slot
        }
        val worldFromAnchor=slot.anchor.worldFromAnchor() ?: return null
        slot.lastUse=clock()
        val f=FrameRef(++lastFrameId,epoch,timestampNs,clock(),slot.anchor.id,worldFromAnchor.inverse()*worldFromCamera,k)
        frames[f.id]=f; prune(); return f
    }
    fun get(id: Long): FrameRef? { prune(); return frozen[id]?.frame ?: frames[id] }
    /** Repeated freeze never renews the lease; callers may pin at most four frames. */
    fun freeze(id: Long): FrameRef? {
        prune(); frozen[id]?.let { return it.frame }
        val f=frames[id] ?: return null
        if(frozen.size>=4) return null
        frozen[id]=Frozen(f,clock()+maxFreezeNs); return f
    }
    fun unfreeze(id: Long) { frozen.remove(id); prune() }
    fun currentWorldFromCamera(f: FrameRef): Rigid? {
        if(f.epoch!=epoch || get(f.id)!=f) return null
        return anchors[f.anchorId]?.anchor?.worldFromAnchor()?.times(f.anchorFromCamera)
    }
    fun retain(anchorId: Long): Boolean {
        val s=anchors[anchorId] ?: return false; s.holds++; return true
    }
    fun release(anchorId: Long) { anchors[anchorId]?.let { check(it.holds>0); it.holds-- }; prune() }
    fun worldFromAnchor(id: Long)=anchors[id]?.anchor?.worldFromAnchor()
    fun anchorCount()=anchors.size
    fun frameCount(): Int { prune(); return frames.size }
    private fun prune() {
        val now=clock()
        frozen.entries.removeAll { now>it.value.deadline }
        frames.entries.removeAll { now-it.value.capturedNs !in 0..historyNs }
        while(frames.size>maxFrames) frames.remove(frames.keys.first())
        val used=frames.values.map { it.anchorId }.toSet()+frozen.values.map { it.frame.anchorId }
        val dead=anchors.values.filter { it.holds==0 && it.anchor.id !in used && now-it.lastUse>historyNs }
        dead.forEach { anchors.remove(it.anchor.id); it.anchor.close() }
    }
    fun reset() { anchors.values.forEach { it.anchor.close() }; anchors.clear(); frames.clear(); frozen.clear(); epoch++ }
    override fun close()=reset()
}
