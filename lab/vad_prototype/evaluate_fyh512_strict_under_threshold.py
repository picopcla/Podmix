#!/usr/bin/env python3
"""Évalue après gel la voie voix stricte sous 0,95 sur les 32 pistes FYH512."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics

from evaluate_fyh512_existing_engine_plus_voice import match_free, rss_reference, rss_to_youtube


LAB = Path(__file__).resolve().parent
RESULTS = LAB / "results"
PREFIX = "fyh512-existing-engine-strict-under-threshold"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_outputs(name: str, references: list[dict]) -> tuple[list[dict], list[dict], list[dict], list[dict]]:
    rows = json.loads((RESULTS / name).read_text(encoding="utf-8"))
    detections = [
        {"number": index, "artist": row["artist"], "title": row["title"],
         "rss_time_seconds": float(row["providedTime"]), "confidence": row["confidence"]}
        for index, row in enumerate(rows, 1)
    ]
    return (*match_free(detections, references), detections)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[dict]) -> None:
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def hhmmss(value: float) -> str:
    rounded = round(value)
    hours, remainder = divmod(rounded, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


def main() -> int:
    manifest_path = RESULTS / f"{PREFIX}-freeze-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["reference_times_loaded"] is not False or manifest["voice_replay"] is not True:
        raise ValueError("Le gel/replay de l'expérience n'est pas conforme")
    for name, expected in manifest["outputs"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelée modifiée: {name}")

    reference_data = json.loads((LAB / "fyh512_reference.json").read_text(encoding="utf-8"))
    references = [{**row, "rss_time_seconds": rss_reference(row)} for row in reference_data["tracks"]]
    baseline_pairs, baseline_missing, baseline_surplus, baseline = load_outputs(
        "fyh512-existing-engine-results-frozen.json", references,
    )
    immediate_pairs, immediate_missing, immediate_surplus, immediate = load_outputs(
        "fyh512-existing-engine-plus-voice-frozen.json", references,
    )
    pairs, missing, surplus, detections = load_outputs(f"{PREFIX}-frozen.json", references)

    immediate_by_ref = {row["reference_number"]: row for row in immediate_pairs}
    current_by_ref = {row["reference_number"]: row for row in pairs}
    comparison = []
    for reference, before, after in zip(references, immediate, detections, strict=True):
        pair_before = immediate_by_ref.get(reference["number"])
        pair_after = current_by_ref.get(reference["number"])
        if pair_after and not pair_before:
            free_status = "gagne"
        elif pair_before and not pair_after:
            free_status = "perdu"
        elif pair_after:
            free_status = "conserve"
        else:
            free_status = "toujours_hors_30s"
        before_youtube = rss_to_youtube(before["rss_time_seconds"])
        after_youtube = rss_to_youtube(after["rss_time_seconds"])
        signed = round(after_youtube - reference["youtube_time_seconds"], 4)
        comparison.append({
            "reference_number": reference["number"], "artist": reference["artist"], "title": reference["title"],
            "youtube_reference_seconds": reference["youtube_time_seconds"],
            "algorithm_rss_seconds": after["rss_time_seconds"], "algorithm_youtube_seconds": after_youtube,
            "signed_index_distance_seconds": signed, "absolute_index_distance_seconds": abs(signed),
            "immediate_algorithm_rss_seconds": before["rss_time_seconds"],
            "immediate_algorithm_youtube_seconds": before_youtube,
            "signed_algorithm_change_seconds": round(after_youtube - before_youtube, 4),
            "free_matching_status_versus_16_of_32": free_status,
            "free_match_signed_error_seconds": pair_after["signed_error_seconds"] if pair_after else None,
        })

    errors = [row["absolute_error_seconds"] for row in pairs]
    distances = [row["absolute_index_distance_seconds"] for row in comparison]
    distribution = {
        "under_60_seconds": sum(value < 60 for value in distances),
        "from_60_to_under_120_seconds": sum(60 <= value < 120 for value in distances),
        "from_120_to_under_300_seconds": sum(120 <= value < 300 for value in distances),
        "at_least_300_seconds": sum(value >= 300 for value in distances),
        "denominator": len(distances),
    }
    gained = [row["reference_number"] for row in comparison if row["free_matching_status_versus_16_of_32"] == "gagne"]
    lost = [row["reference_number"] for row in comparison if row["free_matching_status_versus_16_of_32"] == "perdu"]

    trace = json.loads((RESULTS / f"{PREFIX}-trace-frozen.json").read_text(encoding="utf-8"))
    interpretations = {row["detection_id"]: row for row in trace["voice"]["interpretation"]}
    validations = {row["detection_id"]: row for row in trace["voice"]["validation"]}
    audit = []
    for event in trace["voice"]["events"]:
        interpreted = interpretations[event["detection_id"]]
        validated = validations.get(event["detection_id"])
        audit.append({
            "detection_id": event["detection_id"], "voice_start_seconds": event["voice_start_seconds"],
            "voice_end_seconds": event["voice_end_seconds"], "vad_confidence": event["voice_internal_confidence"],
            "transcript": event["transcript"], "route": interpreted.get("route"),
            "asr_average_log_probability": interpreted.get("asr_average_log_probability"),
            "asr_max_no_speech_probability": interpreted.get("asr_max_no_speech_probability"),
            "relation": interpreted.get("relation"), "relation_score": interpreted.get("relation_score"),
            "title_score": interpreted.get("title_score"), "title_margin": interpreted.get("title_margin"),
            "track_index": interpreted.get("track_index"), "interpretation_decision": interpreted["decision"],
            "interpretation_reason": interpreted.get("reason"),
            "optimizer_decision": validated.get("decision") if validated else "not_submitted",
            "optimizer_reason": validated.get("reason") if validated else interpreted.get("reason"),
        })

    verdict = "validated_no_regression" if not lost and len(pairs) >= len(immediate_pairs) else "regression_stop"
    summary = {
        "frozen_outputs_verified_by_sha256": True,
        "replay_condition": manifest["replay_condition"],
        "matching_rule": "appariement libre glouton un-à-un par erreur absolue croissante <= 30 s",
        "reference_count": len(references),
        "baseline_12_of_32": {"matched": len(baseline_pairs), "missing": len(baseline_missing), "surplus": len(baseline_surplus)},
        "immediate_16_of_32": {"matched": len(immediate_pairs), "missing": len(immediate_missing), "surplus": len(immediate_surplus)},
        "strict_under_threshold": {
            "matched": len(pairs), "missing": len(missing), "surplus": len(surplus),
            "mean_absolute_error_seconds_on_matched": round(statistics.mean(errors), 4),
            "median_absolute_error_seconds_on_matched": round(statistics.median(errors), 4),
            "statistics_denominator": len(errors),
        },
        "gained_reference_numbers_versus_immediate": gained,
        "lost_reference_numbers_versus_immediate": lost,
        "index_association_distance_distribution": distribution,
        "verdict": verdict,
    }
    lines = [
        f'{hhmmss(row["youtube_reference_seconds"])} 1001TL | {hhmmss(row["algorithm_youtube_seconds"])} ALGO'
        for row in comparison
    ]
    for suffix, rows in (("pairs", pairs), ("missing", missing), ("surplus", surplus),
                         ("comparison-32", comparison), ("candidate-audit", audit)):
        write_json(RESULTS / f"{PREFIX}-{suffix}.json", rows)
        write_csv(RESULTS / f"{PREFIX}-{suffix}.csv", rows)
    write_json(RESULTS / f"{PREFIX}-evaluation-summary.json", summary)
    (RESULTS / f"{PREFIX}-32-lines.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if verdict == "regression_stop" else 0


if __name__ == "__main__":
    raise SystemExit(main())
