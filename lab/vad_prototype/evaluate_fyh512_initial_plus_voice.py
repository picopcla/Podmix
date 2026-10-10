#!/usr/bin/env python3
"""Evalue le baseline FYH512 et son complement voix avec la meme regle libre."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics


ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
TOLERANCE = 30.0
SPLIT_YOUTUBE = 3565.0
OFFSET_BEFORE = 1.3455
OFFSET_AFTER = 1.7265


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def reference_rss(track: dict) -> float:
    offset = OFFSET_BEFORE if float(track["youtube_time_seconds"]) < SPLIT_YOUTUBE else OFFSET_AFTER
    return round(float(track["youtube_time_seconds"]) + offset, 4)


def rss_to_youtube(value: float) -> float:
    offset = OFFSET_BEFORE if value < SPLIT_YOUTUBE + OFFSET_BEFORE else OFFSET_AFTER
    return round(value - offset, 4)


def match_free(detections: list[dict], references: list[dict]) -> tuple[list[dict], list[dict], list[dict]]:
    unused_detections = set(range(len(detections)))
    unused_references = set(range(len(references)))
    candidates = sorted(
        (abs(detections[di]["rss_time_seconds"] - references[ri]["rss_time_seconds"]), di, ri)
        for di in unused_detections for ri in unused_references
        if abs(detections[di]["rss_time_seconds"] - references[ri]["rss_time_seconds"]) <= TOLERANCE
    )
    pairs = []
    for _, di, ri in candidates:
        if di not in unused_detections or ri not in unused_references:
            continue
        unused_detections.remove(di)
        unused_references.remove(ri)
        detection, reference = detections[di], references[ri]
        signed = round(detection["rss_time_seconds"] - reference["rss_time_seconds"], 4)
        pairs.append({
            "reference_number": reference["number"], "artist": reference["artist"],
            "title": reference["title"],
            "youtube_reference_seconds": reference["youtube_time_seconds"],
            "rss_reference_seconds": reference["rss_time_seconds"],
            "detection_id": detection["detection_id"], "origin": detection["origin"],
            "rss_algorithm_seconds": detection["rss_time_seconds"],
            "youtube_algorithm_seconds": rss_to_youtube(detection["rss_time_seconds"]),
            "signed_error_seconds": signed, "absolute_error_seconds": abs(signed),
        })
    pairs.sort(key=lambda row: row["reference_number"])
    missing = [references[index] for index in sorted(unused_references)]
    surplus = [detections[index] for index in sorted(unused_detections,
                                                      key=lambda item: detections[item]["rss_time_seconds"])]
    return pairs, missing, surplus


def metrics(pairs: list[dict], missing: list[dict], surplus: list[dict], count: int) -> dict:
    errors = [row["absolute_error_seconds"] for row in pairs]
    return {
        "reference_count": len(pairs) + len(missing), "detection_count": count,
        "matched": len(pairs), "missing": len(missing), "surplus": len(surplus),
        "mean_absolute_error_seconds_on_matches": round(statistics.mean(errors), 4) if errors else None,
        "median_absolute_error_seconds_on_matches": round(statistics.median(errors), 4) if errors else None,
        "matched_under_1_second": sum(value < 1 for value in errors),
        "matched_under_3_seconds": sum(value < 3 for value in errors),
        "matched_under_10_seconds": sum(value < 10 for value in errors),
        "statistics_denominator": len(pairs),
    }


def clock(seconds: float) -> str:
    value = int(seconds + 0.5)
    return f"{value // 3600:02d}:{value % 3600 // 60:02d}:{value % 60:02d}"


def main() -> int:
    frozen = json.loads((RESULTS / "fyh512-initial-plus-voice-summary-frozen.json").read_text(encoding="utf-8"))
    if frozen["reference_times_loaded"] is not False:
        raise ValueError("Le complement n'est pas gele avant evaluation")
    for name, expected in frozen["output_sha256"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelee modifiee: {name}")
    reference = json.loads((ROOT / "fyh512_reference.json").read_text(encoding="utf-8"))
    references = [{**row, "rss_time_seconds": reference_rss(row)} for row in reference["tracks"]]
    baseline_rows = json.loads((RESULTS / "fyh512-transitions-frozen.json").read_text(encoding="utf-8"))
    completed_rows = json.loads((RESULTS / "fyh512-initial-plus-voice-transitions-frozen.json").read_text(encoding="utf-8"))
    baseline = [{"detection_id": f"S{index:03d}", "origin": row["algorithm_origin"],
                 "rss_time_seconds": float(row["algorithm_time_seconds"])}
                for index, row in enumerate(baseline_rows, 1)]
    completed = [{"detection_id": row["complement_point_id"], "origin": row["algorithm_origin"],
                  "rss_time_seconds": float(row["algorithm_time_seconds"])} for row in completed_rows]
    baseline_pairs, baseline_missing, baseline_surplus = match_free(baseline, references)
    completed_pairs, completed_missing, completed_surplus = match_free(completed, references)
    baseline_by_number = {row["reference_number"]: row for row in baseline_pairs}
    completed_by_number = {row["reference_number"]: row for row in completed_pairs}
    comparison = []
    lines = []
    for reference_row in references:
        number = reference_row["number"]
        old, new = baseline_by_number.get(number), completed_by_number.get(number)
        comparison.append({
            "reference_number": number, "artist": reference_row["artist"], "title": reference_row["title"],
            "youtube_reference_seconds": reference_row["youtube_time_seconds"],
            "baseline_status": "trouve" if old else "non trouve",
            "baseline_youtube_algorithm_seconds": old["youtube_algorithm_seconds"] if old else None,
            "baseline_absolute_error_seconds": old["absolute_error_seconds"] if old else None,
            "initial_plus_voice_status": "trouve" if new else "non trouve",
            "initial_plus_voice_detection_id": new["detection_id"] if new else None,
            "initial_plus_voice_origin": new["origin"] if new else None,
            "initial_plus_voice_youtube_algorithm_seconds": new["youtube_algorithm_seconds"] if new else None,
            "initial_plus_voice_absolute_error_seconds": new["absolute_error_seconds"] if new else None,
        })
        algo = clock(new["youtube_algorithm_seconds"]) if new else "non trouve"
        lines.append(f"{clock(reference_row['youtube_time_seconds'])} | {algo}")
    summary = {
        "episode_id": frozen["episode_id"], "frozen_outputs_verified_by_sha256": True,
        "matching_rule_identical": True, "matching_tolerance_seconds": TOLERANCE,
        "alignment": {"split_youtube_seconds": SPLIT_YOUTUBE,
                      "offset_before_seconds": OFFSET_BEFORE, "offset_after_seconds": OFFSET_AFTER},
        "baseline_initial": metrics(baseline_pairs, baseline_missing, baseline_surplus, len(baseline)),
        "initial_plus_voice": metrics(completed_pairs, completed_missing, completed_surplus, len(completed)),
        "independent_titles_identified": 0,
        "verdict": "gain" if len(completed_pairs) > len(baseline_pairs)
                   else "recul" if len(completed_pairs) < len(baseline_pairs) else "aucun gain",
    }
    outputs = {
        "fyh512-initial-baseline-pairs": baseline_pairs,
        "fyh512-initial-baseline-missing": baseline_missing,
        "fyh512-initial-baseline-surplus": baseline_surplus,
        "fyh512-initial-plus-voice-pairs": completed_pairs,
        "fyh512-initial-plus-voice-missing": completed_missing,
        "fyh512-initial-plus-voice-surplus": completed_surplus,
        "fyh512-initial-plus-voice-comparison-32": comparison,
    }
    for name, rows in outputs.items():
        write_json(RESULTS / f"{name}.json", rows)
        write_csv(RESULTS / f"{name}.csv", rows)
    write_json(RESULTS / "fyh512-initial-plus-voice-evaluation-summary.json", summary)
    (RESULTS / "fyh512-initial-plus-voice-32-lines.txt").write_text(
        "YT | ALGO\n" + "\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
