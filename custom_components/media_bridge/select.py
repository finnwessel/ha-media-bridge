"""Picture mode of JMGO projectors."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .const import PICTURE_MODES
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0

VALUES = {key: value for value, key in PICTURE_MODES.items()}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the picture mode select (only useful on devices that report one)."""
    async_add_entities([PictureModeSelect(entry.runtime_data)])


class PictureModeSelect(MediaBridgeEntity, SelectEntity):
    """Picture mode; needs WRITE_SECURE_SETTINGS on the device to change it."""

    _attr_translation_key = "picture_mode"
    _attr_options = list(VALUES)

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the select."""
        super().__init__(coordinator, "picture_mode")

    @property
    def available(self) -> bool:
        """Only while the device reports a picture mode."""
        return super().available and (self.coordinator.data or {}).get("picture_mode") is not None

    @property
    def current_option(self) -> str | None:
        """Current mode, None for values without a name."""
        return PICTURE_MODES.get((self.coordinator.data or {}).get("picture_mode"))

    async def async_select_option(self, option: str) -> None:
        """Switch the picture mode."""
        try:
            await self.coordinator.client.command("set_setting", key="picture_mode", value=VALUES[option])
        except MediaBridgeError as err:
            raise HomeAssistantError(
                f"Picture mode not changed: {err}. Grant WRITE_SECURE_SETTINGS to the app (see README)."
            ) from err
        if self.coordinator.data is not None:
            self.coordinator.data["picture_mode"] = VALUES[option]
            self.async_write_ha_state()
