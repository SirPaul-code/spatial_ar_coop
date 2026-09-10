package com.sirpaul.showme

import com.sirpaul.stablear.core.*
import org.junit.Assert.*
import org.junit.Test

class StableArIntegrationTest {
    private fun binding(id:Long,epoch:Int,source:Long,captured:Long,value:String="sample") =
        StableVideoBinding(id,epoch,source,source*1_000_000L,captured,value)

    @Test fun exactVideoIdentityAcceptsLiveAndDelayedFramesButRejectsWrongEpochAndStaleFrames() {
        var now=1_000_000_000L
        val frames=StableArFrameRegistry<String>({now},historyNs=4_000_000_000L)
        frames.bind(binding(101,7,9001,now))
        assertEquals("sample",frames.resolve(101,7)?.value)
        now+=3_900_000_000L
        assertEquals("sample",frames.resolve(101,7)?.value)
        assertNull(frames.resolve(101,8))
        assertNull(frames.resolve(999,7))
        now+=200_000_000L
        assertNull(frames.resolve(101,7))
    }

    @Test fun frozenFrameHasBoundedLeaseAndRepeatedFreezeDoesNotRenewIt() {
        var now=1_000_000_000L
        val frames=StableArFrameRegistry<String>({now},historyNs=4_000_000_000L,freezeNs=60_000_000_000L)
        frames.bind(binding(11,3,77,now))
        assertNotNull(frames.freeze(11,3))
        now+=30_000_000_000L
        assertNotNull(frames.freeze(11,3))
        assertNotNull(frames.resolve(11,3))
        now+=30_100_000_000L
        assertNull(frames.resolve(11,3))
        assertNull(frames.frozenVideoFrameId())
    }

    @Test fun unfreezeAndWorldClearInvalidateHistoricalAuthority() {
        var now=1_000_000_000L
        val frames=StableArFrameRegistry<String>({now})
        frames.bind(binding(12,4,88,now));assertNotNull(frames.freeze(12,4))
        assertEquals(12L,frames.unfreeze()!!.videoFrameId)
        now+=4_100_000_000L;assertNull(frames.resolve(12,4))
        frames.bind(binding(13,5,89,now));frames.clear()
        assertNull(frames.resolve(13,5));assertEquals(0,frames.frameCount())
    }

    @Test fun oneCoherentStableRootPreservesPinArrowDrawingAndCircleGeometryExactly() {
        val pin=listOf(floatArrayOf(1f,2f,3f))
        val arrow=listOf(floatArrayOf(0f,0f,1f),floatArrayOf(2f,0f,1f))
        val drawing=listOf(floatArrayOf(-.2f,.3f,1.2f),floatArrayOf(.4f,.7f,1.4f),floatArrayOf(.9f,.1f,1.1f))
        val circle=listOf(floatArrayOf(0f,1f,2f),floatArrayOf(1f,0f,2f),floatArrayOf(0f,-1f,2f),floatArrayOf(-1f,0f,2f))
        val pinRoot=floatArrayOf(1f,2f,3f)
        assertArrayEquals(floatArrayOf(0f,0f,0f),StableArPlacementMath.relativeOffsets(pin,pinRoot)!![0],1e-6f)

        for(shape in listOf(arrow,drawing,circle)) {
            // Deliberately use a root that is NOT the 3D centroid. The root comes from StableAR's
            // exact historical centroid pixel reconstruction, not from averaging world vertices.
            val stableRoot=floatArrayOf(.37f,-.21f,1.83f)
            val offsets=StableArPlacementMath.relativeOffsets(shape,stableRoot)!!
            for(i in shape.indices) {
                val reconstructed=FloatArray(3){axis->stableRoot[axis]+offsets[i][axis]}
                assertArrayEquals(shape[i],reconstructed,1e-6f)
            }
            for(i in shape.indices)for(j in shape.indices) {
                assertEquals(ShowMeGeometry.distance(shape[i],shape[j]).toDouble(),
                    ShowMeGeometry.distance(offsets[i],offsets[j]).toDouble(),1e-6)
            }
        }
        val root=StableArPlacementMath.normalizedRoot(listOf(doubleArrayOf(.2,.3),doubleArrayOf(.8,.7)))!!
        assertArrayEquals(doubleArrayOf(.5,.5),root,1e-12)
    }

    @Test fun removedAttachmentCannotBeResurrectedAndOcclusionRetainsWorldHypothesis() {
        var now=2_000_000_000L
        val engine=AttachmentEngine({now})
        val k=Intrinsics(500.0,500.0,320.0,240.0,640,480)
        val root=RootReference(1,1,1,1_000_000_000L,Rigid.ID,k,V2(320.0,240.0))
        val fit=SurfaceFit(1.0,.01,12,.005,setOf(EvidenceId(DepthOrigin.RAW,1_000_000_000L)),listOf(0.0,0.0,1.0))
        val attachment=engine.create(root,fit)
        val point=attachment.pointInAnchor()
        engine.visibility(attachment.id,false)
        val occluded=engine.snapshot(attachment.id)!!
        assertEquals(LockState.OCCLUDED,occluded.state)
        assertEquals(point.x,occluded.pointInAnchor().x,1e-12)
        assertEquals(point.y,occluded.pointInAnchor().y,1e-12)
        assertEquals(point.z,occluded.pointInAnchor().z,1e-12)
        engine.remove(attachment.id)
        assertNull(engine.snapshot(attachment.id))
        val stale=VisualObservation(2,1,1,attachment.generation,1_100_000_000L,now,Rigid.ID,k,
            V2(320.0,240.0),30,.1,.1)
        assertNull(engine.offer(attachment.id,stale))
    }

    @Test fun legacyModeBypassesStableArLeaseWithoutRevertingCode() {
        val state=ShowMeSession();state.begin();state.stableArEnabled=false
        assertTrue(state.requestSpatialFreeze(123,state.epoch).get())
        assertTrue(state.requestSpatialUnfreeze().get())
        assertNull(state.pollSpatialLease())
        state.stableArEnabled=true;state.tracking=true
        val pending=state.requestSpatialFreeze(123,state.epoch)
        assertFalse(pending.isDone)
        val command=state.pollSpatialLease()!!
        assertEquals(SpatialLeaseAction.FREEZE,command.action)
        command.answer.complete(false);assertFalse(pending.get())
    }

    @Test fun helperDisconnectQueuesUnfreezeAndAReplacementHelperCanReconnect() {
        val state=ShowMeSession();state.begin();state.tracking=true
        assertTrue(state.join("helper0001","Alice"))
        val freeze=state.requestSpatialFreeze(44,state.epoch)
        val freezeCommand=state.pollSpatialLease()!!
        assertEquals(SpatialLeaseAction.FREEZE,freezeCommand.action)
        freezeCommand.answer.complete(true);assertTrue(freeze.get())
        state.leave("helper0001")
        val release=state.pollSpatialLease()!!
        assertEquals(SpatialLeaseAction.UNFREEZE,release.action)
        release.answer.complete(true)
        assertTrue(state.join("helper0002","Bob"))
    }

    @Test fun worldResetRejectsPendingAndOldEpochSpatialWork() {
        val state=ShowMeSession();state.begin();state.tracking=true
        val oldEpoch=state.epoch
        val pending=state.requestSpatialFreeze(22,oldEpoch)
        assertFalse(pending.isDone)
        state.resetWorld()
        assertTrue(state.epoch>oldEpoch)
        assertTrue(pending.isDone);assertFalse(pending.get())
        assertFalse(state.requestSpatialFreeze(22,oldEpoch).get())
    }
}
