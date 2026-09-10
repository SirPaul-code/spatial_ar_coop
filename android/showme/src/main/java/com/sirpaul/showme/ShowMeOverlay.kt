package com.sirpaul.showme

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

class ShowMeOverlay(context: Context) : View(context) {
    data class Shape(val id: String, val tool: String, val color: String, val label: String,
        val points: List<FloatArray>, val verified: Boolean)
    @Volatile private var shapes = emptyList<Shape>()
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = d(12f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val path = Path()
    private fun d(v: Float) = v * density
    fun show(value: List<Shape>) { shapes = value; postInvalidateOnAnimation() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (shape in shapes) {
            if (shape.points.isEmpty()) continue
            val color = runCatching { Color.parseColor(shape.color) }.getOrDefault(0xff8ff1c6.toInt())
            val p = shape.points.first()
            if (shape.tool == "pin") {
                if (p[0] !in 0f..width.toFloat() || p[1] !in 0f..height.toFloat()) {
                    edgeIndicator(canvas, p, color); continue
                }
                val phase = ((monotonicMs() % 1600) / 1600.0 * Math.PI * 2).toFloat()
                fill.color = color; fill.alpha = 35
                canvas.drawCircle(p[0], p[1], d(24f + sin(phase) * 2f), fill)
                line.color = 0xb00b1220.toInt(); line.strokeWidth = d(6f)
                canvas.drawCircle(p[0], p[1], d(13f), line)
                line.color = color; line.strokeWidth = d(2.5f)
                canvas.drawCircle(p[0], p[1], d(13f), line)
                fill.color = color; fill.alpha = 255
                canvas.drawCircle(p[0], p[1], d(3.5f), fill)
            } else {
                path.reset(); path.moveTo(p[0], p[1])
                shape.points.drop(1).forEach { path.lineTo(it[0], it[1]) }
                if (shape.tool == "circle") path.close()
                line.color = 0xb50b1220.toInt(); line.strokeWidth = d(7f); canvas.drawPath(path, line)
                line.color = color; line.strokeWidth = d(3.5f); canvas.drawPath(path, line)
                if (shape.tool == "arrow" && shape.points.size > 1) {
                    val end = shape.points.last(); val previous = shape.points[shape.points.lastIndex - 1]
                    val angle = atan2(end[1] - previous[1], end[0] - previous[0])
                    path.reset(); path.moveTo(end[0], end[1])
                    path.lineTo(end[0] - d(18f) * cos(angle - 0.5f), end[1] - d(18f) * sin(angle - 0.5f))
                    path.moveTo(end[0], end[1])
                    path.lineTo(end[0] - d(18f) * cos(angle + 0.5f), end[1] - d(18f) * sin(angle + 0.5f))
                    canvas.drawPath(path, line)
                }
            }
            val label = shape.label.ifBlank { if (shape.tool == "pin") "PINNED" else "" }
            if (label.isNotBlank() && p[0] in 0f..width.toFloat() && p[1] in 0f..height.toFloat()) {
                val displayed = label.take(34)
                val w = min(width - d(24f), text.measureText(displayed) + d(24f)).coerceAtLeast(d(36f))
                val left = (p[0] + d(23f)).coerceIn(d(8f), max(d(8f), width - w - d(8f)))
                val top = (p[1] - d(17f)).coerceIn(d(8f), max(d(8f), height - d(44f)))
                fill.color = 0xe80b1220.toInt(); fill.alpha = 255
                canvas.drawRoundRect(left, top, left + w, top + d(34f), d(10f), d(10f), fill)
                canvas.drawText(displayed, left + d(12f), top + d(22f), text)
            }
        }
        if (shapes.isNotEmpty()) postInvalidateOnAnimation()
    }
    private fun edgeIndicator(canvas: Canvas, p: FloatArray, color: Int) {
        val cx = width / 2f; val cy = height / 2f
        val dx = p[0] - cx; val dy = p[1] - cy
        val factor = min((cx - d(24f)) / abs(dx).coerceAtLeast(1f), (cy - d(100f)) / abs(dy).coerceAtLeast(1f))
        val x = cx + dx * factor; val y = cy + dy * factor
        fill.color = color; fill.alpha = 220
        canvas.drawCircle(x, y, d(5f), fill)
    }
}
