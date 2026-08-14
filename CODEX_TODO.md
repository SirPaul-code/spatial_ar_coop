# CODEX TODO — BUILD/DEPLOY ONLY

You are an execution-only build/deploy operator. The source implementation is already complete.

## HARD RULES

- DO NOT implement, debug, refactor, edit or format source code.
- DO NOT modify, reset, stash or delete the user's current checkout.
- DO NOT commit or push.
- DO NOT browse the web.
- DO NOT print secrets.
- DO NOT uninstall the Android app or clear app data.
- If a test/build command fails, STOP and report that command plus its relevant error output.
- Build only the latest `origin/main`.
- NEVER build/install an APK with Cloud Anchors unconfigured.

## EXPECTED TARGET

- base Android version: `1.2.1`
- versionCode: `14`
- installed debug package: `com.sirpaul.spatialarcoop.debug`
- installed debug versionName: `1.2.1-debug`
- Cloud Anchors: compiled ENABLED
- server `/ops`: hi-fi free-fly 3D COP

## 1. CLEAN BUILD WORKTREE

From the user's existing repository checkout:

```powershell
$sourceRoot = (git rev-parse --show-toplevel).Trim()
git fetch origin
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$buildRoot = Join-Path $env:TEMP ("spatial_ar_coop_build_" + [guid]::NewGuid().ToString("N"))
git worktree add --detach $buildRoot origin/main
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (Test-Path (Join-Path $sourceRoot "android/local.properties")) {
    Copy-Item (Join-Path $sourceRoot "android/local.properties") (Join-Path $buildRoot "android/local.properties") -Force
}

Set-Location $buildRoot
git log -1 --oneline
Select-String -Path android/app/build.gradle.kts -Pattern "versionCode|versionName"
```

STOP if the checked-out base version is not `1.2.1` / code `14`.

## 2. CLOUD ANCHOR BUILD GATE — MANDATORY

Gradle resolves `ARCORE_API_KEY` from the environment first, then `android/local.properties`.
Never print the value.

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
if (-not $keyConfigured) { throw "ARCORE_API_KEY REQUIRED — DO NOT BUILD OR INSTALL" }
Write-Host "ARCORE_API_KEY: configured (hidden)"
```

## 3. SERVER VERIFY + DEPLOY

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

The Windows `.mjs` wildcard issue is already fixed in source. Do not create a workaround.

If Docker is available, rebuild/redeploy the existing service while preserving all volumes/data:

```powershell
docker info
if ($LASTEXITCODE -eq 0) {
    docker compose build spatial-server
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker compose up -d spatial-server
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker compose ps
}
```

Never use `docker compose down -v`. Never delete server identity, maps or scan data.

If the server is locally exposed on port 8080, verify without changing configuration:

```powershell
try { (Invoke-WebRequest http://127.0.0.1:8080/healthz -UseBasicParsing).StatusCode } catch { Write-Host "Local health check unavailable: $($_.Exception.Message)" }
try { (Invoke-WebRequest http://127.0.0.1:8080/ops -UseBasicParsing).StatusCode } catch { Write-Host "Local /ops check unavailable: $($_.Exception.Message)" }
```

## 4. ANDROID TEST + APK

```powershell
Set-Location (Join-Path $buildRoot "android")
.\gradlew.bat --no-daemon --stacktrace testDebugUnitTest assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Set-Location $buildRoot
```

Verify Cloud Anchors were compiled into the generated build:

```powershell
$generated = Get-ChildItem -Path (Join-Path $buildRoot "android/app/build/generated") -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'BuildConfig\.(java|kt)$' } |
    Select-Object -First 1
if (-not $generated) { throw "Generated BuildConfig not found" }
$cloudFlag = Select-String -Path $generated.FullName -Pattern 'CLOUD_ANCHORS_CONFIGURED\s*=\s*true'
if (-not $cloudFlag) { throw "APK BUILD BLOCKED — CLOUD_ANCHORS_CONFIGURED is not true" }
Write-Host "Cloud Anchors compiled: ENABLED"
```

Verify APK:

```powershell
$apk = Join-Path $buildRoot "android/app/build/outputs/apk/debug/app-debug.apk"
if (!(Test-Path $apk)) { throw "APK WAS NOT CREATED" }
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash
Get-Item $apk
Write-Host "SHA256 $hash"
```

## 5. WIRELESS ADB INSTALL

Use only already-paired/connected wireless ADB devices.

```powershell
adb start-server
adb devices -l
$wireless = adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ -match ":" }
```

If none are connected, print `APK READY — NO WIRELESS ADB DEVICE CONNECTED` and stop successfully.

Install the same verified APK on every connected wireless device:

```powershell
foreach ($serial in $wireless) {
    adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "ADB install failed on $serial" }
}
```

Never uninstall automatically. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, STOP.

Verify:

```powershell
foreach ($serial in $wireless) {
    Write-Host "DEVICE $serial"
    adb -s $serial shell dumpsys package com.sirpaul.spatialarcoop.debug | Select-String "versionCode|versionName"
}
```

Expected: `versionCode=14`, `versionName=1.2.1-debug`.

## FINAL OUTPUT

```text
BUILD/DEPLOY SUCCESS
CLOUD ANCHORS: ENABLED
BASE VERSION: 1.2.1
INSTALLED DEBUG VERSION: 1.2.1-debug
VERSION CODE: 14
GIT: <origin/main SHA>
APK: <absolute path>
SHA256: <hash>
WIRELESS DEVICES INSTALLED: <serials or none>
```
