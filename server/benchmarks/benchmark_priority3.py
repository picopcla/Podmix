#!/usr/bin/env python3
"""Benchmark reproductible du recalage et de la validation acoustique."""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import time
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from analyzer import analyze_advanced  # noqa: E402
from beatgrid import detect_beat_grid, snap_to_grid  # noqa: E402
from refiner import subsequence_dtw  # noqa: E402


def load_manifest() -> dict:
    return json.loads(
        Path(__file__).with_name("reference_corpus.json").read_text(
            encoding="utf-8",
        )
    )


def synthesize(manifest: dict) -> tuple[np.ndarray, int]:
    sample_rate = int(manifest["sampleRate"])
    duration = float(manifest["duration"])
    signal = np.zeros(round(duration * sample_rate), dtype=np.float32)
    for index, segment in enumerate(manifest["segments"]):
        start = float(segment["start"])
        end = (
            float(manifest["segments"][index + 1]["start"])
            if index + 1 < len(manifest["segments"])
            else duration
        )
        first, last = round(start * sample_rate), round(end * sample_rate)
        time_axis = np.arange(last - first, dtype=np.float32) / sample_rate
        frequency = float(segment["frequency"])
        # Une dérive mélodique propre au segment rend chaque position
        # identifiable par chroma, contrairement à un accord stationnaire.
        semitone_curve = (
            (index + 1) * 0.35 * time_axis
            + 1.8 * np.sin(2 * np.pi * time_axis / (5.0 + index))
        )
        frequency_curve = frequency * np.power(2.0, semitone_curve / 12.0)
        phase = 2 * np.pi * np.cumsum(frequency_curve) / sample_rate
        tone = (
            0.32 * np.sin(phase)
            + 0.18 * np.sin(phase * 1.5)
            + 0.10 * np.sin(phase * 2.0)
        )
        signal[first:last] += tone.astype(np.float32)

    # Clics à 120 BPM, accentués toutes les quatre pulsations.
    click_length = round(sample_rate * 0.045)
    click_axis = np.arange(click_length, dtype=np.float32) / sample_rate
    click = np.exp(-click_axis * 75) * np.sin(2 * np.pi * 1200 * click_axis)
    for beat_index, beat_time in enumerate(np.arange(0, duration, 0.5)):
        start = round(float(beat_time) * sample_rate)
        end = min(len(signal), start + click_length)
        gain = 0.7 if beat_index % 4 == 0 else 0.28
        signal[start:end] += gain * click[:end - start]
    peak = float(np.max(np.abs(signal))) or 1.0
    return signal / peak * 0.9, sample_rate


def mean_nearest_error(found: list[float], expected: list[float]) -> float | None:
    if not found or not expected:
        return None
    return round(
        sum(min(abs(value - candidate) for candidate in found) for value in expected)
        / len(expected),
        4,
    )


def run() -> dict:
    manifest = load_manifest()
    signal, sample_rate = synthesize(manifest)
    transitions = [float(value) for value in manifest["transitions"]]
    with tempfile.TemporaryDirectory() as directory:
        audio_path = Path(directory, "reference.wav")
        sf.write(audio_path, signal, sample_rate, subtype="PCM_16")

        started = time.perf_counter()
        _, detected = analyze_advanced(audio_path)
        analysis_seconds = round(time.perf_counter() - started, 3)
        detected_times = [float(item["time"]) for item in detected]

        grid = detect_beat_grid(
            audio_path,
            signal=signal,
            sample_rate=sample_rate,
        )
        jittered = [value - 0.63 for value in transitions]
        snapped = [snap_to_grid(value, grid)[0] for value in jittered]

        hop = 512
        mix_chroma = librosa.feature.chroma_cqt(
            y=signal,
            sr=sample_rate,
            hop_length=hop,
        )
        preview_offset = float(manifest["previewOffset"])
        preview_duration = float(manifest["previewDuration"])
        preview_matches = []
        for segment in manifest["segments"]:
            expected = float(segment["start"]) + preview_offset
            first = round(expected * sample_rate)
            last = round((expected + preview_duration) * sample_rate)
            preview = librosa.feature.chroma_cqt(
                y=signal[first:last],
                sr=sample_rate,
                hop_length=hop,
            )
            match = subsequence_dtw(preview, mix_chroma)
            found = match[0] * hop / sample_rate if match else None
            preview_matches.append({
                "segment": segment["id"],
                "expected": expected,
                "found": round(found, 3) if found is not None else None,
                "error": round(abs(found - expected), 3) if found is not None else None,
                "cost": round(match[2], 5) if match else None,
            })

    preview_errors = [
        float(item["error"])
        for item in preview_matches
        if item["error"] is not None
    ]
    result = {
        "corpus": manifest["name"],
        "analysis": {
            "seconds": analysis_seconds,
            "detectedTransitions": detected_times,
            "meanNearestError": mean_nearest_error(detected_times, transitions),
        },
        "beatGrid": {
            "engine": grid["engine"],
            "beatCount": len(grid["beats"]),
            "downbeatCount": len(grid["downbeats"]),
            "jittered": jittered,
            "snapped": snapped,
            "meanError": mean_nearest_error(snapped, transitions),
        },
        "previewDtw": {
            "matches": preview_matches,
            "meanError": (
                round(sum(preview_errors) / len(preview_errors), 4)
                if preview_errors else None
            ),
        },
        "externalFingerprinting": {
            "olaf": {
                "installed": bool(shutil.which("olaf")),
                "decision": "optional-only-agpl",
            },
            "panako": {
                "installed": bool(shutil.which("panako")),
                "decision": "optional-only-agpl",
            },
        },
    }
    result["passed"] = all((
        result["analysis"]["meanNearestError"] is not None
        and result["analysis"]["meanNearestError"] <= 0.5,
        result["beatGrid"]["meanError"] is not None
        and result["beatGrid"]["meanError"] <= 0.5,
        result["previewDtw"]["meanError"] is not None
        and result["previewDtw"]["meanError"] <= 0.5,
    ))
    return result


if __name__ == "__main__":
    report = run()
    print(json.dumps(report, indent=2, ensure_ascii=False))
    raise SystemExit(0 if report["passed"] else 1)
