#!/usr/bin/env python3
"""API locale Podmix : uploads, jobs SQLite, analyse WAV et progression SSE."""

from __future__ import annotations

import json
import ipaddress
import os
import socket
import sqlite3
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from analyzer import advanced_engine_available, analyze_audio
from acoustid import identify_segment
from catalog import search_deezer, search_spotify, validate_track
from discovery import discover_tracklist, import_dj_set, search_dj_sets
from feeds import import_feed
from radios import search_radios
from podcasts import search_podcasts
from refiner import refine_tracks
from tracklist import align_tracklist, parse_tracklist
from tracklist_sources import search_external_tracklist
from tl1001 import scrape_tracklist, search_tracklist
from websearch import search_tracklist_candidates

HOST = os.environ.get("PODMIX_HOST", "127.0.0.1")
PORT = int(os.environ.get("PODMIX_PORT", "8099"))
DATA_DIR = Path(__file__).parent / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
DATABASE = DATA_DIR / "podmix.sqlite3"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
DB_LOCK = threading.Lock()
WORKER_CONDITION = threading.Condition()
WORKER_STARTED = False
MAX_UPLOAD_SIZE = 300 * 1024 * 1024
CAST_SESSION_TTL_SECONDS = int(os.environ.get("PODMIX_CAST_SESSION_TTL", str(24 * 60 * 60)))
CAST_PUBLIC_BASE = os.environ.get(
    "PODMIX_CAST_PUBLIC_BASE",
    f"http://127.0.0.1:{PORT}/v1/cast",
).rstrip("/")
CAST_DURATION_CACHE: dict[str, float] = {}
CAST_DURATION_LOCK = threading.Lock()


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


def validate_remote_audio_url(value: str) -> str:
    parsed = urlparse(value.strip())
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("L’audio distant doit utiliser une URL HTTPS publique")
    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
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


def probe_cast_duration(url: str) -> float:
    """Resolve redirects safely, then ask ffprobe for the source duration."""
    with CAST_DURATION_LOCK:
        cached = CAST_DURATION_CACHE.get(url)
    if cached is not None:
        return cached

    response = None
    try:
        response, resolved = open_cast_upstream(
            url,
            {"User-Agent": "Podmix-Cast/1.0", "Accept": "audio/*,*/*;q=0.8"},
            "HEAD",
        )
        response.close()
        response = None
        result = subprocess.run(
            [
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", resolved,
            ],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        duration = max(0.0, float(result.stdout.strip() or 0)) if result.returncode == 0 else 0.0
    except (OSError, ValueError, subprocess.SubprocessError):
        duration = 0.0
    finally:
        if response is not None:
            response.close()

    if duration > 0:
        with CAST_DURATION_LOCK:
            CAST_DURATION_CACHE[url] = duration
    return duration


def resolve_cast_duration(url: str, start_seconds: float, requested_duration: float) -> float:
    requested = max(0.0, requested_duration)
    if requested > start_seconds + 5:
        return requested
    probed = probe_cast_duration(url)
    return probed if probed > start_seconds + 5 else 0.0


def download_remote_audio(url: str, target: Path) -> tuple[int, str]:
    opener = build_opener(NoRedirect)
    current = validate_remote_audio_url(url)
    for _ in range(6):
        try:
            response = opener.open(Request(current, headers={"User-Agent": "Podmix/1.0"}), timeout=25)
        except Exception as error:
            location = getattr(error, "headers", {}).get("Location") if hasattr(error, "headers") else None
            code = getattr(error, "code", 0)
            if code in {301, 302, 303, 307, 308} and location:
                from urllib.parse import urljoin
                current = validate_remote_audio_url(urljoin(current, location))
                continue
            raise ValueError(f"Téléchargement audio refusé : {error}") from error
        length = int(response.headers.get("Content-Length") or 0)
        if length > MAX_UPLOAD_SIZE:
            response.close()
            raise ValueError("Le fichier audio dépasse 300 Mo")
        media_type = response.headers.get_content_type()
        written = 0
        try:
            with target.open("wb") as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > MAX_UPLOAD_SIZE:
                        raise ValueError("Le fichier audio dépasse 300 Mo")
                    output.write(chunk)
        except Exception:
            target.unlink(missing_ok=True)
            raise
        finally:
            response.close()
        if written == 0:
            target.unlink(missing_ok=True)
            raise ValueError("Le serveur a renvoyé un fichier audio vide")
        return written, media_type
    raise ValueError("Trop de redirections pour le fichier audio")


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
        CREATE TABLE IF NOT EXISTS uploads (
          id TEXT PRIMARY KEY, filename TEXT NOT NULL, path TEXT NOT NULL,
          media_type TEXT NOT NULL, size INTEGER NOT NULL, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS catalog_cache (
          cache_key TEXT PRIMARY KEY, response_json TEXT NOT NULL, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, episode_id TEXT NOT NULL, upload_id TEXT NOT NULL,
          status TEXT NOT NULL, stage TEXT NOT NULL, progress INTEGER NOT NULL,
          tracks_json TEXT NOT NULL DEFAULT '[]', error TEXT,
          operation TEXT NOT NULL DEFAULT 'analyze',
          context_json TEXT NOT NULL DEFAULT '{}',
          request_key TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          FOREIGN KEY(upload_id) REFERENCES uploads(id)
        );
        CREATE TABLE IF NOT EXISTS cast_sessions (
          token TEXT PRIMARY KEY, source_url TEXT NOT NULL, title TEXT NOT NULL,
          expires_at REAL NOT NULL, created_at TEXT NOT NULL,
          start_seconds REAL NOT NULL DEFAULT 0,
          duration_seconds REAL NOT NULL DEFAULT 0
        );
        """)
        cast_columns = {row[1] for row in database.execute("PRAGMA table_info(cast_sessions)")}
        if "start_seconds" not in cast_columns:
            database.execute("ALTER TABLE cast_sessions ADD COLUMN start_seconds REAL NOT NULL DEFAULT 0")
        if "duration_seconds" not in cast_columns:
            database.execute("ALTER TABLE cast_sessions ADD COLUMN duration_seconds REAL NOT NULL DEFAULT 0")
        columns = {row[1] for row in database.execute("PRAGMA table_info(jobs)")}
        if "duration" not in columns:
            database.execute("ALTER TABLE jobs ADD COLUMN duration REAL")
        if "operation" not in columns:
            database.execute("ALTER TABLE jobs ADD COLUMN operation TEXT NOT NULL DEFAULT 'analyze'")
        if "context_json" not in columns:
            database.execute("ALTER TABLE jobs ADD COLUMN context_json TEXT NOT NULL DEFAULT '{}'")
        if "request_key" not in columns:
            database.execute("ALTER TABLE jobs ADD COLUMN request_key TEXT")
        database.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS jobs_request_key ON jobs(request_key) WHERE request_key IS NOT NULL"
        )
        database.execute(
            """
            UPDATE jobs
               SET status = 'queued',
                   stage = CASE operation
                     WHEN 'refine' THEN 'Raffinage repris après redémarrage'
                     WHEN 'download_analyze' THEN 'Téléchargement repris après redémarrage'
                     ELSE 'Analyse reprise après redémarrage'
                   END,
                   updated_at = ?
             WHERE status = 'running'
            """,
            (now(),),
        )


def row_to_job(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"], "episodeId": row["episode_id"], "status": row["status"],
        "stage": row["stage"], "progress": row["progress"],
        "createdAt": row["created_at"], "updatedAt": row["updated_at"],
        "tracks": json.loads(row["tracks_json"]), "duration": row["duration"],
        "operation": row["operation"],
        **({"error": row["error"]} if row["error"] else {}),
    }


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


def _automatic_tracklist(
    job_id: str,
    duration: float,
    transitions: list[dict],
    context: dict,
) -> list[dict]:
    if not context.get("automaticTracklist"):
        return transitions
    candidates: list[dict] = []
    evidence = ""
    description = str(context.get("description") or "")
    if description:
        update_job(job_id, stage="Lecture de la tracklist publiée", progress=64)
        candidates = parse_tracklist(description, structured_only=True)
        if len(candidates) >= 2:
            evidence = "Tracklist publiée dans la description"
        else:
            candidates = []
    source_url = str(context.get("sourceUrl") or "")
    if not candidates and source_url:
        update_job(job_id, stage="Exploration des chapitres et métadonnées", progress=69)
        try:
            discovery = discover_tracklist(source_url)
            candidates = discovery["candidates"]
            if candidates:
                evidence = f"Métadonnées {discovery['extractor']} : {discovery['sourceUrl']}"
        except Exception:
            candidates = []
    external_search_enabled = context.get(
        "enableExternalTracklists",
        context.get("enable1001"),
    )
    if not candidates and external_search_enabled and context.get("title"):
        update_job(job_id, stage="Recherche dans les bases de tracklists", progress=74)
        try:
            discovery = search_external_tracklist(str(context["title"]))
            candidates = discovery["candidates"]
            if candidates:
                source_name = (
                    "MixesDB"
                    if discovery.get("source") == "mixesdb"
                    else "1001Tracklists"
                )
                evidence = f"{source_name} : {discovery['sourceUrl']}"
        except Exception:
            candidates = []
    if not candidates:
        return transitions
    tracks = align_tracklist(candidates, transitions, duration)
    for track in tracks:
        if evidence:
            track.setdefault("evidence", []).insert(0, evidence)
    if context.get("refineTimestamps"):
        update_job(job_id, stage="Raffinage automatique des timestamps", progress=80)
        try:
            audio_path = get_job_audio_path(job_id)
            if audio_path:
                tracks = refine_tracks(
                    audio_path,
                    tracks,
                    duration,
                    lambda progress, stage: update_job(
                        job_id,
                        progress=80 + round(progress * .18),
                        stage=stage,
                    ),
                )
        except Exception as error:
            for track in tracks:
                track.setdefault("evidence", []).append(
                    f"Raffinage automatique indisponible : {type(error).__name__}"
                )
    return tracks


def run_job(job_id: str, audio_path: Path) -> None:
    try:
        update_job(job_id, status="running", stage="Ouverture du fichier", progress=5)
        duration, transitions = analyze_audio(
            audio_path,
            lambda progress, stage: update_job(
                job_id,
                progress=min(62, 5 + round(progress * .57)),
                stage=stage,
            ),
        )
        if get_job(job_id)["status"] == "cancelled":
            return
        tracks = _automatic_tracklist(job_id, duration, transitions, get_job_context(job_id))
        if get_job(job_id)["status"] == "cancelled":
            return
        update_job(job_id, status="completed", stage="Analyse terminée", progress=100, tracks=tracks, duration=duration)
    except Exception as error:
        update_job(job_id, status="failed", stage="Échec de l’analyse", progress=100, error=str(error))


def run_remote_job(job_id: str, audio_path: Path) -> None:
    try:
        context = get_job_context(job_id)
        remote_url = validate_remote_audio_url(str(context.get("remoteAudioUrl") or ""))
        update_job(job_id, status="running", stage="Téléchargement de l’audio sur le VPS", progress=2)
        size, media_type = download_remote_audio(remote_url, audio_path)
        with DB_LOCK, connect() as database:
            database.execute(
                """
                UPDATE uploads
                   SET size = ?, media_type = ?
                 WHERE id = (SELECT upload_id FROM jobs WHERE id = ?)
                """,
                (size, media_type, job_id),
            )
        if get_job(job_id)["status"] == "cancelled":
            return
        update_job(job_id, operation="analyze", stage="Audio téléchargé", progress=5)
        run_job(job_id, audio_path)
    except Exception as error:
        audio_path.unlink(missing_ok=True)
        update_job(job_id, status="failed", stage="Échec du téléchargement audio", progress=100, error=str(error))


def run_refinement(job_id: str, audio_path: Path) -> None:
    try:
        job = get_job(job_id)
        update_job(job_id, status="running", stage="Préparation du raffinage chroma", progress=1, error=None)
        tracks = refine_tracks(
            audio_path,
            job["tracks"],
            job["duration"],
            lambda progress, stage: update_job(job_id, progress=progress, stage=stage),
        )
        if get_job(job_id)["status"] == "cancelled":
            return
        update_job(job_id, status="completed", stage="Raffinage chroma terminé", progress=100, tracks=tracks)
    except Exception as error:
        update_job(job_id, status="failed", stage="Échec du raffinage chroma", progress=100, error=str(error))


def get_job_audio_path(job_id: str) -> Path | None:
    with DB_LOCK, connect() as database:
        row = database.execute(
            "SELECT uploads.path FROM jobs JOIN uploads ON uploads.id = jobs.upload_id WHERE jobs.id = ?",
            (job_id,),
        ).fetchone()
    return Path(row["path"]) if row else None


def claim_next_job() -> dict | None:
    with DB_LOCK, connect() as database:
        database.execute("BEGIN IMMEDIATE")
        row = database.execute(
            """
            SELECT jobs.id, jobs.operation, uploads.path
              FROM jobs
              JOIN uploads ON uploads.id = jobs.upload_id
             WHERE jobs.status = 'queued'
             ORDER BY jobs.created_at, jobs.id
             LIMIT 1
            """
        ).fetchone()
        if not row:
            return None
        database.execute(
            "UPDATE jobs SET status = 'running', updated_at = ? WHERE id = ? AND status = 'queued'",
            (now(), row["id"]),
        )
    return {"id": row["id"], "operation": row["operation"], "path": row["path"]}


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
        path = Path(claimed["path"])
        if claimed["operation"] == "refine":
            run_refinement(claimed["id"], path)
        elif claimed["operation"] == "download_analyze":
            run_remote_job(claimed["id"], path)
        else:
            run_job(claimed["id"], path)


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
            "http://localhost", "https://localhost", "capacitor://localhost",
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

        current = validate_cast_audio_url(session["source_url"])
        response = None
        transcoder = None
        try:
            start_seconds = max(0.0, float(session["start_seconds"] or 0))
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
            if start_seconds > 2 and not head_only:
                transcoder = subprocess.Popen(
                    [
                        "ffmpeg", "-hide_banner", "-loglevel", "error",
                        "-ss", f"{start_seconds:.3f}", "-i", current,
                        "-map", "0:a:0", "-vn", "-c:a", "libmp3lame",
                        "-b:a", "160k", "-f", "mp3", "pipe:1",
                    ],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                )
                self.send_response(200)
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                assert transcoder.stdout is not None
                while True:
                    chunk = transcoder.stdout.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
                return
            headers = {
                "User-Agent": "Podmix-Cast/1.0",
                "Accept": "audio/*,*/*;q=0.8",
                "Icy-MetaData": self.headers.get("Icy-MetaData", "0"),
            }
            if self.headers.get("Range"):
                headers["Range"] = self.headers["Range"]
            response, current = open_cast_upstream(current, headers, "HEAD" if head_only else "GET")

            self.send_response(getattr(response, "status", 200))
            for name in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Icy-Br", "Icy-Genre", "Icy-Name", "Icy-Url"):
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
            if transcoder is not None:
                if transcoder.stdout is not None:
                    transcoder.stdout.close()
                if transcoder.poll() is None:
                    transcoder.terminate()
                try:
                    transcoder.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    transcoder.kill()
            self.close_connection = True

    def stream_segment(self, audio_url: str, start: float, end: float) -> None:
        """Extract and stream an audio segment using FFmpeg."""
        duration = end - start
        
        transcoder = None
        try:
            transcoder = subprocess.Popen(
                [
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-ss", f"{start:.3f}", "-i", audio_url,
                    "-t", f"{duration:.3f}",
                    "-map", "0:a:0", "-vn", "-c:a", "libmp3lame",
                    "-b:a", "160k", "-f", "mp3", "pipe:1",
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            assert transcoder.stdout is not None
            
            # Read stderr in a separate thread to avoid blocking
            stderr_lines = []
            def read_stderr():
                while True:
                    line = transcoder.stderr.readline()
                    if not line:
                        break
                    stderr_lines.append(line.decode('utf-8', errors='ignore'))
            
            import threading
            stderr_thread = threading.Thread(target=read_stderr)
            stderr_thread.start()
            
            # Stream audio data
            bytes_sent = 0
            while True:
                chunk = transcoder.stdout.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                bytes_sent += len(chunk)
            
            stderr_thread.join(timeout=5)
            transcoder.wait(timeout=10)
            
            # Check if FFmpeg failed
            if transcoder.returncode != 0:
                error_msg = ''.join(stderr_lines[-5:]) if stderr_lines else 'Unknown error'
                print(f"[podmix-api] FFmpeg error (exit {transcoder.returncode}): {error_msg}")
                if bytes_sent == 0:
                    try:
                        self.wfile.write(b'\n\nFFmpeg error: ' + error_msg.encode() + b'\n')
                    except (BrokenPipeError, ConnectionResetError):
                        pass
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            print(f"[podmix-api] stream_segment error: {error}")
            if transcoder is not None and transcoder.poll() is None:
                transcoder.terminate()
                try:
                    transcoder.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    transcoder.kill()
        finally:
            if transcoder is not None:
                if transcoder.stdout is not None:
                    transcoder.stdout.close()
                if transcoder.poll() is None:
                    transcoder.terminate()
                try:
                    transcoder.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    transcoder.kill()

    def extract_tracks(self, audio_url: str, tracks: list, episode_id: str) -> dict:
        """Extract individual tracks from an episode and return their info."""
        extracted = []
        for i, track in enumerate(tracks):
            start_time = float(track.get('time', 0))
            next_track = tracks[i + 1] if i + 1 < len(tracks) else None
            end_time = float(next_track['time']) if next_track else None
            
            # Extract track to temporary file
            output_path = DATA_DIR / 'extracted_tracks' / episode_id / f"track_{i:03d}.mp3"
            output_path.parent.mkdir(parents=True, exist_ok=True)
            
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-ss", f"{start_time:.3f}",
                "-i", audio_url,
            ]
            if end_time is not None:
                cmd.extend(["-t", f"{end_time - start_time:.3f}"])
            cmd.extend([
                "-map", "0:a:0", "-vn", "-c:a", "libmp3lame",
                "-b:a", "160k", "-y", str(output_path)
            ])
            
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
            
            if result.returncode == 0 and output_path.exists():
                extracted.append({
                    "index": i,
                    "title": track.get('title', f'Track {i+1}'),
                    "artist": track.get('artist', ''),
                    "startTime": start_time,
                    "endTime": end_time,
                    "localPath": str(output_path),
                    "size": output_path.stat().st_size,
                })
            else:
                print(f"[podmix-api] Failed to extract track {i}: {result.stderr}")
        
        return {
            "episodeId": episode_id,
            "tracks": extracted,
            "count": len(extracted),
        }

    def do_HEAD(self) -> None:
        parts = urlparse(self.path).path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["v1", "cast"]:
            self.relay_cast(parts[2], head_only=True)
            return
        self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()

    def do_GET(self) -> None:
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path == "/health":
            self.json_response({"status": "healthy", "service": "podmix-api", "engine": "librosa" if advanced_engine_available() else "portable-wav"})
            return
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["v1", "cast"]:
            self.relay_cast(parts[2])
            return
        if path == "/v1/segment":
            from urllib.parse import parse_qs
            query = parse_qs(parsed_url.query)
            audio_url = query.get("url", [""])[0]
            start = float(query.get("start", ["0"])[0])
            end = float(query.get("end", ["0"])[0])
            if not audio_url or start < 0 or end <= start:
                self.json_response({"error": "invalid_segment_params"}, 400)
                return
            try:
                self.stream_segment(audio_url, start, end)
            except Exception as error:
                self.json_response({"error": "segment_failed", "message": str(error)}, 500)
            return
            return
        if path == "/v1/extract-tracks":
            from urllib.parse import parse_qs, unquote
            import json as json_module
            query = parse_qs(parsed_url.query)
            audio_url = unquote(query.get("url", [""])[0])
            tracks_json = unquote(query.get("tracks", [""])[0])
            episode_id = query.get("episodeId", [""])[0]
            if not audio_url or not tracks_json or not episode_id:
                self.json_response({"error": "missing_params"}, 400)
                return
            try:
                tracks = json_module.loads(tracks_json)
                result = self.extract_tracks(audio_url, tracks, episode_id)
                self.json_response(result)
            except Exception as error:
                self.json_response({"error": "extraction_failed", "message": str(error)}, 500)
        if path == "/v1/tracklists/candidates":
            from urllib.parse import parse_qs
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
        if path == "/v1/detection-jobs":
            from urllib.parse import parse_qs
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
            from urllib.parse import parse_qs
            query = parse_qs(parsed_url.query).get("q", [""])[0]
            try:
                self.json_response({"items": search_dj_sets(query)})
            except Exception as error:
                self.json_response({"error": "dj_search_unavailable", "message": str(error)}, 502)
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
            self.json_response({
                "id": token,
                "relayUrl": f"{CAST_PUBLIC_BASE}/{token}",
                "startSeconds": start_seconds,
                "durationSeconds": duration_seconds,
                "expiresAt": datetime.fromtimestamp(expires_at, timezone.utc).isoformat(),
            }, 201)
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
        if path == "/v1/episode-analysis":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                episode_id = str(payload["episodeId"]).strip()
                title = str(payload["title"]).strip()
                remote_url = validate_remote_audio_url(str(payload["audioUrl"]))
                if not episode_id or not title:
                    raise ValueError("L’épisode doit avoir un identifiant et un titre")
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "invalid_episode", "message": str(error)}, 400); return
            request_key = str(payload.get("requestKey") or f"episode:{episode_id}").strip()[:500]
            existing = get_job_by_request_key(request_key)
            if existing and not payload.get("force") and existing["status"] not in {"failed", "cancelled"}:
                self.json_response(existing)
                return
            upload_id, job_id, created = str(uuid.uuid4()), str(uuid.uuid4()), now()
            suffix = Path(urlparse(remote_url).path).suffix.lower() or ".audio"
            if suffix not in {".wav", ".mp3", ".flac", ".ogg", ".oga", ".aac", ".m4a", ".mp4"}:
                suffix = ".audio"
            target = UPLOAD_DIR / f"{upload_id}{suffix}"
            context = {
                "title": title[:300],
                "description": str(payload.get("description") or "")[:20_000],
                "sourceUrl": str(payload.get("sourceUrl") or "")[:2_000],
                "remoteAudioUrl": remote_url,
                "automaticTracklist": True,
                "enable1001": bool(payload.get("enable1001")),
                "enableExternalTracklists": bool(
                    payload.get(
                        "enableExternalTracklists",
                        payload.get("enable1001"),
                    )
                ),
                "sourceKind": str(payload.get("sourceKind") or "")[:30],
                "musical": bool(payload.get("musical")),
                "refineTimestamps": bool(payload.get("refineTimestamps", True)),
            }
            try:
                with DB_LOCK, connect() as database:
                    if existing:
                        database.execute(
                            "UPDATE jobs SET request_key = NULL WHERE request_key = ?",
                            (request_key,),
                        )
                    database.execute(
                        "INSERT INTO uploads VALUES (?, ?, ?, ?, ?, ?)",
                        (upload_id, f"{title}.audio", str(target), "application/octet-stream", 0, created),
                    )
                    database.execute(
                        """
                        INSERT INTO jobs (
                          id, episode_id, upload_id, status, stage, progress,
                          tracks_json, error, operation, context_json, request_key,
                          created_at, updated_at
                        ) VALUES (?, ?, ?, 'queued', 'Téléchargement en attente', 0,
                                  '[]', NULL, 'download_analyze', ?, ?, ?, ?)
                        """,
                        (
                            job_id, episode_id, upload_id,
                            json.dumps(context, ensure_ascii=False),
                            request_key, created, created,
                        ),
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
        if path == "/v1/uploads":
            length = int(self.headers.get("Content-Length", "0"))
            filename = Path(self.headers.get("X-Filename", "audio.wav")).name
            if length <= 0 or length > 300 * 1024 * 1024:
                self.json_response({"error": "invalid_upload_size"}, 400); return
            if Path(filename).suffix.lower() not in {".wav", ".mp3", ".flac", ".ogg", ".oga", ".aac", ".m4a", ".mp4"}:
                self.json_response({"error": "unsupported_audio_format", "message": "Format audio non pris en charge"}, 415); return
            upload_id = str(uuid.uuid4())
            suffix = Path(filename).suffix.lower() or ".bin"
            target = UPLOAD_DIR / f"{upload_id}{suffix}"
            remaining = length
            with target.open("wb") as output:
                while remaining:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    output.write(chunk); remaining -= len(chunk)
            created = now()
            with DB_LOCK, connect() as database:
                database.execute(
                    "INSERT INTO uploads VALUES (?, ?, ?, ?, ?, ?)",
                    (upload_id, filename, str(target), self.headers.get("Content-Type", "application/octet-stream"), length, created),
                )
            self.json_response({"id": upload_id, "filename": filename, "size": length, "createdAt": created}, 201)
            return
        if path == "/v1/uploads/from-url":
            length = int(self.headers.get("Content-Length", "0"))
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
                remote_url = validate_remote_audio_url(payload["url"])
                filename = Path(str(payload.get("filename") or "episode.mp3")).name
                suffix = Path(urlparse(remote_url).path).suffix.lower() or Path(filename).suffix.lower() or ".audio"
                if suffix not in {".wav", ".mp3", ".flac", ".ogg", ".oga", ".aac", ".m4a", ".mp4"}:
                    suffix = ".audio"
                upload_id = str(uuid.uuid4())
                target = UPLOAD_DIR / f"{upload_id}{suffix}"
                size, media_type = download_remote_audio(remote_url, target)
            except (json.JSONDecodeError, KeyError, TypeError):
                self.json_response({"error": "invalid_request"}, 400); return
            except ValueError as error:
                self.json_response({"error": "remote_upload_failed", "message": str(error)}, 400); return
            except Exception as error:
                self.json_response({"error": "remote_upload_failed", "message": str(error)}, 502); return
            created = now()
            with DB_LOCK, connect() as database:
                database.execute(
                    "INSERT INTO uploads VALUES (?, ?, ?, ?, ?, ?)",
                    (upload_id, filename, str(target), media_type, size, created),
                )
            self.json_response({"id": upload_id, "filename": filename, "size": size, "createdAt": created}, 201)
            return
        parts = path.strip("/").split("/")
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
            tracks = align_tracklist(candidates, job["tracks"], job["duration"])
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
            tracks = align_tracklist(discovery["candidates"], job["tracks"], job["duration"])
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
            tracks = align_tracklist(discovery["candidates"], job["tracks"], job["duration"])
            source_name = "MixesDB" if discovery.get("source") == "mixesdb" else "1001Tracklists"
            for track in tracks:
                track["evidence"].append(f"{source_name} : {discovery['sourceUrl']}")
            update_job(parts[2], tracks=tracks, stage=f"Tracklist {source_name} alignée")
            self.json_response({**discovery, "tracks": tracks})
            return
        if len(parts) == 4 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "refine":
            job = get_job(parts[2])
            audio_path = get_job_audio_path(parts[2])
            if not job or not audio_path:
                self.json_response({"error": "job_not_found"}, 404); return
            if not job["tracks"]:
                self.json_response({"error": "tracklist_required", "message": "Importez d’abord une tracklist"}, 422); return
            update_job(
                parts[2],
                status="queued",
                operation="refine",
                stage="Raffinage chroma en attente",
                progress=0,
                error=None,
            )
            notify_worker()
            self.json_response(get_job(parts[2]), 202)
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
        if len(parts) == 6 and parts[:2] == ["v1", "detection-jobs"] and parts[3] == "tracks" and parts[5] == "fingerprint":
            job = get_job(parts[2])
            audio_path = get_job_audio_path(parts[2])
            if not job or not audio_path:
                self.json_response({"error": "job_not_found"}, 404); return
            try:
                track_id = int(parts[4])
                track = next(track for track in job["tracks"] if int(track["id"]) == track_id)
            except (ValueError, StopIteration):
                self.json_response({"error": "track_not_found"}, 404); return
            try:
                result = identify_segment(audio_path, float(track.get("time") or 0))
            except Exception as error:
                self.json_response({"error": "fingerprint_failed", "message": str(error)}, 502); return
            best = result.get("bestMatch")
            evidence = [item for item in list(track.get("evidence") or []) if not item.startswith("AcoustID")]
            if best:
                evidence.append(f"AcoustID {best['score']} % : {best['artist']} — {best['title']}")
                track["confidence"] = max(int(track.get("confidence") or 0), min(99, int(best["score"])))
                track["mbid"] = best.get("mbid") or track.get("mbid")
            else:
                evidence.append(result.get("message") or "AcoustID : aucune correspondance")
            track["evidence"] = evidence
            update_job(parts[2], tracks=job["tracks"], stage="Empreinte acoustique effectuée")
            self.json_response({"track": track, **result})
            return
        if path != "/v1/detection-jobs":
            self.json_response({"error": "not_found"}, 404); return
        length = int(self.headers.get("Content-Length", "0"))
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
            upload_id = payload["audioSource"]["uploadId"]
        except (json.JSONDecodeError, KeyError, TypeError):
            self.json_response({"error": "invalid_request"}, 400); return
        with DB_LOCK, connect() as database:
            upload = database.execute("SELECT * FROM uploads WHERE id = ?", (upload_id,)).fetchone()
        if not upload:
            self.json_response({"error": "upload_not_found"}, 404); return
        context = {
            "title": str(payload.get("title") or payload.get("audioSource", {}).get("label") or "")[:300],
            "description": str(payload.get("description") or "")[:20_000],
            "sourceUrl": str(payload.get("sourceUrl") or "")[:2_000],
            "automaticTracklist": bool(payload.get("automaticTracklist")),
            "enable1001": bool(payload.get("enable1001")),
            "enableExternalTracklists": bool(
                payload.get(
                    "enableExternalTracklists",
                    payload.get("enable1001"),
                )
            ),
            "sourceKind": str(payload.get("sourceKind") or "")[:30],
            "musical": bool(payload.get("musical")),
            "refineTimestamps": bool(payload.get("refineTimestamps")),
        }
        request_key = str(payload.get("requestKey") or "").strip()[:500] or None
        if request_key and not payload.get("force"):
            existing = get_job_by_request_key(request_key)
            if existing and existing["status"] not in {"failed", "cancelled"}:
                self.json_response(existing)
                return
        if request_key and payload.get("force"):
            with DB_LOCK, connect() as database:
                database.execute(
                    "UPDATE jobs SET request_key = NULL WHERE request_key = ?",
                    (request_key,),
                )
        job_id, created = str(uuid.uuid4()), now()
        with DB_LOCK, connect() as database:
            database.execute(
                """
                INSERT INTO jobs (
                  id, episode_id, upload_id, status, stage, progress,
                  tracks_json, error, operation, context_json, request_key, created_at, updated_at
                ) VALUES (?, ?, ?, 'queued', 'En attente', 0, '[]', NULL, 'analyze', ?, ?, ?, ?)
                """,
                (
                    job_id,
                    payload.get("episodeId", "unknown"),
                    upload_id,
                    json.dumps(context, ensure_ascii=False),
                    request_key,
                    created,
                    created,
                ),
            )
        notify_worker()
        self.json_response(get_job(job_id), 202)

    def do_DELETE(self) -> None:
        parts = urlparse(self.path).path.strip("/").split("/")
        if len(parts) != 3 or parts[:2] != ["v1", "detection-jobs"] or not get_job(parts[2]):
            self.json_response({"error": "job_not_found"}, 404); return
        update_job(parts[2], status="cancelled", stage="Analyse annulée")
        self.json_response(get_job(parts[2]))


if __name__ == "__main__":
    initialize()
    start_worker()
    print(f"Podmix API disponible sur http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
