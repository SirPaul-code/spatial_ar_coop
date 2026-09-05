# Spatial Sync V2 — direct phone-to-phone shared AR

Fresh Android proof of concept for sharing a physical 3D point between two phones **without an access point, router, external server, Cloud Anchors, pre-scanned map, or stored common origin**.

The runtime is fully peer-to-peer:

```text
Phone A                                      Phone B
ARCore + Depth                              ARCore + Depth
     |                                           |
     +---- Wi-Fi Aware discovery / encrypted ---+
     |              direct data path             |
     +---- camera + metric support geometry -----+
     |                                           |
     +<--- local SIFT + metric PnP on both ------+
     |                                           |
     +----------- POI / quality / RTT ---------->+
```

Each phone independently estimates the transform from the other phone's ARCore world into its own local ARCore world. No server participates in discovery, transport, alignment, or POI rendering.

## User flow

1. Install the same APK on both phones.
2. Enable Wi-Fi. Grant Camera and Nearby devices permissions.
3. On phone 1 enter a username and press **CREATE SPACE**.
4. On phone 2 enter a username and press **JOIN NEARBY**.
5. Select the nearby room shown with the creator username and short room code.
6. The phones create an encrypted Wi-Fi Aware data path directly between each other.
7. Point both cameras at a useful part of the same static, textured scene and move them slightly while ARCore gathers geometry.
8. Wait until both HUDs show **LOCKED**.
9. Tap a physical point on either phone.
10. The other phone receives a custom in-app banner: `POI added from <username>`.
11. If the POI is outside the camera view or behind the user, an edge arrow shows which way to turn. When it enters view, the arrow becomes an AR marker with username, distance, and lock quality.

## Implemented V2 features

### Direct transport — no AP

- Wi-Fi Aware publish/subscribe discovery.
- Human-readable username advertised in service metadata.
- Random six-character room code.
- Nearby room list on the joining device.
- Encrypted Wi-Fi Aware network data path using a per-room PSK.
- Direct peer IPv6 TCP socket carried only over that Aware network.
- Binary `SPV2` protocol for frames, metric geometry, POIs, quality state, clear events, and range samples.
- Back-pressure: camera frames use a latest-frame queue so a slow solve cannot grow an unbounded network backlog.
- Reconnect/error state surfaced in the UI.

### Alignment — on both phones

Primary solve is metric 3D-to-2D PnP:

1. ARCore Depth / PointCloud provides metric support samples in the sender's local world.
2. Both phones exchange grayscale camera frames, intrinsics, camera poses, and selected metric samples.
3. OpenCV SIFT finds cross-view correspondences locally on each phone.
4. Remote matched pixels are associated with metric remote-world support points.
5. `solvePnPRansac` + LM refinement estimates the remote world in the local camera.
6. OpenCV camera axes are converted to ARCore axes.
7. The result is composed with the local ARCore camera pose to produce `T_localWorld_remoteWorld`.
8. A remote POI is transformed into the receiver's local world and reprojected every AR frame.

There is no RF-derived scale requirement in the primary solve: scale comes from ARCore metric geometry.

### Quality gates

The app does not report `LOCKED` from a single weak solve. Current gates include:

- minimum PnP inlier count,
- minimum correspondence count,
- median reprojection-error threshold,
- spatial image-coverage threshold,
- aggregate confidence threshold,
- transform consistency across consecutive solves,
- independent readiness exchange — POI placement is enabled only when **both phones** are locked,
- AR tracking-loss invalidation,
- Wi-Fi RTT distance used as an independent sanity check against a geometrically impossible visual solution.

These gates intentionally prefer refusing a weak placement over displaying a confidently wrong marker.

### POI placement

Tap depth uses a local 5×5 ARCore depth neighborhood instead of trusting one pixel:

- median valid depth,
- median absolute deviation rejection,
- ARCore hit-test fallback when dense depth is unavailable.

### AR UI

- username saved locally,
- create/join flow,
- nearby room cards,
- direct-link status pill,
- alignment / inlier / lock status,
- custom animated in-app notification banner,
- in-view POI ring/crosshair,
- owner name,
- distance,
- lock quality,
- edge-clamped directional arrow for off-screen targets,
- correct behind-camera guidance,
- synchronized **CLEAR POI** action.

No Android default Toast is used for the normal POI UX.

## Build on Windows

Requirements:

- JDK 17
- Android SDK platform 36 / build-tools 36
- Android platform-tools (`adb`) on PATH

Build:

```powershell
.\scripts\build-android.ps1
```

Sideload to all connected ADB devices:

```powershell
.\scripts\sideload.ps1
```

Or one phone:

```powershell
.\scripts\sideload.ps1 -Serial <SERIAL>
```

The APK is produced at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

There is no server to start.

## Recommended first field test

For the first real geometry validation:

- use the S25 Ultra and S26 close enough for Wi-Fi Aware,
- start with roughly 0.5–2 m separation between phones,
- point both at the same richly textured static area,
- use a target roughly 1–5 m away,
- move each phone by a small amount so ARCore Depth/VIO has useful geometry,
- wait for `LOCKED` on both devices before tapping,
- after placement, deliberately turn the receiving phone away from the target and verify the edge arrow brings the user back to it.

Once this passes, increase phone separation, target distance, viewpoint difference, and scene difficulty one variable at a time.

## Accuracy statement

The implementation is designed to reject weak geometry and to exploit metric ARCore depth plus multi-view PnP. It does **not** claim a universal 10 cm error under arbitrary lighting, texture, distance, motion, or viewpoint.

A fresh exact 6-DoF transform is fundamentally not observable from one scalar phone-to-phone range alone. Initial lock therefore requires enough overlapping static visual geometry (or some other shared geometric constraint). Once a reliable transform is locked, each phone's local ARCore VIO can continue to render the shared POI while the user turns away from the original common view.

Real-world accuracy still has to be measured on physical devices; CI verifies the code/build, not centimeter error in the field.

## Repository layout

```text
android/                    Native Kotlin + ARCore + OpenCV V2 runtime
scripts/                    Build and ADB sideload helpers
.github/workflows/ci.yml    Android build verification + APK artifact
docs/RESEARCH.md            Research, observability limits, error budgets
docs/PROTOCOL.md            Direct SPV2 peer protocol
docs/V2_IMPLEMENTATION.md   V2 implementation details and field checks
```

The old `server/` folder is retained only as historical/reference code from the first server-assisted experiment. **Spatial Sync V2 does not call or require it at runtime.**
