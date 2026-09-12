# Presentation overlays: clients and live cars

This document describes product-level behavior in the Spatial AR / Anduril-style demo. It is deliberately outside the StableAR SDK contract.

## Client overlay

`ArActivity` publishes each localized phone pose in the shared SITE frame and receives remote client poses over the realtime room. The `Clients: Off / Clients: On` toggle controls only participant rendering.

When enabled, every other recently localized client is rendered with:

- a distance-scaled cyan device target box;
- a phone glyph at the shared camera pose;
- RGB orientation axes when the remote orientation can be projected;
- client role, short device id, distance, tracking state and pose age;
- an edge arrow when the client is outside the current camera view.

The local phone is never rendered as its own remote client. Client poses time out independently of object tracks.

## Cars are live shared observations, not retained markers

Detected/spatialized objects use the normal `SpatialTrack` pipeline. Cars continue to render whether the client overlay is on or off.

For the road-demo use case, a car should be visible only while at least one device still has fresh accepted evidence for it:

- every source publishes its current car tracks normally;
- the server tracks whether each source track's `hitCount` is still advancing;
- repeated motion predictions with the same `hitCount` do not renew visibility;
- a short 650 ms visibility grace prevents one missed detector frame from making boxes flicker;
- once a source no longer advances evidence, that source's car disappears after the grace window;
- if another device is still observing the car, its source track remains visible;
- when the last observing device stops seeing the car, no car track remains to render;
- disconnecting a device immediately removes its source tracks rather than leaving them as last-known cars.

There is no 120-second presentation retention anymore. Historical detections, analytics or mission replay should be stored separately from the live render state so history cannot create stale boxes on a busy road.

## Known presentation follow-up: cross-device fusion

Track identity is still source-scoped (`clientId:trackId`). If two phones see the same physical car at the same time, the raw realtime room can therefore contain two source tracks. For the cleanest multi-phone road presentation, the next product-level step is spatial/velocity association into one fused car entity with a set of active witness client IDs. Rendering would then use the fused entity while its witness set is non-empty.
