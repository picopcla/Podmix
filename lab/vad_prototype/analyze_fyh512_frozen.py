#!/usr/bin/env python3
"""Freeze FYH512 audio outputs before loading published reference times."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys
import tempfile

import numpy as np

from analyze_full_episode import detect


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
sys.path.insert(0, str(REPO / "server"))

from audio_fallback import (  # noqa: E402
    HOP_SECONDS,
    _decode_to_f32,
    _robust_positive,
    _transition_candidates,
    detect_pure_transitions,
    extract_features,
    pure_transition_novelty,
)

SOURCE = ROOT / "fyh512_source.json"
FYH512_PROTOCOL = ROOT / "fyh512_protocol.json"
FULL_PROTOCOL = ROOT / "full_episode_protocol.json"
RESULTS = ROOT / "results"


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows({
            key: json.dumps(value, ensure_ascii=False, separators=(",", ":"))
            if isinstance(value, (dict, list)) else value
            for key, value in row.items()
        } for row in rows)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    args = parser.parse_args()

    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    protocol = json.loads(FYH512_PROTOCOL.read_text(encoding="utf-8"))
    full_protocol = json.loads(FULL_PROTOCOL.read_text(encoding="utf-8"))
    if not protocol.get("frozen_before_reference_evaluation"):
        raise ValueError("Le protocole FYH512 n'est pas gele")
    media = args.media.resolve()
    if media.stat().st_size != source["audio_size_bytes"] or sha256(media) != source["audio_sha256"]:
        raise ValueError("Le media ne correspond pas a la source FYH512 gelee")

    episode = {"id": source["episode_id"], "title": source["title"], "tracks": []}
    voice_detections = detect(episode, full_protocol)
    threshold = float(protocol["voice_detection"]["reliable_internal_confidence_minimum"])
    for row in voice_detections:
        row["passes_reliable_threshold"] = float(row["confidence"]) >= threshold
        row["boundary_role"] = "experimental_voice_boundary_not_final_track_boundary"
        row["identity_confirmed"] = False

    with tempfile.TemporaryDirectory(prefix="fyh512-frozen-") as directory:
        pcm = Path(directory) / "fyh512.f32"
        _decode_to_f32(media, pcm)
        samples = np.memmap(pcm, dtype="<f4", mode="r")
        features = extract_features(samples)
        measured_duration = samples.size / 8000
        novelty = pure_transition_novelty(features)
        candidates = _transition_candidates(novelty, measured_duration)
        track_count = int(protocol["transition_detection"]["track_count"])
        boundaries = detect_pure_transitions(novelty, track_count, measured_duration)
        robust = _robust_positive(novelty)

        transition_rows = []
        for index, boundary in enumerate(boundaries, 1):
            frame = min(robust.size - 1, max(0, int(boundary / HOP_SECONDS)))
            transition_rows.append({
                "algorithm_track_index": index,
                "algorithm_time_seconds": boundary,
                "algorithm_origin": (
                    "audio_start" if index == 1
                    else "audio_spectral_transition_assisted_by_rss_track_count"
                ),
                "internal_confidence": None if index == 1 else round(float(robust[frame]), 6),
                "internal_confidence_kind": "robust_novelty_z_not_probability",
                "independent_artist": None,
                "independent_title": None,
                "independent_title_confidence": None,
                "title_decision": "abstention_no_secret_free_independent_identifier",
            })

    RESULTS.mkdir(exist_ok=True)
    voice_path = RESULTS / "fyh512-voice-detections-frozen.json"
    transitions_path = RESULTS / "fyh512-transitions-frozen.json"
    voice_path.write_text(json.dumps(voice_detections, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    transitions_path.write_text(json.dumps(transition_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_csv(RESULTS / "fyh512-voice-detections-frozen.csv", voice_detections)
    write_csv(RESULTS / "fyh512-transitions-frozen.csv", transition_rows)
    candidate_rows = [
        {"candidate_time_seconds": round(time, 6), "internal_salience": round(score, 6)}
        for time, score in candidates
    ]
    (RESULTS / "fyh512-transition-candidates-frozen.json").write_text(
        json.dumps(candidate_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    summary = {
        "episode_id": source["episode_id"],
        "reference_times_loaded": False,
        "media_sha256": source["audio_sha256"],
        "decoded_sample_duration_seconds": round(measured_duration, 6),
        "models_run_sequentially": ["Silero VAD", "inaSpeechSegmenter", "spectral transition detector"],
        "voice_detections": len(voice_detections),
        "reliable_voice_candidates": sum(row["passes_reliable_threshold"] for row in voice_detections),
        "voice_identity_confirmed": 0,
        "transition_candidates": len(candidate_rows),
        "track_starts_frozen": len(transition_rows),
        "transition_times_frozen": max(0, len(transition_rows) - 1),
        "track_count_assistance": "32 titres RSS; aucun timestamp RSS/YouTube utilise",
        "independent_titles_identified": 0,
        "independent_title_identification_status": "abstention",
        "independent_title_identification_reason": "AudD exige un jeton; la methode de landmarks existante prend les titres connus en entree et n'est donc pas independante.",
        "output_sha256": {
            voice_path.name: sha256(voice_path),
            transitions_path.name: sha256(transitions_path),
        },
    }
    (RESULTS / "fyh512-algorithm-summary-frozen.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
