package com.sirpaul.spatialarcoop.vision

import com.sirpaul.spatialarcoop.data.PoseJoint
import com.sirpaul.spatialarcoop.data.SpatialTrack
import com.sirpaul.spatialarcoop.data.defaultTrackExtent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

data class SpatialObservation(
    val label: String,
    val confidence: Float,
    val position: FloatArray,
    val observedAtMs: Long,
    val uncertaintyMeters: Float = 0.35f,
    val associationKey: String? = null,
    val extentMeters: FloatArray = defaultTrackExtent(label),
    val yawRadians: Float = 0f,
    val requiredHits: Int = 2,
    val poseJoints: List<PoseJoint> = emptyList(),
    val spatialMethod: String = "unknown",
    val terrainY: Float? = null,
    val depthConfidence: Float? = null
)

class DetectionTracker(private val sourceId: String) {
    private data class State(
        val id: String,
        var label: String,
        var associationKey: String?,
        var confidence: Float,
        var position: FloatArray,
        var velocity: FloatArray,
        var uncertaintyMeters: Float,
        var extentMeters: FloatArray,
        var yawRadians: Float,
        var requiredHits: Int,
        var lastSeenAtMs: Long,
        var hitCount: Int,
        var rejectedMeasurements: Int,
        var rejectedPosition: FloatArray?,
        var rejectedAtMs: Long?,
        var poseJoints: List<PoseJoint>,
        var poseLastSeenAtMs: Long?,
        var spatialMethod: String,
        var terrainY: Float?,
        var depthConfidence: Float?
    )

    private val states = linkedMapOf<String, State>()
    private var nextId = 1L

    @Synchronized
    fun update(observations: List<SpatialObservation>, nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        val unmatched = states.values.toMutableSet()

        observations.sortedByDescending { it.confidence }.forEach { observation ->
            val hinted = observation.associationKey?.let { key ->
                unmatched.firstOrNull { it.label == observation.label && it.associationKey == key }
            }
            val spatial = hinted ?: bestSpatialCandidate(unmatched, observation)
            val reacquired = spatial ?: bestConservativeReacquire(unmatched, observation)

            if (reacquired == null) {
                val id = "t${nextId++}"
                states[id] = State(
                    id = id,
                    label = observation.label,
                    associationKey = observation.associationKey,
                    confidence = observation.confidence,
                    position = observation.position.copyOf(),
                    velocity = floatArrayOf(0f, 0f, 0f),
                    uncertaintyMeters = observation.uncertaintyMeters,
                    extentMeters = observation.extentMeters.copyOf(),
                    yawRadians = normalizeAngle(observation.yawRadians),
                    requiredHits = observation.requiredHits.coerceIn(2, 6),
                    lastSeenAtMs = observation.observedAtMs,
                    hitCount = 1,
                    rejectedMeasurements = 0,
                    rejectedPosition = null,
                    rejectedAtMs = null,
                    poseJoints = copyPose(observation.poseJoints),
                    poseLastSeenAtMs = observation.observedAtMs.takeIf { observation.poseJoints.isNotEmpty() },
                    spatialMethod = observation.spatialMethod,
                    terrainY = observation.terrainY,
                    depthConfidence = observation.depthConfidence
                )
            } else {
                unmatched.remove(reacquired)
                applyObservation(reacquired, observation)
            }
        }

        expire(nowMs)
        return states.values.filter(::isPublishable).map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun current(nowMs: Long = System.currentTimeMillis()): List<SpatialTrack> {
        expire(nowMs)
        return states.values.filter(::isPublishable).map { toPublicTrack(it, nowMs) }
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun isPublishable(state: State): Boolean = state.hitCount >= state.requiredHits

    private fun bestSpatialCandidate(unmatched: Set<State>, observation: SpatialObservation): State? =
        unmatched
            .asSequence()
            .filter { it.label == observation.label }
            .map { it to predictedDistance(it, observation) }
            .filter { (state, distance) -> distance <= associationRadius(state, observation) }
            .minByOrNull { it.second }
            ?.first

    private fun bestConservativeReacquire(unmatched: Set<State>, observation: SpatialObservation): State? {
        val candidates = unmatched
            .asSequence()
            .filter { it.label == observation.label }
            .map { it to predictedDistance(it, observation) }
            .filter { (_, distance) -> distance <= reacquireRadius(observation.label) }
            .sortedBy { it.second }
            .toList()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first().first
        val first = candidates[0]
        val second = candidates[1]
        return if (first.second + REACQUIRE_MARGIN_METERS < second.second * REACQUIRE_RATIO) first.first else null
    }

    private fun applyObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(1.5f)
        val predicted = predictedPosition(state, observation.observedAtMs)
        val residual = FloatArray(3) { index -> observation.position[index] - predicted[index] }
        val residualDistance = magnitude(residual)
        val gate = measurementGate(state, observation, dt)

        state.associationKey = observation.associationKey ?: state.associationKey
        state.confidence = state.confidence * 0.38f + observation.confidence * 0.62f

        if (residualDistance > gate) {
            if (shouldRebaseAfterRejectedMeasurements(state, observation)) {
                rebaseObservation(state, observation)
                return
            }

            state.rejectedMeasurements += 1
            state.rejectedPosition = observation.position.copyOf()
            state.rejectedAtMs = observation.observedAtMs
            val damping = when {
                state.rejectedMeasurements >= 3 -> 0.65f
                state.rejectedMeasurements >= 2 -> 0.78f
                else -> 0.92f
            }
            state.velocity = FloatArray(3) { index -> state.velocity[index] * damping }
            state.uncertaintyMeters = (maxOf(state.uncertaintyMeters, observation.uncertaintyMeters) + 0.10f)
                .coerceAtMost(MAX_UNCERTAINTY_METERS)
            // Important: a rejected 3D sample is not an accepted position update. Keeping the
            // previous lastSeenAtMs lets a genuinely stale hypothesis expire instead of refreshing
            // a frozen marker forever. A moving car can still rebase after repeated physically
            // plausible measurements below.
            return
        }

        clearRejected(state)
        val apparentSpeed = residualDistance / dt
        val alpha = positionAlpha(
            observation.label,
            residualDistance,
            apparentSpeed,
            observation.uncertaintyMeters,
            observation.depthConfidence
        )
        val corrected = FloatArray(3) { index -> predicted[index] + alpha * residual[index] }
        val groundContactMeasurement = isGroundContactMethod(observation.spatialMethod)
        if (groundContactMeasurement) corrected[1] = observation.position[1]

        val measuredVelocity = FloatArray(3) { index -> residual[index] / dt }
        val beta = when {
            observation.label == "car" && apparentSpeed > 1.2f -> 0.52f
            observation.label == "car" -> 0.24f
            apparentSpeed > 1.2f -> 0.28f
            else -> 0.16f
        }
        var velocity = FloatArray(3) { index ->
            state.velocity[index] * (1f - beta) + measuredVelocity[index] * beta
        }
        velocity = clampMagnitude(velocity, maxSpeed(observation.label))
        if (groundContactMeasurement) velocity[1] *= 0.06f
        if (residualDistance < STATIONARY_RESIDUAL_METERS && magnitude(velocity) < STATIONARY_SPEED_METERS_PER_SECOND) {
            velocity = floatArrayOf(0f, 0f, 0f)
        }

        state.position = corrected
        state.velocity = velocity
        state.uncertaintyMeters = state.uncertaintyMeters * 0.48f + observation.uncertaintyMeters * 0.52f
        state.extentMeters = FloatArray(3) { index ->
            state.extentMeters.getOrElse(index) { observation.extentMeters[index] } * 0.68f +
                observation.extentMeters[index] * 0.32f
        }
        updateYaw(state, observation, velocity)
        if (observation.poseJoints.isNotEmpty()) {
            state.poseJoints = blendPose(state.poseJoints, observation.poseJoints)
            state.poseLastSeenAtMs = observation.observedAtMs
        }
        state.spatialMethod = observation.spatialMethod
        state.terrainY = observation.terrainY
        state.depthConfidence = observation.depthConfidence
        state.requiredHits = min(state.requiredHits, observation.requiredHits.coerceIn(2, 6))
        state.lastSeenAtMs = observation.observedAtMs
        state.hitCount += 1
    }

    private fun shouldRebaseAfterRejectedMeasurements(state: State, observation: SpatialObservation): Boolean {
        if (observation.label != "car") return false
        if (state.rejectedMeasurements < CAR_REBASE_REQUIRED_PRIOR_REJECTS) return false
        if (observation.confidence < CAR_REBASE_MIN_CONFIDENCE) return false
        val previousRejected = state.rejectedPosition ?: return false
        val previousRejectedAtMs = state.rejectedAtMs ?: return false

        val sampleDt = ((observation.observedAtMs - previousRejectedAtMs).coerceAtLeast(1L) / 1000f)
            .coerceAtMost(1.0f)
        val sampleStep = horizontalDistance(previousRejected, observation.position)
        val maxSampleStep = CAR_REBASE_STEP_BASE_METERS + CAR_REACQUIRE_MAX_SPEED_METERS_PER_SECOND * sampleDt
        if (sampleStep > maxSampleStep) return false

        val acceptedDt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f)
            .coerceAtMost(2.0f)
        val displacement = horizontalDistance(state.position, observation.position)
        val maxDisplacement = CAR_REBASE_BASE_METERS + CAR_REACQUIRE_MAX_SPEED_METERS_PER_SECOND * acceptedDt
        if (displacement > maxDisplacement) return false
        if (abs(observation.position[1] - state.position[1]) > CAR_REBASE_MAX_VERTICAL_METERS) return false

        return true
    }

    private fun rebaseObservation(state: State, observation: SpatialObservation) {
        val dt = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(1L) / 1000f).coerceAtMost(2.0f)
        var measuredVelocity = FloatArray(3) { index -> (observation.position[index] - state.position[index]) / dt }
        measuredVelocity = clampMagnitude(measuredVelocity, maxSpeed(observation.label))
        if (isGroundContactMethod(observation.spatialMethod)) measuredVelocity[1] *= 0.04f

        state.position = observation.position.copyOf()
        state.velocity = FloatArray(3) { index -> state.velocity[index] * 0.18f + measuredVelocity[index] * 0.82f }
        state.uncertaintyMeters = maxOf(observation.uncertaintyMeters, state.uncertaintyMeters * 0.72f)
            .coerceAtMost(MAX_UNCERTAINTY_METERS)
        state.extentMeters = FloatArray(3) { index ->
            state.extentMeters.getOrElse(index) { observation.extentMeters[index] } * 0.45f +
                observation.extentMeters[index] * 0.55f
        }
        updateYaw(state, observation, state.velocity, forceMotion = true)
        state.spatialMethod = observation.spatialMethod
        state.terrainY = observation.terrainY
        state.depthConfidence = observation.depthConfidence
        state.requiredHits = min(state.requiredHits, observation.requiredHits.coerceIn(2, 6))
        state.lastSeenAtMs = observation.observedAtMs
        state.hitCount += 1
        clearRejected(state)
    }

    private fun updateYaw(
        state: State,
        observation: SpatialObservation,
        velocity: FloatArray,
        forceMotion: Boolean = false
    ) {
        if (observation.label == "car") {
            val speed = planarSpeed(velocity)
            if (forceMotion || speed >= CAR_YAW_FROM_SPEED_METERS_PER_SECOND) {
                if (speed >= CAR_MIN_VALID_MOTION_YAW_SPEED_METERS_PER_SECOND) {
                    val target = -atan2(velocity[0], velocity[2])
                    val alpha = (0.46f + (speed / 7f).coerceIn(0f, 1f) * 0.36f).coerceIn(0.46f, 0.82f)
                    state.yawRadians = blendAngle(state.yawRadians, target, alpha)
                }
            }
            // A 2D detector does not know the true yaw of a stationary car. Keep the last stable
            // orientation instead of rotating the cuboid whenever the camera viewpoint changes.
            return
        }
        state.yawRadians = blendAngle(state.yawRadians, observation.yawRadians, 0.26f)
    }

    private fun clearRejected(state: State) {
        state.rejectedMeasurements = 0
        state.rejectedPosition = null
        state.rejectedAtMs = null
    }

    private fun blendPose(current: List<PoseJoint>, incoming: List<PoseJoint>): List<PoseJoint> {
        if (current.isEmpty()) return copyPose(incoming)
        val old = current.associateBy { it.index }
        return incoming.map { fresh ->
            val previous = old[fresh.index] ?: return@map PoseJoint(fresh.index, fresh.offsetMeters.copyOf(), fresh.confidence)
            PoseJoint(
                index = fresh.index,
                offsetMeters = FloatArray(3) { index ->
                    previous.offsetMeters.getOrElse(index) { fresh.offsetMeters[index] } * (1f - POSE_JOINT_ALPHA) +
                        fresh.offsetMeters[index] * POSE_JOINT_ALPHA
                },
                confidence = previous.confidence * 0.28f + fresh.confidence * 0.72f
            )
        }
    }

    private fun copyPose(values: List<PoseJoint>): List<PoseJoint> =
        values.map { PoseJoint(it.index, it.offsetMeters.copyOf(), it.confidence) }

    private fun positionAlpha(
        label: String,
        residualDistance: Float,
        apparentSpeed: Float,
        uncertaintyMeters: Float,
        depthConfidence: Float?
    ): Float {
        if (residualDistance < POSITION_DEADBAND_METERS) return if (label == "car") 0.12f else 0.08f
        val quality = (1f - (uncertaintyMeters / 1.5f)).coerceIn(0f, 1f)
        val depthBoost = (depthConfidence ?: 0f).coerceIn(0f, 1f) * 0.10f
        return if (label == "car") {
            val motionBoost = (apparentSpeed / 7f).coerceIn(0f, 1f) * 0.38f
            (0.34f + quality * 0.18f + depthBoost + motionBoost).coerceIn(0.32f, 0.88f)
        } else {
            val motionBoost = (apparentSpeed / 4f).coerceIn(0f, 1f) * 0.30f
            (0.24f + quality * 0.20f + depthBoost + motionBoost).coerceIn(0.22f, 0.78f)
        }
    }

    private fun isGroundContactMethod(method: String): Boolean =
        method.contains("depth", true) || method.contains("terrain", true) ||
            method.contains("ground", true) || method.contains("plane", true)

    private fun measurementGate(state: State, observation: SpatialObservation, dt: Float): Float {
        val base = when (observation.label) {
            "car" -> 1.25f
            "person" -> 0.74f
            "bird" -> 0.44f
            else -> 0.62f
        }
        val uncertainty = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.8f)
        val motionScale = if (observation.label == "car") 0.72f else 0.55f
        val motionAllowance = maxSpeed(observation.label) * dt * motionScale
        val maxGate = when (observation.label) {
            "car" -> 6.0f
            "person" -> 2.1f
            "bird" -> 1.25f
            else -> 1.9f
        }
        return (base + uncertainty * 1.20f + motionAllowance).coerceAtMost(maxGate)
    }

    private fun predictedPosition(state: State, atMs: Long): FloatArray {
        val dt = ((atMs - state.lastSeenAtMs).coerceAtLeast(0L) / 1000f).coerceAtMost(MAX_PREDICTION_SECONDS)
        return FloatArray(3) { index -> state.position[index] + state.velocity[index] * dt }
    }

    private fun predictedDistance(state: State, observation: SpatialObservation): Float {
        val predicted = predictedPosition(state, observation.observedAtMs)
        var squared = 0f
        for (index in 0..2) {
            val delta = observation.position[index] - predicted[index]
            squared += delta * delta
        }
        return sqrt(squared)
    }

    private fun associationRadius(state: State, observation: SpatialObservation): Float {
        val ageSeconds = ((observation.observedAtMs - state.lastSeenAtMs).coerceAtLeast(0L) / 1000f)
        val uncertaintyAllowance = (state.uncertaintyMeters + observation.uncertaintyMeters).coerceAtMost(1.5f)
        if (observation.label == "car") {
            return (1.55f + ageSeconds * 7.5f + uncertaintyAllowance * 1.10f).coerceAtMost(7.0f)
        }
        val base = when (observation.label) {
            "person" -> 0.85f
            "bird" -> 0.46f
            else -> 0.75f
        }
        return (base + ageSeconds * 0.65f + uncertaintyAllowance).coerceAtMost(3.0f)
    }

    private fun reacquireRadius(label: String): Float = when (label) {
        "car" -> 8.0f
        "person" -> 2.8f
        "bird" -> 1.2f
        else -> 2.0f
    }

    private fun maxSpeed(label: String): Float = when (label) {
        "car" -> 28f
        "person" -> 7f
        "bird" -> 12f
        "dog" -> 11f
        "cat" -> 9f
        else -> 10f
    }

    private fun clampMagnitude(vector: FloatArray, maximum: Float): FloatArray {
        val length = magnitude(vector)
        if (!length.isFinite() || length <= maximum || length <= 0f) return vector
        val scale = maximum / length
        return FloatArray(3) { index -> vector[index] * scale }
    }

    private fun magnitude(vector: FloatArray): Float {
        var squared = 0f
        for (value in vector) squared += value * value
        return sqrt(squared)
    }

    private fun horizontalDistance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dz * dz)
    }

    private fun planarSpeed(vector: FloatArray): Float = sqrt(vector[0] * vector[0] + vector[2] * vector[2])

    private fun blendAngle(current: Float, target: Float, alpha: Float): Float {
        val delta = normalizeAngle(target - current)
        return normalizeAngle(current + delta * alpha)
    }

    private fun normalizeAngle(value: Float): Float {
        var result = value
        val pi = PI.toFloat()
        while (result > pi) result -= 2f * pi
        while (result < -pi) result += 2f * pi
        return result
    }

    private fun trackTimeoutMs(label: String): Long = if (label == "car") CAR_TRACK_TIMEOUT_MS else TRACK_TIMEOUT_MS

    private fun expire(nowMs: Long) {
        states.entries.removeIf { nowMs - it.value.lastSeenAtMs > trackTimeoutMs(it.value.label) }
    }

    private fun toPublicTrack(state: State, nowMs: Long): SpatialTrack {
        val ageMs = (nowMs - state.lastSeenAtMs).coerceAtLeast(0L)
        val timeoutMs = trackTimeoutMs(state.label)
        val confidenceDecay = (1f - (ageMs.toFloat() / timeoutMs) * 0.58f).coerceIn(0.30f, 1f)
        val poseAgeMs = state.poseLastSeenAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
        return SpatialTrack(
            key = "$sourceId:${state.id}",
            id = state.id,
            sourceId = sourceId,
            label = state.label,
            confidence = state.confidence * confidenceDecay,
            position = predictedPosition(state, nowMs),
            velocity = state.velocity.copyOf(),
            uncertaintyMeters = state.uncertaintyMeters + (ageMs / 1000f) * 0.10f,
            observedAtMs = nowMs,
            extentMeters = state.extentMeters.copyOf(),
            yawRadians = state.yawRadians,
            poseJoints = if (state.label == "person" && poseAgeMs <= POSE_HOLD_MS) copyPose(state.poseJoints) else emptyList(),
            spatialMethod = state.spatialMethod,
            terrainY = state.terrainY,
            depthConfidence = state.depthConfidence,
            hitCount = state.hitCount
        )
    }

    companion object {
        private const val POSITION_DEADBAND_METERS = 0.05f
        private const val STATIONARY_RESIDUAL_METERS = 0.18f
        private const val STATIONARY_SPEED_METERS_PER_SECOND = 0.35f
        private const val CAR_YAW_FROM_SPEED_METERS_PER_SECOND = 0.55f
        private const val CAR_MIN_VALID_MOTION_YAW_SPEED_METERS_PER_SECOND = 0.30f
        private const val CAR_REACQUIRE_MAX_SPEED_METERS_PER_SECOND = 18f
        private const val CAR_REBASE_BASE_METERS = 1.35f
        private const val CAR_REBASE_STEP_BASE_METERS = 0.55f
        private const val CAR_REBASE_MAX_VERTICAL_METERS = 2.0f
        private const val CAR_REBASE_MIN_CONFIDENCE = 0.26f
        private const val CAR_REBASE_REQUIRED_PRIOR_REJECTS = 2
        private const val MAX_UNCERTAINTY_METERS = 3.0f
        private const val MAX_PREDICTION_SECONDS = 0.45f
        private const val TRACK_TIMEOUT_MS = 1_500L
        private const val CAR_TRACK_TIMEOUT_MS = 2_200L
        private const val REACQUIRE_RATIO = 0.70f
        private const val REACQUIRE_MARGIN_METERS = 0.20f
        private const val POSE_JOINT_ALPHA = 0.62f
        private const val POSE_HOLD_MS = 1_500L
    }
}
