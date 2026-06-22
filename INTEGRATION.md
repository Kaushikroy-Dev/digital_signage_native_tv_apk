# Android TV integration contract (native player)

Native Kotlin/Compose player — no WebView. Pairing and playback use the MSR API gateway directly.

## Identity

| ID | Source | Purpose |
|----|--------|---------|
| `player_id` | Generated on first launch (UUID, SharedPreferences) | Pairing identity |
| `device_id` | Server after pairing | Schedule/content target |
| `device_token` | Optional from `POST /device/init` | `x-device-token` header |

## Configure endpoints

In `app/build.gradle`:

```gradle
buildConfigField "String", "API_BASE_URL", "\"https://your-api.example.com/api\""
buildConfigField "String", "WS_BASE_URL", "\"wss://your-api.example.com/ws\""
```

## REST API

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/device/init` | Optional boot check `{ "player_id": "uuid" }` |
| POST | `/devices/pairing/generate` | TV requests 8-char code |
| GET | `/devices/pairing/status/{code}` | Poll until `assignedDeviceId` |
| GET | `/schedules/player/{deviceId}/content` | Playlist + items + overlay |
| GET | `/devices/player/{deviceId}/environmental` | Weather/AQI for env bar |
| POST | `/devices/{deviceId}/heartbeat` | Device online status every 30s (`isPlaying`, `networkStatus`, memory/storage) |

## WebSocket (`WS_BASE_URL`)

On connect, send:

- `{"type":"register_player","playerId":"..."}` before pairing
- `{"type":"register","deviceId":"..."}` after pairing

Handle:

| Message | Action |
|---------|--------|
| `device_paired` | Save `deviceId`, load content |
| `command: screen_off` | Display off screen |
| `command: screen_on` | Resume playback |
| `command: reset_device_id` | Clear storage, re-pair |
| `command: refresh` / `refresh_content` | Re-fetch playlist |
| `command: clear_cache` | Clear media cache |

## Playback (native v1)

| `file_type` | Renderer |
|-------------|----------|
| `video` | ExoPlayer (Media3) |
| `image` | Coil + duration timer |
| `document` | Page image slideshow |
| `template` | Placeholder (full widget engine future) |

## Offline

- Playlist JSON cached in Room (24h TTL)
- Media files in app storage with LRU eviction (~1.5 GB)
- Cold boot without network plays cached playlist + local files
- Runtime network loss: keeps playing cached content, shows offline badge (ConnectivityManager + API failure detection)
- Document pages cached per `playbackKey:page:N` and played from local `file://` paths when available
- 404 on content fetch clears pairing and shows pairing code (stale device)

### Heartbeat body (every 30s)

```json
{
  "cpuUsage": 0,
  "memoryUsage": 42.5,
  "storageUsedGb": 1.2,
  "storageTotalGb": 32,
  "networkStatus": "online",
  "isPlaying": true
}
```

`isPlaying` is true when actively playing and display is on (`screen_off` command sets it false).

## Pairing flow (TV)

1. App shows 8-digit code (Stitch UI)
2. Admin claims code in portal
3. App polls status or receives `device_paired` via WebSocket
4. Content fetch begins automatically

## Handoff

Export `android-tv-app/` only. Include this file and `stitch_msr_signage_player_interface/` as design reference. Do not include backend/portal source.
