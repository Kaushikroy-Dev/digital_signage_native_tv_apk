# End-to-end offline playback test for Android TV player (emulator or device)
param(
    [string]$Device = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$proj = Split-Path $PSScriptRoot -Parent
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "C:\ab\sdk\platform-tools\adb.exe" }
$apk = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
$pkg = "com.digitalsignage.player"

function Invoke-Adb([string[]]$AdbArgs) {
    & $adb -s $Device @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($AdbArgs -join ' ')" }
}

Write-Host "==> Checking device $Device"
& $adb -s $Device get-state 2>&1 | Out-String | Write-Host

Write-Host "==> Installing APK"
& $adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "==> Launching player"
& $adb -s $Device shell am force-stop $pkg
& $adb -s $Device shell am start -n "$pkg/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "Failed to launch player" }
Start-Sleep -Seconds 12

Write-Host "==> Capturing pre-offline screenshot"
Invoke-Adb shell screencap -p /sdcard/offline-before.png
Invoke-Adb pull /sdcard/offline-before.png (Join-Path $proj "offline-before.png") | Out-Null

Write-Host "==> Disabling Wi-Fi (simulating network unavailable)"
Invoke-Adb shell svc wifi disable
Start-Sleep -Seconds 3

Write-Host "==> Logcat snapshot (offline markers)"
& $adb -s $Device logcat -d -t 80 -s PlayerViewModel:* chromium:* ExoPlayer:* 2>&1 | Select-Object -Last 30

Write-Host "==> Capturing offline screenshot"
Start-Sleep -Seconds 15
Invoke-Adb shell screencap -p /sdcard/offline-during.png
Invoke-Adb pull /sdcard/offline-during.png (Join-Path $proj "offline-during.png") | Out-Null

Write-Host "==> Re-enabling Wi-Fi"
Invoke-Adb shell svc wifi enable
Start-Sleep -Seconds 8

Write-Host "==> Capturing post-restore screenshot"
Invoke-Adb shell screencap -p /sdcard/offline-after.png
Invoke-Adb pull /sdcard/offline-after.png (Join-Path $proj "offline-after.png") | Out-Null

Write-Host "==> Done. Screenshots:"
Write-Host "  $(Join-Path $proj 'offline-before.png')"
Write-Host "  $(Join-Path $proj 'offline-during.png')"
Write-Host "  $(Join-Path $proj 'offline-after.png')"
