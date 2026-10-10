#!/usr/bin/env python3
"""Run one CPU-only VAD method sequentially with boundary-level checkpoints."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import math
import os
from pathlib import Path
import resource
import subprocess
import time

import imageio_ffmpeg
import numpy as np

ROOT = Path(__file__).resolve().parent
CONFIG = ROOT / "episodes.json"
OUTPUT = ROOT / "output"


def boundaries(config: dict, split: str):
    before = config["protocol"]["window_before_seconds"]
    after = config["protocol"]["window_after_seconds"]
    for episode in config["episodes"]:
        media = Path(episode["local_media"])
        if not media.is_file():
            raise FileNotFoundError(media)
        for track in episode["tracks"]:
            if track.get("split") not in {"tuning", "evaluation"}:
                continue
            if split != "all" and track["split"] != split:
                continue
            original = float(track["time"])
            yield {
                "id": f'{episode["id"]}-t{track["number"]:02d}',
                "episode_id": episode["id"],
                "episode_title": episode["title"],
                "source_url": episode["source_url"],
                "tracklist_url": episode["tracklist_url"],
                "media": str(media),
                "media_size_bytes": media.stat().st_size,
                "track_number": track["number"],
                "track_title": track["title"],
                "split": track["split"],
                "original_time": original,
                "window_start": max(0.0, original - before),
                "window_end": original + after,
            }


def decode_float32(media: str, start: float, end: float) -> np.ndarray:
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-ss", f"{start:.3f}", "-to", f"{end:.3f}", "-i", media,
        "-vn", "-ac", "1", "-ar", "16000", "-f", "f32le", "pipe:1",
    ]
    proc = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return np.frombuffer(proc.stdout, dtype="<f4").copy()


def complete_segments(speech: list[dict], duration: float) -> list[dict]:
    result = []
    cursor = 0.0
    for seg in speech:
        start, end = max(0.0, seg["start"]), min(duration, seg["end"])
        if start > cursor:
            result.append({"label": "non_speech", "start": cursor, "end": start})
        result.append({"label": "speech", "start": start, "end": end})
        cursor = max(cursor, end)
    if cursor < duration:
        result.append({"label": "non_speech", "start": cursor, "end": duration})
    return result


class SileroCPU:
    def __init__(self, params: dict):
        import onnxruntime as ort

        dist = importlib.metadata.distribution("silero-vad")
        self.model_path = Path(dist.locate_file("silero_vad/data/silero_vad.onnx"))
        options = ort.SessionOptions()
        options.inter_op_num_threads = 1
        options.intra_op_num_threads = 2
        self.session = ort.InferenceSession(
            str(self.model_path), providers=["CPUExecutionProvider"], sess_options=options
        )
        self.params = params

    def __call__(self, audio: np.ndarray) -> tuple[list[dict], list[float]]:
        chunk_size = self.params["chunk_samples"]
        state = np.zeros((2, 1, 128), dtype=np.float32)
        context = np.zeros((1, 64), dtype=np.float32)
        probabilities = []
        for pos in range(0, len(audio), chunk_size):
            chunk = audio[pos : pos + chunk_size]
            if len(chunk) < chunk_size:
                chunk = np.pad(chunk, (0, chunk_size - len(chunk)))
            joined = np.concatenate((context, chunk.reshape(1, -1)), axis=1)
            output, state = self.session.run(
                None,
                {"input": joined, "state": state, "sr": np.array(16000, dtype=np.int64)},
            )
            probabilities.append(float(output.item()))
            context = joined[:, -64:]
        speech = timestamps_from_probs(probabilities, len(audio), self.params)
        return complete_segments(speech, len(audio) / 16000), probabilities


def timestamps_from_probs(probs: list[float], audio_samples: int, p: dict) -> list[dict]:
    sr, window = p["sample_rate"], p["chunk_samples"]
    min_speech = sr * p["min_speech_ms"] / 1000
    min_silence = sr * p["min_silence_ms"] / 1000
    pad = sr * p["speech_pad_ms"] / 1000
    triggered = False
    temp_end = 0
    current = {}
    speeches = []
    for index, probability in enumerate(probs):
        sample = index * window
        if probability >= p["threshold"] and not triggered:
            triggered = True
            current = {"start": sample}
            continue
        if probability < p["negative_threshold"] and triggered:
            if not temp_end:
                temp_end = sample
            if sample - temp_end < min_silence:
                continue
            current["end"] = temp_end
            if current["end"] - current["start"] > min_speech:
                speeches.append(current)
            triggered, temp_end, current = False, 0, {}
        elif probability >= p["threshold"] and temp_end:
            temp_end = 0
    if current and audio_samples - current["start"] > min_speech:
        current["end"] = audio_samples
        speeches.append(current)
    for idx, speech in enumerate(speeches):
        speech["start"] = max(0, speech["start"] - pad)
        speech["end"] = min(audio_samples, speech["end"] + pad)
        if idx and speech["start"] < speeches[idx - 1]["end"]:
            middle = (speech["start"] + speeches[idx - 1]["end"]) / 2
            speech["start"] = middle
            speeches[idx - 1]["end"] = middle
    return [{"start": round(s["start"] / sr, 3), "end": round(s["end"] / sr, 3)} for s in speeches]


class InaCPU:
    def __init__(self, params: dict):
        from inaSpeechSegmenter import Segmenter

        self.ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        self.model = Segmenter(
            vad_engine=params["vad_engine"],
            detect_gender=params["detect_gender"],
            ffmpeg=self.ffmpeg,
            batch_size=params["batch_size"],
            energy_ratio=params["energy_ratio"],
        )

    def __call__(self, item: dict) -> list[dict]:
        segments = self.model(item["media"], item["window_start"], item["window_end"])
        return [
            {
                "label": label,
                "start": round(start - item["window_start"], 3),
                "end": round(end - item["window_start"], 3),
            }
            for label, start, end in segments
        ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--method", required=True, choices=("silero", "ina"))
    parser.add_argument("--split", default="all", choices=("tuning", "evaluation", "all"))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    config = json.loads(CONFIG.read_text())
    items = list(boundaries(config, args.split))
    raw_dir = OUTPUT / "raw" / args.method
    raw_dir.mkdir(parents=True, exist_ok=True)
    started_wall = time.perf_counter()
    started_cpu = time.process_time()
    if args.method == "silero":
        runner = SileroCPU(config["protocol"]["silero"])
    else:
        runner = InaCPU(config["protocol"]["ina"])
    completed = skipped = 0
    for index, item in enumerate(items, 1):
        target = raw_dir / f'{item["id"]}.json'
        if args.resume and target.exists():
            skipped += 1
            continue
        boundary_wall = time.perf_counter()
        boundary_cpu = time.process_time()
        if args.method == "silero":
            audio = decode_float32(item["media"], item["window_start"], item["window_end"])
            segments, probabilities = runner(audio)
            extra = {
                "probability_summary": {
                    "frames": len(probabilities),
                    "min": round(min(probabilities), 6),
                    "max": round(max(probabilities), 6),
                    "mean": round(float(np.mean(probabilities)), 6),
                },
                "decoded_seconds": round(len(audio) / 16000, 3),
            }
        else:
            segments = runner(item)
            extra = {}
        record = {
            **{k: v for k, v in item.items() if k != "media"},
            "method": args.method,
            "window": [item["window_start"], item["window_end"]],
            "segments": segments,
            "analysis_wall_seconds": round(time.perf_counter() - boundary_wall, 4),
            "analysis_cpu_seconds": round(time.process_time() - boundary_cpu, 4),
            **extra,
        }
        target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
        completed += 1
        print(f'[{index}/{len(items)}] {args.method} {item["id"]}', flush=True)
    usage = resource.getrusage(resource.RUSAGE_SELF)
    metrics = {
        "method": args.method,
        "split": args.split,
        "boundaries_selected": len(items),
        "boundaries_completed": completed,
        "boundaries_resumed": skipped,
        "wall_seconds": round(time.perf_counter() - started_wall, 4),
        "cpu_seconds": round(time.process_time() - started_cpu, 4),
        "peak_rss_kib": usage.ru_maxrss,
        "threads_limited": {"OMP_NUM_THREADS": os.environ.get("OMP_NUM_THREADS")},
    }
    (OUTPUT / f"metrics-{args.method}-{args.split}.json").write_text(
        json.dumps(metrics, indent=2) + "\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
