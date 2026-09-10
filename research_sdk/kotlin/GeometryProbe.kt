import com.sirpaul.showme.ShowMeGeometry as G
import com.sirpaul.spatialnomap.*
import kotlin.math.*
import kotlin.random.Random

/** Compiles unchanged repository Models.kt + ShowMeGeometry.kt, no Android mocks. */
fun main() {
    val r = Random(20260910)
    val k = IntrinsicsPacket(800f,800f,320f,240f,640,480)
    var assertions = 0
    fun verify(b: Boolean) { check(b); assertions++ }
    repeat(1000) {
        val u = r.nextFloat(); val v = r.nextFloat()
        for (rotation in listOf(0,90,180,270)) {
            val upright = G.rawToUpright(u,v,rotation)
            val raw = G.uprightToRaw(upright[0],upright[1],rotation)
            verify(abs(raw[0]-u)<1e-6 && abs(raw[1]-v)<1e-6)
        }
        val q = FloatArray(4) { r.nextFloat()-.5f }
        val p = PosePacket(FloatArray(3) { r.nextFloat()-.5f },q)
        val camera = floatArrayOf((r.nextFloat()-.5f)*.2f,(r.nextFloat()-.5f)*.2f,-2f)
        val world = G.toWorld(p,camera)
        verify(G.distance(camera,G.toCamera(p,world)) < 2e-6)
        val frame = CapturedFrame(1,p,k,"",emptyList())
        val uv = G.project(frame,world)!!
        verify(abs(uv[0]-(k.fx*camera[0]/-camera[2]+k.cx))<.001)
        verify(abs(uv[1]-(k.fy*-camera[1]/-camera[2]+k.cy))<.001)
    }
    val p = PosePacket(floatArrayOf(0f,0f,0f),floatArrayOf(0f,0f,0f,1f))
    val supports = mutableListOf<FloatArray>()
    for (dy in -10..10 step 4) for (dx in -10..10 step 4) {
        val u=320f+dx; val v=240f+dy
        val z=1f/(.5f-.0004f*dx+.0002f*dy)
        supports.add(floatArrayOf(u,v,(u-320)/800*z,-(v-240)/800*z,-z))
    }
    val f=CapturedFrame(1,p,k,"",supports)
    val resolved=G.pointAt(f,320f,240f)!!
    verify(G.distance(resolved,floatArrayOf(0f,0f,-2f))<1e-5)
    verify(G.pointAt(f.copy(metricPoints=emptyList()),320f,240f)==null)
    verify(G.pointAt(f,Float.NaN,240f)==null)
    verify(G.pointAt(f,-1f,240f)==null)
    val edge = listOf(-2f,-1f,1f,2f,3f,4f).mapIndexed { i,dx ->
        val z=if(i%2==0) 1f else 2f
        floatArrayOf(320+dx,240f,dx/800*z,0f,-z)
    }
    verify(G.pointAt(f.copy(metricPoints=edge),320f,240f)==null)
    println("PASS: $assertions checks against unchanged repository geometry (not Android runtime).")
}
