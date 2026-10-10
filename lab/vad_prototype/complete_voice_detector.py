#!/usr/bin/env python3
"""Run a real full-episode local VAD + ASR pass for the existing engine hook."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from analyze_full_episode import confidence, nearest_silero


LAB = Path(__file__).resolve().parent
PROTOCOL = LAB / "fyh512_complete_voice_protocol.json"
FULL_PROTOCOL = LAB / "full_episode_protocol.json"
EPISODE_ID = "find-your-harmony-512-complete-voice"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def detect_candidates(ina: dict, silero: dict, params: dict) -> list[dict]:
    silero_speech = [item for item in silero["segments"] if item["label"] == "speech"]
    candidates = []
    for index, segment in enumerate(ina["segments"][:-1]):
        following = ina["segments"][index + 1]
        speech_duration = segment["end"] - segment["start"]
        music_duration = following["end"] - following["start"]
        if segment["label"] not in params["ina_speech_labels"]:
            continue
        if following["label"] not in params["ina_music_labels"]:
            continue
        if speech_duration < params["min_ina_speech_seconds"] or music_duration < params["min_return_to_music_seconds"]:
            continue
        match = nearest_silero(segment, silero_speech, params)
        if match is None:
            continue
        end_gap, negative_shared, silero_segment = match
        score, components = confidence(segment, following, silero_segment, params)
        candidates.append({
            "algorithm_time_seconds": round(segment["end"], 3),
            "confidence": score,
            "ina_speech_start_seconds": round(segment["start"], 3),
            "ina_speech_end_seconds": round(segment["end"], 3),
            "ina_speech_duration_seconds": round(speech_duration, 3),
            "ina_music_end_seconds": round(following["end"], 3),
            "ina_music_duration_seconds": round(music_duration, 3),
            "silero_speech_start_seconds": round(silero_segment["start"], 3),
            "silero_speech_end_seconds": round(silero_segment["end"], 3),
            "model_end_gap_seconds": round(end_gap, 3),
            "speech_overlap_seconds": round(-negative_shared, 3),
            "confidence_components": components,
        })
    deduplicated = []
    for candidate in sorted(candidates, key=lambda item: item["algorithm_time_seconds"]):
        if deduplicated and candidate["algorithm_time_seconds"] - deduplicated[-1]["algorithm_time_seconds"] <= params["deduplication_seconds"]:
            if candidate["confidence"] > deduplicated[-1]["confidence"]:
                deduplicated[-1] = candidate
        else:
            deduplicated.append(candidate)
    for index, item in enumerate(deduplicated, 1):
        item["detection_id"] = f"{EPISODE_ID}-d{index:03d}"
    return deduplicated


def transcribe_worker(media: Path, candidates_path: Path, output: Path, model_name: str) -> None:
    from faster_whisper import WhisperModel

    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))["transcription"]
    candidates = json.loads(candidates_path.read_text(encoding="utf-8"))
    model = WhisperModel(model_name, device="cpu", compute_type="int8", cpu_threads=protocol["cpu_threads"])
    rows = []
    for candidate in candidates:
        start = candidate["ina_speech_start_seconds"]
        end = candidate["ina_speech_end_seconds"]
        segments, info = model.transcribe(
            str(media), language="en", beam_size=protocol["beam_size"], vad_filter=False,
            condition_on_previous_text=False, clip_timestamps=f"{start},{end}",
        )
        items = [{
            "absolute_start_seconds": round(segment.start, 3),
            "absolute_end_seconds": round(segment.end, 3),
            "text": segment.text.strip(),
            "average_log_probability": round(segment.avg_logprob, 6),
            "no_speech_probability": round(segment.no_speech_prob, 6),
        } for segment in segments]
        rows.append({
            **candidate,
            "voice_start_seconds": start,
            "voice_end_seconds": end,
            "voice_internal_confidence": candidate["confidence"],
            "engine": f"faster-whisper-{model_name}-local-cpu-int8",
            "language": info.language,
            "language_probability": round(info.language_probability, 6),
            "segments": items,
            "transcript": " ".join(item["text"] for item in items).strip(),
        })
    write_json(output, rows)


def run_complete_pass(media: Path, work: Path, *, vad_python: str, asr_python: str) -> tuple[list[dict], dict]:
    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
    full = json.loads(FULL_PROTOCOL.read_text(encoding="utf-8"))
    if not protocol["frozen_before_reference_evaluation"]:
        raise ValueError("Le protocole voix n'est pas gele")
    if sha256(media) != protocol["audio_sha256"]:
        raise ValueError("Le media FYH512 ne correspond pas au SHA-256 gele")
    work.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    commands = []
    environment = {
        **os.environ,
        "CUDA_VISIBLE_DEVICES": "-1",
        "TF_CPP_MIN_LOG_LEVEL": "2",
        "OMP_NUM_THREADS": "2",
        "TF_NUM_INTRAOP_THREADS": "2",
        "TF_NUM_INTEROP_THREADS": "1",
    }
    for method in protocol["vad_methods_in_execution_order"]:
        command = [
            vad_python, str(LAB / "run_external_full_episode.py"), "--method", method,
            "--episode", EPISODE_ID, "--title", "Find Your Harmony Episode #512",
            "--media", str(media), "--duration", str(protocol["duration_seconds"]),
            "--output-root", str(work / "vad"),
        ]
        subprocess.run(command, check=True, env=environment)
        commands.append({"stage": f"vad_{method}", "argv_without_media": [Path(command[0]).name, Path(command[1]).name, *command[2:10], *command[12:]]})
    ina_path = work / "vad" / f"{EPISODE_ID}-ina.json"
    silero_path = work / "vad" / f"{EPISODE_ID}-silero.json"
    ina = json.loads(ina_path.read_text(encoding="utf-8"))
    silero = json.loads(silero_path.read_text(encoding="utf-8"))
    candidates = detect_candidates(ina, silero, full["detector"])
    candidates_path = work / "candidates.json"
    transcripts_path = work / "transcripts.json"
    write_json(candidates_path, candidates)
    asr_command = [asr_python, str(Path(__file__).resolve()), "--asr-worker", "--media", str(media),
                   "--candidates", str(candidates_path), "--output", str(transcripts_path),
                   "--model", protocol["transcription"]["model"]]
    subprocess.run(asr_command, check=True, env=environment)
    commands.append({"stage": "asr", "argv_without_media": [Path(asr_command[0]).name, Path(asr_command[1]).name, *asr_command[2:4], *asr_command[6:]]})
    events = json.loads(transcripts_path.read_text(encoding="utf-8"))
    coverage = {
        "audio_sha256": protocol["audio_sha256"],
        "duration_seconds": protocol["duration_seconds"],
        "coverage_start_seconds": 0.0,
        "coverage_end_seconds": protocol["duration_seconds"],
        "coverage_complete": True,
        "vad_execution_order": protocol["vad_methods_in_execution_order"],
        "windowing": full["windowing"],
        "vad_parameters": {name: full[name] for name in protocol["vad_methods_in_execution_order"]},
        "detector_parameters": full["detector"],
        "transcription_parameters": protocol["transcription"],
        "versions": {
            "inaSpeechSegmenter": "0.8.0",
            "silero-vad": "6.2.3",
            "onnxruntime": "1.31.0",
            "faster-whisper": "1.2.1",
        },
        "windows": {"ina": ina["windows"], "silero": silero["windows"]},
        "candidates": len(candidates),
        "transcribed_candidates": len(events),
        "commands": commands,
        "wall_seconds": round(time.perf_counter() - started, 3),
        "artifacts_sha256": {
            "ina": sha256(ina_path), "silero": sha256(silero_path),
            "candidates": sha256(candidates_path), "transcripts": sha256(transcripts_path),
        },
    }
    write_json(work / "coverage.json", coverage)
    return events, coverage


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asr-worker", action="store_true")
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--candidates", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--model", default="small")
    args = parser.parse_args()
    if not args.asr_worker or args.candidates is None or args.output is None:
        parser.error("Ce point d'entree public est reserve au worker ASR")
    transcribe_worker(args.media.resolve(), args.candidates.resolve(), args.output.resolve(), args.model)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
