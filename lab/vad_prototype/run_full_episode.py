#!/usr/bin/env python3
"""Segment complete episodes with INA or Silero on CPU, one method at a time."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import resource
import time

import numpy as np

from run_vad import InaCPU, SileroCPU, decode_float32

ROOT = Path(__file__).resolve().parent
EPISODES = ROOT / "episodes.json"
PROTOCOL = ROOT / "full_episode_protocol.json"
OUTPUT = ROOT / "output" / "full_episode"


def episode_by_id(config: dict, episode_id: str) -> dict:
    for episode in config["episodes"]:
        if episode["id"] == episode_id:
            return episode
    raise ValueError(f"Episode inconnu: {episode_id}")


def windows(duration: float, size: float, overlap: float):
    if not 0 <= overlap < size:
        raise ValueError("Le recouvrement doit etre inferieur a la fenetre")
    step = size - overlap
    start = 0.0
    index = 0
    while start < duration:
        end = min(duration, start + size)
        core_start = 0.0 if index == 0 else start + overlap / 2
        core_end = duration if end >= duration else end - overlap / 2
        yield index, start, end, core_start, core_end
        if end >= duration:
            break
        start += step
        index += 1


def clip_to_core(segments: list[dict], core_start: float, core_end: float) -> list[dict]:
    clipped = []
    for segment in segments:
        start = max(core_start, float(segment["start"]))
        end = min(core_end, float(segment["end"]))
        if end > start:
            clipped.append({"label": segment["label"], "start": start, "end": end})
    return clipped


def merge_adjacent(segments: list[dict], tolerance: float = 0.002) -> list[dict]:
    merged: list[dict] = []
    for segment in sorted(segments, key=lambda item: (item["start"], item["end"])):
        clean = {
            "label": segment["label"],
            "start": round(float(segment["start"]), 3),
            "end": round(float(segment["end"]), 3),
        }
        if clean["end"] <= clean["start"]:
            continue
        if merged and clean["label"] == merged[-1]["label"] and clean["start"] <= merged[-1]["end"] + tolerance:
            merged[-1]["end"] = round(max(merged[-1]["end"], clean["end"]), 3)
        else:
            merged.append(clean)
    return merged


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--method", required=True, choices=("ina", "silero"))
    parser.add_argument("--episode", required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    episodes = json.loads(EPISODES.read_text())
    protocol = json.loads(PROTOCOL.read_text())
    episode = episode_by_id(episodes, args.episode)
    if args.episode not in protocol["episodes"]:
        raise ValueError(f"Episode hors protocole gele: {args.episode}")
    media = Path(episode["local_media"])
    if not media.is_file():
        raise FileNotFoundError(media)
    duration = float(episode["duration_seconds"])
    window_params = protocol["windowing"]
    work = list(windows(duration, window_params["window_seconds"], window_params["overlap_seconds"]))
    raw_dir = OUTPUT / "windows" / args.method / args.episode
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
        target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
        completed += 1
        print(f"[{index + 1}/{len(work)}] {args.method} {args.episode}", flush=True)

    records = [json.loads(path.read_text()) for path in sorted(raw_dir.glob("window-*.json"))]
    if len(records) != len(work):
        raise RuntimeError(f"Checkpoints incomplets: {len(records)}/{len(work)}")
    segments = merge_adjacent([segment for record in records for segment in record["segments"]])
    usage = resource.getrusage(resource.RUSAGE_SELF)
    output = {
        "episode_id": args.episode,
        "episode_title": episode["title"],
        "method": args.method,
        "media_size_bytes": media.stat().st_size,
        "media_sha256_expected": episode.get("media_sha256"),
        "duration_seconds": duration,
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
    target = OUTPUT / f"{args.episode}-{args.method}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(output["metrics"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
