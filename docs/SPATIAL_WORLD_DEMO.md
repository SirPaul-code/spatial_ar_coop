# Spatial Sync 0.7 — demo runbook

This build is designed for a two-phone live demonstration of infrastructure-free shared AR plus shared perception. Both phones must run the same V6 APK.

## What to show

1. Launch the app on both phones and wait for local `TRACKING`.
2. Create/join the same room.
3. For the first ~3 seconds point both cameras at the same textured region. A small sideways motion is ideal. The app runs a synchronized high-rate acquisition burst and the host is the preferred canonical solver.
4. Wait for `LOCKED`.
5. Tap several physical surfaces on either phone. Manual POIs are additive and remain visible to the owner and peer.
6. Point one phone at a car. Vehicle detection is automatic; the car is a dynamic world-space track and is shared without tapping.
7. Walk/rotate either phone while observing that POIs remain attached and car motion is smoothed.
8. Tap `WORLD` at the bottom of the screen.

## WORLD view

`WORLD` is a screen-recordable shared-world visualization. It shows:

- the accumulated metric 3D voxel map built from both phones,
- the latest high-density local and transformed peer point clouds,
- both phone poses as moving actors,
- persistent manual POIs,
- dynamic vehicle tracks,
- actor/vehicle motion trails,
- auto-orbit animation,
- drag-to-orbit and pinch-to-zoom controls,
- current alignment state and gate telemetry.

The 3D map is not a pre-scanned map and is not a Cloud Anchor. It is built live from the metric geometry already exchanged by the phones after their shared frame is verified.

## Alignment behavior

The host is preferred as the canonical solver. The joiner concentrates on capture/streaming for the first 7 seconds and adopts a strong host transform after local rigid/gravity/range validation. If the host cannot solve, the joiner automatically enables its own recovery solve.

Registration frames are tagged with a common acquisition-burst id and sequence. Equal or adjacent burst sequences are strongly preferred by the live frame-pair selector. The burst keeps one keyframe per sequence even if the phones are nearly stationary.

If a previous verified landmark checkpoint exists, the host first attempts infrastructure-free relocalization. It matches the current host and peer worlds back to their old visual/metric keyframes and transports the old room transform into the new ARCore origins. Failure is safe: normal fresh registration continues.

## Flight recorder

Every physical connection gets a rolling session directory under the app external-files area (`alignment_sessions`). The most recent sessions contain:

- `events.ndjson` — wire/registration timeline,
- `quality.ndjson` — exact alignment quality/gate snapshots,
- `frames.spv6` — full replayable registration frames,
- `shared-world.voxels` — accumulated metric world map,
- `README.txt` — session format reminder.

`RecordedAlignmentReplay` can decode `frames.spv6` and prioritizes matching acquisition-burst frame sequences for offline solver regression.

When a device gets stuck in `ALIGNING`, do not loosen thresholds blindly. Pull the newest session and inspect `quality.ndjson` plus the recorded frames.

## Protocol compatibility

Wire protocol is **V6**. It carries up to 8000 metric supports per registration frame, burst metadata, and a dedicated dynamic-target wire type for automatic vehicle observations. V5 and older APKs are intentionally incompatible; update both phones together.

## Current multi-peer boundary

The canonical room-state model (`SharedRoomState`) already supports arbitrary peers and MANUAL / VEHICLE / PERSON / GENERIC canonical targets. The current Wi-Fi Aware runtime transport still owns one active physical peer socket per app instance, so today's APK demonstration is two-phone. Do not present physical N-phone fan-out as already enabled until `WifiAwarePeerTransport` is replaced/refactored into a multi-session transport hub.
