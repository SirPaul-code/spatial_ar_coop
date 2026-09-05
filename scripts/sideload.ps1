param(
    [string]$Serial = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $Apk)) { throw "APK not found. Run scripts\build-android.ps1 first." }
$adb = Get-Command adb -ErrorAction Stop
if ($Serial) {
    & $adb.Source -s $Serial install -r $Apk
    exit $LASTEXITCODE
}
$devices = @(& $adb.Source devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\t")[0] })
if ($devices.Count -eq 0) { throw "No adb devices found." }
foreach ($device in $devices) {
    Write-Host "Installing on $device"
    & $adb.Source -s $device install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed on $device" }
}
