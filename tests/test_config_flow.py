"""Config flow tests."""

from ipaddress import ip_address
from unittest.mock import AsyncMock, patch

from homeassistant import config_entries
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.media_bridge.api import MediaBridgeAuthError, MediaBridgeError
from custom_components.media_bridge.const import DOMAIN

from .conftest import INFO

FLOW_CLIENT = "custom_components.media_bridge.config_flow.MediaBridgeClient"
SETUP = "custom_components.media_bridge.async_setup_entry"


def _flow_client(cls):
    client = cls.return_value
    client.info = AsyncMock(return_value=INFO)
    client.pair_start = AsyncMock()
    client.pair_finish = AsyncMock(
        side_effect=[MediaBridgeAuthError("invalid code", 401), {**INFO, "token": "t0k"}]
    )
    return client


async def test_user_flow_with_pairing(hass: HomeAssistant) -> None:
    """Manual host, wrong code, right code."""
    with patch(FLOW_CLIENT) as cls, patch(SETUP, return_value=True):
        client = _flow_client(cls)
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_USER}
        )
        assert result["type"] is FlowResultType.FORM
        assert result["step_id"] == "user"

        result = await hass.config_entries.flow.async_configure(
            result["flow_id"], {"host": "10.0.1.137", "port": 8765}
        )
        assert result["step_id"] == "pair"
        client.pair_start.assert_awaited_once()

        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "000000"})
        assert result["errors"] == {"base": "invalid_code"}

        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "123456"})
        assert result["type"] is FlowResultType.CREATE_ENTRY
        assert result["title"] == "Beamer"
        assert result["data"]["token"] == "t0k"
        assert result["data"]["host"] == "10.0.1.137"
        assert result["result"].unique_id == "dev-1"


async def test_user_flow_cannot_connect(hass: HomeAssistant) -> None:
    """Unreachable host shows an error."""
    with patch(FLOW_CLIENT) as cls:
        cls.return_value.info = AsyncMock(side_effect=MediaBridgeError("refused"))
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_USER}
        )
        result = await hass.config_entries.flow.async_configure(
            result["flow_id"], {"host": "10.0.1.99", "port": 8765}
        )
        assert result["errors"] == {"base": "cannot_connect"}


async def test_empty_code_shows_new_one(hass: HomeAssistant) -> None:
    """Submitting without a code requests a fresh one."""
    with patch(FLOW_CLIENT) as cls:
        client = _flow_client(cls)
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_USER}
        )
        result = await hass.config_entries.flow.async_configure(
            result["flow_id"], {"host": "10.0.1.137", "port": 8765}
        )
        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": ""})
        assert result["step_id"] == "pair"
        assert client.pair_start.await_count == 2


def _zeroconf(ip: str = "10.0.1.137") -> ZeroconfServiceInfo:
    return ZeroconfServiceInfo(
        ip_address=ip_address(ip),
        ip_addresses=[ip_address(ip)],
        hostname="beamer.local.",
        name="Beamer._mediabridge._tcp.local.",
        port=8765,
        type="_mediabridge._tcp.local.",
        properties={"id": "dev-1", "version": "0.2.0", "api": "1"},
    )


async def test_zeroconf_flow(hass: HomeAssistant) -> None:
    """Discovered device: confirm, then pair."""
    with patch(FLOW_CLIENT) as cls, patch(SETUP, return_value=True):
        client = _flow_client(cls)
        client.pair_finish.side_effect = None
        client.pair_finish.return_value = {**INFO, "token": "t0k"}
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=_zeroconf()
        )
        assert result["step_id"] == "discovery_confirm"
        result = await hass.config_entries.flow.async_configure(result["flow_id"], {})
        assert result["step_id"] == "pair"
        result = await hass.config_entries.flow.async_configure(result["flow_id"], {"code": "123456"})
        assert result["type"] is FlowResultType.CREATE_ENTRY


async def test_zeroconf_updates_host_of_known_device(hass: HomeAssistant) -> None:
    """A known device with a new IP updates the entry instead of a new flow."""
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="dev-1",
        data={"host": "10.0.1.50", "port": 8765, "token": "t"},
    )
    entry.add_to_hass(hass)
    with patch(SETUP, return_value=True):
        result = await hass.config_entries.flow.async_init(
            DOMAIN, context={"source": config_entries.SOURCE_ZEROCONF}, data=_zeroconf()
        )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"
    assert entry.data["host"] == "10.0.1.137"
