"""Media player entity for Media Bridge."""

from __future__ import annotations

import base64
from datetime import datetime
from typing import Any

import voluptuous as vol

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv, entity_platform
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .const import HOME_SOURCE, LOGGER
from .coordinator import MediaBridgeConfigEntry
from .entity import MediaBridgeEntity

SERVICE_SHOW_NOTIFICATION = "show_notification"

ACTION_SCHEMA = vol.Any(
    cv.string,
    vol.Schema({vol.Required("id"): cv.string, vol.Optional("title"): cv.string}),
)

SHOW_NOTIFICATION_SCHEMA = {
    vol.Required("message"): cv.string,
    vol.Optional("title"): cv.string,
    vol.Optional("image_entity_id"): cv.entity_domain("camera"),
    vol.Optional("image_url"): cv.url,
    vol.Optional("duration", default=10): vol.All(vol.Coerce(int), vol.Range(min=2, max=300)),
    vol.Optional("actions", default=[]): vol.All(cv.ensure_list, [ACTION_SCHEMA], vol.Length(max=3)),
    vol.Optional("tag"): cv.string,
    vol.Optional("pause_playback", default=False): cv.boolean,
}

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
    platform = entity_platform.async_get_current_platform()
    platform.async_register_entity_service(
        SERVICE_SHOW_NOTIFICATION, SHOW_NOTIFICATION_SCHEMA, "async_show_notification"
    )


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
        """Seeking only where the playing app supports it; sources once the app list is known."""
        features = BASE_FEATURES
        if self._data.get("can_seek"):
            features |= MediaPlayerEntityFeature.SEEK
        if self.coordinator.apps:
            features |= MediaPlayerEntityFeature.SELECT_SOURCE
        return features

    @property
    def source_list(self) -> list[str] | None:
        """Launchable apps, plus the home screen."""
        if not self.coordinator.apps:
            return None
        return [HOME_SOURCE, *(app["label"] for app in self.coordinator.apps)]

    @property
    def source(self) -> str | None:
        """App in the foreground (falls back to the app that owns the media session)."""
        return self._data.get("foreground_label") or self._data.get("app_label")

    async def async_select_source(self, source: str) -> None:
        """Bring the launcher or an app to the front."""
        if source == HOME_SOURCE:
            await self._command("home")
            return
        package = next((a["package"] for a in self.coordinator.apps if a["label"] == source), None)
        if package is None:
            raise ServiceValidationError(f"Unknown app: {source}")
        await self._command("launch", package=package)

    @property
    def media_image_url(self) -> str | None:
        """Cover URL when the app provides one on the web."""
        return self._data.get("artwork_url")

    @property
    def media_image_remotely_accessible(self) -> bool:
        """Web covers can be loaded by the browser directly."""
        return self._data.get("artwork_url") is not None

    @property
    def media_image_hash(self) -> str | None:
        """Changes whenever the cover changes, so the frontend reloads it."""
        if self._data.get("state") in (None, "off"):
            return None
        return self._data.get("artwork_id")

    async def async_get_media_image(self) -> tuple[bytes | None, str | None]:
        """Cover bitmaps are served by the device API."""
        if self.media_image_url:
            return await super().async_get_media_image()
        try:
            data = await self.coordinator.client.artwork()
        except MediaBridgeError:
            return None, None
        return (data, "image/jpeg") if data else (None, None)

    async def async_show_notification(
        self,
        message: str,
        duration: int,
        actions: list,
        pause_playback: bool,
        title: str | None = None,
        image_entity_id: str | None = None,
        image_url: str | None = None,
        tag: str | None = None,
    ) -> None:
        """Card on the screen, optionally with an image, buttons and pausing playback."""
        image = await self._async_load_image(image_entity_id, image_url)
        buttons = [
            {"id": a, "title": a} if isinstance(a, str) else {"id": a["id"], "title": a.get("title", a["id"])}
            for a in actions
        ]
        try:
            await self.coordinator.client.notify(
                message,
                title,
                duration * 1000,
                image=base64.b64encode(image).decode() if image else None,
                actions=buttons,
                tag=tag,
                pause=pause_playback,
            )
        except MediaBridgeError as err:
            raise HomeAssistantError(f"Notification failed: {err}") from err

    async def _async_load_image(self, entity_id: str | None, url: str | None) -> bytes | None:
        """Snapshot of a camera or a fetched URL; the device itself can't reach either with auth."""
        try:
            if entity_id:
                # Imported on use: the camera component pulls in image libraries.
                from homeassistant.components.camera import async_get_image  # noqa: PLC0415

                return (await async_get_image(self.hass, entity_id, width=800)).content
            if url:
                session = async_get_clientsession(self.hass)
                async with session.get(url, timeout=10) as resp:
                    resp.raise_for_status()
                    return await resp.read()
        except Exception as err:  # noqa: BLE001 - an image must never block the notification
            LOGGER.warning("Notification image not available: %s", err)
        return None

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
