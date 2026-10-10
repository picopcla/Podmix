#!/usr/bin/env python3
"""Crée les extraits privés de vérification de l'expérience locale FYH512."""

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
    {"id": "succes-contraint-piste05", "center": 1007.872, "duration": 14.0, "interval": "I002", "kind": "succes",
     "note": "transition contrainte T002, appariee piste 5 a +0,5265 s"},
    {"id": "ancre-piste05-pas-debut", "center": 1060.032, "duration": 14.0, "interval": "I002", "kind": "ancre_vs_debut",
     "note": "ancre locale C05 a 1060,032 s; ce point n'est jamais transforme en debut (transition retenue 1007,872 s)"},
    {"id": "ancien-point-piste14", "center": 2827.120, "duration": 14.0, "interval": "I003", "kind": "ancien_vs_nouveau",
     "note": "baseline B018, appariee piste 14 a +0,7745 s; retiree par la nouvelle methode"},
    {"id": "nouveau-point-surplus-w014", "center": 2984.624, "duration": 14.0, "interval": "I003", "kind": "echec_ancien_vs_nouveau",
     "note": "nouvelle T014 issue de W014; surplus sans reference a +/-30 s"},
    {"id": "abstention-w013-max1", "center": 1926.848, "duration": 14.0, "interval": "I002", "kind": "abstention_ambiguite",
     "note": "premier maximum concurrent de W013; aucune transition retenue"},
    {"id": "abstention-w013-max2", "center": 2005.968471, "duration": 14.0, "interval": "I002", "kind": "abstention_ambiguite",
     "note": "second maximum concurrent de W013; scores proches, abstention explicite"},
    {"id": "reference-piste03-manquee", "center": 586.3455, "duration": 14.0, "interval": "I002", "kind": "echec_manque",
     "note": "reference RSS alignee piste 3; non trouvee par les deux variantes"},
    {"id": "succes-fallback-piste08", "center": 1556.864, "duration": 14.0, "interval": "I002", "kind": "plusieurs_morceaux_meme_intervalle",
     "note": "fallback T011 apparie piste 8; avec pistes 2, 4 et 5 illustre plusieurs morceaux dans I002"},
    {"id": "presence-incoherente-i005", "center": 7181.544, "duration": 14.0, "interval": "I005", "kind": "echec_fingerprint_assiste",
     "note": "presence candidate piste 16 en I005, incoherente avec sa plage officielle; rappel que la methode est assistee"},
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
    with tempfile.TemporaryDirectory(prefix="fyh512-local-excerpts-") as directory:
        temporary = Path(directory)
        for spec in SPECS:
            start = max(0.0, spec["center"] - spec["duration"] / 2)
            destination = args.output / f"fyh512-{spec['id']}.mp3"
            subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-ss", str(start), "-t", str(spec["duration"]), "-i", str(args.media),
                            "-map", "0:a:0", "-ac", "2", "-ar", "44100", "-b:a", "96k", str(destination)], check=True)
            decoded = temporary / f"{spec['id']}.f32"
            subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                            "-i", str(destination), "-map", "0:a:0", "-ac", "1", "-ar", "8000",
                            "-f", "f32le", str(decoded)], check=True)
            actual_duration = decoded.stat().st_size / 4 / 8000
            rows.append({**spec, "file": destination.name, "absolute_start_seconds": round(start, 6),
                         "absolute_end_seconds": round(start + actual_duration, 6),
                         "event_time_in_clip_seconds": round(spec["center"] - start, 6),
                         "verified_decoded_duration_seconds": round(actual_duration, 6),
                         "size_bytes": destination.stat().st_size, "sha256": sha256(destination)})
    manifest = {"private_repository": "picopcla/pont-messages", "time_basis": "RSS decode PCM",
                "source_media_sha256": "47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69",
                "excerpts": rows}
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# Extraits privés — fingerprints locaux FYH512", "",
             "Temps absolus sur le décodage RSS. Le temps dans le clip désigne le point commenté, pas nécessairement un début de morceau.", ""]
    for row in rows:
        lines.extend([f"- [{row['file']}]({row['file']}) — **{row['kind']}**",
                      f"  - absolu `{row['absolute_start_seconds']:.6f}–{row['absolute_end_seconds']:.6f} s`; point à `{row['event_time_in_clip_seconds']:.6f} s` dans le clip; `{row['interval']}`",
                      f"  - {row['note']}",
                      f"  - `{row['size_bytes']}` octets; durée décodée `{row['verified_decoded_duration_seconds']:.6f} s`; SHA-256 `{row['sha256']}`"])
    (args.output / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"excerpts": len(rows), "output": str(args.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
