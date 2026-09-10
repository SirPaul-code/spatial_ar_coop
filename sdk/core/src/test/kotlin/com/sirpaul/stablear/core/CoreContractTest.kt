package com.sirpaul.stablear.core

import java.util.Random
import kotlin.math.*

private var assertions=0
private fun verify(value: Boolean,message: String) { assertions++; check(value) { message } }
private fun near(a: Double,b: Double,tol: Double=1e-8) = verify(abs(a-b)<tol,"$a != $b (tol $tol)")
private val k=Intrinsics(800.0,800.0,320.0,240.0,640,480)
private val center=V2(320.0,240.0)
private fun fit(z: Double)=SurfaceFit(z,.04,20,.002,setOf(EvidenceId(DepthOrigin.RAW,1L)),listOf(0.0,0.0,1/z))
fun main() {
    val tests=linkedMapOf<String,()->Unit>(
        "rigid rebase invariance" to ::transforms,
        "bounded frame history and freeze ownership" to ::history,
        "ray-consistent surface fitting" to ::surfaces,
        "correction transactions and held-out validation" to ::transactions,
        "gross depth seed bootstrap with held-out validation" to ::grossDepthBootstrap,
        "no-parallax and systematic uncertainty" to ::uncertainty,
        "presented crop mapping" to ::mapping,
        "input validation" to ::validation
    )
    tests.forEach { (name,test) -> test(); println("PASS: $name") }
    println("PASS: ${tests.size} contract suites, $assertions assertions. Synthetic tests, not physical accuracy.")
}
private fun transforms() {
    val random=Random(20260910)
    fun pose()=Rigid(V3(random.nextGaussian(),random.nextGaussian(),random.nextGaussian()),
        Q.normalized(random.nextGaussian(),random.nextGaussian(),random.nextGaussian(),random.nextGaussian()))
    repeat(1000) {
        val a=pose(); val c=pose(); val rebase=pose(); val point=k.ray(V2(200.0,180.0))*2.0
        val local=(a.inverse()*c).point(point)
        val expected=(rebase*c).point(point)
        val actual=(rebase*a).point(local)
        near((expected-actual).norm(),0.0)
        near((a.inverse().point(a.point(point))-point).norm(),0.0)
    }
}
private fun history() {
    var now=1_000_000_000L; var next=0L; val closed=mutableSetOf<Long>(); val poses=linkedMapOf<Long,Rigid>()
    val factory=AnchorFactory { pose ->
        val id=++next; poses[id]=pose
        object: AnchorHandle {
            override val id=id
            override fun worldFromAnchor()=poses[id]
            override fun close() { verify(closed.add(id),"Double-detached anchor"); poses.remove(id) }
        }
    }
    val ledger=FrameLedger(factory,{now},maxFrames=3,historyNs=100,maxAnchors=2,maxFreezeNs=1000)
    val f=ledger.capture(Rigid.ID,k,1)!!
    verify(ledger.freeze(f.id)==f,"Pin failed")
    repeat(10) { now+=10; ledger.capture(Rigid.ID,k,it.toLong()+2) }
    verify(ledger.frameCount()<=3,"Unbounded history")
    now+=101
    verify(ledger.get(f.id)==f,"Eviction killed frozen frame")
    val g=Rigid(V3(.1,.2,.3),Q.normalized(.01,.02,.03,1.0))
    poses[f.anchorId]=g
    near((ledger.currentWorldFromCamera(f)!!.point(V3(0.0,0.0,2.0))-g.point(V3(0.0,0.0,2.0))).norm(),0.0)
    val second=ledger.capture(Rigid(V3(2.0,0.0,0.0)),k,30)!!
    verify(ledger.freeze(second.id)!=null,"Second pin failed")
    verify(ledger.capture(Rigid(V3(4.0,0.0,0.0)),k,31)==null,"Capacity must reject, not detach live anchors")
    verify(ledger.anchorCount()==2,"Capacity exceeded")
    now=1_000_001_001L
    verify(ledger.get(f.id)==null,"Expired freeze survived")
    verify(ledger.freeze(f.id)==null,"Freeze silently renewed")
    val beforeEpoch=ledger.epoch; ledger.reset()
    verify(ledger.epoch==beforeEpoch+1,"Epoch not advanced")
    verify(ledger.currentWorldFromCamera(second)==null,"Stale reference accepted after reset")
    verify(ledger.anchorCount()==0,"Anchors leaked after reset")
    ledger.close()
}
private fun surfaces() {
    val samples=ArrayList<DepthSample>()
    for(y in -10..10 step 2) for(x in -10..10 step 2) {
        val z=1/(.5+.0005*x-.0003*y)
        samples.add(DepthSample(V2(320.0+x,240.0+y),z,1.0,EvidenceId(DepthOrigin.RAW,1)))
    }
    val result=SurfaceFitter.fit(k,center,samples)!!
    near(result.depth,2.0,1e-6)
    verify(result.conditionalSigma>=.01,"False zero uncertainty")
    verify(result.evidence.size==1,"Spatial points mistaken for independent source frames")
    verify(SurfaceFitter.fit(k,center,samples.filter { it.pixel.x>320 })==null,"One-sided extrapolation accepted")
    verify(SurfaceFitter.fit(k,center,emptyList())==null,"Missing depth invented")
    val edge=samples.map { if(it.pixel.x>320) it.copy(z=4.0) else it }
    verify(SurfaceFitter.fit(k,center,edge)==null,"Foreground/background edge averaged")
    val duplicates=samples+samples.map { it.copy(evidence=EvidenceId(DepthOrigin.SMOOTHED,2)) }
    val d=SurfaceFitter.fit(k,center,duplicates)!!
    near(d.depth,result.depth); verify(d.supportCount==result.supportCount,"Duplicate raw/full supports counted twice")
}
private fun observation(s: AttachmentSnapshot,index: Int,x: Double,truth: Double=2.0,offset: Double=0.0): VisualObservation {
    val pose=Rigid(V3(x,.02*sin(index.toDouble()),0.0))
    val px=k.project(pose.inverse().point(V3(0.0,0.0,truth)))!!
    val timestamp=1_000_000_000L+index*100_000_000L
    return VisualObservation(index.toLong()+1,s.root.epoch,s.root.anchorId,s.generation,timestamp,timestamp,
        pose,k,V2(px.x+offset,px.y),30,.2,.3,.5)
}
private fun transactions() {
    var now=1_000_000_000L
    val policy=LockPolicy(assumedCommonTranslationSigmaM=.001)
    val engine=AttachmentEngine({now},policy)
    val root=RootReference(1,1,1,1,Rigid.ID,k,center)
    val s=engine.create(root,fit(2.06))
    var proposal: LockProposal?=null
    for(i in 1..6) {
        val o=observation(s,i,i*.1); now=o.capturedNs
        engine.offer(s.id,o)?.let { proposal=it }
        verify(engine.offer(s.id,o)==null,"Repeated frame re-counted")
    }
    val p=proposal ?: error("Expected supported synthetic proposal")
    verify(abs(p.depthM-2)<.02,"Geometric improvement missing: ${p.depthM}")
    val same=p.observations.last()
    verify(!engine.commit(p,same).accepted,"Training observation accepted as held-out")
    val bad=observation(s,8,.68,offset=25.0); now=bad.capturedNs
    verify(!engine.commit(p,bad).accepted,"Bad held-out accepted")
    val good=observation(s,9,.70); now=good.capturedNs
    verify(!engine.commit(p.copy(depthM=2.7),good).accepted,"Forged proposal accepted")
    val accepted=engine.commit(p,good)
    verify(accepted.accepted,"Good held-out refused: ${accepted.reason}")
    val committed=checkNotNull(accepted.snapshot)
    verify(committed.generation==2L,"Generation not incremented")
    verify(committed.root==root,"Root mutated by learning")
    verify(!engine.commit(p,good).accepted,"Stale generation accepted twice")
    verify(engine.offer(s.id,observation(s,10,.72))==null,"Old worker generation accepted")
    engine.remove(s.id)
    verify(!engine.commit(p,good).accepted,"Removed attachment resurrected")
}
private fun grossDepthBootstrap() {
    var now=1_000_000_000L
    val policy=LockPolicy(assumedCommonTranslationSigmaM=.001,bootstrapMaxCorrectionM=3.0)
    val engine=AttachmentEngine({now},policy)
    val root=RootReference(1,1,1,1,Rigid.ID,k,center)
    val seeded=engine.create(root,fit(3.0))
    var proposal: LockProposal?=null
    for(i in 1..6) {
        val o=observation(seeded,i,i*.03,truth=1.0); now=o.capturedNs
        engine.offer(seeded.id,o)?.let { proposal=it }
    }
    val p=proposal ?: error("Grossly wrong seed never produced a multi-view bootstrap proposal")
    verify(p.bootstrap,"First gross-depth correction was not treated as bootstrap")
    verify(abs(p.depthM-1.0)<.05,"Bootstrap stayed near wrong 3m seed instead of 1m visual geometry: ${p.depthM}")
    verify(abs(p.depthM-3.0)>1.0,"Bootstrap did not make the required large correction")
    val heldOut=observation(seeded,9,.24,truth=1.0); now=heldOut.capturedNs
    val accepted=engine.commit(p,heldOut)
    verify(accepted.accepted,"Held-out visual frame rejected valid large bootstrap: ${accepted.reason}")
    val locked=checkNotNull(accepted.snapshot)
    verify(locked.state==LockState.GEOMETRY_SUPPORTED,"Bootstrap did not enter geometry-supported state")
    verify(abs(locked.depthM-1.0)<.05,"Committed depth is wrong after bootstrap: ${locked.depthM}")
}
private fun uncertainty() {
    val root=RootReference(1,1,1,1,Rigid.ID,k,center)
    val s=AttachmentSnapshot(1,1,root,2.06,.04,LockState.UNVERIFIED,0.0)
    val still=(1..6).map { observation(s,it,0.0).copy(cameraInAnchor=Rigid.ID,pixel=center) }
    verify(RayRefiner.propose(s,still,LockPolicy())==null,"No-parallax correction accepted")
    val good=(1..6).map { observation(s,it,it*.1) }
    verify(RayRefiner.propose(s,good.map { it.copy(epoch=99) },LockPolicy())==null,"Wrong epoch used")
    verify(RayRefiner.propose(s,good,LockPolicy(assumedCommonTranslationSigmaM=.5))==null,"Huge correlated uncertainty ignored")
    val engine=AttachmentEngine({9_000_000_000L})
    val attachment=engine.create(root,fit(2.06))
    good.forEach { verify(engine.offer(attachment.id,it)==null,"Expired observation accepted") }
}
private fun validation() {
    fun invalid(block: ()->Unit) { var caught=false; try { block() } catch(_: IllegalArgumentException) { caught=true }; verify(caught,"Invalid input accepted") }
    invalid { V2(Double.NaN,0.0) }; invalid { Q(0.0,0.0,0.0,0.0) }
    invalid { Intrinsics(0.0,1.0,0.0,0.0,1,1) }; invalid { EvidenceId(DepthOrigin.RAW,0) }
    invalid { DepthSample(center,-1.0,.5,EvidenceId(DepthOrigin.RAW,1)) }
}

private fun mapping() {
    val expected=listOf(V2(0.0,0.0),V2(0.0,479.0),V2(639.0,479.0),V2(639.0,0.0))
    for((i,r) in listOf(0,90,180,270).withIndex()) {
        val m=PresentedImage.upright(k,r)
        verify(m.sensorPixel(V2(0.0,0.0),k)==expected[i],"Wrong rotated origin")
        verify(m.sensorPixel(V2(-.01,0.0),k)==null,"Outside normalized crop accepted")
        val centerPixel=m.sensorPixel(V2(.5,.5),k)!!
        near(centerPixel.x,319.5); near(centerPixel.y,239.5)
    }
}
