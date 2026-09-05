# Spatial No-Map Runtime POC

Fresh proof of concept for sharing a physical 3D point between two Android phones without Cloud Anchors, a pre-scanned environment, or a stored common map.

This branch intentionally replaces the old map-first flow with a runtime alignment problem:

```text
World_A -- T_WB_WA --> World_B
```

Phone A taps a physical point. The app estimates that point in A's metric ARCore world. A and B send synchronized-ish camera snapshots, ARCore poses, intrinsics and metric support geometry to a small server. The server estimates the current transform from `World_A` into `World_B`; B then renders the transformed target in its own ARCore view.

## What is implemented

### Primary path: metric 3D-to-2D alignment

1. Both phones run independent ARCore VIO sessions.
2. A samples metric scene geometry from ARCore Depth; ARCore point cloud is the fallback.
3. A sends support samples as `[u_A, v_A, X_WA, Y_WA, Z_WA]` plus a grayscale camera frame.
4. B sends its frame, intrinsics and current ARCore physical-camera pose.
5. The server matches visual features A <-> B with SIFT.
6. A-side matched pixels are associated with nearby metric support samples.
7. `solvePnPRansac` estimates the pose of `World_A` in B's camera directly in metric scale.
8. The server converts OpenCV camera axes to ARCore camera axes and composes with B's local ARCore camera pose to obtain `T_WB_WA`.
9. A's target point is transformed by `P_WB = T_WB_WA * P_WA` and sent to B.
10. B reprojects `P_WB` every render frame using its current ARCore view/projection matrices.

This route does **not** need RF ranging to establish scale if enough valid A-side metric geometry and cross-view matches exist.

### Fallback path: essential matrix + direct phone-to-phone range

If metric PnP cannot be solved, the server can estimate relative rotation and translation direction from an essential matrix. Translation magnitude is then scaled with a recent range measurement.

The Android POC implements direct Wi-Fi Aware peer discovery and Wi-Fi RTT ranging:

- A publishes a Wi-Fi Aware service with ranging enabled.
- B discovers A and ranges the `PeerHandle` through `WifiRttManager`.
- No Wi-Fi access point is required for this peer path.
- Hardware support is optional; if Aware/RTT is absent, the app keeps using the visual path.

The architecture leaves room for Android 16 unified ranging / Bluetooth Channel Sounding and UWB as stronger or additional range constraints.

## What this branch does NOT use

- Google Cloud Anchors
- a pre-generated shared map
- a previously scanned site
- a permanent global origin
- a manual common marker for normal operation

A common marker can still be useful as an optional calibration/debug path later, but it is not part of the core POC.

## Repository layout

```text
android/                    Native Kotlin + ARCore client
server/                     FastAPI + OpenCV runtime alignment server
scripts/                    Build, run and ADB sideload helpers
.github/workflows/ci.yml    Server tests + Android APK build

docs/RESEARCH.md            Technical research, limits and architecture
docs/PROTOCOL.md            WebSocket payloads and coordinate conventions
```

## Fastest Windows path

Requirements:

- JDK 17
- Android SDK platform 36 / build-tools 36
- Android platform-tools (`adb`) on PATH
- Docker Desktop, or Python 3.12 for the server
- two ARCore-capable Android phones on a network path to the server

Build:

```powershell
.\scripts\build-android.ps1
```

Sideload to every connected ADB device:

```powershell
.\scripts\sideload.ps1
```

Or to one serial:

```powershell
.\scripts\sideload.ps1 -Serial R3CN...
```

Run the server:

```powershell
.\scripts\run-server.ps1
```

The server listens on port `8000`.

## Field test

1. Start the server on a laptop reachable by both phones, e.g. `192.168.1.10:8000`.
2. Install the same APK on both phones.
3. On phone A leave role `A`; on phone B switch to role `B`.
4. Enter the same server address and room name on both.
5. Press `CONNECT` on both.
6. Move each phone for a few seconds so ARCore tracking and Depth have geometry.
7. Point both cameras so they share a useful part of the static scene. They do not need the same framing.
8. Tap a physical point on A.
9. The server should report either `metric_depth_pnp` or, when metric PnP fails and RTT is available, `essential_plus_range`.
10. B renders a red marker for the target in its own AR view.

For the first geometry validation, use a textured static scene, moderate phone separation, and a target roughly 1-8 m away. Hard cases such as 50-100 m targets, blank walls, moving-only correspondences, or devices on opposite sides of a building are intentionally treated as later experiments rather than hidden by a fake success state.

## Server without Docker

```bash
cd server
python -m venv .venv
# activate the venv
pip install -r requirements.txt
PYTHONPATH=. pytest -q
uvicorn app:app --host 0.0.0.0 --port 8000
```

On Windows PowerShell after activating the venv:

```powershell
$env:PYTHONPATH = "."
pytest -q
uvicorn app:app --host 0.0.0.0 --port 8000
```

## Debug endpoint

```text
GET /healthz
GET /api/v1/rooms/{roomId}
```

The room endpoint exposes whether both frames exist, whether a range measurement is present, and the latest alignment diagnostic.

## Physical limitation, not an implementation bug

A scalar phone-to-phone range does not determine a 6-DoF transform. If the two phones never share visual geometry and no earlier alignment exists, exact `T_WB_WA` is unobservable from range alone. GNSS/heading, UWB direction, an intermediary device, or a deliberate common initialization observation can add missing constraints. See `docs/RESEARCH.md`.
