# MSR Digital Signage — Native Android TV Player

Full-native Android TV signage player built with **Jetpack Compose**, matching the Stitch **Cinematic Signage** designs.

## Features

- All 12 Stitch UI states (splash, pairing, loading, idle, playback, display off, offline badge, cache sync)
- Native playback: **ExoPlayer** (video), **Coil** (images), document slideshow
- Environmental overlay bar (clock, weather, AQI) top or bottom
- Pairing via 8-digit code + admin portal
- WebSocket remote commands (`screen_off`, `refresh`, `unpair`)
- Offline: Room playlist cache + file media cache with LRU eviction
- Auto-start on boot (`BootReceiver`)

## Design reference

Stitch exports live under:

```
stitch_msr_signage_player_interface/
├── cinematic_signage/DESIGN.md
├── splash_boot_screen/
├── pairing_waiting_for_admin/
├── playing_content_top_bar/
└── ...
```

## Build & run on TV

See **[BUILD_ON_TV.md](BUILD_ON_TV.md)** for APK build (Android Studio or CI) and sideload steps on Android TV.

> **Note:** This is a **Kotlin/Compose native** app, not Expo or React Native.

## Build (local)

1. Set API URLs in `app/build.gradle` (`API_BASE_URL`, `WS_BASE_URL`)
2. Create `local.properties` with `sdk.dir=...`
3. Build:

```bash
cd android-tv-app
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

## Install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Architecture

```
ui/          Compose screens + Stitch theme
domain/      PlayerViewModel + state machine
data/api/    Retrofit MSR APIs
data/local/  Room cache
data/cache/  Media file downloads
data/ws/     Gateway WebSocket
```

See [INTEGRATION.md](INTEGRATION.md) for API contract (handoff to Android team without backend repo).

## Requirements

- Android 7.0+ (API 24)
- Android TV or tablet in landscape kiosk mode
- JDK 17, Android SDK 34
