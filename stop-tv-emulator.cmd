@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-tv-emulator.ps1" %*
