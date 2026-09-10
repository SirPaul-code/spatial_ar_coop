# ShowMe WebRTC validation checkpoint

WebRTC source implementation commit: `e38c9d46f535494529261cc3d7bb0a1459e4f339`.

The implementation replaces live JPEG polling with an actual WebRTC camera video track. The native GPU capture path is configured for 30 fps, uses the existing ARCore camera texture (no second camera session), and sends a protected frame-identity footer that the browser decodes and crops from presentation. Frozen annotations reconstruct the physical point from the historical depth snapshot associated with that video frame, not a newer camera pose.

Local checks completed before source integration:
- 13 Node geometry/frame-stamp tests passed.
- JavaScript syntax checks passed.
- Pure Kotlin frame-stamp source compiled.

GitHub CI must validate the complete Android build, signatures and real browser-to-browser WebRTC encode/decode integration before APK publication is called ready. The browser producer uses synthetic camera/depth fixtures. It does not measure Android hardware encoder performance, native AR placement error, hotspot reliability or real phone audio routing.

The one-time source transport/integration workflows have been removed after applying the checksum-verified reviewed source changes. Normal ShowMe build/release automation remains in `.github/workflows/showme-ci.yml`.

Release channel remains `showme-latest`; original Spatial Sync runtime and `latest-dev` are unchanged. Trusted HTTPS is still required for helper microphone capture. No Internet signaling/TURN deployment was made.
