"""Wake-on-LAN magic packets, without extra requirements."""

from __future__ import annotations

import ipaddress
import socket

from homeassistant.core import HomeAssistant


def normalize_mac(mac: str) -> str | None:
    """aa:bb:cc:dd:ee:ff in lower case, or None if it isn't a MAC address."""
    digits = "".join(c for c in mac.lower() if c in "0123456789abcdef")
    if len(digits) != 12:
        return None
    return ":".join(digits[i : i + 2] for i in range(0, 12, 2))


def _send(mac: str, targets: list[str]) -> None:
    payload = bytes.fromhex("ff" * 6 + mac.replace(":", "") * 16)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for target in targets:
            for port in (9, 7):
                sock.sendto(payload, (target, port))


async def async_wake(hass: HomeAssistant, mac: str, host: str | None) -> None:
    """Send the packet to the device's /24 broadcast, the global broadcast and the host itself."""
    targets = ["255.255.255.255"]
    if host:
        try:
            net = ipaddress.ip_network(f"{host}/24", strict=False)
            targets += [str(net.broadcast_address), host]
        except ValueError:
            pass
    await hass.async_add_executor_job(_send, mac, targets)
