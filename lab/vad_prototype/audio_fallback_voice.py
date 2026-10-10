"""Fallback audio local pour les mixes techno/trance à tracklist connue.

Le découpage part *du mix lui-même*. Une tracklist ordonnée donne le nombre de
transitions à trouver, pas des positions supposées. Le moteur cherche les
changements persistants de texture, de grave, d'énergie et de rythme, puis
choisit globalement les ``N - 1`` changements les plus plausibles. Des
empreintes publiques gratuites corrigent ensuite une position seulement quand
les deux morceaux adjacents sont reconnus dans le bon ordre. Une référence
isolée ou erronée ne peut donc jamais déplacer une coupe.
"""

from __future__ import annotations

import ipaddress
import math
import os
import re
import shutil
import socket
import subprocess
import tempfile
import unicodedata
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

import numpy as np

from discovery import validate_media_url

SAMPLE_RATE = 8_000
FRAME_SIZE = 1_024
HOP_SIZE = 512
HOP_SECONDS = HOP_SIZE / SAMPLE_RATE
SPECTRAL_BINS = 60
SPECTRAL_BINS_PER_OCTAVE = 12
MAX_AUDIO_BYTES = int(os.environ.get("PODMIX_AUDIO_FALLBACK_MAX_BYTES", str(750 * 1024 * 1024)))
MIN_TRACK_GAP_SECONDS = 45.0
DEEZER_PREVIEW_OFFSET_SECONDS = 30.0
MEDIA_HOSTS = ("youtube.com", "youtu.be", "soundcloud.com", "mixcloud.com")
LANDMARK_DOWNSAMPLE = 4
LANDMARK_SECONDS = HOP_SECONDS * LANDMARK_DOWNSAMPLE
LANDMARK_MAX_HASH_OCCURRENCES = 12
LANDMARK_MIN_SCORE = 10.0
LANDMARK_SPEEDS = (0.94, 0.97, 1.0, 1.03, 1.06)
VOICE_MIN_CONFIDENCE = 0.95
VOICE_TITLE_MIN_SCORE = 0.72
VOICE_TITLE_UNIQUENESS_MARGIN = 0.12
VOICE_PRESENCE_TOLERANCE_SECONDS = 90.0


class AudioFallbackError(RuntimeError):
    """Erreur explicite et présentable du fallback audio."""


@dataclass(frozen=True)
class MatchCandidate:
    anchor_seconds: float
    score: float
    distinctiveness: float
    speed: float


@dataclass(frozen=True)
class FeatureSequence:
    chroma: np.ndarray
    rms: np.ndarray
    bass: np.ndarray
    flux: np.ndarray
    spectral: np.ndarray | None = None

    @property
    def frames(self) -> int:
        return int(self.chroma.shape[0])


@dataclass(frozen=True)
class LandmarkPeak:
    frame: int
    frequency: int
    strength: float


@dataclass(frozen=True)
class LandmarkEvent:
    key: tuple[int, int, int]
    frame: int
    delta: int


@dataclass(frozen=True)
class LandmarkCandidate:
    track: int
    reference: int
    anchor: float
    speed: float
    score: float
    votes: int


@dataclass(frozen=True)
class LandmarkPresence:
    track: int
    anchor: float
    start: float
    end: float
    score: float
    events: int


@dataclass(frozen=True)
class BoundaryAnchor:
    """Début de piste annoncé, distinct d'une présence de preview."""

    track_index: int
    time_seconds: float
    confidence: float
    relation: str
    evidence: str
    detection_id: str


def _voice_text(value: object) -> str:
    normalized = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode()
    return " ".join(re.findall(r"[a-z0-9]+", normalized.lower()))


def _voice_track_score(clause: str, track: dict) -> float:
    haystack = _voice_text(clause)
    title = _voice_text(track.get("title"))
    title_core = _voice_text(str(track.get("title") or "").split("(", 1)[0])
    artist = _voice_text(track.get("artist"))
    if not title:
        return 0.0
    title_score = max(
        SequenceMatcher(None, title, haystack).ratio(),
        SequenceMatcher(None, title_core, haystack).ratio(),
    )
    title_tokens = set((title_core or title).split())
    overlap = len(title_tokens & set(haystack.split())) / max(1, len(title_tokens))
    artist_tokens = {token for token in artist.split() if len(token) >= 4}
    artist_overlap = len(artist_tokens & set(haystack.split())) / max(1, len(artist_tokens))
    exact_title = title in haystack or (len(title_core) >= 5 and title_core in haystack)
    if exact_title:
        title_score = 1.0
    score = title_score * 0.35 + overlap * 0.50 + artist_overlap * 0.15
    # Un mot générique partagé (p. ex. "more music") ne suffit pas.
    if not exact_title and artist_overlap == 0.0:
        score = min(score, 0.60)
    return min(1.0, score)


def interpret_voice_announcements(
    events: list[dict], tracks: list[dict], *, minimum_confidence: float = VOICE_MIN_CONFIDENCE,
) -> tuple[list[BoundaryAnchor], list[dict]]:
    """Interprète automatiquement des transcriptions, sans temps de référence."""
    accepted: list[BoundaryAnchor] = []
    trace: list[dict] = []
    next_markers = (
        "kicking things off with", "move on with", "back to the music with",
        "now it s time for", "next up", "coming next",
    )
    recap_markers = ("coming up tonight", "played so far", "tracks so far", "recap")
    current_markers = ("you re listening to", "currently playing")
    previous_markers = ("that was", "you just heard", "previously")
    for event in events:
        transcript = str(event.get("transcript") or "").strip()
        normalized = _voice_text(transcript)
        confidence = float(event.get("voice_internal_confidence") or 0.0)
        row = {
            "detection_id": str(event.get("detection_id") or "unknown"),
            "confidence": confidence,
            "voice_start_seconds": event.get("voice_start_seconds"),
            "voice_end_seconds": event.get("voice_end_seconds"),
            "transcript": transcript,
            "decision": "abstain",
        }
        if confidence < minimum_confidence:
            row["reason"] = "voice_confidence_below_threshold"
            trace.append(row)
            continue
        marker_position, marker = max((normalized.rfind(item), item) for item in next_markers)
        other_relation = next((name for name, markers in (
            ("recap", recap_markers), ("current", current_markers), ("previous", previous_markers)
        ) if any(item in normalized for item in markers)), None)
        if marker_position < 0:
            row["reason"] = f"no_explicit_next_cue:{other_relation or 'ambiguous'}"
            trace.append(row)
            continue
        # Seule la proposition suivant le dernier marqueur explicite est mise
        # en correspondance. Une liste "coming up" plus tôt reste un récapitulatif.
        clause = normalized[marker_position + len(marker):]
        scores = sorted(
            ((_voice_track_score(clause, track), index) for index, track in enumerate(tracks)),
            reverse=True,
        )
        best_score, best_index = scores[0]
        second_score = scores[1][0] if len(scores) > 1 else 0.0
        row["title_candidates"] = [
            {"track_index": index, "score": round(score, 4)} for score, index in scores[:3]
        ]
        if best_score < VOICE_TITLE_MIN_SCORE:
            row["reason"] = "title_below_threshold"
        elif best_score - second_score < VOICE_TITLE_UNIQUENESS_MARGIN:
            row["reason"] = "ambiguous_title"
        else:
            anchor = BoundaryAnchor(
                track_index=best_index,
                time_seconds=round(float(event.get("voice_end_seconds")), 2),
                confidence=round(confidence * best_score, 4),
                relation="next",
                evidence=f"{marker}: {clause}",
                detection_id=row["detection_id"],
            )
            accepted.append(anchor)
            row.update({
                "decision": "candidate_anchor", "relation": "next",
                "track_index": best_index, "time_seconds": anchor.time_seconds,
                "internal_confidence": anchor.confidence,
            })
        trace.append(row)
    return accepted, trace


def validate_boundary_anchors(
    anchors: list[BoundaryAnchor], track_count: int, duration_seconds: float,
    presences: dict[int, LandmarkPresence], corrections: dict[int, float],
    novelty: np.ndarray | None = None,
) -> tuple[dict[int, BoundaryAnchor], list[dict]]:
    """Conserve seulement les ancres dont l'optimiseur prouve la faisabilité."""
    accepted: dict[int, BoundaryAnchor] = {}
    decisions: list[dict] = []
    feasibility_novelty = novelty
    if feasibility_novelty is None:
        feasibility_novelty = np.zeros(
            max(1, int(duration_seconds / HOP_SECONDS) + 1), dtype=np.float32,
        )
    for anchor in sorted(anchors, key=lambda item: (item.time_seconds, item.track_index)):
        reason = None
        if not 0 <= anchor.track_index < track_count:
            reason = "track_index_out_of_range"
        elif anchor.time_seconds < 0.0 or anchor.time_seconds >= duration_seconds:
            reason = "time_out_of_range"
        elif any(anchor.track_index <= item.track_index for item in accepted.values()):
            reason = "anchor_order_conflict"
        presence = presences.get(anchor.track_index + 1)
        if presence is not None:
            musical_start = max(0.0, presence.anchor - DEEZER_PREVIEW_OFFSET_SECONDS)
            if abs(musical_start - anchor.time_seconds) > VOICE_PRESENCE_TOLERANCE_SECONDS:
                reason = "landmark_presence_conflict"
        correction = corrections.get(anchor.track_index)
        if correction is not None and abs(correction - anchor.time_seconds) > VOICE_PRESENCE_TOLERANCE_SECONDS:
            reason = "adjacent_presence_conflict"
        if reason is None:
            trial = {**accepted, anchor.track_index: anchor}
            try:
                # Source unique de vérité : mêmes couloirs, candidats et DP que
                # l'exécution finale. Aucun seuil parallèle n'est approximé ici.
                detect_constrained_transitions(
                    feasibility_novelty, track_count, duration_seconds, presences, trial,
                )
            except AudioFallbackError as error:
                if str(error) not in {
                    "Ancres audio incompatibles avec une suite de coupes cohérente",
                    "Optimisation audio contrainte incomplète",
                }:
                    raise
                reason = "optimizer_constraint_conflict"
        decisions.append({
            "detection_id": anchor.detection_id, "track_index": anchor.track_index,
            "time_seconds": anchor.time_seconds,
            "decision": "accepted" if reason is None else "abstain", "reason": reason,
        })
        if reason is None and anchor.track_index not in accepted:
            accepted[anchor.track_index] = anchor
    return accepted, decisions


class _SafeAudioRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return super().redirect_request(
            request, file_pointer, code, message, headers, _safe_public_audio_url(new_url)
        )


def _safe_public_audio_url(value: str) -> str:
    parsed = urlparse(str(value or "").strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise AudioFallbackError("URL audio publique invalide")
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
        raise AudioFallbackError("Serveur audio introuvable") from error
    if not addresses or any(not address.is_global for address in addresses):
        raise AudioFallbackError("Adresse audio privée ou locale refusée")
    return str(value).strip()


def _download_direct(url: str, destination: Path) -> None:
    safe_url = _safe_public_audio_url(url)
    opener = build_opener(_SafeAudioRedirects())
    request = Request(safe_url, headers={"User-Agent": "PodmixAudioFallback/1.0"})
    total = 0
    with opener.open(request, timeout=30) as response, destination.open("wb") as output:
        final_url = response.geturl()
        _safe_public_audio_url(final_url)
        declared = int(response.headers.get("Content-Length") or 0)
        if declared > MAX_AUDIO_BYTES:
            raise AudioFallbackError("Fichier audio trop volumineux")
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_AUDIO_BYTES:
                raise AudioFallbackError("Fichier audio trop volumineux")
            output.write(chunk)
    if total < 16_000:
        raise AudioFallbackError("Fichier audio vide ou incomplet")


def _download_media(url: str, destination: Path) -> None:
    safe_url = validate_media_url(url)
    try:
        import yt_dlp

        options = {
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "format": "bestaudio/best",
            "noplaylist": True,
            "outtmpl": str(destination),
            "max_filesize": MAX_AUDIO_BYTES,
            "socket_timeout": 30,
        }
        cookie_file = os.environ.get("YTDLP_COOKIE_FILE", "").strip()
        if cookie_file and Path(cookie_file).is_file():
            # Le secret Docker est monté en lecture seule, alors que yt-dlp
            # actualise son cookiejar même pour un simple téléchargement.
            # Une copie éphémère conserve l'accès sans jamais modifier le
            # secret monté dans le conteneur.
            writable_cookie_file = destination.parent / "yt-dlp-cookies.txt"
            shutil.copyfile(cookie_file, writable_cookie_file)
            options["cookiefile"] = str(writable_cookie_file)
        with yt_dlp.YoutubeDL(options) as downloader:
            downloader.download([safe_url])
    except Exception as error:
        raise AudioFallbackError(f"Téléchargement du mix impossible : {error}") from error
    if not destination.is_file() or destination.stat().st_size < 16_000:
        raise AudioFallbackError("Téléchargement du mix incomplet")


def _download_source(
    audio_url: str,
    source_url: str,
    destination: Path,
    alternate_urls: list[str] | None = None,
) -> None:
    failures: list[str] = []
    if audio_url:
        try:
            _download_direct(audio_url, destination)
            return
        except AudioFallbackError as error:
            failures.append(str(error))
            destination.unlink(missing_ok=True)
    sources = list(dict.fromkeys(
        str(value).strip() for value in [source_url, *(alternate_urls or [])]
        if str(value or "").strip()
    ))
    for candidate in sources:
        host = (urlparse(candidate).hostname or "").casefold()
        try:
            if any(host == domain or host.endswith(f".{domain}") for domain in MEDIA_HOSTS):
                _download_media(candidate, destination)
            else:
                _download_direct(candidate, destination)
            return
        except AudioFallbackError as error:
            failures.append(f"{host or 'source'} : {error}")
            destination.unlink(missing_ok=True)
    detail = failures[-1] if failures else "Aucune source audio disponible"
    raise AudioFallbackError(f"Toutes les sources audio ont échoué · {detail}")


REFERENCE_NOISE_WORDS = {
    "a", "an", "and", "asot", "edit", "extended", "feat", "featuring",
    "mix", "mixed", "official", "original", "radio", "remaster", "remastered",
    "remix", "the", "version", "with",
}


def _reference_words(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKD", str(value or ""))
    ascii_value = "".join(character for character in normalized if not unicodedata.combining(character))
    return {
        word for word in re.findall(r"[a-z0-9]+", ascii_value.casefold())
        if word not in REFERENCE_NOISE_WORDS and not word.isdigit()
    }


def _soundcloud_reference_score(
    artist: str,
    title: str,
    candidate_title: str,
    uploader: str,
) -> float:
    wanted_title = _reference_words(title)
    wanted_artist = _reference_words(artist)
    found_title = _reference_words(candidate_title)
    found_all = found_title | _reference_words(uploader)
    if not wanted_title:
        return 0.0
    title_coverage = len(wanted_title & found_title) / len(wanted_title)
    artist_coverage = (
        len(wanted_artist & found_all) / len(wanted_artist)
        if wanted_artist else 1.0
    )
    # Le titre doit réellement correspondre. Cette porte rejette notamment
    # deux extended mixes du même artiste mais de morceaux différents.
    if title_coverage < 0.72 or artist_coverage < 0.34:
        return 0.0
    return title_coverage * 0.72 + artist_coverage * 0.28


def _soundcloud_reference_url(artist: str, title: str) -> str | None:
    enabled = os.environ.get("PODMIX_AUDIO_SOUNDCLOUD_REFERENCES", "1").strip().lower()
    if enabled in {"0", "false", "no", "off", "disabled"}:
        return None
    if not title or title.casefold().startswith(("unknown", "id ", "id -")):
        return None
    try:
        import yt_dlp

        options = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "playlistend": 3,
            "socket_timeout": 15,
        }
        with yt_dlp.YoutubeDL(options) as downloader:
            result = downloader.extract_info(
                f"scsearch3:{artist} {title}", download=False
            )
    except Exception:
        return None
    candidates: list[tuple[float, str]] = []
    for item in (result or {}).get("entries") or []:
        if not item:
            continue
        duration = float(item.get("duration") or 0)
        if duration and not 20.0 <= duration <= 15 * 60:
            continue
        score = _soundcloud_reference_score(
            artist,
            title,
            str(item.get("title") or item.get("track") or ""),
            str(item.get("uploader") or ""),
        )
        url = str(item.get("webpage_url") or "")
        if score >= 0.72 and url.startswith("https://soundcloud.com/"):
            candidates.append((score, url))
    return max(candidates, default=(0.0, ""))[1] or None


def _track_reference_urls(track: dict) -> list[str]:
    values: list[str] = []
    explicit = track.get("referenceUrls") or []
    if isinstance(explicit, str):
        explicit = [explicit]
    for value in [track.get("previewUrl"), track.get("referenceUrl"), *explicit]:
        clean = str(value or "").strip()
        if clean and clean not in values:
            values.append(clean)
    if not values:
        try:
            # Import local pour garder audio_fallback indépendant au démarrage.
            from catalog import search_deezer

            deezer = search_deezer(
                str(track.get("artist") or ""), str(track.get("title") or "")
            )
            preview = str((deezer or {}).get("previewUrl") or "").strip()
            if preview and int((deezer or {}).get("score") or 0) >= 70:
                values.append(preview)
        except Exception:
            pass
    soundcloud = _soundcloud_reference_url(
        str(track.get("artist") or ""), str(track.get("title") or "")
    )
    if soundcloud and soundcloud not in values:
        values.append(soundcloud)
    return values[:2]


def _download_reference(url: str, destination: Path) -> None:
    host = (urlparse(url).hostname or "").casefold()
    if any(host == domain or host.endswith(f".{domain}") for domain in MEDIA_HOSTS):
        _download_media(url, destination)
    else:
        _download_direct(url, destination)


def _decode_to_f32(source: Path, destination: Path) -> None:
    ffmpeg = os.environ.get("PODMIX_FFMPEG", "").strip() or shutil.which("ffmpeg")
    if not ffmpeg:
        raise AudioFallbackError("FFmpeg est absent du serveur")
    result = subprocess.run(
        [
            ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source), "-map", "0:a:0", "-ac", "1", "-ar", str(SAMPLE_RATE),
            "-f", "f32le", str(destination),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        timeout=45 * 60,
        check=False,
    )
    if result.returncode or not destination.is_file() or destination.stat().st_size < SAMPLE_RATE * 4:
        detail = result.stderr.decode(errors="replace")[-500:].strip()
        raise AudioFallbackError(f"Décodage audio impossible{f' : {detail}' if detail else ''}")


def extract_features(samples: np.ndarray) -> FeatureSequence:
    """Extrait des descripteurs légers, stables sur les mixes électroniques."""
    samples = np.asarray(samples, dtype=np.float32)
    frame_count = 1 + max(0, (samples.size - FRAME_SIZE) // HOP_SIZE)
    if frame_count < 8:
        raise AudioFallbackError("Audio trop court pour l’analyse")

    frequencies = np.fft.rfftfreq(FRAME_SIZE, 1 / SAMPLE_RATE)
    chroma_map = np.zeros((frequencies.size, 12), dtype=np.float32)
    usable = (frequencies >= 55.0) & (frequencies <= 2_500.0)
    midi = np.rint(69 + 12 * np.log2(np.maximum(frequencies[usable], 1.0) / 440.0)).astype(int)
    chroma_map[np.flatnonzero(usable), np.mod(midi, 12)] = 1.0
    spectral_map = np.zeros((frequencies.size, SPECTRAL_BINS), dtype=np.float32)
    spectral_low, spectral_high = 55.0, 3_500.0
    spectral_usable = (frequencies >= spectral_low) & (frequencies <= spectral_high)
    spectral_positions = np.floor(
        (np.log2(frequencies[spectral_usable] / spectral_low)
         / np.log2(spectral_high / spectral_low)) * SPECTRAL_BINS
    ).astype(int)
    spectral_map[np.flatnonzero(spectral_usable), np.clip(spectral_positions, 0, SPECTRAL_BINS - 1)] = 1.0
    bass_mask = (frequencies >= 45.0) & (frequencies <= 220.0)
    window = np.hanning(FRAME_SIZE).astype(np.float32)

    chroma = np.empty((frame_count, 12), dtype=np.float32)
    spectral = np.empty((frame_count, SPECTRAL_BINS), dtype=np.float32)
    rms = np.empty(frame_count, dtype=np.float32)
    bass = np.empty(frame_count, dtype=np.float32)
    flux = np.zeros(frame_count, dtype=np.float32)
    previous_log_spectrum: np.ndarray | None = None

    chunk_frames = 2_048
    for first in range(0, frame_count, chunk_frames):
        count = min(chunk_frames, frame_count - first)
        offsets = (first + np.arange(count))[:, None] * HOP_SIZE + np.arange(FRAME_SIZE)[None, :]
        frames = samples[offsets] * window
        spectrum = np.abs(np.fft.rfft(frames, axis=1)).astype(np.float32)
        power = spectrum * spectrum
        log_power = np.log1p(power)
        block_chroma = log_power @ chroma_map
        norm = np.linalg.norm(block_chroma, axis=1, keepdims=True)
        chroma[first:first + count] = block_chroma / np.maximum(norm, 1e-7)
        block_spectral = log_power @ spectral_map
        spectral_norm = np.linalg.norm(block_spectral, axis=1, keepdims=True)
        spectral[first:first + count] = block_spectral / np.maximum(spectral_norm, 1e-7)
        rms[first:first + count] = np.sqrt(np.mean(frames * frames, axis=1))
        bass[first:first + count] = (
            np.sum(power[:, bass_mask], axis=1) / np.maximum(np.sum(power, axis=1), 1e-9)
        )
        if previous_log_spectrum is not None:
            delta = np.diff(np.vstack([previous_log_spectrum, log_power]), axis=0)
        else:
            delta = np.diff(log_power, axis=0, prepend=log_power[:1])
        flux[first:first + count] = np.mean(np.maximum(delta, 0.0), axis=1)
        previous_log_spectrum = log_power[-1]

    return FeatureSequence(chroma=chroma, rms=rms, bass=bass, flux=flux, spectral=spectral)


def landmark_constellation(spectral: np.ndarray) -> list[LandmarkPeak]:
    """Conserve les maxima locaux temps/fréquence d'une empreinte spectrale."""
    usable_frames = spectral.shape[0] // LANDMARK_DOWNSAMPLE * LANDMARK_DOWNSAMPLE
    if usable_frames < LANDMARK_DOWNSAMPLE:
        return []
    reduced = spectral[:usable_frames].reshape(
        -1, LANDMARK_DOWNSAMPLE, spectral.shape[1]
    ).mean(axis=1)
    threshold = float(np.percentile(reduced, 72))
    local = np.ones(reduced.shape, dtype=bool)
    # Équivalent léger d'un maximum_filter(5, 3), sans dépendance SciPy.
    for time_shift in range(-2, 3):
        for frequency_shift in range(-1, 2):
            if time_shift == 0 and frequency_shift == 0:
                continue
            source_time = slice(max(0, -time_shift), min(reduced.shape[0], reduced.shape[0] - time_shift))
            target_time = slice(max(0, time_shift), min(reduced.shape[0], reduced.shape[0] + time_shift))
            source_frequency = slice(
                max(0, -frequency_shift),
                min(reduced.shape[1], reduced.shape[1] - frequency_shift),
            )
            target_frequency = slice(
                max(0, frequency_shift),
                min(reduced.shape[1], reduced.shape[1] + frequency_shift),
            )
            local[target_time, target_frequency] &= (
                reduced[target_time, target_frequency]
                >= reduced[source_time, source_frequency] - 1e-7
            )
    mask = local & (reduced >= threshold)
    peaks: list[LandmarkPeak] = []
    for frame in range(reduced.shape[0]):
        frequencies = np.flatnonzero(mask[frame])
        if frequencies.size > 4:
            strongest = np.argsort(reduced[frame, frequencies])[-4:]
            frequencies = frequencies[strongest]
        peaks.extend(
            LandmarkPeak(frame, int(frequency), float(reduced[frame, frequency]))
            for frequency in frequencies
        )
    return sorted(peaks, key=lambda peak: (peak.frame, peak.frequency))


def landmark_hashes(
    peaks: list[LandmarkPeak], speed: float = 1.0
) -> list[LandmarkEvent]:
    """Relie les maxima comme une constellation Shazam tolérant ±6 % de pitch."""
    if not peaks:
        return []
    times = np.asarray([peak.frame for peak in peaks])
    pitch_shift = round(12 * math.log2(speed))
    events: list[LandmarkEvent] = []
    for peak in peaks:
        first = int(np.searchsorted(times, peak.frame + 2))
        last = int(np.searchsorted(times, peak.frame + 25))
        targets = sorted(
            peaks[first:last], key=lambda target: target.strength, reverse=True
        )[:6]
        for target in targets:
            delta = round((target.frame - peak.frame) / speed)
            frequency = peak.frequency + pitch_shift
            target_frequency = target.frequency + pitch_shift
            if (
                0 <= frequency < SPECTRAL_BINS
                and 0 <= target_frequency < SPECTRAL_BINS
                and 2 <= delta <= 24
            ):
                events.append(LandmarkEvent(
                    (frequency, target_frequency, delta),
                    round(peak.frame / speed),
                    delta,
                ))
    return events


def landmark_index(
    events: list[LandmarkEvent],
) -> dict[tuple[int, int, int], list[LandmarkEvent]]:
    result: dict[tuple[int, int, int], list[LandmarkEvent]] = defaultdict(list)
    for event in events:
        result[event.key].append(event)
    return result


def landmark_reference_candidates(
    track: int,
    reference: int,
    reference_events: dict[float, list[LandmarkEvent]],
    mix_index: dict[tuple[int, int, int], list[LandmarkEvent]],
    duration: float,
) -> list[LandmarkCandidate]:
    candidates: list[LandmarkCandidate] = []
    for speed, events in reference_events.items():
        weighted: dict[int, float] = defaultdict(float)
        raw: dict[int, int] = defaultdict(int)
        for event in events:
            matches = mix_index.get(event.key, ())
            if not matches or len(matches) > LANDMARK_MAX_HASH_OCCURRENCES:
                continue
            weight = 1.0 / len(matches)
            for match in matches:
                offset = round((match.frame - event.frame) / 2) * 2
                weighted[offset] += weight
                raw[offset] += 1
        # Une référence de 30 s produit moins de votes qu'un extended mix.
        # Le score pondéré reste strict et le minimum de votes s'adapte à sa taille.
        minimum_votes = min(30, max(15, round(len(events) * 0.018)))
        for offset, score in sorted(
            weighted.items(), key=lambda item: item[1], reverse=True
        )[:8]:
            anchor = offset * LANDMARK_SECONDS
            votes = raw[offset]
            if (
                score >= LANDMARK_MIN_SCORE
                and votes >= minimum_votes
                and -60.0 <= anchor < duration
            ):
                candidates.append(LandmarkCandidate(
                    track, reference, anchor, speed, round(score, 3), votes
                ))
    candidates.sort(key=lambda item: (item.score, item.votes), reverse=True)
    selected: list[LandmarkCandidate] = []
    for candidate in candidates:
        if all(abs(candidate.anchor - other.anchor) >= 6.0 for other in selected):
            selected.append(candidate)
        if len(selected) == 8:
            break
    return selected


def select_ordered_landmarks(
    candidate_sets: list[list[LandmarkCandidate]],
) -> dict[int, LandmarkCandidate]:
    """Préfère une longue chaîne ordonnée aux meilleurs faux positifs isolés."""
    nodes = sorted(
        (candidate for candidates in candidate_sets for candidate in candidates),
        key=lambda candidate: (candidate.track, candidate.anchor),
    )
    states: list[tuple[int, float, int | None]] = []
    for index, candidate in enumerate(nodes):
        best = (1, math.log1p(candidate.score), None)
        for parent_index, parent in enumerate(nodes[:index]):
            if parent.track >= candidate.track:
                continue
            track_delta = candidate.track - parent.track
            if candidate.anchor - parent.anchor < MIN_TRACK_GAP_SECONDS * track_delta:
                continue
            length, score, _ = states[parent_index]
            proposal = (
                length + 1,
                score + math.log1p(candidate.score) - max(0, track_delta - 1),
                parent_index,
            )
            if proposal[:2] > best[:2]:
                best = proposal
        states.append(best)
    if not nodes:
        return {}
    cursor: int | None = max(range(len(nodes)), key=lambda item: states[item][:2])
    chosen: dict[int, LandmarkCandidate] = {}
    while cursor is not None:
        chosen[nodes[cursor].track] = nodes[cursor]
        cursor = states[cursor][2]
    return chosen


def landmark_presence(
    candidate: LandmarkCandidate,
    reference_events: list[LandmarkEvent],
    mix_index: dict[tuple[int, int, int], list[LandmarkEvent]],
) -> LandmarkPresence | None:
    evidence: list[float] = []
    for event in reference_events:
        matches = mix_index.get(event.key, ())
        if not matches or len(matches) > LANDMARK_MAX_HASH_OCCURRENCES:
            continue
        for match in matches:
            offset = (match.frame - event.frame) * LANDMARK_SECONDS
            if abs(offset - candidate.anchor) <= 1.1:
                evidence.extend((
                    match.frame * LANDMARK_SECONDS,
                    (match.frame + match.delta) * LANDMARK_SECONDS,
                ))
    if len(evidence) < max(24, candidate.votes):
        return None
    start, end = np.percentile(np.asarray(evidence), [3, 97])
    return LandmarkPresence(
        candidate.track,
        candidate.anchor,
        round(float(start), 2),
        round(float(end), 2),
        candidate.score,
        len(evidence) // 2,
    )


def landmark_transition_corrections(
    mix_features: FeatureSequence,
    references: list[list[FeatureSequence]],
    duration: float,
    audio_candidates: list[tuple[float, float]],
    *,
    include_presences: bool = False,
) -> tuple[dict[int, float], dict[int, str], int] | tuple[dict[int, float], dict[int, str], int, dict[int, LandmarkPresence]]:
    """Retourne uniquement les transitions prouvées par deux pistes adjacentes.

    Une empreinte isolée sert de contexte, jamais de timestamp : c'est la règle
    qui empêche un mashup ou une mauvaise version catalogue de déplacer une
    coupe spectrale correcte.
    """
    if mix_features.spectral is None or not any(references):
        empty = ({}, {}, 0)
        return (*empty, {}) if include_presences else empty
    mix_events = landmark_hashes(landmark_constellation(mix_features.spectral))
    mix_event_index = landmark_index(mix_events)
    cached_events: dict[tuple[int, int, float], list[LandmarkEvent]] = {}
    candidate_sets: list[list[LandmarkCandidate]] = []
    for track, track_references in enumerate(references, start=1):
        candidates: list[LandmarkCandidate] = []
        for reference_index, features in enumerate(track_references):
            if features.spectral is None:
                continue
            peaks = landmark_constellation(features.spectral)
            by_speed = {
                speed: landmark_hashes(peaks, speed) for speed in LANDMARK_SPEEDS
            }
            for speed, events in by_speed.items():
                cached_events[(track, reference_index, speed)] = events
            candidates.extend(landmark_reference_candidates(
                track,
                reference_index,
                by_speed,
                mix_event_index,
                duration,
            ))
        candidate_sets.append(candidates)
    chosen = select_ordered_landmarks(candidate_sets)
    presences: dict[int, LandmarkPresence] = {}
    for track, candidate in chosen.items():
        presence = landmark_presence(
            candidate,
            cached_events[(track, candidate.reference, candidate.speed)],
            mix_event_index,
        )
        if presence is not None:
            presences[track] = presence

    corrections: dict[int, float] = {}
    confidence: dict[int, str] = {}
    for incoming in range(2, len(references) + 1):
        outgoing_presence = presences.get(incoming - 1)
        incoming_presence = presences.get(incoming)
        if outgoing_presence is None or incoming_presence is None:
            continue
        left, right = outgoing_presence.end, incoming_presence.start
        midpoint = (left + right) / 2
        position = midpoint
        gap = right - left
        if gap > 90.0:
            local = [item for item in audio_candidates if left <= item[0] <= right]
            if local:
                nearest = min(local, key=lambda item: abs(item[0] - midpoint))
                if abs(nearest[0] - midpoint) <= 30.0:
                    position = nearest[0]
        corrections[incoming - 1] = round(position, 2)
        confidence[incoming - 1] = (
            "high" if left >= right or abs(gap) <= 90.0
            else "medium" if gap <= 180.0
            else "low"
        )

    # Les corrections directes restent strictes : il faut les deux morceaux
    # adjacents. Une empreinte isolée ne devient jamais une coupe ; elle sert
    # uniquement, dans l'optimisation suivante, à recalculer son intervalle
    # théorique local. L'ancienne interpolation "si 50 % des pistes sont
    # reconnues" est volontairement supprimée : elle pouvait créer une longue
    # coupe arbitraire dans les parties peu couvertes.
    result = (corrections, confidence, len(presences))
    return (*result, presences) if include_presences else result


def _nearest_landmark_audio_change(
    expected: float,
    lower: float,
    upper: float,
    audio_candidates: list[tuple[float, float]],
    tolerance: float,
) -> float:
    local = [candidate for candidate in audio_candidates if lower <= candidate[0] <= upper]
    if not local:
        return expected
    nearest = min(local, key=lambda candidate: abs(candidate[0] - expected))
    return nearest[0] if abs(nearest[0] - expected) <= tolerance else expected


def _resample_time(template: np.ndarray, speed: float) -> np.ndarray:
    target_frames = max(16, int(round(template.shape[0] / speed)))
    old_x = np.linspace(0.0, 1.0, template.shape[0])
    new_x = np.linspace(0.0, 1.0, target_frames)
    stretched = np.vstack([
        np.interp(new_x, old_x, template[:, feature_bin])
        for feature_bin in range(template.shape[1])
    ]).T.astype(np.float32)

    return stretched


def _resample_chroma(template: np.ndarray, speed: float) -> np.ndarray:
    stretched = _resample_time(template, speed)
    # Un changement de vitesse d'une platine modifie simultanément durée et
    # hauteur. L'interpolation circulaire tolère les ±6 % usuels en trance.
    semitones = 12.0 * math.log2(speed)
    low = math.floor(semitones)
    fraction = semitones - low
    shifted = (1.0 - fraction) * np.roll(stretched, low, axis=1) + fraction * np.roll(stretched, low + 1, axis=1)
    norm = np.linalg.norm(shifted, axis=1, keepdims=True)
    return shifted / np.maximum(norm, 1e-7)


def _resample_spectral(template: np.ndarray, speed: float) -> np.ndarray:
    """Retient la texture par octave, absente d'un chroma 12 classes."""
    stretched = _resample_time(template, speed)
    shift = SPECTRAL_BINS_PER_OCTAVE * math.log2(speed)
    bins = np.arange(stretched.shape[1], dtype=np.float32)
    shifted = np.vstack([
        np.interp(bins - shift, bins, row, left=0.0, right=0.0)
        for row in stretched
    ]).astype(np.float32)
    norm = np.linalg.norm(shifted, axis=1, keepdims=True)
    return shifted / np.maximum(norm, 1e-7)


def _fft_similarity(
    mix: np.ndarray,
    template: np.ndarray,
    mix_fft_cache: dict[tuple[int, int], np.ndarray] | None = None,
) -> np.ndarray:
    valid = mix.shape[0] - template.shape[0] + 1
    if valid <= 0:
        return np.empty(0, dtype=np.float32)
    full_size = mix.shape[0] + template.shape[0] - 1
    fft_size = 1 << (full_size - 1).bit_length()
    cache = mix_fft_cache if mix_fft_cache is not None else {}
    cache_key = (fft_size, mix.shape[1])
    mix_fft = cache.get(cache_key)
    if mix_fft is None:
        mix_fft = np.fft.rfft(mix, fft_size, axis=0)
        cache[cache_key] = mix_fft
    template_fft = np.fft.rfft(template[::-1], fft_size, axis=0)
    correlated = np.fft.irfft(mix_fft * template_fft, fft_size, axis=0)
    score = np.sum(
        correlated[template.shape[0] - 1:template.shape[0] - 1 + valid],
        axis=1,
        dtype=np.float64,
    )
    return (score / template.shape[0]).astype(np.float32)


def match_reference(
    mix_chroma: np.ndarray,
    preview_chroma: np.ndarray,
    top_k: int = 8,
    mix_spectral: np.ndarray | None = None,
    preview_spectral: np.ndarray | None = None,
) -> list[MatchCandidate]:
    """Aligne plusieurs fenêtres d'un extrait et fait voter leurs positions.

    Dans un long blend, une partie des 30 s Deezer peut être filtrée ou
    bouclée. Deux fenêtres indépendantes qui pointent au même endroit sont
    plus fiables qu'une corrélation globale unique.
    """
    candidates: list[MatchCandidate] = []
    minimum_distance = max(1, int(45 / HOP_SECONDS))
    mix_fft_cache: dict[tuple[int, int], np.ndarray] = {}
    for speed in (0.94, 0.97, 1.0, 1.03, 1.06):
        template = _resample_chroma(preview_chroma, speed)
        spectral_template = (
            _resample_spectral(preview_spectral, speed)
            if mix_spectral is not None and preview_spectral is not None
            else None
        )
        window_frames = min(template.shape[0], max(64, int(8.0 / HOP_SECONDS)))
        starts = np.linspace(
            0,
            max(0, template.shape[0] - window_frames),
            num=1 if template.shape[0] <= window_frames else 5,
            dtype=int,
        )
        for start in np.unique(starts):
            window = template[start:start + window_frames]
            scores = _fft_similarity(mix_chroma, window, mix_fft_cache)
            if spectral_template is not None:
                spectral_scores = _fft_similarity(
                    mix_spectral,
                    spectral_template[start:start + window_frames],
                    mix_fft_cache,
                )
                scores = scores * 0.30 + spectral_scores * 0.70
            if scores.size == 0:
                continue
            median = float(np.median(scores))
            deviation = float(np.median(np.abs(scores - median))) * 1.4826 + 1e-6
            available = scores.copy()
            for _ in range(3):
                frame = int(np.argmax(available))
                score = float(available[frame])
                distinctiveness = (score - median) / deviation
                if distinctiveness >= 1.4:
                    # Retour au début de l'aperçu, pas au début de la fenêtre.
                    candidates.append(MatchCandidate(
                        (frame - start) * HOP_SECONDS,
                        score,
                        distinctiveness,
                        speed,
                    ))
                left, right = max(0, frame - minimum_distance), min(available.size, frame + minimum_distance + 1)
                available[left:right] = -np.inf

    # Fusionne les votes voisins. Les groupes sont volontairement courts :
    # l'affinage des transitions interviendra ensuite, mais le fingerprint doit
    # déjà localiser le bon morceau à quelques secondes près.
    candidates.sort(key=lambda item: (item.anchor_seconds, item.speed))
    clusters: list[list[MatchCandidate]] = []
    for candidate in candidates:
        matching = next((cluster for cluster in clusters if (
            abs(candidate.anchor_seconds - np.mean([item.anchor_seconds for item in cluster])) <= 8.0
            and abs(candidate.speed - np.mean([item.speed for item in cluster])) <= 0.005
        )), None)
        if matching is None:
            clusters.append([candidate])
        else:
            matching.append(candidate)

    consolidated: list[MatchCandidate] = []
    for cluster in clusters:
        # Une seule fenêtre est trop ambiguë dans les rythmiques 4/4.
        if len(cluster) < 2:
            continue
        weights = np.array([max(0.1, item.distinctiveness) for item in cluster], dtype=np.float64)
        distinctiveness = float(np.sum(weights))
        if distinctiveness < 5.5:
            continue
        consolidated.append(MatchCandidate(
            anchor_seconds=float(np.average([item.anchor_seconds for item in cluster], weights=weights)),
            score=float(np.average([item.score for item in cluster], weights=weights)),
            distinctiveness=distinctiveness,
            speed=float(np.average([item.speed for item in cluster], weights=weights)),
        ))
    consolidated.sort(key=lambda item: (item.distinctiveness, item.score), reverse=True)
    selected: list[MatchCandidate] = []
    for candidate in consolidated:
        if all(abs(candidate.anchor_seconds - item.anchor_seconds) >= 35 for item in selected):
            selected.append(candidate)
        if len(selected) >= top_k:
            break
    return selected


def select_global_sequence(
    candidate_sets: list[list[MatchCandidate]],
    duration_seconds: float,
) -> dict[int, MatchCandidate]:
    """Choisit la suite de matches la plus cohérente avec l'ordre connu."""
    track_count = len(candidate_sets)
    nodes = []
    for track_index, candidates in enumerate(candidate_sets):
        earliest = track_index * MIN_TRACK_GAP_SECONDS + DEEZER_PREVIEW_OFFSET_SECONDS
        latest = duration_seconds - (track_count - track_index - 1) * MIN_TRACK_GAP_SECONDS
        for candidate in candidates:
            if candidate.anchor_seconds < earliest or candidate.anchor_seconds > latest:
                continue
            nodes.append((track_index, candidate))
    if not nodes:
        return {}
    expected_gap = duration_seconds / max(1, len(candidate_sets))
    nodes.sort(key=lambda item: (item[0], item[1].anchor_seconds))
    states: list[tuple[float, int | None, int]] = []
    for node_index, (track_index, candidate) in enumerate(nodes):
        start_prior = -abs(
            candidate.anchor_seconds - (track_index * expected_gap + DEEZER_PREVIEW_OFFSET_SECONDS)
        ) / max(60.0, expected_gap)
        best_score = candidate.distinctiveness + start_prior
        best_parent: int | None = None
        best_length = 1
        for parent_index in range(node_index):
            previous_track, previous = nodes[parent_index]
            track_delta = track_index - previous_track
            if track_delta <= 0:
                continue
            gap = candidate.anchor_seconds - previous.anchor_seconds
            if gap < MIN_TRACK_GAP_SECONDS * track_delta:
                continue
            wanted = max(MIN_TRACK_GAP_SECONDS, expected_gap * track_delta)
            gap_penalty = abs(math.log(max(gap, 1.0) / wanted)) * 1.25
            skipped_track_penalty = max(0, track_delta - 1) * 0.35
            score = states[parent_index][0] + candidate.distinctiveness - gap_penalty - skipped_track_penalty
            length = states[parent_index][2] + 1
            if score > best_score or (math.isclose(score, best_score) and length > best_length):
                best_score, best_parent, best_length = score, parent_index, length
        states.append((best_score, best_parent, best_length))

    last_candidate = max(
        range(len(states)),
        key=lambda index: (states[index][2], states[index][0]),
    )
    result: dict[int, MatchCandidate] = {}
    while last_candidate is not None:
        track_index, candidate = nodes[last_candidate]
        result[track_index] = candidate
        parent = states[last_candidate][1]
        if parent is None:
            break
        last_candidate = parent
    return result


def transition_novelty(features: FeatureSequence, window_seconds: float = 16.0) -> np.ndarray:
    radius = max(4, int(window_seconds / HOP_SECONDS))
    frames = features.frames
    novelty = np.zeros(frames, dtype=np.float32)
    if frames <= radius * 2:
        return novelty

    chroma_sum = np.vstack([np.zeros((1, 12), dtype=np.float64), np.cumsum(features.chroma, axis=0, dtype=np.float64)])
    bass_sum = np.concatenate([[0.0], np.cumsum(features.bass, dtype=np.float64)])
    positions = np.arange(radius, frames - radius)
    left = chroma_sum[positions] - chroma_sum[positions - radius]
    right = chroma_sum[positions + radius] - chroma_sum[positions]
    cosine = np.sum(left * right, axis=1) / np.maximum(np.linalg.norm(left, axis=1) * np.linalg.norm(right, axis=1), 1e-8)
    bass_change = np.abs(
        (bass_sum[positions] - bass_sum[positions - radius]) / radius
        - (bass_sum[positions + radius] - bass_sum[positions]) / radius
    )
    flux = features.flux[positions]
    flux = (flux - np.median(flux)) / (np.median(np.abs(flux - np.median(flux))) * 1.4826 + 1e-6)
    novelty[positions] = (1.0 - cosine) + bass_change * 1.5 + np.maximum(flux, 0.0) * 0.08
    smooth_frames = max(1, int(2.0 / HOP_SECONDS))
    kernel = np.ones(smooth_frames, dtype=np.float32) / smooth_frames
    return np.convolve(novelty, kernel, mode="same").astype(np.float32)


def _robust_positive(values: np.ndarray) -> np.ndarray:
    """Met des mesures hétérogènes sur une échelle robuste commune."""
    median = float(np.median(values))
    scale = float(np.median(np.abs(values - median))) * 1.4826 + 1e-6
    return np.maximum(0.0, (values - median) / scale).astype(np.float32)


def pure_transition_novelty(features: FeatureSequence) -> np.ndarray:
    """Courbe de changement adaptée aux crossfades techno/trance.

    Une transition n'est pas nécessairement un pic bref : elle peut durer 32 à
    90 secondes. On compare donc les moyennes avant/après à 8, 16 et 32 s. Les
    trois échelles font ressortir à la fois un drop, une sortie progressive de
    basse et le remplacement lent de la texture harmonique.
    """
    frames = features.frames
    result = np.zeros(frames, dtype=np.float32)
    if frames < int(20 / HOP_SECONDS):
        return result
    spectral = features.spectral
    chroma_prefix = np.vstack([np.zeros((1, 12)), np.cumsum(features.chroma, axis=0, dtype=np.float64)])
    spectral_prefix = (
        np.vstack([np.zeros((1, spectral.shape[1])), np.cumsum(spectral, axis=0, dtype=np.float64)])
        if spectral is not None else None
    )
    bass_prefix = np.concatenate([[0.0], np.cumsum(features.bass, dtype=np.float64)])
    rms_prefix = np.concatenate([[0.0], np.cumsum(features.rms, dtype=np.float64)])
    flux_prefix = np.concatenate([[0.0], np.cumsum(features.flux, dtype=np.float64)])

    for seconds, weight in ((8.0, 0.65), (16.0, 1.0), (32.0, 0.85)):
        radius = max(2, int(round(seconds / HOP_SECONDS)))
        if frames <= radius * 2:
            continue
        positions = np.arange(radius, frames - radius)
        left_chroma = (chroma_prefix[positions] - chroma_prefix[positions - radius]) / radius
        right_chroma = (chroma_prefix[positions + radius] - chroma_prefix[positions]) / radius
        harmonic = 1.0 - (
            np.sum(left_chroma * right_chroma, axis=1)
            / np.maximum(np.linalg.norm(left_chroma, axis=1) * np.linalg.norm(right_chroma, axis=1), 1e-8)
        )
        if spectral_prefix is not None:
            left_spectral = (spectral_prefix[positions] - spectral_prefix[positions - radius]) / radius
            right_spectral = (spectral_prefix[positions + radius] - spectral_prefix[positions]) / radius
            texture = 1.0 - (
                np.sum(left_spectral * right_spectral, axis=1)
                / np.maximum(np.linalg.norm(left_spectral, axis=1) * np.linalg.norm(right_spectral, axis=1), 1e-8)
            )
        else:
            texture = np.zeros_like(harmonic)
        bass_change = np.abs(
            (bass_prefix[positions] - bass_prefix[positions - radius]) / radius
            - (bass_prefix[positions + radius] - bass_prefix[positions]) / radius
        )
        energy_change = np.abs(
            (rms_prefix[positions] - rms_prefix[positions - radius]) / radius
            - (rms_prefix[positions + radius] - rms_prefix[positions]) / radius
        )
        onset_change = np.abs(
            (flux_prefix[positions] - flux_prefix[positions - radius]) / radius
            - (flux_prefix[positions + radius] - flux_prefix[positions]) / radius
        )
        # Les poids privilégient ce qu'un auditeur repère dans ce genre :
        # l'arrivée/disparition de la texture et de la basse, pas une cymbale.
        local = (
            _robust_positive(harmonic) * 0.30
            + _robust_positive(texture) * 0.32
            + _robust_positive(bass_change) * 0.23
            + _robust_positive(energy_change) * 0.10
            + _robust_positive(onset_change) * 0.05
        )
        result[positions] += local * weight

    # Une transition longue peut former un plateau ; cette moyenne préserve son
    # milieu, qui est la convention de découpe choisie pour Podmix.
    smooth = max(1, int(round(3.0 / HOP_SECONDS)))
    return np.convolve(result, np.ones(smooth, dtype=np.float32) / smooth, mode="same").astype(np.float32)


def _transition_candidates(novelty: np.ndarray, duration_seconds: float) -> list[tuple[float, float]]:
    """Retourne des maxima locaux, sans inventer de candidats uniformes."""
    margin = max(MIN_TRACK_GAP_SECONDS * 0.5, 20.0)
    lower = int(margin / HOP_SECONDS)
    upper = min(novelty.size, int((duration_seconds - margin) / HOP_SECONDS))
    if upper <= lower:
        return []
    values = novelty[lower:upper]
    normalized = _robust_positive(values)
    # Une fenêtre de 12 s évite de compter les diverses micro-ruptures d'un
    # même break comme plusieurs morceaux.
    half_window = max(1, int(12.0 / HOP_SECONDS))
    peaks: list[tuple[float, float]] = []
    for relative in range(half_window, max(half_window, normalized.size - half_window)):
        score = float(normalized[relative])
        if score < 0.55:
            continue
        start, end = relative - half_window, relative + half_window + 1
        if score >= float(np.max(normalized[start:end])):
            peaks.append(((relative + lower) * HOP_SECONDS, score))
    # Les plateaux peuvent donner plusieurs maxima identiques : on conserve le
    # centre pondéré du groupe, donc le milieu perceptible du crossfade.
    groups: list[list[tuple[float, float]]] = []
    for peak in peaks:
        if groups and peak[0] - groups[-1][-1][0] <= 18.0:
            groups[-1].append(peak)
        else:
            groups.append([peak])
    return [
        (float(np.average([time for time, _ in group], weights=[score for _, score in group])), max(score for _, score in group))
        for group in groups
    ]


def _anchored_theoretical_boundaries(
    track_count: int,
    duration_seconds: float,
    presences: dict[int, LandmarkPresence],
    boundary_anchors: dict[int, BoundaryAnchor] | None = None,
) -> list[float]:
    """Calcule les couloirs théoriques *locaux* à partir des ancres sûres.

    Une empreinte d'un morceau n'est pas une coupe, mais elle localise son
    début (un aperçu Deezer commence vers 30 s). On l'utilise donc comme point
    de maillage. Entre deux ancres, les morceaux inconnus sont répartis selon
    la durée disponible de cet intervalle — jamais selon la moyenne globale du
    set. Les bornes 0 et durée sont des ancres virtuelles.
    """
    voice = boundary_anchors or {}
    first_start = voice[0].time_seconds if 0 in voice else 0.0
    points: dict[int, float] = {0: first_start, track_count: duration_seconds}
    for track, presence in presences.items():
        # ``anchor`` est le début du fichier de référence dans le mix ; pour
        # les previews Deezer, le vrai départ est environ 30 s plus tôt.
        boundary_index = track - 1
        cue = max(0.0, presence.anchor - DEEZER_PREVIEW_OFFSET_SECONDS)
        if 0 < boundary_index < track_count:
            points[boundary_index] = cue
    # Canal distinct : aucun offset de preview n'est appliqué aux annonces.
    for boundary_index, anchor in voice.items():
        if 0 <= boundary_index < track_count:
            points[boundary_index] = anchor.time_seconds
    ordered = sorted(points.items())
    theoretical = [0.0] * (track_count + 1)
    for (left_index, left_time), (right_index, right_time) in zip(ordered, ordered[1:]):
        span = max(1, right_index - left_index)
        for index in range(left_index, right_index + 1):
            ratio = (index - left_index) / span
            theoretical[index] = left_time + (right_time - left_time) * ratio
    theoretical[0], theoretical[-1] = first_start, duration_seconds
    # Une empreinte catalogue peut pointer un mashup, un vocal isolé ou une
    # mauvaise version. Son ancre reste un indice, jamais une contrainte
    # capable de rendre le problème insoluble. On projette donc le maillage
    # sur une suite réalisable avec au moins 70 s par morceau.
    average = duration_seconds / max(1, track_count)
    minimum = min(70.0, average)
    maximum = max(minimum, min(450.0, average * 1.8))
    # Quelques passes avant/arrière projettent les ancres sur l'ensemble des
    # durées réalisables [minimum, maximum], en gardant 0 et la fin fixes.
    for _ in range(4):
        theoretical[0] = first_start
        for index in range(1, track_count):
            theoretical[index] = min(
                theoretical[index - 1] + maximum,
                max(theoretical[index], theoretical[index - 1] + minimum),
            )
        theoretical[-1] = duration_seconds
        for index in range(track_count - 1, 0, -1):
            theoretical[index] = max(
                theoretical[index + 1] - maximum,
                min(theoretical[index], theoretical[index + 1] - minimum),
            )
    theoretical[0], theoretical[-1] = first_start, duration_seconds
    return theoretical


def detect_constrained_transitions(
    novelty: np.ndarray,
    track_count: int,
    duration_seconds: float,
    presences: dict[int, LandmarkPresence],
    boundary_anchors: dict[int, BoundaryAnchor] | None = None,
) -> list[float]:
    """Optimise les coupes dans des couloirs audio recalculés par ancres.

    Les pics spectraux ne choisissent plus librement une rupture très lointaine
    (un break de 15 minutes, par exemple). Chaque coupe a une cible théorique
    locale ; un pic ne peut la remplacer que dans un couloir de 45–105 s.
    """
    wanted = track_count - 1
    if wanted <= 0:
        return [0.0]
    # Sans aucune ancre, une répartition uniforme rigide serait pire que le
    # détecteur spectral historique : les morceaux d'un DJ set peuvent avoir
    # des durées très inégales. Le maillage est une amélioration *conditionnée*
    # par au moins une empreinte reconnue.
    voice = boundary_anchors or {}
    if not presences and not voice:
        return detect_pure_transitions(novelty, track_count, duration_seconds)
    theoretical = _anchored_theoretical_boundaries(
        track_count, duration_seconds, presences, voice,
    )
    candidates = _transition_candidates(novelty, duration_seconds)
    # Une zone calme ne doit pas casser le maillage : la cible théorique reste
    # disponible comme candidat de dernier ressort, avec une saillance neutre.
    normalized_novelty = _robust_positive(novelty)
    for target in theoretical[1:-1]:
        frame = min(novelty.size - 1, max(0, int(target / HOP_SECONDS)))
        candidates.append((target, float(normalized_novelty[frame])))
    for boundary_index, anchor in voice.items():
        if 0 < boundary_index < track_count:
            candidates.append((anchor.time_seconds, 25.0))
    candidates.sort(key=lambda item: item[0])

    states: dict[tuple[int, int], tuple[float, int | None]] = {}
    maximum_global_gap = max(
        120.0,
        min(450.0, duration_seconds / max(1, track_count) * 1.8),
    )
    for boundary_index in range(1, track_count):
        target = theoretical[boundary_index]
        local_step = max(
            MIN_TRACK_GAP_SECONDS,
            min(theoretical[boundary_index] - theoretical[boundary_index - 1],
                theoretical[boundary_index + 1] - theoretical[boundary_index]),
        )
        radius = min(105.0, max(45.0, local_step * 0.45))
        # Les 45 s absolues évitent les doublons, mais ne suffisent pas pour
        # une émission de deux heures : ici un titre de 49 s était passé. On
        # impose aussi 32 % de la durée théorique locale, plafonné à 110 s
        # pour garder les vraies pistes courtes possibles.
        previous_expected = max(
            MIN_TRACK_GAP_SECONDS,
            theoretical[boundary_index] - theoretical[boundary_index - 1],
        )
        minimum_local_gap = min(110.0, max(70.0, previous_expected * 0.32))
        for candidate_index, (time, salience) in enumerate(candidates):
            fixed = voice.get(boundary_index)
            if fixed is not None and abs(time - fixed.time_seconds) > 0.01:
                continue
            if abs(time - target) > radius:
                continue
            if time < MIN_TRACK_GAP_SECONDS * boundary_index:
                continue
            if duration_seconds - time < MIN_TRACK_GAP_SECONDS * (track_count - boundary_index):
                continue
            if boundary_index == 1 and time > maximum_global_gap:
                continue
            if boundary_index == 1 and time - theoretical[0] < MIN_TRACK_GAP_SECONDS:
                continue
            distance = abs(time - target) / radius
            base = salience - distance * 0.85
            if boundary_index == 1:
                states[(boundary_index, candidate_index)] = (base, None)
                continue
            best: tuple[float, int | None] | None = None
            for previous_index, (previous_time, _) in enumerate(candidates[:candidate_index]):
                previous = states.get((boundary_index - 1, previous_index))
                gap = time - previous_time
                if previous is None or gap < minimum_local_gap or gap > maximum_global_gap:
                    continue
                score = previous[0] + base
                if best is None or score > best[0]:
                    best = (score, previous_index)
            if best is not None:
                states[(boundary_index, candidate_index)] = best
    terminal = [
        (state[0], index) for (boundary, index), state in states.items()
        if boundary == wanted
        and duration_seconds - candidates[index][0] <= maximum_global_gap
    ]
    if not terminal:
        raise AudioFallbackError("Ancres audio incompatibles avec une suite de coupes cohérente")
    _, cursor = max(terminal)
    selected: list[float] = []
    for boundary_index in range(wanted, 0, -1):
        selected.append(candidates[cursor][0])
        parent = states[(boundary_index, cursor)][1]
        if parent is None:
            break
        cursor = parent
    if len(selected) != wanted:
        raise AudioFallbackError("Optimisation audio contrainte incomplète")
    return [round(theoretical[0], 2)] + [round(time, 2) for time in reversed(selected)]


def boundaries_are_coherent(
    boundaries: list[float],
    duration_seconds: float,
    track_count: int,
    first_start_seconds: float = 0.0,
) -> bool:
    """Valide aussi les corrections appliquées après l'optimisation DP."""
    if not boundaries or boundaries[0] != first_start_seconds or len(boundaries) != track_count:
        return False
    maximum = max(120.0, min(450.0, duration_seconds / max(1, track_count) * 1.8))
    complete = [*boundaries, duration_seconds]
    return all(
        MIN_TRACK_GAP_SECONDS <= right - left <= maximum
        for left, right in zip(complete, complete[1:])
    )


def detect_pure_transitions(novelty: np.ndarray, track_count: int, duration_seconds: float) -> list[float]:
    """Choisit globalement exactement ``track_count - 1`` zones de transition.

    Le DP ne suppose pas que tous les morceaux ont la même durée. Il applique
    seulement une pénalité douce aux sections déraisonnablement courtes ou
    longues, ce qui laisse les enchaînements DJ naturels respirer.
    """
    wanted = track_count - 1
    if wanted <= 0:
        return [0.0]
    candidates = _transition_candidates(novelty, duration_seconds)
    if len(candidates) < wanted:
        raise AudioFallbackError(
            f"Transitions audio insuffisamment nettes ({len(candidates)}/{wanted})"
        )
    expected = duration_seconds / track_count
    # state[(chosen, candidate_index)] = (score, parent_index)
    states: dict[tuple[int, int], tuple[float, int | None]] = {}
    for index, (time, salience) in enumerate(candidates):
        if time < MIN_TRACK_GAP_SECONDS:
            continue
        states[(1, index)] = (salience - 0.45 * abs(math.log(max(time, 1.0) / expected)), None)
    for chosen in range(2, wanted + 1):
        for index, (time, salience) in enumerate(candidates):
            best: tuple[float, int | None] | None = None
            for previous in range(index):
                prior = states.get((chosen - 1, previous))
                if prior is None:
                    continue
                gap = time - candidates[previous][0]
                if gap < MIN_TRACK_GAP_SECONDS:
                    continue
                score = prior[0] + salience - 0.55 * abs(math.log(gap / expected))
                if best is None or score > best[0]:
                    best = (score, previous)
            if best is not None:
                states[(chosen, index)] = best
    best_last: tuple[float, int] | None = None
    for index, (time, _) in enumerate(candidates):
        state = states.get((wanted, index))
        if state is None or duration_seconds - time < MIN_TRACK_GAP_SECONDS:
            continue
        score = state[0] - 0.45 * abs(math.log(max(duration_seconds - time, 1.0) / expected))
        if best_last is None or score > best_last[0]:
            best_last = (score, index)
    if best_last is None:
        raise AudioFallbackError("Impossible de répartir les transitions audio de façon cohérente")
    selected: list[float] = []
    index: int | None = best_last[1]
    for chosen in range(wanted, 0, -1):
        if index is None:
            raise AudioFallbackError("Optimisation des transitions audio incomplète")
        selected.append(candidates[index][0])
        index = states[(chosen, index)][1]
    return [0.0] + [round(time, 2) for time in reversed(selected)]


def _interpolated_coarse_times(
    track_count: int,
    matches: dict[int, MatchCandidate],
    duration_seconds: float,
) -> list[float]:
    known = {0: 0.0, track_count: duration_seconds}
    for index, match in matches.items():
        known[index] = max(0.0, match.anchor_seconds - DEEZER_PREVIEW_OFFSET_SECONDS)
    points = sorted(known.items())
    coarse = [0.0] * track_count
    for (left_index, left_time), (right_index, right_time) in zip(points, points[1:]):
        span = max(1, right_index - left_index)
        for index in range(left_index, min(right_index, track_count)):
            fraction = (index - left_index) / span
            coarse[index] = left_time + (right_time - left_time) * fraction
    return coarse


def refine_boundaries(
    novelty: np.ndarray,
    track_count: int,
    matches: dict[int, MatchCandidate],
    duration_seconds: float,
) -> list[float]:
    coarse = _interpolated_coarse_times(track_count, matches, duration_seconds)
    boundaries = [0.0]
    search_radius = 90.0
    for index in range(1, track_count):
        center = coarse[index]
        lower = max(boundaries[-1] + MIN_TRACK_GAP_SECONDS, center - search_radius)
        upper = min(duration_seconds - 5.0, center + search_radius)
        left_frame = max(0, int(lower / HOP_SECONDS))
        right_frame = min(novelty.size, int(upper / HOP_SECONDS) + 1)
        if right_frame <= left_frame:
            boundary = max(boundaries[-1] + MIN_TRACK_GAP_SECONDS, min(center, duration_seconds - 5.0))
        else:
            segment = novelty[left_frame:right_frame]
            median = float(np.median(segment))
            deviation = float(np.median(np.abs(segment - median))) * 1.4826 + 1e-6
            frames = np.arange(left_frame, right_frame)
            distance_penalty = np.abs(frames * HOP_SECONDS - center) / search_radius * 0.75
            objective = (segment - median) / deviation - distance_penalty
            boundary = float(frames[int(np.argmax(objective))] * HOP_SECONDS)
        boundaries.append(round(boundary, 2))
    return boundaries


def analyze_known_tracklist(
    *,
    audio_url: str,
    source_url: str,
    tracks: list[dict],
    duration_seconds: float,
    source_urls: list[str] | None = None,
    on_progress=None,
    voice_detector=None,
    voice_trace: dict | None = None,
) -> list[dict]:
    if len(tracks) < 2:
        raise AudioFallbackError("La tracklist doit contenir au moins deux titres")
    progress = on_progress or (lambda stage, value: None)
    with tempfile.TemporaryDirectory(prefix="podmix-audio-fallback-") as directory:
        root = Path(directory)
        source_path, pcm_path = root / "mix.audio", root / "mix.f32"
        progress("Téléchargement audio du fallback", 84)
        _download_source(audio_url, source_url, source_path, source_urls)
        progress("Décodage audio techno/trance", 86)
        _decode_to_f32(source_path, pcm_path)
        samples = np.memmap(pcm_path, dtype="<f4", mode="r")
        boundary_candidates: list[BoundaryAnchor] = []
        interpretation_trace: list[dict] = []
        if voice_detector is not None:
            progress("Détection et interprétation des annonces DJ", 87)
            voice_events = voice_detector(source_path, pcm_path, tracks)
            boundary_candidates, interpretation_trace = interpret_voice_announcements(
                voice_events, tracks,
            )
        mix_features = extract_features(samples)
        measured_duration = samples.size / SAMPLE_RATE
        duration = measured_duration if measured_duration > 0 else duration_seconds

        progress("Recherche des empreintes publiques gratuites", 88)
        references: list[list[FeatureSequence]] = [[] for _ in tracks]
        for track_index, track in enumerate(tracks):
            for reference_index, reference_url in enumerate(_track_reference_urls(track)):
                reference_source = root / f"reference-{track_index}-{reference_index}.audio"
                reference_pcm = root / f"reference-{track_index}-{reference_index}.f32"
                try:
                    _download_reference(reference_url, reference_source)
                    _decode_to_f32(reference_source, reference_pcm)
                    reference_samples = np.memmap(reference_pcm, dtype="<f4", mode="r")
                    references[track_index].append(extract_features(reference_samples))
                except (AudioFallbackError, OSError, ValueError):
                    # Une référence absente, DRM ou mauvaise ne doit jamais
                    # empêcher le fallback spectral de produire un résultat.
                    continue

        progress("Détection des transitions techno/trance", 91)
        novelty = pure_transition_novelty(mix_features)
        audio_candidates = _transition_candidates(novelty, duration)
        progress("Ancrage des empreintes et maillage théorique local", 93)
        corrections, landmark_confidence, matched_references, presences = landmark_transition_corrections(
            mix_features,
            references,
            duration,
            audio_candidates,
            include_presences=True,
        )
        boundary_anchors, anchor_decisions = validate_boundary_anchors(
            boundary_candidates, len(tracks), duration, presences, corrections, novelty,
        )
        if voice_trace is not None:
            voice_trace.update({
                "interpretation": interpretation_trace,
                "validation": anchor_decisions,
                "boundary_anchors": {
                    str(index): {
                        "track_index": anchor.track_index,
                        "time_seconds": anchor.time_seconds,
                        "confidence": anchor.confidence,
                        "relation": anchor.relation,
                        "evidence": anchor.evidence,
                        "detection_id": anchor.detection_id,
                    }
                    for index, anchor in boundary_anchors.items()
                },
                "preview_offset_applied_to_voice": False,
            })
        progress("Optimisation audio dans les couloirs locaux", 95)
        boundaries = detect_constrained_transitions(
            novelty, len(tracks), duration, presences, boundary_anchors,
        )
        corrected_boundaries = list(boundaries)
        for boundary_index, position in corrections.items():
            if boundary_index not in boundary_anchors:
                corrected_boundaries[boundary_index] = position
        first_start = boundary_anchors.get(0).time_seconds if 0 in boundary_anchors else 0.0
        if boundaries_are_coherent(corrected_boundaries, duration, len(tracks), first_start):
            boundaries = corrected_boundaries
        else:
            # Une chaîne incohérente est rejetée en bloc : aucune correction
            # partielle ne doit déplacer les voisins d'une mauvaise ancre.
            landmark_confidence = {}

        results = []
        for index, (track, boundary) in enumerate(zip(tracks, boundaries)):
            frame = min(novelty.size - 1, max(0, int(boundary / HOP_SECONDS)))
            local = novelty[max(0, frame - int(10 / HOP_SECONDS)):min(novelty.size, frame + int(10 / HOP_SECONDS) + 1)]
            local_z = _robust_positive(local)
            landmark_level = landmark_confidence.get(index)
            confidence = (
                96 if index == 0
                else {"high": 93, "medium": 82, "low": 68}[landmark_level]
                if landmark_level
                else max(55, min(82, round(55 + float(np.max(local_z)) * 7)))
            )
            evidence = [
                "Fallback audio techno/trance · ancres d'empreintes et maillage théorique local",
                "Milieu de transition · texture spectrale + chroma + grave + énergie + rythme",
            ]
            if landmark_level:
                evidence.insert(
                    0,
                    "Empreintes audio concordantes des deux morceaux adjacents "
                    f"· confiance {landmark_level}",
                )
            elif matched_references:
                evidence.append(
                    f"{matched_references} morceau(x) reconnu(s) · couloir de recherche recalculé localement"
                )
            if index in boundary_anchors:
                anchor = boundary_anchors[index]
                evidence.insert(
                    0,
                    "Annonce DJ explicite du morceau suivant · "
                    f"confiance interne {anchor.confidence:.4f} · sans offset preview",
                )
            evidence.append("Position contrainte par l'ordre et le nombre de titres de la tracklist")
            results.append({
                "artist": str(track.get("artist") or "Artiste inconnu"),
                "title": str(track.get("title") or f"Morceau {index + 1}"),
                "providedTime": boundary,
                "confidence": confidence,
                "evidence": evidence,
            })
        progress("Fallback audio terminé", 99)
        return results
