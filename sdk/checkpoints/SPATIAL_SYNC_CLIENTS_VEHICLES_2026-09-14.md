# Spatial Sync client + vehicle tracking checkpoint — 2026-09-14

## Current scope

- Active transport is still exactly two phones over Wi-Fi Aware. `SharedRoomState` is N-peer-ready state modeling but is not wired to an N-peer transport yet.
- Remote client pose is now visualized in AR as a phone-sized 3D box and in WORLD as an oriented phone footprint.
- WORLD/CLIENTS controls use system-bar/display-cutout bottom insets.
- Client pose expires after 3 seconds without a fresh remote frame.
- Existing vehicle dedupe already used shared IDs + spatial association + deterministic winner selection.
- New behavior: when the second phone observes the already-reserved vehicle, it contributes that observation under the same canonical ID instead of creating or retaining a second car.
- Vehicle memory TTL is 4 seconds after the last observation from either side; if one phone keeps seeing the vehicle, its repeated same-ID updates keep the logical vehicle alive.
- World visualization merges same-ID local/remote targets to one rendered object.

## Required physical acceptance

1. Two phones reach ALIGNING -> LOCKED.
2. CLIENTS ON: each phone shows the other phone's shared-world box and label.
3. CLIENTS OFF hides peer visualization only.
4. WORLD/CLIENTS sit above the Android gesture/navigation bar.
5. A sees a car first; B later sees the same car: one logical target, same canonical ID.
6. A turns away while B keeps seeing car: target remains.
7. B also turns away: target disappears after bounded grace TTL.
8. Reacquisition after expiry may allocate a new vehicle track ID.

## Build gate

The applying workflow must pass `:app:testDebugUnitTest :app:assembleDebug` and verify the packaged XFeat model before committing these product-source changes.
