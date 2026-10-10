#!/usr/bin/env python3
"""Vérifie l'offset RSS/YouTube par corrélation normalisée mathématiquement bornée."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
RATE = 2000


def decode(source: Path, destination: Path) -> np.memmap:
    ffmpeg = os.environ.get("PODMIX_FFMPEG", "").strip() or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("FFmpeg absent")
    subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                    "-map", "0:a:0", "-ac", "1", "-ar", str(RATE), "-f", "f32le", str(destination)], check=True)
    return np.memmap(destination, dtype="<f4", mode="r")


def normalized_offsets(fixed: np.ndarray, search: np.ndarray) -> np.ndarray:
    n = fixed.size
    fft_size = 1 << (fixed.size + search.size - 2).bit_length()
    convolution = np.fft.irfft(np.fft.rfft(search, fft_size) * np.fft.rfft(fixed[::-1], fft_size), fft_size)
    dots = convolution[n - 1:search.size]
    prefix = np.concatenate([[0.0], np.cumsum(search, dtype=np.float64)])
    prefix2 = np.concatenate([[0.0], np.cumsum(search * search, dtype=np.float64)])
    sums = prefix[n:] - prefix[:-n]
    sums2 = prefix2[n:] - prefix2[:-n]
    fixed_sum = float(np.sum(fixed, dtype=np.float64))
    fixed_centered = float(np.sum(fixed * fixed, dtype=np.float64) - fixed_sum * fixed_sum / n)
    numerator = dots - fixed_sum * sums / n
    denominator = np.sqrt(np.maximum(fixed_centered * (sums2 - sums * sums / n), 1e-20))
    return np.clip(numerator / denominator, -1.0, 1.0)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rss", required=True, type=Path)
    parser.add_argument("--youtube", required=True, type=Path)
    args = parser.parse_args()
    probes, window, maximum_offset = [120, 600, 2500, 3400, 3600, 5000, 7100], 30.0, 3.0
    with tempfile.TemporaryDirectory(prefix="fyh512-alignment-") as directory:
        root = Path(directory)
        rss, youtube = decode(args.rss, root / "rss.f32"), decode(args.youtube, root / "youtube.f32")
        rows = []
        for probe in probes:
            fixed = np.asarray(rss[int(probe * RATE):int((probe + window) * RATE)], dtype=np.float64)
            base = probe - maximum_offset
            search = np.asarray(youtube[int(base * RATE):int((probe + window) * RATE) + 1], dtype=np.float64)
            coefficients = normalized_offsets(fixed, search)
            index = int(np.argmax(coefficients))
            youtube_start = base + index / RATE
            offset = probe - youtube_start
            rows.append({
                "rss_probe_seconds": probe, "window_seconds": window,
                "rss_equals_youtube_plus_offset_seconds": round(offset, 4),
                "normalized_correlation_coefficient": round(float(coefficients[index]), 6),
                "coefficient_bounds": "[-1,1]", "sample_rate_hz": RATE,
                "reported_precision_warning": "pas de precision au-dela de 0,01 s interpretable (encodage et timestamps publies)",
            })
        result = {
            "method": "produit scalaire de Pearson normalise par fenetre glissante, signaux mono 2 kHz",
            "offset_convention": "temps_RSS = temps_YouTube + offset",
            "search_range_seconds": [0, maximum_offset], "window_seconds": window,
            "rss_decoded_duration_seconds": round(rss.size / RATE, 6),
            "youtube_decoded_duration_seconds": round(youtube.size / RATE, 6),
            "rss_reported_duration_seconds": 7210,
            "rss_previous_container_measurement_seconds": 7210.7253,
            "rss_decode_duration_divergence_warning": "Le decodage PCM inclut 7216,927 s; les timestamps/conteneur MP3 divergent. Les offsets sont donc conditionnels aux echantillons decodes.",
            "observations": rows,
        }
    path = RESULTS / "fyh512-alignment-normalized.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
