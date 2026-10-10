#!/usr/bin/env python3
"""Cree les extraits prives de verification du complement voix FYH512."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent
SELECTIONS = [
    {
        "file": "fyh512-d002-rapprochement.mp3", "start": 105.0, "duration": 70.0,
        "points": {"fin_voix_d002": 120.0, "ancien_spectral_S002": 162.69},
        "result": "rapprochement utile; S002 remplace par d002",
    },
    {
        "file": "fyh512-d005-rapprochement.mp3", "start": 2348.0, "duration": 72.0,
        "points": {"fin_voix_d005": 2365.68, "ancien_spectral_S011": 2409.73},
        "result": "rapprochement utile; S011 remplace par d005",
    },
    {
        "file": "fyh512-d008-rapprochement.mp3", "start": 5338.0, "duration": 55.0,
        "points": {"fin_voix_d008": 5349.38, "ancien_spectral_S023": 5375.74},
        "result": "rapprochement utile; S023 remplace par d008",
    },
    {
        "file": "fyh512-d010-rapprochement.mp3", "start": 6945.0, "duration": 78.0,
        "points": {"fin_voix_d010": 6975.72, "ancien_spectral_S032": 7013.25},
        "result": "rapprochement utile; S032 remplace par d010",
    },
    {
        "file": "fyh512-d006-sous-seuil-rate.mp3", "start": 3372.0, "duration": 38.0,
        "points": {"fin_voix_d006": 3391.04},
        "result": "limite/raté; confiance 0,8435 sous le seuil 0,95, donc aucune modification",
    },
    {
        "file": "fyh512-d001-presence-seule.mp3", "start": 0.0, "duration": 20.0,
        "points": {"fin_voix_d001": 4.58},
        "result": "limite; présence vocale seule et confiance 0,9086 sous le seuil, donc aucune borne",
    },
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = json.loads((ROOT / "fyh512_source.json").read_text(encoding="utf-8"))
    media = args.media.resolve()
    if media.stat().st_size != source["audio_size_bytes"] or sha256(media) != source["audio_sha256"]:
        raise ValueError("Le media ne correspond pas a la source FYH512 gelee")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    ffmpeg = os.environ.get("PODMIX_FFMPEG", "").strip() or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("FFmpeg absent; renseigner PODMIX_FFMPEG")
    rows = []
    for selection in SELECTIONS:
        target = output / selection["file"]
        subprocess.run([
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-ss", f"{selection['start']:.3f}", "-i", str(media),
            "-t", f"{selection['duration']:.3f}", "-map", "0:a:0",
            "-ac", "1", "-ar", "44100", "-b:a", "96k", str(target),
        ], check=True)
        points = [{"label": label, "absolute_seconds": absolute,
                   "relative_seconds": round(absolute - selection["start"], 3)}
                  for label, absolute in selection["points"].items()]
        rows.append({
            "file": selection["file"], "size_bytes": target.stat().st_size,
            "sha256": sha256(target), "clip_start_seconds": selection["start"],
            "clip_end_seconds": selection["start"] + selection["duration"],
            "examined_points": points, "result": selection["result"],
            "method": "FFmpeg; mono 44,1 kHz; MP3 96 kbit/s; extraction de la source FYH512 gelee",
        })
    manifest = {"source_audio_sha256": source["audio_sha256"], "private_audio": True, "clips": rows}
    (output / "index.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# Extraits FYH512 — initial + voix", "", "Dépôt privé. Les temps relatifs indiquent précisément où écouter.", ""]
    for row in rows:
        point_text = "; ".join(f"{p['label']} à {p['relative_seconds']:.3f} s" for p in row["examined_points"])
        lines.extend([f"- [{row['file']}]({row['file']}) — {point_text} — {row['result']} — SHA256 `{row['sha256']}`", ""])
    (output / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
