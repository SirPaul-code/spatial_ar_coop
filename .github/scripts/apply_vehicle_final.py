from pathlib import Path
import re


def read(path):
    return Path(path).read_text()


def write(path, text):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


def sub_once(text, pattern, repl, label, flags=0):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly 1 regex match, got {n}")
    return out

# -----------------------------------------------------------------------------
# Pure geometry policy: no depth PCA. Class priors define cuboid dimensions;
# image aspect + camera/target bearing provides the initial 180-degree yaw line;
# velocity takes authority once the track is genuinely moving.
# -----------------------------------------------------------------------------
write('android/app/src/main/java/com/sirpaul/spatialnomap/VehicleGeometryPolicy.kt', r'''package com.sirpaul.spatialnomap

import kotlin.math.abs
import kotlin.math.sqrt

object VehicleGeometryPolicy {
    data class HalfExtents(val length: Float, val width: Float, val height: Float)

    fun halfExtents(label: String): HalfExtents = when (label.uppercase()) {
        "BUS" -> HalfExtents(4.8f, 1.15f, 1.45f)
        "TRUCK" -> HalfExtents(3.2f, 1.05f, 1.20f)
        else -> HalfExtents(2.15f, 0.90f, 0.78f)
    }

    /**
     * A sparse depth cloud cannot tell a vehicle's longitudinal axis reliably: a rear
     * bumper makes PCA point sideways. Use the camera-to-target bearing instead. A
     * strongly wide silhouette is treated as a side view and rotates the axis 90°.
     * The axis is a line, so forward/backward (180°) are intentionally equivalent.
     */
    fun observedAxis(cameraWorld: FloatArray, centerWorld: FloatArray, imageAspect: Float): FloatArray {
        val dx = centerWorld.getOrElse(0) { 0f } - cameraWorld.getOrElse(0) { 0f }
        val dz = centerWorld.getOrElse(2) { 0f } - cameraWorld.getOrElse(2) { 0f }
        val n = sqrt(dx * dx + dz * dz)
        val toward = if (n.isFinite() && n > 1e-4f) floatArrayOf(dx / n, 0f, dz / n)
        else floatArrayOf(1f, 0f, 0f)
        return if (imageAspect >= SIDE_VIEW_ASPECT) {
            floatArrayOf(-toward[2], 0f, toward[0])
        } else toward
    }

    fun stabilizeAxis(
        previous: FloatArray?,
        observed: FloatArray?,
        velocity: FloatArray,
        velocityHeadingMinMps: Float = 1.0f,
    ): FloatArray {
        val speed = horizontalSpeed(velocity)
        val velocityAxis = horizontalUnit(velocity)
        var candidate = if (velocityAxis != null && speed >= velocityHeadingMinMps) {
            velocityAxis
        } else {
            horizontalUnit(observed ?: FloatArray(0))
                ?: horizontalUnit(previous ?: FloatArray(0))
                ?: floatArrayOf(1f, 0f, 0f)
        }
        val prior = horizontalUnit(previous ?: FloatArray(0))
        if (prior != null) {
            if (dot(candidate, prior) < 0f) candidate = floatArrayOf(-candidate[0], 0f, -candidate[2])
            // When nearly stationary, strongly prefer temporal yaw continuity.
            val freshWeight = if (speed >= velocityHeadingMinMps) 0.72f else 0.18f
            candidate = horizontalUnit(floatArrayOf(
                prior[0] * (1f - freshWeight) + candidate[0] * freshWeight,
                0f,
                prior[2] * (1f - freshWeight) + candidate[2] * freshWeight,
            )) ?: candidate
        }
        return candidate
    }

    fun horizontalSpeed(v: FloatArray): Float {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val s = sqrt(x * x + z * z)
        return if (s.isFinite()) s else 0f
    }

    fun horizontalUnit(v: FloatArray): FloatArray? {
        val x = v.getOrElse(0) { 0f }
        val z = v.getOrElse(2) { 0f }
        val n = sqrt(x * x + z * z)
        if (!n.isFinite() || n < 1e-4f) return null
        return floatArrayOf(x / n, 0f, z / n)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a.getOrElse(0) { 0f } * b.getOrElse(0) { 0f } + a.getOrElse(2) { 0f } * b.getOrElse(2) { 0f }

    private const val SIDE_VIEW_ASPECT = 1.85f
}
''')

# Compact vehicle metadata fits inside the existing 48-char owner field, avoiding a
# wire-version break while finally carrying shared cuboid orientation across phones.
write('android/app/src/main/java/com/sirpaul/spatialnomap/VehicleWireMetadata.kt', r'''package com.sirpaul.spatialnomap

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object VehicleWireMetadata {
    const val PREFIX = "AUTO:CAR:"

    data class Parsed(
        val owner: String,
        val label: String,
        val axis: FloatArray?,
    )

    fun encode(owner: String, label: String, axis: FloatArray?): String {
        val cleanOwner = owner.replace('|', '_').take(24).ifBlank { "Peer" }
        val code = when (label.uppercase()) {
            "BUS" -> 'B'
            "TRUCK" -> 'T'
            else -> 'C'
        }
        val unit = axis?.let(VehicleGeometryPolicy::horizontalUnit)
        if (unit == null) return "$PREFIX$cleanOwner|${code}---"
        var deg = Math.toDegrees(atan2(unit[2].toDouble(), unit[0].toDouble())).roundToInt()
        deg = ((deg % 180) + 180) % 180
        return "$PREFIX$cleanOwner|$code${deg.toString().padStart(3, '0')}"
    }

    fun decode(raw: String): Parsed {
        val payload = raw.removePrefix(PREFIX)
        val marker = payload.lastIndexOf('|')
        if (marker < 0 || marker + 5 != payload.length) {
            return Parsed(payload.ifBlank { "Peer" }, "CAR", null)
        }
        val owner = payload.substring(0, marker).ifBlank { "Peer" }
        val suffix = payload.substring(marker + 1)
        val label = when (suffix.firstOrNull()) {
            'B' -> "BUS"
            'T' -> "TRUCK"
            else -> "CAR"
        }
        val deg = suffix.drop(1).toIntOrNull()
        val axis = deg?.let {
            val rad = it.coerceIn(0, 179) * PI / 180.0
            floatArrayOf(cos(rad).toFloat(), 0f, sin(rad).toFloat())
        }
        return Parsed(owner, label, axis)
    }
}
''')

# -----------------------------------------------------------------------------
# VehicleDetector: latest-frame mailbox, depth-independent fallback, no PCA size/yaw.
# -----------------------------------------------------------------------------
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/VehicleDetector.kt')
s = p.read_text()
s = s.replace('import java.util.concurrent.atomic.AtomicBoolean\n', 'import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicReference\n')
s = s.replace('import kotlin.math.atan2\n', '')
s = s.replace('import kotlin.math.cos\n', '')
s = s.replace('import kotlin.math.sin\n', '')

s = replace_once(s, '''    private data class RawVehicle(
        val label: String,
        val confidence: Float,
        val pointWorld: FloatArray,
        val worldBox: WorldBox?,
    )
''', '''    data class CameraModel(
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
''', 'VehicleDetector request models')

s = replace_once(s, '''    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val motionTracks = LinkedHashMap<Int, MotionTrack>()
''', '''    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val pending = AtomicReference<DetectionRequest?>(null)
    private val motionTracks = LinkedHashMap<Int, MotionTrack>()
''', 'VehicleDetector mailbox fields')

submit_pattern = r'''    fun submit\(\n        image: Image,.*?\n        return true\n    \}\n\n    fun close\(\) \{'''
submit_repl = r'''    fun submit(
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

    fun close() {'''
s = sub_once(s, submit_pattern, submit_repl, 'VehicleDetector submit/process', flags=re.S)

s = replace_once(s, '''        closed = true
        executor.execute {
            runCatching { detector?.close() }
            detector = null
            motionTracks.clear()
        }
''', '''        closed = true
        pending.getAndSet(null)?.let { runCatching { it.image.close() } }
        executor.execute {
            runCatching { detector?.close() }
            detector = null
            motionTracks.clear()
        }
''', 'VehicleDetector close pending')

# Adaptive detector-local association.
s = replace_once(s, '''                val predicted = track.filter.predict(nowMs)?.position ?: continue
                val distance = pointDistance(predicted, vehicle.pointWorld)
                if (distance < bestDistance && distance <= FILTER_ASSOCIATION_M) {
''', '''                val state = track.filter.predict(nowMs) ?: continue
                val predicted = state.position
                val distance = pointDistance(predicted, vehicle.pointWorld)
                val ageSeconds = ((nowMs - track.lastSeenMs).coerceAtLeast(0L) / 1000f).coerceAtMost(1.5f)
                val adaptiveGate = (FILTER_BASE_GATE_M + VehicleTrackPolicy.speedMps(state.velocity) * ageSeconds * 1.35f)
                    .coerceAtMost(FILTER_MAX_GATE_M)
                if (distance < bestDistance && distance <= adaptiveGate) {
''', 'VehicleDetector adaptive association')

# Replace box smoothing with geometry policy.
s = sub_once(s,
    r'''    private fun smoothWorldBox\(.*?\n    \}\n\n    private fun cameraRotationDegrees''',
    r'''    private fun smoothWorldBox(
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

    private fun cameraRotationDegrees''',
    'VehicleDetector smoothWorldBox', flags=re.S)

# Replace PCA geometry block up through classDimensionBounds with robust center + fixed cuboid + fallback.
s = sub_once(s,
    r'''    /\*\*\n     \* Build a robust metric centroid.*?    /\*\* min L/W/H followed by max L/W/H, all half-extents\. \*/\n    private fun classDimensionBounds\(category: String\): FloatArray = when \(category\) \{.*?\n    \}\n''',
    r'''    /** Robust depth is used for translation only. Vehicle yaw never comes from sparse-depth PCA. */
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
''',
    'VehicleDetector geometry replacement', flags=re.S)

# Remove now-unused percentile/horizontal helpers/dot if still present (smooth no longer uses them).
s = sub_once(s, r'''    private fun percentile\(sorted: List<Float>.*?\n    private fun yuv420ToBitmap''', '    private fun yuv420ToBitmap', 'VehicleDetector remove PCA helpers', flags=re.S)

s = replace_once(s, '''        private const val SCORE_THRESHOLD = 0.38f
        private const val MAX_RESULTS = 4
        private const val MIN_METRIC_SUPPORTS = 5
        private const val MIN_METRIC_INLIERS = 3
        private const val MIN_VEHICLE_DISTANCE_M = 0.35f
        private const val MAX_VEHICLE_DISTANCE_M = 45f
        private const val FILTER_ASSOCIATION_M = 4.0f
        private const val FILTER_TRACK_TTL_MS = 3_600L
        private const val VELOCITY_HEADING_MIN_MPS = 0.60f
''', '''        private const val SCORE_THRESHOLD = 0.34f
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
''', 'VehicleDetector constants')
p.write_text(s)

# -----------------------------------------------------------------------------
# Track policy: uncertainty grows with time and speed but is bounded.
# -----------------------------------------------------------------------------
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/VehicleTrackPolicy.kt')
s = p.read_text()
s = replace_once(s, '''        val ageSeconds = ((nowMs - lastSeenMs).coerceAtLeast(0L) / 1000f).coerceAtMost(3f)
        val speed = speedMps(velocity)
        return min(maxM, baseM + speed * ageSeconds + ageSeconds * 0.35f)
''', '''        val ageSeconds = ((nowMs - lastSeenMs).coerceAtLeast(0L) / 1000f).coerceAtMost(3f)
        val speed = speedMps(velocity)
        val motionUncertainty = speed * ageSeconds * 1.25f
        val clockAndAlignmentUncertainty = 0.45f + ageSeconds * 0.65f
        return min(maxM, baseM + motionUncertainty + clockAndAlignmentUncertainty)
''', 'VehicleTrackPolicy adaptive gate')
p.write_text(s)

# -----------------------------------------------------------------------------
# Renderer: 8-ish Hz latest-frame detector, no depth prerequisite, camera model,
# shared yaw metadata, stable class boxes, stronger dedupe and no duplicate overlay.
# -----------------------------------------------------------------------------
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/ArRenderer.kt')
s = p.read_text()
s = replace_once(s, '''    private fun maybeDetectVehicles(session: Session, frame: Frame, camera: Camera) {
        if (!coordinator.quality().bothReady || vehicleDetector.isBusy()) return
        val now = System.currentTimeMillis()
        if (now - lastVehicleSubmitMs < VEHICLE_DETECT_INTERVAL_MS) return

        val metric = MetricSupportSampler.sample(frame, camera, VEHICLE_METRIC_BUDGET)
        if (metric.size < 16) return
        val image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
        val accepted = vehicleDetector.submit(
            image = image,
            displayRotation = rotationProvider(),
            cameraId = runCatching { session.cameraConfig.cameraId }.getOrNull(),
            cameraWorld = camera.pose.translation.copyOf(),
            metricPoints = metric,
            onResult = { pendingVehicleDetections.set(it) },
            onError = { pendingVehicleError.set(it) },
        )
        if (accepted) lastVehicleSubmitMs = now
    }
''', '''    private fun maybeDetectVehicles(session: Session, frame: Frame, camera: Camera) {
        if (!coordinator.quality().bothReady) return
        val now = System.currentTimeMillis()
        if (now - lastVehicleSubmitMs < VEHICLE_DETECT_INTERVAL_MS) return

        // Depth enriches translation but must never gate 2D vehicle recognition.
        val metric = MetricSupportSampler.sample(frame, camera, VEHICLE_METRIC_BUDGET)
        val image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
        val intr = camera.imageIntrinsics
        val accepted = vehicleDetector.submit(
            image = image,
            displayRotation = rotationProvider(),
            cameraId = runCatching { session.cameraConfig.cameraId }.getOrNull(),
            camera = VehicleDetector.CameraModel(
                translation = camera.pose.translation.copyOf(),
                quaternion = camera.pose.rotationQuaternion.copyOf(),
                fx = intr.focalLength[0],
                fy = intr.focalLength[1],
                cx = intr.principalPoint[0],
                cy = intr.principalPoint[1],
            ),
            metricPoints = metric,
            onResult = { pendingVehicleDetections.set(it) },
            onError = { pendingVehicleError.set(it) },
        )
        if (accepted) lastVehicleSubmitMs = now
    }
''', 'ArRenderer maybeDetectVehicles')

# Remote metadata parsing and world box orientation.
s = replace_once(s, '''        val now = System.currentTimeMillis()
        val cleanOwner = request.owner.removePrefix(AUTO_CAR_PREFIX).ifBlank { "Peer" }
        synchronized(targetLock) {
''', '''        val now = System.currentTimeMillis()
        val metadata = VehicleWireMetadata.decode(request.owner)
        val cleanOwner = metadata.owner.ifBlank { "Peer" }
        val observedAxis = metadata.axis
        synchronized(targetLock) {
''', 'ArRenderer remote metadata')
s = replace_once(s, '''                updateRemoteVehicle(existingSameId, incomingPoint, cleanOwner, request.confidence, now)
''', '''                updateRemoteVehicle(existingSameId, incomingPoint, cleanOwner, metadata.label, observedAxis, request.confidence, now)
''', 'ArRenderer remote update call')
s = replace_once(s, '''            remoteVehicles[request.id] = DynamicTarget(
                point = incomingPoint.copyOf(3),
                velocity = velocity,
                worldBox = defaultVehicleBox(incomingPoint, velocity, "CAR"),
                owner = cleanOwner,
                label = "CAR",
''', '''            remoteVehicles[request.id] = DynamicTarget(
                point = incomingPoint.copyOf(3),
                velocity = velocity,
                worldBox = defaultVehicleBox(incomingPoint, velocity, metadata.label, preferredAxis = observedAxis),
                owner = cleanOwner,
                label = metadata.label,
''', 'ArRenderer remote initial box')
s = replace_once(s, '''    private fun updateRemoteVehicle(
        track: DynamicTarget,
        incomingPoint: FloatArray,
        owner: String,
        confidence: Float,
        now: Long,
    ) {
''', '''    private fun updateRemoteVehicle(
        track: DynamicTarget,
        incomingPoint: FloatArray,
        owner: String,
        label: String,
        observedAxis: FloatArray?,
        confidence: Float,
        now: Long,
    ) {
''', 'ArRenderer updateRemote signature')
s = replace_once(s, '''        track.owner = owner
        track.confidence = max(track.confidence * 0.35f, confidence)
        track.lastSeenMs = now
        track.worldBox = defaultVehicleBox(track.point, track.velocity, track.label, track.worldBox)
''', '''        track.owner = owner
        track.label = label
        track.confidence = max(track.confidence * 0.35f, confidence)
        track.lastSeenMs = now
        track.worldBox = defaultVehicleBox(track.point, track.velocity, track.label, track.worldBox, observedAxis)
''', 'ArRenderer remote orientation update')

# Send compact metadata for both remote-reservation contribution and owned local track.
s = replace_once(s, '''                coordinator.sendPoi(sharedId, vehicle.pointWorld, "$AUTO_CAR_PREFIX$owner")
''', '''                coordinator.sendPoi(
                    sharedId,
                    vehicle.pointWorld,
                    VehicleWireMetadata.encode(owner, vehicle.label, vehicle.worldBox?.forwardWorld ?: vehicle.velocityWorld),
                )
''', 'ArRenderer shared vehicle metadata send')
s = replace_once(s, '''            coordinator.sendPoi(sharedId, point, "$AUTO_CAR_PREFIX$owner")
''', '''            coordinator.sendPoi(
                sharedId,
                point,
                VehicleWireMetadata.encode(owner, vehicle.label, worldBox?.forwardWorld ?: velocity),
            )
''', 'ArRenderer local vehicle metadata send')

# Geometry stays class-sized and only changes yaw; never preserve corrupted dimensions.
s = replace_once(s, '''    private fun moveBoxCenter(
        box: VehicleDetector.WorldBox,
        center: FloatArray,
        velocity: FloatArray,
    ): VehicleDetector.WorldBox {
        val movingAxis = horizontalUnit(velocity)
        var axis = movingAxis ?: horizontalUnit(box.forwardWorld) ?: floatArrayOf(1f, 0f, 0f)
        val previous = horizontalUnit(box.forwardWorld)
        if (previous != null && dot3(axis, previous) < 0f) axis = FloatArray(3) { -axis[it] }
        return box.copy(centerWorld = center.copyOf(3), forwardWorld = axis)
    }
''', '''    private fun moveBoxCenter(
        box: VehicleDetector.WorldBox,
        center: FloatArray,
        velocity: FloatArray,
    ): VehicleDetector.WorldBox {
        val axis = VehicleGeometryPolicy.stabilizeAxis(
            previous = box.forwardWorld,
            observed = box.forwardWorld,
            velocity = velocity,
            velocityHeadingMinMps = VEHICLE_YAW_FROM_VELOCITY_MPS,
        )
        return box.copy(centerWorld = center.copyOf(3), forwardWorld = axis)
    }
''', 'ArRenderer moveBoxCenter')
s = replace_once(s, '''    private fun defaultVehicleBox(
        center: FloatArray,
        velocity: FloatArray,
        label: String,
        previous: VehicleDetector.WorldBox? = null,
    ): VehicleDetector.WorldBox {
        var axis = horizontalUnit(velocity) ?: previous?.forwardWorld?.let(::horizontalUnit) ?: floatArrayOf(1f, 0f, 0f)
        previous?.forwardWorld?.let { prior ->
            if (dot3(axis, prior) < 0f) axis = FloatArray(3) { -axis[it] }
        }
        val dims = when (label.uppercase(Locale.US)) {
            "BUS" -> floatArrayOf(4.8f, 1.15f, 1.45f)
            "TRUCK" -> floatArrayOf(3.2f, 1.05f, 1.20f)
            else -> floatArrayOf(2.15f, 0.90f, 0.78f)
        }
        return VehicleDetector.WorldBox(
            centerWorld = center.copyOf(3),
            forwardWorld = axis,
            halfLengthM = previous?.halfLengthM ?: dims[0],
            halfWidthM = previous?.halfWidthM ?: dims[1],
            halfHeightM = previous?.halfHeightM ?: dims[2],
        )
    }
''', '''    private fun defaultVehicleBox(
        center: FloatArray,
        velocity: FloatArray,
        label: String,
        previous: VehicleDetector.WorldBox? = null,
        preferredAxis: FloatArray? = null,
    ): VehicleDetector.WorldBox {
        val axis = VehicleGeometryPolicy.stabilizeAxis(
            previous = previous?.forwardWorld,
            observed = preferredAxis,
            velocity = velocity,
            velocityHeadingMinMps = VEHICLE_YAW_FROM_VELOCITY_MPS,
        )
        val dims = VehicleGeometryPolicy.halfExtents(label)
        return VehicleDetector.WorldBox(
            centerWorld = center.copyOf(3),
            forwardWorld = axis,
            halfLengthM = dims.length,
            halfWidthM = dims.width,
            halfHeightM = dims.height,
        )
    }
''', 'ArRenderer defaultVehicleBox')

# Suppress a remote duplicate at render time too; shared state convergence may lag by a packet.
s = replace_once(s, '''        for ((id, target) in remoteVehicleSnapshot) {
            projectDynamicTarget(
''', '''        for ((id, target) in remoteVehicleSnapshot) {
            val duplicateLocal = localVehicleSnapshot.any { (_, local) ->
                VehicleTrackPolicy.samePhysicalVehicle(
                    local.point, local.velocity, local.lastSeenMs,
                    target.point, now, VEHICLE_COAST_MS,
                    DISPLAY_DEDUPE_BASE_GATE_M, DISPLAY_DEDUPE_MAX_GATE_M,
                )
            }
            if (duplicateLocal) continue
            projectDynamicTarget(
''', 'ArRenderer display dedupe')

s = replace_once(s, '''        private const val VEHICLE_DETECT_INTERVAL_MS = 450L
        private const val VEHICLE_METRIC_BUDGET = 5000
        private const val LOCAL_DETECTION_MERGE_M = 1.45f
        private const val LOCAL_VEHICLE_BASE_GATE_M = 1.9f
        private const val LOCAL_VEHICLE_MAX_GATE_M = 4.8f
        private const val CROSS_DEVICE_BASE_GATE_M = 2.1f
        private const val CROSS_DEVICE_MAX_GATE_M = 5.2f
''', '''        private const val VEHICLE_DETECT_INTERVAL_MS = 120L
        private const val VEHICLE_METRIC_BUDGET = 1800
        private const val LOCAL_DETECTION_MERGE_M = 1.8f
        private const val LOCAL_VEHICLE_BASE_GATE_M = 2.4f
        private const val LOCAL_VEHICLE_MAX_GATE_M = 12.0f
        private const val CROSS_DEVICE_BASE_GATE_M = 2.8f
        private const val CROSS_DEVICE_MAX_GATE_M = 14.0f
        private const val DISPLAY_DEDUPE_BASE_GATE_M = 2.4f
        private const val DISPLAY_DEDUPE_MAX_GATE_M = 8.0f
        private const val VEHICLE_YAW_FROM_VELOCITY_MPS = 1.0f
''', 'ArRenderer vehicle constants')
p.write_text(s)

# -----------------------------------------------------------------------------
# Coordinator transforms the peer's shared yaw line into local world before renderer.
# -----------------------------------------------------------------------------
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/AlignmentCoordinator.kt')
s = p.read_text()
s = replace_once(s, '''    private fun publishDynamicPoi(message: WireMessage.Poi) {
        val transform = lockedTransform ?: return
        if (!peerTransformVerified) return
        val p = AlignmentEngine.transformPoint(transform, message.pointWorld)
        listener.onRemotePoi(
            message.id,
            floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()),
            message.owner,
            localConfidence,
        )
    }
''', '''    private fun publishDynamicPoi(message: WireMessage.Poi) {
        val transform = lockedTransform ?: return
        if (!peerTransformVerified) return
        val p = AlignmentEngine.transformPoint(transform, message.pointWorld)
        val metadata = VehicleWireMetadata.decode(message.owner)
        val localAxis = metadata.axis?.let { axis ->
            VehicleGeometryPolicy.horizontalUnit(floatArrayOf(
                (transform[0] * axis[0] + transform[2] * axis[2]).toFloat(),
                0f,
                (transform[8] * axis[0] + transform[10] * axis[2]).toFloat(),
            ))
        }
        listener.onRemotePoi(
            message.id,
            floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()),
            VehicleWireMetadata.encode(metadata.owner, metadata.label, localAxis),
            localConfidence,
        )
    }
''', 'AlignmentCoordinator dynamic yaw transform')
p.write_text(s)

# MainActivity: dynamic tracks are not user-created POIs; stop banner spam.
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/MainActivity.kt')
s = p.read_text()
s = replace_once(s, '''            renderer.setRemoteTarget(id, pointLocal, owner, confidence)
            val shouldAnnounce = pointLocal != null && announcedRemotePoiIds.add(id)
''', '''            renderer.setRemoteTarget(id, pointLocal, owner, confidence)
            val dynamicVehicle = owner.startsWith(VehicleWireMetadata.PREFIX)
            val shouldAnnounce = !dynamicVehicle && pointLocal != null && announcedRemotePoiIds.add(id)
''', 'MainActivity dynamic banner suppression')
p.write_text(s)

# WorldViz labels decode compact metadata instead of leaking it to UI.
p = Path('android/app/src/main/java/com/sirpaul/spatialnomap/WorldViz.kt')
s = p.read_text()
s = replace_once(s, '''                    val clean = cleanOwner(message.owner, localName)
                    localTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "CAR • YOU" else "${clean.ifBlank { "YOU" }} • ${shortId(message.id)}",
''', '''                    val metadata = if (dynamic) VehicleWireMetadata.decode(message.owner) else null
                    val clean = metadata?.owner ?: cleanOwner(message.owner, localName)
                    localTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "${metadata?.label ?: "CAR"} • YOU" else "${clean.ifBlank { "YOU" }} • ${shortId(message.id)}",
''', 'WorldViz local metadata label')
s = replace_once(s, '''                    val clean = cleanOwner(message.owner, peerName)
                    remoteTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "CAR • $clean" else "$clean • ${shortId(message.id)}",
''', '''                    val metadata = if (dynamic) VehicleWireMetadata.decode(message.owner) else null
                    val clean = metadata?.owner ?: cleanOwner(message.owner, peerName)
                    remoteTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "${metadata?.label ?: "CAR"} • $clean" else "$clean • ${shortId(message.id)}",
''', 'WorldViz remote metadata label')
p.write_text(s)

# Tests.
p = Path('android/app/src/test/java/com/sirpaul/spatialnomap/VehicleTrackPolicyTest.kt')
s = p.read_text()
s = replace_once(s, '''    fun movingTrackGetsLargerAssociationGateThanStationaryTrack() {
        val stationary = VehicleTrackPolicy.associationGateM(FloatArray(3), 1_000L, 2_000L)
        val moving = VehicleTrackPolicy.associationGateM(floatArrayOf(3f, 0f, 0f), 1_000L, 2_000L)
        assertTrue(moving > stationary)
    }
''', '''    fun movingTrackGetsLargerAssociationGateThanStationaryTrack() {
        val stationary = VehicleTrackPolicy.associationGateM(FloatArray(3), 1_000L, 2_000L)
        val moving = VehicleTrackPolicy.associationGateM(floatArrayOf(12f, 0f, 0f), 1_000L, 2_000L, 2.4f, 20f)
        assertTrue(moving > stationary)
        assertTrue(moving >= 15f)
    }
''', 'VehicleTrackPolicyTest fast gate')
p.write_text(s)

write('android/app/src/test/java/com/sirpaul/spatialnomap/VehicleGeometryPolicyTest.kt', r'''package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleGeometryPolicyTest {
    @Test fun classPriorsStayPhysical() {
        val car = VehicleGeometryPolicy.halfExtents("CAR")
        assertEquals(2.15f, car.length, 0.001f)
        assertEquals(0.90f, car.width, 0.001f)
        assertEquals(0.78f, car.height, 0.001f)
    }

    @Test fun rearViewUsesBearingButWideSideViewRotatesNinetyDegrees() {
        val camera = floatArrayOf(0f, 1f, 0f)
        val target = floatArrayOf(0f, 1f, -10f)
        val rear = VehicleGeometryPolicy.observedAxis(camera, target, 1.3f)
        val side = VehicleGeometryPolicy.observedAxis(camera, target, 2.6f)
        val dot = rear[0] * side[0] + rear[2] * side[2]
        assertTrue(kotlin.math.abs(dot) < 0.05f)
    }

    @Test fun movingVelocityTakesYawAuthority() {
        val axis = VehicleGeometryPolicy.stabilizeAxis(
            previous = floatArrayOf(1f, 0f, 0f),
            observed = floatArrayOf(1f, 0f, 0f),
            velocity = floatArrayOf(0f, 0f, 12f),
            velocityHeadingMinMps = 1f,
        )
        assertTrue(kotlin.math.abs(axis[2]) > 0.65f)
    }
}
''')

write('android/app/src/test/java/com/sirpaul/spatialnomap/VehicleWireMetadataTest.kt', r'''package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleWireMetadataTest {
    @Test fun roundTripFitsExistingOwnerBudget() {
        val encoded = VehicleWireMetadata.encode("Galaxy S942B", "CAR", floatArrayOf(0f, 0f, -1f))
        assertTrue(encoded.length <= 48)
        val parsed = VehicleWireMetadata.decode(encoded)
        assertEquals("Galaxy S942B", parsed.owner)
        assertEquals("CAR", parsed.label)
        assertNotNull(parsed.axis)
    }

    @Test fun legacyDynamicOwnerStillParses() {
        val parsed = VehicleWireMetadata.decode("AUTO:CAR:Peer")
        assertEquals("Peer", parsed.owner)
        assertEquals("CAR", parsed.label)
    }
}
''')

write('sdk/checkpoints/SPATIAL_SYNC_VEHICLE_FINAL_2026-09-15.md', r'''# Spatial Sync final vehicle tracking pass — 2026-09-15

## Why this pass exists

Physical two-phone testing exposed three real defects in the previous vehicle path:

1. sparse-depth PCA could rotate a vehicle cuboid 90 degrees or toward background geometry;
2. detection was gated by depth availability and throttled to 450 ms, so road-speed vehicles could cross the association gate between observations;
3. dynamic packets carried only XYZ, so a peer could not reproduce the sender's cuboid yaw and could render a second/misaligned box.

## Final architecture

- EfficientDet-Lite0 remains on-device recognition, but the detector now uses a **latest-frame mailbox**. If inference is busy, stale pending camera images are closed and replaced by the newest frame instead of adding latency.
- Submission cadence is 120 ms (~8.3 Hz target). Actual inference rate is hardware-dependent, but inference always consumes the freshest available frame.
- 2D recognition is **not gated by depth**. ARCore depth/point-cloud data enrich translation when available; otherwise an intrinsics + class-height range estimate supplies a temporary 3D observation which later metric observations correct.
- Sparse metric supports determine **translation only**. PCA yaw and PCA dimensions are removed.
- Cuboid dimensions are fixed physical class priors (car/truck/bus), preventing a few background depth points from creating giant boxes.
- Initial yaw uses camera-to-target bearing plus silhouette aspect (rear/front vs side-view line). Once horizontal vehicle velocity is >= 1 m/s, velocity becomes the yaw authority with temporal continuity.
- Detector-local and room-level association gates grow with observed speed and elapsed time, bounded to prevent arbitrary merges.
- Duplicate 2D boxes are NMS-filtered before 3D estimation.
- Dynamic vehicle owner metadata compactly carries class + 180-degree yaw inside the existing V6 owner field, so no protocol-version break is required. AlignmentCoordinator rotates the peer yaw line through the shared-world transform before handing it to ArRenderer.
- Render-time cross-device duplicate suppression is an additional safety net while canonical IDs converge.
- Dynamic vehicle packets no longer trigger the user-facing "POI added" banner/haptic.

## Scope boundary

Vehicle tracks remain dynamic and are intentionally NOT StableAR material attachments or permanent ARCore anchors. StableAR continues to own static material POIs.

## Verification gate

The applying CI workflow must pass:

```
gradle -p android --no-daemon --stacktrace :app:testDebugUnitTest :app:assembleDebug
```

and confirm the packaged XFeat model before committing/publishing the APK.

## Physical final acceptance

1. parked vehicle rear/front: cuboid longitudinal axis must not randomly sit across the bumper;
2. side view: cuboid may choose either 180-degree direction but must follow the vehicle's long axis;
3. no giant cuboid caused by wall/road/tree depth leaking into the 2D box;
4. drive-by vehicle at road speed must be detected while crossing the camera, with no stale-frame inference backlog;
5. second phone looking at the same vehicle must converge to one logical shared target, not persistent green + blue duplicates;
6. one phone turns away while the other still observes: track stays alive; after both lose it, expiry remains bounded;
7. remote cuboid orientation should agree with sender after the shared-world transform;
8. LOCKED alignment/static StableAR POIs must remain unaffected.

## Resume instruction

If physical tests still fail, log the exact screenshot/video + approximate range/speed and instrument detector inference time, fallback-vs-metric source, track id, shared id, association distance/gate and yaw source. Do not return to sparse-depth PCA.
''')

print('Final vehicle tracking pass staged.')
