# CODEX TODO — BUILD/DEPLOY ONLY

You are an execution-only build/deploy operator. Do not implement, debug, refactor or edit project source code.

## HARD RULES

- DO NOT modify, reset, stash or delete the user's current working tree.
- DO NOT write source-code fixes.
- DO NOT commit or push.
- DO NOT browse the web.
- DO NOT uninstall the Android app or clear app data.
- DO NOT print API keys, tokens, passwords or other secrets.
- If any test/build fails, STOP and print only the failing command plus relevant error output.
- Build only the latest `origin/main`.
- NEVER build or install an Android APK if Cloud Anchors would be compiled as unconfigured.

## TARGET

- Gradle base `versionName = "1.1.2"`
- Android `versionCode = 12`
- Debug APK installed package: `com.sirpaul.spatialarcoop.debug`
- Installed debug versionName: `1.1.2-debug`
- Cloud Anchors MUST be configured in the APK.
- Build debug APK and install it with `adb install -r` to every already-connected wireless ADB device.

## 1. CREATE A CLEAN BUILD WORKTREE

Leave the user's current checkout untouched.

```powershell
$sourceRoot = (git rev-parse --show-toplevel).Trim()
git fetch origin
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$buildRoot = Join-Path $env:TEMP ("spatial_ar_coop_build_" + [guid]::NewGuid().ToString("N"))
git worktree add --detach $buildRoot origin/main
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Copy local Android build configuration if present. Never print its contents:

```powershell
if (Test-Path (Join-Path $sourceRoot "android/local.properties")) {
    Copy-Item (Join-Path $sourceRoot "android/local.properties") (Join-Path $buildRoot "android/local.properties")
}
Set-Location $buildRoot
git log -1 --oneline
Select-String -Path android/app/build.gradle.kts -Pattern "versionCode|versionName"
```

Expected Gradle base version is code `12`, name `1.1.2`. If not, STOP.

## 2. CLOUD ANCHOR BUILD GATE — MANDATORY

The Gradle project resolves `ARCORE_API_KEY` from the process environment first, then from `android/local.properties`. A missing key compiles the APK with Cloud Anchors DISABLED. That APK MUST NOT be built or installed.

Check configuration without revealing the key:

```powershell
$keyConfigured = -not [string]::IsNullOrWhiteSpace($env:ARCORE_API_KEY)

if (-not $keyConfigured) {
    $lp = Join-Path $buildRoot "android/local.properties"
    if (Test-Path $lp) {
        $match = Get-Content $lp | Where-Object { $_ -match '^\s*ARCORE_API_KEY\s*=\s*.+$' } | Select-Object -First 1
        if ($match) {
            $value = (($match -split '=',2)[1]).Trim()
            $keyConfigured = -not [string]::IsNullOrWhiteSpace($value) -and $value -ne 'UNCONFIGURED'
            $value = $null
        }
    }
}

if (-not $keyConfigured) {
    throw "ARCORE_API_KEY REQUIRED — Cloud Anchors would be disabled. DO NOT BUILD OR INSTALL APK."
}

Write-Host "ARCORE_API_KEY: configured (value hidden)"
```

Do not invent, replace, rotate or print the key. If this gate fails, STOP and report only that the existing build environment does not contain the required key.

## 3. SERVER VERIFY/BUILD

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

The Windows `*.mjs` shell glob problem is already fixed in `main`. Do not create a workaround.

If Docker is already available, build/redeploy the existing server without deleting volumes:

```powershell
docker info
if ($LASTEXITCODE -eq 0) {
    docker compose build spatial-server
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker compose up -d spatial-server
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
```

Never run `docker compose down -v` and never delete server/map data.

## 4. BUILD APK

```powershell
Set-Location (Join-Path $buildRoot "android")
.\gradlew.bat --no-daemon --stacktrace testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Set-Location $buildRoot
```

Before installing, verify the generated BuildConfig explicitly says Cloud Anchors are enabled, without exposing any secret:

```powershell
$generated = Get-ChildItem -Path (Join-Path $buildRoot "android/app/build/generated") -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'BuildConfig\.(java|kt)$' } |
    Select-Object -First 1

if (-not $generated) { throw "Generated BuildConfig not found" }
$cloudFlag = Select-String -Path $generated.FullName -Pattern 'CLOUD_ANCHORS_CONFIGURED\s*=\s*true'
if (-not $cloudFlag) { throw "APK BUILD BLOCKED — CLOUD_ANCHORS_CONFIGURED is not true" }
Write-Host "Cloud Anchors compiled: ENABLED"
```

Then verify APK:

```powershell
$apk = Join-Path $buildRoot "android/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $apk)) { throw "APK WAS NOT CREATED" }
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash
Get-Item $apk
Write-Host "SHA256 $hash"
```

## 5. WIRELESS ADB INSTALL

Use ONLY devices already paired/connected to ADB. Do not perform rooting or network reconfiguration.

```powershell
adb start-server
adb devices -l
$wireless = adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ -match ":" }
```

If `$wireless.Count -eq 0`, print `APK READY — NO WIRELESS ADB DEVICE CONNECTED` and stop successfully.

Install the SAME verified APK on every available wireless device:

```powershell
foreach ($serial in $wireless) {
    adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed on $serial" }
}
```

If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` occurs, STOP. Never uninstall automatically.

Verify installed package:

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
CLOUD ANCHORS: ENABLED
BASE VERSION: 1.1.2
INSTALLED DEBUG VERSION: 1.1.2-debug
VERSION CODE: 12
GIT: <origin/main commit SHA>
APK: <absolute APK path>
SHA256: <hash>
WIRELESS DEVICES INSTALLED: <serials or none>
```
