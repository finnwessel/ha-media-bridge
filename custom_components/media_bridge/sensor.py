"""Sensors for Media Bridge."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0


def media_end(data: dict[str, Any]) -> datetime | None:
    """When the current media will end, if it is playing and its length is known."""
    duration = data.get("duration_ms")
    position = data.get("position_ms")
    received = data.get("received_at")
    if data.get("state") != "playing" or not duration or position is None or received is None:
        return None
    if position >= duration:
        # Implausible report (position at or past the end): better no value than "now".
        return None
    remaining = duration - position
    return (received + timedelta(milliseconds=remaining)).replace(microsecond=0)


@dataclass(frozen=True, kw_only=True)
class MediaBridgeSensorDescription(SensorEntityDescription):
    """Sensor description with value and attribute functions."""

    value_fn: Callable[[dict[str, Any]], Any]
    attrs_fn: Callable[[dict[str, Any]], dict[str, Any]] | None = None


SENSORS: tuple[MediaBridgeSensorDescription, ...] = (
    MediaBridgeSensorDescription(
        key="media_end",
        translation_key="media_end",
        device_class=SensorDeviceClass.TIMESTAMP,
        value_fn=media_end,
    ),
    MediaBridgeSensorDescription(
        key="foreground_app",
        translation_key="foreground_app",
        value_fn=lambda data: data.get("foreground_label") or data.get("foreground_app"),
        attrs_fn=lambda data: {"package": data.get("foreground_app")},
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the sensors."""
    async_add_entities(
        MediaBridgeSensor(entry.runtime_data, description) for description in SENSORS
    )


class MediaBridgeSensor(MediaBridgeEntity, SensorEntity):
    """A value derived from the device state."""

    entity_description: MediaBridgeSensorDescription

    def __init__(
        self,
        coordinator: MediaBridgeCoordinator,
        description: MediaBridgeSensorDescription,
    ) -> None:
        """Initialize the sensor."""
        super().__init__(coordinator, description.key)
        self.entity_description = description

    @property
    def native_value(self) -> Any:
        """Current value."""
        return self.entity_description.value_fn(self.coordinator.data or {})

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        """Extra attributes."""
        if self.entity_description.attrs_fn is None:
            return None
        return self.entity_description.attrs_fn(self.coordinator.data or {})
