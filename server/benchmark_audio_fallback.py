#!/usr/bin/env python3
"""Mesure reproductible du détecteur de transitions audio pur.

Usage (aucun audio n'est conservé par ce script) :
  PODMIX_FFMPEG=/chemin/ffmpeg .venv/bin/python server/benchmark_audio_fallback.py \
    /chemin/vers/set.m4a 0 201 435 670
"""

from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

import numpy as np

from audio_fallback import (
    SAMPLE_RATE,
    _decode_to_f32,
    detect_pure_transitions,
    extract_features,
    pure_transition_novelty,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark de transitions Podmix")
    parser.add_argument("audio", type=Path, help="mix audio local")
    parser.add_argument("timestamps", type=float, nargs="+", help="positions publiées, première à 0")
    parser.add_argument("--tolerance", type=float, default=30.0, help="tolérance en secondes (défaut : 30)")
    args = parser.parse_args()
    if len(args.timestamps) < 2 or args.timestamps[0] != 0:
        parser.error("au moins deux timestamps, dont le premier doit être 0")
    if not args.audio.is_file():
        parser.error("audio introuvable")

    with tempfile.TemporaryDirectory(prefix="podmix-transition-benchmark-") as directory:
        pcm = Path(directory) / "mix.f32"
        _decode_to_f32(args.audio, pcm)
        samples = np.memmap(pcm, dtype="<f4", mode="r")
        detected = detect_pure_transitions(
            pure_transition_novelty(extract_features(samples)),
            len(args.timestamps),
            samples.size / SAMPLE_RATE,
        )
    errors = np.abs(np.asarray(detected) - np.asarray(args.timestamps))
    transition_errors = errors[1:]
    print("Détectés :", ", ".join(f"{item:.1f}" for item in detected))
    print(f"Erreur médiane : {np.median(transition_errors):.1f} s")
    print(f"Erreur p95 : {np.percentile(transition_errors, 95):.1f} s")
    print(f"Dans ±{args.tolerance:.0f} s : {np.count_nonzero(transition_errors <= args.tolerance)}/{transition_errors.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
