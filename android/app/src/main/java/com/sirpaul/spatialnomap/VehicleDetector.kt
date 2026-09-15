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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    data class CameraModel(
        val translation: FloatArray,
        val quaternion: FloatArray,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    )

    private data class RawVehicle(
        val label: String,
        val confidence: Float,
        val pointWorld: FloatArray,
        val worldBox: WorldBox?,
    )

    private data class DetectionRequest(
        val image: Image,
        val displayRotation: Int,
        val cameraId: String?,
        val camera: CameraModel,
        val metricPoints: List<FloatArray>,
        val onResult: (List<Vehicle>) -> Unit,
        val onError: (String) -> Unit,
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
    private val pending = AtomicReference<DetectionRequest?>(null)
    private val motionTracks = LinkedHashMap<Int, MotionTrack>()
    private var nextMotionTrackId = 1
    @Volatile private var detector: ObjectDetector? = null
    @Volatile private var closed = false

    fun isBusy(): Boolean = busy.get()

    fun submit(
        image: Image,
        displayRotation: Int,
        cameraId: String?,
        camera: CameraModel,
        metricPoints: List<FloatArray>,
        onResult: (List<Vehicle>) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (closed) {
            runCatching { image.close() }
            return false
        }
        val request = DetectionRequest(
            image = image,
            displayRotation = displayRotation,
            cameraId = cameraId,
            camera = camera,
            metricPoints = metricPoints,
            onResult = onResult,
            onError = onError,
        )
        // Latest-frame mailbox: never build inference latency by queueing stale video.
        pending.getAndSet(request)?.let { stale -> runCatching { stale.image.close() } }
        if (busy.compareAndSet(false, true)) executor.execute(::drainLatest)
        return true
    }

    private fun drainLatest() {
        try {
            while (!closed) {
                val request = pending.getAndSet(null) ?: break
                process(request)
            }
        } finally {
            busy.set(false)
            // Close the race where a frame arrived between the final getAndSet and busy=false.
            if (!closed && pending.get() != null && busy.compareAndSet(false, true)) executor.execute(::drainLatest)
        }
    }

    private fun process(request: DetectionRequest) {
        var rawBitmap: Bitmap? = null
        var detectorBitmap: Bitmap? = null
        try {
            val image = request.image
            val rawWidth = image.width
            val rawHeight = image.height
            rawBitmap = yuv420ToBitmap(image)
                ?: throw IllegalStateException("Could not convert AR camera frame")
            val rotation = cameraRotationDegrees(request.cameraId, request.displayRotation)
            detectorBitmap = rotateBitmap(rawBitmap, rotation)

            val task = detector ?: createDetector().also { detector = it }
            val mpImage = BitmapImageBuilder(detectorBitmap).build()
            val result = try {
                task.detect(mpImage)
            } finally {
                runCatching { mpImage.close() }
            }

            data class Candidate(val box: RectF, val label: String, val score: Float)
            val candidates = ArrayList<Candidate>()
            for (detection in result.detections()) {
                val category = detection.categories().maxByOrNull { it.score() } ?: continue
                val name = category.categoryName().lowercase()
                if (name !in VEHICLE_LABELS || category.score() < SCORE_THRESHOLD) continue
                val rawBox = mapDetectorRectToRaw(detection.boundingBox(), rawWidth, rawHeight, rotation)
                if (rawBox.width() < 4f || rawBox.height() < 4f) continue
                candidates += Candidate(rawBox, name, category.score())
            }

            val rawVehicles = ArrayList<RawVehicle>()
            val acceptedBoxes = ArrayList<RectF>()
            for (candidate in candidates.sortedByDescending { it.score }) {
                if (acceptedBoxes.any { intersectionOverUnion(it, candidate.box) >= DETECTION_NMS_IOU }) continue
                val geometry = estimateWorldGeometry(
                    candidate.box, request.camera, request.metricPoints, candidate.label,
                ) ?: estimateWorldGeometryFromImage(candidate.box, request.camera, candidate.label)
                if (geometry == null) continue
                acceptedBoxes += candidate.box
                rawVehicles += RawVehicle(
                    label = candidate.label.uppercase(),
                    confidence = candidate.score,
                    pointWorld = geometry.centerWorld.copyOf(),
                    worldBox = geometry,
                )
                if (rawVehicles.size >= MAX_RESULTS) break
            }

            request.onResult(filterVehicleMotion(rawVehicles, System.currentTimeMillis()))
        } catch (t: Throwable) {
            request.onError(errorText(t))
        } finally {
            runCatching { request.image.close() }
            if (detectorBitmap != null && detectorBitmap !== rawBitmap) runCatching { detectorBitmap?.recycle() }
            runCatching { rawBitmap?.recycle() }
        }
    }

    fun close() {
        closed = true
        pending.getAndSet(null)?.let { runCatching { it.image.close() } }
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
                val state = track.filter.predict(nowMs) ?: continue
                val predicted = state.position
                val distance = pointDistance(predicted, vehicle.pointWorld)
                val ageSeconds = ((nowMs - track.lastSeenMs).coerceAtLeast(0L) / 1000f).coerceAtMost(1.5f)
                val adaptiveGate = (FILTER_BASE_GATE_M + VehicleTrackPolicy.speedMps(state.velocity) * ageSeconds * 1.35f)
                    .coerceAtMost(FILTER_MAX_GATE_M)
                if (distance < bestDistance && distance <= adaptiveGate) {
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
        val axis = VehicleGeometryPolicy.stabilizeAxis(
            previous = previous?.forwardWorld,
            observed = observed?.forwardWorld ?: box.forwardWorld,
            velocity = velocity,
            velocityHeadingMinMps = VELOCITY_HEADING_MIN_MPS,
        )
        val dims = VehicleGeometryPolicy.halfExtents("CAR")
        return WorldBox(
            centerWorld = center.copyOf(3),
            forwardWorld = axis,
            // Never let a few bad depth samples inflate the physical cuboid.
            halfLengthM = observed?.halfLengthM ?: previous?.halfLengthM ?: dims.length,
            halfWidthM = observed?.halfWidthM ?: previous?.halfWidthM ?: dims.width,
            halfHeightM = observed?.halfHeightM ?: previous?.halfHeightM ?: dims.height,
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

    /** Robust depth is used for translation only. Vehicle yaw never comes from sparse-depth PCA. */
    private fun estimateWorldGeometry(
        box: RectF,
        camera: CameraModel,
        metricPoints: List<FloatArray>,
        category: String,
    ): WorldBox? {
        if (box.width() < 4f || box.height() < 4f) return null
        val insetX = box.width() * 0.10f
        val insetY = box.height() * 0.10f
        val central = RectF(box.left + insetX, box.top + insetY, box.right - insetX, box.bottom - insetY)

        data class Support(val p: FloatArray, val distance: Float)
        val supports = ArrayList<Support>()
        for (m in metricPoints) {
            if (m.size < 5 || m[0] !in central.left..central.right || m[1] !in central.top..central.bottom) continue
            val p = floatArrayOf(m[2], m[3], m[4])
            if (!p.all { it.isFinite() }) continue
            val d = pointDistance(camera.translation, p)
            if (d.isFinite() && d in MIN_VEHICLE_DISTANCE_M..MAX_VEHICLE_DISTANCE_M) supports += Support(p, d)
        }
        if (supports.size < MIN_METRIC_SUPPORTS) return null

        val median = supports.map { it.distance }.sorted().let { it[it.size / 2] }
        val tolerance = max(0.35f, median * 0.075f)
        val inliers = supports.filter { abs(it.distance - median) <= tolerance }
            .sortedBy { abs(it.distance - median) }
            .take(96)
        if (inliers.size < MIN_METRIC_INLIERS) return null

        // Median XYZ is much harder for background/ground leakage to drag meters away.
        fun medianAxis(i: Int): Float {
            val values = inliers.map { it.p[i] }.sorted()
            return values[values.size / 2]
        }
        val center = floatArrayOf(medianAxis(0), medianAxis(1), medianAxis(2))
        val dims = VehicleGeometryPolicy.halfExtents(category)
        val axis = VehicleGeometryPolicy.observedAxis(
            camera.translation, center, box.width() / box.height().coerceAtLeast(1f),
        )
        return WorldBox(center, axis, dims.length, dims.width, dims.height)
    }

    /**
     * Detection must not depend on ARCore depth being ready. Estimate metric range from
     * apparent physical height and camera intrinsics; later depth observations correct it.
     */
    private fun estimateWorldGeometryFromImage(
        box: RectF,
        camera: CameraModel,
        category: String,
    ): WorldBox? {
        if (box.height() < MIN_FALLBACK_BOX_PX || camera.fy <= 1f || camera.fx <= 1f) return null
        val dims = VehicleGeometryPolicy.halfExtents(category)
        val physicalHeight = dims.height * 2f
        val distance = (camera.fy * physicalHeight / box.height())
            .coerceIn(MIN_FALLBACK_DISTANCE_M, MAX_FALLBACK_DISTANCE_M)
        val u = box.centerX()
        val v = box.centerY()
        val rayCamera = normalize3(floatArrayOf(
            (u - camera.cx) / camera.fx,
            -(v - camera.cy) / camera.fy,
            -1f,
        )) ?: return null
        val rayWorld = rotateByQuaternion(rayCamera, camera.quaternion)
        val center = FloatArray(3) { i -> camera.translation.getOrElse(i) { 0f } + rayWorld[i] * distance }
        val axis = VehicleGeometryPolicy.observedAxis(
            camera.translation, center, box.width() / box.height().coerceAtLeast(1f),
        )
        return WorldBox(center, axis, dims.length, dims.width, dims.height)
    }

    private fun rotateByQuaternion(v: FloatArray, q: FloatArray): FloatArray {
        val qx = q.getOrElse(0) { 0f }; val qy = q.getOrElse(1) { 0f }
        val qz = q.getOrElse(2) { 0f }; val qw = q.getOrElse(3) { 1f }
        val tx = 2f * (qy * v[2] - qz * v[1])
        val ty = 2f * (qz * v[0] - qx * v[2])
        val tz = 2f * (qx * v[1] - qy * v[0])
        return normalize3(floatArrayOf(
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx),
        )) ?: v.copyOf(3)
    }

    private fun normalize3(v: FloatArray): FloatArray? {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (!n.isFinite() || n < 1e-6f) return null
        return FloatArray(3) { i -> v.getOrElse(i) { 0f } / n }
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left); val top = max(a.top, b.top)
        val right = min(a.right, b.right); val bottom = min(a.bottom, b.bottom)
        val iw = (right - left).coerceAtLeast(0f); val ih = (bottom - top).coerceAtLeast(0f)
        val intersection = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 1e-4f) intersection / union else 0f
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
        private const val SCORE_THRESHOLD = 0.34f
        private const val MAX_RESULTS = 6
        private const val MIN_METRIC_SUPPORTS = 4
        private const val MIN_METRIC_INLIERS = 3
        private const val MIN_VEHICLE_DISTANCE_M = 0.35f
        private const val MAX_VEHICLE_DISTANCE_M = 65f
        private const val FILTER_BASE_GATE_M = 2.4f
        private const val FILTER_MAX_GATE_M = 12.0f
        private const val FILTER_TRACK_TTL_MS = 2_800L
        private const val VELOCITY_HEADING_MIN_MPS = 1.0f
        private const val DETECTION_NMS_IOU = 0.48f
        private const val MIN_FALLBACK_BOX_PX = 10f
        private const val MIN_FALLBACK_DISTANCE_M = 1.0f
        private const val MAX_FALLBACK_DISTANCE_M = 65f
        private val VEHICLE_LABELS = setOf("car", "truck", "bus")
    }
}
