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

    /**
     * Conservative research fit. The clicked pixel must be surrounded by coherent metric supports;
     * this is intentionally strict and remains available as the conservative SDK contract.
     */
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
        // Refuse extrapolation: click must be enclosed angularly by the supports.
        val angles=points.map { atan2(it.pixel.y-click.y,it.pixel.x-click.x) }.sorted()
        val gaps=angles.zipWithNext { a,b -> b-a }+(angles.first()+2*PI-angles.last())
        if(gaps.max()>PI+.01) return null
        return fitPlane(click,radiusPx,points,seed,limit,maxResidualRatio=.015)
    }

    /**
     * Interactive user placement. A thin foreground object must not lose to a larger background
     * plane merely because the background contributes more depth pixels.
     *
     * We explicitly separate local metric samples into depth layers. The front-most plausible layer
     * wins only when it has real nearby support (preferably RAW or ARCore point-cloud evidence).
     * If foreground/background ownership is genuinely ambiguous we reject rather than silently pin
     * to the background. No fixed-distance or monocular guessed plane is ever introduced here.
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
        val layers=depthLayers(near,click,maxSnapPx)
            .filter { it.points.size>=4 && it.nearestPx<=maxSnapPx && it.closeCount>=3 }
            .sortedBy { it.depth }
        if(layers.isEmpty()) return null

        val chosen=chooseInteractiveLayer(layers,maxSnapPx) ?: return null
        val seed=chosen.depth
        val limit=max(.035,seed*.035)
        val points=chosen.points.filter { abs(it.z-seed)<=limit*1.35 }
            .sortedBy { (it.pixel-click).norm() }
            .take(96)
        if(points.size<4) return null

        // At an exact depth discontinuity, do not let a background layer win just because it is
        // dense. Conversely, if two equally local strong layers compete and neither owns the click,
        // fail closed so multi-view visual refinement can be requested instead of making up Z.
        val competitors=layers.filter { it !== chosen && abs(it.depth-chosen.depth)>max(.06,chosen.depth*.06) }
        val equallyLocal=competitors.filter { it.nearestPx<=chosen.nearestPx+3.0 && it.strongCount>=2 }
        if(equallyLocal.isNotEmpty() && chosen.strongCount<2 && chosen.nearestPx>maxSnapPx*.35) return null

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
                points=bucket.toList(),
                depth=depth,
                nearestPx=distances.min(),
                closeCount=distances.count { it<=maxSnapPx*1.5 },
                rawCount=bucket.count { it.evidence.origin==DepthOrigin.RAW },
                cloudCount=bucket.count { it.evidence.origin==DepthOrigin.POINT_CLOUD },
                smoothedCount=bucket.count { it.evidence.origin==DepthOrigin.SMOOTHED },
            )
        }
    }

    private fun chooseInteractiveLayer(layers: List<Layer>,maxSnapPx: Double): Layer? {
        if(layers.isEmpty()) return null
        if(layers.size==1) return layers.first()

        // Physical visibility rule: when a nearer layer has convincing local RAW/point-cloud support,
        // it occludes a farther plane. This is exactly the freestanding-PCB-in-front-of-monitor case.
        val strongFront=layers.firstOrNull { layer ->
            layer.nearestPx<=maxSnapPx && layer.closeCount>=3 &&
                (layer.cloudCount>=1 || layer.rawCount>=2 || layer.strongCount>=3)
        }
        if(strongFront!=null) return strongFront

        // If no layer has strong independent evidence, only accept the layer that actually owns the
        // closest image neighborhood. A tie across separated depths is ambiguous and must be rejected.
        val byLocal=layers.sortedWith(compareBy<Layer> { it.nearestPx }.thenByDescending { it.closeCount })
        val first=byLocal[0]
        val second=byLocal.getOrNull(1)
        if(second!=null && abs(first.depth-second.depth)>max(.06,first.depth*.06) &&
            abs(first.nearestPx-second.nearestPx)<=3.0 && abs(first.closeCount-second.closeCount)<=1) return null
        return first
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
