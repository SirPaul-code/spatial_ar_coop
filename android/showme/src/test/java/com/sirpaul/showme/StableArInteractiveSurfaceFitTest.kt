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
    private val evidence=EvidenceId(DepthOrigin.SMOOTHED,123_000_000L)

    private fun s(x:Double,y:Double,z:Double=1.20)=DepthSample(V2(x,y),z,.8,evidence)

    @Test fun interactiveFitAcceptsCoherentOneSidedMeasuredDepth() {
        // All supports are to the right of the click, which intentionally fails the research
        // fitter's angular-enclosure gate but is common when tapping a physical edge.
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

    @Test fun interactiveFitStillRejectsNoMetricEvidence() {
        assertNull(SurfaceFitter.fitInteractive(k,click,emptyList()))
    }

    @Test fun interactiveFitRejectsCompetingNearestDepthLayers() {
        // The nearest neighborhood alternates between two incompatible surfaces. Do not guess.
        val samples=listOf(
            s(646.0,355.0,.70), s(647.0,365.0,1.60), s(650.0,350.0,.72),
            s(651.0,370.0,1.58), s(655.0,357.0,.71), s(656.0,364.0,1.61),
            s(670.0,350.0,.70), s(672.0,370.0,.71),
        )
        assertNull(SurfaceFitter.fitInteractive(k,click,samples))
    }
}
