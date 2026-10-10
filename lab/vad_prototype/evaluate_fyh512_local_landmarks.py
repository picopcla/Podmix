#!/usr/bin/env python3
"""Évalue après gel la variante locale et son baseline avec la même règle."""

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
    keys: list[str] = []
    for row in rows:
        for key in row:
            if key not in keys:
                keys.append(key)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def reference_rss(track: dict) -> float:
    offset = OFFSET_BEFORE if track["youtube_time_seconds"] < SPLIT_YOUTUBE else OFFSET_AFTER
    return round(track["youtube_time_seconds"] + offset, 4)


def rss_to_youtube(value: float) -> float:
    offset = OFFSET_BEFORE if value < SPLIT_YOUTUBE + OFFSET_BEFORE else OFFSET_AFTER
    return round(value - offset, 4)


def match_free(detections: list[dict], references: list[dict]) -> tuple[list[dict], list[dict], list[dict]]:
    unused_detections, unused_references = set(range(len(detections))), set(range(len(references)))
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
        pairs.append({"reference_number": reference["number"], "artist": reference["artist"],
                      "title": reference["title"], "youtube_reference_seconds": reference["youtube_time_seconds"],
                      "rss_reference_seconds": reference["rss_time_seconds"],
                      "detection_id": detection["detection_id"], "origin": detection["origin"],
                      "rss_algorithm_seconds": detection["rss_time_seconds"],
                      "algorithm_brought_to_youtube_seconds": rss_to_youtube(detection["rss_time_seconds"]),
                      "signed_error_seconds": signed, "absolute_error_seconds": abs(signed)})
    pairs.sort(key=lambda row: row["reference_number"])
    missing = [references[index] for index in sorted(unused_references)]
    surplus = [detections[index] for index in sorted(unused_detections,
                                                     key=lambda item: detections[item]["rss_time_seconds"])]
    return pairs, missing, surplus


def metrics(pairs: list[dict], missing: list[dict], surplus: list[dict], detection_count: int) -> dict:
    errors = [row["absolute_error_seconds"] for row in pairs]
    return {"reference_count": len(pairs) + len(missing), "detection_count": detection_count,
            "matches_within_30_seconds": len(pairs), "missing": len(missing), "surplus": len(surplus),
            "mean_absolute_error_seconds_on_matches": round(statistics.mean(errors), 4) if errors else None,
            "median_absolute_error_seconds_on_matches": round(statistics.median(errors), 4) if errors else None,
            "matches_under_1_second": sum(value < 1 for value in errors),
            "matches_under_5_seconds": sum(value < 5 for value in errors),
            "matches_under_10_seconds": sum(value < 10 for value in errors),
            "matches_under_30_seconds": sum(value < 30 for value in errors),
            "threshold_denominator": len(pairs)}


def main() -> int:
    frozen = json.loads((RESULTS / "fyh512-local-landmarks-summary-frozen.json").read_text(encoding="utf-8"))
    if frozen["reference_times_loaded"] is not False:
        raise ValueError("Sortie non gelée avant référence")
    for filename, expected in frozen["output_sha256"].items():
        if sha256(RESULTS / filename) != expected:
            raise ValueError(f"Sortie gelée modifiée: {filename}")

    reference = json.loads((ROOT / "fyh512_reference.json").read_text(encoding="utf-8"))
    references = [{**track, "rss_time_seconds": reference_rss(track)} for track in reference["tracks"]]
    intervals = json.loads((RESULTS / "fyh512-local-landmarks-intervals-frozen.json").read_text(encoding="utf-8"))
    voice = [{"detection_id": f"V{index:03d}", "origin": "frozen_reliable_voice_boundary",
              "rss_time_seconds": row["start_seconds"]} for index, row in enumerate(intervals[1:], 1)]
    new_transitions = json.loads((RESULTS / "fyh512-local-landmarks-transitions-frozen.json").read_text(encoding="utf-8"))
    baseline_transitions = json.loads((RESULTS / "fyh512-local-landmarks-baseline-transitions-frozen.json").read_text(encoding="utf-8"))
    new_detections = sorted(voice + [{"detection_id": row["transition_id"], "origin": row["origin"],
                                     "rss_time_seconds": row["absolute_time_seconds"]} for row in new_transitions],
                            key=lambda row: row["rss_time_seconds"])
    baseline_detections = sorted(voice + [{"detection_id": row["transition_id"], "origin": row["origin"],
                                          "rss_time_seconds": row["absolute_time_seconds"]} for row in baseline_transitions],
                                 key=lambda row: row["rss_time_seconds"])
    new_pairs, new_missing, new_surplus = match_free(new_detections, references)
    baseline_pairs, baseline_missing, baseline_surplus = match_free(baseline_detections, references)
    new_by_reference = {row["reference_number"]: row for row in new_pairs}
    baseline_by_reference = {row["reference_number"]: row for row in baseline_pairs}
    comparison = []
    for reference_row in references:
        number = reference_row["number"]
        new, old = new_by_reference.get(number), baseline_by_reference.get(number)
        comparison.append({
            "reference_number": number, "artist": reference_row["artist"], "title": reference_row["title"],
            "youtube_reference_seconds": reference_row["youtube_time_seconds"],
            "rss_reference_seconds": reference_row["rss_time_seconds"],
            "new_status": "trouve" if new else "non trouve",
            "new_detection_id": new["detection_id"] if new else None,
            "new_origin": new["origin"] if new else None,
            "new_rss_algorithm_seconds": new["rss_algorithm_seconds"] if new else None,
            "new_algorithm_brought_to_youtube_seconds": new["algorithm_brought_to_youtube_seconds"] if new else None,
            "new_signed_error_seconds": new["signed_error_seconds"] if new else None,
            "new_absolute_error_seconds": new["absolute_error_seconds"] if new else None,
            "baseline_status": "trouve" if old else "non trouve",
            "baseline_detection_id": old["detection_id"] if old else None,
            "baseline_rss_algorithm_seconds": old["rss_algorithm_seconds"] if old else None,
            "baseline_algorithm_brought_to_youtube_seconds": old["algorithm_brought_to_youtube_seconds"] if old else None,
            "baseline_absolute_error_seconds": old["absolute_error_seconds"] if old else None,
        })

    presences = json.loads((RESULTS / "fyh512-local-landmarks-presences-frozen.json").read_text(encoding="utf-8"))
    presence_evaluation = []
    for row in presences:
        candidate = references[row["candidate_number"] - 1]
        next_start = (references[row["candidate_number"]]["rss_time_seconds"]
                      if row["candidate_number"] < len(references) else frozen["decoded_duration_seconds"])
        midpoint = (row["presence_start_absolute_seconds"] + row["presence_end_absolute_seconds"]) / 2
        plausible = candidate["rss_time_seconds"] - TOLERANCE <= midpoint <= next_start + TOLERANCE
        presence_evaluation.append({**row, "presence_midpoint_rss_seconds": round(midpoint, 4),
                                    "candidate_reference_start_rss_seconds": candidate["rss_time_seconds"],
                                    "next_reference_start_rss_seconds": next_start,
                                    "timing_plausible_with_known_reference": plausible,
                                    "evaluation_warning": "titre connu en entree; ne mesure pas une identification independante"})

    summary = {
        "episode_id": frozen["episode_id"], "frozen_outputs_verified_by_sha256": True,
        "known_reference_not_blind": True, "matching_tolerance_seconds": TOLERANCE,
        "matching_rule_identical": True,
        "alignment": {"split_youtube_seconds": SPLIT_YOUTUBE, "offset_before_seconds": OFFSET_BEFORE,
                      "offset_after_seconds": OFFSET_AFTER,
                      "warning": "offsets medians conditionnels aux decodages"},
        "new_anchor_constrained_with_fallback": metrics(new_pairs, new_missing, new_surplus, len(new_detections)),
        "precedent_spectral_local": metrics(baseline_pairs, baseline_missing, baseline_surplus, len(baseline_detections)),
        "method_change": {"new_anchor_constrained_transitions": frozen["anchor_constrained_transitions"],
                          "new_fallback_transitions": frozen["fallback_local_transitions"],
                          "baseline_local_transitions": frozen["baseline_local_transitions"]},
        "recognition": {"candidate_titles": frozen["candidate_titles"],
                        "previews_available": frozen["candidate_previews_available"],
                        "previews_missing": frozen["candidate_previews_missing"],
                        "interval_candidate_requests": frozen["local_recognition_attempts"],
                        "accepted_assisted_presences": len(presences),
                        "assisted_presence_timing_plausible": sum(row["timing_plausible_with_known_reference"] for row in presence_evaluation),
                        "assisted_interval_candidate_abstentions": frozen["local_recognition_attempts"] - len(presences),
                        "independent_titles_identified": 0,
                        "independent_titles_abstentions": 32},
        "causal_conclusion": "Comparaison descriptive seulement: les ancres modifient des choix mais ne prouvent pas que les voix causent un gain. Aucun gain ne doit etre annonce sans test supplementaire.",
    }
    outputs = {"fyh512-local-landmarks-comparison-32": comparison,
               "fyh512-local-landmarks-new-pairs": new_pairs,
               "fyh512-local-landmarks-new-missing": new_missing,
               "fyh512-local-landmarks-new-surplus": new_surplus,
               "fyh512-local-landmarks-baseline-pairs": baseline_pairs,
               "fyh512-local-landmarks-baseline-missing": baseline_missing,
               "fyh512-local-landmarks-baseline-surplus": baseline_surplus,
               "fyh512-local-landmarks-presence-evaluation": presence_evaluation}
    for name, rows in outputs.items():
        write_json(RESULTS / f"{name}.json", rows)
        write_csv(RESULTS / f"{name}.csv", rows)
    write_json(RESULTS / "fyh512-local-landmarks-evaluation-summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
