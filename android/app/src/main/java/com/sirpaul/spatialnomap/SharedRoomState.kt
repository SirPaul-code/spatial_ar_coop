package com.sirpaul.spatialnomap

import java.util.LinkedHashMap

/**
 * Canonical room-state model for the N-phone star topology.
 *
 * The current Wi-Fi Aware transport still establishes one physical peer link per
 * client, but room semantics no longer need to be pairwise: the host can assign
 * every peer a canonicalFromPeer transform and fan target/actor updates through a
 * single canonical coordinate system. This class is deliberately transport-free so
 * the same state can be used by Wi-Fi Aware, a future relay, replay tests and the
 * Bird's Eye view.
 */
class SharedRoomState {
    enum class TargetType { MANUAL, VEHICLE, PERSON, GENERIC }

    data class PeerState(
        val peerId: String,
        val displayName: String,
        val canonicalFromPeer: DoubleArray?,
        val poseCanonical: PosePacket?,
        val ready: Boolean,
        val confidence: Float,
        val lastSeenMs: Long,
    )

    data class TargetState(
        val id: Long,
        val type: TargetType,
        val ownerPeerId: String,
        val label: String,
        val positionCanonical: FloatArray,
        val velocityCanonical: FloatArray,
        val confidence: Float,
        val dynamic: Boolean,
        val lastSeenMs: Long,
    )

    data class Snapshot(
        val peers: List<PeerState>,
        val targets: List<TargetState>,
        val timestampMs: Long,
    )

    private val peers = LinkedHashMap<String, PeerState>()
    private val targets = LinkedHashMap<Long, TargetState>()

    @Synchronized fun upsertPeer(
        peerId: String,
        displayName: String,
        canonicalFromPeer: DoubleArray?,
        ready: Boolean,
        confidence: Float,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val previous = peers[peerId]
        peers[peerId] = PeerState(
            peerId = peerId,
            displayName = displayName,
            canonicalFromPeer = canonicalFromPeer?.copyOf(16) ?: previous?.canonicalFromPeer?.copyOf(),
            poseCanonical = previous?.poseCanonical,
            ready = ready,
            confidence = confidence,
            lastSeenMs = nowMs,
        )
    }

    @Synchronized fun updatePeerPose(
        peerId: String,
        posePeerWorld: PosePacket,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val peer = peers[peerId] ?: return false
        val transform = peer.canonicalFromPeer ?: return false
        val mapped = transformPose(transform, posePeerWorld) ?: return false
        peers[peerId] = peer.copy(poseCanonical = mapped, lastSeenMs = nowMs)
        return true
    }

    @Synchronized fun upsertTarget(
        id: Long,
        type: TargetType,
        ownerPeerId: String,
        label: String,
        positionCanonical: FloatArray,
        velocityCanonical: FloatArray = FloatArray(3),
        confidence: Float = 1f,
        dynamic: Boolean = type != TargetType.MANUAL,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (positionCanonical.size < 3 || positionCanonical.take(3).any { !it.isFinite() }) return
        targets[id] = TargetState(
            id = id,
            type = type,
            ownerPeerId = ownerPeerId,
            label = label,
            positionCanonical = positionCanonical.copyOf(3),
            velocityCanonical = velocityCanonical.copyOf(3),
            confidence = confidence.coerceIn(0f, 1f),
            dynamic = dynamic,
            lastSeenMs = nowMs,
        )
    }

    @Synchronized fun removeTarget(id: Long) {
        targets.remove(id)
    }

    @Synchronized fun clearTargets() {
        targets.clear()
    }

    @Synchronized fun removePeer(peerId: String) {
        peers.remove(peerId)
        targets.entries.removeIf { it.value.ownerPeerId == peerId && it.value.dynamic }
    }

    @Synchronized fun prune(nowMs: Long = System.currentTimeMillis()) {
        targets.entries.removeIf { it.value.dynamic && nowMs - it.value.lastSeenMs > DYNAMIC_TTL_MS }
        peers.entries.removeIf { nowMs - it.value.lastSeenMs > PEER_STALE_MS }
    }

    @Synchronized fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        prune(nowMs)
        return Snapshot(
            peers = peers.values.map { peer ->
                peer.copy(
                    canonicalFromPeer = peer.canonicalFromPeer?.copyOf(),
                    poseCanonical = peer.poseCanonical?.let { PosePacket(it.t.copyOf(), it.q.copyOf()) },
                )
            },
            targets = targets.values.map { target ->
                target.copy(
                    positionCanonical = target.positionCanonical.copyOf(),
                    velocityCanonical = target.velocityCanonical.copyOf(),
                )
            },
            timestampMs = nowMs,
        )
    }

    private fun transformPose(t: DoubleArray, pose: PosePacket): PosePacket? {
        if (t.size < 16 || pose.t.size < 3 || pose.q.size < 4) return null
        val p = transformPoint(t, pose.t) ?: return null
        val rq = matrixQuaternion(t) ?: return null
        val q = multiplyQuaternion(rq, pose.q)
        return PosePacket(p, q)
    }

    private fun transformPoint(t: DoubleArray, p: FloatArray): FloatArray? {
        if (t.size < 16 || p.size < 3 || t.take(16).any { !it.isFinite() }) return null
        val x = p[0].toDouble(); val y = p[1].toDouble(); val z = p[2].toDouble()
        val out = floatArrayOf(
            (t[0] * x + t[1] * y + t[2] * z + t[3]).toFloat(),
            (t[4] * x + t[5] * y + t[6] * z + t[7]).toFloat(),
            (t[8] * x + t[9] * y + t[10] * z + t[11]).toFloat(),
        )
        return if (out.all { it.isFinite() }) out else null
    }

    private fun matrixQuaternion(m: DoubleArray): FloatArray? {
        val trace = m[0] + m[5] + m[10]
        val q = DoubleArray(4)
        if (trace > 0.0) {
            val s = kotlin.math.sqrt(trace + 1.0) * 2.0
            q[3] = 0.25 * s
            q[0] = (m[9] - m[6]) / s
            q[1] = (m[2] - m[8]) / s
            q[2] = (m[4] - m[1]) / s
        } else if (m[0] > m[5] && m[0] > m[10]) {
            val s = kotlin.math.sqrt(1.0 + m[0] - m[5] - m[10]) * 2.0
            q[3] = (m[9] - m[6]) / s
            q[0] = 0.25 * s
            q[1] = (m[1] + m[4]) / s
            q[2] = (m[2] + m[8]) / s
        } else if (m[5] > m[10]) {
            val s = kotlin.math.sqrt(1.0 + m[5] - m[0] - m[10]) * 2.0
            q[3] = (m[2] - m[8]) / s
            q[0] = (m[1] + m[4]) / s
            q[1] = 0.25 * s
            q[2] = (m[6] + m[9]) / s
        } else {
            val s = kotlin.math.sqrt(1.0 + m[10] - m[0] - m[5]) * 2.0
            q[3] = (m[4] - m[1]) / s
            q[0] = (m[2] + m[8]) / s
            q[1] = (m[6] + m[9]) / s
            q[2] = 0.25 * s
        }
        return if (q.any { !it.isFinite() }) null else FloatArray(4) { q[it].toFloat() }
    }

    private fun multiplyQuaternion(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    companion object {
        private const val DYNAMIC_TTL_MS = 5_000L
        private const val PEER_STALE_MS = 15_000L
    }
}
