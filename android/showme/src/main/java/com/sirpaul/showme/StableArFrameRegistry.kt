package com.sirpaul.showme

/**
 * Pure exact-frame identity ledger used at the ShowMe/StableAR boundary.
 * It deliberately knows nothing about ARCore. The value is an immutable SDK sample owned by
 * the bridge; video frame IDs are never confused with StableAR FrameRef IDs.
 */
internal data class StableVideoBinding<T>(
    val videoFrameId: Long,
    val showMeEpoch: Int,
    val sourceFrameId: Long,
    val sourceTimestampNs: Long,
    val capturedNs: Long,
    val value: T,
)

internal class StableArFrameRegistry<T>(
    private val clockNs: () -> Long = System::nanoTime,
    private val historyNs: Long = 4_000_000_000L,
    private val freezeNs: Long = 60_000_000_000L,
    private val maxFrames: Int = 120,
) {
    private data class Frozen<T>(val binding: StableVideoBinding<T>, val deadlineNs: Long)
    private val frames = LinkedHashMap<Long, StableVideoBinding<T>>()
    private var frozen: Frozen<T>? = null

    init {
        require(historyNs > 0L && freezeNs > 0L && maxFrames > 0)
    }

    fun bind(binding: StableVideoBinding<T>) {
        require(binding.videoFrameId > 0L && binding.showMeEpoch > 0)
        require(binding.sourceFrameId > 0L && binding.sourceTimestampNs > 0L && binding.capturedNs > 0L)
        prune()
        frames[binding.videoFrameId] = binding
        while (frames.size > maxFrames) frames.remove(frames.keys.first())
    }

    fun resolve(videoFrameId: Long, showMeEpoch: Int): StableVideoBinding<T>? {
        prune()
        val held = frozen?.binding?.takeIf { it.videoFrameId == videoFrameId }
        val candidate = held ?: frames[videoFrameId]
        return candidate?.takeIf { it.showMeEpoch == showMeEpoch }
    }

    /** Repeated freeze of the same frame never extends its deadline. */
    fun freeze(videoFrameId: Long, showMeEpoch: Int): StableVideoBinding<T>? {
        prune()
        frozen?.let { return it.binding.takeIf { b -> b.videoFrameId == videoFrameId && b.showMeEpoch == showMeEpoch } }
        val binding = resolve(videoFrameId, showMeEpoch) ?: return null
        frozen = Frozen(binding, clockNs() + freezeNs)
        return binding
    }

    /** Returns the held binding so the owner thread can release the SDK FrameRef lease. */
    fun unfreeze(): StableVideoBinding<T>? {
        val value = frozen?.binding
        frozen = null
        prune()
        return value
    }

    fun clear() {
        frames.clear()
        frozen = null
    }

    fun frameCount(): Int {
        prune()
        return frames.size
    }

    fun frozenVideoFrameId(): Long? {
        prune()
        return frozen?.binding?.videoFrameId
    }

    private fun prune() {
        val now = clockNs()
        if (frozen?.let { now > it.deadlineNs } == true) frozen = null
        frames.entries.removeAll { now - it.value.capturedNs !in 0L..historyNs }
    }
}

internal object StableArPlacementMath {
    fun normalizedRoot(points: List<DoubleArray>): DoubleArray? {
        if (points.isEmpty() || points.any { it.size != 2 || it.any { v -> !v.isFinite() || v !in 0.0..1.0 } }) return null
        return doubleArrayOf(points.sumOf { it[0] } / points.size, points.sumOf { it[1] } / points.size)
    }

    /**
     * Preserve ShowMe's exact historical stroke around the actual StableAR placement point.
     * The StableAR root reconstructed from the centroid pixel is not assumed to equal the 3D
     * centroid of all independently reconstructed stroke vertices.
     */
    fun relativeOffsets(world: List<FloatArray>, rootWorld: FloatArray): List<FloatArray>? {
        if (rootWorld.size < 3 || rootWorld.take(3).any { !it.isFinite() }) return null
        if (world.isEmpty() || world.any { it.size < 3 || it.take(3).any { v -> !v.isFinite() } }) return null
        return world.map { p -> FloatArray(3) { axis -> p[axis] - rootWorld[axis] } }
    }
}
