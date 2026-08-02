"""Recherche de podcasts via l’annuaire public iTunes."""

from __future__ import annotations

import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def search_podcasts(query: str, limit: int = 25) -> list[dict]:
    term = " ".join(query.split())
    if len(term) < 2:
        return []
    url = "https://itunes.apple.com/search?" + urlencode({
        "term": term,
        "media": "podcast",
        "entity": "podcast",
        "limit": min(50, max(1, limit)),
        "country": "FR",
    })
    request = Request(url, headers={"Accept": "application/json", "User-Agent": "PodmixStudio/0.2"})
    with urlopen(request, timeout=12) as response:
        payload = json.loads(response.read().decode())
    return [{
        "id": str(item.get("collectionId") or item.get("trackId") or ""),
        "title": str(item.get("collectionName") or item.get("trackName") or ""),
        "artist": str(item.get("artistName") or ""),
        "feedUrl": str(item.get("feedUrl") or ""),
        "artworkUrl": str(item.get("artworkUrl600") or item.get("artworkUrl100") or ""),
        "genre": str(item.get("primaryGenreName") or ""),
        "episodeCount": int(item.get("trackCount") or 0),
    } for item in payload.get("results") or [] if item.get("feedUrl")]
