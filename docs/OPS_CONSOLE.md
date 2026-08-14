# Spatial Ops Console

`/ops` is a second operator view layered on top of the existing Spatial AR server. The classic `/` World Debugger remains unchanged.

## What it demonstrates

- multiple phones acting as spatial sensors in one Cloud Anchor-aligned site frame;
- server-side fusion of same-class observations into stable entities;
- per-entity provenance: source phone, source track, confidence, uncertainty, spatial method and depth confidence;
- multi-sensor confirmation and agreement scoring;
- sensor/client pose health in the same common operating picture;
- sparse scan point cloud and Cloud Anchor placement behind the live entity layer;
- a rolling mission buffer with timeline scrubbing and replay;
- isometric and top-down operator views.

The fusion layer is deliberately observation-centric. It does not control vehicles, effectors, or external systems; it only turns the existing spatial sensor feed into a shared operational picture.

## Run

Start the normal server, then open:

```text
http://SERVER:PORT/ops
```

Authenticate with the same owner token used by the existing admin dashboard and select a map.

For the best demo, connect two or more localized Android clients to the same map and point them at the same person/car. A fused entity changes from `SINGLE_SENSOR` to `MULTI_SENSOR` when spatially compatible observations from distinct clients agree.

## Fusion behaviour

The server groups observations only when:

- labels match;
- observations come from different source clients;
- their 3D positions are compatible with a class/uncertainty-aware spatial gate.

Fused position, velocity, extent and uncertainty are uncertainty-weighted. Fused IDs are associated frame-to-frame so the operator view is stable while raw phone track IDs come and go.

## Replay

The server samples the current common operating picture every 500 ms and keeps a bounded in-memory rolling buffer (default ~10 minutes / 1200 frames). This is a demo/debug buffer; it is intentionally not persisted to disk.
