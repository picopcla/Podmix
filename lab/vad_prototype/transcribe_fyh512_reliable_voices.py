#!/usr/bin/env python3
"""Transcrit localement les quatre segments voix fiables, sans référence temporelle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--model", default="small")
    args = parser.parse_args()
    source = json.loads((ROOT / "fyh512_source.json").read_text(encoding="utf-8"))
    voices = json.loads((RESULTS / "fyh512-voice-detections-frozen.json").read_text(encoding="utf-8"))
    media = args.media.resolve()
    if media.stat().st_size != source["audio_size_bytes"] or sha256(media) != source["audio_sha256"]:
        raise ValueError("Le media ne correspond pas a FYH512")

    from faster_whisper import WhisperModel

    model = WhisperModel(args.model, device="cpu", compute_type="int8", cpu_threads=2)
    ffmpeg = os.environ.get("PODMIX_FFMPEG", "").strip() or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("FFmpeg absent")
    rows = []
    with tempfile.TemporaryDirectory(prefix="fyh512-asr-") as directory:
        root = Path(directory)
        for voice in voices:
            if not voice["passes_reliable_threshold"]:
                continue
            start = float(voice["ina_speech_start_seconds"])
            end = float(voice["ina_speech_end_seconds"])
            sample = root / f'{voice["detection_id"]}.wav'
            subprocess.run([
                ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", str(start), "-to", str(end), "-i", str(media),
                "-ac", "1", "-ar", "16000", str(sample),
            ], check=True)
            segments, info = model.transcribe(
                str(sample), language="en", beam_size=5, vad_filter=False,
                condition_on_previous_text=False,
            )
            items = [{
                "relative_start_seconds": round(segment.start, 3),
                "relative_end_seconds": round(segment.end, 3),
                "absolute_start_seconds": round(start + segment.start, 3),
                "absolute_end_seconds": round(start + segment.end, 3),
                "text": segment.text.strip(),
                "average_log_probability": round(segment.avg_logprob, 6),
                "no_speech_probability": round(segment.no_speech_prob, 6),
            } for segment in segments]
            rows.append({
                "detection_id": voice["detection_id"],
                "voice_start_seconds": start,
                "voice_end_seconds": end,
                "voice_internal_confidence": voice["confidence"],
                "engine": "faster-whisper-small-local-cpu-int8",
                "language": info.language,
                "language_probability": round(info.language_probability, 6),
                "segments": items,
                "transcript": " ".join(item["text"] for item in items).strip(),
                "interpretation": "annonce potentiellement precedente, suivante ou multiple; aucune orientation injectee",
            })
    RESULTS.mkdir(exist_ok=True)
    output = RESULTS / "fyh512-reliable-voices-asr-frozen.json"
    output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"segments": len(rows), "output": str(output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
