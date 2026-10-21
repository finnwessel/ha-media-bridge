# Media Bridge

Real-time media state and control for Android TV / Google TV devices in Home Assistant – built for a projector, works on any Android (TV) device.

Two parts in one repository:

- **Android app** (`android/`): a tiny background service without UI. It reads all media sessions, pushes every change instantly to Home Assistant and offers a small local API for commands and on-screen notifications.
- **Home Assistant integration** (`custom_components/media_bridge`): discovers the device, pairs with an on-screen code and creates the entities.

## What you get in Home Assistant

| Entity | |
|---|---|
| `media_player.<device>` | State (playing, paused, buffering, idle, off), title, artist, app, position/duration, volume, mute. Play/pause/stop/next/previous, seek, volume. |
| `sensor.<device>_media_end` | Timestamp when the current media ends (only while playing with known duration). |
| `sensor.<device>_foreground_app` | App in the foreground, also without a media session (attribute `package`). |
| `notify.<device>_screen` | Card in the top-right corner above whatever is playing (`notify.send_message` with title and message). |

Updates are pushed: play/pause/seek/app/volume changes arrive within ~250 ms, the position every 30 s while playing. A 60 s poll is only a safety net and marks the device unavailable when it is gone.

## Installation

### 1. App on the device (via ADB)

```sh
curl -LO https://github.com/finnwessel/ha-media-bridge/releases/latest/download/ha-media-bridge.apk
adb connect <device-ip>
adb install -r ha-media-bridge.apk

PKG=de.finnwessel.mediabridge
# Required: access to media sessions
adb shell cmd notification allow_listener $PKG/$PKG.MediaBridgeService
# Notifications and pairing code on screen
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
# Foreground app sensor
adb shell appops set $PKG GET_USAGE_STATS allow
```

Updates: `adb install -r` with the new APK; permissions stay.

### 2. Integration (HACS)

HACS → three dots → *Custom repositories* → `https://github.com/finnwessel/ha-media-bridge`, category *Integration*. Install **Media Bridge**, restart Home Assistant.

The device is discovered automatically (mDNS `_mediabridge._tcp`). Otherwise *Settings → Devices & services → Add integration → Media Bridge* and enter the IP (port 8765).

During setup a 6-digit code appears on the device screen; enter it in Home Assistant. The integration then registers its own webhook and tells the device where to push.

## Local API (port 8765)

| | Auth | |
|---|---|---|
| `GET /info` | – | id, name, model, version |
| `POST /pair/start` | – | shows a pairing code (valid 2 min, 5 attempts) |
| `POST /pair/finish` `{"code"}` | – | returns the token |
| `GET /state` | Bearer | current snapshot |
| `POST /config` `{"webhook_url"}` | Bearer | where to push |
| `POST /command` `{"command", …}` | Bearer | `play`, `pause`, `play_pause`, `stop`, `next`, `previous`, `seek` (`position_ms`), `volume_set` (`level` 0–1), `volume_up`, `volume_down`, `mute` (`muted`) |
| `POST /notify` `{"title", "message", "duration_ms"}` | Bearer | on-screen card |

Push payload (POST to the webhook) and `/state` share the same JSON: `id`, `state`, `screen_on`, `app`, `app_label`, `title`, `artist`, `album`, `duration_ms`, `position_ms`, `can_seek`, `foreground_app`, `foreground_label`, `volume`, `muted`, `sent_at`.

## Troubleshooting

```sh
adb logcat -s MediaBridge
```

- *listener not connected*: notification access missing (`allow_listener` above).
- *webhook failed*: the device can't reach Home Assistant; the integration derives the URL from the interface facing the device and HA's HTTP port.
- Show the token / set a webhook manually without the integration:
  `adb shell am broadcast -f 0x20 -n de.finnwessel.mediabridge/.ConfigReceiver -a de.finnwessel.mediabridge.CONFIGURE --ez show_token true` (also `--es url …`, `--ez resend true`).
- Vendor ROMs may kill background services: `adb shell dumpsys deviceidle whitelist +de.finnwessel.mediabridge`.

## Development

- App: `cd android && gradle assembleRelease` (Gradle 8.14, JDK 17, Android SDK 35). All builds are signed with the committed `signing.keystore` so updates install over each other; installing still needs ADB access.
- Integration tests: `pip install -r requirements_test.txt && pytest`.
- CI builds the APK, runs tests, hassfest and HACS validation. Every push to `main` publishes the release for the current version (app `versionName` and integration `version` must match).

## License

MIT
