# HA Media Bridge

Winzige Android-(TV-)App ohne Oberfläche, die jede Wiedergabeänderung (`playing`, `paused`, `buffering`, `stopped`, `idle`, `off`) sofort an einen Home-Assistant-Webhook schickt. Kein Polling, keine Companion-App, Konfiguration komplett per ADB.

## Wie es funktioniert

- Ein `NotificationListenerService` liefert Zugriff auf die MediaSessions aller Apps (Benachrichtigungen selbst werden ignoriert).
- Pro Session hängt ein `MediaController.Callback`; Änderungen werden 250 ms entprellt und nur gesendet, wenn sich Status, App, Titel oder Interpret geändert haben.
- Bildschirm aus → `off`, Bildschirm an → aktueller Status.
- Gesendet wird per HTTP-POST an die Webhook-URL; bei Fehlern bis zu 3 Versuche, ältere Zustände werden verworfen, sobald ein neuerer vorliegt.

Payload:

```json
{"state":"paused","app":"com.netflix.ninja","title":"…","artist":null,
 "duration_ms":5400000,"position_ms":1234000,"device":"N1S Ultimate","sent_at":1792…}
```

## Build

GitHub Actions baut bei jedem Push ein signiertes Release-APK (Artefakt `ha-media-bridge`). Tags `v*` hängen das APK zusätzlich an ein Release. Lokal: `gradle assembleRelease` (Gradle 8.14, JDK 17, Android SDK 35).

Alle Builds sind mit demselben Schlüssel (`app/signing.keystore`) signiert, damit `adb install -r` Updates ohne Neuinstallation einspielt.

## Einrichtung per ADB

```sh
# 1. Installieren
adb connect <beamer-ip>
adb install -r ha-media-bridge.apk

# 2. Zugriff auf MediaSessions erteilen
adb shell cmd notification allow_listener de.finnwessel.mediabridge/de.finnwessel.mediabridge.MediaBridgeService

# 3. Webhook setzen (schickt danach sofort den aktuellen Zustand)
adb shell am broadcast -f 0x20 -n de.finnwessel.mediabridge/.ConfigReceiver \
  -a de.finnwessel.mediabridge.CONFIGURE \
  --es url "http://<ha-ip>:8123/api/webhook/<webhook-id>"

# Prüfen
adb logcat -s MediaBridge
```

Aktuellen Zustand erneut senden: `… -a de.finnwessel.mediabridge.CONFIGURE --ez resend true`.

Der Konfigurations-Receiver verlangt `android.permission.DUMP`, die nur die ADB-Shell hat – andere Apps auf dem Gerät können die URL also nicht umbiegen.

## Home Assistant

Eine Automation mit Webhook-Trigger (`local_only: true`, `POST`) schreibt `trigger.json.state` in ein `input_select` (Optionen: `off`, `idle`, `stopped`, `buffering`, `paused`, `playing`) und `trigger.json.app` in ein `input_text`. Automationen reagieren dann auf Zustandsänderungen des `input_select`.

## Hinweise

- Hersteller-ROMs beenden Hintergrunddienste teils aggressiv. Falls Updates nach einiger Zeit ausbleiben: `adb shell dumpsys deviceidle whitelist +de.finnwessel.mediabridge`.
- Manche Apps setzen eine MediaSession erst nach dem ersten Abspielen.
