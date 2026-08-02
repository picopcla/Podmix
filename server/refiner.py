"""Raffinage acoustique de timestamps par chroma et DTW subsequence."""

from __future__ import annotations

import io
import os
from pathlib import Path
from urllib.request import Request, urlopen

try:
    from .beatgrid import detect_beat_grid, snap_to_grid
    from .catalog import search_deezer
except ImportError:
    from beatgrid import detect_beat_grid, snap_to_grid
    from catalog import search_deezer


def score_acoustic_validation(
    catalog_score: int,
    dtw_cost: float,
    *,
    maximum_cost: float = 0.52,
) -> dict:
    """Combine cohérence des métadonnées et présence acoustique du titre."""
    metadata_score = max(0, min(100, int(catalog_score)))
    acoustic_score = max(0, min(100, round((1 - float(dtw_cost)) * 100)))
    accepted = metadata_score >= 70 and dtw_cost <= maximum_cost
    return {
        "accepted": accepted,
        "catalogScore": metadata_score,
        "acousticScore": acoustic_score,
        "dtwCost": round(float(dtw_cost), 4),
        "confidence": round(metadata_score * 0.35 + acoustic_score * 0.65),
    }


def subsequence_dtw(query, reference, downsample: int = 8) -> tuple[int, int, float] | None:
    """Retourne (début, fin, coût normalisé) en frames originales."""
    import numpy as np

    def shrink(values):
        bins, frames = values.shape
        usable = frames // downsample * downsample
        if usable < downsample:
            return values.T
        return values[:, :usable].reshape(bins, -1, downsample).mean(axis=2).T

    q, r = shrink(query), shrink(reference)
    m, n = len(q), len(r)
    if m < 5 or n < m:
        return None
    q /= np.linalg.norm(q, axis=1, keepdims=True) + 1e-8
    r /= np.linalg.norm(r, axis=1, keepdims=True) + 1e-8
    distances = np.clip(1 - r @ q.T, 0, 1)
    infinity = np.finfo(np.float32).max / 4
    costs = np.full((n, m), infinity, dtype=np.float32)
    back = np.zeros((n, m), dtype=np.uint8)
    costs[:, 0] = distances[:, 0]
    for column in range(1, m):
        for row in range(n):
            if row == 0:
                costs[row, column] = distances[row, column] + costs[row, column - 1]
                back[row, column] = 2
                continue
            options = (costs[row - 1, column - 1], costs[row - 1, column], costs[row, column - 1])
            direction = int(np.argmin(options))
            costs[row, column] = distances[row, column] + options[direction]
            back[row, column] = direction
    end = int(np.argmin(costs[:, -1]))
    normalized_cost = float(costs[end, -1] / m)
    row, column = end, m - 1
    while column > 0 and row > 0:
        direction = back[row, column]
        if direction == 0:
            row -= 1; column -= 1
        elif direction == 1:
            row -= 1
        else:
            column -= 1
    return row * downsample, end * downsample, normalized_cost


def _preview_chroma(url: str, sample_rate: int, hop: int):
    import librosa

    request = Request(url, headers={"User-Agent": "PodmixStudio/0.2"})
    with urlopen(request, timeout=15) as response:
        content = response.read(8 * 1024 * 1024)
    signal, _ = librosa.load(io.BytesIO(content), sr=sample_rate, mono=True, duration=30)
    if len(signal) < sample_rate * 5:
        return None, 0.0
    return librosa.feature.chroma_cqt(y=signal, sr=sample_rate, hop_length=hop), len(signal) / sample_rate


def refine_tracks(
    audio_path: Path,
    tracks: list[dict],
    duration: float | None,
    on_progress=lambda _progress, _stage: None,
) -> list[dict]:
    import librosa

    if not tracks:
        return []
    sample_rate, hop = 11025, 512
    on_progress(5, "Décodage pour le raffinage chroma")
    signal, _ = librosa.load(audio_path, sr=sample_rate, mono=True)
    mix_chroma = librosa.feature.chroma_cqt(y=signal, sr=sample_rate, hop_length=hop)
    try:
        beat_grid = detect_beat_grid(
            audio_path,
            signal=signal,
            sample_rate=sample_rate,
            hop=hop,
        )
    except Exception:
        beat_grid = {"engine": "indisponible", "beats": [], "downbeats": []}
    hop_seconds = hop / sample_rate
    audio_duration = duration or len(signal) / sample_rate
    transitions = sorted(float(track["time"]) for track in tracks)
    maximum_cost = float(os.environ.get("PODMIX_PREVIEW_DTW_MAX_COST", "0.52"))
    refined = []
    last_time = -1.0
    for index, source in enumerate(tracks):
        track = dict(source)
        track["evidence"] = list(source.get("evidence") or [])
        on_progress(10 + round(index * 85 / max(1, len(tracks))), f"Chroma {index + 1}/{len(tracks)}")
        match = search_deezer(str(track.get("artist") or ""), str(track.get("title") or ""))
        preview_url = match.get("previewUrl") if match else None
        if not preview_url:
            track["acousticValidation"] = {
                "accepted": False,
                "available": False,
                "provider": "deezer",
            }
            track["evidence"].append("Validation acoustique : aperçu Deezer indisponible")
            refined.append(track)
            continue
        try:
            preview, preview_duration = _preview_chroma(preview_url, sample_rate, hop)
        except Exception:
            preview = None
            preview_duration = 0
        if preview is None:
            track["acousticValidation"] = {
                "accepted": False,
                "available": False,
                "provider": "deezer",
            }
            track["evidence"].append("Validation acoustique : aperçu illisible")
            refined.append(track)
            continue
        estimate = float(track.get("time") or (audio_duration * index / max(1, len(tracks))))
        window_start = max(last_time + 5 if last_time >= 0 else 0, estimate - 180)
        window_end = min(audio_duration, estimate + 180)
        start_frame = max(0, round(window_start / hop_seconds))
        end_frame = min(mix_chroma.shape[1], round(window_end / hop_seconds))
        aligned = subsequence_dtw(preview, mix_chroma[:, start_frame:end_frame])
        if not aligned:
            track["acousticValidation"] = {
                "accepted": False,
                "available": True,
                "provider": "deezer",
                "catalogScore": int(match.get("score") or 0),
            }
            track["evidence"].append("Validation acoustique : comparaison impossible")
            refined.append(track)
            continue
        validation = score_acoustic_validation(
            int(match.get("score") or 0),
            aligned[2],
            maximum_cost=maximum_cost,
        )
        track["acousticValidation"] = {
            **validation,
            "available": True,
            "provider": "deezer",
            "catalogId": match.get("id"),
        }
        if not validation["accepted"]:
            track["evidence"].append(
                "Validation acoustique rejetée : "
                f"catalogue {validation['catalogScore']} %, "
                f"audio {validation['acousticScore']} %"
            )
            refined.append(track)
            continue
        cue_time = window_start + aligned[0] * hop_seconds
        corrected = max(window_start, cue_time - min(15.0, preview_duration * 0.35))
        nearby = [value for value in transitions if abs(value - corrected) <= 20]
        final_time = min(nearby, key=lambda value: abs(value - corrected)) if nearby else corrected
        final_time, beat_evidence = snap_to_grid(final_time, beat_grid)
        if last_time >= 0 and final_time < last_time - 30:
            track["evidence"].append("Validation acoustique rejetée : ordre incohérent")
            refined.append(track)
            continue
        track["time"] = round(final_time, 2)
        track["confidence"] = max(
            int(track.get("confidence") or 0),
            min(98, int(validation["confidence"])),
        )
        track["source"] = "detected"
        track["evidence"].append(
            "Validation acoustique Deezer/chroma : "
            f"{validation['acousticScore']} %, cue {cue_time:.1f} s, "
            f"timestamp {final_time:.1f} s"
        )
        if beat_evidence:
            track["evidence"].append(beat_evidence)
        last_time = final_time
        refined.append(track)
    refined.sort(key=lambda item: float(item["time"]))
    on_progress(98, "Validation de la cohérence temporelle")
    return refined
