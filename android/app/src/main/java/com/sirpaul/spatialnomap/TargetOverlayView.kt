package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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

    private val accent = 0xff4fffc3.toInt()
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xd91b1f24.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = accent }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x88ffffff.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt(); textSize = 31f; isFakeBoldText = true }
    private val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffc8d0d7.toInt(); textSize = 24f }
    private val arrow = Path()
    @Volatile private var target: Target? = null

    fun setTarget(target: Target?) { this.target = target; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = target ?: return
        if (width <= 0 || height <= 0) return
        val margin = 58f
        val visible = t.inFront && t.screenX in margin..(width - margin) && t.screenY in margin..(height - margin)
        if (visible) drawMarker(canvas, t) else drawEdgeArrow(canvas, t, margin)
    }

    private fun drawMarker(canvas: Canvas, t: Target) {
        val x = t.screenX; val y = t.screenY
        canvas.drawCircle(x, y, 34f, ring); canvas.drawCircle(x, y, 6f, fill)
        canvas.drawLine(x - 50f, y, x - 25f, y, thin); canvas.drawLine(x + 25f, y, x + 50f, y, thin)
        canvas.drawLine(x, y - 50f, x, y - 25f, thin); canvas.drawLine(x, y + 25f, x, y + 50f, thin)
        val label = if (t.owner.isBlank()) "POI" else t.owner
        val distance = if (t.distanceM.isFinite()) "%.1f m".format(t.distanceM) else ""
        val confidence = when { t.confidence >= 0.62f -> "HIGH"; t.confidence >= 0.38f -> "GOOD"; else -> "LOCKED" }
        val metaText = listOf(distance, confidence).filter { it.isNotBlank() }.joinToString("  •  ")
        val w = max(title.measureText(label), meta.measureText(metaText)) + 42f
        val left = (x + 48f).coerceAtMost(width - w - 18f)
        val top = (y - 50f).coerceIn(18f, height - 108f)
        canvas.drawRoundRect(RectF(left, top, left + w, top + 92f), 22f, 22f, panel)
        canvas.drawText(label, left + 20f, top + 36f, title)
        if (metaText.isNotBlank()) canvas.drawText(metaText, left + 20f, top + 70f, meta)
    }

    private fun drawEdgeArrow(canvas: Canvas, t: Target, margin: Float) {
        val cx = width * 0.5f; val cy = height * 0.5f
        var dx: Float; var dy: Float
        if (t.inFront && t.screenX.isFinite() && t.screenY.isFinite()) { dx = t.screenX - cx; dy = t.screenY - cy }
        else { dx = sin(t.bearingRad); dy = -cos(t.bearingRad); if (abs(dx) < 0.08f && dy > 0f) dx = if (t.bearingRad >= 0f) 0.08f else -0.08f }
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f); dx /= length; dy /= length
        val halfW = cx - margin; val halfH = cy - margin
        val sx = if (abs(dx) < 1e-4f) Float.POSITIVE_INFINITY else halfW / abs(dx)
        val sy = if (abs(dy) < 1e-4f) Float.POSITIVE_INFINITY else halfH / abs(dy)
        val scale = min(sx, sy)
        val x = cx + dx * scale; val y = cy + dy * scale
        val tipX = x + dx * 8f; val tipY = y + dy * 8f; val baseX = x - dx * 34f; val baseY = y - dy * 34f
        val px = -dy; val py = dx
        arrow.reset(); arrow.moveTo(tipX, tipY); arrow.lineTo(baseX + px * 22f, baseY + py * 22f); arrow.lineTo(baseX - px * 22f, baseY - py * 22f); arrow.close()
        canvas.drawPath(arrow, fill); canvas.drawCircle(x, y, 44f, ring)
        val distance = if (t.distanceM.isFinite()) "%.1f m".format(t.distanceM) else "POI"
        val label = if (t.owner.isBlank()) distance else "${t.owner}  •  $distance"
        val w = meta.measureText(label) + 32f
        val labelLeft = (x - w / 2f).coerceIn(12f, width - w - 12f)
        val labelTop = (if (y < height * 0.25f) y + 58f else y - 96f).coerceIn(16f, height - 54f)
        canvas.drawRoundRect(RectF(labelLeft, labelTop, labelLeft + w, labelTop + 48f), 18f, 18f, panel)
        canvas.drawText(label, labelLeft + 16f, labelTop + 32f, meta)
    }
}
