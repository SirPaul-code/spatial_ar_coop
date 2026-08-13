package com.sirpaul.spatialarcoop.vision

import android.app.ActivityManager
import android.content.Context
import android.graphics.RectF
import android.os.Build
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.sirpaul.spatialarcoop.util.FileLogger
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min


data class CaptureGeometry(
    val siteFromCamera: FloatArray?,
    val focalLength: FloatArray,
    val principalPoint: FloatArray,
    val depthSnapshot: DepthSnapshot? = null
)

data class PoseLandmark2D(
    val index: Int,
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class Detection2D(
    val label: String,
    val confidence: Float,
    val rawBoundingBox: RectF,
    val rawBottomCenter: FloatArray,
    val capturedAtMs: Long,
    val rawImageWidth: Int,
    val rawImageHeight: Int,
    val temporalId: String? = null,
    val temporallyConfirmed: Boolean = true,
    val captureGeometry: CaptureGeometry? = null,
    val poseLandmarks: List<PoseLandmark2D> = emptyList()
)

class ObjectDetectorEngine(
    context: Context,
    private val threshold: Float,
    private val logger: FileLogger,
    private val onResult: (List<Detection2D>, inferenceMs: Long) -> Unit,
    private val onError: (String) -> Unit,
    private val onRuntimeState: (DetectorRuntimeState) -> Unit = {}
) : Closeable {
    private val appContext = context.applicationContext
    private val detectorExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "spatial-object-detector") }
    private val busy = AtomicBoolean(false)
    private val temporalTracker = TemporalDetectionTracker(threshold)
    private val lowRamDevice = (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val emulator = Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("Emulator", true)
    private val policy = DetectorRuntimePolicy(lowRamDevice || emulator)

    @Volatile private var closed = false
    @Volatile private var detector: ObjectDetector? = null
    @Volatile private var activeProfile = policy.profile
    private var poseLandmarker: PoseLandmarker? = null
    private var submittedFrames = 0L
    private var resultFrames = 0L
    private var droppedFrames = 0L

    init {
        detectorExecutor.execute {
            runCatching { ensureDetectorOnWorker() }
                .onFailure(::handleDetectorFailureOnWorker)
        }
    }

    fun submit(
        frame: YuvFrame,
        rotationDegrees: Int,
        capturedAtMs: Long,
        captureGeometry: CaptureGeometry? = null
    ): Boolean {
        if (closed) return false
        submittedFrames += 1
        if (!busy.compareAndSet(false, true)) {
            droppedFrames += 1
            emitRuntimeState("busy-drop")
            return false
        }

        detectorExecutor.execute {
            try {
                ensureDetectorOnWorker()
                val startNs = System.nanoTime()
                val rawBitmap = YuvFrameConverter.toBitmap(frame)
                val upright = YuvFrameConverter.rotate(rawBitmap, rotationDegrees)
                val image = BitmapImageBuilder(upright).build()
                var inferenceMs = 0L
                try {
                    val result = (detector ?: error("Object detector is not initialized")).detect(image)
                    inferenceMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    val rawCandidates = result.detections().mapNotNull { detection ->
                        val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                        val label = category.categoryName().lowercase()
                        if (label !in ALLOWED_LABELS) return@mapNotNull null
                        val box = detection.boundingBox()
                        val corners = arrayOf(
                            YuvFrameConverter.rotatedToRaw(box.left, box.top, frame.width, frame.height, rotationDegrees),
                            YuvFrameConverter.rotatedToRaw(box.right, box.top, frame.width, frame.height, rotationDegrees),
                            YuvFrameConverter.rotatedToRaw(box.right, box.bottom, frame.width, frame.height, rotationDegrees),
                            YuvFrameConverter.rotatedToRaw(box.left, box.bottom, frame.width, frame.height, rotationDegrees)
                        )
                        DetectionCandidate2D(
                            label = label,
                            confidence = category.score(),
                            left = corners.minOf { it[0] },
                            top = corners.minOf { it[1] },
                            right = corners.maxOf { it[0] },
                            bottom = corners.maxOf { it[1] }
                        )
                    }

                    val candidates = suppressOverlaps(rawCandidates)
                    val tracked = temporalTracker.update(candidates, capturedAtMs)
                    val confirmed = tracked.filter { it.confirmed }
                    val poseByTemporalId = if (confirmed.any { it.label == "person" }) {
                        detectPersonPoses(
                            image = image,
                            uprightWidth = upright.width,
                            uprightHeight = upright.height,
                            rawWidth = frame.width,
                            rawHeight = frame.height,
                            rotationDegrees = rotationDegrees,
                            people = confirmed.filter { it.label == "person" }
                        )
                    } else emptyMap()

                    val detections = confirmed.map { detection ->
                        val rawBox = RectF(detection.left, detection.top, detection.right, detection.bottom)
                        val pose = poseByTemporalId[detection.temporalId].orEmpty()
                        val contact = if (detection.label == "person") poseGroundContact(pose) else null
                        Detection2D(
                            label = detection.label,
                            confidence = detection.confidence,
                            rawBoundingBox = rawBox,
                            rawBottomCenter = contact ?: floatArrayOf(
                                rawBox.centerX(),
                                rawBox.bottom - rawBox.height() * BOTTOM_CENTER_INSET
                            ),
                            capturedAtMs = capturedAtMs,
                            rawImageWidth = frame.width,
                            rawImageHeight = frame.height,
                            temporalId = detection.temporalId,
                            temporallyConfirmed = true,
                            captureGeometry = captureGeometry,
                            poseLandmarks = pose
                        )
                    }

                    resultFrames += 1
                    onResult(detections, inferenceMs)
                    emitRuntimeState("image")
                } finally {
                    image.close()
                    if (upright !== rawBitmap) upright.recycle()
                    rawBitmap.recycle()
                }

                policy.observeLatency(inferenceMs, System.currentTimeMillis())?.let(::reconfigureOnWorker)
            } catch (error: Throwable) {
                handleDetectorFailureOnWorker(error)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    private fun ensureDetectorOnWorker() {
        if (closed || detector != null) return
        var profile = activeProfile
        try {
            detector = createDetector(profile)
        } catch (gpuError: Throwable) {
            if (profile.delegate != DetectorDelegateProfile.GPU) throw gpuError
            logger.warn(
                "GPU object detector initialization failed; falling back to CPU",
                mapOf("model" to profile.model.displayName, "error" to (gpuError.message ?: gpuError.javaClass.simpleName))
            )
            profile = policy.gpuFailure(System.currentTimeMillis()) ?: profile.copy(delegate = DetectorDelegateProfile.CPU)
            activeProfile = profile
            detector = createDetector(profile)
        }
        activeProfile = profile
        logger.info(
            "Object detector initialized",
            mapOf(
                "model" to profile.model.displayName,
                "delegate" to profile.delegate.name,
                "runningMode" to RunningMode.IMAGE.name,
                "userThreshold" to threshold,
                "lowRam" to lowRamDevice,
                "emulator" to emulator
            )
        )
        emitRuntimeState("ready")
    }

    private fun createDetector(profile: DetectorRuntimeProfile): ObjectDetector {
        val modelThreshold = minOf(threshold, MIN_MODEL_SCORE_THRESHOLD)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(profile.model.assetName)
            .setDelegate(if (profile.delegate == DetectorDelegateProfile.GPU) Delegate.GPU else Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(modelThreshold)
            .setCategoryAllowlist(ALLOWED_LABELS.toList())
            .setMaxResults(MAX_RESULTS)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        return ObjectDetector.createFromOptions(appContext, options)
    }

    private fun reconfigureOnWorker(profile: DetectorRuntimeProfile) {
        if (closed || profile == activeProfile) return
        val old = activeProfile
        runCatching { detector?.close() }
        detector = null
        activeProfile = profile
        try {
            ensureDetectorOnWorker()
            logger.info(
                "Object detector runtime profile changed",
                mapOf("from" to "${old.model.name}/${old.delegate.name}", "to" to "${activeProfile.model.name}/${activeProfile.delegate.name}")
            )
        } catch (error: Throwable) {
            handleDetectorFailureOnWorker(error)
        }
    }

    private fun handleDetectorFailureOnWorker(error: Throwable) {
        if (closed) return
        logger.warn(
            "Object detector error",
            mapOf(
                "model" to activeProfile.model.displayName,
                "delegate" to activeProfile.delegate.name,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        )
        if (activeProfile.delegate == DetectorDelegateProfile.GPU) {
            val fallback = policy.gpuFailure(System.currentTimeMillis())
                ?: activeProfile.copy(delegate = DetectorDelegateProfile.CPU)
            runCatching { detector?.close() }
            detector = null
            activeProfile = fallback
            runCatching { ensureDetectorOnWorker() }
                .onSuccess {
                    logger.info("Object detector recovered on CPU", mapOf("model" to activeProfile.model.displayName))
                    emitRuntimeState("gpu-fallback")
                }
                .onFailure { fallbackError ->
                    logger.error("CPU object detector fallback failed", fallbackError)
                    onError(fallbackError.message ?: fallbackError.javaClass.simpleName)
                }
        } else {
            onError(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun emitRuntimeState(note: String) {
        onRuntimeState(
            DetectorRuntimeState(
                profile = activeProfile,
                ewmaLatencyMs = policy.ewmaLatencyMs,
                submittedFrames = submittedFrames,
                resultFrames = resultFrames,
                droppedFrames = droppedFrames,
                switches = policy.switches,
                note = note
            )
        )
    }

    private fun detectPersonPoses(
        image: MPImage,
        uprightWidth: Int,
        uprightHeight: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        people: List<TrackedDetection2D>
    ): Map<String, List<PoseLandmark2D>> {
        return runCatching {
            val poseCandidates = poseLandmarker().detect(image).landmarks().mapNotNull { landmarks ->
                val rawLandmarks = landmarks.mapIndexedNotNull { index, landmark ->
                    val x = landmark.x() * uprightWidth
                    val y = landmark.y() * uprightHeight
                    if (!x.isFinite() || !y.isFinite()) return@mapIndexedNotNull null
                    val raw = YuvFrameConverter.rotatedToRaw(x, y, rawWidth, rawHeight, rotationDegrees)
                    val visibility = landmark.visibility().orElse(1f)
                    val presence = landmark.presence().orElse(1f)
                    PoseLandmark2D(
                        index = index,
                        x = raw[0],
                        y = raw[1],
                        confidence = min(visibility, presence).coerceIn(0f, 1f)
                    )
                }
                PoseCandidate.from(rawLandmarks)
            }.toMutableList()

            buildMap {
                people.sortedByDescending { it.confidence }.forEach { person ->
                    val best = poseCandidates
                        .map { candidate -> candidate to poseAssociationScore(person, candidate) }
                        .maxByOrNull { it.second }
                        ?.takeIf { it.second >= MIN_POSE_ASSOCIATION_SCORE }
                        ?.first
                    if (best != null) {
                        poseCandidates.remove(best)
                        put(person.temporalId, best.landmarks.filter { it.index in SHARED_POSE_INDICES })
                    }
                }
            }
        }.onFailure { error ->
            logger.warn("Pose landmark detection failed", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
        }.getOrDefault(emptyMap())
    }

    private fun poseLandmarker(): PoseLandmarker {
        poseLandmarker?.let { return it }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(MAX_POSES)
            .setMinPoseDetectionConfidence(POSE_DETECTION_CONFIDENCE)
            .setMinPosePresenceConfidence(POSE_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(POSE_TRACKING_CONFIDENCE)
            .build()
        return PoseLandmarker.createFromOptions(appContext, options).also {
            poseLandmarker = it
            logger.info("Pose landmarker initialized", mapOf("model" to "Pose Landmarker Full float16", "maxPoses" to MAX_POSES))
        }
    }

    private fun poseGroundContact(pose: List<PoseLandmark2D>): FloatArray? {
        val feet = pose.filter { it.index in POSE_GROUND_INDICES && it.confidence >= POSE_GROUND_CONFIDENCE }
        if (feet.size < 2) return null
        val sortedX = feet.map { it.x }.sorted()
        val sortedY = feet.map { it.y }.sorted()
        return floatArrayOf(sortedX[sortedX.size / 2], sortedY[sortedY.size / 2])
    }

    private fun suppressOverlaps(values: List<DetectionCandidate2D>): List<DetectionCandidate2D> {
        val kept = mutableListOf<DetectionCandidate2D>()
        values.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = kept.any { existing ->
                if (existing.label != candidate.label) return@any false
                val overlap = overlapMetrics(existing, candidate)
                overlap.iou >= nmsThreshold(candidate.label) ||
                    (candidate.label in CONTAINMENT_SUPPRESSED_LABELS && overlap.smallerCoverage >= CONTAINMENT_THRESHOLD)
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private data class OverlapMetrics(val iou: Float, val smallerCoverage: Float)

    private fun overlapMetrics(a: DetectionCandidate2D, b: DetectionCandidate2D): OverlapMetrics {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return OverlapMetrics(0f, 0f)
        val union = a.area + b.area - intersection
        val smaller = minOf(a.area, b.area).coerceAtLeast(1f)
        return OverlapMetrics(
            iou = if (union > 0f) intersection / union else 0f,
            smallerCoverage = intersection / smaller
        )
    }

    private fun poseAssociationScore(person: TrackedDetection2D, pose: PoseCandidate): Float {
        val left = maxOf(person.left, pose.left)
        val top = maxOf(person.top, pose.top)
        val right = minOf(person.right, pose.right)
        val bottom = minOf(person.bottom, pose.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val personArea = ((person.right - person.left).coerceAtLeast(1f) * (person.bottom - person.top).coerceAtLeast(1f))
        val poseArea = ((pose.right - pose.left).coerceAtLeast(1f) * (pose.bottom - pose.top).coerceAtLeast(1f))
        val union = personArea + poseArea - intersection
        val iou = if (union > 0f) intersection / union else 0f
        val centerInside = pose.centerX in person.left..person.right && pose.centerY in person.top..person.bottom
        return iou + if (centerInside) 0.24f else 0f
    }

    private fun nmsThreshold(label: String): Float = when (label) {
        "person", "car" -> 0.35f
        "bird" -> 0.55f
        else -> 0.45f
    }

    override fun close() {
        if (closed) return
        closed = true
        temporalTracker.clear()
        detectorExecutor.execute {
            runCatching { poseLandmarker?.close() }
            poseLandmarker = null
            runCatching { detector?.close() }
            detector = null
        }
        detectorExecutor.shutdown()
    }

    private data class PoseCandidate(
        val landmarks: List<PoseLandmark2D>,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f

        companion object {
            fun from(values: List<PoseLandmark2D>): PoseCandidate? {
                val visible = values.filter { it.confidence >= POSE_BOX_CONFIDENCE }
                if (visible.size < MIN_POSE_BOX_JOINTS) return null
                return PoseCandidate(
                    landmarks = values,
                    left = visible.minOf { it.x },
                    top = visible.minOf { it.y },
                    right = visible.maxOf { it.x },
                    bottom = visible.maxOf { it.y }
                )
            }
        }
    }

    companion object {
        private val ALLOWED_LABELS = linkedSetOf("person", "car", "bird", "dog", "cat")
        private val CONTAINMENT_SUPPRESSED_LABELS = setOf("person", "car")
        private val SHARED_POSE_INDICES = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 31, 32)
        private val POSE_GROUND_INDICES = setOf(27, 28, 31, 32)
        private const val CONTAINMENT_THRESHOLD = 0.72f
        private const val MIN_MODEL_SCORE_THRESHOLD = 0.10f
        private const val BOTTOM_CENTER_INSET = 0.04f
        private const val MAX_RESULTS = 40
        private const val MAX_POSES = 4
        private const val POSE_DETECTION_CONFIDENCE = 0.48f
        private const val POSE_PRESENCE_CONFIDENCE = 0.45f
        private const val POSE_TRACKING_CONFIDENCE = 0.50f
        private const val POSE_BOX_CONFIDENCE = 0.28f
        private const val POSE_GROUND_CONFIDENCE = 0.42f
        private const val MIN_POSE_BOX_JOINTS = 8
        private const val MIN_POSE_ASSOCIATION_SCORE = 0.12f
    }
}
