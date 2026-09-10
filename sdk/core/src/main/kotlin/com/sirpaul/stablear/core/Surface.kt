package com.sirpaul.stablear.core

import kotlin.math.*

enum class DepthOrigin { RAW, SMOOTHED, POINT_CLOUD }
data class EvidenceId(val origin: DepthOrigin,val sourceTimestampNs: Long) {
    init { require(sourceTimestampNs>0) }
}
/** sigma is a declared noise model, NOT a conversion of the confidence byte into metres. */
data class DepthSample(val pixel: V2,val z: Double,val confidence: Double,val evidence: EvidenceId) {
    init { require(z.isFinite() && z>0); require(confidence in 0.0..1.0) }
}
data class SurfaceFit(val depth: Double,val conditionalSigma: Double,val supportCount: Int,
    val residualM: Double,val evidence: Set<EvidenceId>,val inversePlane: List<Double>)

/** One perspective-correct surface model for both placement and verification. */
object SurfaceFitter {
    /**
     * Conservative research fit. The clicked pixel must be surrounded by coherent metric supports;
     * this is intentionally strict and remains the default SDK contract.
     */
    fun fit(k: Intrinsics,click: V2,samples: List<DepthSample>,radiusPx: Double=max(12.0,k.width*.025)): SurfaceFit? {
        if(!k.contains(click) || !radiusPx.isFinite() || radiusPx<1) return null
        // Prefer raw measurements; never count full/raw at the same pixel twice.
        val near=samples.filter { it.confidence>=.5 && it.z in .15..8.0 &&
            (it.pixel-click).norm()<=radiusPx }.sortedWith(compareBy<DepthSample> {
            (it.pixel-click).norm() }.thenBy { it.evidence.origin.ordinal })
            .distinctBy { Pair((it.pixel.x/2).toInt(),(it.pixel.y/2).toInt()) }.take(96)
        if(near.size<6 || (near.first().pixel-click).norm()>radiusPx*.6) return null
        val seed=median(near.take(5).map { it.z }); val limit=max(.04,seed*.04)
        if(near.take(6).count { abs(it.z-seed)>2*limit }>=2) return null
        val points=near.filter { abs(it.z-seed)<limit }
        if(points.size<6 || points.size<near.size*.65) return null
        // Refuse extrapolation: click must be enclosed angularly by the supports.
        val angles=points.map { atan2(it.pixel.y-click.y,it.pixel.x-click.x) }.sorted()
        val gaps=angles.zipWithNext { a,b -> b-a }+(angles.first()+2*PI-angles.last())
        if(gaps.max()>PI+.01) return null
        return fitPlane(click,radiusPx,points,seed,limit,maxResidualRatio=.015)
    }

    /**
     * Interactive placement fit for a user-selected physical edge/corner.
     *
     * Raw AR depth is sparse, so a real click can legitimately have coherent depth supports only
     * on one side. This fit permits a SMALL extrapolation from the nearest coherent metric cluster
     * instead of requiring the click to be enclosed. It never invents a fixed-distance plane:
     * there must still be >=4 measured supports, a close measured sample and a low-residual depth
     * cluster. Competing depth layers are excluded by seeding from the closest measurement.
     */
    fun fitInteractive(k: Intrinsics,click: V2,samples: List<DepthSample>,
        radiusPx: Double=max(24.0,k.width*.04)): SurfaceFit? {
        if(!k.contains(click) || !radiusPx.isFinite() || radiusPx<1) return null
        val valid=samples.filter { it.confidence>=.5 && it.z in .15..8.0 }
        if(valid.isEmpty()) return null
        val near=valid.filter { (it.pixel-click).norm()<=radiusPx }
            .sortedWith(compareBy<DepthSample> { (it.pixel-click).norm() }
                .thenBy { it.evidence.origin.ordinal })
            .distinctBy { Pair((it.pixel.x/2).toInt(),(it.pixel.y/2).toInt()) }
            .take(128)
        if(near.size<4) return null
        // Bound how far a click may be extrapolated to the nearest actual metric support.
        val nearestPx=(near.first().pixel-click).norm()
        val maxSnapPx=min(radiusPx*.60,max(14.0,k.width*.020))
        if(nearestPx>maxSnapPx) return null

        // The nearest measured layer is the user's most local evidence. Do not let a farther
        // foreground/background layer win just because it has more samples.
        val seed=near.first().z
        val limit=max(.035,seed*.035)
        val points=near.filter { abs(it.z-seed)<=limit }
        if(points.size<4) return null
        // Require several close supports from this same layer, not a remote cluster.
        if(points.take(4).last().let { (it.pixel-click).norm() } > radiusPx*.80) return null

        // A genuine depth discontinuity may contain a second layer. That is fine only when the
        // selected nearest layer itself has enough coherent evidence and clearly dominates the
        // very local neighborhood.
        val local=near.take(min(10,near.size))
        val sameLocal=local.count { abs(it.z-seed)<=limit }
        if(sameLocal<3) return null

        return fitPlane(click,radiusPx,points,seed,limit*1.5,maxResidualRatio=.025)
            ?: constantDepthFallback(points,seed,limit)
    }

    private fun fitPlane(click: V2,radiusPx: Double,points: List<DepthSample>,seed: Double,
        limit: Double,maxResidualRatio: Double): SurfaceFit? {
        val rows=points.map { doubleArrayOf((it.pixel.x-click.x)/radiusPx,(it.pixel.y-click.y)/radiusPx,1.0) }
        var weights=DoubleArray(points.size) { i -> points[i].confidence/(1+(points[i].pixel-click).norm().pow(2)/64) }
        var fit: DoubleArray?=null
        repeat(5) {
            val a=Array(3) { DoubleArray(3) }; val b=DoubleArray(3)
            for(i in points.indices) for(r in 0..2) {
                b[r]+=weights[i]*rows[i][r]/points[i].z
                for(c in 0..2) a[r][c]+=weights[i]*rows[i][r]*rows[i][c]
            }
            fit=solve3(a,b) ?: return null
            val f=fit!!
            val residual=points.indices.map { i -> abs(rows[i].indices.sumOf { j -> rows[i][j]*f[j] }-1/points[i].z) }
            val scale=max(.0005,1.4826*median(residual))
            weights=DoubleArray(points.size) { i -> points[i].confidence/(1+(points[i].pixel-click).norm().pow(2)/64)*min(1.0,1.5*scale/max(1e-12,residual[i])) }
        }
        val f=fit!!; if(f[2]<=0) return null
        val z=1/f[2]; if(z !in .15..8.0 || abs(z-seed)>limit) return null
        val errors=points.indices.map { i ->
            val inv=rows[i].indices.sumOf { j -> rows[i][j]*f[j] }
            if(inv<=0) Double.POSITIVE_INFINITY else abs(1/inv-points[i].z)
        }
        val error=median(errors)
        if(error>max(.015,z*maxResidualRatio)) return null
        val floor=max(.010,z*.01)
        return SurfaceFit(z,max(floor,1.4826*error),points.size,error,
            points.map { it.evidence }.toSet(),f.toList())
    }

    /**
     * Degenerate one-sided geometry can make a 3-parameter plane singular. A robust constant-depth
     * estimate is allowed only for a very coherent measured cluster; it is still metric evidence,
     * not a guessed distance.
     */
    private fun constantDepthFallback(points: List<DepthSample>,seed: Double,limit: Double): SurfaceFit? {
        if(points.size<4) return null
        val depths=points.map { it.z }.sorted()
        val z=median(depths)
        val errors=depths.map { abs(it-z) }
        val error=median(errors)
        val spread=depths.last()-depths.first()
        if(abs(z-seed)>limit || spread>max(.045,z*.035) || error>max(.018,z*.018)) return null
        val floor=max(.012,z*.012)
        return SurfaceFit(z,max(floor,1.4826*error),points.size,error,
            points.map { it.evidence }.toSet(),listOf(0.0,0.0,1.0/z))
    }
}
