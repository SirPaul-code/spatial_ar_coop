# ShowMe release acceptance

Automated success does not prove AR placement on a phone. Do not promote the local preview to a production release before measuring these cases.

## Physical two-device acceptance (pending)

- Fresh ShowMe install, permissions granted/denied, ARCore install-required case.
- Camera phone and browser on normal Wi-Fi; repeat on phone hotspot. Confirm guest-network isolation gives a clear connection error.
- Full share-sheet link and QR both join. No camera visible before owner approval. Decline, helper disconnect/reconnect, second helper, End and expired link.
- Owner enters background through share sheet: no new video/audio in background; Resume continues without deleting marks.
- Pin a textured corner/terminal at 0.5 m, 1 m and 2 m. Move camera 0.5 m sideways and rotate. Measure physical offset; never infer a centimeter guarantee from a screenshot.
- Freeze on helper, MOVE THE CAMERA, then click the saved image. Confirm the point is on the original real surface, not the current screen ray.
- Draw arrows, circles and strokes; repeat with portrait browser, landscape browser and wide desktop letterboxing.
- Textureless, shiny, dark and depth-discontinuous scenes: uncertain placement should be rejected, not fabricated.
- Keep a mark visible from different angles; ensure surface correction stays bounded and does not drift onto similar neighboring texture.
- Manual Freeze older than 45 s is rejected/resumed. Oversized/duplicate draw messages do not leak anchors.
- Undo/remove/clear, temporary-pointer expiry, retain marks after helper leaves, clear them at End.
- Native microphone disabled by default. Owner microphone + browser listen test. Confirm HTTP helper microphone is explicitly unavailable, not silently broken.
- Snapshot and silent browser recording create usable files, no capture before a real received frame.
- Thermal/memory test for 20 minutes, repeated Start/End 10 times, capture/AR tracking after interruptions.

## Production blockers

HTTPS/TLS and authenticated remote session service; TURN and hardware media transport; accounts/consent/privacy policy; private production signing; abuse limits and security review; calibrated spatial/latency reliability metrics; device compatibility matrix; accessibility and Play policy review. Commercial licensing is not implemented and must not be presented as enforced.
