#!/usr/bin/env python3
"""Create private FYH511 listening excerpts for representative abstentions."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import imageio_ffmpeg


ROOT = Path(__file__).resolve().parent
SUGGESTIONS = ROOT / "results" / "fyh511-suggestions.json"


def timestamp(seconds: float) -> str:
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{int(hours):02d}:{int(minutes):02d}:{secs:06.3f}"


def representative(items: list[dict], count: int, duration: float) -> list[dict]:
    reliable = [item for item in items if item["passes_confidence_threshold"]]
    if len(reliable) <= count:
        selected = list(reliable)
        under_threshold = sorted(
            (item for item in items if not item["passes_confidence_threshold"]),
            key=lambda item: (-float(item["confidence"]), float(item["algorithm_time_seconds"])),
        )
        selected.extend(under_threshold[:count - len(selected)])
        return sorted(selected, key=lambda item: float(item["algorithm_time_seconds"]))
    selected = []
    for bin_index in range(count):
        low = duration * bin_index / count
        high = duration * (bin_index + 1) / count
        candidates = [
            item for item in reliable
            if low <= float(item["algorithm_time_seconds"]) < high
        ]
        if candidates:
            selected.append(max(candidates, key=lambda item: (float(item["confidence"]), -float(item["algorithm_time_seconds"]))))
    if len(selected) < count:
        used = {item["detection_id"] for item in selected}
        remaining = sorted(
            (item for item in reliable if item["detection_id"] not in used),
            key=lambda item: (-float(item["confidence"]), float(item["algorithm_time_seconds"])),
        )
        selected.extend(remaining[:count - len(selected)])
    return sorted(selected, key=lambda item: float(item["algorithm_time_seconds"]))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--duration", required=True, type=float)
    parser.add_argument("--count", type=int, default=8, choices=range(5, 9))
    args = parser.parse_args()
    if not args.media.is_file():
        raise FileNotFoundError(args.media)
    items = json.loads(SUGGESTIONS.read_text(encoding="utf-8"))
    chosen = representative(items, args.count, args.duration)
    if len(chosen) < 5:
        raise RuntimeError(f"Pas assez de suggestions fiables pour 5 extraits: {len(chosen)}")
    args.output.mkdir(parents=True, exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    index = [
        "# FYH 511 — index des extraits privés",
        "",
        "Aucune frontière n'a été ajustée : la base RSS contient 33 titres mais aucun temps.",
        "Chaque extrait couvre environ 30 s avant et 30 s après une fin de voix détectée fiable.",
        "Sept points passent le seuil gelé; un huitième cas sous seuil illustre une abstention supplémentaire.",
        "Ces points sont des suggestions d'écoute avec abstention, pas des chapitres proposés.",
        "",
        "| Fichier | Fin de voix à écouter | Fenêtre | Confiance interne | Décision |",
        "|---|---:|---:|---:|---|",
    ]
    manifest = []
    for item in chosen:
        center = float(item["algorithm_time_seconds"])
        start = max(0.0, center - 30.0)
        length = min(60.0, args.duration - start)
        name = f"{item['detection_id']}-abstention.mp3"
        target = args.output / name
        subprocess.run([
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-ss", f"{start:.3f}", "-t", f"{length:.3f}", "-i", str(args.media),
            "-vn", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", "-y", str(target),
        ], check=True)
        row = {
            "file": name,
            "size_bytes": target.stat().st_size,
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "detection_id": item["detection_id"],
            "voice_end_seconds": center,
            "voice_end_timestamp": timestamp(center),
            "clip_start_seconds": round(start, 3),
            "clip_end_seconds": round(start + length, 3),
            "confidence": item["confidence"],
            "decision": item["combined_reason"],
        }
        manifest.append(row)
        index.append(
            f"| `{name}` | {row['voice_end_timestamp']} (≈30 s dans l'extrait) | "
            f"{timestamp(start)}–{timestamp(start + length)} | {float(item['confidence']):.4f} | {item['combined_reason']} |"
        )
    (args.output / "index.md").write_text("\n".join(index) + "\n", encoding="utf-8")
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"excerpts": len(manifest), "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
