#!/usr/bin/env python3
"""Produit les courts MP3 privés et leur manifeste vérifié."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

SPECS = [
    {"id": "voice-d002-next-title", "center": 120.0, "duration": 14.0, "interval": "I001/I002", "relative": 120.0, "kind": "voix_et_asr_succes", "result": "Keep On Running cite; borne voix appariee a -1,338 s"},
    {"id": "voice-d002-multiple-future-titles", "center": 95.0, "duration": 20.0, "interval": "I001", "relative": 95.0, "kind": "asr_ambiguite", "result": "plusieurs titres futurs cites; abstention d'affectation"},
    {"id": "landmark-l002-save-me", "center": 337.92, "duration": 12.0, "interval": "I002", "relative": 217.92, "kind": "fingerprint_assiste_succes", "result": "presence candidate Save Me; methode non independante"},
    {"id": "transition-t007-low-error", "center": 1007.872, "duration": 12.0, "interval": "I002", "relative": 887.872, "kind": "transition_interne_appariee", "result": "ecart +0,534 s a la reference alignee"},
    {"id": "transition-t003-surplus", "center": 399.936, "duration": 12.0, "interval": "I002", "relative": 279.936, "kind": "transition_interne_echec", "result": "surnumeraire sans reference a +/-30 s"},
    {"id": "reference-track03-missed", "center": 586.338, "duration": 12.0, "interval": "I002", "relative": 466.338, "kind": "abstention_transition", "result": "frontiere officielle piste 3 manquee"},
    {"id": "voice-d005-next-title", "center": 2365.68, "duration": 14.0, "interval": "I002/I003", "relative": 2245.68, "kind": "voix_et_asr_succes", "result": "More & More cite; affectation ASR gelee en abstention"},
    {"id": "landmark-l025-assisted-limit", "center": 5694.464, "duration": 12.0, "interval": "I004", "relative": 345.084, "kind": "fingerprint_assiste_limite", "result": "presence In And Out Of Love; ancre d'apercu non assimilable au debut"},
    {"id": "voice-d010-classic", "center": 6975.72, "duration": 18.0, "interval": "I004/I005", "relative": 1626.34, "kind": "voix_asr_tokenisation_echec", "result": "Sweet Sorrow audible; rapprochement automatique ASR non retenu"}
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
    ffmpeg = os.environ.get("PODMIX_FFMPEG", "").strip() or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("FFmpeg absent")
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory(prefix="fyh512-excerpt-check-") as directory:
        temp = Path(directory)
        for spec in SPECS:
            start = max(0.0, spec["center"] - spec["duration"] / 2)
            destination = args.output / f'fyh512-{spec["id"]}.mp3'
            subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-ss", str(start), "-t", str(spec["duration"]), "-i", str(args.media),
                            "-map", "0:a:0", "-ac", "2", "-ar", "44100", "-b:a", "96k", str(destination)], check=True)
            decoded = temp / f'{spec["id"]}.f32'
            subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-i", str(destination), "-map", "0:a:0", "-ac", "1", "-ar", "8000",
                            "-f", "f32le", str(decoded)], check=True)
            actual_duration = decoded.stat().st_size / 4 / 8000
            rows.append({
                **spec, "file": destination.name, "absolute_start_seconds": round(start, 3),
                "absolute_end_seconds": round(start + actual_duration, 3),
                "verified_decoded_duration_seconds": round(actual_duration, 6),
                "size_bytes": destination.stat().st_size, "sha256": sha256(destination),
                "source_media_sha256": "47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69",
            })
    manifest = {"private_repository": "picopcla/pont-messages", "episode": "find-your-harmony-512",
                "time_basis": "RSS decode PCM", "excerpts": rows}
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# Extraits privés FYH512 — pipeline séquentiel", "", "Temps absolus sur le décodage RSS; temps relatifs à l'intervalle indiqué. Les liens pointent vers ce dépôt privé.", ""]
    for row in rows:
        lines.extend([
            f"- [{row['file']}]({row['file']}) — `{row['kind']}`",
            f"  - absolu {row['absolute_start_seconds']:.3f}–{row['absolute_end_seconds']:.3f} s; centre {row['center']:.3f} s; relatif {row['relative']:.3f} s; {row['interval']}",
            f"  - {row['result']}",
            f"  - {row['size_bytes']} octets; durée décodée {row['verified_decoded_duration_seconds']:.6f} s; SHA-256 `{row['sha256']}`",
        ])
    (args.output / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"excerpts": len(rows), "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
