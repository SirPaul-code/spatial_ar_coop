package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TargetOverlayView(context: Context) : View(context) {
    data class Target(
        val screenX: Float,
        val screenY: Float,
        val inFront: Boolean,
        val bearingRad: Float,
        val distanceM: Float,
        val owner: String,
        val confidence: Float,
    )

    private val density = resources.displayMetrics.density
    private fun d(value: Float) = value * density

    private val accent = 0xff55f0bd.toInt()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe2161d22.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(2.4f)
        color = accent
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(1f)
        color = 0x99ffffff.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d(7f)
        color = 0x2855f0bd
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = d(14f)
        isFakeBoldText = true
    }
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffc6d0d6.toInt()
        textSize = d(11.5f)
    }
    private val arrow = Path()

    @Volatile private var target: Target? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun setTarget(target: Target?) {
        this.target = target
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = target ?: return
        if (width <= 0 || height <= 0) return
        val margin = d(42f)
        val visible = t.inFront &&
            t.screenX.isFinite() && t.screenY.isFinite() &&
            t.screenX in margin..(width - margin) &&
            t.screenY in margin..(height - margin)
        if (visible) drawMarker(canvas, t) else drawEdgeArrow(canvas, t, margin)
    }

    private fun drawMarker(canvas: Canvas, t: Target) {
        val x = t.screenX
        val y = t.screenY
        val radius = d(16f)
        canvas.drawCircle(x, y, radius + d(4f), glow)
        canvas.drawCircle(x, y, radius, ring)
        canvas.drawCircle(x, y, d(2.8f), fill)
        canvas.drawLine(x - d(24f), y, x - d(13f), y, thin)
        canvas.drawLine(x + d(13f), y, x + d(24f), y, thin)
        canvas.drawLine(x, y - d(24f), x, y - d(13f), thin)
        canvas.drawLine(x, y + d(13f), x, y + d(24f), thin)

        val label = if (t.owner.isBlank()) "POI" else t.owner
        val distance = if (t.distanceM.isFinite()) "%.1f m".format(t.distanceM) else ""
        val confidence = when {
            t.confidence >= 0.62f -> "HIGH"
            t.confidence >= 0.38f -> "GOOD"
            else -> "LOCKED"
        }
        val metaText = listOf(distance, confidence).filter { it.isNotBlank() }.joinToString("  •  ")
        val boxW = max(title.measureText(label), meta.measureText(metaText)) + d(24f)
        val boxH = d(48f)
        var left = x + d(27f)
        if (left + boxW > width - d(8f)) left = x - d(27f) - boxW
        left = left.coerceIn(d(8f), width - boxW - d(8f))
        val top = (y - boxH / 2f).coerceIn(d(8f), height - boxH - d(8f))
        canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), d(12f), d(12f), panel)
        canvas.drawText(label, left + d(12f), top + d(19f), title)
        if (metaText.isNotBlank()) canvas.drawText(metaText, left + d(12f), top + d(37f), meta)
    }

    private fun drawEdgeArrow(canvas: Canvas, t: Target, margin: Float) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        var dx: Float
        var dy: Float

        if (t.inFront && t.screenX.isFinite() && t.screenY.isFinite()) {
            dx = t.screenX - cx
            dy = t.screenY - cy
        } else {
            // For a point behind the camera the most useful instruction is which
            // horizontal direction to turn. A vertical "down" arrow for a directly
            // rearward point is visually ambiguous in portrait mode.
            dx = if (t.bearingRad >= 0f) 1f else -1f
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
        val x = cx + dx * scale
        val y = cy + dy * scale

        val tipX = x + dx * d(6f)
        val tipY = y + dy * d(6f)
        val baseX = x - dx * d(17f)
        val baseY = y - dy * d(17f)
        val px = -dy
        val py = dx
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(baseX + px * d(10f), baseY + py * d(10f))
        arrow.lineTo(baseX - px * d(10f), baseY - py * d(10f))
        arrow.close()

        canvas.drawCircle(x, y, d(23f), glow)
        canvas.drawCircle(x, y, d(20f), ring)
        canvas.drawPath(arrow, fill)

        val distance = if (t.distanceM.isFinite()) "%.1f m".format(t.distanceM) else "POI"
        val label = if (t.owner.isBlank()) distance else "${t.owner}  •  $distance"
        val boxW = meta.measureText(label) + d(20f)
        val boxH = d(32f)
        val labelLeft = (x - boxW / 2f).coerceIn(d(8f), width - boxW - d(8f))
        val labelTop = (
            if (y < height * 0.25f) y + d(30f) else y - d(48f)
        ).coerceIn(d(8f), height - boxH - d(8f))
        canvas.drawRoundRect(
            RectF(labelLeft, labelTop, labelLeft + boxW, labelTop + boxH),
            d(10f),
            d(10f),
            panel,
        )
        canvas.drawText(label, labelLeft + d(10f), labelTop + d(21f), meta)
    }
}
