"""Découverte contrôlée de tracklists depuis les métadonnées d'une URL média."""

from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path
from urllib.parse import urlparse

try:
    from .tracklist import parse_tracklist
except ImportError:
    from tracklist import parse_tracklist

ALLOWED_DOMAINS = ("youtube.com", "youtu.be", "soundcloud.com", "mixcloud.com")


def _cookie_file() -> str | None:
    """Copie les cookies dans un fichier inscriptible, car yt-dlp les actualise."""
    configured = os.environ.get("YTDLP_COOKIE_FILE")
    if not configured:
        return None
    source = Path(configured)
    if not source.is_file():
        return None
    meaningful = [line for line in source.read_text(errors="ignore").splitlines() if line and not line.startswith("#")]
    if not meaningful:
        return None
    target = Path(tempfile.gettempdir()) / "podmix-ytdlp-cookies.txt"
    if not target.exists() or source.stat().st_mtime_ns > target.stat().st_mtime_ns:
        shutil.copyfile(source, target)
        target.chmod(0o600)
    return str(target)


def validate_media_url(url: str) -> str:
    parsed = urlparse(url.strip())
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or not any(host == domain or host.endswith(f".{domain}") for domain in ALLOWED_DOMAINS):
        raise ValueError("URL refusée : seuls YouTube, SoundCloud et Mixcloud en HTTPS sont acceptés")
    return url.strip()


def candidates_from_info(info: dict) -> list[dict]:
    if info.get("_type") == "playlist":
        entries = info.get("entries") or []
        info = entries[0] if entries else info
    description = info.get("description") or ""
    chapter_lines = []
    for chapter in info.get("chapters") or []:
        start = int(chapter.get("start_time") or 0)
        hours, remainder = divmod(start, 3600)
        minutes, seconds = divmod(remainder, 60)
        stamp = f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes:02d}:{seconds:02d}"
        chapter_lines.append(f"{stamp} {chapter.get('title') or 'Chapitre'}")

    candidates = parse_tracklist("\n".join(chapter_lines), structured_only=True) if chapter_lines else []
    if len(candidates) < 2:
        candidates = parse_tracklist(description, structured_only=True)
    return candidates if len(candidates) >= 2 else []


def discover_tracklist(url: str) -> dict:
    import yt_dlp

    safe_url = validate_media_url(url)
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": False,
        "playlistend": 1,
        "socket_timeout": 18,
    }
    cookie_file = _cookie_file()
    if cookie_file:
        options["cookiefile"] = cookie_file
    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.extract_info(safe_url, download=False)
        info = downloader.sanitize_info(info)

    if info.get("_type") == "playlist":
        entries = info.get("entries") or []
        info = entries[0] if entries else info
    candidates = candidates_from_info(info)
    return {
        "sourceUrl": safe_url,
        "extractor": info.get("extractor_key") or info.get("extractor") or "media",
        "title": info.get("title") or "",
        "uploader": info.get("uploader") or info.get("channel") or "",
        "duration": info.get("duration"),
        "candidateCount": len(candidates),
        "candidates": candidates,
    }


def import_dj_set(url: str) -> dict:
    """Résout les métadonnées et un flux audio temporaire pour un set autorisé."""
    import hashlib
    import yt_dlp

    safe_url = validate_media_url(url)
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "format": "bestaudio/best",
        "noplaylist": True,
        "socket_timeout": 18,
    }
    cookie_file = _cookie_file()
    if cookie_file:
        options["cookiefile"] = cookie_file
    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.extract_info(safe_url, download=False)
        info = downloader.sanitize_info(info)

    if info.get("_type") == "playlist":
        entries = info.get("entries") or []
        info = entries[0] if entries else info
    stream_url = info.get("url") or ""
    if not stream_url:
        formats = [item for item in info.get("formats") or [] if item.get("url") and item.get("acodec") != "none"]
        if formats:
            stream_url = formats[-1]["url"]
    if not stream_url:
        raise ValueError("Aucun flux audio lisible n’a été trouvé")

    media_id = str(info.get("id") or hashlib.sha256(safe_url.encode()).hexdigest()[:20])
    extractor = str(info.get("extractor_key") or info.get("extractor") or "DJ")
    title = str(info.get("title") or "DJ set")
    uploader = str(info.get("uploader") or info.get("channel") or extractor)
    thumbnail = str(info.get("thumbnail") or "")
    duration = int(info.get("duration") or 0)
    hours, remainder = divmod(duration, 3600)
    minutes, seconds = divmod(remainder, 60)
    formatted_duration = f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes}:{seconds:02d}"
    return {
        "id": f"dj:{extractor.lower()}:{media_id}",
        "kind": "dj",
        "title": title,
        "description": f"{uploader} · {extractor}",
        "artworkUrl": thumbnail,
        "feedUrl": safe_url,
        "episodes": [{
            "id": f"dj-episode:{extractor.lower()}:{media_id}",
            "title": title,
            "description": str(info.get("description") or "")[:4000],
            "publishedAt": str(info.get("upload_date") or ""),
            "duration": formatted_duration,
            "audioUrl": stream_url,
            "sourceUrl": safe_url,
            "artworkUrl": thumbnail,
        }],
    }


def search_dj_sets(query: str, limit: int = 12) -> list[dict]:
    """Recherche des sets SoundCloud et YouTube sans télécharger leur média."""
    import yt_dlp

    term = " ".join(query.split())
    if len(term) < 2 or len(term) > 120:
        return []
    result_limit = min(20, max(1, limit))
    options = {"quiet": True, "no_warnings": True, "skip_download": True, "extract_flat": True, "playlistend": result_limit, "socket_timeout": 18}
    cookie_file = _cookie_file()
    if cookie_file:
        options["cookiefile"] = cookie_file
    results = []
    searches = (f"scsearch{result_limit}:{term} DJ set", f"ytsearch{result_limit}:{term} DJ set")
    for search in searches:
        try:
            with yt_dlp.YoutubeDL(options) as downloader:
                info = downloader.extract_info(search, download=False)
                info = downloader.sanitize_info(info)
        except Exception:
            continue
        for entry in info.get("entries") or []:
            media_id = str(entry.get("id") or "")
            url = str(entry.get("webpage_url") or entry.get("original_url") or entry.get("url") or "")
            if search.startswith("ytsearch") and media_id and not url.startswith("http"):
                url = f"https://www.youtube.com/watch?v={media_id}"
            try:
                validate_media_url(url)
            except ValueError:
                continue
            if not media_id or any(item["url"] == url for item in results):
                continue
            results.append({
                "id": media_id,
                "title": str(entry.get("title") or "DJ set"),
                "uploader": str(entry.get("uploader") or entry.get("channel") or ""),
                "url": url,
                "artworkUrl": str(entry.get("thumbnail") or ""),
                "duration": int(entry.get("duration") or 0),
                "viewCount": int(entry.get("view_count") or 0),
            })
            if len(results) >= result_limit:
                return results
    return results
