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


async def test_sources_and_launch(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """Apps become sources; selecting one launches it, Home goes to the launcher."""
    await _setup(hass)
    state = hass.states.get("media_player.beamer")
    assert state.attributes["source_list"] == ["Home", "Netflix", "YouTube"]
    assert state.attributes["source"] == "Startbildschirm"

    await hass.services.async_call(
        "media_player", "select_source", {"entity_id": "media_player.beamer", "source": "Netflix"}, blocking=True
    )
    mock_client.command.assert_awaited_with("launch", package="com.netflix.ninja")
    await hass.services.async_call(
        "media_player", "select_source", {"entity_id": "media_player.beamer", "source": "Home"}, blocking=True
    )
    mock_client.command.assert_awaited_with("home")


async def test_artwork_from_device(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """A cover bitmap is proxied from the device API."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await client.post("/api/webhook/hook", json={**PLAYING, "artwork_id": "abc123", "artwork_url": None})
    await hass.async_block_till_done()
    picture = hass.states.get("media_player.beamer").attributes["entity_picture"]
    assert "cache=abc123" in picture
    resp = await client.get(picture)
    assert await resp.read() == b"jpeg-bytes"


async def test_remote_key_event(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Key presses pushed by the device fire the event entity."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await client.post("/api/webhook/hook", json={"id": "dev-1", "event": "key", "key": "menu", "press": "long_press"})
    await hass.async_block_till_done()
    state = hass.states.get("event.beamer_remote")
    assert state.attributes["event_type"] == "long_press"
    assert state.attributes["key"] == "menu"
    # Events don't replace the media state.
    assert hass.states.get("media_player.beamer").state == "idle"


async def test_notification_service_and_action(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """The service passes buttons on; a button press comes back as a bus event."""
    await _setup(hass)
    await hass.services.async_call(
        DOMAIN,
        "show_notification",
        {
            "entity_id": "media_player.beamer",
            "title": "Haustür",
            "message": "Es hat geklingelt",
            "actions": [{"id": "open", "title": "Öffnen"}, "Ignorieren"],
            "tag": "door",
            "pause_playback": True,
        },
        blocking=True,
    )
    args = mock_client.notify.await_args
    assert args.args == ("Es hat geklingelt", "Haustür", 10000)
    assert args.kwargs["actions"] == [{"id": "open", "title": "Öffnen"}, {"id": "Ignorieren", "title": "Ignorieren"}]
    assert args.kwargs["pause"] is True
    assert args.kwargs["tag"] == "door"

    events = []
    hass.bus.async_listen("media_bridge_notification_action", lambda e: events.append(e))
    client = await hass_client_no_auth()
    await client.post(
        "/api/webhook/hook",
        json={"id": "dev-1", "event": "notification_action", "action": "open", "tag": "door"},
    )
    await hass.async_block_till_done()
    assert len(events) == 1
    assert events[0].data["action"] == "open"
    assert events[0].data["tag"] == "door"


async def test_play_media(hass: HomeAssistant, mock_client: MagicMock) -> None:
    """Links are opened, apps launched by package or label."""
    await _setup(hass)
    await hass.services.async_call(
        "media_player",
        "play_media",
        {
            "entity_id": "media_player.beamer",
            "media_content_type": "url",
            "media_content_id": "https://youtu.be/dQw4w9WgXcQ",
        },
        blocking=True,
    )
    mock_client.command.assert_awaited_with("open_url", url="https://youtu.be/dQw4w9WgXcQ")
    await hass.services.async_call(
        "media_player",
        "play_media",
        {"entity_id": "media_player.beamer", "media_content_type": "app", "media_content_id": "Netflix"},
        blocking=True,
    )
    mock_client.command.assert_awaited_with("launch", package="com.netflix.ninja")


async def test_new_sensors(
    hass: HomeAssistant, mock_client: MagicMock, hass_client_no_auth
) -> None:
    """Content type, up next and audio output follow the pushed state."""
    await _setup(hass)
    client = await hass_client_no_auth()
    await client.post(
        "/api/webhook/hook",
        json={**PLAYING, "next_title": "Teil 2", "audio_output": "bluetooth", "audio_output_name": "Sony WH"},
    )
    await hass.async_block_till_done()
    assert hass.states.get("sensor.beamer_content_type").state == "episode"
    assert hass.states.get("sensor.beamer_up_next").state == "Teil 2"
    audio = hass.states.get("sensor.beamer_audio_output")
    assert audio.state == "bluetooth"
    assert audio.attributes["device_name"] == "Sony WH"
