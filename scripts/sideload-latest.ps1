param(
    [string[]]$Serial = @(),
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$Package = "com.sirpaul.spatialnomap"
$DownloadUrl = "https://github.com/SirPaul-code/spatial_ar_coop/releases/download/latest-dev/SpatialSync-latest-dev.apk"
$Apk = Join-Path $env:TEMP "SpatialSync-latest-dev.apk"

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) {
    $Adb = $adbCommand.Source
} else {
    $Adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (!(Test-Path $Adb)) {
        throw "adb.exe not found. Expected Android platform-tools at $Adb"
    }
}

Write-Host "Downloading latest green Spatial Sync APK..." -ForegroundColor Cyan
Invoke-WebRequest -UseBasicParsing -Uri $DownloadUrl -OutFile $Apk
if (!(Test-Path $Apk) -or (Get-Item $Apk).Length -lt 1000000) {
    throw "Downloaded APK is missing or unexpectedly small: $Apk"
}

if ($Serial.Count -gt 0) {
    $devices = @($Serial)
} else {
    $devices = @(
        & $Adb devices |
        Select-String "^\S+\s+device$" |
        ForEach-Object { ($_.Line -split "\s+")[0] }
    )
    if ($devices.Count -ne 2) {
        Write-Host "" 
        & $Adb devices -l
        throw "Expected exactly 2 connected ADB devices, found $($devices.Count). Connect both wireless-debugging endpoints first, or pass -Serial explicitly."
    }
}

Write-Host ""
Write-Host "Installing to $($devices.Count) device(s)..." -ForegroundColor Cyan

foreach ($device in $devices) {
    $model = (& $Adb -s $device shell getprop ro.product.model).Trim()
    Write-Host ""
    Write-Host "[$model] $device" -ForegroundColor Yellow

    & $Adb -s $device install -r -t $Apk
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed on $model ($device)"
    }

    & $Adb -s $device shell am force-stop $Package | Out-Null
    if (!$NoLaunch) {
        & $Adb -s $device shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    }

    $installed = (& $Adb -s $device shell pm path $Package) -join "`n"
    if ($installed -notmatch "package:") {
        throw "Package verification failed on $model ($device)"
    }
    Write-Host "OK - installed and verified" -ForegroundColor Green
}

Write-Host ""
Write-Host "Spatial Sync latest-dev is installed on all requested phones." -ForegroundColor Green
Write-Host "APK: $Apk"
