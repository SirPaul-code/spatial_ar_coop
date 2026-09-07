package com.sirpaul.spatialnomap

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.LinkedHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Live 3D representation of the actual shared AR world.
 *
 * It renders the accumulated local + transformed peer metric map, current point
 * cloud, phone poses, static POIs, dynamic vehicles and motion trails. It uses no
 * second AR session and no cloud/server. The visualization is deliberately made for
 * a screen-recordable product demo while still showing the real spatial state.
 */
class BirdEyeWorldView(context: Context) : View(context) {
    private data class TrailPoint(val p: FloatArray, val t: Long)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x335cf4c5
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x885cf4c5.toInt()
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
    }
    private val localPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xaaffffff.toInt()
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val remotePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x995cf4c5.toInt()
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val accumulatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x665cf4c5
        strokeWidth = 2.7f
        strokeCap = Paint.Cap.ROUND
    }
    private val accumulatedRemotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66ffc45c
        strokeWidth = 2.7f
        strokeCap = Paint.Cap.ROUND
    }
    private val localPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff5cf4c5.toInt()
        style = Paint.Style.FILL
    }
    private val peerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffc45c.toInt()
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        style = Paint.Style.FILL
    }
    private val dynamicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffff5c91.toInt()
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x995cf4c5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffc7d1d8.toInt()
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff5cf4c5.toInt()
        textSize = 21f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xd90b1014.toInt()
        style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            metresToPx = (metresToPx * detector.scaleFactor).coerceIn(24f, 180f)
            return true
        }
    })

    private val trails = LinkedHashMap<String, ArrayDeque<TrailPoint>>()
    private var metresToPx = 62f
    private var yaw = -0.55f
    private var pitch = 0.92f
    private var autoOrbit = true
    private var lastDrawNs = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun toggleAutoOrbit(): Boolean {
        autoOrbit = !autoOrbit
        return autoOrbit
    }

    fun setAutoOrbit(enabled: Boolean) {
        autoOrbit = enabled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff070a0d.toInt())
        val nowNs = System.nanoTime()
        if (lastDrawNs != 0L && autoOrbit) {
            val dt = ((nowNs - lastDrawNs).coerceAtMost(100_000_000L) / 1_000_000_000f)
            yaw += dt * 0.18f
        }
        lastDrawNs = nowNs

        val snapshot = WorldVizBus.snapshot()
        val persistentMap = SpatialMapAccumulator.snapshot(limit = 6500)
        updateTrails(snapshot)
        val center = chooseCenter(snapshot, persistentMap)
        val groundY = snapshot.actors.firstOrNull { it.local }?.position?.getOrElse(1) { 0f } ?: center[1]

        drawGround(canvas, center, groundY)
        drawPersistentMap(canvas, persistentMap, center)
        drawCurrentPointCloud(canvas, snapshot, center)
        drawTrails(canvas, snapshot, center)
        drawTargets(canvas, snapshot, center)
        drawActors(canvas, snapshot, center)
        drawHud(canvas, snapshot, persistentMap.size)

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                autoOrbit = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                yaw += dx * 0.008f
                pitch = (pitch + dy * 0.004f).coerceIn(0.35f, 1.28f)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun chooseCenter(
        snapshot: WorldVizBus.Snapshot,
        persistentMap: List<SpatialMapAccumulator.Point>,
    ): FloatArray {
        snapshot.actors.firstOrNull { it.local }?.position?.let { return it.copyOf(3) }
        snapshot.targets.firstOrNull()?.position?.let { return it.copyOf(3) }
        persistentMap.firstOrNull()?.xyz?.let { return it.copyOf(3) }
        return floatArrayOf(0f, 0f, 0f)
    }

    private fun drawGround(canvas: Canvas, center: FloatArray, groundY: Float) {
        val radius = 10
        for (i in -radius..radius) {
            val a = project(floatArrayOf(center[0] + i, groundY, center[2] - radius), center)
            val b = project(floatArrayOf(center[0] + i, groundY, center[2] + radius), center)
            val c = project(floatArrayOf(center[0] - radius, groundY, center[2] + i), center)
            val d = project(floatArrayOf(center[0] + radius, groundY, center[2] + i), center)
            canvas.drawLine(a.x, a.y, b.x, b.y, if (i == 0) axisPaint else gridPaint)
            canvas.drawLine(c.x, c.y, d.x, d.y, if (i == 0) axisPaint else gridPaint)
        }
    }

    private fun drawPersistentMap(
        canvas: Canvas,
        points: List<SpatialMapAccumulator.Point>,
        center: FloatArray,
    ) {
        for (point in points) {
            val s = project(point.xyz, center)
            if (s.x !in -20f..width + 20f || s.y !in -20f..height + 20f) continue
            val paint = if (point.remoteSeen) accumulatedRemotePaint else accumulatedPaint
            paint.alpha = (70 + min(150, point.observations * 8)).coerceIn(70, 220)
            canvas.drawPoint(s.x, s.y, paint)
        }
        accumulatedPaint.alpha = 255
        accumulatedRemotePaint.alpha = 255
    }

    private fun drawCurrentPointCloud(canvas: Canvas, snapshot: WorldVizBus.Snapshot, center: FloatArray) {
        snapshot.points.forEach { point ->
            val s = project(point.xyz, center)
            if (s.x in -20f..width + 20f && s.y in -20f..height + 20f) {
                canvas.drawPoint(s.x, s.y, if (point.remote) remotePointPaint else localPointPaint)
            }
        }
    }

    private fun updateTrails(snapshot: WorldVizBus.Snapshot) {
        val now = snapshot.timestampMs
        snapshot.actors.forEach { actor -> appendTrail("actor:${actor.id}", actor.position, now) }
        snapshot.targets.filter { it.dynamic }.forEach { target -> appendTrail("target:${target.id}", target.position, now) }
        trails.values.forEach { trail ->
            while (trail.isNotEmpty() && now - trail.first().t > 18_000L) trail.removeFirst()
            while (trail.size > 100) trail.removeFirst()
        }
        trails.entries.removeIf { it.value.isEmpty() }
    }

    private fun appendTrail(key: String, point: FloatArray, now: Long) {
        val trail = trails.getOrPut(key) { ArrayDeque() }
        val last = trail.lastOrNull()
        if (last == null || distance(last.p, point) >= 0.035f || now - last.t > 700L) {
            trail.addLast(TrailPoint(point.copyOf(3), now))
        }
    }

    private fun drawTrails(canvas: Canvas, snapshot: WorldVizBus.Snapshot, center: FloatArray) {
        val now = snapshot.timestampMs
        trails.forEach { (_, trail) ->
            if (trail.size < 2) return@forEach
            val path = Path()
            var first = true
            trail.forEach { tp ->
                val s = project(tp.p, center)
                if (first) {
                    path.moveTo(s.x, s.y)
                    first = false
                } else {
                    path.lineTo(s.x, s.y)
                }
            }
            val age = now - trail.last().t
            trailPaint.alpha = if (age < 3_000L) 170 else 95
            canvas.drawPath(path, trailPaint)
        }
        trailPaint.alpha = 255
    }

    private fun drawTargets(canvas: Canvas, snapshot: WorldVizBus.Snapshot, center: FloatArray) {
        snapshot.targets.forEach { target ->
            val s = project(target.position, center)
            val paint = if (target.dynamic) dynamicPaint else targetPaint
            canvas.drawCircle(s.x, s.y, if (target.dynamic) 10f else 8f, paint)
            canvas.drawCircle(s.x, s.y, if (target.dynamic) 18f else 14f, Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                alpha = 120
            })
            canvas.drawText(target.label, s.x + 14f, s.y - 12f, labelPaint)
        }
    }

    private fun drawActors(canvas: Canvas, snapshot: WorldVizBus.Snapshot, center: FloatArray) {
        snapshot.actors.forEach { actor ->
            val s = project(actor.position, center)
            val paint = if (actor.local) localPaint else peerPaint
            val heading = yawFromQuaternion(actor.quaternion) - yaw
            val size = 18f
            val path = Path().apply {
                moveTo(s.x + sin(heading) * size, s.y - cos(heading) * size)
                lineTo(s.x + sin(heading + 2.45f) * size * 0.78f, s.y - cos(heading + 2.45f) * size * 0.78f)
                lineTo(s.x + sin(heading - 2.45f) * size * 0.78f, s.y - cos(heading - 2.45f) * size * 0.78f)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.drawCircle(s.x, s.y, 26f, Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                alpha = 130
            })
            canvas.drawText(if (actor.local) "YOU" else actor.label, s.x + 32f, s.y + 7f, labelPaint)
        }
    }

    private fun drawHud(canvas: Canvas, snapshot: WorldVizBus.Snapshot, mapSize: Int) {
        val pad = 24f
        canvas.drawRoundRect(pad, pad, min(width - pad, 760f), 225f, 22f, 22f, panelPaint)
        canvas.drawText("LIVE SPATIAL WORLD", 48f, 68f, textPaint)
        val state = if (snapshot.locked) "LOCKED SHARED FRAME" else "ACQUIRING SHARED FRAME"
        statusPaint.color = if (snapshot.locked) 0xff5cf4c5.toInt() else 0xffffc45c.toInt()
        canvas.drawText(state, 48f, 103f, statusPaint)
        canvas.drawText(
            "actors ${snapshot.actors.size}   targets ${snapshot.targets.size}   map $mapSize   live ${snapshot.points.size}",
            48f,
            136f,
            smallTextPaint,
        )
        val diagnostic = AlignmentDiagnostics.latest()
        if (diagnostic != null) {
            val q = diagnostic.quality
            val metric = if (q.medianMetricResidualM.isFinite()) "%.2fm".format(q.medianMetricResidualM) else "—"
            canvas.drawText(
                "${diagnostic.blocker}  I ${q.inliers}/${q.correspondences}  C ${(q.imageCoverage * 100).toInt()}%  M $metric",
                48f,
                169f,
                smallTextPaint,
            )
        }
        val burst = if (snapshot.burstProgress in 0.001f..0.999f) {
            "   acquisition ${(snapshot.burstProgress * 100).toInt()}%"
        } else ""
        canvas.drawText("drag orbit • pinch zoom$burst", 48f, 202f, smallTextPaint)
    }

    private fun project(p: FloatArray, center: FloatArray): PointF {
        val dx = p.getOrElse(0) { 0f } - center[0]
        val dy = p.getOrElse(1) { 0f } - center[1]
        val dz = p.getOrElse(2) { 0f } - center[2]

        val cy = cos(yaw)
        val sy = sin(yaw)
        val rx = cy * dx - sy * dz
        val rz = sy * dx + cy * dz

        val cp = cos(pitch)
        val sp = sin(pitch)
        val ry = cp * dy - sp * rz
        val depth = sp * dy + cp * rz
        val perspective = (1f / (1f + depth * 0.025f)).coerceIn(0.45f, 1.8f)

        return PointF(
            width * 0.5f + rx * metresToPx * perspective,
            height * 0.58f - ry * metresToPx * perspective,
        )
    }

    private fun yawFromQuaternion(q: FloatArray): Float {
        if (q.size < 4) return 0f
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        return atan2(2f * (w * y + x * z), 1f - 2f * (y * y + z * z))
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/** Installs the WORLD presentation view without coupling it to MainActivity internals. */
object BirdEyeWorldController {
    private const val TAG_BUTTON = "spatial-world-button"
    private const val TAG_SHELL = "spatial-world-shell"
    private var registered = false

    @Synchronized fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = install(activity)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun install(activity: Activity) {
        if (activity !is MainActivity) return
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_BUTTON) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val world = BirdEyeWorldView(activity)
        val shell = FrameLayout(activity).apply {
            tag = TAG_SHELL
            setBackgroundColor(0xff070a0d.toInt())
            visibility = View.GONE
            elevation = dp(40).toFloat()
        }
        shell.addView(world, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val close = TextView(activity).apply {
            text = "CLOSE"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xaa182027.toInt())
            setOnClickListener { shell.visibility = View.GONE }
        }
        shell.addView(close, FrameLayout.LayoutParams(dp(92), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(24)
            rightMargin = dp(18)
        })

        val orbit = TextView(activity).apply {
            text = "AUTO ORBIT"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xaa182027.toInt())
            setOnClickListener {
                val enabled = world.toggleAutoOrbit()
                text = if (enabled) "AUTO ORBIT" else "FREE LOOK"
            }
        }
        shell.addView(orbit, FrameLayout.LayoutParams(dp(112), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(18)
            rightMargin = dp(18)
        })

        content.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val button = TextView(activity).apply {
            tag = TAG_BUTTON
            text = "WORLD"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xdd16241f.toInt())
            elevation = dp(20).toFloat()
            setOnClickListener {
                world.setAutoOrbit(true)
                shell.visibility = View.VISIBLE
                shell.bringToFront()
            }
        }
        content.addView(button, FrameLayout.LayoutParams(dp(96), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(14)
        })
    }
}
