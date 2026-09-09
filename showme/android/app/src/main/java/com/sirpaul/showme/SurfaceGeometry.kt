package com.sirpaul.showme

import com.sirpaul.spatialnomap.CapturedFrame
import com.sirpaul.spatialnomap.PosePacket
import kotlin.math.*

data class Uv(val x: Float, val y: Float)
data class SurfaceHit(val world: FloatArray, val uncertaintyM: Float, val source: String)
data class PlaneSnapshot(val pose: PosePacket, val polygon: FloatArray)

/** Exact display/raw mapping and historical-frame geometry; never calls today's hitTest for an old image. */
object SurfaceGeometry {
    fun rawPixel(p: Uv, width: Int, height: Int, rotation: Int): Uv {
        require(width > 1 && height > 1 && p.x.isFinite() && p.y.isFinite())
        val n = when (rotation) {
            90 -> Uv(p.y, 1f - p.x)
            180 -> Uv(1f - p.x, 1f - p.y)
            270 -> Uv(1f - p.y, p.x)
            else -> p
        }
        return Uv(n.x * (width - 1), n.y * (height - 1))
    }

    fun displayPixel(raw: Uv, width: Int, height: Int, rotation: Int): Uv {
        val u = raw.x / (width - 1); val v = raw.y / (height - 1)
        return when (rotation) { 90 -> Uv(1f - v, u); 180 -> Uv(1f - u, 1f - v); 270 -> Uv(v, 1f - u); else -> Uv(u, v) }
    }

    fun matrix(p: PosePacket): DoubleArray {
        val q = p.q; val n = sqrt(q.sumOf { it.toDouble() * it }).coerceAtLeast(1e-12)
        val x = q[0] / n; val y = q[1] / n; val z = q[2] / n; val w = q[3] / n
        return doubleArrayOf(1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w), p.t[0].toDouble(),
            2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w), p.t[1].toDouble(),
            2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y), p.t[2].toDouble(), 0.0,0.0,0.0,1.0)
    }
    fun inverse(m: DoubleArray): DoubleArray {
        val o = doubleArrayOf(m[0],m[4],m[8],0.0,m[1],m[5],m[9],0.0,m[2],m[6],m[10],0.0,0.0,0.0,0.0,1.0)
        for (r in 0..2) o[r*4+3] = -(o[r*4]*m[3]+o[r*4+1]*m[7]+o[r*4+2]*m[11])
        return o
    }
    fun transform(m: DoubleArray, p: FloatArray): FloatArray = FloatArray(3) { r ->
        (m[r*4]*p[0] + m[r*4+1]*p[1] + m[r*4+2]*p[2] + m[r*4+3]).toFloat()
    }
    fun distance(a: FloatArray, b: FloatArray): Float = sqrt((0..2).sumOf { (a[it]-b[it]).toDouble().pow(2) }).toFloat()
    fun project(frame: CapturedFrame, world: FloatArray): Uv? {
        val p = transform(inverse(matrix(frame.pose)), world); val z = -p[2]
        if (z <= 0.05f) return null
        return Uv(frame.intrinsics.fx*p[0]/z+frame.intrinsics.cx, -frame.intrinsics.fy*p[1]/z+frame.intrinsics.cy)
    }

    fun resolve(frame: CapturedFrame, uv: Uv, planes: List<PlaneSnapshot> = emptyList()): SurfaceHit? {
        val k = frame.intrinsics
        if (uv.x !in 0f..(k.width-1f) || uv.y !in 0f..(k.height-1f) || k.fx <= 1f || k.fy <= 1f) return null
        val depth = depthHit(frame, uv)
        val plane = planeHit(frame, uv, planes)
        if (depth == null) return plane
        if (plane == null) return depth
        val disagreement = distance(depth.world, plane.world)
        return if (disagreement < max(0.10f, depth.uncertaintyM * 3f)) depth
        else if (distance(frame.pose.t, plane.world) < distance(frame.pose.t, depth.world)) plane else depth
    }

    private fun depthHit(frame: CapturedFrame, uv: Uv): SurfaceHit? {
        val k = frame.intrinsics; val inverse = inverse(matrix(frame.pose))
        data class Sample(val du: Double, val dv: Double, val z: Double, val radius2: Double)
        val radius = max(16f, k.width * 0.024f).coerceAtMost(36f)
        val all = frame.metricPoints.mapNotNull { p ->
            if (p.size < 5 || p.any { !it.isFinite() }) return@mapNotNull null
            val dx=p[0]-uv.x; val dy=p[1]-uv.y; val r=dx*dx+dy*dy
            if (r > radius*radius) return@mapNotNull null
            val cp=transform(inverse,floatArrayOf(p[2],p[3],p[4])); val z=-cp[2].toDouble()
            if (z !in 0.08..15.0) null else Sample(dx.toDouble()/k.fx,dy.toDouble()/k.fy,z,r.toDouble())
        }.sortedBy { it.radius2 }.take(48)
        if (all.size < 3 || all.first().radius2 > (radius*0.60).pow(2)) return null
        val seed=all.take(5).map { it.z }.sorted().let { it[it.size/2] }
        var samples=all.filter { abs(it.z-seed) <= max(0.045,seed*0.035) }
        if (samples.size < 3) return null
        // Inverse depth is affine on a physical plane. Fit around the exact ray,
        // instead of copying an adjacent depth pixel onto a different camera ray.
        var coeff: DoubleArray? = null
        repeat(3) {
            val a=Array(3) { DoubleArray(4) }
            for (s in samples) {
                val v=doubleArrayOf(s.du,s.dv,1.0); val weight=1.0/(4.0+s.radius2)
                for (r in 0..2) { for(c in 0..2) a[r][c]+=weight*v[r]*v[c]; a[r][3]+=weight*v[r]/s.z }
            }
            coeff=solve3(a)
            val fit=coeff
            if (fit != null) {
                val kept=samples.filter { s -> val iz=fit[0]*s.du+fit[1]*s.dv+fit[2]; iz>0 && abs(1.0/iz-s.z)<=max(0.020,seed*0.015) }
                if (kept.size>=3) samples=kept
            }
        }
        val zs=samples.map { it.z }.sorted(); val median=zs[zs.size/2]
        val fitted=coeff?.get(2)?.takeIf { it>0 }?.let { 1.0/it }
        val z=fitted?.takeIf { abs(it-median)<=max(0.04,median*0.025) } ?: median
        val spread=zs.map { abs(it-median) }.sorted().let { it[(it.size*0.8).toInt().coerceAtMost(it.lastIndex)] }
        if (!z.isFinite() || z !in 0.08..15.0 || spread>max(0.04,z*0.025)) return null
        val cp=floatArrayOf((uv.x-k.cx)/k.fx*z.toFloat(), -(uv.y-k.cy)/k.fy*z.toFloat(),-z.toFloat())
        return SurfaceHit(transform(matrix(frame.pose),cp),max(0.015,spread*2).toFloat(),"DEPTH")
    }
    private fun solve3(a: Array<DoubleArray>): DoubleArray? {
        for (c in 0..2) {
            val pivot=(c..2).maxBy { abs(a[it][c]) }
            if (abs(a[pivot][c])<1e-10) return null
            val temp=a[c]; a[c]=a[pivot]; a[pivot]=temp
            val d=a[c][c]; for(k in c..3) a[c][k]/=d
            for(r in 0..2) if(r!=c) { val f=a[r][c]; for(k in c..3) a[r][k]-=f*a[c][k] }
        }
        return DoubleArray(3) { a[it][3] }
    }
    private fun planeHit(frame: CapturedFrame, uv: Uv, planes: List<PlaneSnapshot>): SurfaceHit? {
        val k=frame.intrinsics; val origin=frame.pose.t
        val along=transform(matrix(frame.pose),floatArrayOf((uv.x-k.cx)/k.fx,-(uv.y-k.cy)/k.fy,-1f))
        val direction=FloatArray(3) { along[it]-origin[it] }
        var best: SurfaceHit?=null; var bestDistance=15f
        for(plane in planes) {
            val m=matrix(plane.pose); val n=floatArrayOf(m[1].toFloat(),m[5].toFloat(),m[9].toFloat())
            val denom=(0..2).sumOf { (n[it]*direction[it]).toDouble() }
            if(abs(denom)<0.12) continue
            val t=(0..2).sumOf { (n[it]*(plane.pose.t[it]-origin[it])).toDouble() }/denom
            if(t<=0) continue
            val world=FloatArray(3) { origin[it]+direction[it]*t.toFloat() }
            val d=distance(origin,world); if(d !in 0.08f..bestDistance) continue
            val pp=transform(inverse(m),world)
            if(!inside(pp[0],pp[2],plane.polygon)) continue
            bestDistance=d; best=SurfaceHit(world,0.04f,"PLANE")
        }
        return best
    }
    private fun inside(x:Float,y:Float,p:FloatArray):Boolean {
        if(p.size<6) return false
        var result=false; var j=p.size-2
        for(i in p.indices step 2) {
            if((p[i+1]>y)!=(p[j+1]>y) && x<(p[j]-p[i])*(y-p[i+1])/(p[j+1]-p[i+1])+p[i]) result=!result
            j=i
        }
        return result
    }
}
