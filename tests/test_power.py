"""Power, picture mode, screensaver and sleep-after-current."""

import asyncio

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


async def test_app_logo_without_cover(hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth) -> None:
    """Without a cover the playing app's TV banner is shown; the launcher gets no logo."""
    entity = "media_player.beamer"
    await _setup(hass)
    # Idle on the launcher: no picture.
    assert "entity_picture" not in hass.states.get(entity).attributes

    client = await hass_client_no_auth()
    await _push(hass, client, {**PLAYING, "artwork_id": None})
    picture = hass.states.get(entity).attributes["entity_picture"]
    resp = await client.get(picture)
    assert resp.status == 200
    assert resp.content_type == "image/png"
    mock_client.app_icon.assert_awaited_with("com.google.android.youtube.tv", banner=True)

    # With a cover, the cover wins.
    await _push(hass, client, {**PLAYING, "artwork_id": "abc"})
    resp = await client.get(hass.states.get(entity).attributes["entity_picture"])
    assert resp.content_type == "image/jpeg"


async def test_remote_button_names(hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth) -> None:
    """Vendor keys get readable names."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await _push(hass, client, {"id": "dev-1", "event": "key", "key": "button_4", "press": "short_press"})
    state = hass.states.get("event.beamer_remote")
    assert state.attributes["key"] == "button_4"
    assert state.attributes["button"] == "netflix"


async def test_app_icon_view(hass: HomeAssistant, mock_client: MagicMock, hass_client) -> None:
    """Dashboards get app logos by label; results are cached."""
    await _setup(hass)
    client = await hass_client()
    resp = await client.get("/api/media_bridge/icon/media_player.beamer?app=Netflix")
    assert resp.status == 200
    assert await resp.read() == b"png-bytes"
    mock_client.app_icon.assert_awaited_once_with("com.netflix.ninja", banner=True)
    await client.get("/api/media_bridge/icon/media_player.beamer?app=com.netflix.ninja")
    assert mock_client.app_icon.await_count == 1
    resp = await client.get("/api/media_bridge/icon/sensor.nope?app=Netflix")
    assert resp.status == 404


DIAG = {
    "display": {"width": 3840, "height": 2160, "refresh_rate": 23.98, "hdr_types": ["hdr10", "hlg"]},
    "bluetooth": [{"name": "Kopfhörer", "address": "AA", "type": "headphones", "battery": 80}],
    "diagnostics": {
        "network": "wifi",
        "wifi_rssi": -58,
        "wifi_link_mbps": 433,
        "wifi_frequency": 5180,
        "storage_free": 12_345_678_901,
        "storage_total": 32_000_000_000,
        "booted_at": 1_792_600_000_000,
        "permissions": {"overlay": True, "accessibility": False, "device_admin": True,
                        "secure_settings": True, "bluetooth": True, "install": True},
    },
}


async def test_diagnostics_and_permissions(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Output format, Bluetooth, diagnostics and a repair issue per missing permission."""
    from homeassistant.helpers import issue_registry as ir

    entry = await _setup(hass)
    client = await hass_client_no_auth()
    await _push(hass, client, {**PLAYING, **DIAG})
    assert hass.states.get("sensor.beamer_resolution").state == "2160p"
    assert float(hass.states.get("sensor.beamer_refresh_rate").state) == 23.98
    bt = hass.states.get("sensor.beamer_bluetooth_devices")
    assert bt.state == "1"
    assert bt.attributes["devices"] == ["Kopfhörer"]
    assert hass.states.get("sensor.beamer_wi_fi_signal").state == "-58"
    assert hass.states.get("binary_sensor.beamer_permissions") is None  # disabled by default
    registry = ir.async_get(hass)
    assert registry.async_get_issue(DOMAIN, f"{entry.entry_id}_accessibility")
    assert not registry.async_get_issue(DOMAIN, f"{entry.entry_id}_overlay")

    fixed = {**DIAG["diagnostics"], "permissions": {**DIAG["diagnostics"]["permissions"], "accessibility": True}}
    await _push(hass, client, {**PLAYING, **DIAG, "diagnostics": fixed})
    assert not registry.async_get_issue(DOMAIN, f"{entry.entry_id}_accessibility")


async def test_app_update(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth, aioclient_mock
) -> None:
    """The update entity offers the latest release and lets the device install its APK."""
    from custom_components.media_bridge.update import RELEASE_URL

    apk = "https://github.com/finnwessel/ha-media-bridge/releases/download/v9.0.0/ha-media-bridge.apk"
    aioclient_mock.get(
        RELEASE_URL,
        json={"tag_name": "v9.0.0", "html_url": "https://example/r", "body": "Notes",
              "assets": [{"name": "ha-media-bridge.apk", "browser_download_url": apk}]},
    )
    await _setup(hass)
    client = await hass_client_no_auth()
    await _push(hass, client, {**PLAYING, "version": "0.7.0"})
    await hass.async_block_till_done()
    state = hass.states.get("update.beamer_app")
    assert state.state == "on"
    assert state.attributes["latest_version"] == "9.0.0"
    task = hass.async_create_task(
        hass.services.async_call("update", "install", {"entity_id": "update.beamer_app"}, blocking=True)
    )
    for _ in range(20):
        await asyncio.sleep(0)
    mock_client.command.assert_awaited_with("install_update", url=apk)
    assert hass.states.get("update.beamer_app").attributes["in_progress"] is True
    # The app comes back with the new version.
    await _push(hass, client, {**PLAYING, "version": "9.0.0"})
    await task
    state = hass.states.get("update.beamer_app")
    assert state.attributes["in_progress"] is False
    assert state.state == "off"
