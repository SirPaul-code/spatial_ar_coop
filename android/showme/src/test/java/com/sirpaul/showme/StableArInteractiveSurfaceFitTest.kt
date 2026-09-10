package com.sirpaul.showme

import com.sirpaul.stablear.core.DepthOrigin
import com.sirpaul.stablear.core.DepthSample
import com.sirpaul.stablear.core.EvidenceId
import com.sirpaul.stablear.core.Intrinsics
import com.sirpaul.stablear.core.SurfaceFitter
import com.sirpaul.stablear.core.V2
import org.junit.Assert.*
import org.junit.Test

class StableArInteractiveSurfaceFitTest {
    private val k=Intrinsics(900.0,900.0,640.0,360.0,1280,720)
    private val click=V2(640.0,360.0)
    private val smooth=EvidenceId(DepthOrigin.SMOOTHED,123_000_000L)
    private val raw=EvidenceId(DepthOrigin.RAW,123_000_001L)
    private val cloud=EvidenceId(DepthOrigin.POINT_CLOUD,123_000_002L)

    private fun s(x:Double,y:Double,z:Double=1.20,e:EvidenceId=smooth,c:Double=.8)=
        DepthSample(V2(x,y),z,c,e)

    @Test fun interactiveFitAcceptsCoherentOneSidedMeasuredDepth() {
        val samples=listOf(
            s(646.0,350.0,1.198), s(648.0,360.0,1.200), s(650.0,370.0,1.202),
            s(660.0,346.0,1.199), s(662.0,362.0,1.201), s(664.0,376.0,1.203),
            s(676.0,354.0,1.200), s(678.0,370.0,1.202),
        )
        assertNull("strict fit should remain conservative at a one-sided edge",
            SurfaceFitter.fit(k,click,samples))
        val fit=SurfaceFitter.fitInteractive(k,click,samples)
        assertNotNull("interactive placement should use the coherent measured edge cluster",fit)
        assertEquals(1.20,fit!!.depth,.04)
        assertTrue(fit.supportCount>=4)
    }

    @Test fun foregroundMetricLayerBeatsDenseBackgroundPlane() {
        // Reproduces the physical PCB/ECU-in-front-of-monitor failure: the background is denser and
        // even has samples a couple of pixels closer to the click, so a naive nearest/dominant fit
        // selects ~1.8 m. Independent RAW/point-cloud support proves a ~0.75 m foreground layer.
        val background=listOf(
            s(640.0,356.0,1.80),s(644.0,360.0,1.80),s(640.0,364.0,1.81),s(636.0,360.0,1.79),
            s(644.0,364.0,1.80),s(636.0,356.0,1.81),s(648.0,352.0,1.79),s(632.0,368.0,1.80),
            s(652.0,360.0,1.80),s(628.0,360.0,1.80),
        )
        val foreground=listOf(
            s(646.0,360.0,.75,raw,.95),
            s(648.0,358.0,.74,cloud,.92),
            s(648.0,364.0,.76,raw,.90),
            s(650.0,360.0,.75,cloud,.94),
            s(652.0,356.0,.75,raw,.91),
            s(652.0,366.0,.76,cloud,.90),
        )
        val all=background+foreground
        val strict=SurfaceFitter.fit(k,click,all)
        assertNotNull("the conservative fitter intentionally demonstrates the old background trap",strict)
        assertEquals(1.80,strict!!.depth,.08)

        val interactive=SurfaceFitter.fitInteractive(k,click,all)
        assertNotNull("interactive fit must recover the visible foreground layer",interactive)
        assertEquals(.75,interactive!!.depth,.05)
        assertTrue(interactive.evidence.any { it.origin==DepthOrigin.RAW || it.origin==DepthOrigin.POINT_CLOUD })
    }

    @Test fun interactiveFitStillRejectsNoMetricEvidence() {
        assertNull(SurfaceFitter.fitInteractive(k,click,emptyList()))
    }

    @Test fun interactiveFitRejectsCompetingNearestDepthLayersWithoutIndependentOwnership() {
        val samples=listOf(
            s(646.0,355.0,.70), s(647.0,365.0,1.60), s(650.0,350.0,.72),
            s(651.0,370.0,1.58), s(655.0,357.0,.71), s(656.0,364.0,1.61),
            s(670.0,350.0,.70), s(672.0,370.0,.71),
        )
        assertNull(SurfaceFitter.fitInteractive(k,click,samples))
    }
}
