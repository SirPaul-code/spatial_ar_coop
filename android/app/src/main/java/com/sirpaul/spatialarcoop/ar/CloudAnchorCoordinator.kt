package com.sirpaul.spatialarcoop.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Session.FeatureMapQuality
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.Pose
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.sirpaul.spatialarcoop.BuildConfig
import com.sirpaul.spatialarcoop.data.AnchorDefinition
import com.sirpaul.spatialarcoop.data.AnchorStatus
import com.sirpaul.spatialarcoop.data.AppDatabase
import com.sirpaul.spatialarcoop.data.FeatureQuality
import com.sirpaul.spatialarcoop.data.MapDefinition
import com.sirpaul.spatialarcoop.util.FileLogger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CloudAnchorCoordinator(
    private val session: Session,
    private val mapId: String,
    private val database: AppDatabase,
    private val logger: FileLogger,
    private val scheduleUpload: () -> Unit,
    private val onState: (String) -> Unit
) {
    private data class Reference(val anchor: Anchor, val definition: AnchorDefinition)

    private val reference = AtomicReference<Reference?>(null)
    private val lastWorldFromSite = AtomicReference<FloatArray?>(null)
    private val hosting = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)
    private val resolveGeneration = AtomicInteger(0)
    private val hostFutures = CopyOnWriteArrayList<HostCloudAnchorFuture>()
    private val resolveFutures = CopyOnWriteArrayList<ResolveCloudAnchorFuture>()
    private val ownedAnchors = CopyOnWriteArrayList<Anchor>()
    private var lastHostAttemptAtMs = 0L

    val cloudConfigured: Boolean get() = BuildConfig.CLOUD_ANCHORS_CONFIGURED
    val hasReference: Boolean get() = reference.get() != null
    val isHosting: Boolean get() = hosting.get()
    val isResolving: Boolean get() = resolving.get()
    val currentReferenceId: String? get() = reference.get()?.definition?.id

    /**
     * Refresh from the live anchor while it is tracking. After a successful resolve, retain the
     * last valid site transform through temporary Anchor.PAUSED periods rather than reverting a
     * healthy Live session to Localizing.
     */
    fun currentWorldFromSite(): FloatArray? {
        val current = reference.get() ?: return lastWorldFromSite.get()?.copyOf()
        if (current.anchor.trackingState == TrackingState.TRACKING) {
            val live = PoseMath.multiply(
                PoseMath.poseToMatrix(current.anchor.pose),
                PoseMath.rigidInverse(current.definition.siteFromAnchor)
            )
            lastWorldFromSite.set(live.copyOf())
            return live
        }
        return lastWorldFromSite.get()?.copyOf()
    }

    fun attachHostedReference(localAnchor: Anchor, definition: AnchorDefinition) {
        ownedAnchors += localAnchor
        if (reference.compareAndSet(null, Reference(localAnchor, definition))) {
            lastWorldFromSite.set(
                PoseMath.multiply(
                    PoseMath.poseToMatrix(localAnchor.pose),
                    PoseMath.rigidInverse(definition.siteFromAnchor)
                )
            )
        }
    }

    fun resolveMap(map: MapDefinition) {
        if (!cloudConfigured) {
            onState("Cloud Anchors are not configured in this APK")
            return
        }
        if (hasReference) return
        if (resolving.get() || !resolving.compareAndSet(false, true)) return

        val candidates = map.anchors
            .filter { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }
            .sortedWith(
                compareByDescending<AnchorDefinition> { it.id == map.rootAnchorId }
                    .thenByDescending { it.updatedAtMs }
            )
            .take(MAX_CONCURRENT_RESOLVES)
        if (candidates.isEmpty()) {
            resolving.set(false)
            onState("Map has no hosted anchors yet")
            return
        }

        val generation = resolveGeneration.incrementAndGet()
        val root = map.rootAnchorId?.let { id -> candidates.firstOrNull { it.id == id } }
        val fallbacks = if (root == null) candidates else candidates.filterNot { it.id == root.id }
        logger.info(
            "Cloud Anchor resolve batch started",
            mapOf("mapId" to mapId, "anchors" to candidates.size, "rootAnchorId" to map.rootAnchorId)
        )

        if (root != null) {
            onState("Trying the map root Cloud Anchor first · move slowly and look around")
            resolveCandidate(root, generation, 1, candidates.size) { state ->
                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate
                logger.warn(
                    "Root Cloud Anchor resolve failed; starting fallbacks",
                    mapOf("mapId" to mapId, "anchorId" to root.id, "state" to state.name, "fallbacks" to fallbacks.size)
                )
                if (fallbacks.isEmpty()) {
                    resolving.set(false)
                    onState("Localization failed: root ${root.id.takeLast(8)}=${state.name} · retrying automatically")
                } else {
                    onState("Root anchor returned ${state.name} · trying ${fallbacks.size} backup anchor(s)")
                    resolveFallbacks(fallbacks, generation, candidates.size)
                }
            }
        } else {
            onState("Trying ${fallbacks.size} saved Cloud Anchors · move slowly and look around")
            resolveFallbacks(fallbacks, generation, candidates.size)
        }
    }

    private fun resolveFallbacks(candidates: List<AnchorDefinition>, generation: Int, total: Int) {
        if (candidates.isEmpty()) {
            resolving.set(false)
            return
        }
        val remaining = AtomicInteger(candidates.size)
        val failures = CopyOnWriteArrayList<String>()
        candidates.forEachIndexed { fallbackIndex, definition ->
            resolveCandidate(definition, generation, fallbackIndex + 1, total) { state ->
                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate
                failures += "${definition.id.takeLast(8)}=${state.name}"
                val left = remaining.decrementAndGet()
                if (left > 0) {
                    onState("Backup anchor ${fallbackIndex + 1}/${candidates.size}: ${state.name} · trying $left more")
                } else {
                    resolving.set(false)
                    resolveFutures.clear()
                    onState("Localization failed: ${failures.joinToString(", ").take(220)} · retrying automatically")
                }
            }
        }
    }

    private fun resolveCandidate(
        definition: AnchorDefinition,
        generation: Int,
        index: Int,
        total: Int,
        onFailure: (CloudAnchorState) -> Unit
    ) {
        logger.info(
            "Resolving Cloud Anchor",
            mapOf("mapId" to mapId, "anchorId" to definition.id, "candidate" to index, "total" to total)
        )
        var pending: ResolveCloudAnchorFuture? = null
        val future = session.resolveCloudAnchorAsync(definition.cloudAnchorId) { anchor, state ->
            pending?.let(resolveFutures::remove)
            if (generation != resolveGeneration.get() || reference.get() != null) {
                anchor?.detach()
                return@resolveCloudAnchorAsync
            }
            if (state == CloudAnchorState.SUCCESS && anchor != null) {
                if (reference.compareAndSet(null, Reference(anchor, definition))) {
                    ownedAnchors += anchor
                    lastWorldFromSite.set(
                        PoseMath.multiply(
                            PoseMath.poseToMatrix(anchor.pose),
                            PoseMath.rigidInverse(definition.siteFromAnchor)
                        )
                    )
                    resolving.set(false)
                    resolveGeneration.incrementAndGet()
                    val others = resolveFutures.toList()
                    resolveFutures.clear()
                    others.filter { it !== pending }.forEach { runCatching { it.cancel() } }
                    logger.info(
                        "Cloud Anchor resolved",
                        mapOf("mapId" to mapId, "anchorId" to definition.id, "candidate" to index, "total" to total)
                    )
                    onState("Localized · reference ${definition.id.takeLast(8)}")
                } else {
                    anchor.detach()
                }
                return@resolveCloudAnchorAsync
            }

            anchor?.detach()
            logger.warn(
                "Cloud Anchor resolve failed",
                mapOf("mapId" to mapId, "anchorId" to definition.id, "candidate" to index, "total" to total, "state" to state.name)
            )
            onFailure(state)
        }
        pending = future
        resolveFutures += future
    }

    private fun cancelResolveBatch() {
        resolveGeneration.incrementAndGet()
        resolveFutures.forEach { runCatching { it.cancel() } }
        resolveFutures.clear()
        resolving.set(false)
    }

    fun featureQuality(cameraPose: Pose): FeatureQuality = runCatching {
        when (session.estimateFeatureMapQualityForHosting(Pose.makeTranslation(cameraPose.tx(), cameraPose.ty(), cameraPose.tz()))) {
            FeatureMapQuality.GOOD -> FeatureQuality.GOOD
            FeatureMapQuality.SUFFICIENT -> FeatureQuality.SUFFICIENT
            FeatureMapQuality.INSUFFICIENT -> FeatureQuality.INSUFFICIENT
            else -> FeatureQuality.UNKNOWN
        }
    }.getOrDefault(FeatureQuality.UNKNOWN)

    fun considerAutoHost(cameraPose: Pose, worldFromSite: FloatArray, map: MapDefinition) {
        if (!map.autoAnchor || hosting.get() || !cloudConfigured) return
        val now = System.currentTimeMillis()
        if (now - lastHostAttemptAtMs < AUTO_HOST_COOLDOWN_MS) return
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val sitePosition = PoseMath.transformPoint(siteFromWorld, cameraPose.translation)
        val anchors = database.getAnchors(mapId)
        val nearbyFailed = anchors
            .filter { it.status == AnchorStatus.NEEDS_RESCAN || it.status == AnchorStatus.FAILED }
            .minByOrNull { PoseMath.distance(PoseMath.translationOf(it.siteFromAnchor), sitePosition) }
            ?.takeIf { PoseMath.distance(PoseMath.translationOf(it.siteFromAnchor), sitePosition) <= RETRY_RADIUS_METERS }
        if (featureQuality(cameraPose) != FeatureQuality.GOOD) return
        if (nearbyFailed != null) {
            host(cameraPose, worldFromSite, map, forced = false, retry = nearbyFailed)
            return
        }
        val minimumDistance = anchors
            .filter { it.status == AnchorStatus.HOSTED }
            .minOfOrNull { PoseMath.distance(PoseMath.translationOf(it.siteFromAnchor), sitePosition) }
            ?: Float.POSITIVE_INFINITY
        if (minimumDistance < map.minAnchorSpacingMeters) return
        host(cameraPose, worldFromSite, map, forced = false)
    }

    fun retryNearestFailed(cameraPose: Pose, worldFromSite: FloatArray, map: MapDefinition) {
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val sitePosition = PoseMath.transformPoint(siteFromWorld, cameraPose.translation)
        val candidate = database.getAnchors(mapId)
            .filter { it.status == AnchorStatus.NEEDS_RESCAN || it.status == AnchorStatus.FAILED }
            .minByOrNull { PoseMath.distance(PoseMath.translationOf(it.siteFromAnchor), sitePosition) }
        if (candidate == null) {
            onState("No failed anchor is waiting for a retry")
            return
        }
        val distance = PoseMath.distance(PoseMath.translationOf(candidate.siteFromAnchor), sitePosition)
        if (distance > RETRY_RADIUS_METERS) {
            onState("Move within ${RETRY_RADIUS_METERS.toInt()} m of a failed anchor before retrying")
            return
        }
        host(cameraPose, worldFromSite, map, forced = true, retry = candidate)
    }

    fun host(
        cameraPose: Pose,
        worldFromSite: FloatArray,
        map: MapDefinition,
        forced: Boolean,
        retry: AnchorDefinition? = null
    ) {
        if (!cloudConfigured) {
            onState("Set ARCORE_API_KEY and rebuild before hosting anchors")
            return
        }
        if (!hosting.compareAndSet(false, true)) return
        lastHostAttemptAtMs = System.currentTimeMillis()
        val quality = featureQuality(cameraPose)
        if (!forced && quality != FeatureQuality.GOOD) {
            hosting.set(false)
            onState("Move around until feature quality is GOOD")
            return
        }
        if (forced && quality == FeatureQuality.INSUFFICIENT) {
            hosting.set(false)
            onState("Not enough visual features; move around this area first")
            return
        }

        val anchorId = retry?.id ?: "a-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
        val localPose = Pose.makeTranslation(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val localAnchor = session.createAnchor(localPose)
        ownedAnchors += localAnchor
        val siteFromWorld = PoseMath.rigidInverse(worldFromSite)
        val siteFromAnchor = PoseMath.multiply(siteFromWorld, PoseMath.poseToMatrix(localAnchor.pose))
        val hostingDefinition = (retry ?: AnchorDefinition(
            mapId = mapId,
            id = anchorId,
            cloudAnchorId = "",
            siteFromAnchor = siteFromAnchor,
            status = AnchorStatus.HOSTING,
            featureQuality = quality
        )).copy(
            cloudAnchorId = "",
            siteFromAnchor = siteFromAnchor,
            status = AnchorStatus.HOSTING,
            featureQuality = quality,
            lastError = null,
            updatedAtMs = System.currentTimeMillis(),
            syncPending = true
        )
        database.upsertAnchor(hostingDefinition)
        scheduleUpload()
        onState(if (retry == null) "Hosting $anchorId (${quality.name})" else "Retrying $anchorId (${quality.name})")
        logger.info(
            "Cloud Anchor hosting started",
            mapOf(
                "mapId" to mapId,
                "anchorId" to anchorId,
                "quality" to quality.name,
                "retry" to (retry != null)
            )
        )

        // This app authenticates Cloud Anchors with an API key. ARCore limits that mode to one day.
        val effectiveTtlDays = 1
        var pending: HostCloudAnchorFuture? = null
        val future = session.hostCloudAnchorAsync(localAnchor, effectiveTtlDays) { cloudId, state ->
            pending?.let(hostFutures::remove)
            hosting.set(false)
            if (state == CloudAnchorState.SUCCESS && !cloudId.isNullOrBlank()) {
                val currentWorldFromSite = currentWorldFromSite() ?: worldFromSite
                val currentSiteFromWorld = PoseMath.rigidInverse(currentWorldFromSite)
                val definition = hostingDefinition.copy(
                    cloudAnchorId = cloudId,
                    siteFromAnchor = PoseMath.multiply(currentSiteFromWorld, PoseMath.poseToMatrix(localAnchor.pose)),
                    status = AnchorStatus.HOSTED,
                    updatedAtMs = System.currentTimeMillis(),
                    syncPending = true
                )
                database.upsertAnchor(definition)
                val latestMap = database.getMap(mapId)
                if (latestMap?.rootAnchorId == null) database.updateMapRuntime(mapId, rootAnchorId = anchorId)
                val retainedAsReference = reference.compareAndSet(null, Reference(localAnchor, definition))
                if (retainedAsReference) {
                    lastWorldFromSite.set(
                        PoseMath.multiply(
                            PoseMath.poseToMatrix(localAnchor.pose),
                            PoseMath.rigidInverse(definition.siteFromAnchor)
                        )
                    )
                } else {
                    localAnchor.detach()
                    ownedAnchors.remove(localAnchor)
                }
                scheduleUpload()
                logger.info("Cloud Anchor hosted", mapOf("mapId" to mapId, "anchorId" to anchorId))
                onState("Anchor hosted: $anchorId")
            } else {
                val definition = hostingDefinition.copy(
                    status = AnchorStatus.NEEDS_RESCAN,
                    lastError = state.name,
                    updatedAtMs = System.currentTimeMillis(),
                    syncPending = true
                )
                database.upsertAnchor(definition)
                scheduleUpload()
                localAnchor.detach()
                ownedAnchors.remove(localAnchor)
                logger.warn("Cloud Anchor hosting failed", mapOf("mapId" to mapId, "anchorId" to anchorId, "state" to state.name))
                onState("Anchor failed ($state); this area is saved as needs-rescan")
            }
        }
        pending = future
        hostFutures += future
    }

    fun resetReference() {
        cancelResolveBatch()
        lastWorldFromSite.set(null)
        reference.getAndSet(null)?.let { current ->
            ownedAnchors.remove(current.anchor)
            runCatching { current.anchor.detach() }
        }
    }

    fun close() {
        hostFutures.forEach { runCatching { it.cancel() } }
        cancelResolveBatch()
        ownedAnchors.forEach { runCatching { it.detach() } }
        hostFutures.clear()
        ownedAnchors.clear()
        reference.set(null)
        lastWorldFromSite.set(null)
    }

    companion object {
        private const val AUTO_HOST_COOLDOWN_MS = 8_000L
        private const val RETRY_RADIUS_METERS = 4f
        private const val MAX_CONCURRENT_RESOLVES = 8
    }
}
