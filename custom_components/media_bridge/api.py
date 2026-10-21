"""Client for the local API of the Media Bridge app."""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp


class MediaBridgeError(Exception):
    """The device could not be reached or rejected the request."""

    def __init__(self, message: str, status: int | None = None) -> None:
        """Initialize the error."""
        super().__init__(message)
        self.status = status


class MediaBridgeAuthError(MediaBridgeError):
    """The token (or pairing code) was rejected."""


class MediaBridgeClient:
    """Talks to one device running the Media Bridge app."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int,
        token: str | None = None,
    ) -> None:
        """Initialize the client."""
        self._session = session
        self._base = f"http://{host}:{port}"
        self._token = token

    async def _request(
        self, method: str, path: str, payload: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {self._token}"} if self._token else {}
        try:
            async with asyncio.timeout(5):
                async with self._session.request(
                    method, self._base + path, json=payload, headers=headers
                ) as resp:
                    data = await resp.json(content_type=None)
                    if resp.status == 401:
                        raise MediaBridgeAuthError(data.get("error", "unauthorized"), 401)
                    if resp.status >= 400:
                        raise MediaBridgeError(data.get("error", "error"), resp.status)
                    return data
        except (aiohttp.ClientError, TimeoutError, ValueError) as err:
            raise MediaBridgeError(str(err) or type(err).__name__) from err

    async def info(self) -> dict[str, Any]:
        """Return device id, name, model and app version (no token needed)."""
        return await self._request("GET", "/info")

    async def pair_start(self) -> None:
        """Ask the device to show a pairing code on screen."""
        await self._request("POST", "/pair/start")

    async def pair_finish(self, code: str) -> dict[str, Any]:
        """Exchange the on-screen code for the API token (plus device info)."""
        return await self._request("POST", "/pair/finish", {"code": code})

    async def state(self) -> dict[str, Any]:
        """Return the current state snapshot."""
        return await self._request("GET", "/state")

    async def set_webhook(self, url: str | None) -> None:
        """Tell the device where to push state changes."""
        await self._request("POST", "/config", {"webhook_url": url})

    async def command(self, command: str, **args: Any) -> None:
        """Run a playback or volume command."""
        await self._request("POST", "/command", {"command": command, **args})

    async def notify(self, message: str, title: str | None, duration_ms: int) -> None:
        """Show a notification on screen."""
        await self._request(
            "POST",
            "/notify",
            {"message": message, "title": title, "duration_ms": duration_ms},
        )
