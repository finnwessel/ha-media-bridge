"""The Media Bridge integration: Android (TV) media state and control, pushed in real time."""

from __future__ import annotations

from aiohttp import web

from homeassistant.components import network, webhook
from homeassistant.const import CONF_HOST, CONF_PORT, CONF_TOKEN, CONF_WEBHOOK_ID, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .api import MediaBridgeClient
from .const import DOMAIN, LOGGER
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator

PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.EVENT,
    Platform.MEDIA_PLAYER,
    Platform.NOTIFY,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
]


async def async_setup_entry(hass: HomeAssistant, entry: MediaBridgeConfigEntry) -> bool:
    """Set up a paired device."""
    client = MediaBridgeClient(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_TOKEN],
    )
    coordinator = MediaBridgeCoordinator(hass, entry, client)
    entry.runtime_data = coordinator

    webhook_id = entry.data.get(CONF_WEBHOOK_ID)
    if not webhook_id:
        webhook_id = webhook.async_generate_id()
        hass.config_entries.async_update_entry(
            entry, data={**entry.data, CONF_WEBHOOK_ID: webhook_id}
        )

    async def handle_webhook(
        hass: HomeAssistant, webhook_id: str, request: web.Request
    ) -> web.Response:
        try:
            data = await request.json()
        except ValueError:
            return web.Response(status=400)
        if not isinstance(data, dict):
            return web.Response(status=400)
        coordinator.async_push(data)
        return web.Response(status=200)

    webhook.async_register(
        hass,
        DOMAIN,
        entry.title,
        webhook_id,
        handle_webhook,
        local_only=True,
        allowed_methods=["POST"],
    )
    entry.async_on_unload(lambda: webhook.async_unregister(hass, webhook_id))

    coordinator.webhook_url = await _async_webhook_url(hass, entry.data[CONF_HOST], webhook_id)

    # Don't fail setup when the device is off: entities start unavailable and
    # come alive on the first push or poll.
    await coordinator.async_refresh()

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_options_updated))
    return True


async def _async_options_updated(hass: HomeAssistant, entry: MediaBridgeConfigEntry) -> None:
    """MAC changed: reload so the power features follow."""
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: MediaBridgeConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def _async_webhook_url(hass: HomeAssistant, device_host: str, webhook_id: str) -> str | None:
    """URL under which the device reaches this webhook.

    Prefers the IP of the interface that faces the device, because Android
    resolves `homeassistant.local` unreliably.
    """
    path = webhook.async_generate_path(webhook_id)
    api = hass.config.api
    if api is not None and not api.use_ssl:
        source_ip = await network.async_get_source_ip(hass, target_ip=device_host)
        return f"http://{source_ip}:{api.port}{path}"
    try:
        return get_url(hass, allow_external=False, allow_cloud=False) + path
    except NoURLAvailableError:
        LOGGER.warning("No internal URL configured; the device can't push updates and will be polled")
        return None
