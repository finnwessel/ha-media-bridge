"""App logos for dashboards: GET /api/media_bridge/icon/<media player>?app=<label or package>."""

from __future__ import annotations

from http import HTTPStatus

from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er

from .api import MediaBridgeError
from .const import DOMAIN


class AppIconView(HomeAssistantView):
    """Serves app logos from the device, so cards can show them (needs auth or a signed path)."""

    url = "/api/media_bridge/icon/{entity_id}"
    name = "api:media_bridge:icon"

    def __init__(self, hass: HomeAssistant) -> None:
        """Initialize the view."""
        self.hass = hass

    async def get(self, request: web.Request, entity_id: str) -> web.Response:
        """Return the PNG logo of an app."""
        app = request.query.get("app")
        if not app:
            return web.Response(status=HTTPStatus.BAD_REQUEST)
        entity = er.async_get(self.hass).async_get(entity_id)
        if entity is None or entity.platform != DOMAIN or entity.config_entry_id is None:
            return web.Response(status=HTTPStatus.NOT_FOUND)
        entry = self.hass.config_entries.async_get_entry(entity.config_entry_id)
        coordinator = getattr(entry, "runtime_data", None) if entry else None
        if coordinator is None:
            return web.Response(status=HTTPStatus.NOT_FOUND)
        try:
            icon = await coordinator.async_app_icon(
                coordinator.package_for(app), banner=request.query.get("kind") != "icon"
            )
        except MediaBridgeError:
            return web.Response(status=HTTPStatus.SERVICE_UNAVAILABLE)
        if icon is None:
            return web.Response(status=HTTPStatus.NOT_FOUND)
        return web.Response(
            body=icon, content_type="image/png", headers={"Cache-Control": "private, max-age=86400"}
        )
