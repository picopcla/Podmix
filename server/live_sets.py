"""Découverte autonome de DJ live sets.

Ce module ne connaît volontairement ni RSS, ni épisodes podcasts, ni jobs de
timestamping. Il ne fait que retourner des candidats de lecture, à enrichir par
le futur pipeline DJ Live Sets.
"""

from __future__ import annotations

import re
import threading
import time
import unicodedata
from collections.abc import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from discovery import validate_media_url
from discovery import discover_tracklist
from tracklist import align_tracklist, parse_tracklist
from tracklist_sources import search_external_tracklist
from tl1001 import scrape_tracklist

MIN_SET_DURATION_SECONDS = 20 * 60
MAX_RESULTS = 50
YEAR = re.compile(r"\b(?:19|20)\d{2}\b")
PUBLISHED_1001_URL = re.compile(r"https?://(?:www\.)?1001tracklists\.com/[^\s<>'\"]+", re.IGNORECASE)
# Les URL audio YouTube/SoundCloud sont signées et coûteuses à obtenir. Le
# lecteur Android ouvre à nouveau le même média à chaque morceau de tracklist
# et Android Auto peut faire une ouverture supplémentaire. Garder la
# résolution pendant un court laps de temps évite ces appels yt-dlp répétés.
LIVE_SET_RESOLVE_CACHE_TTL_SECONDS = 30 * 60
LIVE_SET_RESOLVE_CACHE: dict[str, tuple[float, dict]] = {}
LIVE_SET_RESOLVE_LOCK = threading.Lock()
AUDIO_FORMAT_SELECTOR = (
    "bestaudio[ext=m4a]/"
    "bestaudio[acodec^=mp4a]/"
    "bestaudio[ext=mp3]/"
    "bestaudio[ext=webm]/"
    "bestaudio/best"
)
YOUTUBE_PROGRESSIVE_FORMAT_SELECTOR = "best[ext=mp4]/best"


def live_set_resolve_options(url: str) -> dict:
    """Build yt-dlp options that prefer a real audio-only stream.

    YouTube's plain Android client can expose only format 18 (a combined
    video/audio MP4) for some DJ sets.  Relaying that large progressive video
    makes Media3 wait indefinitely at 0:00.  The Android VR client still
    exposes the DASH M4A formats; keep the regular Android client only as a
    final compatibility fallback.
    """
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        # This is a preference ladder, not a forced container. Media3 can
        # consume AAC/M4A, MP3, Opus/WebM and adaptive audio; yt-dlp falls
        # through to the next available family for each provider.
        "format": AUDIO_FORMAT_SELECTOR,
        "noplaylist": True,
        "socket_timeout": 18,
    }
    host = (urlparse(url).hostname or "").lower()
    if host in {"youtube.com", "www.youtube.com", "youtu.be"}:
        options["extractor_args"] = {
            "youtube": {"player_client": ["android_vr", "android"]},
        }
    return options


def youtube_progressive_resolve_options(url: str) -> dict:
    """Return a seekable YouTube fallback for Media3.

    Some current YouTube audio-only URLs expose the first bytes but reject
    every later HTTP Range with 403.  An M4A player then freezes at 0:00 while
    looking for the MP4 index.  Format 18 is larger because it also contains
    video, but its byte ranges remain readable and Media3 can decode its AAC
    audio immediately.
    """
    options = live_set_resolve_options(url)
    options["format"] = YOUTUBE_PROGRESSIVE_FORMAT_SELECTOR
    options["extractor_args"] = {
        "youtube": {"player_client": ["android"]},
    }
    return options


def media_url_supports_random_access(url: str, headers: dict[str, str] | None = None) -> bool:
    """Probe a non-initial byte range required by MP4/WebM extractors."""
    request_headers = dict(headers or {})
    request_headers["Range"] = "bytes=1048576-1114111"
    try:
        with urlopen(Request(url, headers=request_headers), timeout=12) as response:
            content_range = str(response.headers.get("Content-Range") or "")
            return getattr(response, "status", 200) == 206 and content_range.startswith("bytes 1048576-")
    except (HTTPError, URLError, OSError, TimeoutError):
        return False


def invalidate_live_set_resolution(url: str) -> None:
    """Forget a signed media URL after its upstream has rejected it."""
    safe_url = validate_media_url(url)
    with LIVE_SET_RESOLVE_LOCK:
        LIVE_SET_RESOLVE_CACHE.pop(safe_url, None)


def _normalized(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    plain = "".join(char for char in decomposed if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", " ", plain).strip()


def _tokens(value: str) -> set[str]:
    return {token for token in _normalized(value).split() if len(token) > 1}


def search_queries(query: str) -> tuple[str, ...]:
    """Conserve la requête utilisateur, puis ajoute des formulations DJ."""
    term = " ".join(query.split())
    if len(term) < 2 or len(term) > 120:
        return ()
    lowered = term.casefold()
    variants = [term]
    if "live set" not in lowered:
        variants.append(f"{term} live set")
    if "dj set" not in lowered and "mix" not in lowered:
        variants.append(f"{term} DJ set")
    return tuple(dict.fromkeys(variants))


def _is_probable_set(title: str, duration: int) -> bool:
    text = _normalized(title)
    if duration and duration < MIN_SET_DURATION_SECONDS:
        return False
    excluded = ("official video", "official audio", "music video", "radio edit", "extended mix", "interview", "tutorial", "reaction")
    return not any(phrase in text for phrase in excluded)


def _score(query: str, candidate: dict) -> float:
    wanted = _tokens(query)
    title_value = str(candidate.get("title") or "")
    title = _tokens(title_value)
    channel = _tokens(str(candidate.get("channel") or ""))
    title_overlap = len(wanted & title) / max(1, len(wanted))
    channel_overlap = len(wanted & channel) / max(1, len(wanted))
    overlap = title_overlap + channel_overlap * 0.22
    years = set(YEAR.findall(query))
    year_bonus = 0.30 if years and years & set(YEAR.findall(title_value)) else 0.0
    # Une vidéo de compilation peut empiler vingt DJs dans son titre. Pour une
    # recherche par artiste, l'artiste demandé doit être près du début du titre
    # ou présent dans la chaîne : cela favorise le set réellement attribué.
    requested_names = [token for token in _normalized(query).split() if not YEAR.fullmatch(token)]
    title_words = _normalized(title_value).split()
    position_penalty = 0.0
    if requested_names:
        try:
            position = title_words.index(requested_names[0])
            position_penalty = min(0.32, max(0, position - 1) * 0.055)
        except ValueError:
            position_penalty = 0.10
    official_bonus = 0.10 if requested_names and requested_names[0] in channel else 0.0
    duration = int(candidate.get("duration") or 0)
    duration_bonus = min(0.12, duration / (5 * 60 * 60) * 0.12) if duration else 0.0
    return round(overlap + year_bonus + duration_bonus + official_bonus - position_penalty, 4)


def _result(provider: str, entry: dict, query: str) -> dict | None:
    media_id = str(entry.get("id") or "").strip()
    raw_url = str(entry.get("webpage_url") or entry.get("original_url") or entry.get("url") or "").strip()
    if provider == "youtube" and media_id and not raw_url.startswith("http"):
        raw_url = f"https://www.youtube.com/watch?v={media_id}"
    try:
        url = validate_media_url(raw_url)
    except ValueError:
        return None
    title = str(entry.get("title") or "DJ live set").strip()
    duration = max(0, int(entry.get("duration") or 0))
    if not media_id or not _is_probable_set(title, duration):
        return None
    candidate = {
        "id": f"{provider}:{media_id}",
        "provider": provider,
        "title": title,
        "channel": str(entry.get("uploader") or entry.get("channel") or "").strip(),
        "url": url,
        "artworkUrl": str(entry.get("thumbnail") or "").strip(),
        "duration": duration,
        "viewCount": max(0, int(entry.get("view_count") or 0)),
        "publishedAt": str(entry.get("upload_date") or entry.get("timestamp") or "").strip(),
    }
    candidate["score"] = _score(query, candidate)
    return candidate


def _deduplicate(items: Iterable[dict]) -> list[dict]:
    result: list[dict] = []
    seen_urls: set[str] = set()
    seen_keys: set[tuple[str, int]] = set()
    for item in items:
        title_key = _normalized(item["title"])
        duration_bucket = int(item["duration"] or 0) // 180
        key = (title_key, duration_bucket)
        if item["url"] in seen_urls or key in seen_keys:
            continue
        seen_urls.add(item["url"])
        seen_keys.add(key)
        result.append(item)
    return result


def search_live_sets(query: str, limit: int = 24) -> list[dict]:
    """Recherche YouTube et SoundCloud, sans créer de source ni job podcast."""
    import yt_dlp

    queries = search_queries(query)
    if not queries:
        return []
    # Le choix « 50 résultats » de l'application doit réellement élargir la
    # recherche, sans transformer une requête en exploration infinie.
    per_query = min(30, max(6, limit))
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": True,
        "playlistend": per_query,
        "socket_timeout": 18,
    }
    found: list[dict] = []
    for provider, prefix in (("youtube", "ytsearch"), ("soundcloud", "scsearch")):
        for term in queries:
            try:
                with yt_dlp.YoutubeDL(options) as downloader:
                    payload = downloader.sanitize_info(downloader.extract_info(f"{prefix}{per_query}:{term}", download=False))
            except Exception:
                continue
            for entry in payload.get("entries") or []:
                if not isinstance(entry, dict):
                    continue
                candidate = _result(provider, entry, query)
                if candidate:
                    found.append(candidate)
    return sorted(
        _deduplicate(found),
        key=lambda item: (item["score"], item["publishedAt"], item["viewCount"]),
        reverse=True,
    )[:max(1, min(limit, MAX_RESULTS))]


def resolve_live_set(url: str) -> dict:
    """Résout uniquement le média demandé, sans créer de CatalogSource."""
    import yt_dlp

    safe_url = validate_media_url(url)
    now = time.monotonic()
    with LIVE_SET_RESOLVE_LOCK:
        cached = LIVE_SET_RESOLVE_CACHE.get(safe_url)
        if cached and now - cached[0] < LIVE_SET_RESOLVE_CACHE_TTL_SECONDS:
            return dict(cached[1])
    options = live_set_resolve_options(safe_url)
    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.sanitize_info(downloader.extract_info(safe_url, download=False))
    if info.get("_type") == "playlist":
        info = (info.get("entries") or [info])[0]
    host = (urlparse(safe_url).hostname or "").lower()
    if host in {"youtube.com", "www.youtube.com", "youtu.be"}:
        candidate_url = str(info.get("url") or "")
        candidate_headers = {
            str(key): str(value)
            for key, value in (info.get("http_headers") or {}).items()
            if str(key).lower() not in {"host", "content-length", "accept-encoding"}
        }
        if not candidate_url or not media_url_supports_random_access(candidate_url, candidate_headers):
            fallback_options = youtube_progressive_resolve_options(safe_url)
            with yt_dlp.YoutubeDL(fallback_options) as downloader:
                info = downloader.sanitize_info(downloader.extract_info(safe_url, download=False))
            if info.get("_type") == "playlist":
                info = (info.get("entries") or [info])[0]
    audio_url = str(info.get("url") or "")
    if not audio_url:
        formats = [item for item in info.get("formats") or [] if item.get("url") and item.get("acodec") != "none"]
        audio_url = str(formats[-1].get("url") or "") if formats else ""
    if not audio_url:
        raise ValueError("Aucun flux audio lisible n’a été trouvé")
    # Les URL googlevideo sont signées pour le client yt-dlp qui les a
    # obtenues. Le proxy de lecture reprend ces en-têtes ; l'application ne
    # tente donc jamais d'ouvrir directement une URL YouTube éphémère.
    audio_headers = {
        str(key): str(value)
        for key, value in (info.get("http_headers") or {}).items()
        if str(key).lower() not in {"host", "content-length", "accept-encoding"}
    }
    if host in {"youtube.com", "www.youtube.com", "youtu.be"}:
        # Le client Android de yt-dlp signe le flux avec son propre profil.
        # Réinjecter les en-têtes Web présents dans les métadonnées provoque
        # un 403 Googlevideo au moment du relais.
        audio_headers = {}
    result = {
        "url": safe_url,
        "audioUrl": audio_url,
        "audioHeaders": audio_headers,
        "title": str(info.get("title") or "DJ live set"),
        "channel": str(info.get("uploader") or info.get("channel") or ""),
        "artworkUrl": str(info.get("thumbnail") or ""),
        "duration": max(0, int(info.get("duration") or 0)),
        "description": str(info.get("description") or "")[:4000],
        # Keep the selected family visible to the relay/logs. This lets the
        # playback path adapt and makes provider-specific failures diagnosable
        # without exposing the signed media URL.
        "format": {
            "id": str(info.get("format_id") or ""),
            "extension": str(info.get("ext") or ""),
            "audioCodec": str(info.get("acodec") or ""),
            "videoCodec": str(info.get("vcodec") or ""),
            "protocol": str(info.get("protocol") or ""),
        },
    }
    with LIVE_SET_RESOLVE_LOCK:
        LIVE_SET_RESOLVE_CACHE[safe_url] = (time.monotonic(), result)
        # La sélection personnelle est courte, mais borner le cache protège
        # le serveur quand beaucoup de recherches distinctes sont ouvertes.
        if len(LIVE_SET_RESOLVE_CACHE) > 96:
            oldest = min(LIVE_SET_RESOLVE_CACHE, key=lambda key: LIVE_SET_RESOLVE_CACHE[key][0])
            LIVE_SET_RESOLVE_CACHE.pop(oldest, None)
    return dict(result)


def resolve_live_set_tracklist(url: str, title: str = "") -> dict:
    """Pipeline DJ isolé : métadonnées du set, puis bases de tracklists.

    Il n'écrit rien en SQLite et ne crée jamais de job podcast.
    """
    discovered = discover_tracklist(url)
    candidates = discovered.get("candidates") or []
    origin = "description"
    source_url = discovered.get("sourceUrl") or url
    # Les grandes scènes (Tomorrowland, Cercle…) publient souvent le lien
    # 1001Tracklists exact dans la description YouTube. L'extracteur YouTube
    # ne sait pas toujours lire directement ses lignes, mais ce lien est la
    # preuve la plus forte ; il doit passer avant une recherche générale qui
    # peut retourner un autre set du même DJ.
    if len(candidates) < 2:
        linked = PUBLISHED_1001_URL.search(str(discovered.get("sourceText") or ""))
        if linked:
            published_url = linked.group(0).rstrip(".,;:!?)]")
            try:
                published = scrape_tracklist(published_url)
                if len(published.get("candidates") or []) >= 2:
                    candidates = published["candidates"]
                    origin = "1001tracklists lié à la vidéo"
                    source_url = str(published.get("sourceUrl") or published_url)
            except Exception:
                # Une URL publiée inaccessible ne doit pas empêcher les
                # autres sources, mais elle ne doit pas non plus être prise
                # pour une validation.
                pass
    if len(candidates) < 2:
        external = search_external_tracklist(title or discovered.get("title") or "")
        if len(external.get("candidates") or []) >= 2:
            candidates = external["candidates"]
            origin = str(external.get("source") or "tracklist externe")
            source_url = str(external.get("sourceUrl") or source_url)
    timestamp_source = "youtube" if "youtube" in url or "youtu.be" in url else "external"
    tracks = align_tracklist(candidates, [], discovered.get("duration"), timestamp_source=timestamp_source)
    return {
        "tracks": tracks,
        "origin": origin,
        "sourceUrl": source_url,
        "candidateCount": len(candidates),
    }


def parse_live_set_tracklist(text: str) -> dict:
    candidates = parse_tracklist(text, structured_only=False)
    return {
        "tracks": align_tracklist(candidates, [], None, timestamp_source="manual"),
        "origin": "manuel",
        "sourceUrl": "",
        "candidateCount": len(candidates),
    }
