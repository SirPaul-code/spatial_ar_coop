# Spatial AR Coop

[![CI](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/ci.yml) [![Server container](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml/badge.svg)](https://github.com/SirPaul-code/spatial_ar_coop/actions/workflows/container.yml) [![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Spatial AR Coop is a self-hosted native Android + ARCore system for sharing a common spatial frame between phones. One phone can map/localize a place and report compact object tracks; another localized phone can render those shared tracks at their approximate 3D position even when a physical wall blocks the second phone's camera view.

The project is private-first and has **no central Spatial AR server or map directory**. Users run their own relay and share individual places explicitly with per-map QR invites.

Camera inference runs on the phone. The server receives map metadata, sparse diagnostic geometry, participant poses and compact object tracks — not a camera-video stream.

## What is included

- Native Kotlin Android app; no Unity runtime.
- **Guided map setup**: automatic shared origin for new maps, local-first sparse point-cloud capture, automatic Cloud Anchor placement/recovery, opportunistic floor detection, clear readiness/progress and resumable uploads.
- **Live AR**: all participants observe remote tracks; each phone can independently enable/disable object reporting.
- QR-first private place sharing with server identity verification and isolated per-map keys.
- MediaPipe EfficientDet-Lite0 detection for `person`, `car`, `bird`, `dog` and `cat` with ARCore depth/hit/ground-plane spatial estimation.
- Shared-site localization with Cloud Anchors plus a manual shared-origin fallback for builds without Cloud Anchor credentials.
- Always-visible remote-track overlay for cooperative/x-ray-style spatial visualization.
- SQLite + `.sac.gz` local persistence and WorkManager upload retry.
- Node.js 22 REST/WebSocket server with map persistence, isolated access keys, live-state diagnostics, QR invite rendering, a sparse point-cloud debugger and Docker support.
- Stable self-hosted server identities and isolated per-map credentials.
- Mobile-friendly WebSocket heartbeat independent from short object-track TTLs.
- Docker Compose, private Tailscale deployment docs, reproducible Android builds, optional release signing and GitHub Actions CI.

## Privacy and sharing model

Every self-hosted installation has three different values:

| Value | Secret | Scope | Normal holder |
| --- | --- | --- | --- |
| `serverId` | no | identifies one server installation | all clients |
| `sar_admin_...` | yes | owner/admin access to that whole server | owner devices only |
| `sar_map_...` | yes | access to exactly one shared place/map | invited participants |

**Participants do not need the owner token.** Tailscale or another network path only makes a server reachable; it does not reveal maps. A participant learns about a place only when the owner intentionally shares that place's QR/link.

A place QR encodes a deep link like:

```text
spatialar://join?url=https%3A%2F%2Fserver.example&serverId=srv_...&mapId=map-...&key=sar_map_...
```

The Android app verifies the server's `/api/v1/info` identity before importing the map. A key for map A cannot list the server's maps, read map B or join map B's WebSocket room. Owners can rotate one map key to revoke old QR codes without changing other maps.

See [server identity and private sharing](docs/SHARING_AND_IDENTITY.md) and the practical [field-use guide](docs/FIELD_USE.md).

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

The owner token is for owner/admin devices and the operator dashboard. It is **not** the token participants use to join a place.

Full server setup, backup, upgrades and credential rotation: [SELF_HOSTING.md](docs/SELF_HOSTING.md).

## Private remote access with Tailscale

The recommended remote deployment leaves Docker on localhost and exposes it only to your tailnet with Tailscale Serve HTTPS:

```bash
sudo tailscale serve --bg 8080
tailscale serve status
```

Set the reported private HTTPS endpoint in `.env` so generated dashboard QR codes contain an immediately usable address:

```dotenv
SPATIAL_PUBLIC_URL=https://your-machine.your-tailnet.ts.net
```

Then restart the server:

```bash
docker compose up -d
```

Phones install the normal Tailscale Android app and can reach the server while connected to the permitted tailnet. Spatial AR does not embed the Tailscale SDK.

For collaboration outside your own tailnet, Tailscale can share only the server machine; send/show the Spatial AR place QR separately so the recipient gets network access **and** only the intended map credential.

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

The app uses the official Google Play Services code-scanner flow for QR joining; no third-party scanner app is required.

Release signing is optional and configured only through noncommitted environment/local properties. See [ANDROID_BUILD.md](docs/ANDROID_BUILD.md).

## Owner workflow

1. Run your self-hosted server and optionally publish it privately through Tailscale Serve.
2. In Android open **Owner server & diagnostics**.
3. Enter the reachable server URL and its `sar_admin_...` owner token.
4. Tap **Save & verify owner server**. This checks server identity/auth and refreshes maps; there are no separate Save/Test/Sync owner actions.
5. Tap **New place**.
6. Follow guided **Map setup**. For a new map, shared origin, sparse scanning, Cloud Anchor placement and floor detection run automatically. The screen shows points/chunks, feature quality, anchor state, floor state and server/offline sync state.
7. **Keep scanning** changes to **Finish setup** only when useful geometry exists and, for Cloud-Anchor-enabled builds, at least one anchor is hosted.
8. Tap **Finish setup**. Local data is flushed and the final server upload remains queued/retryable.
9. Open **Share & manage** and show the generated QR to participants.
10. Use **Revoke old invites & create new QR** when old participant access should stop working.

Changing the owner/default server profile does not rewrite existing places. Each saved map keeps its own server URL, server identity and access key.

Advanced mapping recovery actions are under **More** and are explained in [FIELD_USE.md](docs/FIELD_USE.md); they are not mandatory steps for a healthy new map.

## Participant workflow

1. Make sure the phone can reach the server, for example through the intended Tailscale connection.
2. Open Spatial AR and tap **Scan place QR**.
3. Scan the owner's QR. The app verifies the endpoint's `serverId` and requests exactly the invited map using its `sar_map_...` key.
4. The place appears under **Places on this phone**; no owner/admin token is required.
5. Tap **Live AR**.
6. Move slowly while the app resolves a saved Cloud Anchor. The header explicitly shows localizing/localized and server connection state.
7. The phone receives shared tracks once localized/connected. Tap **Start reporting** only if this phone should also run local object detection and publish tracks.

**Paste invite** remains available for links sent through chat/email.

## Operator dashboard

Open the self-hosted server root in a browser. The operator dashboard is intentionally owner-only because it can enumerate every map on that server.

It now presents an explicit **Connect as server owner** gate. Enter the `sar_admin_...` token. A `sar_map_...` place key is intentionally rejected there.

After authentication it provides:

- map selection and truthful server map status,
- sparse uploaded point-cloud visualization,
- Cloud Anchor records/status/quality/errors,
- connected participants, liveness and last pose/track ages,
- current compact object tracks,
- **Share place QR** for the selected map.

Opening the dashboard from the Android owner screen transfers the already configured owner token in a URL fragment that the page consumes locally; it is not sent as a query parameter to the server.

## Guided map setup: what is automatic?

For a brand-new map, normal setup does not require separate Align / Add Anchor / Set Ground buttons:

- the first stable ARCore tracking pose establishes the gravity-aligned site origin,
- sparse scan capture is continuous and local-first,
- upload retry is automatic,
- Cloud Anchor hosting is automatic when feature quality/spacing permit,
- floor detection is attempted automatically,
- readiness is computed continuously,
- the final Finish action provides visible completion feedback.

The **More** menu keeps manual recovery tools for unfinished/legacy maps and diagnostics. For example, **Re-establish shared origin** is only appropriate at the original map start position/heading; using it at an arbitrary position would intentionally redefine the local-to-site transform and misalign existing geometry.

See [FIELD_USE.md](docs/FIELD_USE.md) for exact states and controls.

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

Public/non-secret endpoints:

```text
GET /healthz
GET /api/v1/info
```

Owner-only operations include global map listing/creation/deletion, metrics, logs and map-key rotation.

Map credentials authorize only the specified map's metadata, anchors, scan chunks, point-cloud preview, live-state endpoint, QR invite and WebSocket room.

The sparse point cloud is diagnostic geometry, not a dense digital twin. The server cannot independently determine a Cloud Anchor's physical ARCore world pose; a device must resolve it.

## Repository layout

```text
android/       Native Android application
server/        REST/WebSocket relay, persistence and debugger UI
docs/          Self-hosting, Tailscale, Android builds, field use, protocol and architecture
scripts/       Local verification helpers
.github/       CI, container build, issue templates, dependency updates
```

## Verification

Local server checks:

```bash
cd server
npm ci --ignore-scripts
npm test
npm run check
npm audit --omit=dev --audit-level=high
```

Android:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

GitHub Actions runs both suites, builds the server container and uploads the debug APK from successful CI runs. The server suite covers REST/WebSocket behavior, persistence, scan upload/readback, point-cloud sampling, QR invites, track expiry and cross-map authorization isolation.

Passing CI proves that the public source compiles and its automated protocol/storage/security tests pass. It does **not** replace a physical multi-phone Cloud Anchor field test for a particular site/device/network combination.

## Backups

All durable server state is below `/data`, including the `server.json` identity/credential file and map/chunk data. Preserve it as one unit. Restoring it preserves the same logical server identity and map keys.

See [SELF_HOSTING.md](docs/SELF_HOSTING.md) for Docker-volume backup/restore procedures.

## Technical boundaries

- Cloud Anchors require internet access and a correctly configured Google Cloud/ARCore build credential.
- The manual origin fallback is deterministic only when participating devices align at the same physical origin and heading; it cannot automatically recover arbitrary starts.
- Sparse scans are intended for diagnostics/shared-map context, not photorealistic reconstruction.
- Monocular/depth target estimates are approximate; ground-plane fusion helps feet/wheels but distant/small objects remain difficult.
- Track identity is namespaced by source device; cross-sensor person identity fusion is not performed.
- A map QR/key is a bearer secret. Use HTTPS/Tailscale on untrusted networks and rotate it when access should be revoked.
- Public-internet deployments need normal production controls around the reference server: TLS, rate limiting, monitoring, host hardening and a deployment-specific ingress policy.
- The overlay intentionally shows shared remote tracks through physical occluders; it is a cooperative spatial-computing visualization, not a real-world depth-occlusion renderer.

## Privacy and responsible use

Maps, anchors, sparse geometry, poses and tracks can reveal sensitive information about a physical site. Obtain appropriate consent, protect server state and invite links, and remove captures that are no longer required. See [SECURITY.md](SECURITY.md) and [privacy and safety](docs/privacy-and-safety.md).

This repository is intended for cooperative spatial-computing, robotics research, games, training and controlled-site experiments. It is not intended for covert surveillance or autonomous targeting.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
