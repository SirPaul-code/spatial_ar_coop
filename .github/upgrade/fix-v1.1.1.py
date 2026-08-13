from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# Android release bump.
replace_once("android/app/build.gradle.kts", 'versionCode = 10\n        versionName = "1.1.0"', 'versionCode = 11\n        versionName = "1.1.1"')

# Never persist non-finite ARCore points. One bad float used to poison an entire scan preview.
replace_once(
    "android/app/src/main/java/com/sirpaul/spatialarcoop/ar/PointCloudRecorder.kt",
    '''                    val world = floatArrayOf(buffer.get(), buffer.get(), buffer.get())
                    val confidence = buffer.get()
                    if (confidence < MIN_CONFIDENCE) continue
                    val site = PoseMath.transformPoint(siteFromWorld, world)
                    val key = VoxelKey(''',
    '''                    val world = floatArrayOf(buffer.get(), buffer.get(), buffer.get())
                    val confidence = buffer.get()
                    if (!confidence.isFinite() || confidence < MIN_CONFIDENCE || world.any { !it.isFinite() }) continue
                    val site = PoseMath.transformPoint(siteFromWorld, world)
                    if (site.any { !it.isFinite() }) continue
                    val key = VoxelKey('''
)

# Keep MediaPipe LIVE_STREAM backing images alive until its async callback has completed.
path = "android/app/src/main/java/com/sirpaul/spatialarcoop/vision/ObjectDetectorEngine.kt"
replace_once(path, "import android.content.Context\nimport android.graphics.RectF", "import android.content.Context\nimport android.graphics.Bitmap\nimport android.graphics.RectF")
replace_once(
    path,
    '''    private data class PendingFrame(
        val streamTimestampMs: Long,
        val capturedAtMs: Long,
        val rawWidth: Int,
        val rawHeight: Int,
        val rotationDegrees: Int,
        val captureGeometry: CaptureGeometry?
    )
''',
    '''    private data class PendingFrame(
        val streamTimestampMs: Long,
        val capturedAtMs: Long,
        val rawWidth: Int,
        val rawHeight: Int,
        val rotationDegrees: Int,
        val captureGeometry: CaptureGeometry?,
        val image: MPImage,
        val rawBitmap: Bitmap,
        val uprightBitmap: Bitmap
    ) {
        fun release() {
            runCatching { image.close() }
            if (uprightBitmap !== rawBitmap && !uprightBitmap.isRecycled) uprightBitmap.recycle()
            if (!rawBitmap.isRecycled) rawBitmap.recycle()
        }
    }
'''
)
replace_once(
    path,
    '''    private var submittedFrames = 0L
    private var resultFrames = 0L
    private var droppedFrames = 0L
''',
    '''    private var submittedFrames = 0L
    private var resultFrames = 0L
    private var droppedFrames = 0L
    @Volatile private var firstSubmittedAtMs = 0L
    @Volatile private var lastCallbackAtMs = System.currentTimeMillis()
'''
)
replace_once(
    path,
    '''        if (closed || reconfigurePending.get()) return false
        val timestamp = nextStreamTimestamp()
        submittedFrames += 1
        detectorExecutor.execute {''',
    '''        if (closed || reconfigurePending.get()) return false
        val nowMs = System.currentTimeMillis()
        if (firstSubmittedAtMs == 0L) firstSubmittedAtMs = nowMs
        if (
            submittedFrames >= LIVE_STALL_MIN_SUBMISSIONS &&
            nowMs - firstSubmittedAtMs >= LIVE_STALL_TIMEOUT_MS &&
            nowMs - lastCallbackAtMs >= LIVE_STALL_TIMEOUT_MS &&
            activeProfile.delegate == DetectorDelegateProfile.GPU
        ) {
            policy.gpuFailure(nowMs)?.let(::requestReconfigure)
            return false
        }
        val timestamp = nextStreamTimestamp()
        submittedFrames += 1
        detectorExecutor.execute {'''
)
replace_once(
    path,
    '''                if (pending.size >= MAX_PENDING_METADATA) {
                    pending.keys.sorted().take(pending.size - MAX_PENDING_METADATA + 1).forEach {
                        if (pending.remove(it) != null) droppedFrames += 1
                    }
                }
''',
    '''                if (pending.size >= MAX_PENDING_METADATA) {
                    pending.keys.sorted().take(pending.size - MAX_PENDING_METADATA + 1).forEach {
                        pending.remove(it)?.let { frame ->
                            frame.release()
                            droppedFrames += 1
                        }
                    }
                }
'''
)
replace_once(
    path,
    '''                pending[timestamp] = PendingFrame(
                    timestamp,
                    capturedAtMs,
                    frame.width,
                    frame.height,
                    rotationDegrees,
                    captureGeometry
                )
                try {
                    detector?.detectAsync(image, timestamp)
                } finally {
                    image.close()
                    if (upright !== rawBitmap) upright.recycle()
                    rawBitmap.recycle()
                }
            } catch (error: Throwable) {
                pending.remove(timestamp)
                handleDetectorFailure(error)
            }
''',
    '''                pending[timestamp] = PendingFrame(
                    timestamp,
                    capturedAtMs,
                    frame.width,
                    frame.height,
                    rotationDegrees,
                    captureGeometry,
                    image,
                    rawBitmap,
                    upright
                )
                try {
                    (detector ?: error("Object detector is not initialized")).detectAsync(image, timestamp)
                } catch (error: Throwable) {
                    pending.remove(timestamp)?.release()
                    throw error
                }
            } catch (error: Throwable) {
                pending.remove(timestamp)?.release()
                handleDetectorFailure(error)
            }
'''
)
replace_once(
    path,
    '''            val timestamp = result.timestampMs()
            val metadata = pending.remove(timestamp) ?: run {''',
    '''            lastCallbackAtMs = System.currentTimeMillis()
            val timestamp = result.timestampMs()
            val metadata = pending.remove(timestamp) ?: run {'''
)
replace_once(
    path,
    '''            val dropped = pending.keys.filter { it < timestamp }
            for (old in dropped) if (pending.remove(old) != null) droppedFrames += 1
''',
    '''            val dropped = pending.keys.filter { it < timestamp }
            for (old in dropped) {
                pending.remove(old)?.let { frame ->
                    frame.release()
                    droppedFrames += 1
                }
            }
'''
)
replace_once(
    path,
    '''            } catch (error: Throwable) {
                logger.error("Object detector result processing failed", error)
                onError(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun requestReconfigure''',
    '''            } catch (error: Throwable) {
                logger.error("Object detector result processing failed", error)
                onError(error.message ?: error.javaClass.simpleName)
            } finally {
                metadata.release()
            }
        }
    }

    private fun releasePendingFrames() {
        val frames = pending.values.toList()
        pending.clear()
        frames.forEach(PendingFrame::release)
    }

    private fun requestReconfigure'''
)
replace_once(path, "                pending.clear()\n                activeProfile = profile", "                releasePendingFrames()\n                activeProfile = profile\n                firstSubmittedAtMs = System.currentTimeMillis()\n                lastCallbackAtMs = firstSubmittedAtMs")
replace_once(path, "        pending.clear()\n        synchronized(resultLock)", "        releasePendingFrames()\n        synchronized(resultLock)")
replace_once(
    path,
    '''        private const val MAX_PENDING_METADATA = 8
        private const val POSE_DETECTION_CONFIDENCE = 0.48f''',
    '''        private const val MAX_PENDING_METADATA = 8
        private const val LIVE_STALL_MIN_SUBMISSIONS = 12
        private const val LIVE_STALL_TIMEOUT_MS = 2_500L
        private const val POSE_DETECTION_CONFIDENCE = 0.48f'''
)

# Localization: do not wait eight seconds for a root anchor that may be on the other side of a house.
path = "android/app/src/main/java/com/sirpaul/spatialarcoop/ar/CloudAnchorCoordinator.kt"
start = '''        val generation = resolveGeneration.incrementAndGet()
        val root = map.rootAnchorId?.let { id -> candidates.firstOrNull { it.id == id } }
        val fallbacks = if (root == null) candidates else candidates.filterNot { it.id == root.id }
        val fallbackStarted = AtomicBoolean(false)
        fun startFallbacks(detail: String) {
            if (generation != resolveGeneration.get() || reference.get() != null || fallbacks.isEmpty()) return
            if (!fallbackStarted.compareAndSet(false, true)) return
            logger.info(
                "Cloud Anchor fallback resolves started",
                mapOf("mapId" to mapId, "fallbacks" to fallbacks.size, "detail" to detail)
            )
            onState(detail)
            resolveFallbacks(fallbacks, generation, candidates.size)
        }

        logger.info(
            "Cloud Anchor resolve batch started",
            mapOf("mapId" to mapId, "anchors" to candidates.size, "rootAnchorId" to map.rootAnchorId)
        )

        if (root != null) {
            onState("Trying map root ${root.id.takeLast(8)} · backups will join if needed")
            resolveCandidate(root, generation, 1, candidates.size) { state ->
                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate
                logger.warn(
                    "Root Cloud Anchor resolve failed",
                    mapOf("mapId" to mapId, "anchorId" to root.id, "state" to state.name, "fallbacks" to fallbacks.size)
                )
                if (fallbacks.isEmpty()) {
                    resolving.set(false)
                    onState("Localization failed: root ${root.id.takeLast(8)}=${state.name} · retrying automatically")
                } else if (!fallbackStarted.get()) {
                    startFallbacks("Root ${root.id.takeLast(8)} returned ${state.name} · trying backup anchors")
                } else if (resolveFutures.isEmpty()) {
                    resolving.set(false)
                    onState("Localization failed: root and backup anchors did not resolve · retrying automatically")
                } else {
                    onState("Root returned ${state.name} · backup anchors still resolving")
                }
            }
            if (fallbacks.isNotEmpty()) {
                mainHandler.postDelayed({
                    if (generation == resolveGeneration.get() && reference.get() == null && resolving.get()) {
                        startFallbacks("Root still resolving · trying ${fallbacks.size} backup anchor(s) in parallel")
                    }
                }, ROOT_PREFERENCE_GRACE_MS)
            }
        } else {
            startFallbacks("Trying ${fallbacks.size} saved Cloud Anchors · move slowly and look around")
        }
'''
replacement = '''        val generation = resolveGeneration.incrementAndGet()
        val remaining = AtomicInteger(candidates.size)
        val failures = CopyOnWriteArrayList<String>()
        logger.info(
            "Cloud Anchor parallel resolve batch started",
            mapOf("mapId" to mapId, "anchors" to candidates.size, "rootAnchorId" to map.rootAnchorId)
        )
        onState("Trying ${candidates.size} saved Cloud Anchors in parallel · move slowly and look around")
        candidates.forEachIndexed { index, definition ->
            resolveCandidate(definition, generation, index + 1, candidates.size) { state ->
                if (generation != resolveGeneration.get() || reference.get() != null) return@resolveCandidate
                failures += "${definition.id.takeLast(8)}=${state.name}"
                val left = remaining.decrementAndGet()
                if (left > 0) {
                    onState("Anchor ${definition.id.takeLast(8)}: ${state.name} · $left candidate(s) still resolving")
                } else {
                    resolving.set(false)
                    onState("Localization failed: ${failures.joinToString(", ").take(220)} · retrying automatically")
                }
            }
        }
'''
replace_once(path, start, replacement)

# Denser auto-anchor coverage. GOOD remains preferred; SUFFICIENT is accepted only when it fills a real coverage hole.
replace_once(
    path,
    '''        if (featureQuality(cameraPose) != FeatureQuality.GOOD) return
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
''',
    '''        val quality = featureQuality(cameraPose)
        if (quality == FeatureQuality.INSUFFICIENT || quality == FeatureQuality.UNKNOWN) return
        if (nearbyFailed != null) {
            host(cameraPose, worldFromSite, map, forced = quality != FeatureQuality.GOOD, retry = nearbyFailed)
            return
        }
        val minimumDistance = anchors
            .filter { it.status == AnchorStatus.HOSTED }
            .minOfOrNull { PoseMath.distance(PoseMath.translationOf(it.siteFromAnchor), sitePosition) }
            ?: Float.POSITIVE_INFINITY
        val targetSpacing = minOf(map.minAnchorSpacingMeters.coerceAtLeast(MIN_AUTO_ANCHOR_SPACING_METERS), MAX_AUTO_ANCHOR_SPACING_METERS)
        if (minimumDistance < targetSpacing) return
        if (quality == FeatureQuality.SUFFICIENT) {
            if (minimumDistance < SUFFICIENT_QUALITY_MIN_DISTANCE_METERS) return
            if (now - lastHostAttemptAtMs < SUFFICIENT_QUALITY_COOLDOWN_MS) return
        }
        host(cameraPose, worldFromSite, map, forced = quality == FeatureQuality.SUFFICIENT)
'''
)
replace_once(
    path,
    '''        private const val AUTO_HOST_COOLDOWN_MS = 8_000L
        private const val RETRY_RADIUS_METERS = 4f
        private const val MAX_CONCURRENT_RESOLVES = 8
        private const val ROOT_PREFERENCE_GRACE_MS = 8_000L
''',
    '''        private const val AUTO_HOST_COOLDOWN_MS = 8_000L
        private const val SUFFICIENT_QUALITY_COOLDOWN_MS = 18_000L
        private const val MIN_AUTO_ANCHOR_SPACING_METERS = 1.75f
        private const val MAX_AUTO_ANCHOR_SPACING_METERS = 2.50f
        private const val SUFFICIENT_QUALITY_MIN_DISTANCE_METERS = 3.25f
        private const val RETRY_RADIUS_METERS = 4f
        private const val MAX_CONCURRENT_RESOLVES = 8
        private const val ROOT_PREFERENCE_GRACE_MS = 8_000L
'''
)

# Existing bad chunks must not make /point-cloud fail. Keep accounting, skip only invalid samples.
path = "server/src/persistence.mjs"
replace_once(
    path,
    '''    let globalIndex = 0;
    for (const file of files) {
      const decoded = decodeScanChunk(fs.readFileSync(file));
      for (let index = 0; index < decoded.pointCount; index += 1) {
        const offset = 16 + index * 16;
        const x = decoded.raw.readFloatLE(offset);
        const y = decoded.raw.readFloatLE(offset + 4);
        const z = decoded.raw.readFloatLE(offset + 8);
        const q = decoded.raw.readFloatLE(offset + 12);
        if (![x, y, z, q].every(Number.isFinite)) throw badRequest('INVALID_SCAN_POINT', `Chunk ${path.basename(file)} contains a non-finite point`);
        min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
        max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
        if (globalIndex % stride === 0 && points.length < cap) points.push([x, y, z, q]);
        globalIndex += 1;
      }
    }
    return {
      mapId: map.id,
      totalPoints: globalIndex,
      sampledPoints: points.length,
      bounds: globalIndex ? { min, max } : null,
      points
    };
''',
    '''    let globalIndex = 0;
    let validPoints = 0;
    let invalidPoints = 0;
    const invalidChunks = new Set();
    for (const file of files) {
      const decoded = decodeScanChunk(fs.readFileSync(file));
      for (let index = 0; index < decoded.pointCount; index += 1) {
        const offset = 16 + index * 16;
        const x = decoded.raw.readFloatLE(offset);
        const y = decoded.raw.readFloatLE(offset + 4);
        const z = decoded.raw.readFloatLE(offset + 8);
        const q = decoded.raw.readFloatLE(offset + 12);
        if (![x, y, z, q].every(Number.isFinite)) {
          invalidPoints += 1;
          invalidChunks.add(path.basename(file));
          globalIndex += 1;
          continue;
        }
        min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
        max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
        if (globalIndex % stride === 0 && points.length < cap) points.push([x, y, z, q]);
        globalIndex += 1;
        validPoints += 1;
      }
    }
    if (invalidPoints > 0) {
      this.logger.warn('scan_preview_invalid_points_skipped', {
        mapId: map.id,
        invalidPoints,
        invalidChunks: [...invalidChunks].slice(0, 12)
      });
    }
    return {
      mapId: map.id,
      totalPoints: globalIndex,
      validPoints,
      invalidPoints,
      sampledPoints: points.length,
      bounds: validPoints ? { min, max } : null,
      points
    };
'''
)

# Cross-platform npm syntax check (Windows cmd does not expand src/*.mjs globs).
pkg_path = ROOT / "server/package.json"
pkg = json.loads(pkg_path.read_text(encoding="utf-8"))
pkg["version"] = "1.1.1"
pkg["scripts"]["check"] = "node -e \"const fs=require('node:fs'),{spawnSync}=require('node:child_process');for(const d of ['src','test'])for(const f of fs.readdirSync(d))if(f.endsWith('.mjs')){const r=spawnSync(process.execPath,['--check',d+'/'+f],{stdio:'inherit'});if(r.status)process.exit(r.status)}\""
pkg_path.write_text(json.dumps(pkg, indent=2) + "\n", encoding="utf-8")

lock_path = ROOT / "server/package-lock.json"
lock = json.loads(lock_path.read_text(encoding="utf-8"))
lock["version"] = "1.1.1"
if "" in lock.get("packages", {}):
    lock["packages"][""]["version"] = "1.1.1"
lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")

# Regression test: a legacy NaN must be skipped instead of taking the whole map offline.
scan_test = ROOT / "server/test/scan-read.test.mjs"
text = scan_test.read_text(encoding="utf-8")
append = r'''

test('point-cloud preview skips legacy non-finite samples instead of failing the map', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-scan-invalid-'));
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, apiToken: 'scan-token', adminToken: 'scan-token', stdout: false });
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;
    const auth = { Authorization: 'Bearer scan-token' };
    const jsonHeaders = { ...auth, 'Content-Type': 'application/json' };
    assert.equal((await fetch(`${base}/api/v1/maps`, { method: 'POST', headers: jsonHeaders, body: JSON.stringify({ id: 'invalid-map', name: 'Invalid legacy scan' }) })).status, 201);
    const body = sac1([[0, 0, 0, .9], [Number.NaN, 1, 2, .8], [2, .5, 3, .7]]);
    assert.equal((await fetch(`${base}/api/v1/maps/invalid-map/scan-chunks`, {
      method: 'POST', headers: { ...auth, 'Content-Type': 'application/octet-stream', 'X-Chunk-Id': 'legacy', 'X-Device-Id': 'phone-a' }, body
    })).status, 201);
    const response = await fetch(`${base}/api/v1/maps/invalid-map/point-cloud?maxPoints=100`, { headers: auth });
    assert.equal(response.status, 200);
    const preview = await response.json();
    assert.equal(preview.totalPoints, 3);
    assert.equal(preview.validPoints, 2);
    assert.equal(preview.invalidPoints, 1);
    assert.equal(preview.sampledPoints, 2);
    assert.deepEqual(preview.bounds.min, [0, 0, 0]);
    assert.deepEqual(preview.bounds.max, [2, .5, 3]);
    assert.ok(preview.points.flat().every(Number.isFinite));
  } finally {
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
'''
if "point-cloud preview skips legacy non-finite samples" not in text:
    scan_test.write_text(text.rstrip() + append + "\n", encoding="utf-8")

# Admin: make connected-but-unlocalized clients explicit and surface scan corruption recovery.
path = "server/src/admin-page.mjs"
replace_once(
    path,
    "$('connection').textContent='Live · '+(state.live.clients||[]).length+' clients · '+(state.live.tracks||[]).length+' tracks';",
    "const clients=state.live.clients||[],localized=clients.filter(c=>c.pose?.position).length;$('connection').textContent='Live · '+clients.length+' clients · '+localized+' localized · '+(clients.length-localized)+' localizing · '+(state.live.tracks||[]).length+' tracks';"
)
replace_once(
    path,
    "$('hud').textContent=(state.cloud?.sampledPoints||0).toLocaleString()+' scan pts / '+(state.cloud?.totalPoints||0).toLocaleString()+' total · '+state.terrain.length.toLocaleString()+' terrain cells · '+(state.map?.anchors||[]).length+' anchors · '+(state.live.clients||[]).length+' clients · '+(state.live.tracks||[]).length+' live tracks'",
    "const clients=state.live.clients||[],localized=clients.filter(c=>c.pose?.position).length,invalid=state.cloud?.invalidPoints||0;$('hud').textContent=(state.cloud?.sampledPoints||0).toLocaleString()+' scan pts / '+(state.cloud?.totalPoints||0).toLocaleString()+' total'+(invalid?' · '+invalid+' invalid skipped':'')+' · '+state.terrain.length.toLocaleString()+' terrain cells · '+(state.map?.anchors||[]).length+' anchors · '+clients.length+' clients ('+localized+' localized) · '+(state.live.tracks||[]).length+' live tracks'"
)

print("v1.1.1 reliability patch applied")
