"""Sleep after the current item: standby when it has finished."""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .api import MediaBridgeError
from .const import EVENT_SLEEP_TIMER_FINISHED, LOGGER
from .coordinator import MediaBridgeConfigEntry, MediaBridgeCoordinator
from .entity import MediaBridgeEntity

PARALLEL_UPDATES = 0

# A push this close to the end counts as finished (heartbeat is every 30 s).
END_MARGIN_MS = 5_000
FINISHED_STATES = {"idle", "stopped", "off"}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MediaBridgeConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the switch."""
    async_add_entities([SleepAfterCurrentSwitch(entry.runtime_data)])


class SleepAfterCurrentSwitch(MediaBridgeEntity, SwitchEntity):
    """When on, the device goes to standby once the current item has finished.

    Finished means: playback stopped, the app moved on to another title
    (autoplay; it is paused first), or the position reached the end.
    Pausing in between does not count.
    """

    _attr_translation_key = "sleep_after_current"
    _attr_icon = "mdi:power-sleep"

    def __init__(self, coordinator: MediaBridgeCoordinator) -> None:
        """Initialize the switch."""
        super().__init__(coordinator, "sleep_after_current")
        self._armed_title: str | None = None
        self._armed_app: str | None = None
        self._finishing = False

    @property
    def is_on(self) -> bool:
        """Armed."""
        return self._armed_app is not None

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Arm for what is playing right now."""
        data = self.coordinator.data or {}
        if data.get("state") not in ("playing", "paused", "buffering") or not data.get("app"):
            raise ServiceValidationError("Nothing is playing")
        self._armed_app = data["app"]
        self._armed_title = data.get("title")
        self.async_write_ha_state()

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Disarm."""
        self._disarm()

    def _disarm(self) -> None:
        self._armed_app = None
        self._armed_title = None
        self.async_write_ha_state()

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        """What the timer waits for."""
        return {"title": self._armed_title} if self.is_on else None

    @callback
    def _handle_coordinator_update(self) -> None:
        if self.is_on and not self._finishing:
            reason = self._finished(self.coordinator.data or {})
            if reason:
                self._finishing = True
                self.hass.async_create_task(self._async_finish(reason))
        super()._handle_coordinator_update()

    def _finished(self, data: dict[str, Any]) -> str | None:
        state = data.get("state")
        if state in FINISHED_STATES:
            return "stopped"
        if data.get("app") != self._armed_app or (
            self._armed_title and data.get("title") and data.get("title") != self._armed_title
        ):
            return "next_item"
        duration, position = data.get("duration_ms"), data.get("position_ms")
        if state == "playing" and duration and position is not None and position >= duration - END_MARGIN_MS:
            return "end_reached"
        return None

    async def _async_finish(self, reason: str) -> None:
        client = self.coordinator.client
        try:
            if reason == "next_item" and (self.coordinator.data or {}).get("state") == "playing":
                await client.command("pause")
            if (self.coordinator.data or {}).get("state") != "off":
                await client.command("sleep")
        except MediaBridgeError as err:
            LOGGER.warning("Sleep after current item failed: %s", err)
        finally:
            title = self._armed_title
            self._finishing = False
            self._disarm()
            self.hass.bus.async_fire(
                EVENT_SLEEP_TIMER_FINISHED,
                {"entity_id": self.entity_id, "reason": reason, "title": title},
            )
