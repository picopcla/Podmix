"""Validation textuelle de pistes avec le catalogue ouvert MusicBrainz."""

from __future__ import annotations

import json
import base64
import html
import os
import re
import threading
import time
import urllib.parse
import urllib.request
from functools import lru_cache

from bs4 import BeautifulSoup

RATE_LOCK = threading.Lock()
LAST_REQUEST = 0.0
SPOTIFY_TOKEN: tuple[str, float] | None = None


def _clean_credit(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def _clean_catalog_title(value: str) -> str:
    cleaned = _clean_credit(value)
    # Les tracklists ajoutent souvent le label entre crochets, alors que les
    # catalogues musicaux ne l'incluent pas dans le titre du morceau.
    return re.sub(r"\s*\[[^\]]+\]\s*$", "", cleaned).strip()


def _primary_artist(value: str) -> str:
    cleaned = _clean_credit(value)
    return re.split(
        r"\s+(?:feat\.?|ft\.?|vs\.?|presents)\s+|\s*[&,]\s*|\s+[xX]\s+",
        cleaned,
        maxsplit=1,
        flags=re.IGNORECASE,
    )[0].strip()


def _artist_name(recording: dict) -> str:
    credits = recording.get("artist-credit") or []
    return "".join(
        credit.get("name", "") + (credit.get("joinphrase") or "")
        for credit in credits if isinstance(credit, dict)
    ).strip()


def search_recording(artist: str, title: str) -> list[dict]:
    global LAST_REQUEST
    query = f'recording:"{title}" AND artist:"{artist}"'
    parameters = urllib.parse.urlencode({"query": query, "fmt": "json", "limit": 5})
    request = urllib.request.Request(
        f"https://musicbrainz.org/ws/2/recording/?{parameters}",
        headers={
            "Accept": "application/json",
            "User-Agent": "PodmixStudio/0.1 (local-prototype)",
        },
    )
    with RATE_LOCK:
        wait = 1.05 - (time.monotonic() - LAST_REQUEST)
        if wait > 0:
            time.sleep(wait)
        with urllib.request.urlopen(request, timeout=12) as response:
            payload = json.loads(response.read().decode())
        LAST_REQUEST = time.monotonic()
    return [{
        "mbid": item.get("id"),
        "title": item.get("title") or "",
        "artist": _artist_name(item),
        "score": int(item.get("score") or 0),
        "firstReleaseDate": item.get("first-release-date"),
    } for item in payload.get("recordings", [])]


def validate_track(artist: str, title: str) -> dict:
    matches = search_recording(artist, title)
    best = matches[0] if matches else None
    accepted = bool(best and best["score"] >= 85)
    try:
        deezer = search_deezer(artist, title)
    except Exception:
        deezer = None
    try:
        spotify = search_spotify(artist, title)
    except Exception:
        spotify = None
    return {
        "accepted": accepted,
        "bestMatch": best,
        "matches": matches,
        "artworkUrl": (
            (deezer or {}).get("artworkUrl")
            or (spotify or {}).get("artworkUrl")
        ),
        "deezerUrl": deezer.get("url") if deezer else None,
        "spotifyUrl": spotify.get("url") if spotify else None,
        "deezerMatch": deezer,
        "spotifyMatch": spotify,
    }


def _words(value: str) -> set[str]:
    normalized = "".join(character.lower() if character.isalnum() else " " for character in _clean_credit(value))
    return {word for word in normalized.split() if len(word) > 2}


def _score_match(wanted_artist: str, wanted_title: str, found_artist: str, found_title: str) -> int:
    artist_words, title_words = _words(wanted_artist), _words(wanted_title)
    found_artist_words, found_title_words = _words(found_artist), _words(found_title)
    artist_score = len(artist_words & found_artist_words) / max(1, len(artist_words))
    title_score = len(title_words & found_title_words) / max(1, len(title_words))
    return round((artist_score * 0.45 + title_score * 0.55) * 100)


def _get_json(url: str, headers: dict[str, str] | None = None) -> dict:
    request = urllib.request.Request(url, headers={"Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.loads(response.read().decode())


def search_deezer(artist: str, title: str) -> dict | None:
    artist = _clean_credit(artist)
    title = _clean_catalog_title(title)
    query = urllib.parse.urlencode({"q": f"{artist} {title}", "limit": 5})
    payload = _get_json(f"https://api.deezer.com/search?{query}")
    candidates = []
    for item in payload.get("data") or []:
        found_artist = str((item.get("artist") or {}).get("name") or "")
        found_title = str(item.get("title") or "")
        score = _score_match(artist, title, found_artist, found_title)
        if item.get("link"):
            album = item.get("album") or {}
            candidates.append({
                "url": item["link"],
                "artworkUrl": (
                    album.get("cover_xl")
                    or album.get("cover_big")
                    or album.get("cover_medium")
                    or None
                ),
                "previewUrl": item.get("preview") or None,
                "artist": found_artist,
                "title": found_title,
                "score": score,
                "id": str(item.get("id") or ""),
            })
    best = max(candidates, key=lambda item: item["score"], default=None)
    return best if best and best["score"] >= 60 else None


def _spotify_access_token() -> str | None:
    global SPOTIFY_TOKEN
    if SPOTIFY_TOKEN and SPOTIFY_TOKEN[1] > time.time() + 60:
        return SPOTIFY_TOKEN[0]
    client_id = os.environ.get("SPOTIFY_CLIENT_ID", "").strip()
    client_secret = os.environ.get("SPOTIFY_CLIENT_SECRET", "").strip()
    if not client_id or not client_secret:
        return None
    credentials = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    request = urllib.request.Request(
        "https://accounts.spotify.com/api/token",
        data=urllib.parse.urlencode({"grant_type": "client_credentials"}).encode(),
        headers={"Authorization": f"Basic {credentials}", "Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=12) as response:
        payload = json.loads(response.read().decode())
    token = str(payload.get("access_token") or "")
    if not token:
        return None
    SPOTIFY_TOKEN = (token, time.time() + int(payload.get("expires_in") or 3600))
    return token


def search_spotify(artist: str, title: str) -> dict | None:
    artist = _clean_credit(artist)
    title = _clean_catalog_title(title)
    token = _spotify_access_token()
    if not token:
        return search_spotify_public(artist, title)

    # Reprend la cascade de l'ancienne application Android : la recherche
    # stricte de Spotify rate certains crédits multiples et certains remixes.
    primary_artist = _primary_artist(artist)
    searches = [
        (f"artist:{artist} track:{title}", True),
        (f"{artist} {title}", False),
    ]
    if primary_artist and primary_artist.casefold() != artist.casefold():
        searches.extend([
            (f"artist:{primary_artist} track:{title}", False),
            (f"{primary_artist} {title}", False),
        ])

    for search, trust_spotify_filters in searches:
        query = urllib.parse.urlencode({"q": search, "type": "track", "limit": 3})
        try:
            payload = _get_json(
                f"https://api.spotify.com/v1/search?{query}",
                {"Authorization": f"Bearer {token}"},
            )
        except Exception:
            continue

        candidates = []
        for item in (payload.get("tracks") or {}).get("items") or []:
            found_artist = ", ".join(str(value.get("name") or "") for value in item.get("artists") or [])
            found_title = str(item.get("name") or "")
            score = _score_match(artist, title, found_artist, found_title)
            artist_overlap = len(_words(artist) & _words(found_artist)) / max(1, len(_words(artist)))
            title_overlap = len(_words(title) & _words(found_title)) / max(1, len(_words(title)))
            url = (item.get("external_urls") or {}).get("spotify")
            if not url or (not trust_spotify_filters and (artist_overlap < 0.4 or title_overlap < 0.4)):
                continue
            images = (item.get("album") or {}).get("images") or []
            candidates.append({
                "url": url,
                "artworkUrl": (
                    str(images[0].get("url") or "")
                    if images and isinstance(images[0], dict)
                    else None
                ),
                "artist": found_artist,
                "title": found_title,
                "score": score,
                "id": str(item.get("id") or ""),
            })
        best = max(candidates, key=lambda item: item["score"], default=None)
        if best:
            return best
    return None


def _musicbrainz_json(path: str, parameters: dict[str, str | int]) -> dict:
    global LAST_REQUEST
    query = urllib.parse.urlencode(parameters)
    request = urllib.request.Request(
        f"https://musicbrainz.org/ws/2/{path}?{query}",
        headers={
            "Accept": "application/json",
            "User-Agent": "PodmixStudio/0.1 (local-prototype)",
        },
    )
    with RATE_LOCK:
        wait = 1.05 - (time.monotonic() - LAST_REQUEST)
        if wait > 0:
            time.sleep(wait)
        with urllib.request.urlopen(request, timeout=12) as response:
            payload = json.loads(response.read().decode())
        LAST_REQUEST = time.monotonic()
    return payload


@lru_cache(maxsize=256)
def _spotify_artist_url(artist: str) -> str:
    primary = _primary_artist(artist)
    if not primary:
        return ""
    payload = _musicbrainz_json("artist/", {
        "query": f'artist:"{primary}"',
        "fmt": "json",
        "limit": 5,
    })
    matches = payload.get("artists") or []
    best = next((item for item in matches if int(item.get("score") or 0) >= 90), None)
    if not best or not best.get("id"):
        return ""
    details = _musicbrainz_json(f"artist/{best['id']}", {"inc": "url-rels", "fmt": "json"})
    for relation in details.get("relations") or []:
        resource = str((relation.get("url") or {}).get("resource") or "")
        if re.fullmatch(r"https://open\.spotify\.com/artist/[A-Za-z0-9]+/?", resource):
            return resource.rstrip("/")
    return ""


def _spotify_page(url: str) -> BeautifulSoup:
    request = urllib.request.Request(
        url,
        headers={
            "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
            "User-Agent": "Mozilla/5.0",
        },
    )
    with urllib.request.urlopen(request, timeout=12) as response:
        return BeautifulSoup(response.read(2_000_001), "html.parser")


def _spotify_track_from_page(soup: BeautifulSoup, artist: str, title: str) -> dict | None:
    candidates = []
    for row in soup.select('[data-testid="track-row"]'):
        found_title = _clean_credit(str(row.get("aria-label") or ""))
        labelled_by = str(row.get("aria-labelledby") or "")
        match = re.search(r"spotify:track:([A-Za-z0-9]{22})", labelled_by)
        if not found_title or not match:
            continue
        score = _score_match(artist, title, artist, found_title)
        image = row.select_one("img[src]")
        candidates.append({
            "url": f"https://open.spotify.com/track/{match.group(1)}",
            "artworkUrl": str(image.get("src") or "") if image else None,
            "artist": artist,
            "title": found_title,
            "score": score,
            "id": match.group(1),
        })
    best = max(candidates, key=lambda item: item["score"], default=None)
    return best if best and best["score"] >= 75 else None


@lru_cache(maxsize=512)
def search_spotify_public(artist: str, title: str) -> dict | None:
    """Résout un lien Spotify sans secret API via les pages publiques officielles."""
    artist = _clean_credit(artist)
    title = _clean_catalog_title(title)
    artist_url = _spotify_artist_url(artist)
    if not artist_url:
        return None
    artist_page = _spotify_page(artist_url)
    direct = _spotify_track_from_page(artist_page, artist, title)
    if direct:
        return direct

    # Si le titre n'est pas dans les morceaux populaires, inspecter les sorties
    # dont le nom recouvre le titre demandé (trois pages maximum).
    releases: list[tuple[int, str]] = []
    seen: set[str] = set()
    wanted_words = _words(title)
    for anchor in artist_page.select('a[href*="/album/"]'):
        href = str(anchor.get("href") or "")
        found_words = _words(anchor.get_text(" ", strip=True))
        overlap = len(wanted_words & found_words)
        if not href.startswith("/album/") or href in seen or overlap < max(1, len(wanted_words) - 1):
            continue
        seen.add(href)
        releases.append((overlap, f"https://open.spotify.com{href}"))
    for _, release_url in sorted(releases, reverse=True)[:3]:
        match = _spotify_track_from_page(_spotify_page(release_url), artist, title)
        if match:
            return match
    return None
