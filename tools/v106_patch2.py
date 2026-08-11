from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


cloud_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt")
cloud = cloud_path.read_text()
cloud = replace_once(
    cloud,
    '''package com.sirpaul.spatialarcoop.ar\n\nimport com.google.ar.core.Anchor\n''',
    '''package com.sirpaul.spatialarcoop.ar\n\nimport android.os.Handler\nimport android.os.Looper\nimport com.google.ar.core.Anchor\n''',
    "CloudAnchor Android imports",
)
cloud = replace_once(
    cloud,
    '''    private val reference = AtomicReference<Reference?>(null)\n    private val lastWorldFromSite = AtomicReference<FloatArray?>(null)\n''',
    '''    private val reference = AtomicReference<Reference?>(null)\n    private val lastWorldFromSite = AtomicReference<FloatArray?>(null)\n    private val mainHandler = Handler(Looper.getMainLooper())\n''',
    "CloudAnchor handler field",
)
start = cloud.index("    fun resolveMap(map: MapDefinition) {")
end = cloud.index("    private fun resolveCandidate(", start)
new_resolve = '''    fun resolveMap(map: MapDefinition) {\n        if (!cloudConfigured) {\n            onState("Cloud Anchors are not configured in this APK")\n            return\n        }\n        if (hasReference) return\n        if (resolving.get() || !resolving.compareAndSet(false, true)) return\n\n        val candidates = map.anchors\n            .filter { it.status == AnchorStatus.HOSTED && it.cloudAnchorId.isNotBlank() }\n            .sortedWith(\n                compareByDescending<AnchorDefinition> { it.id == map.rootAnchorId }\n                    .thenByDescending { it.updatedAtMs }\n            )\n            .take(MAX_CONCURRENT_RESOLVES)\n        if (candidates.isEmpty()) {\n            resolving.set(false)\n            onState("Map has no hosted anchors yet")\n            return\n        }\n\n        val generation = resolveGeneration.incrementAndGet()\n        val root = map.rootAnchorId?.let { id -> candidates.firstOrNull { it.id == id } }\n        val fallbacks = if (root == null) candidates else candidates.filterNot { it.id == root.id }\n        val fallbackStarted = AtomicBoolean(false)\n        fun startFallbacks(detail: String) {\n            if (generation != resolveGeneration.get() || reference.get() != null || fallbacks.isEmpty()) return\n            if (!fallbackStarted.compareAndSet(false, true)) return\n            logger.info(\n                "Cloud Anchor fallback resolves started",\n                mapOf("mapId" to mapId, "fallbacks" to fallbacks.size, "detail" to detail)\n            )\n            onState(detail)\n            resolveFallbacks(fallbacks, generation, candidates.size)\n        }\n\n        logger.info(\n            "Cloud Anchor resolve batch started",\n            mapOf("mapId" to mapId, "anchors" to candidates.size, "rootAnchorId" to map.rootAnchorId)\n        )\n\n        if (root != null) {\n            onState("Trying map root ${root.id.takeLast(8)} · backups will join if needed")\n            resolveCandidate(root, generation, 1, candidates.size) { state ->\n                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate\n                logger.warn(\n                    "Root Cloud Anchor resolve failed",\n                    mapOf("mapId" to mapId, "anchorId" to root.id, "state" to state.name, "fallbacks" to fallbacks.size)\n                )\n                if (fallbacks.isEmpty()) {\n                    resolving.set(false)\n                    onState("Localization failed: root ${root.id.takeLast(8)}=${state.name} · retrying automatically")\n                } else if (!fallbackStarted.get()) {\n                    startFallbacks("Root ${root.id.takeLast(8)} returned ${state.name} · trying backup anchors")\n                } else if (resolveFutures.isEmpty()) {\n                    resolving.set(false)\n                    onState("Localization failed: root and backup anchors did not resolve · retrying automatically")\n                } else {\n                    onState("Root returned ${state.name} · backup anchors still resolving")\n                }\n            }\n            if (fallbacks.isNotEmpty()) {\n                mainHandler.postDelayed({\n                    if (generation == resolveGeneration.get() && reference.get() == null && resolving.get()) {\n                        startFallbacks("Root still resolving · trying ${fallbacks.size} backup anchor(s) in parallel")\n                    }\n                }, ROOT_PREFERENCE_GRACE_MS)\n            }\n        } else {\n            startFallbacks("Trying ${fallbacks.size} saved Cloud Anchors · move slowly and look around")\n        }\n    }\n\n    private fun resolveFallbacks(candidates: List<AnchorDefinition>, generation: Int, total: Int) {\n        if (candidates.isEmpty()) {\n            resolving.set(false)\n            return\n        }\n        val remaining = AtomicInteger(candidates.size)\n        val failures = CopyOnWriteArrayList<String>()\n        candidates.forEachIndexed { fallbackIndex, definition ->\n            resolveCandidate(definition, generation, fallbackIndex + 1, total) { state ->\n                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate\n                failures += "${definition.id.takeLast(8)}=${state.name}"\n                val left = remaining.decrementAndGet()\n                if (left > 0) {\n                    onState("Backup anchor ${fallbackIndex + 1}/${candidates.size}: ${state.name} · trying $left more")\n                } else if (resolveFutures.isNotEmpty()) {\n                    // A preferred root future may still be alive. Keep it running instead of\n                    // recreating the old cancel/retry localization loop.\n                    onState("Backup anchors failed · preferred root still resolving")\n                } else {\n                    resolving.set(false)\n                    onState("Localization failed: ${failures.joinToString(", ").take(220)} · retrying automatically")\n                }\n            }\n        }\n    }\n\n'''
cloud = cloud[:start] + new_resolve + cloud[end:]
cloud = replace_once(
    cloud,
    '''    private fun cancelResolveBatch() {\n        resolveGeneration.incrementAndGet()\n''',
    '''    private fun cancelResolveBatch() {\n        mainHandler.removeCallbacksAndMessages(null)\n        resolveGeneration.incrementAndGet()\n''',
    "CloudAnchor cancel handler",
)
cloud = replace_once(
    cloud,
    '''        private const val MAX_CONCURRENT_RESOLVES = 8\n''',
    '''        private const val MAX_CONCURRENT_RESOLVES = 8\n        private const val ROOT_PREFERENCE_GRACE_MS = 8_000L\n''',
    "CloudAnchor root grace constant",
)
cloud_path.write_text(cloud)

ar_path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
ar = ar_path.read_text()
ar = replace_once(
    ar,
    '''        val locationState = when {\n            worldFromSite != null -> "Localized"\n            cloudAnchors?.isResolving == true -> "Localizing…"\n            else -> "Waiting for location"\n        }\n''',
    '''        val locationState = when {\n            worldFromSite != null -> cloudAnchors?.currentReferenceId\n                ?.let { "Localized · anchor ${it.takeLast(8)}" }\n                ?: "Localized · manual"\n            cloudAnchors?.isResolving == true -> "Localizing…"\n            else -> "Waiting for location"\n        }\n''',
    "ArActivity anchor reference HUD",
)
ar_path.write_text(ar)

print("v1.0.6 root preference + HUD patch applied")
