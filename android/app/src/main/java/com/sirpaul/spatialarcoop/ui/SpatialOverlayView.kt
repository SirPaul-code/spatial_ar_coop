package com.sirpaul.spatialarcoop.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


data class ProjectedTrack(
    val key: String,
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val onScreen: Boolean,
    val distanceMeters: Float,
    val uncertaintyMeters: Float,
    val ageMs: Long,
    val sourceId: String
)

data class ProjectedBox(val label: String, val confidence: Float, val rectangle: RectF)

data class ScanOverlayState(
    val chunkCount: Int = 0,
    val pointCount: Int = 0,
    val featureQuality: String = "UNKNOWN",
    val hostedAnchors: Int = 0,
    val pendingAnchors: Int = 0
)

class SpatialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.rgb(255, 78, 78)
    }
    private val thinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.argb(170, 255, 110, 90)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(52, 255, 60, 60)
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(205, 12, 16, 22)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 220, 226, 236)
        textSize = 10.5f * density
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(90, 220, 255)
    }
    private val history = ConcurrentHashMap<String, ArrayDeque<Pair<Float, Float>>>()
    @Volatile private var tracks: List<ProjectedTrack> = emptyList()
    @Volatile private var boxes: List<ProjectedBox> = emptyList()
    @Volatile private var scan = ScanOverlayState()
    @Volatile private var showScan = false

    fun updateTracks(values: List<ProjectedTrack>) {
        tracks = values
        val active = values.mapTo(hashSetOf()) { it.key }
        history.keys.removeIf { it !in active }
        values.filter { it.onScreen }.forEach { value ->
            val points = history.getOrPut(value.key) { ArrayDeque() }
            val previous = points.lastOrNull()
            if (previous == null || kotlin.math.hypot(value.x - previous.first, value.y - previous.second) > 2f * density) {
                points.addLast(value.x to value.y)
                while (points.size > 10) points.removeFirst()
            }
        }
        postInvalidateOnAnimation()
    }

    fun updateLocalBoxes(values: List<ProjectedBox>) {
        boxes = values
        postInvalidateOnAnimation()
    }

    fun updateScanState(value: ScanOverlayState, visible: Boolean = true) {
        scan = value
        showScan = visible
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        boxes.forEach { drawDetectionBox(canvas, it) }
        tracks.forEach { track -> if (track.onScreen) drawTrack(canvas, track) else drawOffscreen(canvas, track) }
        if (showScan) drawScanCard(canvas)
        drawReticle(canvas)
    }

    private fun drawTrack(canvas: Canvas, track: ProjectedTrack) {
        val ageAlpha = (255 - (track.ageMs / 8).toInt()).coerceIn(70, 255)
        stroke.alpha = ageAlpha
        thinStroke.alpha = (ageAlpha * 0.72f).toInt()
        fill.alpha = (ageAlpha * 0.22f).toInt()

        val radius = (18f + track.uncertaintyMeters.coerceIn(0f, 3f) * 12f) * density
        val feetY = track.y
        val markerTop = drawClassGeometry(canvas, track, feetY)
        canvas.drawCircle(track.x, feetY, radius, thinStroke)
        canvas.drawCircle(track.x, feetY, 5f * density, stroke)
        canvas.drawLine(track.x - radius, feetY, track.x + radius, feetY, thinStroke)
        canvas.drawLine(track.x, feetY - radius, track.x, feetY + radius, thinStroke)

        val trail = history[track.key]
        if (trail != null && trail.size > 1) {
            val path = Path()
            trail.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
            }
            canvas.drawPath(path, thinStroke)
        }

        val title = track.label.uppercase()
        val detail = "%.1f m   %.0f%%   %d ms".format(track.distanceMeters, track.confidence * 100f, track.ageMs)
        val panelWidth = maxOf(text.measureText(title), subText.measureText(detail)) + 18f * density
        val left = (track.x - panelWidth / 2f).coerceIn(6f * density, width - panelWidth - 6f * density)
        val top = markerTop - 53f * density
        val rect = RectF(left, top, left + panelWidth, top + 38f * density)
        canvas.drawRoundRect(rect, 7f * density, 7f * density, panel)
        canvas.drawText(title, rect.left + 9f * density, rect.top + 15f * density, text)
        canvas.drawText(detail, rect.left + 9f * density, rect.top + 31f * density, subText)
    }


    private fun drawClassGeometry(canvas: Canvas, track: ProjectedTrack, feetY: Float): Float {
        return when (track.label.lowercase()) {
            "person" -> {
                val top = feetY - 88f * density
                val body = RectF(track.x - 19f * density, top, track.x + 19f * density, feetY - 13f * density)
                canvas.drawRoundRect(body, 18f * density, 18f * density, fill)
                canvas.drawRoundRect(body, 18f * density, 18f * density, stroke)
                canvas.drawCircle(track.x, top - 11f * density, 11f * density, fill)
                canvas.drawCircle(track.x, top - 11f * density, 11f * density, stroke)
                top - 22f * density
            }
            "car" -> {
                val top = feetY - 43f * density
                val body = RectF(track.x - 48f * density, top + 13f * density, track.x + 48f * density, feetY - 8f * density)
                val roof = RectF(track.x - 27f * density, top, track.x + 25f * density, feetY - 18f * density)
                canvas.drawRoundRect(body, 9f * density, 9f * density, fill)
                canvas.drawRoundRect(body, 9f * density, 9f * density, stroke)
                canvas.drawRoundRect(roof, 8f * density, 8f * density, fill)
                canvas.drawRoundRect(roof, 8f * density, 8f * density, stroke)
                canvas.drawCircle(track.x - 28f * density, feetY - 5f * density, 7f * density, stroke)
                canvas.drawCircle(track.x + 28f * density, feetY - 5f * density, 7f * density, stroke)
                top
            }
            "bird" -> {
                val top = feetY - 42f * density
                val body = RectF(track.x - 23f * density, top + 11f * density, track.x + 20f * density, feetY - 5f * density)
                canvas.drawOval(body, fill)
                canvas.drawOval(body, stroke)
                canvas.drawCircle(track.x + 19f * density, top + 12f * density, 8f * density, fill)
                canvas.drawCircle(track.x + 19f * density, top + 12f * density, 8f * density, stroke)
                canvas.drawLine(track.x - 8f * density, feetY - 5f * density, track.x - 8f * density, feetY, thinStroke)
                canvas.drawLine(track.x + 5f * density, feetY - 5f * density, track.x + 5f * density, feetY, thinStroke)
                top
            }
            else -> {
                val top = feetY - 48f * density
                val body = RectF(track.x - 25f * density, top + 8f * density, track.x + 25f * density, feetY - 6f * density)
                canvas.drawOval(body, fill)
                canvas.drawOval(body, stroke)
                canvas.drawCircle(track.x + 22f * density, top + 13f * density, 9f * density, fill)
                canvas.drawCircle(track.x + 22f * density, top + 13f * density, 9f * density, stroke)
                top
            }
        }
    }

    private fun drawOffscreen(canvas: Canvas, track: ProjectedTrack) {
        val centerX = width / 2f
        val centerY = height / 2f
        val angle = atan2(track.y - centerY, track.x - centerX)
        val margin = 34f * density
        val radiusX = width / 2f - margin
        val radiusY = height / 2f - margin
        val x = centerX + cos(angle) * min(radiusX, radiusY)
        val y = centerY + sin(angle) * min(radiusX, radiusY)
        val arrow = Path().apply {
            moveTo(x + cos(angle) * 15f * density, y + sin(angle) * 15f * density)
            lineTo(x + cos(angle + 2.55f) * 11f * density, y + sin(angle + 2.55f) * 11f * density)
            lineTo(x + cos(angle - 2.55f) * 11f * density, y + sin(angle - 2.55f) * 11f * density)
            close()
        }
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.drawText("${track.label} %.1fm".format(track.distanceMeters), x - 28f * density, y - 18f * density, subText)
    }

    private fun drawDetectionBox(canvas: Canvas, box: ProjectedBox) {
        canvas.drawRoundRect(box.rectangle, 8f * density, 8f * density, boxStroke)
        val label = "${box.label} %.0f%%".format(box.confidence * 100f)
        val width = text.measureText(label) + 12f * density
        val background = RectF(box.rectangle.left, box.rectangle.top - 24f * density, box.rectangle.left + width, box.rectangle.top)
        canvas.drawRoundRect(background, 5f * density, 5f * density, panel)
        canvas.drawText(label, background.left + 6f * density, background.bottom - 6f * density, text)
    }

    private fun drawScanCard(canvas: Canvas) {
        val value = scan
        val left = 12f * density
        val top = height - 176f * density
        val rect = RectF(left, top, left + 210f * density, top + 90f * density)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, panel)
        canvas.drawText("SPATIAL MAP", rect.left + 12f * density, rect.top + 20f * density, text)
        canvas.drawText("${value.pointCount} points / ${value.chunkCount} saved chunks", rect.left + 12f * density, rect.top + 40f * density, subText)
        canvas.drawText("Feature quality: ${value.featureQuality}", rect.left + 12f * density, rect.top + 58f * density, subText)
        canvas.drawText("Anchors: ${value.hostedAnchors} hosted / ${value.pendingAnchors} pending", rect.left + 12f * density, rect.top + 76f * density, subText)
    }

    private fun drawReticle(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = Color.argb(175, 255, 255, 255)
        }
        canvas.drawCircle(x, y, 10f * density, reticle)
        canvas.drawLine(x - 16f * density, y, x - 6f * density, y, reticle)
        canvas.drawLine(x + 6f * density, y, x + 16f * density, y, reticle)
        canvas.drawLine(x, y - 16f * density, x, y - 6f * density, reticle)
        canvas.drawLine(x, y + 6f * density, x, y + 16f * density, reticle)
    }
}
