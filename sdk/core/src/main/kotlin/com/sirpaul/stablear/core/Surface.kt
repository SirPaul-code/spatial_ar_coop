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
    private data class Layer(
        val points: List<DepthSample>,
        val depth: Double,
        val nearestPx: Double,
        val closeCount: Int,
        val rawCount: Int,
        val cloudCount: Int,
        val smoothedCount: Int,
    ) {
        val strongCount get() = rawCount + cloudCount
    }

    /** Conservative SDK fit: the clicked pixel must be enclosed by one coherent metric layer. */
    fun fit(k: Intrinsics,click: V2,samples: List<DepthSample>,radiusPx: Double=max(12.0,k.width*.025)): SurfaceFit? {
        if(!k.contains(click) || !radiusPx.isFinite() || radiusPx<1) return null
        val near=dedupe(samples.filter { it.confidence>=.5 && it.z in .15..8.0 &&
            (it.pixel-click).norm()<=radiusPx })
            .sortedWith(compareBy<DepthSample> { (it.pixel-click).norm() }.thenBy(::originRank)).take(96)
        if(near.size<6 || (near.first().pixel-click).norm()>radiusPx*.6) return null
        val seed=median(near.take(5).map { it.z }); val limit=max(.04,seed*.04)
        if(near.take(6).count { abs(it.z-seed)>2*limit }>=2) return null
        val points=near.filter { abs(it.z-seed)<limit }
        if(points.size<6 || points.size<near.size*.65) return null
        val angles=points.map { atan2(it.pixel.y-click.y,it.pixel.x-click.x) }.sorted()
        val gaps=angles.zipWithNext { a,b -> b-a }+(angles.first()+2*PI-angles.last())
        if(gaps.max()>PI+.01) return null
        return fitPlane(click,radiusPx,points,seed,limit,maxResidualRatio=.015)
    }

    /**
     * Interactive user placement. Candidate layers and veto layers are intentionally different:
     * >=4 coherent supports are required to CREATE a surface, while >=3 nearby supports from a
     * competing separated depth are enough to VETO an ambiguous choice. This prevents a thin
     * foreground/background boundary from disappearing merely because one side has only three
     * samples in the exact frame.
     */
    fun fitInteractive(k: Intrinsics,click: V2,samples: List<DepthSample>,
        radiusPx: Double=max(24.0,k.width*.04)): SurfaceFit? {
        if(!k.contains(click) || !radiusPx.isFinite() || radiusPx<1) return null
        val valid=samples.filter { it.confidence>=.5 && it.z in .15..8.0 }
        if(valid.isEmpty()) return null
        val near=dedupe(valid.filter { (it.pixel-click).norm()<=radiusPx })
            .sortedWith(compareBy<DepthSample> { (it.pixel-click).norm() }.thenBy(::originRank))
            .take(192)
        if(near.size<4) return null

        val maxSnapPx=min(radiusPx*.60,max(14.0,k.width*.020))
        val allLocalLayers=depthLayers(near,click,maxSnapPx)
            .filter { it.nearestPx<=maxSnapPx && it.closeCount>=3 && it.points.size>=3 }
            .sortedBy { it.depth }
        val fitLayers=allLocalLayers.filter { it.points.size>=4 }
        if(fitLayers.isEmpty()) return null

        val chosen=chooseInteractiveLayer(fitLayers,allLocalLayers,maxSnapPx) ?: return null
        val seed=chosen.depth
        val limit=max(.035,seed*.035)
        val points=chosen.points.filter { abs(it.z-seed)<=limit*1.35 }
            .sortedBy { (it.pixel-click).norm() }.take(96)
        if(points.size<4) return null

        return fitPlane(click,radiusPx,points,seed,limit*1.5,maxResidualRatio=.025)
            ?: constantDepthFallback(points,seed,limit*1.5)
    }

    /**
     * Keep one raster sample per small pixel cell, but preserve an independent point-cloud sample
     * in the same cell. RAW beats SMOOTHED because the latter can bleed across object boundaries.
     */
    private fun dedupe(samples: List<DepthSample>): List<DepthSample> {
        val ordered=samples.sortedWith(compareBy<DepthSample>(::originRank).thenByDescending { it.confidence })
        val seenRaster=HashSet<Pair<Int,Int>>()
        val seenCloud=HashSet<Pair<Int,Int>>()
        val out=ArrayList<DepthSample>(ordered.size)
        for(s in ordered) {
            val cell=Pair((s.pixel.x/2).toInt(),(s.pixel.y/2).toInt())
            val accepted=if(s.evidence.origin==DepthOrigin.POINT_CLOUD) seenCloud.add(cell) else seenRaster.add(cell)
            if(accepted) out.add(s)
        }
        return out
    }

    private fun originRank(sample: DepthSample)=when(sample.evidence.origin) {
        DepthOrigin.RAW -> 0
        DepthOrigin.POINT_CLOUD -> 1
        DepthOrigin.SMOOTHED -> 2
    }

    private fun depthLayers(near: List<DepthSample>,click: V2,maxSnapPx: Double): List<Layer> {
        val buckets=ArrayList<MutableList<DepthSample>>()
        for(s in near.sortedBy { it.z }) {
            var best: MutableList<DepthSample>?=null
            var bestDelta=Double.POSITIVE_INFINITY
            for(bucket in buckets) {
                val z=median(bucket.map { it.z })
                val delta=abs(s.z-z)
                val tolerance=max(.04,min(s.z,z)*.04)
                if(delta<=tolerance && delta<bestDelta) { best=bucket;bestDelta=delta }
            }
            if(best==null) buckets.add(mutableListOf(s)) else best.add(s)
        }
        return buckets.map { bucket ->
            val depth=median(bucket.map { it.z })
            val distances=bucket.map { (it.pixel-click).norm() }
            Layer(
                points=bucket.toList(), depth=depth, nearestPx=distances.min(),
                closeCount=distances.count { it<=maxSnapPx*1.5 },
                rawCount=bucket.count { it.evidence.origin==DepthOrigin.RAW },
                cloudCount=bucket.count { it.evidence.origin==DepthOrigin.POINT_CLOUD },
                smoothedCount=bucket.count { it.evidence.origin==DepthOrigin.SMOOTHED },
            )
        }
    }

    private fun chooseInteractiveLayer(fitLayers: List<Layer>,allLocalLayers: List<Layer>,maxSnapPx: Double): Layer? {
        if(fitLayers.isEmpty()) return null

        // Independent RAW/point-cloud evidence establishes foreground ownership. Among such layers,
        // the nearest physical layer wins because it occludes every deeper candidate at this pixel.
        val strongForeground=fitLayers.firstOrNull { layer ->
            layer.nearestPx<=maxSnapPx && layer.closeCount>=3 &&
                (layer.cloudCount>=1 || layer.rawCount>=2 || layer.strongCount>=3)
        }
        if(strongForeground!=null) return strongForeground

        if(fitLayers.size==1) {
            val only=fitLayers.first()
            // Three close samples are not enough to fit a rival surface, but they ARE enough to
            // prove that the click sits on a depth discontinuity. Do not silently ignore that veto.
            val rival=allLocalLayers.any { other ->
                other !== only && abs(other.depth-only.depth)>max(.06,min(other.depth,only.depth)*.06) &&
                    other.nearestPx<=only.nearestPx+max(4.0,maxSnapPx*.18)
            }
            return if(rival) null else only
        }

        // Without independent foreground evidence, separated layers that are comparably close to
        // the click are intrinsically ambiguous. Density is NOT ownership; a huge monitor must not
        // win over a thin object merely because it has more smoothed pixels.
        val byLocal=allLocalLayers.sortedBy { it.nearestPx }
        val first=byLocal[0]
        val separatedRival=byLocal.drop(1).firstOrNull { other ->
            abs(other.depth-first.depth)>max(.06,min(other.depth,first.depth)*.06) &&
                other.nearestPx<=first.nearestPx+max(4.0,maxSnapPx*.18)
        }
        if(separatedRival!=null) return null

        return fitLayers.minBy { it.nearestPx }
    }

    private fun fitPlane(click: V2,radiusPx: Double,points: List<DepthSample>,seed: Double,
        limit: Double,maxResidualRatio: Double): SurfaceFit? {
        val rows=points.map { doubleArrayOf((it.pixel.x-click.x)/radiusPx,(it.pixel.y-click.y)/radiusPx,1.0) }
        var weights=DoubleArray(points.size) { i ->
            val originWeight=when(points[i].evidence.origin) {
                DepthOrigin.RAW -> 1.15
                DepthOrigin.POINT_CLOUD -> 1.20
                DepthOrigin.SMOOTHED -> .80
            }
            originWeight*points[i].confidence/(1+(points[i].pixel-click).norm().pow(2)/64)
        }
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
            weights=DoubleArray(points.size) { i ->
                val originWeight=when(points[i].evidence.origin) {
                    DepthOrigin.RAW -> 1.15
                    DepthOrigin.POINT_CLOUD -> 1.20
                    DepthOrigin.SMOOTHED -> .80
                }
                originWeight*points[i].confidence/(1+(points[i].pixel-click).norm().pow(2)/64)*
                    min(1.0,1.5*scale/max(1e-12,residual[i]))
            }
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
