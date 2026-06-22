# Start Android TV emulator (API 34) and install debug APK
# Requires: C:\ab\sdk from initial setup, or ANDROID_HOME with emulator + TV image

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\ab\sdk" }
$Jdk = Join-Path $Root ".build-tools\jdk-17"

$env:JAVA_HOME = if (Test-Path $Jdk) { $Jdk } else { $env:JAVA_HOME }
$env:ANDROID_HOME = $Sdk
$env:PATH = "$Sdk\emulator;$Sdk\platform-tools;$Sdk\cmdline-tools\latest\bin;$env:PATH"

$AvdName = "msr_tv_34"
$adb = "$Sdk\platform-tools\adb.exe"
$emu = "$Sdk\emulator\emulator.exe"

if (-not (Test-Path $emu)) {
    Write-Host "Emulator not found. Install with sdkmanager emulator and TV system image." -ForegroundColor Red
    exit 1
}

$running = & $adb devices 2>$null | Select-String "emulator-"
if (-not $running) {
    Write-Host "Starting TV emulator ($AvdName)..."
    Start-Process -FilePath $emu -ArgumentList "-avd",$AvdName,"-no-audio","-gpu","swiftshader_indirect","-skin","1920x1080" -WindowStyle Normal
    & $adb wait-for-device
    do { Start-Sleep 3; $boot = & $adb shell getprop sys.boot_completed 2>$null } until ($boot -match "1")
    Write-Host "Emulator ready."
} else {
    Write-Host "Emulator already running."
}

if (Test-Path $Apk) {
    Write-Host "Installing APK..."
    & $adb install -r $Apk
    & $adb shell am force-stop com.digitalsignage.player
    & $adb shell am start -n com.digitalsignage.player/.MainActivity
    Write-Host "MSR player launched on TV emulator." -ForegroundColor Green
    Write-Host "Emulator fullscreen: Ctrl+Shift+F  |  Stop emulator: .\scripts\stop-tv-emulator.ps1" -ForegroundColor Cyan
} else {
    Write-Host "APK not found at: $Apk" -ForegroundColor Yellow
    Write-Host "Build it first:" -ForegroundColor Yellow
    Write-Host "  cd $Root" -ForegroundColor Yellow
    Write-Host "  `$env:JAVA_HOME = `"$Jdk`"" -ForegroundColor Yellow
    Write-Host "  .\gradlew.bat assembleDebug" -ForegroundColor Yellow
}
