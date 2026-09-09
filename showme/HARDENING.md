# Local preview hardening

The first CI run compiled the shared spatial library and app and passed 14 JVM tests, 10 browser protocol tests and 6 responsive-browser tests. It then correctly blocked publication on Android lint's `PermissionImpliesUnsupportedChromeOsHardware` error. The manifest now declares camera capabilities explicitly rather than suppressing lint, includes the audio-routing permission, and excludes app data from backup/device transfer.

The next build adds three real loopback TCP/WebSocket tests: assets are whitelisted/no-store, bad tokens and foreign origins are rejected, and camera frames cannot be received until owner approval. This is stronger evidence than a mocked browser transport, but it is still not a physical Android/browser AR test.

Owner-approval writes now run on the bounded writer executor, never the Android UI thread. READY and permission to receive video share the same writer lock. Frame metadata limits agree on both ends (512 KiB JSON, <=48 annotations, bounded overall envelope). Client control-frame rates are checked before processing ping/pong or commands.

Native content applies explicit system-bar/display-cutout insets for SDK 36. Build SHA is visible. The invite dialog reminds the owner to return and press Resume after switching to Messenger or another sharing application. Resuming is driven by the render thread rather than claiming the camera is ready from a button callback.

The source archive includes the unchanged original Android sources and wrapper required to build the separate ShowMe project. The public development signing key is deliberately not a production publishing key.

Outstanding checks and deployment boundaries remain in `RELEASE_CHECKLIST.md`; no physical performance or accuracy guarantee is implied by green CI.
