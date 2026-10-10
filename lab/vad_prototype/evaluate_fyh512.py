#!/usr/bin/env python3
"""Evaluate committed FYH512 outputs against the later-loaded official reference."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics


ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
REFERENCE = ROOT / "fyh512_reference.json"
FROZEN_SUMMARY = RESULTS / "fyh512-algorithm-summary-frozen.json"
FROZEN_TRANSITIONS = RESULTS / "fyh512-transitions-frozen.json"
FROZEN_VOICES = RESULTS / "fyh512-voice-detections-frozen.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def aligned_time(track: dict, alignment: dict) -> tuple[float, float]:
    video_time = float(track["youtube_time_seconds"])
    offset = (
        float(alignment["offset_before_seconds"])
        if video_time < float(alignment["split_youtube_seconds"])
        else float(alignment["offset_after_seconds"])
    )
    return round(video_time + offset, 3), offset


def main() -> int:
    reference = json.loads(REFERENCE.read_text(encoding="utf-8"))
    frozen_summary = json.loads(FROZEN_SUMMARY.read_text(encoding="utf-8"))
    transitions = json.loads(FROZEN_TRANSITIONS.read_text(encoding="utf-8"))
    voices = json.loads(FROZEN_VOICES.read_text(encoding="utf-8"))
    if frozen_summary["reference_times_loaded"] is not False:
        raise ValueError("Les sorties ne sont pas marquees comme gelees sans reference")
    for name, expected in frozen_summary["output_sha256"].items():
        actual = sha256(RESULTS / name)
        if actual != expected:
            raise ValueError(f"Sortie gelee modifiee: {name}")
    if len(reference["tracks"]) != len(transitions):
        raise ValueError("Cardinalites reference/algorithme incompatibles")

    rows = []
    for track, algorithm in zip(reference["tracks"], transitions):
        reference_audio, offset = aligned_time(track, reference["audio_alignment"])
        algorithm_time = float(algorithm["algorithm_time_seconds"])
        signed = round(algorithm_time - reference_audio, 3)
        absolute = round(abs(signed), 3)
        rows.append({
            "track_number": track["number"],
            "reference_artist": track["artist"],
            "reference_title": track["title"],
            "algorithm_origin": algorithm["algorithm_origin"],
            "algorithm_time_seconds": algorithm_time,
            "algorithm_internal_confidence": algorithm["internal_confidence"],
            "algorithm_internal_confidence_kind": algorithm["internal_confidence_kind"],
            "independent_artist": algorithm["independent_artist"],
            "independent_title": algorithm["independent_title"],
            "independent_title_confidence": algorithm["independent_title_confidence"],
            "title_identification_status": "missing_abstention",
            "title_match": None,
            "reference_youtube_time_seconds": track["youtube_time_seconds"],
            "reference_alignment_offset_seconds": offset,
            "reference_audio_time_seconds": reference_audio,
            "signed_error_seconds": signed,
            "absolute_error_seconds": absolute,
            "absolute_error_lt_1": absolute < 1,
            "absolute_error_lt_3": absolute < 3,
            "absolute_error_lt_10": absolute < 10,
            "error_gt_10": absolute > 10,
            "boundary_status": "paired_by_frozen_rss_order",
            "reference_status": "official_published_not_human_audited",
        })

    aligned_references = [
        {**track, "audio_time_seconds": aligned_time(track, reference["audio_alignment"])[0]}
        for track in reference["tracks"]
    ]
    voice_rows = []
    for voice in voices:
        nearest = min(
            aligned_references,
            key=lambda track: abs(float(voice["algorithm_time_seconds"]) - track["audio_time_seconds"]),
        )
        delta = round(float(voice["algorithm_time_seconds"]) - nearest["audio_time_seconds"], 3)
        voice_rows.append({
            "detection_id": voice["detection_id"],
            "algorithm_time_seconds": voice["algorithm_time_seconds"],
            "internal_confidence": voice["confidence"],
            "passes_reliable_threshold": voice["passes_reliable_threshold"],
            "identity_confirmed": False,
            "nearest_reference_track_number": nearest["number"],
            "nearest_reference_title": nearest["title"],
            "nearest_reference_audio_time_seconds": nearest["audio_time_seconds"],
            "signed_delta_seconds": delta,
            "absolute_delta_seconds": abs(delta),
            "within_10_seconds": abs(delta) < 10,
            "interpretation": "proximity_only_not_identity_or_transition_proof",
        })

    errors = [row["absolute_error_seconds"] for row in rows]
    summary = {
        "episode_id": "find-your-harmony-512",
        "comparison_is_non_circular_for_times": True,
        "frozen_outputs_verified_by_sha256": True,
        "track_count_assistance": "32 titres RSS connus avant detection; temps de reference non utilises",
        "reference_track_boundaries": len(rows),
        "algorithm_boundaries": len(transitions),
        "paired_by_frozen_order": len(rows),
        "missing_reference_boundaries": 0,
        "surplus_algorithm_boundaries": 0,
        "unmodelled_intro_before_track_1": True,
        "independent_titles_identified": 0,
        "independent_titles_missing": len(rows),
        "independent_titles_surplus": 0,
        "independent_title_identification_rate": 0.0,
        "mean_absolute_error_seconds": round(statistics.mean(errors), 3),
        "median_absolute_error_seconds": round(statistics.median(errors), 3),
        "share_absolute_error_lt_1": round(sum(value < 1 for value in errors) / len(errors), 4),
        "share_absolute_error_lt_3": round(sum(value < 3 for value in errors) / len(errors), 4),
        "share_absolute_error_lt_10": round(sum(value < 10 for value in errors) / len(errors), 4),
        "errors_gt_10": sum(value > 10 for value in errors),
        "voice_candidates": len(voice_rows),
        "reliable_voice_candidates": sum(row["passes_reliable_threshold"] for row in voice_rows),
        "voice_candidates_within_10_seconds_of_published_boundary": sum(row["within_10_seconds"] for row in voice_rows),
        "reference_limit": reference["audio_alignment"]["limit"],
        "verdict": "Le detecteur spectral assiste par le nombre de titres ne reproduit pas fiablement les frontieres publiees; aucune identification de titre independante n'a pu etre executee.",
    }
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "fyh512-evaluation.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(RESULTS / "fyh512-evaluation.csv", rows)
    (RESULTS / "fyh512-voice-reference-comparison.json").write_text(
        json.dumps(voice_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(RESULTS / "fyh512-voice-reference-comparison.csv", voice_rows)
    (RESULTS / "fyh512-evaluation-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
