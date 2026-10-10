#!/usr/bin/env python3
"""Conservative lab-only complement of explicit chapter times with VAD detections.

The script consumes the already versioned PTR492/PTR493 detections. It never
reads media and never writes Podmix application data.
"""

from __future__ import annotations

import csv
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import statistics
from typing import Iterable


ROOT = Path(__file__).resolve().parent
EPISODES_PATH = ROOT / "episodes.json"
PROTOCOL_PATH = ROOT / "combined_protocol.json"
DETECTIONS_PATH = ROOT / "results" / "full-episode-detections.json"
RESULTS = ROOT / "results"
TARGET_EPISODES = ("pure-trance-radio-492", "pure-trance-radio-493")


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def boundary_id(episode_id: str, track_number: int) -> str:
    return f"{episode_id}-t{track_number:02d}"


def load_base_boundaries(episodes_document: dict, protocol: dict) -> list[dict]:
    episodes = {item["id"]: item for item in episodes_document["episodes"]}
    rows: list[dict] = []
    expected_counts = protocol["base"]["ptr_boundary_counts"]
    for episode_id in TARGET_EPISODES:
        episode = episodes[episode_id]
        previous_time = -math.inf
        for source_order, track in enumerate(episode["tracks"], 1):
            base_time = float(track["time"])
            if base_time <= previous_time:
                raise ValueError(f"Base non strictement croissante: {episode_id}")
            previous_time = base_time
            rows.append({
                "episode_id": episode_id,
                "episode_title": episode["title"],
                "boundary_id": boundary_id(episode_id, int(track["number"])),
                "source_order": source_order,
                "track_number": int(track["number"]),
                "track_title": track["title"],
                "base_provenance": "CueNation cuesheet",
                "base_source_url": episode["tracklist_url"],
                "base_code_path": "server/tracklist.py::parse_tracklist + align_tracklist",
                "base_time_seconds": base_time,
            })
        actual = sum(row["episode_id"] == episode_id for row in rows)
        if actual != expected_counts[episode_id]:
            raise ValueError(f"Nombre de frontieres inattendu pour {episode_id}: {actual}")
    return rows


def validate_detection_input(detections: list[dict]) -> None:
    ids: set[str] = set()
    times: set[tuple[str, float]] = set()
    for item in detections:
        detection_id = item["detection_id"]
        key = (item["episode_id"], float(item["algorithm_time_seconds"]))
        if detection_id in ids:
            raise ValueError(f"Identifiant de detection duplique: {detection_id}")
        if key in times:
            raise ValueError(f"Temps de detection duplique: {key}")
        ids.add(detection_id)
        times.add(key)


def _decision_for_boundary(boundary: dict, detections: list[dict], policy: dict) -> dict:
    base_time = boundary["base_time_seconds"]
    before = policy["local_window_before_seconds"]
    after = policy["local_window_after_seconds"]
    minimum_delta = policy["minimum_positive_displacement_seconds"]
    maximum_delta = policy["maximum_displacement_seconds"]
    minimum_confidence = policy["minimum_internal_confidence"]
    local = [
        item for item in detections
        if base_time - before <= float(item["algorithm_time_seconds"]) <= base_time + after
    ]
    forward = [
        item for item in local
        if minimum_delta <= float(item["algorithm_time_seconds"]) - base_time <= maximum_delta
    ]
    reliable = [item for item in forward if float(item["confidence"]) >= minimum_confidence]
    closest = min(
        local,
        default=None,
        key=lambda item: (
            abs(float(item["algorithm_time_seconds"]) - base_time),
            -float(item["confidence"]),
            item["detection_id"],
        ),
    )
    result = {
        "local_detection_count": len(local),
        "eligible_detection_count": len(reliable),
        "nearby_detection_ids": [item["detection_id"] for item in local],
        "closest_detection_id": closest["detection_id"] if closest else None,
        "closest_detection_time_seconds": float(closest["algorithm_time_seconds"]) if closest else None,
        "closest_detection_confidence": float(closest["confidence"]) if closest else None,
        "retained_detection_id": None,
        "retained_detection_time_seconds": None,
        "retained_detection_confidence": None,
        "decision": "abstention",
        "reason": "abstention_no_detection_in_local_window",
        "automatic_combined_time_seconds": base_time,
        "automatic_displacement_seconds": 0.0,
    }
    if not local:
        return result
    if not forward:
        result["reason"] = "abstention_no_detection_in_forward_interval"
        return result
    if not reliable:
        result["reason"] = "abstention_confidence_below_minimum"
        return result
    if len(reliable) > 1:
        result["reason"] = "abstention_ambiguous_multiple_detections"
        return result
    retained = reliable[0]
    retained_time = float(retained["algorithm_time_seconds"])
    result.update({
        "retained_detection_id": retained["detection_id"],
        "retained_detection_time_seconds": retained_time,
        "retained_detection_confidence": float(retained["confidence"]),
        "decision": "adjusted_automatically",
        "reason": "adjusted_unique_reliable_voice_end_then_music",
        "automatic_combined_time_seconds": retained_time,
        "automatic_displacement_seconds": round(retained_time - base_time, 6),
    })
    return result


def combine_boundaries(base: list[dict], detections: list[dict], protocol: dict) -> list[dict]:
    validate_detection_input(detections)
    by_episode: dict[str, list[dict]] = defaultdict(list)
    for detection in detections:
        if detection["episode_id"] in TARGET_EPISODES:
            by_episode[detection["episode_id"]].append(detection)

    combined = [
        {**boundary, **_decision_for_boundary(boundary, by_episode[boundary["episode_id"]], protocol["automatic_complement"])}
        for boundary in base
    ]

    # A detection is never allowed to move multiple boundaries.
    claims: dict[str, list[dict]] = defaultdict(list)
    for row in combined:
        if row["retained_detection_id"]:
            claims[row["retained_detection_id"]].append(row)
    for detection_id, rows in claims.items():
        if len(rows) < 2:
            continue
        for row in rows:
            row.update({
                "retained_detection_id": None,
                "retained_detection_time_seconds": None,
                "retained_detection_confidence": None,
                "decision": "abstention",
                "reason": f"abstention_detection_conflict:{detection_id}",
                "automatic_combined_time_seconds": row["base_time_seconds"],
                "automatic_displacement_seconds": 0.0,
            })

    # Proposed moves must preserve strict order and remain before the next raw boundary.
    rows_by_episode: dict[str, list[dict]] = defaultdict(list)
    for row in combined:
        rows_by_episode[row["episode_id"]].append(row)
    for rows in rows_by_episode.values():
        for index, row in enumerate(rows):
            if row["decision"] != "adjusted_automatically":
                continue
            proposed = row["automatic_combined_time_seconds"]
            previous = rows[index - 1]["automatic_combined_time_seconds"] if index else -math.inf
            next_base = rows[index + 1]["base_time_seconds"] if index + 1 < len(rows) else math.inf
            if not (previous < proposed < next_base):
                row.update({
                    "retained_detection_id": None,
                    "retained_detection_time_seconds": None,
                    "retained_detection_confidence": None,
                    "decision": "abstention",
                    "reason": "abstention_order_protection",
                    "automatic_combined_time_seconds": row["base_time_seconds"],
                    "automatic_displacement_seconds": 0.0,
                })

    human = {
        item["boundary_id"]: item for item in protocol["human_corrections"]["boundaries"]
    }
    for row in combined:
        correction = human.get(row["boundary_id"])
        row["human_validation_status"] = "validated_for_lab_only" if correction else "none"
        row["existing_human_time_seconds"] = correction["human_time_seconds"] if correction else None
        row["human_only_time_seconds"] = correction["human_time_seconds"] if correction else row["base_time_seconds"]
        row["automatic_plus_human_time_seconds"] = (
            correction["human_time_seconds"] if correction else row["automatic_combined_time_seconds"]
        )
        row["automatic_plus_human_source"] = (
            "existing_human_correction" if correction
            else "automatic_complement" if row["decision"] == "adjusted_automatically"
            else "raw_base"
        )
    return combined


def normal_ci(value: float, standard_error: float) -> list[float]:
    return [round(value - 1.96 * standard_error, 6), round(value + 1.96 * standard_error, 6)]


def offset_and_drift(matched: list[dict]) -> dict:
    n = len(matched)
    if n < 3:
        return {"available": False, "n": n, "reason": "Moins de trois appariements."}
    xs = [float(row["reference_time_seconds"]) for row in matched]
    ys = [float(row["signed_error_seconds"]) for row in matched]
    x_mean = statistics.mean(xs)
    y_mean = statistics.mean(ys)
    sxx = sum((x - x_mean) ** 2 for x in xs)
    slope = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys)) / sxx
    intercept = y_mean - slope * x_mean
    residuals = [y - (intercept + slope * x) for x, y in zip(xs, ys)]
    sse = sum(value * value for value in residuals)
    syy = sum((y - y_mean) ** 2 for y in ys)
    sigma2 = sse / (n - 2)
    slope_se = math.sqrt(sigma2 / sxx)
    intercept_se = math.sqrt(sigma2 * (1 / n + x_mean * x_mean / sxx))
    mean_se = statistics.stdev(ys) / math.sqrt(n)
    mean_ci = normal_ci(y_mean, mean_se)
    slope_ci = normal_ci(slope, slope_se)
    return {
        "available": True,
        "n": n,
        "mean_signed_offset_seconds": round(y_mean, 6),
        "mean_signed_offset_ci95_seconds": mean_ci,
        "constant_offset_detected": bool(mean_ci[0] > 0 or mean_ci[1] < 0),
        "intercept_seconds": round(intercept, 6),
        "intercept_ci95_seconds": normal_ci(intercept, intercept_se),
        "slope_seconds_per_second": round(slope, 9),
        "slope_seconds_per_hour": round(slope * 3600, 6),
        "slope_ci95_seconds_per_hour": [round(value * 3600, 6) for value in slope_ci],
        "drift_detected": bool(slope_ci[0] > 0 or slope_ci[1] < 0),
        "r_squared": round(1 - sse / syy, 6) if syy else 0.0,
        "test_limit": "OLS et intervalles normaux 95 %, exploratoires.",
    }


def match_one_to_one(
    references: list[dict], candidates: list[dict], time_key: str, tolerance: float
) -> tuple[list[dict], list[dict], list[dict]]:
    unused = {item["boundary_id"]: item for item in candidates}
    matched: list[dict] = []
    missing: list[dict] = []
    for reference in sorted(references, key=lambda item: item["base_time_seconds"]):
        eligible = [
            item for item in unused.values()
            if item["episode_id"] == reference["episode_id"]
            and abs(float(item[time_key]) - float(reference["base_time_seconds"])) <= tolerance
        ]
        candidate = min(
            eligible,
            default=None,
            key=lambda item: (
                abs(float(item[time_key]) - float(reference["base_time_seconds"])),
                float(item[time_key]),
                item["boundary_id"],
            ),
        )
        if candidate is None:
            missing.append(reference)
            continue
        signed_error = round(float(candidate[time_key]) - float(reference["base_time_seconds"]), 6)
        matched.append({
            "episode_id": reference["episode_id"],
            "reference_boundary_id": reference["boundary_id"],
            "candidate_boundary_id": candidate["boundary_id"],
            "reference_time_seconds": reference["base_time_seconds"],
            "candidate_time_seconds": candidate[time_key],
            "signed_error_seconds": signed_error,
            "absolute_error_seconds": abs(signed_error),
        })
        unused.pop(candidate["boundary_id"])
    return matched, missing, list(unused.values())


def metrics_for_scenario(
    references: list[dict], candidates: list[dict], time_key: str, tolerance: float
) -> dict:
    matched, missing, unmatched = match_one_to_one(references, candidates, time_key, tolerance)
    absolute = [row["absolute_error_seconds"] for row in matched]
    return {
        "candidate_time_field": time_key,
        "reference_boundaries": len(references),
        "candidate_boundaries": len(candidates),
        "matched_boundaries": len(matched),
        "missing_references": len(missing),
        "unmatched_boundaries": len(unmatched),
        "missing_reference_ids": [row["boundary_id"] for row in missing],
        "unmatched_boundary_ids": [row["boundary_id"] for row in unmatched],
        "signed_errors_seconds": [row["signed_error_seconds"] for row in matched],
        "median_absolute_error_seconds": round(statistics.median(absolute), 6) if absolute else None,
        "mean_absolute_error_seconds": round(statistics.mean(absolute), 6) if absolute else None,
        "share_absolute_error_lt_1": round(sum(value < 1 for value in absolute) / len(absolute), 6) if absolute else None,
        "share_absolute_error_lt_3": round(sum(value < 3 for value in absolute) / len(absolute), 6) if absolute else None,
        "share_absolute_error_lt_10": round(sum(value < 10 for value in absolute) / len(absolute), 6) if absolute else None,
        "offset_and_drift": offset_and_drift(matched),
    }


def evaluate(combined: list[dict], protocol: dict) -> dict:
    tolerance = float(protocol["evaluation"]["maximum_matching_tolerance_seconds"])
    scenario_fields = {
        "raw_base": "base_time_seconds",
        "automatic_combined": "automatic_combined_time_seconds",
        "existing_human_corrections_only": "human_only_time_seconds",
        "automatic_plus_existing_human": "automatic_plus_human_time_seconds",
    }
    by_episode: dict[str, list[dict]] = defaultdict(list)
    for row in combined:
        by_episode[row["episode_id"]].append(row)

    scenarios: dict[str, dict] = {}
    for scenario, field in scenario_fields.items():
        scenarios[scenario] = {
            "overall": metrics_for_scenario(combined, combined, field, tolerance),
            "episodes": {
                episode_id: metrics_for_scenario(rows, rows, field, tolerance)
                for episode_id, rows in by_episode.items()
            },
        }

    adjusted = [row for row in combined if row["decision"] == "adjusted_automatically"]
    return {
        "protocol_file": "lab/vad_prototype/combined_protocol.json",
        "input_detection_file": "lab/vad_prototype/results/full-episode-detections.json",
        "input_detection_commit": protocol["detection_input"]["source_commit"],
        "audio_recomputed": False,
        "base_method": protocol["base"],
        "evaluation_validity": {
            "reference_is_same_cuenation_source_as_base": True,
            "classification": "concordance_a_la_source_circulaire",
            "audio_precision_claim_allowed": False,
            "cuenation_rss_audio_alignment_verified": False,
            "independent_exhaustive_human_reference_available": False,
            "known_human_cases_are_blind_test_set": False,
            "warning": protocol["evaluation"]["validity_warning"],
        },
        "boundary_invariants": {
            "raw_count": len(combined),
            "automatic_count": len(combined),
            "created": 0,
            "deleted": 0,
            "reordered": 0,
            "titles_changed": 0,
        },
        "automatic_decisions": {
            "adjusted": len(adjusted),
            "unchanged": len(combined) - len(adjusted),
            "adjusted_boundary_ids": [row["boundary_id"] for row in adjusted],
            "reason_counts": dict(sorted(Counter(row["reason"] for row in combined).items())),
        },
        "existing_human_corrections": [
            {
                "boundary_id": row["boundary_id"],
                "base_time_seconds": row["base_time_seconds"],
                "human_time_seconds": row["existing_human_time_seconds"],
                "also_selected_by_automatic_policy": row["decision"] == "adjusted_automatically",
            }
            for row in combined if row["existing_human_time_seconds"] is not None
        ],
        "scenarios": scenarios,
    }


def effect_label(base_error: float, combined_error: float) -> str:
    if combined_error < base_error:
        return "improved"
    if combined_error > base_error:
        return "degraded"
    return "unchanged"


def enrich_rows_for_output(rows: list[dict]) -> list[dict]:
    output = []
    for row in rows:
        base_error = round(row["base_time_seconds"] - row["base_time_seconds"], 6)
        combined_error = round(row["automatic_combined_time_seconds"] - row["base_time_seconds"], 6)
        human_error = round(row["human_only_time_seconds"] - row["base_time_seconds"], 6)
        layered_error = round(row["automatic_plus_human_time_seconds"] - row["base_time_seconds"], 6)
        output.append({
            **row,
            "reference_provenance": "CueNation cuesheet (same source as raw base)",
            "reference_time_seconds": row["base_time_seconds"],
            "base_signed_error_seconds": base_error,
            "base_absolute_error_seconds": abs(base_error),
            "automatic_combined_signed_error_seconds": combined_error,
            "automatic_combined_absolute_error_seconds": abs(combined_error),
            "automatic_effect_vs_base_source_concordance": effect_label(abs(base_error), abs(combined_error)),
            "human_only_signed_error_seconds": human_error,
            "automatic_plus_human_signed_error_seconds": layered_error,
        })
    return output


def csv_value(value):
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return value


def write_csv(path: Path, rows: Iterable[dict]) -> None:
    materialized = list(rows)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(materialized[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows({key: csv_value(value) for key, value in row.items()} for row in materialized)


def generate() -> tuple[list[dict], dict]:
    protocol = load_json(PROTOCOL_PATH)
    if not protocol.get("frozen_before_combined_evaluation"):
        raise ValueError("Le protocole combine n'est pas gele avant evaluation")
    base = load_base_boundaries(load_json(EPISODES_PATH), protocol)
    combined = combine_boundaries(base, load_json(DETECTIONS_PATH), protocol)
    output_rows = enrich_rows_for_output(combined)
    summary = evaluate(output_rows, protocol)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "combined-boundaries.json").write_text(
        json.dumps(output_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(RESULTS / "combined-boundaries.csv", output_rows)
    (RESULTS / "combined-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return output_rows, summary


if __name__ == "__main__":
    rows, summary = generate()
    print(json.dumps({
        "boundaries": len(rows),
        "automatic_adjusted": summary["automatic_decisions"]["adjusted"],
        "automatic_unchanged": summary["automatic_decisions"]["unchanged"],
        "audio_recomputed": summary["audio_recomputed"],
    }, ensure_ascii=False))
