# Run the native Android TV APK on your TV

This project is a **Kotlin / Jetpack Compose** app (`android-tv-app/`). It is **not** an Expo or React Native app.

## Option A — Build on your PC (Android Studio)

1. Install [Android Studio](https://developer.android.com/studio) (includes JDK 17 + SDK).
2. Open the `android-tv-app` folder in Android Studio.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. APK path: `app/build/outputs/apk/debug/app-debug.apk`

Or from PowerShell (with SDK installed):

```powershell
cd android-tv-app
.\scripts\build-android-tv.ps1
```

## Option B — Download APK from GitHub Actions

1. Push this repo (or run workflow on `main`).
2. Open **Actions → Build Android TV APK (Native)**.
3. Download artifact **msr-android-tv-debug**.
4. Sideload the APK (see below).

## Install on Android TV

### Enable developer mode on the TV

1. **Settings → Device preferences → About**
2. Click **Build** 7 times → Developer options enabled
3. **Settings → Device preferences → Developer options**
4. Enable **USB debugging** and **Apps from unknown sources** (wording varies by OEM)

### Install via ADB (USB or network)

```bash
# USB
adb devices
adb install -r app-debug.apk

# Same Wi‑Fi (find TV IP in Settings → Network)
adb connect 192.168.1.100:5555
adb install -r app-debug.apk
```

### Install via USB stick

1. Copy `app-debug.apk` to a USB drive.
2. Use a file manager on TV (or **Send files to TV** app) to open the APK and install.

### Launch

- Find **MSR Digital Signage** / **Digital Signage Player** in the TV app list.
- Leanback launcher category is already set in the manifest.

## API endpoints

Production URLs are in `app/build.gradle`:

- `API_BASE_URL` — REST API
- `WS_BASE_URL` — WebSocket

Change these before building if you use a different environment.

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Gradle needs JDK 17 | Use Android Studio JBR or install Temurin 17 |
| `sdk.dir` missing | Create `local.properties`: `sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk` |
| TV rejects APK | Enable unknown sources; use `adb install -r` |
| App not in launcher | Reboot TV after install; check leanback launcher |
