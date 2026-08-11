package com.sirpaul.spatialarcoop.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.min


data class ProjectedCuboid(val points: List<FloatArray>)
data class ProjectedJoint(val index: Int, val x: Float, val y: Float, val confidence: Float)
data class ProjectedSkeleton(val joints: List<ProjectedJoint>)
data class ProjectedPose(val joints: List<ProjectedJoint>)

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
    val sourceId: String,
    val bounds: RectF? = null,
    val cuboid: ProjectedCuboid? = null,
    val skeleton: ProjectedSkeleton? = null,
    val offscreenDx: Float = 1f,
    val offscreenDy: Float = 0f
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
        color = Color.rgb(213, 154, 74)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val thinStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.argb(180, 213, 154, 74)
    }
    private val skeletonJoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(213, 154, 74)
    }
    private val localPoseStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.3f * density
        color = Color.rgb(120, 149, 178)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val localPoseJoint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(120, 149, 178)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(34, 213, 154, 74)
    }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(218, 23, 24, 26)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(242, 239, 232)
        textSize = 13f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 185, 179, 169)
        textSize = 10.5f * density
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(160, 120, 149, 178)
    }
    @Volatile private var tracks: List<ProjectedTrack> = emptyList()
    @Volatile private var boxes: List<ProjectedBox> = emptyList()
    @Volatile private var localPoses: List<ProjectedPose> = emptyList()
    @Volatile private var scan = ScanOverlayState()
    @Volatile private var showScan = false

    fun updateTracks(values: List<ProjectedTrack>) {
        tracks = values
        postInvalidateOnAnimation()
    }

    fun updateLocalBoxes(values: List<ProjectedBox>) {
        boxes = values
        postInvalidateOnAnimation()
    }

    fun updateLocalPoses(values: List<ProjectedPose>) {
        localPoses = values
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
        localPoses.forEach { drawLocalPose(canvas, it) }
        tracks.forEach { track -> if (track.onScreen) drawTrack(canvas, track) else drawOffscreen(canvas, track) }
        if (showScan) drawScanCard(canvas)
        drawReticle(canvas)
    }

    private fun drawTrack(canvas: Canvas, track: ProjectedTrack) {
        val ageAlpha = (255 - (track.ageMs / 10).toInt()).coerceIn(78, 255)
        stroke.alpha = ageAlpha
        thinStroke.alpha = (ageAlpha * 0.76f).toInt()
        skeletonJoint.alpha = ageAlpha
        fill.alpha = (ageAlpha * 0.14f).toInt()

        val radius = (12f + track.uncertaintyMeters.coerceIn(0f, 3f) * 8f) * density
        val feetY = track.y
        val markerTop = when {
            track.skeleton != null -> drawSkeleton(canvas, track.skeleton, stroke, skeletonJoint)
            track.cuboid != null -> drawCuboid(canvas, track.cuboid)
            track.bounds != null -> {
                canvas.drawRoundRect(track.bounds, 7f * density, 7f * density, fill)
                canvas.drawRoundRect(track.bounds, 7f * density, 7f * density, stroke)
                val tick = min(track.bounds.width(), track.bounds.height()).coerceAtMost(22f * density) * 0.35f
                drawCornerTicks(canvas, track.bounds, tick)
                track.bounds.top
            }
            else -> drawClassGeometry(canvas, track, feetY)
        }

        canvas.drawCircle(track.x, feetY, radius, thinStroke)
        canvas.drawCircle(track.x, feetY, 4.5f * density, stroke)

        val stableId = track.key.substringAfterLast(':').take(8)
        val title = if (track.skeleton != null && track.label.equals("person", true)) {
            "person pose · $stableId"
        } else {
            "${track.label} · $stableId"
        }
        val detail = "%.1f m   %.0f%%   %d ms".format(track.distanceMeters, track.confidence * 100f, track.ageMs)
        val panelWidth = maxOf(text.measureText(title), subText.measureText(detail)) + 18f * density
        val left = (track.x - panelWidth / 2f).coerceIn(6f * density, width - panelWidth - 6f * density)
        val top = (markerTop - 47f * density).coerceAtLeast(6f * density)
        val rect = RectF(left, top, left + panelWidth, top + 38f * density)
        canvas.drawRoundRect(rect, 7f * density, 7f * density, panel)
        canvas.drawText(title, rect.left + 9f * density, rect.top + 15f * density, text)
        canvas.drawText(detail, rect.left + 9f * density, rect.top + 31f * density, subText)
    }

    private fun drawLocalPose(canvas: Canvas, pose: ProjectedPose) {
        drawSkeleton(canvas, ProjectedSkeleton(pose.joints), localPoseStroke, localPoseJoint)
    }

    private fun drawSkeleton(
        canvas: Canvas,
        skeleton: ProjectedSkeleton,
        linePaint: Paint,
        jointPaint: Paint
    ): Float {
        val byIndex = skeleton.joints
            .filter { it.confidence >= MIN_RENDER_JOINT_CONFIDENCE && it.x.isFinite() && it.y.isFinite() }
            .associateBy { it.index }
        if (byIndex.size < MIN_RENDER_JOINTS) return height * 0.5f

        SKELETON_EDGES.forEach { edge ->
            val a = byIndex[edge[0]] ?: return@forEach
            val b = byIndex[edge[1]] ?: return@forEach
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        byIndex.values.forEach { joint ->
            canvas.drawCircle(joint.x, joint.y, 3.2f * density, jointPaint)
        }
        val head = byIndex[0]
        if (head != null) canvas.drawCircle(head.x, head.y, 8.5f * density, linePaint)
        return byIndex.values.minOf { it.y } - 9f * density
    }

    private fun drawCuboid(canvas: Canvas, cuboid: ProjectedCuboid): Float {
        if (cuboid.points.size != 8) return height * 0.5f
        CUBOID_EDGES.forEach { edge ->
            val a = cuboid.points[edge[0]]
            val b = cuboid.points[edge[1]]
            canvas.drawLine(a[0], a[1], b[0], b[1], stroke)
        }
        val groundPath = Path().apply {
            val first = cuboid.points[0]
            moveTo(first[0], first[1])
            for (index in 1..3) lineTo(cuboid.points[index][0], cuboid.points[index][1])
            close()
        }
        canvas.drawPath(groundPath, fill)
        return cuboid.points.minOf { it[1] }
    }

    private fun drawCornerTicks(canvas: Canvas, bounds: RectF, tick: Float) {
        canvas.drawLine(bounds.left, bounds.top, bounds.left + tick, bounds.top, stroke)
        canvas.drawLine(bounds.left, bounds.top, bounds.left, bounds.top + tick, stroke)
        canvas.drawLine(bounds.right, bounds.top, bounds.right - tick, bounds.top, stroke)
        canvas.drawLine(bounds.right, bounds.top, bounds.right, bounds.top + tick, stroke)
        canvas.drawLine(bounds.left, bounds.bottom, bounds.left + tick, bounds.bottom, stroke)
        canvas.drawLine(bounds.left, bounds.bottom, bounds.left, bounds.bottom - tick, stroke)
        canvas.drawLine(bounds.right, bounds.bottom, bounds.right - tick, bounds.bottom, stroke)
        canvas.drawLine(bounds.right, bounds.bottom, bounds.right, bounds.bottom - tick, stroke)
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
                canvas.drawRoundRect(body, 9f * density, 9f * density, fill)
                canvas.drawRoundRect(body, 9f * density, 9f * density, stroke)
                top
            }
            "bird" -> {
                val top = feetY - 42f * density
                val body = RectF(track.x - 23f * density, top + 11f * density, track.x + 20f * density, feetY - 5f * density)
                canvas.drawOval(body, fill)
                canvas.drawOval(body, stroke)
                top
            }
            else -> {
                val top = feetY - 48f * density
                val body = RectF(track.x - 25f * density, top + 8f * density, track.x + 25f * density, feetY - 6f * density)
                canvas.drawOval(body, fill)
                canvas.drawOval(body, stroke)
                top
            }
        }
    }

    private fun drawOffscreen(canvas: Canvas, track: ProjectedTrack) {
        val direction = OffscreenDirection(track.offscreenDx, track.offscreenDy)
        val point = OffscreenIndicatorMath.edgePoint(width.toFloat(), height.toFloat(), 38f * density, direction)
        val angle = atan2(direction.dy, direction.dx)
        val x = point.x
        val y = point.y
        val arrow = Path().apply {
            moveTo(x + kotlin.math.cos(angle) * 15f * density, y + kotlin.math.sin(angle) * 15f * density)
            lineTo(x + kotlin.math.cos(angle + 2.55f) * 11f * density, y + kotlin.math.sin(angle + 2.55f) * 11f * density)
            lineTo(x + kotlin.math.cos(angle - 2.55f) * 11f * density, y + kotlin.math.sin(angle - 2.55f) * 11f * density)
            close()
        }
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        val label = "${track.label} %.1fm".format(track.distanceMeters)
        val labelWidth = subText.measureText(label)
        val labelX = (x - labelWidth / 2f).coerceIn(8f * density, width - labelWidth - 8f * density)
        val labelY = (y - 18f * density).coerceIn(16f * density, height - 12f * density)
        canvas.drawText(label, labelX, labelY, subText)
    }

    private fun drawDetectionBox(canvas: Canvas, box: ProjectedBox) {
        canvas.drawRoundRect(box.rectangle, 8f * density, 8f * density, boxStroke)
        val label = "${box.label} %.0f%%".format(box.confidence * 100f)
        val labelWidth = text.measureText(label) + 12f * density
        val background = RectF(box.rectangle.left, box.rectangle.top - 24f * density, box.rectangle.left + labelWidth, box.rectangle.top)
        canvas.drawRoundRect(background, 5f * density, 5f * density, panel)
        canvas.drawText(label, background.left + 6f * density, background.bottom - 6f * density, text)
    }

    private fun drawScanCard(canvas: Canvas) {
        val value = scan
        val left = 12f * density
        val top = height - 176f * density
        val rect = RectF(left, top, left + 220f * density, top + 90f * density)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, panel)
        canvas.drawText("Map setup", rect.left + 12f * density, rect.top + 20f * density, text)
        canvas.drawText("${value.pointCount} points · ${value.chunkCount} saved chunks", rect.left + 12f * density, rect.top + 40f * density, subText)
        canvas.drawText("Feature quality: ${value.featureQuality.lowercase()}", rect.left + 12f * density, rect.top + 58f * density, subText)
        canvas.drawText("Anchors: ${value.hostedAnchors} hosted · ${value.pendingAnchors} pending", rect.left + 12f * density, rect.top + 76f * density, subText)
    }

    private fun drawReticle(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            color = Color.argb(175, 242, 239, 232)
        }
        canvas.drawCircle(x, y, 10f * density, reticle)
        canvas.drawLine(x - 16f * density, y, x - 6f * density, y, reticle)
        canvas.drawLine(x + 6f * density, y, x + 16f * density, y, reticle)
        canvas.drawLine(x, y - 16f * density, x, y - 6f * density, reticle)
        canvas.drawLine(x, y + 6f * density, x, y + 16f * density, reticle)
    }

    companion object {
        private val CUBOID_EDGES = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
            intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
            intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)
        )
        private val SKELETON_EDGES = arrayOf(
            intArrayOf(11, 12),
            intArrayOf(11, 13), intArrayOf(13, 15),
            intArrayOf(12, 14), intArrayOf(14, 16),
            intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
            intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(27, 31),
            intArrayOf(24, 26), intArrayOf(26, 28), intArrayOf(28, 32),
            intArrayOf(0, 11), intArrayOf(0, 12)
        )
        private const val MIN_RENDER_JOINT_CONFIDENCE = 0.28f
        private const val MIN_RENDER_JOINTS = 6
    }
}
