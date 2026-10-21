"""Media player entity for Media Bridge."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .coordinator import MediaBridgeConfigEntry
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0

STATES = {
    "off": MediaPlayerState.OFF,
    "idle": MediaPlayerState.IDLE,
    "stopped": MediaPlayerState.IDLE,
    "buffering": MediaPlayerState.BUFFERING,
    "paused": MediaPlayerState.PAUSED,
    "playing": MediaPlayerState.PLAYING,
}

BASE_FEATURES = (
    MediaPlayerEntityFeature.PLAY
    | MediaPlayerEntityFeature.PAUSE
    | MediaPlayerEntityFeature.STOP
    | MediaPlayerEntityFeature.NEXT_TRACK
    | MediaPlayerEntityFeature.PREVIOUS_TRACK
    | MediaPlayerEntityFeature.VOLUME_SET
    | MediaPlayerEntityFeature.VOLUME_STEP
    | MediaPlayerEntityFeature.VOLUME_MUTE
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the media player."""
    async_add_entities([MediaBridgeMediaPlayer(entry.runtime_data)])


class MediaBridgeMediaPlayer(MediaBridgeEntity, MediaPlayerEntity):
    """What is playing on the device, with transport and volume control."""

    _attr_name = None
    _attr_device_class = MediaPlayerDeviceClass.TV

    def __init__(self, coordinator) -> None:
        """Initialize the media player."""
        super().__init__(coordinator, "media_player")

    @property
    def _data(self) -> dict[str, Any]:
        return self.coordinator.data or {}

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        """Seeking only where the playing app supports it."""
        if self._data.get("can_seek"):
            return BASE_FEATURES | MediaPlayerEntityFeature.SEEK
        return BASE_FEATURES

    @property
    def state(self) -> MediaPlayerState | None:
        """Playback state."""
        return STATES.get(self._data.get("state"))

    @property
    def media_title(self) -> str | None:
        """Title of the current media."""
        return self._data.get("title")

    @property
    def media_artist(self) -> str | None:
        """Artist (or channel) of the current media."""
        return self._data.get("artist")

    @property
    def media_album_name(self) -> str | None:
        """Album of the current media."""
        return self._data.get("album")

    @property
    def media_duration(self) -> float | None:
        """Duration in seconds."""
        duration = self._data.get("duration_ms")
        return duration / 1000 if duration else None

    @property
    def media_position(self) -> float | None:
        """Position in seconds, as of media_position_updated_at."""
        position = self._data.get("position_ms")
        return position / 1000 if position is not None else None

    @property
    def media_position_updated_at(self) -> datetime | None:
        """When the position was valid."""
        if self._data.get("position_ms") is None:
            return None
        return self._data.get("received_at")

    @property
    def app_id(self) -> str | None:
        """Package of the playing app."""
        return self._data.get("app")

    @property
    def app_name(self) -> str | None:
        """Name of the playing app."""
        return self._data.get("app_label")

    @property
    def volume_level(self) -> float | None:
        """Volume 0..1."""
        return self._data.get("volume")

    @property
    def is_volume_muted(self) -> bool | None:
        """Whether the volume is muted."""
        return self._data.get("muted")

    async def _command(self, command: str, **args: Any) -> None:
        try:
            await self.coordinator.client.command(command, **args)
        except MediaBridgeError as err:
            raise HomeAssistantError(f"{command} failed: {err}") from err

    async def async_media_play(self) -> None:
        """Play."""
        await self._command("play")

    async def async_media_pause(self) -> None:
        """Pause."""
        await self._command("pause")

    async def async_media_stop(self) -> None:
        """Stop."""
        await self._command("stop")

    async def async_media_next_track(self) -> None:
        """Next."""
        await self._command("next")

    async def async_media_previous_track(self) -> None:
        """Previous."""
        await self._command("previous")

    async def async_media_seek(self, position: float) -> None:
        """Seek to a position in seconds."""
        await self._command("seek", position_ms=int(position * 1000))

    async def async_set_volume_level(self, volume: float) -> None:
        """Set the volume 0..1."""
        await self._command("volume_set", level=volume)

    async def async_volume_up(self) -> None:
        """One step up."""
        await self._command("volume_up")

    async def async_volume_down(self) -> None:
        """One step down."""
        await self._command("volume_down")

    async def async_mute_volume(self, mute: bool) -> None:
        """Mute or unmute."""
        await self._command("mute", muted=mute)
