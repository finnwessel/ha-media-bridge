"""Remote-control key presses as an event entity."""

from __future__ import annotations

from homeassistant.components.event import EventDeviceClass, EventEntity
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import signal_key
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the remote event entity."""
    async_add_entities([MediaBridgeRemoteEvent(entry.runtime_data)])


class MediaBridgeRemoteEvent(MediaBridgeEntity, EventEntity):
    """Fires on every key press of the remote (except navigation); the key is in the attributes."""

    _attr_translation_key = "remote"
    _attr_device_class = EventDeviceClass.BUTTON
    _attr_event_types = ["short_press", "long_press"]

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the event entity."""
        super().__init__(coordinator, "remote")

    @property
    def available(self) -> bool:
        """Key presses can arrive while the state poll is failing; stay available."""
        return True

    async def async_added_to_hass(self) -> None:
        """Listen for key presses pushed by the device."""
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, signal_key(self.coordinator.config_entry.entry_id), self._handle_key
            )
        )

    @callback
    def _handle_key(self, key: str | None, press: str | None) -> None:
        if not key or press not in self._attr_event_types:
            return
        self._trigger_event(press, {"key": key})
        self.async_write_ha_state()
