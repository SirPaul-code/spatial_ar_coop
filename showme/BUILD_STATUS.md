# ShowMe delivery checkpoint

Runtime commit: `46cde39bd60826d2965b7bb4801b743cee83c9a7`.
Branch: `showme/local-assist`.
The documentation-only commit containing this file does not alter the APK runtime.

## Verified build, not yet a published GitHub Release

GitHub Actions run `34416568515`, job `102682658872`, workflow `showme-local-ci` run 2 / attempt 1:

- Original Spatial Sync runtime protection check: passed.
- Node browser protocol/geometry tests: 10 passed.
- Playwright browser tests: 6 passed across desktop and mobile layouts. Camera input is explicitly mocked for these UI tests.
- JVM tests: 17 passed (5 geometry, 9 protocol/policy, 3 real loopback HTTP/WebSocket integration tests).
- Android compilation, debug and release APKs: passed.
- Android debug/release lint: 0 errors; app warnings remain.
- APK signature verification: passed.
- Artifact upload: passed.
- GitHub Release publication: FAILED with HTTP 403, `Resource not accessible by integration`; GitHub's response mentions the workflow token's missing `workflows` permission.

Do NOT describe the entire workflow as green and do NOT claim `showme-local-2-1` was published. A release permission/tag-publication fix is still needed. Do not alter the original `latest-dev` or other branches to work around this.

## Deliverable artifact

Run artifact `10129422707`, name `showme-local-apks`, contains:

- `ShowMe-local-release.apk` (189468155 bytes)
- `ShowMe-local-debug.apk`
- `ShowMe-SHA256SUMS.txt`
- `ShowMe-signature.txt`
- `ShowMe-source.zip` (includes the required original Android source dependencies and wrapper)

The signed release APK was extracted from this artifact and checked against its SHA-256:

`87d999a6c8864e0a20f7624834e6251ce372c2c06d14de47a667613c670794ba`

App ID: `com.sirpaul.showme`; it installs alongside Spatial Sync. Signing is the existing public development sideload certificate, NOT a private production/Play signing identity.

## Remaining validation

No physical two-device AR placement test has been performed by the agent. Follow `RELEASE_CHECKLIST.md`, especially freezing a browser frame, moving the camera, then placing a mark on that old image. A successful build and synthetic geometry tests are not a centimeter-accuracy measurement.

Current scope is local Wi-Fi/hotspot camera streaming and AR annotations with owner approval. Ordinary LAN HTTP helper microphone remains restricted by browser secure-context requirements; native-to-browser listen-only audio is implemented but still needs the device/browser test. Internet sessions, accounts, billing, licences, TLS deployment and TURN are not deployed.
