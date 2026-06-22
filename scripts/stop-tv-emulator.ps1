# Stop the running Android TV emulator
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\ab\sdk" }
$adb = Join-Path $Sdk "platform-tools\adb.exe"
$serial = & $adb devices 2>$null | Select-String "emulator-\d+" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1
if (-not $serial) {
    Write-Host "No emulator running." -ForegroundColor Yellow
    exit 0
}
Write-Host "Stopping $serial ..."
& $adb -s $serial emu kill
Write-Host "Emulator stopped." -ForegroundColor Green
