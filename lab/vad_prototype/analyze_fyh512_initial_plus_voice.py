#!/usr/bin/env python3
"""Complete les points FYH512 initiaux avec les seules annonces voix gelees."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
PROTOCOL = ROOT / "fyh512_initial_plus_voice_protocol.json"
BASELINE = RESULTS / "fyh512-transitions-frozen.json"
VOICES = RESULTS / "fyh512-voice-detections-frozen.json"
ASR = RESULTS / "fyh512-reliable-voices-asr-frozen.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build() -> tuple[list[dict], list[dict], dict]:
    protocol = json.loads(PROTOCOL.read_text(encoding="utf-8"))
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    voices = json.loads(VOICES.read_text(encoding="utf-8"))
    asr = json.loads(ASR.read_text(encoding="utf-8"))
    expected_hash = protocol["baseline"]["transitions_sha256"]
    if sha256(BASELINE) != expected_hash:
        raise ValueError("Le baseline initial gele a ete modifie")
    threshold = float(protocol["voice_inputs"]["reliable_internal_confidence_minimum"])
    radius = float(protocol["complement_rule"]["reconciliation_radius_seconds"])
    semantic = {row["detection_id"]: row for row in protocol["semantic_decisions_frozen_without_reference_times"]}
    transcripts = {row["detection_id"]: row for row in asr}
    points = [{**row, "complement_point_id": f"S{index:03d}"}
              for index, row in enumerate(baseline, 1)]
    trace: list[dict] = []
    replaced: set[str] = set()
    additions: list[dict] = []

    for voice in voices:
        detection_id = voice["detection_id"]
        reliable = float(voice["confidence"]) >= threshold
        decision = semantic.get(detection_id, {}).get("decision")
        eligible = reliable and decision == "explicit_next_music_cue" and detection_id in transcripts
        if not eligible:
            trace.append({
                "input_kind": "voice", "input_id": detection_id,
                "input_time_seconds": voice["algorithm_time_seconds"],
                "confidence": voice["confidence"],
                "reliability_class": "reliable" if reliable else "below_threshold",
                "semantic_decision": decision or "not_evaluated_below_threshold",
                "action": "ignored", "reason": "not_eligible",
                "identity_confirmed": False,
            })
            continue
        voice_time = float(voice["ina_speech_end_seconds"])
        neighbours = [row for row in points
                      if row["algorithm_origin"] != "audio_start"
                      and abs(float(row["algorithm_time_seconds"]) - voice_time) <= radius]
        if len(neighbours) > 1:
            trace.append({
                "input_kind": "voice", "input_id": detection_id,
                "input_time_seconds": voice_time, "confidence": voice["confidence"],
                "reliability_class": "reliable", "semantic_decision": decision,
                "action": "ignored", "reason": "ambiguous_multiple_spectral_neighbours",
                "spectral_neighbour_ids": [row["complement_point_id"] for row in neighbours],
                "identity_confirmed": False,
            })
            continue
        voice_point = {
            "algorithm_track_index": None,
            "algorithm_time_seconds": voice_time,
            "algorithm_origin": "reliable_voice_explicit_next_music_cue",
            "internal_confidence": voice["confidence"],
            "internal_confidence_kind": "frozen_voice_internal_confidence_not_probability_of_track_start",
            "independent_artist": None, "independent_title": None,
            "independent_title_confidence": None,
            "title_decision": "abstention_no_independent_identifier",
            "voice_detection_id": detection_id,
            "identity_confirmed": False,
            "complement_point_id": f"V-{detection_id.rsplit('-', 1)[-1]}",
        }
        additions.append(voice_point)
        if neighbours:
            neighbour = neighbours[0]
            replaced.add(neighbour["complement_point_id"])
            trace.append({
                "input_kind": "voice", "input_id": detection_id,
                "input_time_seconds": voice_time, "confidence": voice["confidence"],
                "reliability_class": "reliable", "semantic_decision": decision,
                "action": "reconciled_replaces_spectral", "reason": "unique_spectral_neighbour_within_baseline_minimum_gap",
                "spectral_neighbour_id": neighbour["complement_point_id"],
                "spectral_time_seconds": neighbour["algorithm_time_seconds"],
                "absolute_delta_seconds": round(abs(float(neighbour["algorithm_time_seconds"]) - voice_time), 6),
                "identity_confirmed": False,
            })
        else:
            trace.append({
                "input_kind": "voice", "input_id": detection_id,
                "input_time_seconds": voice_time, "confidence": voice["confidence"],
                "reliability_class": "reliable", "semantic_decision": decision,
                "action": "added", "reason": "no_spectral_neighbour_within_baseline_minimum_gap",
                "identity_confirmed": False,
            })

    for point in points:
        point_id = point["complement_point_id"]
        trace.append({
            "input_kind": "spectral", "input_id": point_id,
            "input_time_seconds": point["algorithm_time_seconds"],
            "action": "replaced_by_voice" if point_id in replaced else "retained",
        })
    completed = [row for row in points if row["complement_point_id"] not in replaced] + additions
    completed.sort(key=lambda row: float(row["algorithm_time_seconds"]))
    for index, row in enumerate(completed, 1):
        row["completed_point_index"] = index
    summary = {
        "episode_id": protocol["episode_id"], "reference_times_loaded": False,
        "baseline_verified_sha256": expected_hash,
        "baseline_points": len(baseline), "voice_detections": len(voices),
        "reliable_voice_detections": sum(float(row["confidence"]) >= threshold for row in voices),
        "below_threshold_voice_detections": sum(float(row["confidence"]) < threshold for row in voices),
        "eligible_explicit_voice_cues": len(additions),
        "spectral_points_retained": len(points) - len(replaced),
        "spectral_points_replaced": len(replaced),
        "voice_points_added_without_replacement": sum(row["action"] == "added" for row in trace),
        "completed_points": len(completed), "independent_titles_identified": 0,
        "pipeline_reused": "initial_global_spectral_only_plus_frozen_voice_information",
    }
    return completed, trace, summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Valide sans ecrire les sorties")
    args = parser.parse_args()
    completed, trace, summary = build()
    if not args.check:
        outputs = {
            "fyh512-initial-plus-voice-transitions-frozen.json": completed,
            "fyh512-initial-plus-voice-trace-frozen.json": trace,
        }
        for name, value in outputs.items():
            write_json(RESULTS / name, value)
        summary["output_sha256"] = {name: sha256(RESULTS / name) for name in outputs}
        write_json(RESULTS / "fyh512-initial-plus-voice-summary-frozen.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
