package com.sirpaul.spatialnomap

import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Process-local mirror of the shared spatial state used by the demo visualizer.
 *
 * This deliberately observes the same wire messages that drive the real app. It
 * does not invent a second coordinate system. Incoming remote frames/targets are
 * transformed with the currently observed localFromPeer transform; local data stay
 * in the local ARCore world. This makes the Bird's Eye view an honest visualization
 * of the exact shared-world transform currently being used by the product.
 */
object WorldVizBus {
    enum class Direction { OUT, IN }

    data class VizPoint(val xyz: FloatArray, val remote: Boolean)
    data class VizActor(
        val id: String,
        val label: String,
        val position: FloatArray,
        val quaternion: FloatArray,
        val local: Boolean,
        val lastSeenMs: Long,
    )
    data class VizTarget(
        val id: Long,
        val label: String,
        val position: FloatArray,
        val confidence: Float,
        val local: Boolean,
        val dynamic: Boolean,
        val lastSeenMs: Long,
    )
    data class Snapshot(
        val points: List<VizPoint>,
        val actors: List<VizActor>,
        val targets: List<VizTarget>,
        val locked: Boolean,
        val burstProgress: Float,
        val recorderPath: String?,
        val timestampMs: Long,
    )

    private var latestLocalFrame: CapturedFrame? = null
    private var latestRemoteFrame: CapturedFrame? = null
    private var localFromPeer: DoubleArray? = null
    private var localName = "YOU"
    private var peerName = "PEER"
    private var localReady = false
    private var peerReady = false
    private val localTargets = LinkedHashMap<Long, VizTarget>()
    private val remoteTargets = LinkedHashMap<Long, VizTarget>()

    @Synchronized fun observe(direction: Direction, message: WireMessage) {
        val now = System.currentTimeMillis()
        when (message) {
            is WireMessage.Hello -> {
                if (direction == Direction.OUT) localName = message.username.ifBlank { "YOU" }
                else peerName = message.username.ifBlank { "PEER" }
                AcquisitionBurstController.observeHello(
                    local = direction == Direction.OUT,
                    username = message.username,
                    deviceModel = message.deviceModel,
                )
            }
            is WireMessage.Frame -> {
                if (direction == Direction.OUT) latestLocalFrame = message.frame
                else latestRemoteFrame = message.frame
            }
            is WireMessage.Poi -> {
                val dynamic = message.owner.startsWith(AUTO_CAR_PREFIX)
                if (direction == Direction.OUT) {
                    val clean = cleanOwner(message.owner, localName)
                    localTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "CAR • YOU" else "${clean.ifBlank { "YOU" }} • ${shortId(message.id)}",
                        position = message.pointWorld.copyOf(3),
                        confidence = 1f,
                        local = true,
                        dynamic = dynamic,
                        lastSeenMs = now,
                    )
                } else {
                    val mapped = transformPoint(localFromPeer, message.pointWorld) ?: return
                    val clean = cleanOwner(message.owner, peerName)
                    remoteTargets[message.id] = VizTarget(
                        id = message.id,
                        label = if (dynamic) "CAR • $clean" else "$clean • ${shortId(message.id)}",
                        position = mapped,
                        confidence = 1f,
                        local = false,
                        dynamic = dynamic,
                        lastSeenMs = now,
                    )
                }
            }
            WireMessage.ClearPoi -> {
                localTargets.clear()
                remoteTargets.clear()
            }
            is WireMessage.Quality -> {
                if (direction == Direction.OUT) {
                    localReady = message.ready
                    message.senderFromPeer?.takeIf { it.size >= 16 }?.let { localFromPeer = it.copyOf(16) }
                } else {
                    peerReady = message.ready
                    message.senderFromPeer?.takeIf { it.size >= 16 }?.let { senderFromLocal ->
                        invertRigid(senderFromLocal)?.let { localFromPeer = it }
                    }
                }
                AcquisitionBurstController.observeQuality(direction == Direction.OUT, message.ready)
            }
            is WireMessage.ResetAlignment -> {
                localReady = false
                peerReady = false
                localFromPeer = null
                latestLocalFrame = null
                latestRemoteFrame = null
                localTargets.clear()
                remoteTargets.clear()
                SpatialMapAccumulator.clear()
                AcquisitionBurstController.reset()
            }
            is WireMessage.Range -> Unit
        }
        expireDynamic(now)
    }

    @Synchronized fun snapshot(maxPointsPerSide: Int = 1400): Snapshot {
        val now = System.currentTimeMillis()
        expireDynamic(now)
        val points = ArrayList<VizPoint>(maxPointsPerSide * 2)
        latestLocalFrame?.let { frame ->
            sampleMetric(frame.metricPoints, maxPointsPerSide).forEach { p ->
                points += VizPoint(floatArrayOf(p[2], p[3], p[4]), remote = false)
            }
        }
        val transform = localFromPeer
        if (transform != null) {
            latestRemoteFrame?.let { frame ->
                sampleMetric(frame.metricPoints, maxPointsPerSide).forEach { p ->
                    transformPoint(transform, floatArrayOf(p[2], p[3], p[4]))?.let { mapped ->
                        points += VizPoint(mapped, remote = true)
                    }
                }
            }
        }

        val actors = ArrayList<VizActor>(2)
        latestLocalFrame?.let { frame ->
            actors += VizActor(
                id = "local",
                label = localName.ifBlank { "YOU" },
                position = frame.pose.t.copyOf(3),
                quaternion = frame.pose.q.copyOf(4),
                local = true,
                lastSeenMs = now,
            )
        }
        if (transform != null) {
            latestRemoteFrame?.let { frame ->
                val mappedPosition = transformPoint(transform, frame.pose.t)
                val mappedQuaternion = transformQuaternion(transform, frame.pose.q)
                if (mappedPosition != null && mappedQuaternion != null) {
                    actors += VizActor(
                        id = "peer",
                        label = peerName.ifBlank { "PEER" },
                        position = mappedPosition,
                        quaternion = mappedQuaternion,
                        local = false,
                        lastSeenMs = now,
                    )
                }
            }
        }

        return Snapshot(
            points = points,
            actors = actors,
            targets = localTargets.values.map { it.copy(position = it.position.copyOf()) } +
                remoteTargets.values.map { it.copy(position = it.position.copyOf()) },
            locked = localReady && peerReady && localFromPeer != null,
            burstProgress = AcquisitionBurstController.currentProgress(),
            recorderPath = AlignmentSessionRecorder.latestSessionPath(),
            timestampMs = now,
        )
    }

    private fun sampleMetric(points: List<FloatArray>, limit: Int): List<FloatArray> {
        if (points.size <= limit) return points
        val step = max(1, points.size / limit)
        val out = ArrayList<FloatArray>(limit)
        var i = 0
        while (i < points.size && out.size < limit) {
            val p = points[i]
            if (p.size >= 5 && p[2].isFinite() && p[3].isFinite() && p[4].isFinite()) out += p
            i += step
        }
        return out
    }

    private fun cleanOwner(owner: String, fallback: String): String =
        owner.removePrefix(AUTO_CAR_PREFIX).trim().ifBlank { fallback }

    private fun shortId(id: Long): String = java.lang.Long.toHexString(id).takeLast(3).uppercase()

    private fun expireDynamic(now: Long) {
        localTargets.entries.removeIf { it.value.dynamic && now - it.value.lastSeenMs > 3_500L }
        remoteTargets.entries.removeIf { it.value.dynamic && now - it.value.lastSeenMs > 4_500L }
    }

    private fun transformPoint(transform: DoubleArray?, p: FloatArray): FloatArray? {
        if (transform == null || transform.size < 16 || p.size < 3) return null
        val x = p[0].toDouble()
        val y = p[1].toDouble()
        val z = p[2].toDouble()
        val out = floatArrayOf(
            (transform[0] * x + transform[1] * y + transform[2] * z + transform[3]).toFloat(),
            (transform[4] * x + transform[5] * y + transform[6] * z + transform[7]).toFloat(),
            (transform[8] * x + transform[9] * y + transform[10] * z + transform[11]).toFloat(),
        )
        return if (out.all { it.isFinite() }) out else null
    }

    private fun transformQuaternion(transform: DoubleArray, q: FloatArray): FloatArray? {
        if (transform.size < 16 || q.size < 4) return null
        val rq = rotationMatrixToQuaternion(transform) ?: return null
        val x1 = rq[0]
        val y1 = rq[1]
        val z1 = rq[2]
        val w1 = rq[3]
        val x2 = q[0]
        val y2 = q[1]
        val z2 = q[2]
        val w2 = q[3]
        val out = floatArrayOf(
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
        )
        val n = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
        if (!n.isFinite() || n < 1e-6f) return null
        repeat(4) { out[it] /= n }
        return out
    }

    private fun rotationMatrixToQuaternion(m: DoubleArray): FloatArray? {
        val trace = m[0] + m[5] + m[10]
        val q = DoubleArray(4)
        if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            q[3] = 0.25 * s
            q[0] = (m[9] - m[6]) / s
            q[1] = (m[2] - m[8]) / s
            q[2] = (m[4] - m[1]) / s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = sqrt(1.0 + m[0] - m[5] - m[10]) * 2.0
            q[3] = (m[9] - m[6]) / s
            q[0] = 0.25 * s
            q[1] = (m[1] + m[4]) / s
            q[2] = (m[2] + m[8]) / s
        } else if (m[5] > m[10]) {
            val s = sqrt(1.0 + m[5] - m[0] - m[10]) * 2.0
            q[3] = (m[2] - m[8]) / s
            q[0] = (m[1] + m[4]) / s
            q[1] = 0.25 * s
            q[2] = (m[6] + m[9]) / s
        } else {
            val s = sqrt(1.0 + m[10] - m[0] - m[5]) * 2.0
            q[3] = (m[4] - m[1]) / s
            q[0] = (m[2] + m[8]) / s
            q[1] = (m[6] + m[9]) / s
            q[2] = 0.25 * s
        }
        if (q.any { !it.isFinite() }) return null
        return FloatArray(4) { q[it].toFloat() }
    }

    private fun invertRigid(t: DoubleArray): DoubleArray? {
        if (t.size < 16 || t.take(16).any { !it.isFinite() }) return null
        val out = doubleArrayOf(
            t[0], t[4], t[8], 0.0,
            t[1], t[5], t[9], 0.0,
            t[2], t[6], t[10], 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val tx = t[3]
        val ty = t[7]
        val tz = t[11]
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz)
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz)
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz)
        return if (out.all { it.isFinite() }) out else null
    }

    private const val AUTO_CAR_PREFIX = "AUTO:CAR:"
}
