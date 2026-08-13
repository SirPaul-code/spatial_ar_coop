# CODEX TODO — BUILD/DEPLOY ONLY

You are an execution-only build/deploy operator. Do not implement, debug, refactor or edit project source code.

## HARD RULES

- DO NOT modify, reset, stash or delete the user's current working tree.
- DO NOT write source-code fixes.
- DO NOT commit or push.
- DO NOT browse the web.
- DO NOT uninstall the Android app or clear app data.
- If any test/build fails, STOP and print only the failing command plus relevant error output.
- Build only the latest `origin/main`.

## TARGET

- Gradle base `versionName = "1.1.2"`
- Android `versionCode = 12`
- Debug APK installed package: `com.sirpaul.spatialarcoop.debug`
- Installed debug versionName: `1.1.2-debug`
- Build debug APK and install it with `adb install -r` to every already-connected wireless ADB device.

## 1. CREATE A CLEAN BUILD WORKTREE

The current checkout may contain old local 1.1.1 source changes. LEAVE THEM COMPLETELY UNTOUCHED.

From the existing repository:

```powershell
$sourceRoot = (git rev-parse --show-toplevel).Trim()
git fetch origin
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$buildRoot = Join-Path $env:TEMP ("spatial_ar_coop_build_" + [guid]::NewGuid().ToString("N"))
git worktree add --detach $buildRoot origin/main
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Copy only local Android build configuration if present. Do not print its contents:

```powershell
if (Test-Path (Join-Path $sourceRoot "android/local.properties")) {
    Copy-Item (Join-Path $sourceRoot "android/local.properties") (Join-Path $buildRoot "android/local.properties")
}
Set-Location $buildRoot
git log -1 --oneline
Select-String -Path android/app/build.gradle.kts -Pattern "versionCode|versionName"
```

Expected Gradle base version is code `12`, name `1.1.2`. If not, STOP.

## 2. SERVER VERIFY/BUILD

```powershell
Set-Location (Join-Path $buildRoot "server")
npm ci
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
npm test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
npm run check
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Set-Location $buildRoot
```

If Docker is already available, you MAY build the image, but DO NOT deploy or reconfigure the running server:

```powershell
docker info
if ($LASTEXITCODE -eq 0) {
    docker build -t spatial-ar-coop-server:1.1.2 ./server
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

## 3. BUILD APK

```powershell
Set-Location (Join-Path $buildRoot "android")
.\gradlew.bat --no-daemon --stacktrace testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Set-Location $buildRoot

$apk = Join-Path $buildRoot "android/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $apk)) { throw "APK WAS NOT CREATED" }
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash
Get-Item $apk
Write-Host "SHA256 $hash"
```

## 4. WIRELESS ADB INSTALL

Use ONLY devices already paired/connected to ADB. Do not perform pairing, discovery, rooting, network reconfiguration or USB-mode changes.

```powershell
adb start-server
adb devices -l
$wireless = adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ -match ":" }
```

If `$wireless.Count -eq 0`, print `APK READY — NO WIRELESS ADB DEVICE CONNECTED` and stop successfully.

Otherwise install the SAME built APK on every available wireless device without rebuilding:

```powershell
foreach ($serial in $wireless) {
    adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed on $serial" }
}
```

If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` occurs, STOP. Never uninstall automatically.

Verify installed debug package on every wireless device:

```powershell
foreach ($serial in $wireless) {
    Write-Host "DEVICE $serial"
    adb -s $serial shell dumpsys package com.sirpaul.spatialarcoop.debug | Select-String "versionCode|versionName"
}
```

Expected installed debug build: `versionCode=12`, `versionName=1.1.2-debug`.

## FINAL OUTPUT

Print only:

```text
BUILD SUCCESS
BASE VERSION: 1.1.2
INSTALLED DEBUG VERSION: 1.1.2-debug
VERSION CODE: 12
GIT: <origin/main commit SHA>
APK: <absolute APK path>
SHA256: <hash>
WIRELESS DEVICES INSTALLED: <serials or none>
```
