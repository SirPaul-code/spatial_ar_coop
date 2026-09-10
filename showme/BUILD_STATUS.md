# ShowMe delivery checkpoint

Runtime commit: `46cde39bd60826d2965b7bb4801b743cee83c9a7`.
Branch: `showme/local-assist`.
Documentation-only commits after this runtime do not change the APK implementation.

## Published Android prerelease

Verified on 2026-09-10: GitHub Actions run `34416568515`, attempt **2**, job `102783849689`, completed successfully, including publication.

- Original Spatial Sync runtime protection check: passed.
- Browser protocol tests and Playwright browser tests: passed. Browser UI camera input is mocked; these are not physical AR acceptance tests.
- Android unit tests, compilation, debug/release lint and debug/release APK packaging: passed.
- APK signature verification: passed.
- Artifact upload: passed.
- Separate ShowMe GitHub prerelease publication: passed.

Release tag: `showme-local-2-2`.
Release ID: `386082046`.
Release target: `46cde39bd60826d2965b7bb4801b743cee83c9a7`.

Release page:
https://github.com/SirPaul-code/spatial_ar_coop/releases/tag/showme-local-2-2

Signed release APK:
https://github.com/SirPaul-code/spatial_ar_coop/releases/download/showme-local-2-2/ShowMe-local-release.apk

Release APK asset ID: `554504961`.
Size: `189468155` bytes.
SHA-256:
`7ee78887a754cc7e9421ff7bfb24f11fb36e165607235f396830cf9c22108273`

The release also contains `ShowMe-local-debug.apk`, `ShowMe-SHA256SUMS.txt`, `ShowMe-signature.txt`, and `ShowMe-source.zip`.

App ID: `com.sirpaul.showme`; it installs alongside Spatial Sync. Minimum SDK: 33. Version name: `0.1.0-local.2`; this second build attempt has version code `10022`. Signing uses the existing public development sideload certificate, NOT a private production/Play signing identity.

## Previous failed publication is no longer a blocker

Attempt 1 built and signed the app successfully, but failed during GitHub Release creation. The retrieved job log reports HTTP 500; an earlier version of this handoff described HTTP 403/missing workflow permissions. Do not treat that earlier diagnosis as an established current blocker: attempt 2 succeeded without code, workflow, or permission changes. Do not claim that `showme-local-2-1` exists.

The older attempt-1 artifact `10129422707` contains an APK with SHA-256 `87d999a6c8864e0a20f7624834e6251ce372c2c06d14de47a667613c670794ba`. It is the same runtime source but a different build/version code; prefer the published attempt-2 APK above.

No changes were made to `android/`, `.github/workflows/ci.yml`, Spatial Sync `latest-dev`, or the outdoor branch for this rebuild/publication.

## Remaining validation

No physical two-device AR placement test has been performed by the agent. Follow `RELEASE_CHECKLIST.md`, especially freezing a browser frame, moving the camera, then placing a mark on that old image. A successful build and synthetic geometry tests are not a centimeter-accuracy measurement.

Current scope is local Wi-Fi/hotspot camera streaming and AR annotations with owner approval. The helper opens the invite in a browser and does not install an app. A shared link still requires LAN/hotspot reachability; it is not an Internet session.

Ordinary LAN HTTP helper microphone remains restricted by browser secure-context requirements; native-to-browser listen-only audio is implemented but still needs a device/browser test. Use a trusted WPA-protected LAN/hotspot and do not expose the embedded HTTP endpoint publicly. Internet sessions, accounts, billing, licences, TLS deployment and TURN are not deployed.
