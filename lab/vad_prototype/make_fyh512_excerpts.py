#!/usr/bin/env python3
"""Create selected private FYH512 listening excerpts; never commit the media."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import imageio_ffmpeg


SELECTIONS = [
    ("voice-d002-reliable-close", 120.0, "voice_reliable_near_reference"),
    ("voice-d005-reliable-close", 2365.68, "voice_reliable_near_reference"),
    ("voice-d008-reliable-close", 5349.38, "voice_reliable_near_reference"),
    ("voice-d010-reliable-close", 6975.72, "voice_reliable_near_reference"),
    ("voice-d006-below-threshold-close", 3391.04, "voice_below_threshold_near_reference"),
    ("transition-t08-low-error", 1556.93, "spectral_transition_low_error"),
    ("transition-t23-large-error-algorithm", 5375.74, "spectral_transition_large_error_algorithm_point"),
    ("transition-t23-large-error-reference", 5060.719, "spectral_transition_large_error_reference_point"),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--duration", required=True, type=float)
    args = parser.parse_args()
    media = args.media.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    rows = []
    for label, center, purpose in SELECTIONS:
        start = max(0.0, min(center - 30.0, args.duration - 60.0))
        target = output / f"find-your-harmony-512-{label}.mp3"
        subprocess.run([
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-ss", f"{start:.3f}", "-i", str(media), "-t", "60",
            "-map", "0:a:0", "-ac", "1", "-ar", "44100", "-b:a", "96k", str(target),
        ], check=True)
        rows.append({
            "file": target.name,
            "size_bytes": target.stat().st_size,
            "sha256": sha256(target),
            "purpose": purpose,
            "absolute_point_seconds": center,
            "clip_start_seconds": round(start, 3),
            "clip_end_seconds": round(start + 60.0, 3),
            "listen_relative_seconds": round(center - start, 3),
            "requested_duration_seconds": 60.0,
        })
    (output / "manifest.generated.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(rows, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
