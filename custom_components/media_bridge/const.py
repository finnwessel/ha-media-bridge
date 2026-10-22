"""Constants for the Media Bridge integration."""

from datetime import timedelta
import logging

DOMAIN = "media_bridge"
LOGGER = logging.getLogger(__package__)

DEFAULT_PORT = 8765
# Safety net only: state normally arrives by push. The poll also detects a
# device that went away and re-sends the webhook URL to a device that lost it.
POLL_INTERVAL = timedelta(seconds=60)

CONF_CODE = "code"
CONF_DEVICE_ID = "device_id"

# How long notifications stay on screen by default.
DEFAULT_NOTIFY_DURATION_MS = 8000

APPS_INTERVAL = timedelta(hours=1)

EVENT_NOTIFICATION_ACTION = f"{DOMAIN}_notification_action"

# Source entry that goes to the launcher instead of starting an app.
HOME_SOURCE = "Home"


def signal_key(entry_id: str) -> str:
    """Dispatcher signal for key presses of one device."""
    return f"{DOMAIN}_{entry_id}_key"

CONF_MAC = "mac"

EVENT_SLEEP_TIMER_FINISHED = f"{DOMAIN}_sleep_timer_finished"

# JMGO picture_mode values (Settings.Global) → option keys.
PICTURE_MODES = {7: "standard", 11: "soft", 9: "movie", 2: "office", 0: "dynamic", 3: "vivid"}


def signal_update(entry_id: str) -> str:
    """Dispatcher signal for app self-update progress of one device."""
    return f"{DOMAIN}_{entry_id}_update"


GITHUB_REPO = "finnwessel/ha-media-bridge"
APK_ASSET = "ha-media-bridge.apk"

# Permissions the app reports, with the ADB command that grants each one.
PKG = "de.finnwessel.mediabridge"
PERMISSION_COMMANDS = {
    "overlay": f"adb shell appops set {PKG} SYSTEM_ALERT_WINDOW allow",
    "accessibility": (
        f"adb shell settings put secure enabled_accessibility_services {PKG}/{PKG}.KeyService "
        "&& adb shell settings put secure accessibility_enabled 1"
    ),
    "device_admin": f"adb shell dpm set-active-admin {PKG}/.AdminReceiver",
    "secure_settings": f"adb shell pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS",
    "bluetooth": f"adb shell pm grant {PKG} android.permission.BLUETOOTH_CONNECT",
    "install": f"adb shell appops set {PKG} REQUEST_INSTALL_PACKAGES allow",
}
