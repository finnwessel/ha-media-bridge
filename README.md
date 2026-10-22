# Media Bridge

Real-time media state and control for Android TV / Google TV devices in Home Assistant – built for a projector, works on any Android (TV) device.

Two parts in one repository:

- **Android app** (`android/`): a tiny background service without UI. It reads all media sessions, pushes every change instantly to Home Assistant and offers a small local API for commands and on-screen notifications.
- **Home Assistant integration** (`custom_components/media_bridge`): discovers the device, pairs with an on-screen code and creates the entities.

## What you get in Home Assistant

| Entity | |
|---|---|
| `media_player.<device>` | State (playing, paused, buffering, idle, off), title, artist, cover (the app's TV logo when there is no cover), app, position/duration, volume, mute. Play/pause/stop/next/previous, seek, volume. Source list = installed apps + Home: selecting one starts it. |
| `sensor.<device>_media_end` | Timestamp when the current media ends (only while playing with known duration). |
| `sensor.<device>_foreground_app` | App in the foreground, also without a media session (attribute `package`). |
| `sensor.<device>_content_type` | `movie`, `episode`, `clip`, `music` or `live`, estimated from app, length and title (e.g. `S2:E4`). |
| `sensor.<device>_up_next` | Title of the next item, for apps that publish a queue. |
| `sensor.<device>_audio_output` | `speaker`, `hdmi`, `hdmi_arc`, `bluetooth`, `headphones`, `usb`, `line_out` (attribute `device_name`). Exact route on Android 13+, best guess before. |
| `notify.<device>_screen` | Card in the top-right corner above whatever is playing (`notify.send_message` with title and message). |
| `select.<device>_picture_mode` | JMGO picture mode (standard, soft, movie, office, dynamic, vivid), switchable. Needs `WRITE_SECURE_SETTINGS`. |
| `binary_sensor.<device>_screensaver` | On while the screensaver runs. |
| `switch.<device>_sleep_after_this_item` | Standby once the current item has finished (stopped, end reached, or the next title starts – that one is paused first). Fires `media_bridge_sleep_timer_finished`. |
| `sensor.<device>_resolution`, `sensor.<device>_refresh_rate` | Output mode, e.g. `2160p` and `23.98` Hz – shows whether frame-rate matching kicks in. Attribute `hdr_formats` lists the HDR formats the output supports (the active HDR mode isn't readable by apps). |
| `sensor.<device>_bluetooth` | Number of connected Bluetooth devices; attributes `devices` (names) and `details` (type, battery where reported). Needs `BLUETOOTH_CONNECT`. |
| `update.<device>_app` | New app releases from GitHub; *Install* lets the device download and install the APK itself. The first time Android asks for confirmation on screen (OK with the remote); later updates install without asking. Needs `REQUEST_INSTALL_PACKAGES`. |
| `binary_sensor.<device>_permissions` | Diagnostic problem sensor, disabled by default (enable it for automations). A missing permission raises a repair issue in Home Assistant with the ADB command that fixes it. |
| Diagnostics | `wifi_signal` (dBm), `wifi_link_speed` (Mbit/s), `storage_free` (GB), `last_boot`. |
| `event.<device>_remote` | Remote key presses (`short_press` / `long_press`, attribute `key`, e.g. `menu`, `media_play_pause`, `prog_red`, and `button` with readable names for vendor keys, e.g. `netflix`, `youtube`, `prime_video`, `input` on JMGO remotes). Navigation keys are not reported. Keys are only observed, the remote keeps working. |

### Playing links and apps

`media_player.play_media` opens links in the app that handles them (YouTube and Netflix links go to their TV apps), or starts an app with `media_content_type: app`:

```yaml
action: media_player.play_media
target:
  entity_id: media_player.beamer
data:
  media_content_type: url
  media_content_id: https://www.youtube.com/watch?v=dQw4w9WgXcQ
```

### Service `media_bridge.show_notification`

Card with optional camera image, up to three buttons and pausing playback:

```yaml
action: media_bridge.show_notification
target:
  entity_id: media_player.beamer
data:
  title: Haustür
  message: Es hat geklingelt
  image_entity_id: camera.doorbell
  actions:
    - id: open
      title: Öffnen
    - id: ignore
      title: Ignorieren
  tag: door
  pause_playback: true
  duration: 30
```

While buttons are shown the remote controls the card (BACK closes it). The answer fires the event `media_bridge_notification_action` with `action` (button id, `dismissed` or `timeout`), `tag`, `device_id`.

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
# Foreground app sensor (fallback; the accessibility service below is preferred)
adb shell appops set $PKG GET_USAGE_STATS allow
# Remote key events, foreground app, ending the screensaver (check first that no other accessibility service is enabled:
#   adb shell settings get secure enabled_accessibility_services)
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.KeyService
adb shell settings put secure accessibility_enabled 1
```

"Display over other apps" is also what allows starting apps from the source list.

Standby and picture mode:

```sh
# switch off from Home Assistant (screen off = standby)
adb shell dpm set-active-admin $PKG/.AdminReceiver
# change picture mode from Home Assistant
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# connected Bluetooth devices
adb shell pm grant $PKG android.permission.BLUETOOTH_CONNECT
# app updates from Home Assistant (no ADB needed for updates afterwards)
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
```

Switching **on** uses Wake-on-LAN: enter the device's MAC in the integration options. The device must keep its network up in standby (JMGO: power mode "Optimized" or "Enhanced"). Without a MAC the media player becomes unavailable while the device sleeps.

The address of a paired device can be changed via *Reconfigure*.

Updates: from Home Assistant via the update entity (0.7.0+), or `adb install -r` with the new APK; permissions stay.

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
| `GET /apps` | Bearer | launchable apps `[{package, label}]` |
| `GET /artwork` | Bearer | cover of the current media (JPEG) |
| `GET /icon?package=…` | Bearer | app TV banner as PNG (`&kind=icon` for the launcher icon) |
| `POST /config` `{"webhook_url"}` | Bearer | where to push |
| `POST /command` `{"command", …}` | Bearer | `install_update` (`url`), `play`, `pause`, `play_pause`, `stop`, `next`, `previous`, `seek` (`position_ms`), `volume_set` (`level` 0–1), `volume_up`, `volume_down`, `mute` (`muted`), `launch` (`package`), `open_url` (`url`), `home`, `sleep`, `set_setting` (`key`, `value`) |
| `POST /notify` `{"title", "message", "duration_ms", "image", "actions", "tag", "pause"}` | Bearer | on-screen card; `image` base64, `actions` `[{id, title}]` |

Push payload (POST to the webhook) and `/state` share the same JSON: `id`, `version`, `state`, `screen_on`, `app`, `app_label`, `title`, `artist`, `album`, `duration_ms`, `position_ms`, `can_seek`, `foreground_app`, `foreground_label`, `volume`, `muted`, `artwork_id`, `artwork_url`, `next_title`, `audio_output`, `audio_output_name`, `dreaming`, `picture_mode`, `eye_protector`, `memc`, `sound_mode`, `can_sleep`, `display` (`width`, `height`, `refresh_rate`, `hdr_types`), `bluetooth` (`[{name, address, type, battery}]`), `diagnostics` (`network`, `wifi_rssi`, `wifi_link_mbps`, `wifi_frequency`, `storage_free`, `storage_total`, `booted_at`, `permissions`), `sent_at`. One-off events use the same webhook with an `event` field (`key` with `key`/`press`, `notification_action` with `action`/`tag`, `update` with `status` = `downloading`/`installing`/`confirm`/`installed`/`failed` and `detail`).

## Notes on Android quirks handled by the app

- **Wrong position clock:** some apps (YouTube on Android TV) stamp positions with `uptimeMillis()` instead of `elapsedRealtime()`. Android then extrapolates playing positions ahead by all standby time since boot. The app learns the clock per app from pause states and corrects for it.
- **Inactive paused sessions:** YouTube marks its paused session inactive when the screen goes off. Such sessions are kept until destroyed and still count when their app is in the foreground.
- **Titles from notifications:** apps that publish a session without metadata often still show the title in their media notification; it is used as a fallback.
- **Ignored `setStreamVolume`:** some ROMs ignore it; the app verifies and falls back to step adjustments.

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
