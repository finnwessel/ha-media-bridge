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
