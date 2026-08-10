# Spatial AR Coop

[![CI](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml) [![Server container](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml) [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Spatial AR Coop is a self-hosted native Android + ARCore system for sharing a common spatial frame between phones. One phone can map/localize a place and report compact object tracks; another localized phone can render those shared tracks at their approximate 3D position even when a physical wall blocks the second phone's camera view.

The project is private-first and has **no central Spatial AR server or map directory**. Users run their own relay, and maps are shared explicitly with per-map invite keys.

Camera inference runs on the phone. The server receives map metadata, sparse diagnostic geometry, participant poses and compact object tracks — not a camera-video stream.

## What is included

- Native Kotlin Android app; no Unity runtime.
- **Map setup**: local-first sparse point-cloud capture, feature-quality feedback, ground calibration, Cloud Anchor hosting/recovery and resumable uploads.
- **Live AR**: all participants receive remote tracks; each phone can independently enable/disable object reporting.
- MediaPipe EfficientDet-Lite0 detection for `person`, `car`, `bird`, `dog` and `cat` with ARCore depth/hit/ground-plane spatial estimation.
- Shared-site localization with Cloud Anchors plus a manual shared-origin fallback for builds without Cloud Anchor credentials.
- Always-visible remote-track overlay for cooperative/x-ray-style spatial visualization.
- SQLite + `.sac.gz` local persistence and WorkManager upload retry.
- Node.js 22 REST/WebSocket server with map persistence, track TTL, live-state diagnostics, logs, a WebGL sparse point-cloud debugger and Docker support.
- Stable self-hosted server identities and isolated per-map credentials.
- Docker Compose, private Tailscale deployment docs, reproducible Android builds, optional release signing and GitHub Actions CI.

## Privacy and sharing model

Every self-hosted installation has three different values:

| Value | Secret | Scope |
| --- | --- | --- |
| `serverId` | no | identifies one server installation |
| `sar_admin_...` | yes | owner/admin access to that whole server |
| `sar_map_...` | yes | access to exactly one shared map |

A participant invite looks like:

```text
spatialar://join?url=https%3A%2F%2Fserver.example&serverId=srv_...&mapId=map-...&key=sar_map_...
```

The Android app verifies the server's `/api/v1/info` identity before importing the map. A key for map A cannot list the server's maps, read map B or join map B's WebSocket room. Owners can rotate one map key to revoke old invite links without changing other maps.

See [server identity and private sharing](docs/SHARING_AND_IDENTITY.md).

## Five-minute self-host

Requirements: Docker Engine + Docker Compose v2.

```bash
git clone https://github.com/SirPaul-code/spatial_ar_coop.git
cd spatial_ar_coop
cp .env.example .env
docker compose up -d --build
```

The reference Compose file binds to `127.0.0.1:8080` by default.

Check it:

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/api/v1/info
```

On first start the server generates a stable server ID and random owner token unless you supplied them explicitly. Read the owner identity:

```bash
docker compose exec spatial-server npm run identity
```

Full server setup, backup, upgrades and credential rotation: [SELF_HOSTING.md](docs/SELF_HOSTING.md).

## Private remote access with Tailscale

The recommended remote deployment leaves Docker on localhost and exposes it only to your tailnet with Tailscale Serve HTTPS:

```bash
sudo tailscale serve --bg 8080
tailscale serve status
```

Set the reported private HTTPS endpoint in `.env`:

```dotenv
SPATIAL_PUBLIC_URL=https://your-machine.your-tailnet.ts.net
```

Then:

```bash
docker compose up -d
```

Phones install the normal Tailscale Android app and can reach the server while connected to the permitted tailnet. Spatial AR does not embed the Tailscale SDK.

For collaboration outside your own tailnet, Tailscale can share only the server machine; send the Spatial AR map invite separately so the recipient gets network access **and** only the intended map credential.

Detailed instructions: [TAILSCALE.md](docs/TAILSCALE.md).

## Build the Android APK

Requirements: JDK 17 and Android SDK/API 36.

Create `android/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
ARCORE_API_KEY=UNCONFIGURED
DEFAULT_SERVER_URL=https://your-server.your-tailnet.ts.net
DEFAULT_API_TOKEN=
```

Build and test:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For real Cloud Anchor host/resolve, configure an ARCore API key at build time. A build with `ARCORE_API_KEY=UNCONFIGURED` still compiles and keeps manual shared-origin localization for development/testing, but Cloud Anchor host/resolve is disabled.

Release signing is optional and configured only through noncommitted environment/local properties. See [ANDROID_BUILD.md](docs/ANDROID_BUILD.md).

## Owner workflow

1. Run your self-hosted server and optionally publish it privately through Tailscale Serve.
2. In the Android app open **Server owner & diagnostics**.
3. Enter the server URL and its `sar_admin_...` owner token.
4. Use **Test & sync** and verify the displayed server identity.
5. Tap **New place**. The server creates a private map plus an independent `sar_map_...` key that is saved on the owner phone.
6. Complete **Map setup**: align the origin, capture the site, set ground and host useful Cloud Anchors.
7. Mark the map ready.
8. Open **Manage & share → Share invite** and send the generated `spatialar://join?...` link to an intended participant.
9. Use **Rotate invite key** when old invites should stop working.

Changing the owner/default server profile does not rewrite existing maps. Each saved map keeps its own server URL, server identity and access key.

## Participant workflow

1. Make sure the phone can reach the server (for example through the intended Tailscale connection).
2. Open the owner's `spatialar://join?...` link, or paste it into **Join a shared place**.
3. The app verifies the endpoint's `serverId` and requests exactly the invited map using its `sar_map_...` key.
4. Localize the place with a Cloud Anchor or the manual shared-origin fallback.
5. Open **Live AR session**.
6. The phone receives shared tracks immediately; enable **Start reporting** if this phone should also run local object detection and publish tracks.

The participant does not need and should not receive the owner's `sar_admin_...` token.

## Architecture

```text
Phone A                          Self-hosted server                       Phone B
-------                          ------------------                       -------
ARCore VIO                                                            ARCore VIO
Cloud Anchor / shared site  <-> map metadata + scan chunks <->  Cloud Anchor / shared site
local object detector       ---> compact WS track batches     --->  remote track store
3D target estimate          ---> participant pose/status      --->  always-visible AR overlay
```

Video frames stay local. Network traffic is primarily compact JSON state plus compressed sparse mapping chunks.

Moving objects are stored as coordinates in the common site frame; they are not ARCore anchors. For a source-phone point `p_Ws` and viewer local frame `Wv`:

```text
p_S  = inverse(T_Ws_S) * p_Ws
p_Wv = T_Wv_S * p_S
```

This lets independently localized devices describe the same target position.

## Server API and diagnostics

Public/non-secret endpoint:

```text
GET /healthz
GET /api/v1/info
```

Owner-only operations include global map listing/creation/deletion, metrics, logs and map-key rotation.

Map credentials authorize only the specified map's metadata, anchors, scan chunks, point-cloud preview, live-state endpoint and WebSocket room.

The operator dashboard at the server root visualizes:

- sparse uploaded map points,
- Cloud Anchor records/status/quality/errors,
- connected participants and poses,
- last track age per participant,
- current compact object tracks.

The sparse point cloud is diagnostic geometry, not a dense digital twin.

## Repository layout

```text
android/       Native Android application
server/        REST/WebSocket relay, persistence and debugger UI
docs/          Self-hosting, Tailscale, Android builds, protocol and architecture
scripts/       Local verification helpers
.github/       CI, container publishing, issue templates, dependency updates
```

## Verification

Local server checks:

```bash
cd server
npm install --ignore-scripts
npm test
npm run check
```

Android:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions runs both suites and uploads the debug APK from successful CI runs. The server suite covers REST/WebSocket behavior, persistence, scan upload/readback, point-cloud sampling, track expiry and cross-map authorization isolation.

Passing CI proves that the public source compiles and its automated protocol/storage/security tests pass. It does **not** replace a physical multi-phone Cloud Anchor field test for a particular site/device/network combination.

## Backups

All durable server state is below `/data`, including the `server.json` identity/credential file and map/chunk data. Preserve it as one unit. Restoring it preserves the same logical server identity and map keys.

See [SELF_HOSTING.md](docs/SELF_HOSTING.md) for a Docker-volume backup/restore procedure.

## Technical boundaries

- Cloud Anchors require internet access and a correctly configured Google Cloud/ARCore build credential.
- The manual origin fallback is deterministic only when participating devices align at the same physical origin and heading; it cannot automatically recover arbitrary starts.
- Sparse scans are intended for diagnostics/shared-map context, not photorealistic reconstruction.
- Monocular/depth target estimates are approximate; ground-plane fusion helps feet/wheels but distant/small objects remain difficult.
- Track identity is namespaced by source device; cross-sensor person identity fusion is not performed.
- A map key is a bearer secret. Use HTTPS/Tailscale on untrusted networks and rotate it when access should be revoked.
- Public-internet deployments need normal production controls around the reference server: TLS, rate limiting, monitoring, host hardening and a deployment-specific ingress policy.
- The overlay intentionally shows shared remote tracks through physical occluders; it is a cooperative spatial-computing visualization, not a real-world depth-occlusion renderer.

## Privacy and responsible use

Maps, anchors, sparse geometry, poses and tracks can reveal sensitive information about a physical site. Obtain appropriate consent, protect server state and invite links, and remove captures that are no longer required. See [SECURITY.md](SECURITY.md) and [privacy and safety](docs/privacy-and-safety.md).

This repository is intended for cooperative spatial-computing, robotics research, games, training and controlled-site experiments. It is not intended for covert surveillance or autonomous targeting.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
