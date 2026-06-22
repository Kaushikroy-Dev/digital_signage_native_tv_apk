# Build MSR native Android TV APK (Kotlin/Compose — not Expo)
# Requires: JDK 17+, Android SDK (Android Studio or cmdline-tools)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $Root

function Find-Jdk17 {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $v = & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Out-String
        if ($v -match 'version "1[789]\.|version "[2-9]') { return $env:JAVA_HOME }
    }
    $candidates = @(
        "$Root\.build-tools\jdk-17",
        "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Microsoft\jdk-17*"
    )
    foreach ($c in $candidates) {
        $resolved = Resolve-Path $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved -and (Test-Path "$resolved\bin\java.exe")) { return $resolved.Path }
    }
    return $null
}

function Find-AndroidSdk {
    foreach ($p in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($p -and (Test-Path "$p\platforms")) { return $p }
    }
    return $null
}

$jdk = Find-Jdk17
if (-not $jdk) {
    Write-Host "ERROR: JDK 17+ required. Install Android Studio or Temurin 17." -ForegroundColor Red
    Write-Host "  https://adoptium.net/temurin/releases/?version=17"
    exit 1
}
$env:JAVA_HOME = $jdk
Write-Host "Using JAVA_HOME=$jdk"

$sdk = Find-AndroidSdk
if (-not $sdk) {
    Write-Host "ERROR: Android SDK not found. Install Android Studio and SDK Platform 34." -ForegroundColor Red
    exit 1
}
"sdk.dir=$($sdk -replace '\\','\\')" | Set-Content -Path "local.properties" -Encoding ASCII
Write-Host "Using SDK=$sdk"

.\gradlew.bat assembleDebug --no-daemon
$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Write-Host ""
    Write-Host "APK ready: $Root\$apk" -ForegroundColor Green
    Write-Host "Install on TV: adb connect <TV_IP>:5555"
    Write-Host "             adb install -r $apk"
} else {
    Write-Host "Build finished but APK not found." -ForegroundColor Yellow
    exit 1
}
