"""Config flow for Media Bridge: discovery or manual host, then on-screen pairing code."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import (
    SOURCE_REAUTH,
    ConfigEntry,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT, CONF_TOKEN
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from .api import MediaBridgeAuthError, MediaBridgeClient, MediaBridgeError
from .const import CONF_CODE, CONF_MAC, DEFAULT_PORT, DOMAIN
from .wol import normalize_mac


class MediaBridgeConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Media Bridge."""

    VERSION = 1

    def __init__(self) -> None:
        """Initialize the flow."""
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str | None = None

    @staticmethod
    def async_get_options_flow(config_entry: ConfigEntry) -> MediaBridgeOptionsFlow:
        """Options: MAC address for switching on via Wake-on-LAN."""
        return MediaBridgeOptionsFlow()

    async def async_step_reconfigure(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Change the address of a paired device."""
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            self._host = user_input[CONF_HOST].strip()
            self._port = user_input[CONF_PORT]
            try:
                info = await self._client().info()
            except MediaBridgeError:
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_mismatch(reason="wrong_device")
                return self.async_update_reload_and_abort(
                    entry, data_updates={CONF_HOST: self._host, CONF_PORT: self._port}
                )
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST, default=entry.data[CONF_HOST]): str,
                    vol.Required(CONF_PORT, default=entry.data[CONF_PORT]): int,
                }
            ),
            errors=errors,
        )

    def _client(self) -> MediaBridgeClient:
        assert self._host is not None
        return MediaBridgeClient(async_get_clientsession(self.hass), self._host, self._port)

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Manual setup by host."""
        errors: dict[str, str] = {}
        if user_input is not None:
            self._host = user_input[CONF_HOST].strip()
            self._port = user_input[CONF_PORT]
            try:
                info = await self._client().info()
            except MediaBridgeError:
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_configured(
                    updates={CONF_HOST: self._host, CONF_PORT: self._port}
                )
                self._name = info.get("name")
                return await self.async_step_pair()

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST): str,
                    vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                }
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """Device announced itself via mDNS."""
        if discovery_info.ip_address.version != 4:
            return self.async_abort(reason="not_ipv4")
        device_id = discovery_info.properties.get("id")
        if not device_id:
            return self.async_abort(reason="invalid_discovery")
        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured(
            updates={CONF_HOST: str(discovery_info.ip_address), CONF_PORT: discovery_info.port}
        )
        self._host = str(discovery_info.ip_address)
        self._port = discovery_info.port or DEFAULT_PORT
        try:
            info = await self._client().info()
        except MediaBridgeError:
            return self.async_abort(reason="cannot_connect")
        self._name = info.get("name") or discovery_info.name
        self.context["title_placeholders"] = {CONF_NAME: self._name}
        return await self.async_step_discovery_confirm()

    async def async_step_discovery_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Confirm a discovered device before showing a code on its screen."""
        if user_input is not None:
            return await self.async_step_pair()
        self._set_confirm_only()
        return self.async_show_form(
            step_id="discovery_confirm",
            description_placeholders={CONF_NAME: self._name or self._host or ""},
        )

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Show a code on the device and ask for it. An empty code shows a new one."""
        errors: dict[str, str] = {}
        code = str((user_input or {}).get(CONF_CODE, "")).strip()
        if code:
            try:
                result = await self._client().pair_finish(code)
            except MediaBridgeAuthError:
                errors["base"] = "invalid_code"
            except MediaBridgeError:
                errors["base"] = "cannot_connect"
            else:
                return await self._async_finish(result)
        else:
            try:
                await self._client().pair_start()
            except MediaBridgeError as err:
                # 429: a code was requested a moment ago and is still on screen.
                if err.status != 429:
                    errors["base"] = "cannot_connect"

        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Optional(CONF_CODE, default=""): str}),
            errors=errors,
            description_placeholders={CONF_NAME: self._name or self._host or ""},
        )

    async def _async_finish(self, result: dict[str, Any]) -> ConfigFlowResult:
        if result.get("id") != self.unique_id:
            return self.async_abort(reason="wrong_device")
        data = {
            CONF_HOST: self._host,
            CONF_PORT: self._port,
            CONF_TOKEN: result["token"],
            "manufacturer": result.get("manufacturer"),
            "model": result.get("model"),
            "version": result.get("version"),
        }
        if self.source == SOURCE_REAUTH:
            return self.async_update_reload_and_abort(self._get_reauth_entry(), data_updates=data)
        return self.async_create_entry(title=result.get("name") or self._name or self._host, data=data)

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        """The token was rejected (e.g. app data cleared): pair again."""
        entry = self._get_reauth_entry()
        self._host = entry_data[CONF_HOST]
        self._port = entry_data[CONF_PORT]
        self._name = entry.title
        await self.async_set_unique_id(entry.unique_id)
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Confirm before showing a new code on screen."""
        if user_input is not None:
            return await self.async_step_pair()
        return self.async_show_form(
            step_id="reauth_confirm",
            description_placeholders={CONF_NAME: self._name or ""},
        )


class MediaBridgeOptionsFlow(OptionsFlow):
    """MAC address used to switch the device on."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Edit the options."""
        errors: dict[str, str] = {}
        if user_input is not None:
            raw = (user_input.get(CONF_MAC) or "").strip()
            mac = normalize_mac(raw) if raw else ""
            if mac is None:
                errors[CONF_MAC] = "invalid_mac"
            else:
                return self.async_create_entry(data={CONF_MAC: mac})
        current = self.config_entry.options.get(CONF_MAC, "")
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema({vol.Optional(CONF_MAC, default=current): str}),
            errors=errors,
        )
