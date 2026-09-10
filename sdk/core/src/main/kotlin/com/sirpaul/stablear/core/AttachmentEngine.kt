package com.sirpaul.stablear.core

import kotlin.math.*

/** Values are deliberately not sold as calibrated ARCore covariance. */
data class LockPolicy(val minimumViews: Int=4,val minimumParallaxDeg: Double=2.0,
    val maxCorrectionM: Double=.08,val totalTravelM: Double=.15,
    val maxConditionalSigmaM: Double=.03,val assumedCommonTranslationSigmaM: Double=.005,
    val systematicFloorM: Double=.01,val maxObservationAgeNs: Long=2_000_000_000L) {
    init { require(listOf(minimumParallaxDeg,maxCorrectionM,totalTravelM,maxConditionalSigmaM,assumedCommonTranslationSigmaM,systematicFloorM).all { it.isFinite() }); require(minimumViews>=3 && minimumParallaxDeg>0 && maxCorrectionM>0 && totalTravelM>0)
        require(maxConditionalSigmaM>0 && assumedCommonTranslationSigmaM>=0 && systematicFloorM>=0 && maxObservationAgeNs>0) }
}
data class RootReference(val frameId: Long,val epoch: Long,val anchorId: Long,val cameraTimestampNs: Long,
    val cameraInAnchor: Rigid,val intrinsics: Intrinsics,val pixel: V2) {
    init { require(frameId>0 && epoch>0 && anchorId>0 && cameraTimestampNs>0 && intrinsics.contains(pixel)) }
}
data class VisualObservation(val frameId: Long,val epoch: Long,val anchorId: Long,val generation: Long,
    val cameraTimestampNs: Long,val capturedNs: Long,val cameraInAnchor: Rigid,val intrinsics: Intrinsics,
    val pixel: V2,val inliers: Int,val forwardBackwardPx: Double,val reprojectionPx: Double,
    val sigmaPx: Double=1.0,val depthEvidence: Set<EvidenceId> = emptySet()) {
    init { require(frameId>0 && cameraTimestampNs>0 && capturedNs>=0 && intrinsics.contains(pixel))
        require(inliers>=0 && forwardBackwardPx.isFinite() && forwardBackwardPx>=0)
        require(reprojectionPx.isFinite() && reprojectionPx>=0 && sigmaPx.isFinite() && sigmaPx>0) }
}
enum class LockState { UNVERIFIED, GEOMETRY_SUPPORTED, OCCLUDED, LOST }
data class AttachmentSnapshot(val id: Long,val generation: Long,val root: RootReference,
    val depthM: Double,val conditionalSigmaM: Double,val state: LockState,val travelM: Double) {
    fun pointInAnchor()=root.cameraInAnchor.point(root.intrinsics.ray(root.pixel)*depthM)
}
data class LockProposal(val attachmentId: Long,val generation: Long,val epoch: Long,val anchorId: Long,
    val depthM: Double,val conditionalSigmaM: Double,val parallaxDeg: Double,val observations: List<VisualObservation>,
    val reason: String)
data class LockDecision(val accepted: Boolean,val reason: String,val snapshot: AttachmentSnapshot?)

/** Robust one-dimensional geometric solve. Correspondence identity is a separate front-end obligation. */
object RayRefiner {
    fun propose(s: AttachmentSnapshot,observations: List<VisualObservation>,p: LockPolicy): LockProposal? {
        val root=s.root; val ray=root.cameraInAnchor.q.rotate(root.intrinsics.ray(root.pixel))
        val origin=root.cameraInAnchor.t
        val obs=observations.filter { it.epoch==root.epoch && it.anchorId==root.anchorId && it.generation==s.generation &&
            it.cameraTimestampNs!=root.cameraTimestampNs && it.inliers>=12 && it.forwardBackwardPx<=1.0 && it.reprojectionPx<=1.5 }
            .distinctBy { it.cameraTimestampNs }.takeLast(12)
        if(obs.size<p.minimumViews) return null
        fun project(z: Double,o: VisualObservation,shift: V3=V3.ZERO): V2? =
            o.intrinsics.project(o.cameraInAnchor.q.inverse().rotate(origin+ray*z-o.cameraInAnchor.t-shift))
        fun angle(z: Double,o: VisualObservation): Double {
            val point=origin+ray*z
            val a=point-origin; val b=point-o.cameraInAnchor.t
            if(a.norm()<1e-6 || b.norm()<1e-6) return 0.0
            return acos((a.dot(b)/(a.norm()*b.norm())).coerceIn(-1.0,1.0))*180/PI
        }
        if(obs.maxOf { angle(s.depthM,it) }<p.minimumParallaxDeg) return null
        val lo=max(.15,s.depthM-p.maxCorrectionM); val hi=min(8.0,s.depthM+p.maxCorrectionM)
        var z=s.depthM
        val priorSigma=max(.025,s.conditionalSigmaM)
        fun solve(active: List<VisualObservation>,robust: Boolean): Boolean {
            repeat(15) {
                var h=1/(priorSigma*priorSigma); var g=(z-s.depthM)*h
                for(o in active) {
                    val predicted=project(z,o) ?: return false
                    val a=project(z+1e-4,o) ?: return false; val b=project(z-1e-4,o) ?: return false
                    val j=V2((a.x-b.x)/2e-4/o.sigmaPx,(a.y-b.y)/2e-4/o.sigmaPx)
                    val r=V2((predicted.x-o.pixel.x)/o.sigmaPx,(predicted.y-o.pixel.y)/o.sigmaPx)
                    val w=if(robust) min(1.0,2/r.norm().coerceAtLeast(1e-12)) else 1.0
                    h+=w*(j.x*j.x+j.y*j.y); g+=w*(j.x*r.x+j.y*r.y)
                }
                val step=(g/h).coerceIn(-.03,.03); z=(z-step).coerceIn(lo,hi)
            }
            return true
        }
        if(!solve(obs,true)) return null
        val kept=obs.filter { (project(z,it) ?: return null).minus(it.pixel).norm()<=3*it.sigmaPx }
        if(kept.size<p.minimumViews || !solve(kept,false)) return null
        if(z-lo<1e-5 || hi-z<1e-5) return null
        if(kept.any { (project(z,it) ?: return null).minus(it.pixel).norm()>3*it.sigmaPx }) return null
        val parallax=kept.maxOf { angle(z,it) }; if(parallax<p.minimumParallaxDeg) return null
        var h=1/(priorSigma*priorSigma); val cross=DoubleArray(3)
        for(o in kept) {
            val ap=project(z+1e-5,o) ?: return null; val am=project(z-1e-5,o) ?: return null
            val j=doubleArrayOf((ap.x-am.x)/2e-5/o.sigmaPx,(ap.y-am.y)/2e-5/o.sigmaPx)
            h+=j.sumOf { it*it }
            for(axis in 0..2) {
                val v=when(axis) { 0 -> V3(1e-5,0.0,0.0); 1 -> V3(0.0,1e-5,0.0); else -> V3(0.0,0.0,1e-5) }
                val bp=project(z,o,v) ?: return null; val bm=project(z,o,v*(-1.0)) ?: return null
                cross[axis]+=j[0]*(bp.x-bm.x)/2e-5/o.sigmaPx+j[1]*(bp.y-bm.y)/2e-5/o.sigmaPx
            }
        }
        val sigma=sqrt(1/h+p.systematicFloorM.pow(2)+p.assumedCommonTranslationSigmaM.pow(2)*cross.sumOf { (it/h).pow(2) })
        if(sigma>p.maxConditionalSigmaM) return null
        return LockProposal(s.id,s.generation,root.epoch,root.anchorId,z,sigma,parallax,kept.toList(),
            "Conditional geometry support; identity and systematic noise remain assumptions")
    }
}

/** Own-thread state machine. No learning before commit, no coordinate frame leaking through workers. */
class AttachmentEngine(private val clock: ()->Long, val policy: LockPolicy=LockPolicy()) {
    private data class Entry(var snapshot: AttachmentSnapshot,val seedDepth: Double,
        val observations: LinkedHashMap<Long,VisualObservation> = linkedMapOf(),var lastCommitNs: Long=0)
    private val entries=linkedMapOf<Long,Entry>(); private var nextId=0L
    fun create(root: RootReference,fit: SurfaceFit): AttachmentSnapshot {
        require(fit.depth in .15..8.0 && fit.conditionalSigma.isFinite() && fit.conditionalSigma>0)
        check(entries.size<64) { "Attachment capacity reached" }
        val s=AttachmentSnapshot(++nextId,1,root,fit.depth,fit.conditionalSigma,LockState.UNVERIFIED,0.0)
        entries[s.id]=Entry(s,fit.depth); return s
    }
    fun snapshot(id: Long)=entries[id]?.snapshot
    fun snapshots()=entries.values.map { it.snapshot }
    fun remove(id: Long) { entries.remove(id) }
    fun clear() { entries.clear() }
    fun offer(id: Long,o: VisualObservation): LockProposal? {
        val e=entries[id] ?: return null; val s=e.snapshot
        if(o.epoch!=s.root.epoch || o.anchorId!=s.root.anchorId || o.generation!=s.generation ||
            clock()-o.capturedNs !in 0..policy.maxObservationAgeNs || o.cameraTimestampNs<=e.lastCommitNs ||
            e.observations.containsKey(o.cameraTimestampNs)) return null
        e.observations.entries.removeAll { clock()-it.value.capturedNs !in 0..policy.maxObservationAgeNs }
        // Adjacent frames at effectively the same viewpoint add no useful conditioning.
        val previous=e.observations.values.lastOrNull()
        if(previous!=null && ((previous.cameraInAnchor.t-o.cameraInAnchor.t).norm()<.015 ||
                o.cameraTimestampNs-previous.cameraTimestampNs<80_000_000L)) return null
        e.observations[o.cameraTimestampNs]=o
        while(e.observations.size>12) e.observations.remove(e.observations.keys.first())
        return RayRefiner.propose(s,e.observations.values.toList(),policy)
    }
    /** Recompute from retained observations; a public/caller-forged proposal cannot bypass geometric gates. */
    fun commit(proposal: LockProposal,current: VisualObservation): LockDecision {
        val e=entries[proposal.attachmentId] ?: return LockDecision(false,"Attachment removed",null)
        val s=e.snapshot
        fun reject(reason: String)=LockDecision(false,reason,s)
        if(s.generation!=proposal.generation || s.root.epoch!=proposal.epoch || s.root.anchorId!=proposal.anchorId)
            return reject("Stale attachment generation or tracking space")
        if(current.epoch!=s.root.epoch || current.anchorId!=s.root.anchorId || current.generation!=s.generation ||
            clock()-current.capturedNs !in 0..policy.maxObservationAgeNs) return reject("Invalid current observation")
        val fit=RayRefiner.propose(s,e.observations.values.filter { clock()-it.capturedNs in 0..policy.maxObservationAgeNs },policy)
            ?: return reject("Evidence no longer supports correction")
        if(abs(fit.depthM-proposal.depthM)>1e-8) return reject("Proposal no longer matches evidence")
        // Commit must be corroborated by a held-out image, not one used for the solve.
        if(fit.observations.any { it.cameraTimestampNs==current.cameraTimestampNs } ||
            current.cameraTimestampNs-(fit.observations.maxOf { it.cameraTimestampNs })<80_000_000L)
            return reject("Need a new held-out observation")
        if(current.inliers<12 || current.forwardBackwardPx>1 || current.reprojectionPx>1.5) return reject("Visual check failed")
        val point=s.root.cameraInAnchor.point(s.root.intrinsics.ray(s.root.pixel)*fit.depthM)
        val px=current.intrinsics.project(current.cameraInAnchor.inverse().point(point)) ?: return reject("Behind camera")
        if((px-current.pixel).norm()>2*current.sigmaPx) return reject("Held-out reprojection failed")
        val travel=(point-s.pointInAnchor()).norm()
        if(travel>policy.maxCorrectionM || s.travelM+travel>policy.totalTravelM ||
            abs(fit.depthM-e.seedDepth)*s.root.intrinsics.ray(s.root.pixel).norm()>policy.totalTravelM)
            return reject("Correction travel limit")
        e.snapshot=s.copy(generation=s.generation+1,depthM=fit.depthM,conditionalSigmaM=fit.conditionalSigmaM,
            state=LockState.GEOMETRY_SUPPORTED,travelM=s.travelM+travel)
        e.lastCommitNs=current.cameraTimestampNs; e.observations.clear()
        return LockDecision(true,"Accepted with held-out evidence",e.snapshot)
    }
    fun visibility(id: Long,visible: Boolean,lost: Boolean=false) {
        val e=entries[id] ?: return
        e.snapshot=e.snapshot.copy(state=if(lost) LockState.LOST else if(!visible) LockState.OCCLUDED else LockState.UNVERIFIED)
        if(!visible || lost) e.observations.clear()
    }
}
