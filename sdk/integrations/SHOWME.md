# ShowMe integration boundary

The standalone SDK is implemented and has a single-device demo. The existing
ShowMe module is intentionally untouched in this checkpoint, including on this
branch. Other branches are completely unaffected. This page specifies the adapter
handoff, not a claim that ShowMe has already been migrated.

Keep the existing WebRTC frame identity footer and full epoch checks. In
`publishVideo`, capture/store `CameraSample` alongside the same submitted video ID.
Its internal `FrameRef.id` is NOT the video ID: keep an explicit mapping. On freeze,
marshal `adapter.freeze(sample.ref.id)` to the owner/GL thread; retain the immutable
sample under that lease. Do not call adapter/native anchors on a server thread.

Convert browser normalized vertices using the exact content crop/rotation, then
call `adapter.place(sample,pixel)` on the GL thread. That path creates a nearby
surface anchor using current retained-frame transport, not stale numeric world
coordinates. Pass pixels, timestamps and geometry only; the browser does not need
AR or its own world. A local touch uses the exact same API without networking.

Start migration with point annotations. Existing multi-vertex strokes are not a
collection of unrelated pins: preserve shared patch geometry and add a dedicated
surface-stroke attachment before switching freehand/circle tools. Do not run the
old SurfaceTargetResolver/EdgeSnapRefiner concurrently with the SDK's corrections.
The new optional vision worker owns its cached root and does not train from
rejected proposals. Draw in the camera GL pass. Preserve all auth/lease/expiry
checks of the host protocol.

APK/CI changes from this research must not publish showme-latest or latest-dev.
A migration to any active product branch requires the owner's separate instruction.
