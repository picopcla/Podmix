"""Détection et recalage sur une grille musicale.

BeatNet est utilisé lorsqu'il est installé explicitement sur un worker
compatible. Le worker standard reste léger et utilise Librosa comme repli.
"""

from __future__ import annotations

import os
from pathlib import Path


def beatnet_available() -> bool:
    try:
        from BeatNet.BeatNet import BeatNet  # noqa: F401

        return True
    except (ImportError, OSError):
        return False


def _beatnet_grid(audio_path: Path) -> dict:
    import numpy as np
    from BeatNet.BeatNet import BeatNet

    estimator = BeatNet(
        1,
        mode="offline",
        inference_model="DBN",
        plot=[],
        thread=False,
    )
    output = np.asarray(estimator.process(str(audio_path)))
    if output.ndim != 2 or output.shape[1] < 2:
        raise RuntimeError("BeatNet n’a pas renvoyé de grille exploitable")
    beats = [float(row[0]) for row in output if float(row[0]) >= 0]
    downbeats = [
        float(row[0])
        for row in output
        if float(row[0]) >= 0 and round(float(row[1])) == 1
    ]
    return {"engine": "beatnet", "beats": beats, "downbeats": downbeats}


def _librosa_grid(signal, sample_rate: int, hop: int = 512) -> dict:
    import librosa
    import numpy as np

    onset = librosa.onset.onset_strength(y=signal, sr=sample_rate, hop_length=hop)
    _, frames = librosa.beat.beat_track(
        onset_envelope=onset,
        sr=sample_rate,
        hop_length=hop,
        units="frames",
    )
    beat_frames = np.asarray(frames, dtype=int)
    beats = [
        float(value)
        for value in librosa.frames_to_time(beat_frames, sr=sample_rate, hop_length=hop)
    ]
    if not beats:
        return {"engine": "librosa", "beats": [], "downbeats": []}

    # Librosa ne classe pas les temps forts. On choisit la phase de mesure
    # (4/4) qui maximise l'énergie d'attaque, sans prétendre remplacer BeatNet.
    best_phase = 0
    best_energy = -1.0
    for phase in range(min(4, len(beat_frames))):
        indices = beat_frames[phase::4]
        energy = float(onset[indices].sum()) if len(indices) else 0.0
        if energy > best_energy:
            best_phase, best_energy = phase, energy
    downbeats = beats[best_phase::4]
    return {"engine": "librosa", "beats": beats, "downbeats": downbeats}


def detect_beat_grid(
    audio_path: Path,
    *,
    signal=None,
    sample_rate: int = 11025,
    hop: int = 512,
) -> dict:
    """Retourne une grille avec ``engine``, ``beats`` et ``downbeats``."""
    engine = os.environ.get("PODMIX_BEAT_ENGINE", "auto").strip().casefold()
    if engine not in {"auto", "beatnet", "librosa"}:
        raise ValueError("PODMIX_BEAT_ENGINE doit valoir auto, beatnet ou librosa")
    if engine in {"auto", "beatnet"} and beatnet_available():
        try:
            return _beatnet_grid(audio_path)
        except Exception:
            if engine == "beatnet":
                raise
    if signal is None:
        import librosa

        signal, _ = librosa.load(audio_path, sr=sample_rate, mono=True)
    return _librosa_grid(signal, sample_rate, hop)


def snap_to_grid(
    timestamp: float,
    grid: dict,
    *,
    downbeat_tolerance: float = 2.5,
    beat_tolerance: float = 0.8,
) -> tuple[float, str | None]:
    """Recale un timestamp sur un temps fort, puis sur un beat proche."""
    value = max(0.0, float(timestamp))
    for name, tolerance, label in (
        ("downbeats", downbeat_tolerance, "temps fort"),
        ("beats", beat_tolerance, "beat"),
    ):
        points = [float(point) for point in grid.get(name) or []]
        if not points:
            continue
        nearest = min(points, key=lambda point: abs(point - value))
        if abs(nearest - value) <= tolerance:
            engine = str(grid.get("engine") or "grille")
            return round(nearest, 2), (
                f"Recalé sur un {label} {engine} : {value:.2f} → {nearest:.2f} s"
            )
    return round(value, 2), None

