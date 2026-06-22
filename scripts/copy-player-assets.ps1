# Copy frontend production build into Android assets for offline cold-boot.
$ErrorActionPreference = "Stop"

$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Src = Join-Path $Root "frontend\dist"
$Dest = Join-Path $Root "android-tv-app\app\src\main\assets\player"

if (-not (Test-Path (Join-Path $Src "index.html"))) {
    Write-Error "Missing $Src\index.html — run: cd frontend; npm run build"
}

if (Test-Path $Dest) {
    Remove-Item -Recurse -Force $Dest
}
New-Item -ItemType Directory -Path $Dest -Force | Out-Null
Copy-Item -Path (Join-Path $Src "*") -Destination $Dest -Recurse -Force

Write-Host "Copied web player to $Dest"
Write-Host "Next: set USE_BUNDLED_PLAYER=true in android-tv-app/app/build.gradle and rebuild APK."
