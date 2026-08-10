# Spatial AR Coop

[![CI](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml) [![Server container](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml) [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Native Android cooperative-perception demo: one phone maps and localizes a site, a sensor phone detects moving objects, and another phone renders their shared spatial tracks even when a real wall occludes them.

The project is designed as a polished, reproducible **demo**, not a claim of production-grade tactical perception. It does not stream camera video to the server. Phones perform AR tracking and object detection locally; the server receives compact map metadata, sparse scan chunks, device poses, and object tracks.

## What is implemented

- Native Kotlin Android app; no Unity runtime.
- Three modes in one APK:
  - **MAP**: local-first sparse point-cloud capture, feature-quality feedback, incremental Cloud Anchor hosting, ground-plane calibration, resumable uploads, and map finalization.
  - **SENSOR**: EfficientDet-Lite0 detection for `person`, `car`, `bird`, `dog`, and `cat`; ARCore hit/depth/ground-ray spatial estimation; lightweight 3D tracking; WebSocket publication.
  - **VIEWER**: shared-site relocalization and always-visible AR overlay for remote tracks through physical occluders. Cloud Anchors are preferred; a same-origin manual alignment fallback keeps the public demo usable without a Google API key.
- Atomic on-device persistence using SQLite plus `.sac.gz` point-cloud chunks.
- Retryable WorkManager outbox for maps, anchors, and scan chunks; changing the configured relay rebinds pending local maps without deleting their data.
- Dependency-free Node.js 22 server with REST, WebSocket rooms, track TTL, startup repair, map persistence, structured logs, SSE log stream, a diagnostics page, and a WebGL sparse point-cloud viewer.
- Docker/Compose, GitHub Actions Android APK build, server tests, and GHCR container publishing.

## Repository layout

```text
android/       Native Android app
server/        Relay, map API, persistence, diagnostics UI
scripts/       Local checks and server launcher
docs/          Architecture, setup, protocol, recovery, deployment, demo guide
.github/       CI, container publishing, issue templates, Dependabot
```

## Fastest demo setup

### 1. Run the server on a laptop on the same Wi-Fi

```bash
cp .env.example .env
# Edit tokens if desired.
docker compose up --build -d
docker compose logs -f spatial-server
```

Open `http://<laptop-lan-ip>:8080/` for live rooms, tracks, metrics, and logs. Open `/viewer?mapId=<map-id>` on the same host to inspect uploaded sparse scan points.

Without Docker:

```bash
cd server
node src/server.mjs
```

### 2. Configure and build the Android APK

Create `android/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
ARCORE_API_KEY=your_google_cloud_api_key
DEFAULT_SERVER_URL=http://192.168.1.20:8080
DEFAULT_API_TOKEN=
```

Enable the **ARCore API** for the Google Cloud project associated with the key. For a debug APK, an Android-restricted key must allow package `com.sirpaul.spatialarcoop.debug` and the signing certificate used by that build. Release builds use `com.sirpaul.spatialarcoop`.

Build:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build downloads the EfficientDet-Lite0 model into the generated assets if it is not present. No model binary or API key is committed. An APK built with `ARCORE_API_KEY=UNCONFIGURED` remains usable with manual same-origin alignment; Cloud Anchor host/resolve is disabled in that build.

A GitHub Actions run also publishes `spatial-ar-coop-debug-apk` as a downloadable workflow artifact.

### 3. Map and run

1. Mark a repeatable physical origin and facing direction with tape or a tripod position.
2. Open the app, set the server URL, and tap **NEW MAP**.
3. In **MAP**, stand at that origin/facing, tap **ALIGN ORIGIN**, then walk around the target site slowly. Translate the phone through space; do not only rotate in place.
4. Use **SET GROUND** while aiming at the walkable surface.
5. With an ARCore API key, let automatic anchors host when feature quality is `GOOD`, or use **ADD ANCHOR** at useful viewpoints around the building. If one area is marked for rescan, return there and tap **RETRY NEARBY**.
6. Tap **FINISH MAP** after scan chunks are saved; uploads continue retrying in the background.
7. On a sensor phone, open **SENSOR**. Resolve a Cloud Anchor, or stand at the marked origin/facing and tap **ALIGN HERE**. Point it at a person, car, or sufficiently large bird.
8. On another phone, open **VIEWER** and localize the same way, then aim toward the occluding building.

Cloud Anchors are the better cross-session path. Manual alignment is a deterministic demo fallback: every participating device must align at the same physical origin and heading before moving away.

## Recovery behavior

The scan pipeline is deliberately local-first:

- Each point-cloud chunk is compressed to a temporary file, atomically renamed, and then inserted into the SQLite outbox.
- Server uploads are idempotent by chunk ID.
- Successful Cloud Anchor IDs are persisted immediately and are not recreated on retry.
- **RETRY NEARBY** revisits only the closest failed anchor record, preserves its ID, and leaves every completed anchor and scan chunk untouched.
- If the app dies while a Cloud Anchor host operation is still in flight, that anchor is marked `NEEDS_RESCAN`; completed chunks and completed anchors remain intact.
- ARCore's unpublished internal visual-feature state cannot be reconstructed from the saved sparse point cloud, so only the failed local area must be revisited—not the entire site.

See [mapping and recovery](docs/mapping-and-recovery.md) for the exact state machine.

## Server hosting and GitHub

GitHub Actions can build, test, retain logs, and publish the server image to GitHub Container Registry. GitHub itself does **not** keep an application server continuously running. Deploy the published container to a laptop, home server, VPS, Kubernetes, Fly.io, Render, or another container host, and mount persistent storage at `/data`.

## Current technical limits

- Cloud Anchors require internet access and a configured Google Cloud project. This public build uses API-key authorization, so hosted anchors have a 24-hour maximum lifetime; rebuild with keyless authorization for longer-lived anchors. Manual same-origin alignment works without Cloud Anchors, but cannot automatically recover an arbitrary device start position.
- Sparse scan chunks are useful for diagnostics and an overview map; they are not a dense photorealistic digital twin.
- Object depth from one RGB phone is approximate. Ground-plane fusion makes feet/wheels more stable, but small birds and long distances remain difficult.
- Multi-sensor identity fusion is not yet implemented. Track IDs are namespaced by source device.
- The server is a demo relay, not a hardened zero-trust production backend.
- The viewer overlay is an intentional x-ray visualization and does not attempt real-world depth occlusion.

See [limitations](docs/limitations.md) for measurement targets and next engineering gates. Map deletion is explicit: the app offers either device-only deletion or server-and-device deletion, and preserves local data when a server deletion fails.

## Verification

```bash
./scripts/check.sh server   # server install, tests, and syntax checks
./scripts/check.sh android  # Android unit tests plus debug APK
./scripts/check.sh          # both
```

The server test suite covers persistence, startup repair, protocol validation, idempotent scan upload, sparse point-cloud aggregation, REST, WebSocket relay, and track expiry. Android unit tests cover rigid transforms, scan encoding, rotation mapping, and track association.

## Privacy and responsible use

Maps, anchors, tracks, logs, and scan chunks can reveal sensitive information about a physical site. Obtain consent, keep deployments access-controlled, and delete captures that are no longer required. Object inference runs on device; the current MediaPipe privacy notice states that its Android Tasks APIs send performance and utilization metrics to Google, so distributors are responsible for any consent or notice their jurisdiction requires. This project is intended for controlled demos, robotics research, training, games, and cooperative spatial-computing experiments—not covert surveillance or autonomous targeting. See [privacy and safety](docs/privacy-and-safety.md).

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
