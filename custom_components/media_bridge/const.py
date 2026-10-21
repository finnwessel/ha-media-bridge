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
