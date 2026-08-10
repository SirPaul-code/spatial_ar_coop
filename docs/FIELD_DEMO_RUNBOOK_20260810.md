# Field demo runbook

This runbook is for the controlled two-device cooperative AR demonstration. It deliberately separates spatial alignment, networking, perception, and presentation so that a failure in one layer can be diagnosed without rescanning the entire site.

## 1. Required equipment

- Two ARCore-supported Android devices. A third device is useful for recording a split-screen demo.
- A reachable server: a laptop on the same Wi-Fi/LAN, a small VPS, or a container host.
- Internet access while hosting/resolving Google Cloud Anchors.
- A Google Cloud project with the ARCore API enabled and an API key restricted to the Android package and signing certificate.
- A textured, well-lit place for the root anchor. Avoid blank walls, mirrors, repeating fences, moving vegetation, and very dark surfaces.

Do not commit API keys, service credentials, signing keys, recordings, or private maps to the repository.

## 2. Start the server

From the repository root:

```bash
docker compose up --build -d
docker compose ps
curl --fail http://localhost:8080/healthz
docker compose logs -f --tail=200 server
```

When the phones are on the same LAN, use the computer's LAN address rather than `localhost`, for example `http://192.168.1.50:8080`. Use TLS (`https`/`wss`) when the server is exposed outside a trusted LAN.

Server state is persisted in the configured data volume. Restarting or rebuilding the container must not require rescanning a map.

## 3. Build or install the Android app

The automated build publishes a rolling prerelease only after server tests, a container smoke test, Android unit tests, and `assembleDebug` succeed:

- `demo-latest` is the normal build path.
- `demo-bootstrap-latest` is the independent recovery build path.

For a local build, configure the values described by the repository's Android configuration example, then run the checked-in Gradle wrapper:

```bash
export ARCORE_API_KEY='your-restricted-key'
export SPATIAL_SERVER_URL='http://192.168.1.50:8080'
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
```

If the Android project is under `android/`, run the same command from that directory. Install the generated debug APK with:

```bash
adb install -r path/to/app-debug.apk
```

A CI placeholder API key is sufficient to compile, but Cloud Anchor hosting and resolving require a valid key at runtime.

## 4. Create a recoverable map

1. Open **Maps** and choose **New map**.
2. Give the map a stable name and verify the server connection.
3. Place/host the root anchor only after tracking is stable and feature-map quality is good.
4. Walk the site slowly. Translate the phone through space; do not only rotate in place.
5. Cover anchor areas from several viewing angles and heights.
6. Let checkpoint uploads complete periodically. A checkpoint is immutable and idempotent: retrying the same chunk must not duplicate data.
7. Add additional anchor areas around corners or on the opposite side of a structure when the site is larger than a single Cloud Anchor neighborhood.
8. Finalize only after the app reports that all local chunks are acknowledged by the server.

The scanner stores an append-only local draft before upload. If the app, phone, network, or server stops, reopen the draft, resolve a previously hosted anchor, and continue. Never delete the local draft until the finalized server manifest has been verified.

## 5. Validate alignment before enabling detection

Use the manual shared-marker gate first:

1. Both devices join the same map.
2. Both resolve the same site frame.
3. Device A places a marker on a visible ground point.
4. Device B views the same point from another angle.
5. Repeat at several distances and around a corner.

Do not diagnose detector accuracy until this gate is stable. A consistent offset indicates anchor/site-transform calibration. A drifting offset indicates tracking or relocalization quality. A marker that is correct near the anchor but wrong far away indicates map scale/pose quality or an incorrect anchor-graph transform.

## 6. Run the cooperative perception demo

1. Put one phone in **Sensor** mode and the other in **Viewer** mode.
2. Verify that both show the same map ID, a resolved site frame, a live WebSocket connection, and a recent server clock/round-trip time.
3. Start with a person or car. These are easier validation targets than small birds.
4. The sensor should show the local detection and track ID.
5. Move the target behind the structure while the viewer remains on the other side.
6. The viewer renders the shared track as an always-visible AR overlay, including label, confidence, age, distance, source, and uncertainty.
7. After the basic gate works, test `bird` detections with chickens. A custom chicken model is a separate model-quality improvement, not a spatial-alignment requirement.

A stale track must fade and expire. The viewer must never present old data as current merely because the WebSocket connection remains open.

## 7. Recovery drills

Run these before recording the polished demo:

- Disable Wi-Fi during scanning, continue collecting locally, restore Wi-Fi, and verify ordered idempotent upload.
- Force-stop the scanner, reopen the draft, relocalize, and resume without losing acknowledged chunks.
- Restart the server container and verify that maps/checkpoints/tracks survive according to their documented retention policy.
- Walk outside the mapped area and return; the UI should distinguish `tracking lost`, `site unresolved`, and `network disconnected`.
- Kill the sensor connection; the viewer track should age, extrapolate only within the configured bound, then disappear.
- Submit the same checkpoint twice and verify that the server returns the existing acknowledgement rather than storing a duplicate.

## 8. Logging during a field session

Server:

```bash
docker compose logs -f --timestamps --tail=500 server | tee field-server.log
```

Android:

```bash
adb logcat -c
adb logcat -v threadtime | tee field-android.log
```

Useful filtered view:

```bash
adb logcat -v threadtime | grep -E 'Spatial|ARCore|CloudAnchor|MediaPipe|ObjectDetector|OkHttp|WebSocket|WorkManager'
```

Record the map ID, device/client IDs, app commit, APK SHA-256, server image digest, and wall-clock time at the beginning of a test. This makes a visual glitch traceable to the corresponding server and device events.

## 9. Definition of a publishable demo

- A fresh clone can build the server container and Android debug APK.
- Server tests and container health checks pass.
- Both devices resolve the same site and pass the manual shared-marker gate.
- A sensor track appears at the physically correct position on the viewer through an occluding structure.
- Network loss, process restart, and scan resume have been exercised.
- The video labels the project accurately as a cooperative AR perception prototype, not as an implementation of any proprietary military system.
- No faces, private neighboring property, API keys, or sensitive map data are published without permission.
