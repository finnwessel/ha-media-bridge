"""Fixtures for Media Bridge tests."""

from collections.abc import Generator
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

INFO = {
    "id": "dev-1",
    "name": "Beamer",
    "model": "N1S Ultimate",
    "manufacturer": "JMGO",
    "version": "0.2.0",
    "api": 1,
}

IDLE_STATE = {
    "id": "dev-1",
    "state": "idle",
    "screen_on": True,
    "app": None,
    "app_label": None,
    "title": None,
    "artist": None,
    "album": None,
    "duration_ms": None,
    "position_ms": None,
    "can_seek": False,
    "foreground_app": "com.google.android.tvlauncher",
    "foreground_label": "Startbildschirm",
    "volume": 0.4,
    "muted": False,
    "webhook_url": None,
}


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    """Load the integration from custom_components."""
    return


@pytest.fixture
def mock_client() -> Generator[MagicMock]:
    """Client used by the integration (not by the config flow)."""
    with patch("custom_components.media_bridge.MediaBridgeClient", autospec=True) as cls:
        client = cls.return_value
        client.state = AsyncMock(return_value=dict(IDLE_STATE))
        client.set_webhook = AsyncMock()
        client.command = AsyncMock()
        client.notify = AsyncMock()
        client.apps = AsyncMock(
            return_value=[
                {"package": "com.netflix.ninja", "label": "Netflix"},
                {"package": "com.google.android.youtube.tv", "label": "YouTube"},
            ]
        )
        client.artwork = AsyncMock(return_value=b"jpeg-bytes")
        client.app_icon = AsyncMock(return_value=b"png-bytes")
        yield client
