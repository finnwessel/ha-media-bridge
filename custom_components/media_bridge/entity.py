"""Base entity for Media Bridge."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import MediaBridgeCoordinator


class MediaBridgeEntity(CoordinatorEntity[MediaBridgeCoordinator]):
    """Common device info and availability."""

    _attr_has_entity_name = True

    def __init__(self, coordinator: MediaBridgeCoordinator, key: str) -> None:
        """Initialize the entity."""
        super().__init__(coordinator)
        entry = coordinator.config_entry
        self._attr_unique_id = f"{entry.unique_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.unique_id)},
            name=entry.title,
            manufacturer=entry.data.get("manufacturer"),
            model=entry.data.get("model"),
            sw_version=entry.data.get("version"),
        )

    @property
    def available(self) -> bool:
        """Available while the last push or poll succeeded."""
        return super().available and self.coordinator.data is not None
