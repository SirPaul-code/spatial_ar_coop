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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Local, on-device vehicle recognition for the AR camera stream.
 *
 * EfficientDet-Lite0 supplies the 2D COCO vehicle box. The same ARCore frame also
 * supplies dense metric support points, so the detector associates the box with a
 * robust 3D point in the phone's current ARCore world before anything is shared.
 * No image, model request, or inference leaves the phone at runtime.
 *
 * A constant-velocity filter is applied before the renderer receives observations.
 * The renderer still owns room track IDs; this layer only removes detector/depth
 * jitter and predicts through tiny measurement gaps so a car marker looks stable.
 */
class VehicleDetector(context: Context) {
    data class Vehicle(
        val label: String,
        val confidence: Float,
        val pointWorld: FloatArray,
    )

    private data class MotionTrack(
        val id: Int,
        val label: String,
        val filter: MotionTrackFilter,
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

                val vehicles = ArrayList<Vehicle>()
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
                    val point = estimateWorldPoint(rawBox, cameraWorld, metricPoints) ?: continue
                    vehicles += Vehicle(
                        label = name.uppercase(),
                        confidence = category.score(),
                        pointWorld = point,
                    )
                }
                val filtered = filterVehicleMotion(
                    vehicles.sortedByDescending { it.confidence }.take(MAX_RESULTS),
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

    private fun filterVehicleMotion(input: List<Vehicle>, nowMs: Long): List<Vehicle> {
        motionTracks.entries.removeIf { nowMs - it.value.lastSeenMs > FILTER_TRACK_TTL_MS }
        if (input.isEmpty()) return emptyList()
        val used = HashSet<Int>()
        val out = ArrayList<Vehicle>(input.size)

        for (vehicle in input) {
            var best: MotionTrack? = null
            var bestDistance = Float.POSITIVE_INFINITY
            for (track in motionTracks.values) {
                if (track.id in used || track.label != vehicle.label) continue
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
                lastSeenMs = nowMs,
            ).also { motionTracks[it.id] = it }

            val state = track.filter.update(vehicle.pointWorld, nowMs, vehicle.confidence)
            track.lastSeenMs = nowMs
            used += track.id
            out += vehicle.copy(
                pointWorld = state.position,
                confidence = state.confidence,
            )
        }
        return out
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
     * Select a robust metric point from the central region of the detected vehicle.
     * Median range rejects glass/background outliers; averaging the remaining world
     * points makes the shared marker less sensitive to a single depth pixel.
     */
    private fun estimateWorldPoint(
        box: RectF,
        cameraWorld: FloatArray,
        metricPoints: List<FloatArray>,
    ): FloatArray? {
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
            if (d.isFinite() && d in MIN_VEHICLE_DISTANCE_M..MAX_VEHICLE_DISTANCE_M) {
                supports += Support(p, d)
            }
        }
        if (supports.size < MIN_METRIC_SUPPORTS) return null

        val sortedDistances = supports.map { it.distance }.sorted()
        val median = sortedDistances[sortedDistances.size / 2]
        val tolerance = max(0.45f, median * 0.12f)
        val inliers = supports
            .filter { abs(it.distance - median) <= tolerance }
            .sortedBy { abs(it.distance - median) }
            .take(48)
        if (inliers.size < MIN_METRIC_INLIERS) return null

        val out = FloatArray(3)
        for (s in inliers) {
            out[0] += s.p[0]
            out[1] += s.p[1]
            out[2] += s.p[2]
        }
        val divisor = inliers.size.toFloat()
        repeat(3) { i -> out[i] = out[i] / divisor }
        return out
    }

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
            for (col in 0 until width) {
                nv21[out++] = y.get(rowStart + col * yPlane.pixelStride)
            }
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

    private fun errorText(t: Throwable): String =
        buildString {
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
        private const val FILTER_TRACK_TTL_MS = 2_800L
        private val VEHICLE_LABELS = setOf("car", "truck", "bus")
    }
}
