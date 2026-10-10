#!/usr/bin/env python3
"""Create private listening excerpts for the existing-engine baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


SELECTIONS = [
    {
        "file": "fyh512-existing-good-track08.mp3", "start": 1525.0, "duration": 65.0,
        "purpose": "bon_calage_fallback_spectral",
        "examined": {"algorithm_rss_seconds": 1556.93, "published_rss_seconds": 1555.3455},
    },
    {
        "file": "fyh512-existing-miss-track03-no-reference.mp3", "start": 515.0, "duration": 90.0,
        "purpose": "rate_et_absence_de_reference_publique_retournee",
        "examined": {"algorithm_rss_seconds": 525.44, "published_rss_seconds": 586.3455},
    },
    {
        "file": "fyh512-existing-adjacent-correction-track06.mp3", "start": 1080.0, "duration": 90.0,
        "purpose": "role_des_presences_adjacentes_et_correction_acceptee",
        "examined": {"uncorrected_rss_seconds": 1128.45, "corrected_rss_seconds": 1114.49,
                     "published_rss_seconds": 1143.3455},
    },
    {
        "file": "fyh512-existing-miss-track32-reference-failed.mp3", "start": 6945.0, "duration": 90.0,
        "purpose": "rate_et_echec_telechargement_reference",
        "examined": {"published_rss_seconds": 6975.7265, "algorithm_rss_seconds": 7013.25},
    },
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    for selection in SELECTIONS:
        target = args.output / selection["file"]
        subprocess.run([
            str(args.ffmpeg), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-ss", str(selection["start"]), "-i", str(args.media), "-t", str(selection["duration"]),
            "-map", "0:a:0", "-ac", "1", "-ar", "44100", "-b:a", "96k", str(target),
        ], check=True)
        rows.append({
            **selection, "end": selection["start"] + selection["duration"],
            "size_bytes": target.stat().st_size, "sha256": sha256(target),
            "examined_relative_seconds": {
                key: round(value - selection["start"], 4)
                for key, value in selection["examined"].items()
            },
        })
    (args.output / "manifest.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
