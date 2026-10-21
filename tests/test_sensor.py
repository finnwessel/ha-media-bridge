"""Content type classification."""

import pytest

from custom_components.media_bridge.sensor import content_type

YT = "com.google.android.youtube.tv"
NETFLIX = "com.netflix.ninja"


def _d(app, minutes, title="", state="playing", album=None):
    return {"state": state, "app": app, "duration_ms": int(minutes * 60_000) or None, "title": title, "album": album}


@pytest.mark.parametrize(
    ("data", "expected"),
    [
        (_d(YT, 4, "Katzenvideo"), "clip"),
        (_d(YT, 29, "Die Nordreportage | NDR Doku"), "episode"),
        (_d(YT, 110, "Ganzer Film"), "movie"),
        (_d(NETFLIX, 2, "Trailer"), "clip"),
        (_d(NETFLIX, 48, "Dark"), "episode"),
        (_d(NETFLIX, 23, "S2:E4 Der Anfang"), "episode"),
        (_d(NETFLIX, 125, "Dune"), "movie"),
        (_d(YT, 9, "Folge 3"), "episode"),
        (_d("com.spotify.tv.android", 3, "Song"), "music"),
        (_d("de.zdf.android.tv", 0, "ZDF live"), "live"),
        (_d(NETFLIX, 125, "Dune", state="paused"), "movie"),
        (_d(NETFLIX, 125, "Dune", state="idle"), None),
        (_d(NETFLIX, 125, "Dune", state="off"), None),
    ],
)
def test_content_type(data, expected) -> None:
    """Heuristics by app, length and title."""
    assert content_type(data) == expected
