#!/usr/bin/env python3
"""API Podmix : recherche de tracklists, catalogues et progression SSE."""

from __future__ import annotations

import json
import ipaddress
import os
import re
import secrets
import hashlib
import socket
import sqlite3
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from audio_fallback import AudioFallbackError, analyze_known_tracklist
from catalog import search_deezer, search_spotify, validate_track
from discovery import discover_tracklist, import_dj_set, search_dj_sets
from live_sets import invalidate_live_set_resolution, parse_live_set_tracklist, resolve_live_set, resolve_live_set_tracklist, search_live_sets
from music_recognition import MAX_SAMPLE_BYTES, MusicRecognitionError, recognize_music
from feeds import import_feed
from openai_timestamping import consolidate_timestamping_sources, openai_timestamping_enabled
from radios import search_radios
from podcasts import search_podcasts
from published_links import extract_published_links
from share_pages import ShareValidationError, normalize_share_payload, render_share_page
from tracklist import align_tracklist, apply_external_timestamps, first_track_match_score, parse_tracklist
from tracklist_sources import search_external_tracklist
from tl1001 import scrape_tracklist, search_tracklist
from websearch import search_episode_tracklist_candidates, search_tracklist_candidates

HOST = os.environ.get("PODMIX_HOST", "127.0.0.1")
PORT = int(os.environ.get("PODMIX_PORT", "8099"))


def parse_published_timestamp(value: str) -> int:
    value = str(value or "").strip()
    if not value:
        return 0
    try:
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())
    except Exception:
        pass
    try:
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())
    except Exception:
        return 0
DATA_DIR = Path(__file__).parent / "data"
DATABASE = DATA_DIR / "podmix.sqlite3"
DB_LOCK = threading.Lock()
WORKER_CONDITION = threading.Condition()
WORKER_STARTED = False
CAST_SESSION_TTL_SECONDS = int(os.environ.get("PODMIX_CAST_SESSION_TTL", str(24 * 60 * 60)))
CAST_PUBLIC_BASE = os.environ.get(
    "PODMIX_CAST_PUBLIC_BASE",
    f"http://127.0.0.1:{PORT}/v1/cast",
).rstrip("/")
# Les SoundTouch de première génération ne négocient pas TLS avec beaucoup de
# CDN actuels. Ce relais HTTP reste limité au LAN du mini-PC et ne sert qu'à
# l'enceinte ; l'application continue d'employer l'API HTTPS publique.
CAST_LAN_BASE = os.environ.get("PODMIX_CAST_LAN_BASE", "").rstrip("/")
WEB_TIMESTAMP_TIMEOUT_SECONDS = int(os.environ.get("PODMIX_WEB_TIMESTAMP_TIMEOUT", "86400"))
AUDIO_FALLBACK_ENABLED = os.environ.get("PODMIX_AUDIO_FALLBACK", "0").strip().lower() not in {
    "0", "false", "no", "off", "disabled",
}
MUSIC_RECOGNITION_RATE_LOCK = threading.Lock()
MUSIC_RECOGNITION_REQUESTS: dict[str, list[float]] = {}
MUSIC_RECOGNITION_PER_HOUR = max(1, int(os.environ.get("PODMIX_MUSIC_RECOGNITION_PER_HOUR", "20")))
SHARE_TTL_SECONDS = max(3600, int(os.environ.get("PODMIX_SHARE_TTL", str(30 * 24 * 60 * 60))))
SHARE_PER_HOUR = max(1, int(os.environ.get("PODMIX_SHARE_PER_HOUR", "20")))
SHARE_PUBLIC_BASE = os.environ.get("PODMIX_SHARE_PUBLIC_BASE", "https://podmix.mb4.fr/s").rstrip("/")
SHARE_BRAND_IMAGE_URL = os.environ.get("PODMIX_SHARE_BRAND_IMAGE_URL", "https://podmix.mb4.fr/podmix/favicon.svg")
SHARE_REQUESTS: dict[str, list[float]] = {}
SHARE_RATE_LOCK = threading.Lock()


def allow_music_recognition(client_address: str) -> bool:
    """Évite qu'un client public épuise le quota AudD du compte Podmix."""
    cutoff = time.time() - 3600
    with MUSIC_RECOGNITION_RATE_LOCK:
        recent = [moment for moment in MUSIC_RECOGNITION_REQUESTS.get(client_address, []) if moment >= cutoff]
        if len(recent) >= MUSIC_RECOGNITION_PER_HOUR:
            MUSIC_RECOGNITION_REQUESTS[client_address] = recent
            return False
        recent.append(time.time())
        MUSIC_RECOGNITION_REQUESTS[client_address] = recent
        return True


def allow_share_creation(client_address: str) -> bool:
    cutoff = time.time() - 3600
    with SHARE_RATE_LOCK:
        recent = [moment for moment in SHARE_REQUESTS.get(client_address, []) if moment >= cutoff]
        if len(recent) >= SHARE_PER_HOUR:
            SHARE_REQUESTS[client_address] = recent
            return False
        recent.append(time.time())
        SHARE_REQUESTS[client_address] = recent
        return True
EXPLICIT_TIMESTAMP = re.compile(r"(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)")


def timestamp_source_for_url(source_url: str) -> str:
    host = (urlparse(source_url).hostname or "").casefold()
    return "youtube" if host in {"youtube.com", "www.youtube.com", "youtu.be"} else "external"


def audio_fallback_source_url(context: dict) -> str:
    """Choisit une source écoutable déjà publiée quand le MP3 RSS manque."""
    audio_url = str(context.get("audioUrl") or "").strip()
    if audio_url:
        return audio_url
    candidates = [str(context.get("sourceUrl") or "").strip()]
    candidates.extend(str(item or "").strip() for item in context.get("publishedLinks") or [])
    for candidate in candidates:
        host = (urlparse(candidate).hostname or "").casefold().removeprefix("www.")
        if host in {"youtube.com", "youtu.be", "soundcloud.com", "mixcloud.com"}:
            return candidate
    return next((candidate for candidate in candidates if candidate), "")


def recover_episode_audio_url(context: dict, episode_id: str) -> str:
    """Retrouve l'enclosure audio dans le RSS quand Android ne l'a pas gardée."""
    audio_url = str(context.get("audioUrl") or "").strip()
    if audio_url:
        return audio_url
    feed_url = str(context.get("feedUrl") or "").strip()
    if not feed_url:
        return ""
    source_kind = str(context.get("sourceKind") or "podcast")
    kind = source_kind if source_kind in {"podcast", "show"} else "podcast"
    feed = import_feed(feed_url, kind=kind, limit=500)
    episodes = feed.get("episodes") or []
    episode = next(
        (item for item in episodes if str(item.get("id") or "").strip() == str(episode_id or "").strip()),
        None,
    )
    if not episode:
        return ""
    return str(episode.get("audioUrl") or "").strip()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_cast_upstream(url: str, headers: dict[str, str], method: str = "GET"):
    opener = build_opener(NoRedirect)
    current = validate_cast_audio_url(url)
    for _ in range(6):
        try:
            return opener.open(Request(current, headers=headers, method=method), timeout=25), current
        except HTTPError as error:
            if error.code in {301, 302, 303, 307, 308} and error.headers.get("Location"):
                current = validate_cast_audio_url(urljoin(current, error.headers["Location"]))
                continue
            raise
    raise ValueError("Trop de redirections audio")


def bose_audio_content_type(value: str | None) -> str | None:
    """Expose un M4A comme audio pour les anciennes Bose SoundTouch.

    Googlevideo décrit parfois un flux *audio-only* M4A par ``video/mp4``.
    Les navigateurs le lisent, mais une SoundTouch le classe comme vidéo puis
    abandonne la source. Le conteneur est pourtant bien un flux AAC/MP4.
    """
    if not value:
        return value
    media_type, separator, parameters = value.partition(";")
    if media_type.strip().lower() != "video/mp4":
        return value
    return "audio/mp4" + (separator + parameters if separator else "")


def validate_cast_audio_url(value: str) -> str:
    """Allow public HTTP audio because legacy SoundTouch speakers cannot use modern HTTPS."""
    parsed = urlparse(value.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Le média doit utiliser une URL HTTP ou HTTPS publique")
    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo(
                parsed.hostname,
                parsed.port or (443 if parsed.scheme == "https" else 80),
                type=socket.SOCK_STREAM,
            )
        }
    except (socket.gaierror, ValueError) as error:
        raise ValueError("Le serveur audio est introuvable") from error
    if not addresses or any(
        address.is_private or address.is_loopback or address.is_link_local
        or address.is_multicast or address.is_reserved or address.is_unspecified
        for address in addresses
    ):
        raise ValueError("L’adresse du serveur audio n’est pas publique")
    return value.strip()


def resolve_cast_duration(url: str, start_seconds: float, requested_duration: float) -> float:
    """Use client metadata only; the VPS never probes or decodes the media."""
    del url
    requested = max(0.0, requested_duration)
    return requested if requested > start_seconds + 5 else 0.0


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc_value, traceback):
        try:
            return super().__exit__(exc_type, exc_value, traceback)
        finally:
            self.close()


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DATABASE, factory=ClosingConnection)
    connection.row_factory = sqlite3.Row
    return connection


def initialize() -> None:
    with connect() as database:
        database.executescript("""
        CREATE TABLE IF NOT EXISTS catalog_cache (
          cache_key TEXT PRIMARY KEY, response_json TEXT NOT NULL, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, episode_id TEXT NOT NULL,
          status TEXT NOT NULL, stage TEXT NOT NULL, progress INTEGER NOT NULL,
          tracks_json TEXT NOT NULL DEFAULT '[]', error TEXT,
          operation TEXT NOT NULL DEFAULT 'research',
          context_json TEXT NOT NULL DEFAULT '{}',
          request_key TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          duration REAL
        );
        CREATE TABLE IF NOT EXISTS cast_sessions (
          token TEXT PRIMARY KEY, source_url TEXT NOT NULL, title TEXT NOT NULL,
          expires_at REAL NOT NULL, created_at TEXT NOT NULL,
          start_seconds REAL NOT NULL DEFAULT 0,
          duration_seconds REAL NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS share_pages (
          token_hash TEXT PRIMARY KEY,
          source_kind TEXT NOT NULL,
          source_title TEXT NOT NULL,
          episode_title TEXT NOT NULL DEFAULT '',
          artist TEXT NOT NULL,
          track_title TEXT NOT NULL,
          source_url TEXT NOT NULL,
          audio_url TEXT NOT NULL DEFAULT '',
          artwork_url TEXT NOT NULL DEFAULT '',
          spotify_url TEXT NOT NULL DEFAULT '',
          deezer_url TEXT NOT NULL DEFAULT '',
          start_seconds REAL NOT NULL,
          end_seconds REAL NOT NULL DEFAULT 0,
          created_at TEXT NOT NULL,
          expires_at REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS share_pages_expires_at ON share_pages(expires_at);
        """)
        cast_columns = {row[1] for row in database.execute("PRAGMA table_info(cast_sessions)")}
        if "start_seconds" not in cast_columns:
            database.execute("ALTER TABLE cast_sessions ADD COLUMN start_seconds REAL NOT NULL DEFAULT 0")
        if "duration_seconds" not in cast_columns:
            database.execute("ALTER TABLE cast_sessions ADD COLUMN duration_seconds REAL NOT NULL DEFAULT 0")
        columns = {row[1] for row in database.execute("PRAGMA table_info(jobs)")}
        if "upload_id" in columns:
            # One-way metadata migration: keep results, detach every job from media.
            database.execute("DROP INDEX IF EXISTS jobs_request_key")
            database.execute("ALTER TABLE jobs RENAME TO jobs_audio_legacy")
            database.executescript("""
            CREATE TABLE jobs (
              id TEXT PRIMARY KEY, episode_id TEXT NOT NULL,
              status TEXT NOT NULL, stage TEXT NOT NULL, progress INTEGER NOT NULL,
              tracks_json TEXT NOT NULL DEFAULT '[]', error TEXT,
              operation TEXT NOT NULL DEFAULT 'research',
              context_json TEXT NOT NULL DEFAULT '{}', request_key TEXT,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL, duration REAL
            );
            INSERT INTO jobs (
              id, episode_id, status, stage, progress, tracks_json, error,
              operation, context_json, request_key, created_at, updated_at, duration
            )
            SELECT id, episode_id,
                   CASE WHEN status IN ('queued', 'running') THEN 'cancelled' ELSE status END,
                   CASE WHEN status IN ('queued', 'running')
                        THEN 'Ancien traitement audio VPS désactivé' ELSE stage END,
                   CASE WHEN status IN ('queued', 'running') THEN 100 ELSE progress END,
                   tracks_json, error, 'research', context_json, request_key,
                   created_at, updated_at, duration
              FROM jobs_audio_legacy;
            DROP TABLE jobs_audio_legacy;
            """)
        database.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS jobs_request_key ON jobs(request_key) WHERE request_key IS NOT NULL"
        )
        database.execute(
            "UPDATE jobs SET status = 'queued', stage = 'Recherche reprise après redémarrage', updated_at = ? WHERE status = 'running'",
            (now(),),
        )


def share_token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def cleanup_expired_shares(database: sqlite3.Connection) -> None:
    database.execute("DELETE FROM share_pages WHERE expires_at <= ?", (time.time(),))


def create_share(payload: object) -> dict:
    share = normalize_share_payload(payload)
    token = secrets.token_urlsafe(24)
    expires_at = time.time() + SHARE_TTL_SECONDS
    with DB_LOCK, connect() as database:
        cleanup_expired_shares(database)
        database.execute(
            """INSERT INTO share_pages (
                token_hash, source_kind, source_title, episode_title, artist, track_title,
                source_url, audio_url, artwork_url, spotify_url, deezer_url, start_seconds,
                end_seconds, created_at, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                share_token_hash(token), share["source_kind"], share["source_title"], share["episode_title"],
                share["artist"], share["track_title"], share["source_url"], share["audio_url"],
                share["artwork_url"], share["spotify_url"], share["deezer_url"], share["start_seconds"],
                share["end_seconds"], now(), expires_at,
            ),
        )
    return {
        "id": token,
        "shareUrl": f"{SHARE_PUBLIC_BASE}/{token}",
        "expiresAt": datetime.fromtimestamp(expires_at, timezone.utc).isoformat(),
    }


def get_share(token: str) -> dict | None:
    if not re.fullmatch(r"[A-Za-z0-9_-]{20,128}", token):
        return None
    with DB_LOCK, connect() as database:
        cleanup_expired_shares(database)
        row = database.execute("SELECT * FROM share_pages WHERE token_hash = ?", (share_token_hash(token),)).fetchone()
    return dict(row) if row else None


def row_to_job(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"], "episodeId": row["episode_id"], "status": row["status"],
        "stage": row["stage"], "progress": row["progress"],
        "createdAt": row["created_at"], "updatedAt": row["updated_at"],
        "tracks": json.loads(row["tracks_json"]), "duration": row["duration"],
        "operation": row["operation"],
        **({"error": row["error"]} if row["error"] else {}),
    }


def claim_web_timestamp_job() -> dict | None:
    expire_web_timestamp_jobs()
    # A restart may interrupt a phone while it owns this phase. Its short lease
    # expires so the same (or another) connected phone can resume the job.
    stale_claim_cutoff = datetime.fromtimestamp(time.time() - 90, timezone.utc).isoformat()
    with DB_LOCK, connect() as database:
        row = database.execute(
            """
            SELECT * FROM jobs
             WHERE status = 'web_pending'
               AND operation = 'research'
               AND COALESCE(json_extract(context_json, '$.webQueue'), 0) = 1
               AND (stage != 'Traitement Android des timestamps Web' OR updated_at < ?)
             ORDER BY CASE
                        WHEN COALESCE(json_array_length(json_extract(context_json, '$.publishedLinks')), 0) > 0
                        THEN 0 ELSE 1
                      END,
                      COALESCE(json_extract(context_json, '$.publishedTimestamp'), 0) DESC,
                      created_at DESC,
                      id
             LIMIT 1
            """
        , (stale_claim_cutoff,)).fetchone()
        if not row:
            return None
        database.execute(
            """
            UPDATE jobs
               SET stage = 'Traitement Android des timestamps Web',
                   updated_at = ?
             WHERE id = ? AND status = 'web_pending'
            """,
            (now(), row["id"]),
        )
    context = json.loads(row["context_json"] or "{}")
    web_candidates = list(context.get("webCandidates") or [])
    preferred_1001_url = str(context.get("preferred1001Url") or "").strip()
    if preferred_1001_url:
        web_candidates.insert(0, {
            "url": preferred_1001_url,
            "title": "URL 1001Tracklists mémorisée",
            "domain": "1001tracklists.com",
        })
    title = str(context.get("title") or "").strip()
    reference_tracks = context.get("rssTracks") or row_to_job(row).get("tracks") or []
    if not web_candidates and title:
        try:
            web_candidates = [
                candidate for candidate in search_episode_tracklist_candidates(title, reference_tracks, 8)
                if str(candidate.get("domain") or "").endswith("1001tracklists.com")
            ][:6]
        except Exception:
            web_candidates = []
    return {
        "id": row["id"],
        "episodeId": row["episode_id"],
        "title": title,
        "referenceFirstTrack": context.get("referenceFirstTrack") or {},
        "webCandidates": web_candidates,
        "publishedLinks": context.get("publishedLinks") or [],
        "duration": row["duration"],
    }


def complete_web_timestamp_job(job_id: str, payload: dict) -> dict:
    job = get_job(job_id)
    if not job:
        raise ValueError("job_not_found")
    context = get_job_context(job_id)
    candidates = payload.get("candidates") or []
    reference = context.get("referenceFirstTrack") or {}
    if not candidates:
        raise ValueError("Aucune piste externe reçue")
    if not _authoritative_external_candidates(candidates, reference, float(job.get("duration") or 0)):
        raise ValueError("La tracklist externe est incomplète ou ne correspond pas à l’épisode RSS")
    reference_tracks = [
        {
            "artist": track.get("artist", ""),
            "title": track.get("title", ""),
            "providedTime": track.get("time"),
        }
        for track in job["tracks"]
    ]
    source_url = str(payload.get("sourceUrl") or "")
    candidates = apply_external_timestamps(reference_tracks, candidates)
    source_timestamp = timestamp_source_for_url(source_url)
    tracks = align_tracklist(candidates, job["tracks"], job["duration"], timestamp_source=source_timestamp)
    evidence = (
        f"YouTube via PodmixNG : {source_url}" if source_timestamp == "youtube"
        else f"1001Tracklists via PodmixNG : {source_url}"
    ) if source_url else (
        "Tracklist YouTube via PodmixNG" if source_timestamp == "youtube"
        else "Tracklist Web via PodmixNG"
    )
    for track in tracks:
        track.setdefault("evidence", []).insert(0, evidence)
    update_job(job_id, tracks=tracks, status="completed", stage="Timestamps Web vérifiés", progress=100, error="")
    return get_job(job_id) or {}


def fail_web_timestamp_job(job_id: str, message: str) -> dict:
    job = get_job(job_id)
    if not job:
        raise ValueError("job_not_found")
    clean_message = (message or "Aucune tracklist Web exploitable").strip()
    if len(clean_message) > 500:
        clean_message = clean_message[:497] + "..."
    context = get_job_context(job_id)
    known_tracks = job.get("tracks") or context.get("rssTracks") or []
    fallback_source = audio_fallback_source_url(context)
    audio_fallback_allowed = (
        AUDIO_FALLBACK_ENABLED
        # Un ancien fallback ayant échoué avant un correctif peut être repris
        # explicitement depuis son échec Web ; un job courant ne boucle pas,
        # car il n'atteint cette fonction qu'une seule fois.
        and (not context.get("audioFallbackAttempted") or job.get("status") == "failed")
        and len(known_tracks) >= 2
        and bool(fallback_source or str(context.get("feedUrl") or "").strip())
        # À ce stade RSS, YouTube et les sources Web ont toutes échoué. Dès
        # qu'une tracklist ordonnée et l'audio existent, le fallback doit être
        # tenté : les métadonnées anciennes ne sont pas une raison de perdre
        # l'unique procédure capable de produire des repères.
    )
    if audio_fallback_allowed:
        # L'URL audio RSS est parfois absente de vieux imports Android. Dans
        # ce cas la première source média publiée (YouTube/SoundCloud) devient
        # explicitement la source du fallback local.
        if not context.get("audioUrl"):
            context["sourceUrl"] = fallback_source
        context["audioFallbackPending"] = True
        context["audioFallbackAttempted"] = True
        context["webQueue"] = False
        context["webFailure"] = clean_message
        update_job(
            job_id,
            status="queued",
            stage="Fallback audio techno/trance en attente",
            progress=83,
            error="",
            context=context,
        )
        notify_worker()
        return get_job(job_id) or {}
    update_job(
        job_id,
        status="failed",
        stage="Timestamps Web indisponibles",
        progress=100,
        error=clean_message,
    )
    return get_job(job_id) or {}


def expire_web_timestamp_jobs() -> int:
    cutoff = datetime.fromtimestamp(
        time.time() - max(30, WEB_TIMESTAMP_TIMEOUT_SECONDS),
        timezone.utc,
    ).isoformat()
    with DB_LOCK, connect() as database:
        cursor = database.execute(
            """
            UPDATE jobs
               SET status = 'completed',
                   stage = 'Recherche Web expirée · titres conservés',
                   progress = 100,
                   updated_at = ?
             WHERE status = 'web_pending'
               AND updated_at < ?
            """,
            (now(), cutoff),
        )
        return cursor.rowcount


def get_job(job_id: str) -> dict | None:
    with DB_LOCK, connect() as database:
        row = database.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
    return row_to_job(row) if row else None


def get_job_by_request_key(request_key: str) -> dict | None:
    if not request_key:
        return None
    with DB_LOCK, connect() as database:
        row = database.execute(
            "SELECT * FROM jobs WHERE request_key = ? ORDER BY created_at DESC LIMIT 1",
            (request_key,),
        ).fetchone()
    return row_to_job(row) if row else None


def update_job(job_id: str, **changes: object) -> None:
    names = {
        "status": "status", "stage": "stage", "progress": "progress",
        "tracks": "tracks_json", "error": "error", "duration": "duration",
        "operation": "operation", "context": "context_json",
    }
    values, assignments = [], []
    for key, value in changes.items():
        if key not in names:
            continue
        assignments.append(f"{names[key]} = ?")
        values.append(json.dumps(value, ensure_ascii=False) if key in {"tracks", "context"} else value)
    assignments.append("updated_at = ?")
    values.extend([now(), job_id])
    with DB_LOCK, connect() as database:
        database.execute(f"UPDATE jobs SET {', '.join(assignments)} WHERE id = ?", values)


def get_job_context(job_id: str) -> dict:
    with DB_LOCK, connect() as database:
        row = database.execute("SELECT context_json FROM jobs WHERE id = ?", (job_id,)).fetchone()
    if not row:
        return {}
    try:
        return json.loads(row["context_json"] or "{}")
    except json.JSONDecodeError:
        return {}


def _authoritative_external_candidates(
    candidates: list[dict],
    reference: dict | None,
    duration: float,
) -> bool:
    if len(candidates) < 2:
        return False
    # Au moins 60% des morceaux doivent avoir un repère temporel valide
    timed_items = [item for item in candidates if item.get("providedTime") is not None]
    if len(timed_items) / max(1, len(candidates)) < 0.6:
        return False
    timestamps = [float(item["providedTime"]) for item in timed_items]
    # Une page peut contenir des champs cachés initialisés à 0 pour une partie
    # ou la totalité des morceaux. Un vrai début à 0 est permis, mais au moins
    # 60 % de la tracklist doit avoir une position strictement positive.
    positive_timestamps = [timestamp for timestamp in timestamps if timestamp > 0]
    minimum_positive = 1 if len(candidates) == 2 else max(1, (len(candidates) * 3 + 4) // 5)
    if len(positive_timestamps) < minimum_positive:
        return False
    if timestamps[0] < 0 or any(current < previous for previous, current in zip(timestamps, timestamps[1:])):
        return False
    if duration and timestamps[-1] > duration + 120:
        return False
    return not reference or first_track_match_score(candidates, reference) >= 0.5


def _discover_web_candidates(
    title: str,
    rss_candidates: list[dict],
    reference: dict | None,
    duration: float,
) -> tuple[dict | None, list[dict], list[dict]]:
    """Discover pages through a search index, then parse accessible sources."""

    results = search_episode_tracklist_candidates(title, rss_candidates, limit=8)
    sources: list[dict] = []
    selected: dict | None = None
    for result in results:
        url = str(result.get("url") or "")
        domain = str(result.get("domain") or "")
        snippet = "\n".join(filter(None, [
            str(result.get("title") or ""),
            str(result.get("snippet") or ""),
        ]))
        if snippet:
            sources.append({
                "kind": f"web-search:{domain or 'result'}",
                "url": url,
                "title": str(result.get("title") or title),
                "text": snippet,
            })
        try:
            if domain == "1001tracklists.com":
                discovery = {**scrape_tracklist(url), "source": "1001tracklists"}
            elif domain in {"youtube.com", "youtu.be", "soundcloud.com", "mixcloud.com"}:
                discovery = {**discover_tracklist(url), "source": domain}
            else:
                continue
        except Exception:
            continue
        if discovery.get("sourceText"):
            sources.append({
                "kind": discovery.get("source") or domain or "web",
                "url": discovery.get("sourceUrl") or url,
                "title": discovery.get("title") or str(result.get("title") or title),
                "text": discovery.get("sourceText") or "",
            })
        discovered = discovery.get("candidates") or []
        if _authoritative_external_candidates(discovered, reference, duration):
            selected = discovery
            break
    return selected, sources, results


def _discover_published_source(url: str) -> dict:
    host = (urlparse(url).hostname or "").casefold()
    if host in {"1001tracklists.com", "www.1001tracklists.com"}:
        return {**scrape_tracklist(url), "source": "1001tracklists", "extractor": "1001Tracklists"}
    return discover_tracklist(url)


def _has_timestamping_text(source: dict) -> bool:
    text = str(source.get("text") or "")
    if len(EXPLICIT_TIMESTAMP.findall(text)) >= 2:
        return True
    candidates = parse_tracklist(text, structured_only=True)
    return len(candidates) >= 2 and any(item.get("providedTime") is not None for item in candidates)


def _automatic_tracklist(
    job_id: str,
    duration: float,
    context: dict,
) -> list[dict]:
    if not context.get("automaticTracklist"):
        return []
    candidates: list[dict] = []
    rss_candidates: list[dict] = []
    consolidation_sources: list[dict] = []
    timestamp_source = "rss"
    evidence = ""
    description = str(context.get("description") or "")
    if description:
        consolidation_sources.append({
            "kind": "rss",
            "url": str(context.get("sourceUrl") or ""),
            "title": str(context.get("title") or ""),
            "text": description,
        })
        update_job(job_id, stage="Lecture de la tracklist publiée", progress=64)
        rss_candidates = parse_tracklist(description, structured_only=True)
        if len(rss_candidates) >= 2 and all(track.get("providedTime") is not None for track in rss_candidates):
            candidates = rss_candidates
            evidence = "Tracklist publiée dans la description RSS avec timestamps"
        elif len(rss_candidates) >= 2:
            # Les titres RSS sont fiables, mais leurs positions doivent encore
            # être recherchées sur le Web avant de basculer vers le VPS.
            evidence = "Tracklist publiée dans la description RSS · timestamps absents"

    source_url = str(context.get("sourceUrl") or "")
    needs_external_timestamps = bool(rss_candidates) and not all(
        track.get("providedTime") is not None for track in rss_candidates
    )
    reference = context.get("referenceFirstTrack") or (rss_candidates[0] if rss_candidates else {})
    if reference and not context.get("referenceFirstTrack"):
        context["referenceFirstTrack"] = reference
        update_job(job_id, context=context)
    published_links = extract_published_links(description) if description else []
    if published_links:
        context["publishedLinks"] = published_links
        existing_web = list(context.get("webCandidates") or [])
        for link in published_links:
            domain = (urlparse(link).hostname or "").removeprefix("www.")
            if domain == "1001tracklists.com" and not any(item.get("url") == link for item in existing_web):
                existing_web.append({"url": link, "title": str(context.get("title") or ""), "domain": domain, "address": ""})
        context["webCandidates"] = existing_web[:8]
        update_job(job_id, context=context)

    source_urls = list(dict.fromkeys(filter(None, [source_url, *published_links])))
    if (not candidates or needs_external_timestamps) and source_urls:
        update_job(job_id, stage="Exploration des liens publiés, chapitres et commentaires", progress=69)
    for published_url in source_urls:
        if candidates and not needs_external_timestamps:
            break
        try:
            discovery = _discover_published_source(published_url)
            if discovery.get("sourceText"):
                consolidation_sources.append({
                    "kind": f"media:{discovery.get('extractor') or 'web'}",
                    "url": discovery.get("sourceUrl") or published_url,
                    "title": discovery.get("title") or str(context.get("title") or ""),
                    "text": discovery.get("sourceText") or "",
                })
            discovered = discovery.get("candidates") or []
            if discovered and reference and first_track_match_score(discovered, reference) < 0.5:
                discovered = []
            if (
                _authoritative_external_candidates(discovered, reference, duration)
                and (not candidates or needs_external_timestamps)
            ):
                candidates = discovered
                timestamp_source = timestamp_source_for_url(str(discovery.get("sourceUrl") or published_url))
                needs_external_timestamps = False
                evidence = f"Métadonnées {discovery.get('extractor') or discovery.get('source') or 'Web'} : {discovery.get('sourceUrl') or published_url}"
        except Exception:
            pass

    external_search_enabled = context.get(
        "enableExternalTracklists",
        context.get("enable1001"),
    ) or needs_external_timestamps
    fast_web_queue = bool(context.get("webQueue") and needs_external_timestamps and rss_candidates)
    if (not candidates or needs_external_timestamps) and external_search_enabled and not fast_web_queue and context.get("title"):
        update_job(job_id, stage="Scraping Web et recherche de timestamps", progress=74)
        try:
            query = str(context["title"])
            query = " ".join(filter(None, [query, str(reference.get("artist") or ""), str(reference.get("title") or "")]))
            discovery = search_external_tracklist(query)
            if discovery.get("sourceText"):
                consolidation_sources.append({
                    "kind": discovery.get("source") or "external",
                    "url": discovery.get("sourceUrl") or "",
                    "title": discovery.get("title") or str(context.get("title") or ""),
                    "text": discovery.get("sourceText") or "",
                })
            discovered = discovery["candidates"]
            if _authoritative_external_candidates(discovered, reference, duration):
                candidates = discovered
                timestamp_source = timestamp_source_for_url(str(discovery.get("sourceUrl") or ""))
                needs_external_timestamps = False
                source_name = (
                    "MixesDB"
                    if discovery.get("source") == "mixesdb"
                    else "1001Tracklists"
                )
                evidence = f"{source_name} : {discovery['sourceUrl']}"
        except Exception:
            pass

    if (not candidates or needs_external_timestamps) and external_search_enabled and not fast_web_queue and context.get("title"):
        update_job(job_id, stage="Recherche Web multi-sources", progress=76)
        try:
            discovery, web_sources, web_results = _discover_web_candidates(
                str(context["title"]),
                rss_candidates,
                reference,
                duration,
            )
            consolidation_sources.extend(web_sources)
            if web_results:
                discovered_web_candidates = [
                    {
                        "url": str(result.get("url") or ""),
                        "title": str(result.get("title") or ""),
                        "domain": str(result.get("domain") or ""),
                        "address": str(result.get("address") or ""),
                    }
                    for result in web_results[:8]
                ]
                existing_web = list(context.get("webCandidates") or [])
                context["webCandidates"] = (existing_web + [
                    item for item in discovered_web_candidates
                    if not any(existing.get("url") == item.get("url") for existing in existing_web)
                ])[:8]
                update_job(job_id, context=context)
            if discovery:
                candidates = discovery.get("candidates") or []
                timestamp_source = timestamp_source_for_url(str(discovery.get("sourceUrl") or ""))
                needs_external_timestamps = False
                evidence = f"Recherche Web : {discovery.get('sourceUrl') or ''}".strip()
        except Exception:
            pass

    web_consolidation_sources = [
        source for source in consolidation_sources
        if source.get("kind") != "rss" and _has_timestamping_text(source)
    ]
    should_consolidate_with_openai = (
        openai_timestamping_enabled()
        and web_consolidation_sources
        and (not candidates or needs_external_timestamps)
    )
    if should_consolidate_with_openai:
        update_job(job_id, stage="Consolidation IA des sources Web timestamping", progress=78)
        consolidated = consolidate_timestamping_sources(
            episode_title=str(context.get("title") or ""),
            reference_first_track=context.get("referenceFirstTrack"),
            sources=web_consolidation_sources,
        )
        discovered = consolidated.get("candidates") or []
        if _authoritative_external_candidates(discovered, reference, duration):
            candidates = discovered
            timestamp_source = timestamp_source_for_url(str(discovery.get("sourceUrl") or ""))
            needs_external_timestamps = False
            evidence = " · ".join(consolidated.get("evidence") or ["IA : consolidation des sources Web"])

    if not candidates:
        if rss_candidates:
            tracks = align_tracklist(rss_candidates, [], duration, timestamp_source="rss")
            for track in tracks:
                track.setdefault("evidence", []).insert(0, "Titres RSS disponibles · timestamps Web à rechercher")
            return tracks
        return []
    if timestamp_source in {"external", "youtube"} and rss_candidates:
        candidates = apply_external_timestamps(rss_candidates, candidates)
    tracks = align_tracklist(candidates, [], duration, timestamp_source=timestamp_source)
    for track in tracks:
        if evidence:
            track.setdefault("evidence", []).insert(0, evidence)
    return tracks


def run_research_job(job_id: str) -> None:
    try:
        context = get_job_context(job_id)
        if context.get("audioFallbackPending"):
            update_job(job_id, status="running", stage="Préparation du fallback audio techno/trance", progress=84)
            job = get_job(job_id) or {}
            audio_url = recover_episode_audio_url(context, str(job.get("episodeId") or ""))
            if audio_url and not context.get("audioUrl"):
                context["audioUrl"] = audio_url
                update_job(job_id, context=context)
            source_tracks = job.get("tracks") or context.get("rssTracks") or []
            candidates = [
                {
                    "artist": track.get("artist") or "Artiste inconnu",
                    "title": track.get("title") or "",
                }
                for track in source_tracks
                if track.get("title")
            ]
            results = analyze_known_tracklist(
                audio_url=audio_url,
                source_url=str(context.get("sourceUrl") or ""),
                tracks=candidates,
                duration_seconds=max(0.0, float(job.get("duration") or 0)),
                source_urls=list(context.get("publishedLinks") or []),
                on_progress=lambda stage, progress: update_job(job_id, stage=stage, progress=progress),
            )
            tracks = align_tracklist(results, [], job.get("duration"), timestamp_source="audio")
            for track, result in zip(tracks, results):
                track["confidence"] = int(result.get("confidence") or track.get("confidence") or 0)
                track["timestampScore"] = round(track["confidence"] / 100, 2)
                track["evidence"] = list(result.get("evidence") or [])
            context["audioFallbackPending"] = False
            update_job(
                job_id,
                status="completed",
                stage="Fallback audio techno/trance terminé",
                progress=100,
                tracks=tracks,
                context=context,
                error="",
            )
            return
        update_job(job_id, status="running", stage="Lecture des métadonnées RSS", progress=5)
        job = get_job(job_id) or {}
        duration = max(0.0, float(job.get("duration") or 0))
        if get_job(job_id)["status"] == "cancelled":
            return
        tracks = _automatic_tracklist(job_id, duration, get_job_context(job_id))
        if get_job(job_id)["status"] == "cancelled":
            return
        context = get_job_context(job_id)
        has_verified_timestamps = any(
            track.get("timestampSource") in {"rss", "external", "youtube"}
            and track.get("timestampStatus") == "provided"
            for track in tracks
        )
        if (
            context.get("webQueue")
            and context.get("automaticTracklist")
            and not has_verified_timestamps
        ):
            update_job(
                job_id,
                status="web_pending",
                stage="En attente du navigateur Android pour timestamps Web",
                progress=82,
                tracks=tracks,
                duration=duration,
            )
        else:
            update_job(job_id, status="completed", stage="Recherche terminée", progress=100, tracks=tracks, duration=duration)
    except Exception as error:
        stage = "Échec du fallback audio" if isinstance(error, AudioFallbackError) else "Échec de la recherche"
        update_job(job_id, status="failed", stage=stage, progress=100, error=str(error))


def claim_next_job() -> dict | None:
    expire_web_timestamp_jobs()
    with DB_LOCK, connect() as database:
        database.execute("BEGIN IMMEDIATE")
        row = database.execute(
            """
            SELECT id, operation
              FROM jobs
             WHERE status = 'queued' AND operation = 'research'
             ORDER BY COALESCE(json_extract(context_json, '$.publishedTimestamp'), 0) DESC,
                      jobs.created_at DESC,
                      jobs.id
             LIMIT 1
            """
        ).fetchone()
        if not row:
            return None
        database.execute(
            "UPDATE jobs SET status = 'running', updated_at = ? WHERE id = ? AND status = 'queued'",
            (now(), row["id"]),
        )
    return {"id": row["id"], "operation": row["operation"]}


def notify_worker() -> None:
    with WORKER_CONDITION:
        WORKER_CONDITION.notify()


def worker_loop() -> None:
    while True:
        claimed = claim_next_job()
        if not claimed:
            with WORKER_CONDITION:
                WORKER_CONDITION.wait(timeout=2)
            continue
        run_research_job(claimed["id"])


def start_worker() -> None:
    global WORKER_STARTED
    if WORKER_STARTED:
        return
    WORKER_STARTED = True
    threading.Thread(target=worker_loop, name="podmix-job-worker", daemon=True).start()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[podmix-api] {fmt % args}")

    def cors(self) -> None:
        origin = self.headers.get("Origin", "")
        configured = {value.strip().rstrip("/") for value in os.environ.get("PODMIX_ALLOWED_ORIGINS", "").split(",") if value.strip()}
        allowed = {
            "http://localhost:4173", "http://127.0.0.1:4173",
            "http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost", "http://127.0.0.1", "https://localhost", "capacitor://localhost",
            "file://", "capacitor://",
            *configured,
        }
        self.send_header("Access-Control-Allow-Origin", origin if origin in allowed else "http://localhost:5173")
        self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Filename")

    def json_response(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status); self.cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

    def html_response(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Security-Policy", "default-src 'none'; img-src https: data:; media-src https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "private, max-age=300")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(204); self.cors(); self.send_header("Content-Length", "0"); self.end_headers()

    def relay_cast(self, token: str, head_only: bool = False) -> None:
        with DB_LOCK, connect() as database:
            database.execute("DELETE FROM cast_sessions WHERE expires_at <= ?", (time.time(),))
            session = database.execute(
                "SELECT source_url, start_seconds, duration_seconds FROM cast_sessions WHERE token = ? AND expires_at > ?",
                (token, time.time()),
            ).fetchone()
        if not session:
            self.json_response({"error": "cast_session_not_found"}, 404)
            return

        start_seconds = max(0.0, float(session["start_seconds"] or 0))
        current = validate_cast_audio_url(session["source_url"])
        # Un DJ set est déjà exposé par l'API publique sous
        # /v1/live-sets/stream?url=… . Ne pas faire repasser la Bose par cette
        # URL HTTPS : la SoundTouch doit pouvoir demander directement au
        # mini-PC le flux résolu (et ses en-têtes yt-dlp).
        parsed_source = urlparse(current)
        # L'API est publiée derrière /podmix-api/ en production, alors que
        # le chemin interne est /v1/… . Accepter les deux afin que les DJ
        # sets passent bien par le transcodage MP3 SoundTouch.
        if parsed_source.path.rstrip("/").endswith("/v1/live-sets/stream"):
            source_url = parse_qs(parsed_source.query).get("url", [""])[0]
            if source_url:
                # Les vieux modèles SoundTouch savent lire le MP3 de manière
                # fiable, alors qu'ils rejettent certains M4A progressifs
                # YouTube malgré un en-tête audio/mp4 correct.
                self.relay_live_set(source_url, head_only, as_mp3=True, start_seconds=start_seconds)
                return
        response = None
        try:
            duration_seconds = resolve_cast_duration(
                current,
                start_seconds,
                float(session["duration_seconds"] or 0),
            )
            if start_seconds > 2 and duration_seconds > start_seconds + 5 and not head_only:
                head_response = None
                try:
                    head_response, resolved = open_cast_upstream(
                        current,
                        {"User-Agent": "Podmix-Cast/1.0", "Accept": "audio/*,*/*;q=0.8"},
                        "HEAD",
                    )
                    total_size = int(head_response.headers.get("Content-Length") or 0)
                    media_type = head_response.headers.get("Content-Type") or ""
                    is_mp3 = "audio/mpeg" in media_type.lower() or urlparse(resolved).path.lower().endswith(".mp3")
                    if total_size > 0 and is_mp3:
                        byte_offset = int(total_size * max(0.0, start_seconds - 0.25) / duration_seconds)
                        byte_offset = max(0, min(total_size - 1, byte_offset - (byte_offset % 4096)))
                        head_response.close()
                        head_response = None
                        response, current = open_cast_upstream(
                            current,
                            {
                                "User-Agent": "Podmix-Cast/1.0",
                                "Accept": "audio/mpeg,audio/*;q=0.8",
                                "Range": f"bytes={byte_offset}-",
                            },
                        )
                        if getattr(response, "status", 200) == 206:
                            remaining = int(response.headers.get("Content-Length") or (total_size - byte_offset))
                            self.send_response(200)
                            self.send_header("Content-Type", "audio/mpeg")
                            self.send_header("Content-Length", str(remaining))
                            self.send_header("Accept-Ranges", "bytes")
                            self.send_header("Cache-Control", "no-store")
                            self.send_header("Connection", "close")
                            self.end_headers()
                            while True:
                                chunk = response.read(64 * 1024)
                                if not chunk:
                                    break
                                self.wfile.write(chunk)
                                self.wfile.flush()
                            return
                        response.close()
                        response = None
                except Exception:
                    if response is not None:
                        response.close()
                        response = None
                finally:
                    if head_response is not None:
                        head_response.close()
            headers = {
                "User-Agent": "Podmix-Cast/1.0",
                "Accept": "audio/*,*/*;q=0.8",
                "Icy-MetaData": self.headers.get("Icy-MetaData", "0"),
            }
            if self.headers.get("Range"):
                headers["Range"] = self.headers["Range"]
            response, current = open_cast_upstream(current, headers, "HEAD" if head_only else "GET")

            upstream_status = getattr(response, "status", 200)
            # Les anciennes SoundTouch refusent un premier flux en 206 quand
            # elles n'ont jamais demandé de Range. Cela arrive notamment avec
            # les CDN DJ, qui répondent partiellement par défaut.
            normalize_partial = upstream_status == 206 and not self.headers.get("Range")
            self.send_response(200 if normalize_partial else upstream_status)
            for name in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Icy-Br", "Icy-Genre", "Icy-Name", "Icy-Url"):
                if normalize_partial and name == "Content-Range":
                    continue
                value = response.headers.get(name)
                if value:
                    self.send_header(name, value)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            if not head_only:
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            if not self.wfile.closed:
                try:
                    self.json_response({"error": "cast_upstream_unavailable", "message": str(error)}, 502)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        finally:
            if response is not None:
                response.close()
            self.close_connection = True

    def relay_live_set_as_mp3(self, details: dict, head_only: bool, start_seconds: float = 0) -> None:
        """Transcode un DJ set en MP3 pour une SoundTouch ancienne."""
        if head_only:
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Accept-Ranges", "none")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            return

        headers = {str(key): str(value) for key, value in (details.get("audioHeaders") or {}).items()}
        header_lines = "".join(f"{key}: {value}\\r\\n" for key, value in headers.items())
        command = [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-user_agent", "Podmix-Cast/1.0",
        ]
        if header_lines:
            command.extend(["-headers", header_lines])
        # Le flux MP3 est une nouvelle connexion : repartir au même repère que
        # le morceau choisi garde l'audio, la barre et la ligne active alignés.
        if start_seconds > 0.25:
            command.extend(["-ss", f"{start_seconds:.3f}"])
        command.extend([
            "-i", str(details["audioUrl"]), "-map", "0:a:0", "-vn",
            "-c:a", "libmp3lame", "-b:a", "192k", "-f", "mp3", "pipe:1",
        ])
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Accept-Ranges", "none")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            assert process.stdout is not None
            while True:
                chunk = process.stdout.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
            if process.wait(timeout=5) != 0:
                error = (process.stderr.read() if process.stderr else b"").decode("utf-8", "replace").strip()
                self.log_message("DJ MP3 relay stopped: %s", error or "ffmpeg failed")
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
            self.close_connection = True

    def relay_live_set(
        self,
        source_url: str,
        head_only: bool = False,
        as_mp3: bool = False,
        start_seconds: float = 0,
    ) -> None:
        """Relaye un flux DJ résolu avec les en-têtes yt-dlp associés.

        Googlevideo refuse les URL signées ouvertes directement par Android
        (403). Le relais garde le set hors des pipelines podcasts tout en
        laissant le serveur qui a résolu l'URL effectuer la lecture HTTP.
        """
        response = None
        try:
            details = resolve_live_set(source_url)
            if as_mp3:
                self.relay_live_set_as_mp3(details, head_only, start_seconds)
                return
            # Media3 peut ouvrir le flux sans en-tête Range au premier accès.
            # Googlevideo est plus fiable avec une requête partielle : garder
            # celle du lecteur si elle existe, sinon en demander une ouverte.
            requested_range = self.headers.get("Range")
            # Les URL audio YouTube/SoundCloud sont signées. Si le cache
            # contient une URL expirée, une réponse 401/403/410 force une
            # résolution fraîche avant que DownloadManager ne voie l'erreur.
            first_chunk = b""
            for attempt in range(2):
                headers = {str(key): str(value) for key, value in (details.get("audioHeaders") or {}).items()}
                headers.setdefault("Accept", "audio/*,*/*;q=0.8")
                headers["Range"] = requested_range or "bytes=0-"
                try:
                    response, _ = open_cast_upstream(str(details["audioUrl"]), headers, "HEAD" if head_only else "GET")
                    # Do not commit a successful HTTP response to Media3 until
                    # the upstream has produced actual media bytes. A signed
                    # Googlevideo URL can accept the request and then stall;
                    # in that case refresh the yt-dlp resolution once.
                    if not head_only:
                        first_chunk = response.read(64 * 1024)
                        if not first_chunk:
                            raise OSError("Le flux DJ est vide")
                    break
                except (HTTPError, OSError, TimeoutError, socket.timeout) as error:
                    refreshable = not isinstance(error, HTTPError) or error.code in {401, 403, 410}
                    if response is not None:
                        response.close()
                        response = None
                    if attempt == 0 and refreshable:
                        invalidate_live_set_resolution(source_url)
                        details = resolve_live_set(source_url)
                        continue
                    raise
            upstream_status = getattr(response, "status", 200)
            # SoundTouch interprète un premier 206 comme une source invalide,
            # tandis que Googlevideo répond en 206 même pour bytes=0-.
            normalize_partial = upstream_status == 206 and not requested_range
            self.send_response(200 if normalize_partial else upstream_status)
            self.cors()
            for name in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"):
                if normalize_partial and name == "Content-Range":
                    continue
                value = response.headers.get(name)
                if name == "Content-Type":
                    value = bose_audio_content_type(value)
                if value:
                    self.send_header(name, value)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            if not head_only:
                self.wfile.write(first_chunk)
                self.wfile.flush()
                while True:
                    chunk = response.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            print(f"[podmix-api] live set stream failed for {source_url!r}: {error!r}", flush=True)
            if not self.wfile.closed:
                try:
                    self.json_response({"error": "live_set_stream_unavailable", "message": str(error)}, 502)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        finally:
            if response is not None:
                response.close()
            self.close_connection = True

    def do_HEAD(self) -> None:
        parsed_url = urlparse(self.path)
        parts = parsed_url.path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["v1", "cast"]:
            self.relay_cast(parts[2], head_only=True)
            return
        if parsed_url.path == "/v1/live-sets/stream":
            source_url = parse_qs(parsed_url.query).get("url", [""])[0]
            if source_url:
                self.relay_live_set(source_url, head_only=True)
                return
        self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()

    def do_GET(self) -> None:
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        share_match = re.fullmatch(r"/s/([A-Za-z0-9_-]{20,128})", path)
        if share_match:
            token = share_match.group(1)
            share = get_share(token)
            if not share:
                self.html_response("<!doctype html><title>Lien indisponible</title><p>Ce lien Podmix est indisponible ou a expiré.</p>".encode(), 410)
                return
            self.html_response(render_share_page(share, f"{SHARE_PUBLIC_BASE}/{token}", SHARE_BRAND_IMAGE_URL))
            return
        if path == "/health":
            self.json_response({"status": "healthy", "service": "podmix-api", "mode": "rss-web-ai"})
            return
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["v1", "cast"]:
            self.relay_cast(parts[2])
            return
        if path == "/v1/live-sets/stream":
            source_url = parse_qs(parsed_url.query).get("url", [""])[0]
            if not source_url:
                self.json_response({"error": "url_required", "message": "L’URL du live set est obligatoire"}, 400)
                return
            self.relay_live_set(source_url)
            return
        if path == "/v1/tracklists/candidates":
            parameters = parse_qs(parsed_url.query)
            query = parameters.get("q", [""])[0]
            limit = max(1, min(int(parameters.get("limit", ["8"])[0]), 10))
            try:
                self.json_response({"query": query, "results": search_tracklist_candidates(query, limit)})
            except ValueError as error:
                self.json_response({"error": "invalid_search", "message": str(error)}, 400)
            except Exception as error:
                self.json_response({"error": "search_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/web-timestamp-jobs/next":
            self.json_response(claim_web_timestamp_job() or {})
            return
        if path == "/v1/detection-jobs":
            request_key = parse_qs(parsed_url.query).get("requestKey", [""])[0].strip()
            if not request_key:
                self.json_response({"error": "request_key_required"}, 400)
                return
            job = get_job_by_request_key(request_key)
            if not job:
                self.json_response({"error": "job_not_found"}, 404)
                return
            self.json_response(job)
            return
        if path == "/v1/catalog/dj":
            query = parse_qs(parsed_url.query).get("q", [""])[0]
            try:
                self.json_response({"items": search_dj_sets(query)})
            except Exception as error:
                self.json_response({"error": "dj_search_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/live-sets/search":
            parameters = parse_qs(parsed_url.query)
            query = parameters.get("q", [""])[0]
            limit = max(1, min(int(parameters.get("limit", ["24"])[0]), 50))
            try:
                self.json_response({"items": search_live_sets(query, limit)})
            except Exception as error:
                self.json_response({"error": "live_set_search_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/catalog/podcasts":
            query = parse_qs(parsed_url.query).get("q", [""])[0]
            try:
                self.json_response({"items": search_podcasts(query)})
            except Exception as error:
                self.json_response({"error": "podcasts_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/catalog/radios":
            query = parse_qs(parsed_url.query).get("q", [""])[0]
            try:
                self.json_response({"items": search_radios(query)})
            except Exception as error:
                self.json_response({"error": "radios_unavailable", "message": str(error)}, 502)
            return
        parts = path.strip("/").split("/")
        if len(parts) >= 3 and parts[:2] == ["v1", "detection-jobs"]:
            job = get_job(parts[2])
            if not job:
                self.json_response({"error": "job_not_found"}, 404); return
            if len(parts) == 4 and parts[3] == "events":
                self.send_response(200); self.cors()
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache"); self.send_header("Connection", "keep-alive"); self.end_headers()
                signature = None
                try:
                    while True:
                        job = get_job(parts[2])
                        current = (job["status"], job["progress"], job["stage"])
                        if current != signature:
                            self.wfile.write(f"event: job.updated\ndata: {json.dumps(job, ensure_ascii=False)}\n\n".encode())
                            self.wfile.flush(); signature = current
                        if job["status"] in {"completed", "failed", "cancelled"}:
                            break
                        time.sleep(0.12)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                self.close_connection = True
                return
            self.json_response(job); return
        self.json_response({"error": "not_found"}, 404)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/v1/shares":
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 32_000:
                self.json_response({"error": "invalid_request", "message": "Partage invalide"}, 413)
                return
            if not allow_share_creation(self.client_address[0]):
                self.json_response({"error": "rate_limited", "message": f"Limite de {SHARE_PER_HOUR} partages par heure atteinte"}, 429)
                return
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                self.json_response(create_share(payload), 201)
            except (json.JSONDecodeError, ShareValidationError) as error:
                self.json_response({"error": "invalid_request", "message": str(error)}, 400)
            return
        if path == "/v1/music-recognition":
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_SAMPLE_BYTES:
                self.json_response({"error": "invalid_sample", "message": "Extrait audio invalide"}, 413)
                return
            if not allow_music_recognition(self.client_address[0]):
                self.json_response({"error": "rate_limited", "message": f"Limite de {MUSIC_RECOGNITION_PER_HOUR} reconnaissances par heure atteinte"}, 429)
                return
            sample = self.rfile.read(length)
            try:
                self.json_response(recognize_music(sample))
            except MusicRecognitionError as error:
                self.json_response({"error": "recognition_failed", "message": str(error)}, 502)
            return
        if path == "/v1/cast/sessions":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                source_url = validate_cast_audio_url(str(payload["url"]))
                title = str(payload.get("title") or "Podmix").strip()[:300]
                start_seconds = max(0.0, min(float(payload.get("positionSeconds") or 0), 7 * 24 * 60 * 60))
                requested_duration = max(0.0, min(float(payload.get("durationSeconds") or 0), 7 * 24 * 60 * 60))
                duration_seconds = resolve_cast_duration(source_url, start_seconds, requested_duration)
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_cast_url", "message": str(error)}, 400); return
            token = uuid.uuid4().hex
            expires_at = time.time() + CAST_SESSION_TTL_SECONDS
            with DB_LOCK, connect() as database:
                database.execute("DELETE FROM cast_sessions WHERE expires_at <= ?", (time.time(),))
                database.execute(
                    "INSERT INTO cast_sessions (token, source_url, title, expires_at, created_at, start_seconds, duration_seconds) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (token, source_url, title, expires_at, now(), start_seconds, duration_seconds),
                )
            response = {
                "id": token,
                "relayUrl": f"{CAST_PUBLIC_BASE}/{token}",
                "startSeconds": start_seconds,
                "durationSeconds": duration_seconds,
                "expiresAt": datetime.fromtimestamp(expires_at, timezone.utc).isoformat(),
            }
            if CAST_LAN_BASE:
                response["lanRelayUrl"] = f"{CAST_LAN_BASE}/{token}"
            self.json_response(response, 201)
            return
        if path == "/v1/catalog/links":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                requested = payload.get("tracks") or []
                if not isinstance(requested, list) or len(requested) > 10:
                    raise ValueError("10 morceaux maximum")
                items = []
                for item in requested:
                    key = str(item.get("key") or "")[:1000]
                    artist = str(item.get("artist") or "").strip()[:300]
                    title = str(item.get("title") or "").strip()[:300]
                    if not key or not artist or not title:
                        continue
                    try:
                        deezer = search_deezer(artist, title)
                    except Exception:
                        deezer = None
                    try:
                        spotify = search_spotify(artist, title)
                    except Exception:
                        spotify = None
                    items.append({
                        "key": key,
                        "artworkUrl": (deezer or spotify or {}).get("artworkUrl"),
                        "deezerUrl": (deezer or {}).get("url"),
                        "spotifyUrl": (spotify or {}).get("url"),
                    })
            except (AttributeError, json.JSONDecodeError, TypeError, ValueError) as error:
                self.json_response({"error": "invalid_request", "message": str(error)}, 400)
                return
            self.json_response({"items": items})
            return
        if path == "/v1/catalog/artwork":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                requested = payload.get("tracks") or []
                if not isinstance(requested, list) or len(requested) > 20:
                    raise ValueError("20 morceaux maximum")
                items = []
                for item in requested:
                    key = str(item.get("key") or "")[:1000]
                    artist = str(item.get("artist") or "").strip()[:300]
                    title = str(item.get("title") or "").strip()[:300]
                    if not key or not artist or not title:
                        continue
                    try:
                        match = search_deezer(artist, title)
                    except Exception:
                        match = None
                    items.append({
                        "key": key,
                        "artworkUrl": (match or {}).get("artworkUrl"),
                    })
            except (AttributeError, json.JSONDecodeError, TypeError, ValueError) as error:
                self.json_response({"error": "invalid_request", "message": str(error)}, 400)
                return
            self.json_response({"items": items})
            return
        if path == "/v1/catalog/rss":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                feed = import_feed(payload["url"], payload.get("kind", "podcast"), payload.get("limit", 100))
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_feed", "message": str(error)}, 400); return
            except Exception as error:
                self.json_response({"error": "feed_unavailable", "message": str(error)}, 502); return
            self.json_response(feed, 201); return
        if path == "/v1/catalog/dj":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                source = import_dj_set(payload["url"])
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_media", "message": str(error)}, 400); return
            except Exception as error:
                self.json_response({"error": "media_unavailable", "message": str(error)}, 502); return
            self.json_response(source, 201); return
        if path == "/v1/live-sets/resolve":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                self.json_response(resolve_live_set(str(payload["url"])), 201)
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400)
            except ValueError as error:
                self.json_response({"error": "invalid_media", "message": str(error)}, 400)
            except Exception as error:
                self.json_response({"error": "media_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/live-sets/tracklist":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                if str(payload.get("text") or "").strip():
                    result = parse_live_set_tracklist(str(payload["text"]))
                else:
                    result = resolve_live_set_tracklist(str(payload["url"]), str(payload.get("title") or ""))
                self.json_response(result, 201)
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400)
            except ValueError as error:
                self.json_response({"error": "invalid_tracklist", "message": str(error)}, 400)
            except Exception as error:
                self.json_response({"error": "tracklist_unavailable", "message": str(error)}, 502)
            return
        if path == "/v1/episode-analysis":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                episode_id = str(payload["episodeId"]).strip()
                title = str(payload["title"]).strip()
                duration = max(0.0, float(payload.get("durationSeconds") or 0))
                if not episode_id or not title:
                    raise ValueError("L’épisode doit avoir un identifiant et un titre")
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_episode", "message": str(error)}, 400); return
            request_key = str(payload.get("requestKey") or f"episode:{episode_id}").strip()[:500]
            force_restart = bool(payload.get("force"))
            existing = get_job_by_request_key(request_key)
            if existing and not force_restart and existing["status"] not in {"failed", "cancelled"}:
                self.json_response(existing)
                return
            job_id, created = str(uuid.uuid4()), now()
            rss_tracks = payload.get("rssTracks") or []
            published_at = str(payload.get("publishedAt") or "")[:80]
            published_timestamp = parse_published_timestamp(published_at)
            context = {
                "title": title[:300],
                "description": str(payload.get("description") or "")[:20_000],
                "feedUrl": str(payload.get("feedUrl") or "")[:2_000],
                "sourceUrl": str(payload.get("sourceUrl") or "")[:2_000],
                "audioUrl": str(payload.get("audioUrl") or "")[:4_000],
                "preferred1001Url": str(payload.get("preferred1001Url") or "")[:2_000],
                "automaticTracklist": True,
                "webQueue": bool(payload.get("webQueue")),
                "enable1001": bool(payload.get("enable1001")),
                "enableExternalTracklists": bool(payload.get("enableExternalTracklists", payload.get("enable1001"))),
                "sourceKind": str(payload.get("sourceKind") or "")[:30],
                "musical": bool(payload.get("musical")),
                "publishedAt": published_at,
                "publishedTimestamp": published_timestamp,
                "referenceFirstTrack": rss_tracks[0] if rss_tracks else {},
                "rssTracks": rss_tracks[:40],
            }
            try:
                with DB_LOCK, connect() as database:
                    if force_restart:
                        # « Actualiser » est une remise à zéro, pas une
                        # tentative ajoutée à l'historique : les anciens jobs
                        # (Web en attente, échecs et résultats) disparaissent
                        # tous pour cet épisode avant de repartir du RSS.
                        database.execute("DELETE FROM jobs WHERE episode_id = ?", (episode_id,))
                    elif existing:
                        database.execute("UPDATE jobs SET request_key = NULL WHERE request_key = ?", (request_key,))
                    database.execute(
                        """
                        INSERT INTO jobs (
                          id, episode_id, status, stage, progress, tracks_json, error,
                          operation, context_json, request_key, created_at, updated_at, duration
                        ) VALUES (?, ?, 'queued', 'Recherche en attente', 0, '[]', NULL,
                                  'research', ?, ?, ?, ?, ?)
                        """,
                        (job_id, episode_id, json.dumps(context, ensure_ascii=False),
                         request_key, created, created, duration),
                    )
            except sqlite3.IntegrityError:
                existing = get_job_by_request_key(request_key)
                if existing:
                    self.json_response(existing)
                    return
                self.json_response({"error": "job_conflict"}, 409)
                return
            notify_worker()
            self.json_response(get_job(job_id), 202)
            return
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[:2] == ["v1", "web-timestamp-jobs"] and parts[3] == "failure":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                result = fail_web_timestamp_job(parts[2], str(payload.get("message") or ""))
            except ValueError as error:
                self.json_response({"error": "web_timestamp_failed", "message": str(error)}, 409); return
            except (json.JSONDecodeError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            self.json_response(result)
            return
        if len(parts) == 3 and parts[:2] == ["v1", "web-timestamp-jobs"]:
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                result = complete_web_timestamp_job(parts[2], payload)
            except ValueError as error:
                self.json_response({"error": "web_timestamp_failed", "message": str(error)}, 409); return
            except (json.JSONDecodeError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            self.json_response(result)
            return
        if len(parts) == 4 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "tracklist":
            job = get_job(parts[2])
            if not job:
                self.json_response({"error": "job_not_found"}, 404); return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                candidates = parse_tracklist(payload["text"])
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            if not candidates:
                self.json_response({"error": "no_tracks_found"}, 422); return
            timestamp_source = payload.get("timestampSource")
            if timestamp_source == "external":
                reference = get_job_context(parts[2]).get("referenceFirstTrack") or {}
                if reference and first_track_match_score(candidates, reference) < 0.5:
                    self.json_response({"error": "wrong_episode", "message": "Le premier morceau ne correspond pas à l’épisode RSS"}, 409); return
                reference_tracks = [
                    {
                        "artist": track.get("artist", ""),
                        "title": track.get("title", ""),
                        "providedTime": track.get("time"),
                    }
                    for track in job["tracks"]
                ]
                candidates = apply_external_timestamps(reference_tracks, candidates)
            tracks = align_tracklist(candidates, job["tracks"], job["duration"], timestamp_source=timestamp_source or "manual")
            update_job(parts[2], tracks=tracks, stage="Tracklist alignée")
            self.json_response(get_job(parts[2]))
            return
        if len(parts) == 4 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "discover":
            job = get_job(parts[2])
            if not job:
                self.json_response({"error": "job_not_found"}, 404); return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                discovery = discover_tracklist(payload["url"])
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "url_not_allowed", "message": str(error)}, 400); return
            except Exception as error:
                self.json_response({"error": "discovery_failed", "message": str(error)}, 502); return
            if not discovery["candidates"]:
                self.json_response({**discovery, "tracks": [], "message": "Aucune tracklist trouvée"}, 200); return
            tracks = align_tracklist(discovery["candidates"], job["tracks"], job["duration"], timestamp_source="external")
            for track in tracks:
                track["evidence"].append(f"Métadonnées {discovery['extractor']} : {discovery['sourceUrl']}")
            update_job(parts[2], tracks=tracks, stage="Tracklist découverte et alignée")
            self.json_response({**discovery, "tracks": tracks})
            return
        if len(parts) == 4 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "discover-1001":
            job = get_job(parts[2])
            if not job:
                self.json_response({"error": "job_not_found"}, 404); return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                discovery = (
                    {**scrape_tracklist(payload["url"]), "source": "1001tracklists"}
                    if payload.get("url")
                    else search_external_tracklist(payload["query"])
                )
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_1001_request", "message": str(error)}, 400); return
            except Exception as error:
                self.json_response({"error": "tracklist_unavailable", "message": str(error)}, 502); return
            if not discovery["candidates"]:
                self.json_response({**discovery, "tracks": [], "message": "Aucune tracklist externe trouvée"}); return
            context = get_job_context(parts[2])
            reference = context.get("referenceFirstTrack") or {}
            if reference and first_track_match_score(discovery["candidates"], reference) < 0.5:
                self.json_response({**discovery, "tracks": [], "message": "Le premier morceau ne correspond pas à l’épisode RSS"}, 409); return
            tracks = align_tracklist(discovery["candidates"], job["tracks"], job["duration"], timestamp_source="external")
            source_name = "MixesDB" if discovery.get("source") == "mixesdb" else "1001Tracklists"
            for track in tracks:
                track["evidence"].append(f"{source_name} : {discovery['sourceUrl']}")
            update_job(parts[2], tracks=tracks, stage=f"Tracklist {source_name} alignée")
            self.json_response({**discovery, "tracks": tracks})
            return
        if len(parts) == 6 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "tracks" and parts[5] == "validate":
            job = get_job(parts[2])
            if not job:
                self.json_response({"error": "job_not_found"}, 404); return
            try:
                track_id = int(parts[4])
                track = next(track for track in job["tracks"] if int(track["id"]) == track_id)
            except (ValueError, StopIteration):
                self.json_response({"error": "track_not_found"}, 404); return
            cache_key = f"{track['artist'].lower().strip()}::{track['title'].lower().strip()}"
            with DB_LOCK, connect() as database:
                cached = database.execute("SELECT response_json FROM catalog_cache WHERE cache_key = ?", (cache_key,)).fetchone()
            try:
                result = json.loads(cached["response_json"]) if cached else validate_track(track["artist"], track["title"])
                if cached and not result.get("artworkUrl"):
                    result = validate_track(track["artist"], track["title"])
                    with DB_LOCK, connect() as database:
                        database.execute(
                            "INSERT OR REPLACE INTO catalog_cache VALUES (?, ?, ?)",
                            (cache_key, json.dumps(result, ensure_ascii=False), now()),
                        )
            except Exception as error:
                self.json_response({"error": "catalog_unavailable", "message": str(error)}, 502); return
            if not cached:
                with DB_LOCK, connect() as database:
                    database.execute("INSERT OR REPLACE INTO catalog_cache VALUES (?, ?, ?)", (cache_key, json.dumps(result, ensure_ascii=False), now()))
            best = result.get("bestMatch")
            evidence = list(track.get("evidence") or [])
            evidence = [item for item in evidence if not item.startswith("MusicBrainz")]
            if best:
                evidence.append(f"MusicBrainz {best['score']} % : {best['artist']} — {best['title']} ({best['mbid']})")
                track["confidence"] = max(track["confidence"], min(97, best["score"]))
                track["mbid"] = best["mbid"]
            else:
                evidence.append("MusicBrainz : aucune correspondance")
            if result.get("deezerUrl"):
                track["deezerUrl"] = result["deezerUrl"]
                evidence.append(f"Deezer {result['deezerMatch']['score']} %")
            if result.get("spotifyUrl"):
                track["spotifyUrl"] = result["spotifyUrl"]
                evidence.append(f"Spotify {result['spotifyMatch']['score']} %")
            if result.get("artworkUrl"):
                track["artworkUrl"] = result["artworkUrl"]
            track["evidence"] = evidence
            track["catalogValidated"] = result["accepted"]
            update_job(parts[2], tracks=job["tracks"], stage="Validation catalogue effectuée")
            self.json_response({"track": track, **result})
            return

        self.json_response({"error": "not_found"}, 404)

    def do_DELETE(self) -> None:
        parts = urlparse(self.path).path.strip("/").split("/")
        if len(parts) != 3 or parts[:2] != ["v1", "detection-jobs"] or not get_job(parts[2]):
            self.json_response({"error": "job_not_found"}, 404); return
        update_job(parts[2], status="cancelled", stage="Recherche annulée")
        self.json_response(get_job(parts[2]))


if __name__ == "__main__":
    initialize()
    start_worker()
    print(f"Podmix API disponible sur http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
