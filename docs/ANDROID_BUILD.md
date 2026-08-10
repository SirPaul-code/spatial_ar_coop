# Build the Android app

The Android client is a native Kotlin/ARCore project under `android/`. Unity is not required.

## Requirements

- JDK 17
- Android SDK with API 36/build tools available
- A device supported by ARCore for the real AR experience
- Internet access during the first build so Gradle dependencies and the pinned EfficientDet-Lite0 model can be downloaded

The model download is verified by an expected byte length and SHA-256 before it is moved into app assets.

## Configure `local.properties`

Create `android/local.properties` and do not commit it:

```properties
sdk.dir=/absolute/path/to/Android/sdk
ARCORE_API_KEY=UNCONFIGURED
DEFAULT_SERVER_URL=https://your-server.your-tailnet.ts.net
DEFAULT_API_TOKEN=
```

`DEFAULT_API_TOKEN` is an optional owner bootstrap value. It is not needed for a participant who joins a `spatialar://join` invite. For a public build, leaving it empty is recommended.

### Cloud Anchors

For real cross-device Cloud Anchor host/resolve, set `ARCORE_API_KEY` to the Google Cloud key for your ARCore API project:

```properties
ARCORE_API_KEY=your_api_key
```

For a debug build, remember that the package ID is:

```text
com.sirpaul.spatialarcoop.debug
```

The release package ID is:

```text
com.sirpaul.spatialarcoop
```

If the API key is `UNCONFIGURED`, the APK still builds and the app retains its manual shared-origin development fallback, but Cloud Anchor host/resolve is disabled.

## Build and test a debug APK

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release signing

The project supports optional release signing without committing a keystore or credentials. Supply all four settings through environment variables or `android/local.properties`:

```text
RELEASE_STORE_FILE
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Example `local.properties` entries:

```properties
RELEASE_STORE_FILE=/absolute/private/path/spatial-ar-release.jks
RELEASE_STORE_PASSWORD=change-me
RELEASE_KEY_ALIAS=spatial-ar
RELEASE_KEY_PASSWORD=change-me
```

Generate a keystore with the JDK `keytool` if you do not already have one:

```bash
keytool -genkeypair \
  -keystore spatial-ar-release.jks \
  -alias spatial-ar \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000
```

Keep the keystore and passwords outside the repository and back them up securely. Losing the signing key prevents publishing an update under the same Android signing identity.

Build the release APK:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest assembleRelease
```

Output:

```text
android/app/build/outputs/apk/release/app-release.apk
```

If the four signing values are not configured, Gradle can still compile the release variant but it will not produce a distributable signed identity suitable for normal update workflows. The repository CI therefore treats the debug APK as the reproducible public build artifact unless release-signing secrets are deliberately configured by a distributor.

## Verify an APK

With Android build-tools on `PATH`:

```bash
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

For a release APK, also inspect the certificate that signed it:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions build

The public `ci.yml` workflow runs the server suite and:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

with `ARCORE_API_KEY=UNCONFIGURED`. Successful runs upload the debug APK as a workflow artifact. This proves that the public source tree compiles without requiring the repository owner to expose a Google API key.

A fork owner can add an ARCore API key to their own build environment for Cloud Anchors. Do not put the key directly into a committed Gradle or manifest file.

## Configure the app after installation

### Server owner

Open **Server owner & diagnostics**, enter:

- your server HTTPS URL,
- the server's `sar_admin_...` owner token.

Use **Test & sync**. The app calls `/api/v1/info`, displays the stable server identity, and then lists the maps authorized by the admin token.

### Participant

Do not configure the owner's admin token. Open a `spatialar://join?...` invite or tap **Join a shared place** and paste it. The app verifies the invite's `serverId`, fetches exactly its `mapId` with the invite's `sar_map_...` key, and saves that map connection locally.

## Troubleshooting

### Gradle cannot find the SDK

Check `sdk.dir` in `android/local.properties` or `ANDROID_SDK_ROOT`.

### Model download fails

The build intentionally fails rather than packaging an unverified model. Check outbound HTTPS access to Google's MediaPipe model storage and retry.

### Cloud Anchor controls fall back to manual alignment

Check that `ARCORE_API_KEY` was set at **build time** and that the key's Android restrictions match the package ID and signing certificate of the installed APK.

### App can open the server but cannot open a map

A valid `serverId` is not an authorization credential. The map also needs its current `sar_map_...` key. If the owner rotated the map key, import the new invite.
