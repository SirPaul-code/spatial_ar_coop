package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density

    private val localAccent = 0xff55f0bd.toInt()
    private val peerAccent = 0xff64a9ff.toInt()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe2161d22.toInt() }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(1f)
        color = 0x99ffffff.toInt()
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
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffc6d0d6.toInt()
        textSize = d(10.8f)
    }
    private val arrow = Path()

    @Volatile private var targets: List<Target> = emptyList()

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
                    onSceneTap?.invoke(event.x, event.y)
                    performClick()
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
        this.targets = targets.toList()
        postInvalidateOnAnimation()
    }

    /** Compatibility helper for any older call sites. */
    fun setTarget(target: Target?) {
        setTargets(if (target == null) emptyList() else listOf(target))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = targets
        if (snapshot.isEmpty() || width <= 0 || height <= 0) return

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

        // Draw farther targets first so a nearby physical point remains legible when
        // several markers overlap in screen space. Label only the nearest few; all
        // targets still retain their crosshair.
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
            // For a point behind the camera the useful instruction is which
            // horizontal direction to turn, rather than an ambiguous down arrow.
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

    companion object {
        private const val MAX_TAP_DURATION_MS = 650L
        private const val MAX_VISIBLE_LABELS = 10
        private const val MAX_EDGE_TARGETS = 6
    }
}
