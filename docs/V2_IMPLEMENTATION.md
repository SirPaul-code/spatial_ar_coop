# Spatial Sync V2 implementation

## Goal

Two ordinary ARCore phones establish a shared physical POI coordinate relationship at runtime without a router, access point, cloud anchor, stored scene map, or external computer.

V2 deliberately separates four problems:

1. **Discovery / transport** — Wi‑Fi Aware.
2. **Metric world alignment** — ARCore geometry + cross-view visual PnP.
3. **Validation** — multi-frame transform consensus + RTT sanity + AR tracking state.
4. **UX** — room discovery, username identity, lock state, POI notification, and off-screen guidance.

## Transport state machine

Creator:

```text
IDLE
  -> attach Wi-Fi Aware
  -> PUBLISH(room, username, ranging enabled when supported)
  -> receive JOIN
  -> create TCP ServerSocket
  -> request encrypted Wi-Fi Aware NDP with advertised TCP port
  -> peer connects
  -> DIRECT_CONNECTED
```

Joiner:

```text
IDLE
  -> attach Wi-Fi Aware
  -> SUBSCRIBE
  -> list discovered rooms
  -> user selects room
  -> send JOIN
  -> request matching encrypted NDP
  -> read peer IPv6 + port from WifiAwareNetworkInfo
  -> connect through Aware Network.socketFactory
  -> DIRECT_CONNECTED
```

A camera frame backlog is explicitly avoided. Only the newest unsent frame is retained while the writer is busy.

## Symmetric local solvers

There is no permanent A/B role after connection. Both devices run the same solver.

For local device `L` receiving a frame from remote device `R`:

```text
remote metric samples:
[u_R, v_R, X_WR, Y_WR, Z_WR]

remote SIFT feature <-> local SIFT feature
               |
               v
matched remote metric XYZ <-> local pixel uv
               |
               v
solvePnPRansac
               |
               v
T_CVLocal_WR
               |
OpenCV -> ARCore axis conversion
               v
T_CLocal_WR
               |
compose local ARCore camera pose
               v
T_WLocal_WR
```

Each device therefore owns the transform it needs to render the other device's POI.

## Current quality gate

A raw PnP solve is not enough for `LOCKED`.

A candidate needs:

- at least 10 PnP inliers,
- at least 10 metric correspondences,
- median reprojection error <= 3.5 px,
- image-space coverage >= 0.12,
- aggregate confidence >= 0.22,
- at least **three** consecutive transforms agreeing within 0.40 m translation and 8 degrees rotation,
- final confidence >= 0.28.

Accepted transforms are retained in a seven-solve sliding window. The lock uses the **transform medoid** — the real candidate with minimum aggregate translational/rotational disagreement with the other recent candidates — rather than blindly following the newest solve. This suppresses single-frame pose jitter without averaging rotation matrices into an invalid transform.

The peer also publishes its independent `ready` state. User POI placement is enabled only when both are ready.

These values are intentionally conservative starting points, not immutable production constants. Real S25 Ultra/S26 measurements should drive later tuning.

## RTT cross-check

Wi‑Fi Aware RTT is sampled approximately every 1.2 s when supported.

For every accepted visual transform, V2 computes the phone-to-phone distance implied by that transform. If it disagrees with RTT by more than:

```text
max(1.5 m, 0.35 * range + 2 * RTT_stddev)
```

visual confidence is heavily penalized.

RTT is therefore used to reject a grossly wrong visual hypothesis, not to claim centimeter RF ranging.

## Tap depth

The tap path first queries ARCore depth around the selected pixel using a 5×5 neighborhood. It requires multiple valid samples, uses median depth, and rejects a neighborhood with excessive median absolute deviation. This is less fragile than consuming a single depth pixel.

If dense depth cannot produce a reliable point, ARCore hit testing is attempted as a fallback.

## Runtime after alignment

Once `T_WLocal_WR` is locked, receiving a POI is cheap:

```text
P_WLocal = T_WLocal_WR * P_WR
```

The target remains in the local ARCore world. The receiver can turn away from it; every render frame projects the fixed local-world point through the latest ARCore view/projection matrix.

If the point is outside the viewport or behind the camera, camera-space bearing drives an edge-clamped arrow. When the target enters view it becomes the normal crosshair/label marker.

## Tracking invalidation

A short ARCore tracking wobble does not immediately destroy the lock. A tracking interruption longer than roughly 3 s resets the shared transform and requires a fresh visual lock. This avoids silently using an alignment across an ARCore world reset/relocalization event.

## First field-validation matrix

Start with one controlled baseline before testing difficult geometry:

| Test | Phone baseline | Target range | View overlap | Expected |
| --- | ---: | ---: | --- | --- |
| A | 0.5 m | 2 m | high | easiest initial lock |
| B | 1.0 m | 3 m | high | primary validation |
| C | 2.0 m | 5 m | medium | wider-baseline validation |
| D | 5.0 m | 10 m | medium | stress test |
| E | variable | variable | low/no overlap | should refuse weak fresh lock rather than fake success |

For every accepted lock, measure physical marker error on a clearly defined target and record:

- phone separation,
- target distance,
- inliers,
- reprojection error,
- coverage,
- confidence,
- RTT and RTT standard deviation when available,
- observed marker error,
- lighting / scene notes.

This turns later accuracy tuning into measured engineering rather than threshold guessing.

## Non-goal / physical limit

A single scalar A<->B distance cannot determine a fresh 6-DoF world transform. A brand-new session with no visual overlap, no prior alignment, no shared landmark/map, and only one range measurement is underconstrained. V2 intentionally exposes this as `ALIGNING` instead of manufacturing a false shared origin.
