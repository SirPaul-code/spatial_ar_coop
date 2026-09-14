from pathlib import Path
import re


def load(path):
    p = Path(path)
    return p, p.read_text()


def save(p, text):
    p.write_text(text)


def sub_once(text, pattern, repl, label, flags=0):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 replacement, got {n}")
    return out

# WorldViz: real actor freshness + merge same-ID local/remote dynamic targets.
p, s = load('android/app/src/main/java/com/sirpaul/spatialnomap/WorldViz.kt')
s = sub_once(s,
    r'(private var latestLocalFrame: CapturedFrame\? = null\n\s*private var latestRemoteFrame: CapturedFrame\? = null\n)',
    r'\1    private var latestLocalFrameSeenMs = 0L\n    private var latestRemoteFrameSeenMs = 0L\n',
    'WorldViz frame timestamps')
s = sub_once(s,
    r'is WireMessage\.Frame -> \{\n\s*if \(direction == Direction\.OUT\) latestLocalFrame = message\.frame\n\s*else latestRemoteFrame = message\.frame\n\s*\}',
    '''is WireMessage.Frame -> {
                if (direction == Direction.OUT) {
                    latestLocalFrame = message.frame
                    latestLocalFrameSeenMs = now
                } else {
                    latestRemoteFrame = message.frame
                    latestRemoteFrameSeenMs = now
                }
            }''',
    'WorldViz observe frame')
s = sub_once(s,
    r'(latestLocalFrame = null\n\s*latestRemoteFrame = null\n)',
    r'\1                latestLocalFrameSeenMs = 0L\n                latestRemoteFrameSeenMs = 0L\n',
    'WorldViz reset freshness')
s = s.replace('latestLocalFrame?.let { frame ->\n            actors += VizActor(',
              'latestLocalFrame?.takeIf { now - latestLocalFrameSeenMs <= CLIENT_POSE_TTL_MS }?.let { frame ->\n            actors += VizActor(', 1)
s = s.replace('lastSeenMs = now,\n            )\n        }\n        if (transform != null) {',
              'lastSeenMs = latestLocalFrameSeenMs,\n            )\n        }\n        if (transform != null) {', 1)
s = s.replace('latestRemoteFrame?.let { frame ->\n                val mappedPosition',
              'latestRemoteFrame?.takeIf { now - latestRemoteFrameSeenMs <= CLIENT_POSE_TTL_MS }?.let { frame ->\n                val mappedPosition', 1)
s = s.replace('lastSeenMs = now,\n                    )\n                }\n            }\n        }\n\n        return Snapshot(',
              'lastSeenMs = latestRemoteFrameSeenMs,\n                    )\n                }\n            }\n        }\n\n        val mergedTargets = LinkedHashMap<Long, VizTarget>()\n        remoteTargets.values.forEach { target -> mergedTargets[target.id] = target.copy(position = target.position.copyOf()) }\n        localTargets.values.forEach { target ->\n            val previous = mergedTargets[target.id]\n            if (previous == null || target.lastSeenMs >= previous.lastSeenMs) {\n                mergedTargets[target.id] = target.copy(position = target.position.copyOf())\n            }\n        }\n\n        return Snapshot(', 1)
s = s.replace('targets = localTargets.values.map { it.copy(position = it.position.copyOf()) } +\n                remoteTargets.values.map { it.copy(position = it.position.copyOf()) },',
              'targets = mergedTargets.values.toList(),', 1)
s = s.replace('private const val AUTO_CAR_PREFIX = "AUTO:CAR:"',
              'private const val AUTO_CAR_PREFIX = "AUTO:CAR:"\n    private const val CLIENT_POSE_TTL_MS = 3_000L', 1)
save(p, s)

# BirdEye: safe bottom inset, client toggle, phone footprint.
p, s = load('android/app/src/main/java/com/sirpaul/spatialnomap/BirdEyeWorldView.kt')
s = s.replace('import android.view.ViewGroup\nimport android.widget.FrameLayout',
              'import android.view.ViewGroup\nimport android.view.WindowInsets\nimport android.widget.FrameLayout\nimport android.widget.LinearLayout', 1)
old_draw = re.search(r'    private fun drawActors\(canvas: Canvas, snapshot: WorldVizBus\.Snapshot, center: FloatArray\) \{.*?\n    \}\n\n    private fun drawHud', s, re.S)
if not old_draw:
    raise SystemExit('BirdEye drawActors not found')
new_draw = '''    private fun drawActors(canvas: Canvas, snapshot: WorldVizBus.Snapshot, center: FloatArray) {
        snapshot.actors.filter { it.local || ClientTrackingOverlayState.enabled }.forEach { actor ->
            val s = project(actor.position, center)
            val paint = if (actor.local) localPaint else peerPaint
            val heading = yawFromQuaternion(actor.quaternion) - yaw
            if (actor.local) {
                val size = 18f
                val path = Path().apply {
                    moveTo(s.x + sin(heading) * size, s.y - cos(heading) * size)
                    lineTo(s.x + sin(heading + 2.45f) * size * 0.78f, s.y - cos(heading + 2.45f) * size * 0.78f)
                    lineTo(s.x + sin(heading - 2.45f) * size * 0.78f, s.y - cos(heading - 2.45f) * size * 0.78f)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawCircle(s.x, s.y, 26f, Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    alpha = 130
                })
            } else {
                val fx = sin(heading)
                val fy = -cos(heading)
                val rx = cos(heading)
                val ry = sin(heading)
                val halfLength = 27f
                val halfWidth = 14f
                val phone = Path().apply {
                    moveTo(s.x + fx * halfLength + rx * halfWidth, s.y + fy * halfLength + ry * halfWidth)
                    lineTo(s.x + fx * halfLength - rx * halfWidth, s.y + fy * halfLength - ry * halfWidth)
                    lineTo(s.x - fx * halfLength - rx * halfWidth, s.y - fy * halfLength - ry * halfWidth)
                    lineTo(s.x - fx * halfLength + rx * halfWidth, s.y - fy * halfLength + ry * halfWidth)
                    close()
                }
                canvas.drawPath(phone, Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                })
                canvas.drawCircle(s.x + fx * 17f, s.y + fy * 17f, 4f, paint)
            }
            canvas.drawText(if (actor.local) "YOU" else "CLIENT • ${actor.label}", s.x + 34f, s.y + 7f, labelPaint)
        }
    }

    private fun drawHud'''
s = s[:old_draw.start()] + new_draw + s[old_draw.end():]
s = s.replace('/** Installs the WORLD presentation view without coupling it to MainActivity internals. */\nobject BirdEyeWorldController {',
'''/** Shared toggle consumed by the AR overlay and bird-eye renderer. */
object ClientTrackingOverlayState {
    @Volatile var enabled: Boolean = true
}

/** Installs the WORLD presentation view without coupling it to MainActivity internals. */
object BirdEyeWorldController {''', 1)
old_controls = re.search(r'        val button = TextView\(activity\)\.apply \{\n            tag = TAG_BUTTON.*?content\.addView\(button, FrameLayout\.LayoutParams\(dp\(96\), dp\(48\), Gravity\.BOTTOM or Gravity\.CENTER_HORIZONTAL\)\.apply \{\n            bottomMargin = dp\(14\)\n        \}\)', s, re.S)
if not old_controls:
    raise SystemExit('BirdEye WORLD button block not found')
new_controls = '''        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(20).toFloat()
        }
        val clients = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xdd16241f.toInt())
            fun render() {
                text = if (ClientTrackingOverlayState.enabled) "CLIENTS ON" else "CLIENTS OFF"
                alpha = if (ClientTrackingOverlayState.enabled) 1f else 0.62f
            }
            render()
            setOnClickListener {
                ClientTrackingOverlayState.enabled = !ClientTrackingOverlayState.enabled
                render()
            }
        }
        val button = TextView(activity).apply {
            tag = TAG_BUTTON
            text = "WORLD"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xdd16241f.toInt())
            setOnClickListener {
                world.setAutoOrbit(true)
                shell.visibility = View.VISIBLE
                shell.bringToFront()
            }
        }
        controls.addView(clients, LinearLayout.LayoutParams(dp(112), dp(48)).apply { rightMargin = dp(8) })
        controls.addView(button, LinearLayout.LayoutParams(dp(96), dp(48)))
        val controlsLayout = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(14) }
        content.addView(controls, controlsLayout)
        controls.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = safe.bottom + dp(14)
            view.layoutParams = lp
            insets
        }
        controls.requestApplyInsets()'''
s = s[:old_controls.start()] + new_controls + s[old_controls.end():]
save(p, s)

# ArRenderer: render remote client phone in AR and fuse local observation into remote vehicle ID.
p, s = load('android/app/src/main/java/com/sirpaul/spatialnomap/ArRenderer.kt')
s = s.replace('import kotlin.math.atan2\nimport kotlin.math.max', 'import kotlin.math.atan2\nimport kotlin.math.cos\nimport kotlin.math.max', 1)
s = s.replace('import kotlin.math.min\nimport kotlin.math.sqrt', 'import kotlin.math.min\nimport kotlin.math.sin\nimport kotlin.math.sqrt', 1)
old = '''            if (remoteReservation != null) {
                synchronized(targetLock) {
                    detectorTrackToSharedId.remove(vehicle.trackId)?.let { staleId ->
                        localVehicles.remove(staleId)
                    }
                }
                continue
            }'''
new = '''            if (remoteReservation != null) {
                val sharedId = remoteReservation.first
                synchronized(targetLock) {
                    detectorTrackToSharedId.remove(vehicle.trackId)?.let { staleId ->
                        if (staleId != sharedId) localVehicles.remove(staleId)
                    }
                    remoteVehicles[sharedId]?.let { shared ->
                        val predicted = VehicleTrackPolicy.predict(
                            shared.point, shared.velocity, shared.lastSeenMs, now, VEHICLE_COAST_MS,
                        )
                        shared.point = smoothPoint(predicted, vehicle.pointWorld, LOCAL_POSITION_ALPHA)
                        shared.velocity = smoothPoint(shared.velocity, vehicle.velocityWorld, LOCAL_VELOCITY_ALPHA)
                        limitVelocity(shared.velocity, MAX_VEHICLE_SPEED_MPS)
                        shared.worldBox = (vehicle.worldBox ?: shared.worldBox
                            ?: defaultVehicleBox(shared.point, shared.velocity, vehicle.label))
                            .let { moveBoxCenter(it, shared.point, shared.velocity) }
                        shared.confidence = max(shared.confidence, vehicle.confidence)
                        shared.lastSeenMs = now
                        detectorTrackToSharedId[vehicle.trackId] = sharedId
                    }
                }
                matched += sharedId
                coordinator.sendPoi(sharedId, vehicle.pointWorld, "$AUTO_CAR_PREFIX$owner")
                continue
            }'''
if old not in s:
    raise SystemExit('ArRenderer remoteReservation block not found')
s = s.replace(old, new, 1)
s = s.replace('''        val now = System.currentTimeMillis()

        val localSnapshot: List<Pair<Long, LocalTarget>>''',
'''        val now = System.currentTimeMillis()
        val peerActors = if (ClientTrackingOverlayState.enabled) {
            WorldVizBus.snapshot().actors.filter { actor ->
                !actor.local && now - actor.lastSeenMs <= CLIENT_POSE_TTL_MS
            }
        } else {
            emptyList()
        }

        val localSnapshot: List<Pair<Long, LocalTarget>>''', 1)
s = s.replace('''        val projected = ArrayList<TargetOverlayView.Target>(
            localSnapshot.size + remoteSnapshot.size + localVehicleSnapshot.size + remoteVehicleSnapshot.size,
        )
        for ((id, target) in localSnapshot) {''',
'''        val projected = ArrayList<TargetOverlayView.Target>(
            localSnapshot.size + remoteSnapshot.size + localVehicleSnapshot.size + remoteVehicleSnapshot.size + peerActors.size,
        )
        peerActors.forEach { actor ->
            projectClientActor(camera, actor, view, projection)?.let { projected += it }
        }
        for ((id, target) in localSnapshot) {''', 1)
marker = '    private fun projectDynamicTarget(\n'
if marker not in s:
    raise SystemExit('ArRenderer projectDynamicTarget marker not found')
client_fun = '''    private fun projectClientActor(
        camera: Camera,
        actor: WorldVizBus.VizActor,
        view: FloatArray,
        projection: FloatArray,
    ): TargetOverlayView.Target? {
        if (actor.position.size < 3 || actor.quaternion.size < 4) return null
        val q = actor.quaternion
        val yaw = atan2(
            2f * (q[3] * q[1] + q[0] * q[2]),
            1f - 2f * (q[1] * q[1] + q[2] * q[2]),
        )
        val forward = floatArrayOf(sin(yaw), 0f, -cos(yaw))
        val phoneBox = VehicleDetector.WorldBox(
            centerWorld = actor.position.copyOf(3),
            forwardWorld = forward,
            halfLengthM = 0.008f,
            halfWidthM = 0.040f,
            halfHeightM = 0.085f,
        )
        val id = CLIENT_TARGET_ID_BASE xor actor.id.hashCode().toLong()
        return projectPoint(
            camera = camera,
            point = actor.position,
            id = id,
            label = "CLIENT • ${actor.label}",
            confidence = 1f,
            isLocal = false,
            view = view,
            projection = projection,
            worldBox = phoneBox,
        )
    }

'''
s = s.replace(marker, client_fun + marker, 1)
s = s.replace('private const val VEHICLE_MEMORY_TTL_MS = 6_500L',
              'private const val VEHICLE_MEMORY_TTL_MS = 4_000L\n        private const val CLIENT_POSE_TTL_MS = 3_000L\n        private const val CLIENT_TARGET_ID_BASE = Long.MIN_VALUE + 0x434C4945L', 1)
save(p, s)

# Release workflow reacts to these feature files.
p, s = load('.github/workflows/spatial-stablear-release.yml')
s = s.replace("      - '.github/workflows/spatial-stablear-release.yml'",
'''      - '.github/workflows/spatial-stablear-release.yml'
      - 'android/app/src/main/java/com/sirpaul/spatialnomap/ArRenderer.kt'
      - 'android/app/src/main/java/com/sirpaul/spatialnomap/BirdEyeWorldView.kt'
      - 'android/app/src/main/java/com/sirpaul/spatialnomap/WorldViz.kt'
      - 'android/app/src/main/java/com/sirpaul/spatialnomap/VehicleTrackPolicy.kt'
      - 'android/app/src/main/java/com/sirpaul/spatialnomap/TargetOverlayView.kt' ''', 1)
save(p, s)

checkpoint = Path('sdk/checkpoints/SPATIAL_SYNC_CLIENTS_VEHICLES_2026-09-14.md')
checkpoint.parent.mkdir(parents=True, exist_ok=True)
checkpoint.write_text('''# Spatial Sync client + vehicle tracking checkpoint — 2026-09-14

## Current scope

- Active transport is still exactly two phones over Wi-Fi Aware. `SharedRoomState` is N-peer-ready state modeling but is not wired to an N-peer transport yet.
- Remote client pose is now visualized in AR as a phone-sized 3D box and in WORLD as an oriented phone footprint.
- WORLD/CLIENTS controls use system-bar/display-cutout bottom insets.
- Client pose expires after 3 seconds without a fresh remote frame.
- Existing vehicle dedupe already used shared IDs + spatial association + deterministic winner selection.
- New behavior: when the second phone observes the already-reserved vehicle, it contributes that observation under the same canonical ID instead of creating or retaining a second car.
- Vehicle memory TTL is 4 seconds after the last observation from either side; if one phone keeps seeing the vehicle, its repeated same-ID updates keep the logical vehicle alive.
- World visualization merges same-ID local/remote targets to one rendered object.

## Required physical acceptance

1. Two phones reach ALIGNING -> LOCKED.
2. CLIENTS ON: each phone shows the other phone's shared-world box and label.
3. CLIENTS OFF hides peer visualization only.
4. WORLD/CLIENTS sit above the Android gesture/navigation bar.
5. A sees a car first; B later sees the same car: one logical target, same canonical ID.
6. A turns away while B keeps seeing car: target remains.
7. B also turns away: target disappears after bounded grace TTL.
8. Reacquisition after expiry may allocate a new vehicle track ID.

## Build gate

The applying workflow must pass `:app:testDebugUnitTest :app:assembleDebug` and verify the packaged XFeat model before committing these product-source changes.
''')
