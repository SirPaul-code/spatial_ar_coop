$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $Root "android")
try {
    .\gradlew.bat --no-daemon clean assembleDebug
    $apk = Join-Path $PWD "app\build\outputs\apk\debug\app-debug.apk"
    if (!(Test-Path $apk)) { throw "APK was not produced: $apk" }
    Write-Host "APK: $apk"
} finally {
    Pop-Location
}
