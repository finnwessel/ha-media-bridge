"""On-screen notifications for Media Bridge."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .const import DEFAULT_NOTIFY_DURATION_MS
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the notify entity."""
    async_add_entities([MediaBridgeNotify(entry.runtime_data)])


class MediaBridgeNotify(MediaBridgeEntity, NotifyEntity):
    """Shows a card in the corner of the screen, above whatever is playing."""

    _attr_translation_key = "overlay"
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the notify entity."""
        super().__init__(coordinator, "notify")

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        """Show the message on screen."""
        try:
            await self.coordinator.client.notify(message, title, DEFAULT_NOTIFY_DURATION_MS)
        except MediaBridgeError as err:
            raise HomeAssistantError(f"Notification failed: {err}") from err
