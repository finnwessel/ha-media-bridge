"""State handling for one Media Bridge device: pushed by webhook, polled as fallback."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed
from homeassistant.util import dt as dt_util

from .api import MediaBridgeAuthError, MediaBridgeClient, MediaBridgeError
from .const import APPS_INTERVAL, EVENT_NOTIFICATION_ACTION, LOGGER, POLL_INTERVAL, signal_key

type MediaBridgeConfigEntry = ConfigEntry[MediaBridgeCoordinator]


class MediaBridgeCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Holds the latest snapshot of a device."""

    config_entry: MediaBridgeConfigEntry

    def __init__(
        self,
        hass: HomeAssistant,
        entry: MediaBridgeConfigEntry,
        client: MediaBridgeClient,
    ) -> None:
        """Initialize the coordinator."""
        super().__init__(
            hass,
            LOGGER,
            config_entry=entry,
            name=entry.title,
            update_interval=POLL_INTERVAL,
        )
        self.client = client
        self.webhook_url: str | None = None
        self.apps: list[dict[str, str]] = []
        self._apps_fetched: datetime | None = None

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            data = await self.client.state()
            if self.webhook_url and data.get("webhook_url") != self.webhook_url:
                LOGGER.debug("Sending webhook URL to %s", self.config_entry.title)
                await self.client.set_webhook(self.webhook_url)
        except MediaBridgeAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except MediaBridgeError as err:
            raise UpdateFailed(f"Device not reachable: {err}") from err
        await self._async_refresh_apps()
        return self._stamp(data)

    async def _async_refresh_apps(self) -> None:
        """App list for the source selector; rarely changes, so hourly is enough."""
        now = dt_util.utcnow()
        if self._apps_fetched and now - self._apps_fetched < APPS_INTERVAL:
            return
        try:
            self.apps = await self.client.apps()
            self._apps_fetched = now
        except MediaBridgeError as err:
            LOGGER.debug("Could not fetch app list: %s", err)

    def async_push(self, data: dict[str, Any]) -> None:
        """Handle a state pushed by the device."""
        if data.get("id") not in (None, self.config_entry.unique_id):
            LOGGER.warning("Ignoring push from unexpected device %s", data.get("id"))
            return
        if event := data.get("event"):
            self._handle_event(event, data)
            return
        self.async_set_updated_data(self._stamp(data))

    def _handle_event(self, event: str, data: dict[str, Any]) -> None:
        """One-off events from the device: key presses and notification answers."""
        entry = self.config_entry
        if event == "key":
            async_dispatcher_send(self.hass, signal_key(entry.entry_id), data.get("key"), data.get("press"))
        elif event == "notification_action":
            registry = dr.async_get(self.hass)
            devices = dr.async_entries_for_config_entry(registry, entry.entry_id)
            self.hass.bus.async_fire(
                EVENT_NOTIFICATION_ACTION,
                {
                    "device_id": devices[0].id if devices else None,
                    "config_entry_id": entry.entry_id,
                    "name": entry.title,
                    "action": data.get("action"),
                    "tag": data.get("tag"),
                },
            )
        else:
            LOGGER.debug("Ignoring unknown event %s", event)

    def _stamp(self, data: dict[str, Any]) -> dict[str, Any]:
        self._sync_version(data.get("version"))
        # The device extrapolates its position to the moment it sends, so the
        # time of arrival is the reference for position and end time.
        data["received_at"] = dt_util.utcnow()
        return data

    def _sync_version(self, version: str | None) -> None:
        """Keep the app version shown on the device page current after app updates."""
        entry = self.config_entry
        if not version or version == entry.data.get("version"):
            return
        self.hass.config_entries.async_update_entry(entry, data={**entry.data, "version": version})
        registry = dr.async_get(self.hass)
        for device in dr.async_entries_for_config_entry(registry, entry.entry_id):
            registry.async_update_device(device.id, sw_version=version)
