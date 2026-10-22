"""Power, picture mode, screensaver and sleep-after-current."""

from unittest.mock import MagicMock, patch

from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.media_bridge.const import DOMAIN
from custom_components.media_bridge.wol import normalize_mac

from .test_init import PLAYING


async def _setup(hass: HomeAssistant, mac: str | None = "50:ba:02:33:20:e6") -> MockConfigEntry:
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="dev-1",
        title="Beamer",
        data={"host": "10.0.1.21", "port": 8765, "token": "t", "webhook_id": "hook"},
        options={"mac": mac} if mac else {},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def _push(hass, client, data):
    await client.post("/api/webhook/hook", json=data)
    await hass.async_block_till_done()


def test_normalize_mac() -> None:
    """Accepts common notations."""
    assert normalize_mac("50-BA-02-33-20-E6") == "50:ba:02:33:20:e6"
    assert normalize_mac("50ba023320e6") == "50:ba:02:33:20:e6"
    assert normalize_mac("nope") is None


async def test_turn_on_sends_magic_packet(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """turn_on wakes via WoL; turn_off sends the device to sleep."""
    await _setup(hass)
    with patch("custom_components.media_bridge.wol._send") as send:
        await hass.services.async_call(
            "media_player", "turn_on", {"entity_id": "media_player.beamer"}, blocking=True
        )
    mac, targets = send.call_args.args
    assert mac == "50:ba:02:33:20:e6"
    assert "10.0.1.255" in targets
    await hass.services.async_call("media_player", "turn_off", {"entity_id": "media_player.beamer"}, blocking=True)
    mock_client.command.assert_awaited_with("sleep")


async def test_unreachable_is_off_with_mac(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """A sleeping device shows as off (with a turn_on button), not unavailable."""
    from custom_components.media_bridge.api import MediaBridgeError

    mock_client.state.side_effect = MediaBridgeError("timeout")
    await _setup(hass)
    assert hass.states.get("media_player.beamer").state == "off"


async def test_picture_mode_and_screensaver(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Picture mode select and screensaver follow the pushed state."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await _push(hass, client, {**PLAYING, "picture_mode": 7, "dreaming": True})
    assert hass.states.get("select.beamer_picture_mode").state == "standard"
    assert hass.states.get("binary_sensor.beamer_screensaver").state == "on"
    await hass.services.async_call(
        "select", "select_option", {"entity_id": "select.beamer_picture_mode", "option": "movie"}, blocking=True
    )
    mock_client.command.assert_awaited_with("set_setting", key="picture_mode", value=9)


async def test_sleep_after_current(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Pausing doesn't trigger; the next title starting pauses and sleeps."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await _push(hass, client, PLAYING)
    await hass.services.async_call(
        "switch", "turn_on", {"entity_id": "switch.beamer_sleep_after_this_item"}, blocking=True
    )
    assert hass.states.get("switch.beamer_sleep_after_this_item").state == "on"

    await _push(hass, client, {**PLAYING, "state": "paused"})
    assert hass.states.get("switch.beamer_sleep_after_this_item").state == "on"
    mock_client.command.assert_not_awaited()

    events = []
    hass.bus.async_listen("media_bridge_sleep_timer_finished", lambda e: events.append(e))
    await _push(hass, client, {**PLAYING, "title": "Nächstes Video"})
    await hass.async_block_till_done()
    calls = [c.args[0] for c in mock_client.command.await_args_list]
    assert calls == ["pause", "sleep"]
    assert hass.states.get("switch.beamer_sleep_after_this_item").state == "off"
    assert events[0].data["reason"] == "next_item"
