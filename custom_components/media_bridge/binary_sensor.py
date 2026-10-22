"""Screensaver state and missing app permissions."""

from __future__ import annotations

from typing import Any

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.const import EntityCategory
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
    async_add_entities([ScreensaverSensor(entry.runtime_data), PermissionsSensor(entry.runtime_data)])


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


class PermissionsSensor(MediaBridgeEntity, BinarySensorEntity):
    """Problem when the app lacks a permission (e.g. accessibility switched off after an update).

    Disabled by default: missing permissions are reported as repair issues; this sensor is
    only for automations, and problem sensors tend to show up on area dashboards.
    """

    _attr_translation_key = "permissions"
    _attr_entity_registry_enabled_default = False
    _attr_device_class = BinarySensorDeviceClass.PROBLEM
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the sensor."""
        super().__init__(coordinator, "permissions")

    @property
    def _permissions(self) -> dict[str, bool] | None:
        return ((self.coordinator.data or {}).get("diagnostics") or {}).get("permissions")

    @property
    def available(self) -> bool:
        """Only apps from 0.7.0 report their permissions."""
        return super().available and self._permissions is not None

    @property
    def is_on(self) -> bool:
        """Something is missing."""
        return any(v is False for v in (self._permissions or {}).values())

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Which permissions are missing."""
        perms = self._permissions or {}
        return {"missing": [k for k, v in perms.items() if v is False], **perms}
