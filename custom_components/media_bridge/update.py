"""Update the app on the device from Home Assistant (latest GitHub release)."""

from __future__ import annotations

import asyncio
from datetime import timedelta
from typing import Any

import aiohttp

from homeassistant.components.update import UpdateDeviceClass, UpdateEntity, UpdateEntityFeature
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .const import APK_ASSET, GITHUB_REPO, LOGGER, signal_update
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 1
SCAN_INTERVAL = timedelta(hours=6)
INSTALL_TIMEOUT = 600
RELEASE_URL = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the app update entity."""
    async_add_entities([MediaBridgeAppUpdate(entry.runtime_data)])


class MediaBridgeAppUpdate(MediaBridgeEntity, UpdateEntity):
    """The Media Bridge app on the device; installing downloads the release APK on the device itself."""

    _attr_translation_key = "app"
    _attr_device_class = UpdateDeviceClass.FIRMWARE
    _attr_entity_category = EntityCategory.CONFIG
    _attr_supported_features = (
        UpdateEntityFeature.INSTALL | UpdateEntityFeature.PROGRESS | UpdateEntityFeature.RELEASE_NOTES
    )
    _attr_title = "Media Bridge"

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the update entity."""
        super().__init__(coordinator, "app_update")
        self._release: dict[str, Any] | None = None
        self._done: asyncio.Future[str | None] | None = None

    @property
    def should_poll(self) -> bool:
        """Releases are polled (the device state itself is pushed)."""
        return True

    @property
    def installed_version(self) -> str | None:
        """App version running on the device."""
        data = self.coordinator.data or {}
        return data.get("version") or self.coordinator.config_entry.data.get("version")

    @property
    def latest_version(self) -> str | None:
        """Version of the latest release."""
        if not self._release:
            return self.installed_version
        return str(self._release.get("tag_name", "")).lstrip("v") or self.installed_version

    @property
    def release_url(self) -> str | None:
        """Release page."""
        return self._release.get("html_url") if self._release else None

    async def async_release_notes(self) -> str | None:
        """Release notes from GitHub."""
        return self._release.get("body") if self._release else None

    async def async_update(self) -> None:
        """Fetch the latest release from GitHub."""
        session = async_get_clientsession(self.hass)
        try:
            async with asyncio.timeout(15):
                async with session.get(RELEASE_URL, headers={"Accept": "application/vnd.github+json"}) as resp:
                    if resp.status != 200:
                        LOGGER.debug("GitHub release check failed: HTTP %s", resp.status)
                        return
                    self._release = await resp.json()
        except (aiohttp.ClientError, TimeoutError, ValueError) as err:
            LOGGER.debug("GitHub release check failed: %s", err)
        except Exception as err:  # noqa: BLE001 - a failed check must never break the entity
            LOGGER.debug("GitHub release check failed: %s", err)

    def _apk_url(self) -> str | None:
        for asset in (self._release or {}).get("assets", []):
            if asset.get("name") == APK_ASSET:
                return asset.get("browser_download_url")
        return None

    async def async_install(self, version: str | None, backup: bool, **kwargs: Any) -> None:
        """Let the device download and install the release APK."""
        url = self._apk_url()
        if not url:
            raise HomeAssistantError("The latest release has no APK")
        self._done = self.hass.loop.create_future()
        try:
            await self.coordinator.client.command("install_update", url=url)
        except MediaBridgeError as err:
            raise HomeAssistantError(
                f"The device refused the update ({err}). Is installing allowed? "
                "adb shell appops set de.finnwessel.mediabridge REQUEST_INSTALL_PACKAGES allow"
            ) from err
        # Download, (on first update) confirmation on screen, install and restart of the app.
        try:
            async with asyncio.timeout(INSTALL_TIMEOUT):
                error = await self._done
        except TimeoutError as err:
            raise HomeAssistantError(
                "The update did not finish in time. Was the installation confirmed on the screen?"
            ) from err
        finally:
            self._done = None
        if error:
            raise HomeAssistantError(f"Update failed on the device: {error}")

    async def async_added_to_hass(self) -> None:
        """Follow progress events from the device."""
        await super().async_added_to_hass()
        self.hass.async_create_background_task(self._async_first_check(), "media_bridge release check")
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, signal_update(self.coordinator.config_entry.entry_id), self._handle_progress
            )
        )

    async def _async_first_check(self) -> None:
        await self.async_update()
        self.async_write_ha_state()

    def _finish(self, error: str | None) -> None:
        if self._done is not None and not self._done.done():
            self._done.set_result(error)

    @callback
    def _handle_progress(self, status: str | None, detail: str | None) -> None:
        LOGGER.debug("App update: %s %s", status, detail or "")
        if status == "failed":
            self._finish(detail or "unknown error")
        elif status == "installed":
            self._finish(None)

    @callback
    def _handle_coordinator_update(self) -> None:
        # The app restarts with the new version: done.
        if self.installed_version == self.latest_version:
            self._finish(None)
        super()._handle_coordinator_update()
