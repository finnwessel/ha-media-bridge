"""Screensaver state."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the screensaver sensor."""
    async_add_entities([ScreensaverSensor(entry.runtime_data)])


class ScreensaverSensor(MediaBridgeEntity, BinarySensorEntity):
    """On while the screensaver (Android "dream") runs."""

    _attr_translation_key = "screensaver"

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the sensor."""
        super().__init__(coordinator, "screensaver")

    @property
    def is_on(self) -> bool:
        """Screensaver active."""
        return bool((self.coordinator.data or {}).get("dreaming"))
