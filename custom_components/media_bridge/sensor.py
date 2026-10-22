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
from homeassistant.const import (
    SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    EntityCategory,
    UnitOfDataRate,
    UnitOfFrequency,
    UnitOfInformation,
)
from homeassistant.core import HomeAssistant
from homeassistant.util import dt as dt_util
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


def _diag(data: dict[str, Any], key: str) -> Any:
    return (data.get("diagnostics") or {}).get(key)


def _display(data: dict[str, Any]) -> dict[str, Any]:
    return data.get("display") or {}


def resolution(data: dict[str, Any]) -> str | None:
    """Output resolution as 2160p, 1080p, ..."""
    if data.get("state") in (None, "off"):
        return None
    height = _display(data).get("height")
    return f"{height}p" if height else None


def refresh_rate(data: dict[str, Any]) -> float | None:
    """Refresh rate of the output; changes to 24/50 Hz with frame-rate matching."""
    if data.get("state") in (None, "off"):
        return None
    return _display(data).get("refresh_rate")


def bluetooth_count(data: dict[str, Any]) -> int | None:
    devices = data.get("bluetooth")
    return None if devices is None else len(devices)


def storage_free(data: dict[str, Any]) -> float | None:
    free = _diag(data, "storage_free")
    return round(free / 1e9, 1) if free is not None else None


def booted_at(data: dict[str, Any]) -> datetime | None:
    millis = _diag(data, "booted_at")
    if millis is None:
        return None
    # Rounded to the minute so clock jitter doesn't produce a new state every push.
    return dt_util.utc_from_timestamp(round(millis / 60000) * 60)


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
        key="foreground_app",
        translation_key="foreground_app",
        value_fn=lambda data: data.get("foreground_label") or data.get("foreground_app"),
        attrs_fn=lambda data: {"package": data.get("foreground_app")},
    ),
    MediaBridgeSensorDescription(
        key="resolution",
        translation_key="resolution",
        value_fn=resolution,
        attrs_fn=lambda data: {
            "width": _display(data).get("width"),
            "height": _display(data).get("height"),
            "hdr_formats": _display(data).get("hdr_types"),
        },
    ),
    MediaBridgeSensorDescription(
        key="refresh_rate",
        translation_key="refresh_rate",
        device_class=SensorDeviceClass.FREQUENCY,
        native_unit_of_measurement=UnitOfFrequency.HERTZ,
        suggested_display_precision=0,
        value_fn=refresh_rate,
    ),
    MediaBridgeSensorDescription(
        key="bluetooth",
        translation_key="bluetooth",
        value_fn=bluetooth_count,
        attrs_fn=lambda data: {
            "devices": [d.get("name") for d in data.get("bluetooth") or []],
            "details": data.get("bluetooth") or [],
        },
    ),
    MediaBridgeSensorDescription(
        key="wifi_signal",
        translation_key="wifi_signal",
        device_class=SensorDeviceClass.SIGNAL_STRENGTH,
        native_unit_of_measurement=SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda data: _diag(data, "wifi_rssi"),
        attrs_fn=lambda data: {
            "network": _diag(data, "network"),
            "frequency_mhz": _diag(data, "wifi_frequency"),
        },
    ),
    MediaBridgeSensorDescription(
        key="wifi_link_speed",
        translation_key="wifi_link_speed",
        device_class=SensorDeviceClass.DATA_RATE,
        native_unit_of_measurement=UnitOfDataRate.MEGABITS_PER_SECOND,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda data: _diag(data, "wifi_link_mbps"),
    ),
    MediaBridgeSensorDescription(
        key="storage_free",
        translation_key="storage_free",
        device_class=SensorDeviceClass.DATA_SIZE,
        native_unit_of_measurement=UnitOfInformation.GIGABYTES,
        suggested_display_precision=1,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=storage_free,
        attrs_fn=lambda data: {
            "total_gb": round(_diag(data, "storage_total") / 1e9, 1) if _diag(data, "storage_total") else None
        },
    ),
    MediaBridgeSensorDescription(
        key="last_boot",
        translation_key="last_boot",
        device_class=SensorDeviceClass.TIMESTAMP,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=booted_at,
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
