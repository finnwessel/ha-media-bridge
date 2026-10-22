"""Sensors for Media Bridge."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
import re
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
)
from homeassistant.const import EntityCategory, UnitOfLength
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


AUDIO_OUTPUTS = ["speaker", "hdmi", "hdmi_arc", "bluetooth", "headphones", "usb", "line_out"]

CONTENT_TYPES = ["movie", "episode", "clip", "music", "live"]

# Apps whose sessions are music, whatever the length.
MUSIC_APPS = {
    "com.spotify.tv.android",
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.amazon.mp3",
    "com.apple.android.music",
    "deezer.android.tv",
    "com.aspiro.tidal",
    "io.music_assistant.companion",
}

# Video apps that carry user clips as well as long-form content.
CLIP_APPS = {"com.google.android.youtube.tv", "com.google.android.youtube", "com.zhiliaoapp.musically"}

EPISODE_PATTERN = re.compile(
    r"(\bS\d{1,2}\s*[:.]?\s*[EF]\d{1,3}\b|\b(Folge|Episode|Staffel|Season)\s*\d+)", re.IGNORECASE
)


def content_type(data: dict[str, Any]) -> str | None:
    """Rough kind of what is playing: movie, episode, clip, music or live."""
    if data.get("state") in (None, "off", "idle", "stopped") or not data.get("app"):
        return None
    app = data["app"]
    duration = (data.get("duration_ms") or 0) / 60_000
    title = " ".join(filter(None, (data.get("title"), data.get("album"))))
    if app in MUSIC_APPS:
        return "music"
    if duration <= 0:
        return "live"
    if EPISODE_PATTERN.search(title):
        return "episode"
    if app in CLIP_APPS:
        if duration < 20:
            return "clip"
        return "movie" if duration >= 75 else "episode"
    if duration < 15:
        return "clip"
    return "movie" if duration >= 75 else "episode"


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
        key="content_type",
        translation_key="content_type",
        device_class=SensorDeviceClass.ENUM,
        options=CONTENT_TYPES,
        value_fn=content_type,
    ),
    MediaBridgeSensorDescription(
        key="up_next",
        translation_key="up_next",
        value_fn=lambda data: data.get("next_title"),
    ),
    MediaBridgeSensorDescription(
        key="audio_output",
        translation_key="audio_output",
        device_class=SensorDeviceClass.ENUM,
        options=AUDIO_OUTPUTS,
        value_fn=lambda data: data.get("audio_output") if data.get("audio_output") in AUDIO_OUTPUTS else None,
        attrs_fn=lambda data: {"device_name": data.get("audio_output_name")},
    ),
    MediaBridgeSensorDescription(
        key="projection_distance",
        translation_key="projection_distance",
        device_class=SensorDeviceClass.DISTANCE,
        native_unit_of_measurement=UnitOfLength.METERS,
        suggested_display_precision=2,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda data: round(data["distance_mm"] / 1000, 3) if data.get("distance_mm") else None,
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
