#!/usr/bin/env python3
"""Evaluate the frozen complete local voice pass against the immediate 16/32 base."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics

from evaluate_fyh512_existing_engine_plus_voice import match_free, rss_reference, rss_to_youtube


LAB = Path(__file__).resolve().parent
RESULTS = LAB / "results"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)


def hhmmss(value: float) -> str:
    rounded = round(value)
    hours, remainder = divmod(rounded, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


def evaluate(path: Path, references: list[dict]):
    frozen = json.loads(path.read_text(encoding="utf-8"))
    detections = [{"number": index, "artist": row["artist"], "title": row["title"],
                   "rss_time_seconds": float(row["providedTime"]), "confidence": row["confidence"]}
                  for index, row in enumerate(frozen, 1)]
    return (*match_free(detections, references), detections)


def main() -> int:
    manifest_path = RESULTS / "fyh512-existing-engine-complete-voice-freeze-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["reference_times_loaded"] is not False or manifest["voice_replay"] is not False:
        raise ValueError("La sortie n'est pas une passe locale gelee independamment des references")
    for name, expected in manifest["outputs"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelee modifiee: {name}")
    immediate_path = RESULTS / "fyh512-existing-engine-plus-voice-frozen.json"
    if sha256(immediate_path) != manifest["immediate_frozen_voice_base_sha256"]:
        raise ValueError("La base immediate 16/32 ne correspond pas au SHA attendu")

    reference_data = json.loads((LAB / "fyh512_reference.json").read_text(encoding="utf-8"))
    references = [{**row, "rss_time_seconds": rss_reference(row)} for row in reference_data["tracks"]]
    base_pairs, base_missing, base_surplus, _ = evaluate(immediate_path, references)
    new_path = RESULTS / "fyh512-existing-engine-complete-voice-frozen.json"
    pairs, missing, surplus, detections = evaluate(new_path, references)
    base_by_ref = {row["reference_number"]: row for row in base_pairs}
    by_ref = {row["reference_number"]: row for row in pairs}
    comparison, lines = [], []
    for reference in references:
        pair = by_ref.get(reference["number"])
        old = base_by_ref.get(reference["number"])
        delta = ("gagne" if pair and not old else "perdu" if old and not pair else
                 "conserve" if pair and old else "toujours_non_trouve")
        algorithm = pair["algorithm_youtube_seconds"] if pair else None
        comparison.append({
            "reference_number": reference["number"], "artist": reference["artist"], "title": reference["title"],
            "youtube_reference_seconds": reference["youtube_time_seconds"],
            "youtube_reference_hhmmss": hhmmss(reference["youtube_time_seconds"]),
            "algorithm_youtube_seconds": algorithm,
            "algorithm_youtube_hhmmss": hhmmss(algorithm) if algorithm is not None else None,
            "status": "trouve" if pair else "non trouve",
            "absolute_error_seconds": pair["absolute_error_seconds"] if pair else None,
            "versus_immediate_16_of_32": delta,
        })
        lines.append(f'{hhmmss(reference["youtube_time_seconds"])} YT | '
                     f'{hhmmss(algorithm)} ALGO' if algorithm is not None else
                     f'{hhmmss(reference["youtube_time_seconds"])} YT | non trouvé')
    errors = [row["absolute_error_seconds"] for row in pairs]
    gained = [row["reference_number"] for row in comparison if row["versus_immediate_16_of_32"] == "gagne"]
    lost = [row["reference_number"] for row in comparison if row["versus_immediate_16_of_32"] == "perdu"]
    trace = json.loads((RESULTS / "fyh512-existing-engine-complete-voice-trace-frozen.json").read_text())
    events = trace["voice"]["events"]
    interpretation = {row["detection_id"]: row for row in trace["voice"]["interpretation"]}
    validation = {row["detection_id"]: row for row in trace["voice"]["validation"]}
    candidate_audit = []
    for event in events:
        detection_id = event["detection_id"]
        interpreted = interpretation[detection_id]
        validated = validation.get(detection_id)
        candidate_audit.append({
            "detection_id": detection_id,
            "start_seconds": event["voice_start_seconds"], "end_seconds": event["voice_end_seconds"],
            "vad_confidence": event["voice_internal_confidence"], "transcribed": bool(event["transcript"]),
            "transcript": event["transcript"], "interpretation_decision": interpreted["decision"],
            "interpretation_reason": interpreted.get("reason"),
            "relation": interpreted.get("relation"), "track_index": interpreted.get("track_index"),
            "anchor_decision": validated.get("decision") if validated else "not_submitted",
            "anchor_reason": validated.get("reason") if validated else interpreted.get("reason"),
        })
    summary = {
        "frozen_outputs_verified_by_sha256": True,
        "matching_rule": "appariement libre glouton un-a-un par erreur absolue croissante <= 30 s",
        "immediate_base": {"matched": len(base_pairs), "missing": len(base_missing), "surplus": len(base_surplus)},
        "complete_voice_pass": {
            "reference_count": len(references), "algorithm_output_count": len(detections),
            "matched": len(pairs), "missing": len(missing), "surplus": len(surplus),
            "mean_absolute_error_seconds_on_matched": round(statistics.mean(errors), 4),
            "median_absolute_error_seconds_on_matched": round(statistics.median(errors), 4),
            "under_1_second": sum(value < 1 for value in errors),
            "under_5_seconds": sum(value < 5 for value in errors),
            "under_10_seconds": sum(value < 10 for value in errors),
            "under_or_equal_30_seconds": sum(value <= 30 for value in errors),
            "statistics_denominator": len(errors),
        },
        "gained_reference_numbers": gained, "lost_reference_numbers": lost,
        "candidate_count": len(events), "transcribed_candidate_count": sum(bool(row["transcript"]) for row in events),
        "verdict": "validated_no_regression" if not lost and len(pairs) >= len(base_pairs) else "regression_stop",
    }
    prefix = "fyh512-existing-engine-complete-voice"
    for suffix, rows in (("pairs", pairs), ("missing", missing), ("surplus", surplus),
                         ("comparison-32", comparison), ("candidate-audit", candidate_audit)):
        write_json(RESULTS / f"{prefix}-{suffix}.json", rows)
        write_csv(RESULTS / f"{prefix}-{suffix}.csv", rows)
    write_json(RESULTS / f"{prefix}-evaluation-summary.json", summary)
    (RESULTS / f"{prefix}-32-lines.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if summary["verdict"] == "regression_stop" else 0


if __name__ == "__main__":
    raise SystemExit(main())
