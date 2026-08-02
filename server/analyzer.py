"""Analyse audio portable sans dépendance externe.

Le moteur traite les WAV PCM 16 bits par fenêtres, calcule l'énergie et le taux
de passages par zéro, puis recherche les maxima locaux de nouveauté.
"""

from __future__ import annotations

import math
import shutil
import struct
import subprocess
import tempfile
import wave
from pathlib import Path

try:
    from .beatgrid import detect_beat_grid, snap_to_grid
except ImportError:
    from beatgrid import detect_beat_grid, snap_to_grid


def _median(values: list[float]) -> float:
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def analyze_wav(path: Path, on_progress=lambda _progress, _stage: None) -> tuple[float, list[dict]]:
    with wave.open(str(path), "rb") as audio:
        channels = audio.getnchannels()
        sample_width = audio.getsampwidth()
        sample_rate = audio.getframerate()
        frame_count = audio.getnframes()
        if sample_width != 2:
            raise ValueError("Seuls les WAV PCM 16 bits sont pris en charge par le moteur portable")
        if channels < 1 or channels > 2:
            raise ValueError("Le fichier WAV doit être mono ou stéréo")

        duration = frame_count / sample_rate
        window_frames = sample_rate
        windows = max(1, math.ceil(frame_count / window_frames))
        features: list[tuple[float, float]] = []
        on_progress(12, "Lecture du signal WAV")

        for index in range(windows):
            raw = audio.readframes(window_frames)
            if not raw:
                break
            samples = struct.unpack(f"<{len(raw) // 2}h", raw)
            mono = samples[::channels]
            if not mono:
                continue
            rms = math.sqrt(sum(sample * sample for sample in mono) / len(mono)) / 32768
            crossings = sum((a < 0) != (b < 0) for a, b in zip(mono, mono[1:]))
            zcr = crossings / max(1, len(mono) - 1)
            features.append((rms, zcr))
            if index % 10 == 0:
                on_progress(12 + int(38 * index / windows), "Extraction des caractéristiques")

    if len(features) < 4:
        return duration, []

    novelty = [0.0]
    for previous, current in zip(features, features[1:]):
        energy_delta = abs(math.log10(current[0] + 1e-5) - math.log10(previous[0] + 1e-5))
        texture_delta = abs(current[1] - previous[1]) * 8
        novelty.append(energy_delta + texture_delta)

    baseline = _median(novelty)
    deviations = [abs(value - baseline) for value in novelty]
    threshold = baseline + max(0.035, 2.8 * _median(deviations))
    min_gap = max(3, min(45, int(duration / 8)))
    candidates: list[tuple[int, float]] = []
    for index in range(2, len(novelty) - 2):
        local = novelty[index - 2:index + 3]
        if novelty[index] == max(local) and novelty[index] >= threshold:
            candidates.append((index, novelty[index]))

    selected: list[tuple[int, float]] = []
    for second, score in sorted(candidates, key=lambda item: item[1], reverse=True):
        if second < 3 or duration - second < 3:
            continue
        if all(abs(second - existing[0]) >= min_gap for existing in selected):
            selected.append((second, score))
    selected.sort()
    on_progress(82, "Consolidation des transitions")

    strongest = max((score for _, score in selected), default=1)
    tracks = [{
        "id": 1000 + index,
        "time": float(second),
        "artist": "Détection audio",
        "title": f"Transition {index}",
        "confidence": max(45, min(96, round(45 + 51 * score / strongest))),
        "source": "detected",
    } for index, (second, score) in enumerate(selected, start=1)]
    return duration, tracks


def advanced_engine_available() -> bool:
    try:
        import librosa  # noqa: F401
        import numpy  # noqa: F401
        import soundfile  # noqa: F401
        return True
    except ImportError:
        return False


def analyze_advanced(path: Path, on_progress=lambda _progress, _stage: None) -> tuple[float, list[dict]]:
    import librosa
    import numpy as np

    on_progress(8, "Décodage et normalisation")
    try:
        signal, sample_rate = librosa.load(path, sr=11025, mono=True)
    except Exception as original_error:
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise ValueError("Décodage impossible et FFmpeg n’est pas installé") from original_error
        with tempfile.NamedTemporaryFile(suffix=".wav") as decoded:
            process = subprocess.run(
                [ffmpeg, "-v", "error", "-y", "-i", str(path), "-ac", "1", "-ar", "11025", "-c:a", "pcm_s16le", decoded.name],
                capture_output=True,
                text=True,
                timeout=180,
                check=False,
            )
            if process.returncode != 0:
                raise ValueError(f"FFmpeg ne peut pas décoder ce fichier : {process.stderr[-300:]}") from original_error
            signal, sample_rate = librosa.load(decoded.name, sr=11025, mono=True)
    duration = librosa.get_duration(y=signal, sr=sample_rate)
    if duration < 4:
        return duration, []
    on_progress(28, "Analyse spectrale")

    hop = 512
    onset = librosa.onset.onset_strength(y=signal, sr=sample_rate, hop_length=hop)
    rms = librosa.feature.rms(y=signal, hop_length=hop)[0]
    centroid = librosa.feature.spectral_centroid(y=signal, sr=sample_rate, hop_length=hop)[0]
    chroma = librosa.feature.chroma_stft(y=signal, sr=sample_rate, hop_length=hop)
    length = min(len(onset), len(rms), len(centroid), chroma.shape[1])
    onset, rms, centroid, chroma = onset[:length], rms[:length], centroid[:length], chroma[:, :length]

    def robust(values):
        median = np.median(values)
        spread = np.median(np.abs(values - median)) + 1e-8
        return np.maximum(0, (values - median) / spread)

    energy_change = np.abs(np.diff(np.log10(rms + 1e-6), prepend=np.log10(rms[0] + 1e-6)))
    centroid_change = np.abs(np.diff(np.log10(centroid + 1), prepend=np.log10(centroid[0] + 1)))
    chroma_change = np.linalg.norm(np.diff(chroma, axis=1, prepend=chroma[:, :1]), axis=0)
    novelty = .42 * robust(onset) + .22 * robust(energy_change) + .16 * robust(centroid_change) + .20 * robust(chroma_change)
    novelty = np.convolve(novelty, np.ones(5) / 5, mode="same")
    on_progress(62, "Recherche des changements structurels")

    frames_per_second = sample_rate / hop
    minimum_gap = max(3, min(45, int(duration / 8)))
    peaks = librosa.util.peak_pick(
        novelty,
        pre_max=max(2, int(frames_per_second * 2)),
        post_max=max(2, int(frames_per_second * 2)),
        pre_avg=max(4, int(frames_per_second * 6)),
        post_avg=max(4, int(frames_per_second * 6)),
        delta=max(.35, float(np.percentile(novelty, 75) - np.median(novelty))),
        wait=max(1, int(frames_per_second * minimum_gap)),
    )
    times = librosa.frames_to_time(peaks, sr=sample_rate, hop_length=hop)
    usable = [(float(time_value), float(novelty[peak])) for peak, time_value in zip(peaks, times) if 3 <= time_value <= duration - 3]
    if len(usable) > 80:
        usable = sorted(usable, key=lambda item: item[1], reverse=True)[:80]
        usable.sort()
    strongest = max((score for _, score in usable), default=1)
    usable = [(timestamp, score) for timestamp, score in usable if score >= strongest * .25]
    on_progress(86, "Classement des transitions")
    try:
        grid = detect_beat_grid(path, signal=signal, sample_rate=sample_rate, hop=hop)
    except Exception:
        grid = {"engine": "indisponible", "beats": [], "downbeats": []}
    tracks = []
    seen_times: set[float] = set()
    for timestamp, score in usable:
        snapped, evidence = snap_to_grid(timestamp, grid)
        if snapped in seen_times:
            continue
        seen_times.add(snapped)
        track = {
            "id": 2000 + len(tracks) + 1,
            "time": snapped,
            "artist": "Détection spectrale",
            "title": f"Transition {len(tracks) + 1}",
            "confidence": max(45, min(98, round(48 + 50 * score / strongest))),
            "source": "detected",
        }
        if evidence:
            track["evidence"] = [evidence]
        tracks.append(track)
    return duration, tracks


def analyze_audio(path: Path, on_progress=lambda _progress, _stage: None) -> tuple[float, list[dict]]:
    if advanced_engine_available():
        return analyze_advanced(path, on_progress)
    if path.suffix.lower() == ".wav":
        return analyze_wav(path, on_progress)
    raise ValueError("Format avancé indisponible. Installez server/requirements.txt")
