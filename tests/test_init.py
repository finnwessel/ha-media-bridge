"""Setup, push, entities and commands."""

from unittest.mock import MagicMock

from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.media_bridge.const import DOMAIN

PLAYING = {
    "id": "dev-1",
    "state": "playing",
    "screen_on": True,
    "app": "com.google.android.youtube.tv",
    "app_label": "YouTube",
    "title": "Nordreportage",
    "artist": "NDR Doku",
    "album": None,
    "duration_ms": 1_800_000,
    "position_ms": 600_000,
    "can_seek": True,
    "foreground_app": "com.google.android.youtube.tv",
    "foreground_label": "YouTube",
    "volume": 0.4,
    "muted": False,
}


async def _setup(hass: HomeAssistant) -> MockConfigEntry:
    assert await async_setup_component(hass, "http", {})
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="dev-1",
        title="Beamer",
        data={"host": "10.0.1.137", "port": 8765, "token": "t", "webhook_id": "hook"},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def test_setup_polls_and_sends_webhook(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """Initial poll creates entities and hands the webhook URL to the device."""
    await _setup(hass)
    assert hass.states.get("media_player.beamer").state == "idle"
    assert hass.states.get("sensor.beamer_foreground_app").state == "Startbildschirm"
    assert hass.states.get("sensor.beamer_media_end").state == "unknown"
    mock_client.set_webhook.assert_awaited_once()
    assert mock_client.set_webhook.await_args.args[0].endswith("/api/webhook/hook")


async def test_push_updates_entities(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """A webhook push updates player and sensors immediately."""
    await _setup(hass)
    client = await hass_client_no_auth()
    resp = await client.post("/api/webhook/hook", json=PLAYING)
    assert resp.status == 200
    await hass.async_block_till_done()

    player = hass.states.get("media_player.beamer")
    assert player.state == "playing"
    assert player.attributes["media_title"] == "Nordreportage"
    assert player.attributes["app_name"] == "YouTube"
    assert player.attributes["media_position"] == 600
    assert player.attributes["media_duration"] == 1800
    assert hass.states.get("sensor.beamer_media_end").state not in ("unknown", "unavailable")


async def test_push_from_other_device_is_ignored(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Payloads carrying another device id don't touch this entry."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await client.post("/api/webhook/hook", json={**PLAYING, "id": "someone-else"})
    await hass.async_block_till_done()
    assert hass.states.get("media_player.beamer").state == "idle"


async def test_commands_and_notify(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """Media player services and notify go to the device API."""
    await _setup(hass)
    await hass.services.async_call(
        "media_player", "media_pause", {"entity_id": "media_player.beamer"}, blocking=True
    )
    mock_client.command.assert_awaited_with("pause")

    await hass.services.async_call(
        "media_player",
        "volume_set",
        {"entity_id": "media_player.beamer", "volume_level": 0.25},
        blocking=True,
    )
    mock_client.command.assert_awaited_with("volume_set", level=0.25)

    await hass.services.async_call(
        "notify",
        "send_message",
        {"entity_id": "notify.beamer_screen", "message": "Waschmaschine fertig", "title": "Haushalt"},
        blocking=True,
    )
    mock_client.notify.assert_awaited_with("Waschmaschine fertig", "Haushalt", 8000)


async def test_unload(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """Entry unloads cleanly."""
    entry = await _setup(hass)
    assert await hass.config_entries.async_unload(entry.entry_id)


async def test_version_follows_app_updates(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """The device page shows the version the app reports, not the one at pairing."""
    from homeassistant.helpers import device_registry as dr

    entry = await _setup(hass)
    client = await hass_client_no_auth()
    await client.post("/api/webhook/hook", json={**PLAYING, "version": "9.9.9"})
    await hass.async_block_till_done()
    devices = dr.async_entries_for_config_entry(dr.async_get(hass), entry.entry_id)
    assert [d.sw_version for d in devices] == ["9.9.9"]
    assert entry.data["version"] == "9.9.9"
