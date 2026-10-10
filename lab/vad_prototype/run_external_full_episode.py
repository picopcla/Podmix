#!/usr/bin/env python3
"""Run the frozen full-episode detector on one external, private media file.

This is a lab-only adapter around the existing INA/Silero implementations.  It
does not change the frozen model parameters and never reads application data.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import resource
import time

import numpy as np

from run_full_episode import clip_to_core, merge_adjacent, windows
from run_vad import InaCPU, SileroCPU, decode_float32


ROOT = Path(__file__).resolve().parent
PROTOCOL = ROOT / "full_episode_protocol.json"
OUTPUT = ROOT / "output" / "full_episode"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--method", required=True, choices=("ina", "silero"))
    parser.add_argument("--episode", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--duration", required=True, type=float)
    parser.add_argument("--output-root", type=Path, default=OUTPUT)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    media = args.media.resolve()
    if not media.is_file():
        raise FileNotFoundError(media)
    if args.duration <= 0:
        raise ValueError("La duree doit etre strictement positive")

    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
    if not protocol.get("frozen_before_reference_comparison"):
        raise ValueError("Le protocole global n'est pas gele")
    window_params = protocol["windowing"]
    work = list(windows(args.duration, window_params["window_seconds"], window_params["overlap_seconds"]))
    output_root = args.output_root.resolve()
    raw_dir = output_root / "windows" / args.method / args.episode
    raw_dir.mkdir(parents=True, exist_ok=True)
    runner = SileroCPU(protocol["silero"]) if args.method == "silero" else InaCPU(protocol["ina"])

    started_wall = time.perf_counter()
    started_cpu = time.process_time()
    completed = skipped = 0
    for index, start, end, core_start, core_end in work:
        target = raw_dir / f"window-{index:03d}.json"
        if args.resume and target.exists():
            skipped += 1
            print(f"[{index + 1}/{len(work)}] {args.method} {args.episode} repris", flush=True)
            continue
        item_wall = time.perf_counter()
        item_cpu = time.process_time()
        if args.method == "silero":
            audio = decode_float32(str(media), start, end)
            local_segments, probabilities = runner(audio)
            absolute = [
                {"label": item["label"], "start": start + item["start"], "end": start + item["end"]}
                for item in local_segments
            ]
            extra = {
                "probability_summary": {
                    "frames": len(probabilities),
                    "min": round(min(probabilities), 6),
                    "max": round(max(probabilities), 6),
                    "mean": round(float(np.mean(probabilities)), 6),
                },
                "decoded_seconds": round(len(audio) / protocol["silero"]["sample_rate"], 3),
            }
        else:
            raw = runner({"media": str(media), "window_start": start, "window_end": end})
            absolute = [
                {"label": item["label"], "start": start + item["start"], "end": start + item["end"]}
                for item in raw
            ]
            extra = {}
        record = {
            "episode_id": args.episode,
            "method": args.method,
            "window_index": index,
            "window": [round(start, 3), round(end, 3)],
            "owned_core": [round(core_start, 3), round(core_end, 3)],
            "segments": clip_to_core(absolute, core_start, core_end),
            "analysis_wall_seconds": round(time.perf_counter() - item_wall, 4),
            "analysis_cpu_seconds": round(time.process_time() - item_cpu, 4),
            **extra,
        }
        target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        completed += 1
        print(f"[{index + 1}/{len(work)}] {args.method} {args.episode}", flush=True)

    records = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(raw_dir.glob("window-*.json"))]
    if len(records) != len(work):
        raise RuntimeError(f"Checkpoints incomplets: {len(records)}/{len(work)}")
    segments = merge_adjacent([segment for record in records for segment in record["segments"]])
    usage = resource.getrusage(resource.RUSAGE_SELF)
    output = {
        "episode_id": args.episode,
        "episode_title": args.title,
        "method": args.method,
        "media_size_bytes": media.stat().st_size,
        "duration_seconds": args.duration,
        "protocol": str(PROTOCOL.relative_to(ROOT)),
        "windows": len(work),
        "segments": segments,
        "metrics": {
            "windows_completed": completed,
            "windows_resumed": skipped,
            "wall_seconds": round(time.perf_counter() - started_wall, 4),
            "cpu_seconds": round(time.process_time() - started_cpu, 4),
            "peak_rss_kib": usage.ru_maxrss,
            "threads_limited": {
                "OMP_NUM_THREADS": os.environ.get("OMP_NUM_THREADS"),
                "TF_NUM_INTRAOP_THREADS": os.environ.get("TF_NUM_INTRAOP_THREADS"),
                "TF_NUM_INTEROP_THREADS": os.environ.get("TF_NUM_INTEROP_THREADS"),
            },
        },
    }
    target = output_root / f"{args.episode}-{args.method}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output["metrics"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
