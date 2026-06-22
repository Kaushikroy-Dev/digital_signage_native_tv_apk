# Android TV emulator window & power controls (Windows)
# Run: .\scripts\emulator-window-help.ps1

Write-Host @"

=== Android Emulator window controls ===

FULL SCREEN (hide emulator chrome):
  Click the emulator window, then press:  Ctrl + Shift + F
  (Or menu: View -> Enter Fullscreen)
  Exit fullscreen: Ctrl + Shift + F again

MINIMIZE / RESTORE:
  Exit fullscreen first (Ctrl + Shift + F), then use the normal Windows
  title-bar Minimize button, or Win + D to show desktop.

POWER the virtual TV (sleep/wake, not close emulator):
  Toolbar: click the Power icon (circle with line)
  Or: Extended controls (...) -> Power -> Power off / Power on
  Or adb:  adb shell input keyevent KEYCODE_POWER

CLOSE the emulator completely:
  .\scripts\stop-tv-emulator.ps1
  Or: adb -s emulator-5554 emu kill

RESTART emulator + install player:
  .\scripts\run-tv-emulator.ps1

If the emulator window has no title bar, you are in fullscreen — press Ctrl+Shift+F first.

"@ -ForegroundColor Cyan
