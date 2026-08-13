# CODEX TODO — BUILD/DEPLOY ONLY

You are an execution-only build/deploy operator for this repository.

## HARD RULES

- DO NOT design, refactor, debug or implement source code.
- DO NOT edit source files.
- DO NOT create commits or push code.
- DO NOT browse the web.
- DO NOT reset/stash/delete user work.
- DO NOT uninstall the Android app or clear app data.
- If a build/test fails, STOP and print the exact failing command and relevant error only.
- Remote `main` is the source of truth once the working tree is clean.

## TARGET BUILD

Expected Android build after the current TODO lands:

- versionName: `1.1.2`
- versionCode: `12`
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## 1. UPDATE

From repository root:

```powershell
git status --porcelain
```

If there are source changes outside generated model files and `server/node_modules/**`, STOP and show them. Do not overwrite them.

If clean enough to update:

```powershell
git fetch origin
git switch main
git pull --ff-only origin main
git log -1 --oneline
```

## 2. SERVER VERIFICATION

```powershell
cd server
npm ci
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
npm test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
npm run check
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cd ..
```

If Docker is already available, building the server image is allowed but optional:

```powershell
docker info
```

If that succeeds:

```powershell
docker build -t spatial-ar-coop-server:1.1.2 ./server
```

Do not deploy/reconfigure the running server automatically.

## 3. ANDROID BUILD

Verify `android/local.properties` exists. Do not print secrets.

```powershell
Select-String -Path android/app/build.gradle.kts -Pattern "versionCode|versionName"
cd android
.\gradlew.bat --no-daemon --stacktrace testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cd ..
```

Verify APK:

```powershell
$apk = "android/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $apk)) { throw "APK WAS NOT CREATED" }
Get-Item $apk
Get-FileHash $apk -Algorithm SHA256
```

Expected: `versionCode = 12`, `versionName = "1.1.2"`.

## 4. WIRELESS ADB DEPLOY

Use only devices that are already paired/connected and shown as `device` by ADB. Do not attempt pairing, network discovery, rooting or USB-mode changes.

```powershell
adb start-server
adb devices -l
```

Collect all authorized devices whose serial contains `:` (ADB-over-network/wireless). If none exist, print `APK READY — NO WIRELESS ADB DEVICE CONNECTED` and stop without modifying anything.

For every authorized wireless device, install the SAME already-built APK without rebuilding:

```powershell
$apk = "android/app/build/outputs/apk/debug/app-debug.apk"
$wireless = adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ -match ":" }
foreach ($serial in $wireless) {
    adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed on $serial" }
}
```

If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` occurs, STOP. Do not uninstall anything.

After install, verify each device:

```powershell
foreach ($serial in $wireless) {
    Write-Host "DEVICE $serial"
    adb -s $serial shell dumpsys package com.sirpaul.spatialarcoop | Select-String "versionCode|versionName"
}
```

Expected installed build: versionCode `12`, versionName `1.1.2`.

## FINAL OUTPUT

Print only:

```text
BUILD SUCCESS
VERSION: 1.1.2
VERSION CODE: 12
GIT: <commit SHA>
APK: android/app/build/outputs/apk/debug/app-debug.apk
SHA256: <hash>
WIRELESS DEVICES INSTALLED: <serials or none>
```
