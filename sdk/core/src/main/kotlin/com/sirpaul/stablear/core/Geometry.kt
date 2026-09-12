package com.sirpaul.stablear.core

import kotlin.math.*

/** Metres, Hamilton quaternion xyzw. T_AB maps B into A. Camera: right/down/forward. */
data class V2(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
    operator fun minus(b: V2) = V2(x-b.x, y-b.y)
    fun norm() = hypot(x,y)
}
data class V3(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
    operator fun plus(b: V3) = V3(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: V3) = V3(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Double) = V3(x*s,y*s,z*s)
    fun dot(b: V3) = x*b.x+y*b.y+z*b.z
    fun cross(b: V3) = V3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun norm() = sqrt(dot(this))
    fun unit(): V3 { val n=norm(); require(n>1e-12); return this*(1/n) }
    companion object { val ZERO=V3(0.0,0.0,0.0) }
}
data class Q(val x: Double, val y: Double, val z: Double, val w: Double) {
    init { require(listOf(x,y,z,w).all { it.isFinite() }); require(abs(x*x+y*y+z*z+w*w-1)<1e-5) }
    fun inverse()=Q(-x,-y,-z,w)
    fun rotate(p: V3): V3 { val v=V3(x,y,z); val t=v.cross(p)*2.0; return p+t*w+v.cross(t) }
    operator fun times(b: Q)=normalized(w*b.x+x*b.w+y*b.z-z*b.y,
        w*b.y-x*b.z+y*b.w+z*b.x,w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z)
    companion object {
        val ID=Q(0.0,0.0,0.0,1.0)
        fun normalized(x: Double,y: Double,z: Double,w: Double): Q {
            val n=sqrt(x*x+y*y+z*z+w*w); require(n.isFinite() && n>1e-12)
            return Q(x/n,y/n,z/n,w/n)
        }
    }
}
data class Rigid(val t: V3=V3.ZERO, val q: Q=Q.ID) {
    fun point(p: V3)=q.rotate(p)+t
    fun inverse(): Rigid { val r=q.inverse(); return Rigid(r.rotate(t*(-1.0)),r) }
    operator fun times(b: Rigid)=Rigid(point(b.t),q*b.q)
    companion object { val ID=Rigid() }
}
data class Intrinsics(val fx: Double,val fy: Double,val cx: Double,val cy: Double,val width: Int,val height: Int) {
    init { require(listOf(fx,fy,cx,cy).all { it.isFinite() }); require(fx>0 && fy>0 && width>0 && height>0) }
    fun contains(p: V2)=p.x>=0 && p.y>=0 && p.x<width && p.y<height
    fun ray(p: V2)=V3((p.x-cx)/fx,(p.y-cy)/fy,1.0)
    fun project(p: V3): V2? = if(p.z<.05) null else V2(fx*p.x/p.z+cx,fy*p.y/p.z+cy)
    fun scaled(sx: Double,sy: Double,w: Int,h: Int)=Intrinsics(fx*sx,fy*sy,cx*sx,cy*sy,w,h)
}
internal fun median(a: List<Double>): Double { require(a.isNotEmpty()); val s=a.sorted(); return s[s.size/2] }
internal fun solve3(a: Array<DoubleArray>,b: DoubleArray): DoubleArray? {
    val m=Array(3) { i -> doubleArrayOf(a[i][0],a[i][1],a[i][2],b[i]) }
    for(i in 0..2) {
        val p=(i..2).maxBy { abs(m[it][i]) }
        if(abs(m[p][i])<1e-9) return null
        val tmp=m[p]; m[p]=m[i]; m[i]=tmp
        val s=m[i][i]; for(j in i..3) m[i][j]/=s
        for(r in 0..2) if(r!=i) { val f=m[r][i]; for(j in i..3) m[r][j]-=f*m[i][j] }
    }
    return DoubleArray(3) { m[it][3] }.takeIf { it.all(Double::isFinite) }
}
