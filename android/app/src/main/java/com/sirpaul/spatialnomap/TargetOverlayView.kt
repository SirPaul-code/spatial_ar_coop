package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class TargetOverlayView(context: Context) : View(context) {
    data class Target(
        val id: Long,
        val screenX: Float,
        val screenY: Float,
        val inFront: Boolean,
        val bearingRad: Float,
        val distanceM: Float,
        val label: String,
        val confidence: Float,
        val isLocal: Boolean,
    )

    private data class TrailPoint(val xM: Float, val zM: Float, val atMs: Long)

    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density

    private val localAccent = 0xff55f0bd.toInt()
    private val peerAccent = 0xff64a9ff.toInt()
    private val vehicleAccent = 0xffffc857.toInt()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe2161d22.toInt() }
    private val worldPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xf00b1116.toInt() }
    private val gridMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(1f)
        color = 0x3655f0bd
    }
    private val gridMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(0.7f)
        color = 0x1cffffff
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(1f)
        color = 0x99ffffff.toInt()
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val localRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(2.4f)
        color = localAccent
    }
    private val peerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(2.4f)
        color = peerAccent
    }
    private val localFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = localAccent }
    private val peerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = peerAccent }
    private val vehicleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = vehicleAccent }
    private val localGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(7f)
        color = 0x2855f0bd
    }
    private val peerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(7f)
        color = 0x2864a9ff
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = d(13f)
        isFakeBoldText = true
    }
    private val worldTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = d(18f)
        isFakeBoldText = true
    }
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffc6d0d6.toInt()
        textSize = d(10.8f)
    }
    private val worldMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff9fb0ba.toInt()
        textSize = d(11.5f)
    }
    private val arrow = Path()
    private val actorArrow = Path()

    @Volatile private var targets: List<Target> = emptyList()
    @Volatile private var worldMode = false
    private val trails = LinkedHashMap<Long, ArrayDeque<TrailPoint>>()
    private var lastTrailSampleMs = 0L

    /**
     * Scene tap callback. The overlay intentionally owns camera-surface gestures:
     * it sits above GLSurfaceView and therefore receives a complete DOWN/UP stream.
     */
    var onSceneTap: ((Float, Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tapCandidate = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownAtMs = 0L

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapCandidate = true
                touchDownX = event.x
                touchDownY = event.y
                touchDownAtMs = event.eventTime
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) tapCandidate = false
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (dx * dx + dy * dy > touchSlop * touchSlop) tapCandidate = false
                return true
            }

            MotionEvent.ACTION_UP -> {
                val shortEnough = event.eventTime - touchDownAtMs <= MAX_TAP_DURATION_MS
                val shouldTap = tapCandidate && shortEnough
                tapCandidate = false
                if (shouldTap) {
                    if (isWorldToggleHit(event.x, event.y)) {
                        worldMode = !worldMode
                        performClick()
                        postInvalidateOnAnimation()
                    } else if (worldMode) {
                        // World overview deliberately consumes scene taps. A demo
                        // operator cannot accidentally create a POI while filming it.
                        performClick()
                    } else {
                        onSceneTap?.invoke(event.x, event.y)
                        performClick()
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                tapCandidate = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setTargets(targets: Collection<Target>) {
        val snapshot = targets.toList()
        this.targets = snapshot
        updateTrails(snapshot)
        postInvalidateOnAnimation()
    }

    /** Compatibility helper for any older call sites. */
    fun setTarget(target: Target?) {
        setTargets(if (target == null) emptyList() else listOf(target))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (worldMode) {
            drawWorldOverview(canvas)
            drawWorldToggle(canvas)
            postInvalidateOnAnimation()
            return
        }

        val snapshot = targets
        if (snapshot.isNotEmpty()) {
            val margin = d(42f)
            val visible = ArrayList<Target>(snapshot.size)
            val offscreen = ArrayList<Target>(snapshot.size)
            for (target in snapshot) {
                val isVisible = target.inFront &&
                    target.screenX.isFinite() && target.screenY.isFinite() &&
                    target.screenX in margin..(width - margin) &&
                    target.screenY in margin..(height - margin)
                if (isVisible) visible += target else offscreen += target
            }

            val labeledIds = visible
                .sortedBy { sortableDistance(it.distanceM) }
                .take(MAX_VISIBLE_LABELS)
                .mapTo(HashSet()) { it.id }

            visible
                .sortedByDescending { sortableDistance(it.distanceM) }
                .forEach { drawMarker(canvas, it, it.id in labeledIds) }

            val nearestOffscreen = offscreen
                .sortedBy { sortableDistance(it.distanceM) }
                .take(MAX_EDGE_TARGETS)
            nearestOffscreen.forEachIndexed { index, target ->
                drawEdgeArrow(canvas, target, margin, index)
            }

            if (offscreen.size > MAX_EDGE_TARGETS) {
                drawOverflowBadge(canvas, offscreen.size - MAX_EDGE_TARGETS)
            }
        }
        drawWorldToggle(canvas)
    }

    private fun updateTrails(snapshot: List<Target>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrailSampleMs < TRAIL_SAMPLE_MS) return
        lastTrailSampleMs = now
        val alive = HashSet<Long>()
        for (target in snapshot) {
            if (!target.distanceM.isFinite() || !target.bearingRad.isFinite()) continue
            alive += target.id
            val x = sin(target.bearingRad) * target.distanceM
            val z = cos(target.bearingRad) * target.distanceM
            val deque = trails.getOrPut(target.id) { ArrayDeque() }
            val last = deque.lastOrNull()
            if (last == null || pointDistance(last.xM, last.zM, x, z) >= TRAIL_MIN_STEP_M || now - last.atMs >= 900L) {
                deque.addLast(TrailPoint(x, z, now))
            }
            while (deque.size > MAX_TRAIL_POINTS) deque.removeFirst()
            while (deque.firstOrNull()?.let { now - it.atMs > TRAIL_TTL_MS } == true) deque.removeFirst()
        }
        val stale = trails.keys.filter { it !in alive }.toList()
        stale.forEach { id ->
            val deque = trails[id] ?: return@forEach
            while (deque.firstOrNull()?.let { now - it.atMs > TRAIL_TTL_MS } == true) deque.removeFirst()
            if (deque.isEmpty()) trails.remove(id)
        }
    }

    /**
     * Presentation-focused camera-centric world overview. All target observations
     * already contain metric distance + bearing, so this view remains independent
     * of rendering internals while still showing live actor motion and off-screen
     * geometry. North is intentionally not claimed; "forward" is the current phone
     * camera direction, making the view deterministic and honest for a live demo.
     */
    private fun drawWorldOverview(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), worldPanel)
        val snapshot = targets.filter { it.distanceM.isFinite() && it.bearingRad.isFinite() }
        val headerTop = d(76f)
        val footerBottom = height - d(58f)
        val cx = width * 0.5f
        val cy = (headerTop + footerBottom) * 0.5f + d(12f)

        val farthest = snapshot.maxOfOrNull { it.distanceM } ?: 4f
        val rangeM = chooseWorldRange(farthest)
        val usableHalfW = width * 0.42f
        val usableHalfH = (footerBottom - headerTop) * 0.42f
        val pxPerM = min(usableHalfW, usableHalfH) / rangeM

        drawPerspectiveGrid(canvas, cx, cy, pxPerM, rangeM)
        drawObserver(canvas, cx, cy)

        val now = SystemClock.elapsedRealtime()
        for (target in snapshot) {
            val xM = sin(target.bearingRad) * target.distanceM
            val zM = cos(target.bearingRad) * target.distanceM
            drawTrail(canvas, target, cx, cy, pxPerM, now)
            drawWorldActor(canvas, target, xM, zM, cx, cy, pxPerM)
        }

        canvas.drawText("LIVE SPATIAL WORLD", d(18f), d(31f), worldTitle)
        val vehicleCount = snapshot.count { it.label.contains("CAR", ignoreCase = true) || it.label.contains("TRUCK", ignoreCase = true) || it.label.contains("BUS", ignoreCase = true) }
        val manualCount = snapshot.size - vehicleCount
        canvas.drawText(
            "camera-relative bird's-eye  •  ±${rangeM.toInt()} m  •  $manualCount targets  •  $vehicleCount vehicles",
            d(18f),
            d(52f),
            worldMeta,
        )
        canvas.drawText("FORWARD", cx - worldMeta.measureText("FORWARD") * 0.5f, headerTop + d(8f), worldMeta)
        canvas.drawText(
            "Live trails fade automatically • WORLD returns to AR",
            d(18f),
            height - d(20f),
            worldMeta,
        )
    }

    private fun chooseWorldRange(farthest: Float): Float = when {
        farthest <= 3f -> 3f
        farthest <= 5f -> 5f
        farthest <= 10f -> 10f
        farthest <= 20f -> 20f
        farthest <= 50f -> 50f
        else -> 100f
    }

    private fun drawPerspectiveGrid(canvas: Canvas, cx: Float, cy: Float, pxPerM: Float, rangeM: Float) {
        val steps = 8
        val extent = rangeM * pxPerM
        for (i in -steps..steps) {
            val f = i / steps.toFloat()
            val offset = f * extent
            val paint = if (i % 4 == 0) gridMajor else gridMinor
            canvas.drawLine(cx + offset, cy - extent, cx + offset, cy + extent, paint)
            canvas.drawLine(cx - extent, cy + offset, cx + extent, cy + offset, paint)
        }
        val rings = 4
        for (i in 1..rings) {
            val r = extent * i / rings
            canvas.drawCircle(cx, cy, r, if (i == rings) gridMajor else gridMinor)
            val label = "%.0f m".format(rangeM * i / rings)
            canvas.drawText(label, cx + d(5f), cy - r + d(13f), worldMeta)
        }
    }

    private fun drawObserver(canvas: Canvas, cx: Float, cy: Float) {
        val pulse = 1f + 0.14f * sin(SystemClock.elapsedRealtime() / 220.0).toFloat()
        localGlow.strokeWidth = d(8f)
        canvas.drawCircle(cx, cy, d(19f) * pulse, localGlow)
        canvas.drawCircle(cx, cy, d(12f), localRing)
        canvas.drawCircle(cx, cy, d(3.2f), localFill)
        actorArrow.reset()
        actorArrow.moveTo(cx, cy - d(29f))
        actorArrow.lineTo(cx - d(7f), cy - d(16f))
        actorArrow.lineTo(cx + d(7f), cy - d(16f))
        actorArrow.close()
        canvas.drawPath(actorArrow, localFill)
        canvas.drawText("YOU", cx + d(17f), cy + d(4f), title)
    }

    private fun drawTrail(
        canvas: Canvas,
        target: Target,
        cx: Float,
        cy: Float,
        pxPerM: Float,
        now: Long,
    ) {
        val deque = trails[target.id] ?: return
        if (deque.size < 2) return
        trailPaint.color = actorColor(target)
        val points = deque.toList()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val age = now - b.atMs
            val alpha = (1f - age.toFloat() / TRAIL_TTL_MS).coerceIn(0.08f, 0.72f)
            trailPaint.alpha = (255 * alpha).toInt()
            canvas.drawLine(
                cx + a.xM * pxPerM,
                cy - a.zM * pxPerM,
                cx + b.xM * pxPerM,
                cy - b.zM * pxPerM,
                trailPaint,
            )
        }
        trailPaint.alpha = 255
    }

    private fun drawWorldActor(
        canvas: Canvas,
        target: Target,
        xM: Float,
        zM: Float,
        cx: Float,
        cy: Float,
        pxPerM: Float,
    ) {
        val x = cx + xM * pxPerM
        val y = cy - zM * pxPerM
        val isVehicle = target.label.contains("CAR", ignoreCase = true) ||
            target.label.contains("TRUCK", ignoreCase = true) ||
            target.label.contains("BUS", ignoreCase = true)
        val fill = if (isVehicle) vehicleFill else if (target.isLocal) localFill else peerFill
        val ring = if (target.isLocal) localRing else peerRing
        val pulse = if (isVehicle) 1f + 0.10f * sin((SystemClock.elapsedRealtime() + target.id) / 180.0).toFloat() else 1f
        val radius = d(if (isVehicle) 8.5f else 6.5f) * pulse

        canvas.drawCircle(x, y, radius + d(4f), ring)
        canvas.drawCircle(x, y, radius, fill)

        val label = target.label.ifBlank { "TARGET" }
        val distance = "%.1f m".format(target.distanceM)
        val text = "$label  •  $distance"
        val boxW = worldMeta.measureText(text) + d(16f)
        val left = (x + d(10f)).coerceIn(d(8f), width - boxW - d(8f))
        val top = (y - d(27f)).coerceIn(d(68f), height - d(52f))
        canvas.drawRoundRect(RectF(left, top, left + boxW, top + d(25f)), d(8f), d(8f), panel)
        canvas.drawText(text, left + d(8f), top + d(17f), worldMeta)
    }

    private fun actorColor(target: Target): Int = when {
        target.label.contains("CAR", ignoreCase = true) ||
            target.label.contains("TRUCK", ignoreCase = true) ||
            target.label.contains("BUS", ignoreCase = true) -> vehicleAccent
        target.isLocal -> localAccent
        else -> peerAccent
    }

    private fun drawWorldToggle(canvas: Canvas) {
        val rect = worldToggleRect()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (worldMode) 0xee55f0bd.toInt() else 0xe6161d22.toInt()
        }
        canvas.drawRoundRect(rect, d(15f), d(15f), bg)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (worldMode) 0xff071612.toInt() else 0xffffffff.toInt()
            textSize = d(11.5f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(if (worldMode) "AR VIEW" else "WORLD", rect.centerX(), rect.centerY() + d(4f), textPaint)
    }

    private fun worldToggleRect(): RectF {
        val w = d(78f)
        val h = d(36f)
        return RectF(width - w - d(14f), d(112f), width - d(14f), d(112f) + h)
    }

    private fun isWorldToggleHit(x: Float, y: Float): Boolean = worldToggleRect().contains(x, y)

    private fun sortableDistance(distance: Float): Float =
        if (distance.isFinite()) distance else Float.MAX_VALUE

    private fun drawMarker(canvas: Canvas, target: Target, showLabel: Boolean) {
        val x = target.screenX
        val y = target.screenY
        val radius = d(13.5f)
        val ring = if (target.isLocal) localRing else peerRing
        val fill = if (target.isLocal) localFill else peerFill
        val glow = if (target.isLocal) localGlow else peerGlow

        canvas.drawCircle(x, y, radius + d(4f), glow)
        canvas.drawCircle(x, y, radius, ring)
        canvas.drawCircle(x, y, d(2.6f), fill)
        canvas.drawLine(x - d(21f), y, x - d(11f), y, thin)
        canvas.drawLine(x + d(11f), y, x + d(21f), y, thin)
        canvas.drawLine(x, y - d(21f), x, y - d(11f), thin)
        canvas.drawLine(x, y + d(11f), x, y + d(21f), thin)

        if (!showLabel) return

        val label = target.label.ifBlank { "TARGET" }
        val distance = if (target.distanceM.isFinite()) "%.1f m".format(target.distanceM) else ""
        val state = if (target.isLocal) {
            "LOCAL"
        } else {
            when {
                target.confidence >= 0.62f -> "HIGH"
                target.confidence >= 0.38f -> "GOOD"
                else -> "LOCKED"
            }
        }
        val metaText = listOf(distance, state).filter { it.isNotBlank() }.joinToString("  •  ")
        val boxW = max(title.measureText(label), meta.measureText(metaText)) + d(22f)
        val boxH = d(45f)
        var left = x + d(23f)
        if (left + boxW > width - d(8f)) left = x - d(23f) - boxW
        left = left.coerceIn(d(8f), width - boxW - d(8f))
        val top = (y - boxH / 2f).coerceIn(d(8f), height - boxH - d(8f))
        canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), d(11f), d(11f), panel)
        canvas.drawText(label, left + d(11f), top + d(18f), title)
        if (metaText.isNotBlank()) canvas.drawText(metaText, left + d(11f), top + d(35f), meta)
    }

    private fun drawEdgeArrow(canvas: Canvas, target: Target, margin: Float, slotIndex: Int) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        var dx: Float
        var dy: Float

        if (target.inFront && target.screenX.isFinite() && target.screenY.isFinite()) {
            dx = target.screenX - cx
            dy = target.screenY - cy
        } else {
            dx = if (target.bearingRad >= 0f) 1f else -1f
            dy = 0f
        }

        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
        dx /= length
        dy /= length
        val halfW = cx - margin
        val halfH = cy - margin
        val sx = if (abs(dx) < 1e-4f) Float.POSITIVE_INFINITY else halfW / abs(dx)
        val sy = if (abs(dy) < 1e-4f) Float.POSITIVE_INFINITY else halfH / abs(dy)
        val scale = min(sx, sy)

        val px = -dy
        val py = dx
        val slotOffsetDp = when (slotIndex) {
            0 -> 0f
            1 -> 15f
            2 -> -15f
            3 -> 30f
            4 -> -30f
            else -> 45f
        }
        var x = cx + dx * scale + px * d(slotOffsetDp)
        var y = cy + dy * scale + py * d(slotOffsetDp)
        x = x.coerceIn(margin, width - margin)
        y = y.coerceIn(margin, height - margin)

        val ring = if (target.isLocal) localRing else peerRing
        val fill = if (target.isLocal) localFill else peerFill
        val glow = if (target.isLocal) localGlow else peerGlow

        val tipX = x + dx * d(5f)
        val tipY = y + dy * d(5f)
        val baseX = x - dx * d(14f)
        val baseY = y - dy * d(14f)
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(baseX + px * d(8f), baseY + py * d(8f))
        arrow.lineTo(baseX - px * d(8f), baseY - py * d(8f))
        arrow.close()

        canvas.drawCircle(x, y, d(20f), glow)
        canvas.drawCircle(x, y, d(17f), ring)
        canvas.drawPath(arrow, fill)

        val distance = if (target.distanceM.isFinite()) "%.1f m".format(target.distanceM) else "TARGET"
        val label = if (target.label.isBlank()) distance else "${target.label}  •  $distance"
        val boxW = meta.measureText(label) + d(18f)
        val boxH = d(29f)
        val labelLeft = (x - boxW / 2f).coerceIn(d(8f), width - boxW - d(8f))
        val labelTop = (
            if (y < height * 0.25f) y + d(27f) else y - d(43f)
        ).coerceIn(d(8f), height - boxH - d(8f))
        canvas.drawRoundRect(
            RectF(labelLeft, labelTop, labelLeft + boxW, labelTop + boxH),
            d(9f),
            d(9f),
            panel,
        )
        canvas.drawText(label, labelLeft + d(9f), labelTop + d(19f), meta)
    }

    private fun drawOverflowBadge(canvas: Canvas, hiddenCount: Int) {
        val text = "+$hiddenCount more off-screen"
        val boxW = meta.measureText(text) + d(22f)
        val boxH = d(30f)
        val left = (width - boxW) * 0.5f
        val top = (height - d(88f) - boxH).coerceAtLeast(d(8f))
        canvas.drawRoundRect(
            RectF(left, top, left + boxW, top + boxH),
            d(10f),
            d(10f),
            panel,
        )
        canvas.drawText(text, left + d(11f), top + d(20f), meta)
    }

    private fun pointDistance(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = ax - bx
        val dz = az - bz
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        private const val MAX_TAP_DURATION_MS = 650L
        private const val MAX_VISIBLE_LABELS = 10
        private const val MAX_EDGE_TARGETS = 6
        private const val TRAIL_SAMPLE_MS = 120L
        private const val MAX_TRAIL_POINTS = 72
        private const val TRAIL_TTL_MS = 12_000L
        private const val TRAIL_MIN_STEP_M = 0.08f
    }
}
