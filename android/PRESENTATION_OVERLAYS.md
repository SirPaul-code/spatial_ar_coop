# Presentation overlays: clients and retained cars

This document describes product-level behavior in the Spatial AR / Anduril-style demo. It is deliberately outside the StableAR SDK contract.

## Client overlay

`ArActivity` already publishes each localized phone pose in the shared SITE frame and receives remote client poses over the realtime room. The existing `Clients: Off / Clients: On` toggle controls only participant rendering.

When enabled, every other recently localized client is rendered with:

- a distance-scaled cyan device target box;
- a phone glyph at the shared camera pose;
- RGB orientation axes when the remote orientation can be projected;
- client role, short device id, distance, tracking state and pose age;
- an edge arrow when the client is outside the current camera view.

The local phone is never rendered as its own remote client. Client poses time out independently of object tracks.

## Cars are not part of the client toggle

Detected/spatialized objects use the normal `SpatialTrack` pipeline. Cars therefore continue to render whether the client overlay is on or off.

Cars additionally have bounded last-known memory for the presentation/live-ops view:

- the realtime server does not delete a car immediately when a source sends a replace-source batch that no longer contains it;
- the last server-observed car state is retained for 120 seconds;
- late-joining clients receive retained cars in the normal welcome snapshot;
- Android keeps remote cars for the same 120-second window;
- motion extrapolation remains bounded to 450 ms, so a lost car freezes at its last predicted location instead of continuing indefinitely;
- the overlay changes the age display to `last Ns` once a car is older than one second.

Other detection classes preserve their short realtime expiry semantics.

This is bounded in-memory live-state retention. It survives detector loss and source-client disconnects while the server is running, but it is not yet durable across a server process restart. If durable mission-history storage is required, persist retained object records in the map store as a separate product feature rather than adding it to StableAR.
