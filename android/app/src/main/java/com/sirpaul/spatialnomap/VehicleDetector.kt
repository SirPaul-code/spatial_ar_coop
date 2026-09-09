package com.sirpaul.spatialnomap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.view.Surface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local, on-device vehicle recognition for the AR camera stream.
 *
 * EfficientDet-Lite0 supplies a 2D COCO vehicle box. Metric supports inside that box
 * produce a robust world-space centroid and a horizontal oriented 3D footprint. A
 * constant-velocity alpha-beta filter then gives the renderer a stable detector track
 * id, position and velocity instead of a fresh anonymous point on every inference.
 */
class VehicleDetector(context: Context) {
    data class WorldBox(
        val centerWorld: FloatArray,
        /** Horizontal major axis in ARCore world coordinates. */
        val forwardWorld: FloatArray,
        val halfLengthM: Float,
        val halfWidthM: Float,
        val halfHeightM: Float,
    )

    data class Vehicle(
        /** Stable only inside this detector process; renderer maps it to a room id. */
        val trackId: Int,
        val label: String,
        val confidence: Float,
        val pointWorld: FloatArray,
        val velocityWorld: FloatArray,
        val worldBox: WorldBox?,
    )

    private data class RawVehicle(
        val label: String,
        val confidence: Float,
        val pointWorld: FloatArray,
        val worldBox: WorldBox?,
    )

    private data class MotionTrack(
        val id: Int,
        var label: String,
        val filter: MotionTrackFilter,
        var worldBox: WorldBox?,
        var lastSeenMs: Long,
    )

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val motionTracks = LinkedHashMap<Int, MotionTrack>()
    private var nextMotionTrackId = 1
    @Volatile private var detector: ObjectDetector? = null
    @Volatile private var closed = false

    fun isBusy(): Boolean = busy.get()

    fun submit(
        image: Image,
        displayRotation: Int,
        cameraId: String?,
        cameraWorld: FloatArray,
        metricPoints: List<FloatArray>,
        onResult: (List<Vehicle>) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (closed || !busy.compareAndSet(false, true)) {
            runCatching { image.close() }
            return false
        }

        executor.execute {
            var rawBitmap: Bitmap? = null
            var detectorBitmap: Bitmap? = null
            try {
                val rawWidth = image.width
                val rawHeight = image.height
                rawBitmap = yuv420ToBitmap(image)
                    ?: throw IllegalStateException("Could not convert AR camera frame")
                val rotation = cameraRotationDegrees(cameraId, displayRotation)
                detectorBitmap = rotateBitmap(rawBitmap!!, rotation)

                val task = detector ?: createDetector().also { detector = it }
                val mpImage = BitmapImageBuilder(detectorBitmap!!).build()
                val result = try {
                    task.detect(mpImage)
                } finally {
                    runCatching { mpImage.close() }
                }

                val rawVehicles = ArrayList<RawVehicle>()
                for (detection in result.detections()) {
                    val category = detection.categories().maxByOrNull { it.score() } ?: continue
                    val name = category.categoryName().lowercase()
                    if (name !in VEHICLE_LABELS || category.score() < SCORE_THRESHOLD) continue

                    val rawBox = mapDetectorRectToRaw(
                        detection.boundingBox(),
                        rawWidth,
                        rawHeight,
                        rotation,
                    )
                    val geometry = estimateWorldGeometry(rawBox, cameraWorld, metricPoints, name) ?: continue
                    rawVehicles += RawVehicle(
                        label = name.uppercase(),
                        confidence = category.score(),
                        pointWorld = geometry.centerWorld.copyOf(),
                        worldBox = geometry,
                    )
                }

                val filtered = filterVehicleMotion(
                    rawVehicles.sortedByDescending { it.confidence }.take(MAX_RESULTS),
                    System.currentTimeMillis(),
                )
                onResult(filtered)
            } catch (t: Throwable) {
                onError(errorText(t))
            } finally {
                runCatching { image.close() }
                if (detectorBitmap != null && detectorBitmap !== rawBitmap) runCatching { detectorBitmap?.recycle() }
                runCatching { rawBitmap?.recycle() }
                busy.set(false)
            }
        }
        return true
    }

    fun close() {
        closed = true
        executor.execute {
            runCatching { detector?.close() }
            detector = null
            motionTracks.clear()
        }
        executor.shutdown()
    }

    private fun createDetector(): ObjectDetector {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(8)
            .build()
        return ObjectDetector.createFromOptions(appContext, options)
    }

    private fun filterVehicleMotion(input: List<RawVehicle>, nowMs: Long): List<Vehicle> {
        motionTracks.entries.removeIf { nowMs - it.value.lastSeenMs > FILTER_TRACK_TTL_MS }
        if (input.isEmpty()) return emptyList()
        val used = HashSet<Int>()
        val out = ArrayList<Vehicle>(input.size)

        for (vehicle in input) {
            var best: MotionTrack? = null
            var bestDistance = Float.POSITIVE_INFINITY
            for (track in motionTracks.values) {
                if (track.id in used) continue
                val predicted = track.filter.predict(nowMs)?.position ?: continue
                val distance = pointDistance(predicted, vehicle.pointWorld)
                if (distance < bestDistance && distance <= FILTER_ASSOCIATION_M) {
                    bestDistance = distance
                    best = track
                }
            }

            val track = best ?: MotionTrack(
                id = nextMotionTrackId++,
                label = vehicle.label,
                filter = MotionTrackFilter(alpha = 0.58f, beta = 0.20f),
                worldBox = null,
                lastSeenMs = nowMs,
            ).also { motionTracks[it.id] = it }

            val state = track.filter.update(vehicle.pointWorld, nowMs, vehicle.confidence)
            track.label = vehicle.label
            track.lastSeenMs = nowMs
            track.worldBox = smoothWorldBox(track.worldBox, vehicle.worldBox, state.position, state.velocity)
            used += track.id
            out += Vehicle(
                trackId = track.id,
                label = track.label,
                confidence = state.confidence,
                pointWorld = state.position.copyOf(),
                velocityWorld = state.velocity.copyOf(),
                worldBox = track.worldBox?.copy(
                    centerWorld = state.position.copyOf(),
                    forwardWorld = track.worldBox!!.forwardWorld.copyOf(),
                ),
            )
        }
        return out
    }

    private fun smoothWorldBox(
        previous: WorldBox?,
        observed: WorldBox?,
        center: FloatArray,
        velocity: FloatArray,
    ): WorldBox? {
        val box = observed ?: previous ?: return null
        val observedAxis = observed?.forwardWorld ?: box.forwardWorld
        val velocityAxis = horizontalUnit(velocity)
        var axis = if (velocityAxis != null && horizontalSpeed(velocity) >= VELOCITY_HEADING_MIN_MPS) {
            velocityAxis
        } else {
            observedAxis.copyOf()
        }
        val priorAxis = previous?.forwardWorld
        if (priorAxis != null && dot3(axis, priorAxis) < 0f) axis = FloatArray(3) { -axis[it] }
        if (priorAxis != null) {
            axis = horizontalUnit(
                FloatArray(3) { i -> priorAxis.getOrElse(i) { 0f } * 0.68f + axis.getOrElse(i) { 0f } * 0.32f },
            ) ?: axis
        }

        fun smooth(old: Float?, fresh: Float): Float =
            if (old == null || !old.isFinite()) fresh else old * 0.68f + fresh * 0.32f

        return WorldBox(
            centerWorld = center.copyOf(3),
            forwardWorld = axis,
            halfLengthM = smooth(previous?.halfLengthM, box.halfLengthM),
            halfWidthM = smooth(previous?.halfWidthM, box.halfWidthM),
            halfHeightM = smooth(previous?.halfHeightM, box.halfHeightM),
        )
    }

    private fun cameraRotationDegrees(cameraId: String?, displayRotation: Int): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = runCatching {
            cameraId?.let { id ->
                cameraManager?.getCameraCharacteristics(id)
                    ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            }
        }.getOrNull() ?: 90
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Maps a box from the upright detector bitmap back into raw ARCore CPU pixels. */
    private fun mapDetectorRectToRaw(box: RectF, rawWidth: Int, rawHeight: Int, rotation: Int): RectF {
        fun inverse(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
            90 -> Pair(y, rawHeight - 1f - x)
            180 -> Pair(rawWidth - 1f - x, rawHeight - 1f - y)
            270 -> Pair(rawWidth - 1f - y, x)
            else -> Pair(x, y)
        }

        val corners = listOf(
            inverse(box.left, box.top),
            inverse(box.right, box.top),
            inverse(box.right, box.bottom),
            inverse(box.left, box.bottom),
        )
        val left = corners.minOf { it.first }.coerceIn(0f, rawWidth - 1f)
        val right = corners.maxOf { it.first }.coerceIn(0f, rawWidth - 1f)
        val top = corners.minOf { it.second }.coerceIn(0f, rawHeight - 1f)
        val bottom = corners.maxOf { it.second }.coerceIn(0f, rawHeight - 1f)
        return RectF(left, top, right, bottom)
    }

    /**
     * Build a robust metric centroid plus an oriented vehicle-sized 3D box. Depth
     * outliers are rejected by median range first; horizontal PCA then estimates the
     * dominant world direction. Minimum class dimensions intentionally keep sparse
     * visible-surface depth from collapsing the box into a tiny flat patch.
     */
    private fun estimateWorldGeometry(
        box: RectF,
        cameraWorld: FloatArray,
        metricPoints: List<FloatArray>,
        category: String,
    ): WorldBox? {
        if (box.width() < 4f || box.height() < 4f) return null
        val insetX = box.width() * 0.12f
        val insetY = box.height() * 0.12f
        val central = RectF(
            box.left + insetX,
            box.top + insetY,
            box.right - insetX,
            box.bottom - insetY,
        )

        data class Support(val p: FloatArray, val distance: Float)
        val supports = ArrayList<Support>()
        for (m in metricPoints) {
            if (m.size < 5) continue
            val u = m[0]
            val v = m[1]
            if (u !in central.left..central.right || v !in central.top..central.bottom) continue
            val p = floatArrayOf(m[2], m[3], m[4])
            if (!p.all { it.isFinite() }) continue
            val d = pointDistance(cameraWorld, p)
            if (d.isFinite() && d in MIN_VEHICLE_DISTANCE_M..MAX_VEHICLE_DISTANCE_M) supports += Support(p, d)
        }
        if (supports.size < MIN_METRIC_SUPPORTS) return null

        val sortedDistances = supports.map { it.distance }.sorted()
        val median = sortedDistances[sortedDistances.size / 2]
        val tolerance = max(0.45f, median * 0.12f)
        val inliers = supports
            .filter { abs(it.distance - median) <= tolerance }
            .sortedBy { abs(it.distance - median) }
            .take(64)
        if (inliers.size < MIN_METRIC_INLIERS) return null

        val mean = FloatArray(3)
        for (s in inliers) repeat(3) { i -> mean[i] += s.p[i] }
        repeat(3) { i -> mean[i] /= inliers.size.toFloat() }

        var cxx = 0.0
        var czz = 0.0
        var cxz = 0.0
        for (s in inliers) {
            val x = (s.p[0] - mean[0]).toDouble()
            val z = (s.p[2] - mean[2]).toDouble()
            cxx += x * x
            czz += z * z
            cxz += x * z
        }
        val theta = if (cxx + czz > 1e-7) 0.5 * atan2(2.0 * cxz, cxx - czz) else 0.0
        val forward = floatArrayOf(cos(theta).toFloat(), 0f, sin(theta).toFloat())
        val right = floatArrayOf(-forward[2], 0f, forward[0])

        val along = ArrayList<Float>(inliers.size)
        val across = ArrayList<Float>(inliers.size)
        val vertical = ArrayList<Float>(inliers.size)
        for (s in inliers) {
            val dx = s.p[0] - mean[0]
            val dy = s.p[1] - mean[1]
            val dz = s.p[2] - mean[2]
            along += dx * forward[0] + dz * forward[2]
            across += dx * right[0] + dz * right[2]
            vertical += dy
        }
        along.sort()
        across.sort()
        vertical.sort()

        val a0 = percentile(along, 0.10f)
        val a1 = percentile(along, 0.90f)
        val b0 = percentile(across, 0.10f)
        val b1 = percentile(across, 0.90f)
        val y0 = percentile(vertical, 0.10f)
        val y1 = percentile(vertical, 0.90f)
        val centerA = (a0 + a1) * 0.5f
        val centerB = (b0 + b1) * 0.5f
        val centerY = (y0 + y1) * 0.5f
        val center = floatArrayOf(
            mean[0] + forward[0] * centerA + right[0] * centerB,
            mean[1] + centerY,
            mean[2] + forward[2] * centerA + right[2] * centerB,
        )

        val dims = classDimensionBounds(category)
        return WorldBox(
            centerWorld = center,
            forwardWorld = forward,
            halfLengthM = (((a1 - a0) * 0.5f).coerceAtLeast(dims[0])).coerceAtMost(dims[3]),
            halfWidthM = (((b1 - b0) * 0.5f).coerceAtLeast(dims[1])).coerceAtMost(dims[4]),
            halfHeightM = (((y1 - y0) * 0.5f).coerceAtLeast(dims[2])).coerceAtMost(dims[5]),
        )
    }

    /** min L/W/H followed by max L/W/H, all half-extents. */
    private fun classDimensionBounds(category: String): FloatArray = when (category) {
        "bus" -> floatArrayOf(2.6f, 0.85f, 0.95f, 8.0f, 1.8f, 2.1f)
        "truck" -> floatArrayOf(1.8f, 0.75f, 0.75f, 6.5f, 1.8f, 2.1f)
        else -> floatArrayOf(1.25f, 0.62f, 0.52f, 3.2f, 1.45f, 1.35f)
    }

    private fun percentile(sorted: List<Float>, q: Float): Float {
        if (sorted.isEmpty()) return 0f
        val index = ((sorted.size - 1) * q.coerceIn(0f, 1f)).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun horizontalUnit(v: FloatArray): FloatArray? {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val n = sqrt(x * x + z * z)
        if (!n.isFinite() || n < 1e-4f) return null
        return floatArrayOf(x / n, 0f, z / n)
    }

    private fun horizontalSpeed(v: FloatArray): Float {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        return sqrt(x * x + z * z).takeIf { it.isFinite() } ?: 0f
    }

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } +
            a.getOrElse(1) { 0f } * b.getOrElse(1) { 0f } +
            a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    private fun yuv420ToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || image.planes.size < 3) return null

        val nv21 = ByteArray(width * height + width * height / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var out = 0
        val y = yPlane.buffer.duplicate()
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) nv21[out++] = y.get(rowStart + col * yPlane.pixelStride)
        }

        val u = uPlane.buffer.duplicate()
        val v = vPlane.buffer.duplicate()
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val uRow = row * uPlane.rowStride
            val vRow = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[out++] = v.get(vRow + col * vPlane.pixelStride)
                nv21[out++] = u.get(uRow + col * uPlane.pixelStride)
            }
        }

        val jpeg = ByteArrayOutputStream(max(16_384, width * height / 3))
        val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 88, jpeg)
        if (!ok) return null
        val bytes = jpeg.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun pointDistance(a: FloatArray, b: FloatArray): Float {
        val dx = a.getOrElse(0) { 0f } - b.getOrElse(0) { 0f }
        val dy = a.getOrElse(1) { 0f } - b.getOrElse(1) { 0f }
        val dz = a.getOrElse(2) { 0f } - b.getOrElse(2) { 0f }
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun errorText(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }

    companion object {
        private const val MODEL_ASSET = "efficientdet_lite0_uint8.tflite"
        private const val SCORE_THRESHOLD = 0.38f
        private const val MAX_RESULTS = 4
        private const val MIN_METRIC_SUPPORTS = 5
        private const val MIN_METRIC_INLIERS = 3
        private const val MIN_VEHICLE_DISTANCE_M = 0.35f
        private const val MAX_VEHICLE_DISTANCE_M = 45f
        private const val FILTER_ASSOCIATION_M = 4.0f
        private const val FILTER_TRACK_TTL_MS = 3_600L
        private const val VELOCITY_HEADING_MIN_MPS = 0.60f
        private val VEHICLE_LABELS = setOf("car", "truck", "bus")
    }
}
