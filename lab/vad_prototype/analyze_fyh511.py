#!/usr/bin/env python3
"""Consolidate FYH511 detections without inventing a temporal base."""

from __future__ import annotations

import argparse
import csv
from html import unescape
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from analyze_full_episode import detect


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
sys.path.insert(0, str(REPO))
from server.tracklist import align_tracklist, parse_tracklist  # noqa: E402

SOURCE = ROOT / "fyh511_source.json"
FULL_PROTOCOL = ROOT / "full_episode_protocol.json"
COMBINED_PROTOCOL = ROOT / "combined_protocol.json"
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


def rss_tracklist(feed: Path, expected_title: str, duration: float) -> list[dict]:
    root = ET.parse(feed).getroot()
    item = next(
        item for item in root.findall("./channel/item")
        if (item.findtext("title") or "").strip() == expected_title
    )
    description = item.findtext("description") or ""
    candidates = parse_tracklist(description, structured_only=True)
    tracks = align_tracklist(candidates, [], duration, timestamp_source="rss")
    if len(tracks) != 33:
        raise ValueError(f"Nombre de titres RSS inattendu: {len(tracks)}")
    if any(track["time"] is not None for track in tracks):
        raise ValueError("Le RSS contient maintenant une base temporelle: revue humaine requise")
    return tracks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--feed", required=True, type=Path)
    args = parser.parse_args()
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    full_protocol = json.loads(FULL_PROTOCOL.read_text(encoding="utf-8"))
    combined_protocol = json.loads(COMBINED_PROTOCOL.read_text(encoding="utf-8"))
    policy = combined_protocol["automatic_complement"]
    frozen = {
        "local_window_before_seconds": 5.0,
        "local_window_after_seconds": 5.0,
        "minimum_positive_displacement_seconds": 0.5,
        "maximum_displacement_seconds": 5.0,
        "minimum_internal_confidence": 0.95,
    }
    for key, expected in frozen.items():
        if float(policy[key]) != expected:
            raise ValueError(f"Parametre combine non fige: {key}")
    if policy["required_direction"] != "after_base":
        raise ValueError("Direction combinee non figee")

    tracks = rss_tracklist(args.feed, source["title"], source["duration_seconds"])
    episode = {"id": source["episode_id"], "title": source["title"], "tracks": []}
    detections = detect(episode, full_protocol)
    minimum_confidence = frozen["minimum_internal_confidence"]
    suggestions = []
    for item in detections:
        reliable = float(item["confidence"]) >= minimum_confidence
        suggestions.append({
            **item,
            "confidence_threshold": minimum_confidence,
            "passes_confidence_threshold": reliable,
            "status": "suggestion_ecoute_uniquement" if reliable else "detection_sous_seuil",
            "combined_decision": "abstention",
            "combined_reason": (
                "abstention_no_temporal_base" if reliable
                else "abstention_no_temporal_base_and_confidence_below_minimum"
            ),
        })

    boundaries = []
    for number, track in enumerate(tracks, 1):
        boundaries.append({
            "episode_id": source["episode_id"],
            "boundary_id": f"{source['episode_id']}-t{number:02d}",
            "source_order": number,
            "track_artist": unescape(track["artist"]),
            "track_title": unescape(track["title"]),
            "base_provenance": "description RSS Podbean",
            "base_time_seconds": None,
            "retained_detection_id": None,
            "retained_detection_time_seconds": None,
            "retained_detection_confidence": None,
            "combined_time_seconds": None,
            "displacement_seconds": None,
            "decision": "abstention",
            "reason": "abstention_no_temporal_base",
        })

    reliable = [item for item in suggestions if item["passes_confidence_threshold"]]
    model_metrics = {
        method: json.loads(
            (ROOT / "output" / "full_episode" / f"{source['episode_id']}-{method}.json").read_text(encoding="utf-8")
        )["metrics"]
        for method in ("ina", "silero")
    }
    summary = {
        "episode_id": source["episode_id"],
        "audio_sha256": source["audio_sha256"],
        "duration_seconds": source["duration_seconds"],
        "rss_track_count": len(tracks),
        "temporal_base_available": False,
        "base_reason": "33 titres RSS sans aucun timestamp; aucun chapitre utilisateur accessible sans SQLite/API",
        "combined_adjustment_attempted": False,
        "combined_adjusted_boundaries": 0,
        "combined_abstentions": len(boundaries),
        "audio_recomputed": True,
        "models_run_sequentially": ["Silero VAD", "inaSpeechSegmenter"],
        "model_metrics": model_metrics,
        "detector_parameters_source": "lab/vad_prototype/full_episode_protocol.json (inchange)",
        "combined_parameters_source": "lab/vad_prototype/combined_protocol.json (inchange)",
        "frozen_combined_parameters": frozen,
        "detections_total": len(suggestions),
        "reliable_listening_suggestions": len(reliable),
        "confidence_threshold": minimum_confidence,
        "independent_temporal_reference_available": False,
        "numeric_accuracy_comparison": "non_mesurable",
        "identity_warning": combined_protocol["detection_input"]["identity_warning"],
    }
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "fyh511-detections.json").write_text(
        json.dumps(detections, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (RESULTS / "fyh511-suggestions.json").write_text(
        json.dumps(suggestions, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(RESULTS / "fyh511-suggestions.csv", suggestions)
    (RESULTS / "fyh511-boundaries.json").write_text(
        json.dumps(boundaries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(RESULTS / "fyh511-boundaries.csv", boundaries)
    (RESULTS / "fyh511-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
