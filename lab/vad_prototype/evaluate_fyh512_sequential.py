#!/usr/bin/env python3
"""Compare les sorties séquentielles gelées à la référence déjà connue."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def reference_audio_time(track: dict, alignment: dict) -> float:
    offset = alignment["offset_before_seconds"] if track["youtube_time_seconds"] < alignment["split_youtube_seconds"] else alignment["offset_after_seconds"]
    return round(track["youtube_time_seconds"] + offset, 3)


def main() -> int:
    summary = json.loads((RESULTS / "fyh512-sequential-summary-frozen.json").read_text(encoding="utf-8"))
    if summary["reference_times_loaded"] is not False:
        raise ValueError("Sortie non gelee")
    for name, expected in summary["output_sha256"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelee modifiee: {name}")
    reference = json.loads((ROOT / "fyh512_reference.json").read_text(encoding="utf-8"))
    normalized_alignment = json.loads((RESULTS / "fyh512-alignment-normalized.json").read_text(encoding="utf-8"))
    observations = normalized_alignment["observations"]
    split = reference["audio_alignment"]["split_youtube_seconds"]
    before = statistics.median(row["rss_equals_youtube_plus_offset_seconds"] for row in observations if row["rss_probe_seconds"] < split)
    after = statistics.median(row["rss_equals_youtube_plus_offset_seconds"] for row in observations if row["rss_probe_seconds"] >= split)
    evaluation_alignment = {"split_youtube_seconds": split, "offset_before_seconds": before, "offset_after_seconds": after}
    intervals = json.loads((RESULTS / "fyh512-sequential-intervals-frozen.json").read_text(encoding="utf-8"))
    transitions = json.loads((RESULTS / "fyh512-sequential-transitions-frozen.json").read_text(encoding="utf-8"))
    identifications = json.loads((RESULTS / "fyh512-sequential-identifications-frozen.json").read_text(encoding="utf-8"))
    asr_mentions = json.loads((RESULTS / "fyh512-sequential-asr-mentions-frozen.json").read_text(encoding="utf-8"))
    old_summary = json.loads((RESULTS / "fyh512-evaluation-summary.json").read_text(encoding="utf-8"))

    detected = []
    for interval in intervals[1:]:
        detected.append({"time": interval["start_seconds"], "origin": "reliable_voice_interval_boundary", "id": interval["start_source"]})
    for transition in transitions:
        detected.append({"time": transition["absolute_time_seconds"], "origin": "interval_local_spectral", "id": transition["transition_id"]})
    detected.sort(key=lambda row: row["time"])
    references = [{**track, "audio_time": reference_audio_time(track, evaluation_alignment)} for track in reference["tracks"]]

    pairs = []
    unused_detections, unused_references = set(range(len(detected))), set(range(len(references)))
    candidates = sorted(
        (abs(detected[di]["time"] - references[ri]["audio_time"]), di, ri)
        for di in unused_detections for ri in unused_references
        if abs(detected[di]["time"] - references[ri]["audio_time"]) <= 30.0
    )
    for absolute, di, ri in candidates:
        if di not in unused_detections or ri not in unused_references:
            continue
        unused_detections.remove(di)
        unused_references.remove(ri)
        signed = round(detected[di]["time"] - references[ri]["audio_time"], 3)
        pairs.append({
            "detection_id": detected[di]["id"], "detection_origin": detected[di]["origin"],
            "detected_time_seconds": round(detected[di]["time"], 6), "reference_track_number": references[ri]["number"],
            "reference_artist": references[ri]["artist"], "reference_title": references[ri]["title"],
            "reference_audio_time_seconds": references[ri]["audio_time"], "signed_error_seconds": signed,
            "absolute_error_seconds": abs(signed), "matching_tolerance_seconds": 30.0,
        })
    pairs.sort(key=lambda row: row["reference_track_number"])
    missing = [{"number": references[i]["number"], "artist": references[i]["artist"],
                "title": references[i]["title"], "audio_time_seconds": references[i]["audio_time"]}
               for i in sorted(unused_references)]
    surplus = [{"detection_id": detected[i]["id"], "origin": detected[i]["origin"],
                "time_seconds": detected[i]["time"]} for i in sorted(unused_detections)]

    identification_rows = []
    for row in identifications:
        expected = references[row["candidate_number"] - 1]
        identification_rows.append({
            **row, "reference_audio_time_seconds": expected["audio_time"],
            "anchor_error_seconds": round(row["anchor_seconds"] - expected["audio_time"], 3),
            "candidate_title_matches_reference": row["title"] == expected["title"],
            "evaluation_warning": "candidat connu en entree; ce match ne mesure pas une identification independante",
        })
    asr_rows = []
    for row in asr_mentions:
        expected = references[row["candidate_number"] - 1]
        asr_rows.append({
            **row, "candidate_exists_in_reference": row["candidate_title"] == expected["title"],
            "reference_audio_time_seconds": expected["audio_time"],
            "identification_counted": False,
            "reason": "abstention gelee sur orientation precedent/suivant/liste multiple",
        })

    errors = [row["absolute_error_seconds"] for row in pairs]
    evaluation_summary = {
        "episode_id": "find-your-harmony-512", "known_reference_not_blind": True,
        "frozen_outputs_verified_by_sha256": True, "matching_tolerance_seconds": 30.0,
        "normalized_alignment_used": evaluation_alignment,
        "alignment_precision_warning": "offsets medians conditionnels aux decodages PCM; valeurs publiees au centieme, pas a la milliseconde",
        "reference_tracks": len(references), "detected_boundaries_excluding_audio_start": len(detected),
        "matched_boundaries": len(pairs), "missing_reference_boundaries": len(missing),
        "surplus_detected_boundaries": len(surplus),
        "mean_absolute_error_seconds_on_matched": round(statistics.mean(errors), 3) if errors else None,
        "median_absolute_error_seconds_on_matched": round(statistics.median(errors), 3) if errors else None,
        "matched_under_1_second": sum(error < 1 for error in errors),
        "matched_under_3_seconds": sum(error < 3 for error in errors),
        "matched_under_10_seconds": sum(error < 10 for error in errors),
        "independent_titles_identified": 0, "independent_titles_missing": 32,
        "asr_candidate_mentions": len(asr_rows), "asr_titles_counted_as_identified": 0,
        "assisted_candidate_landmark_presences": len(identification_rows),
        "assisted_candidate_labels_matching_reference": sum(row["candidate_title_matches_reference"] for row in identification_rows),
        "old_global_spectral": {
            "boundaries": old_summary["algorithm_boundaries"],
            "missing": old_summary["missing_reference_boundaries"],
            "surplus": old_summary["surplus_algorithm_boundaries"],
            "mean_absolute_error_seconds_paired_by_forced_order": old_summary["mean_absolute_error_seconds"],
            "median_absolute_error_seconds_paired_by_forced_order": old_summary["median_absolute_error_seconds"],
            "warning": "Ancienne cardinalite imposee a 32 et appariement par ordre; metriques non directement comparables au matching libre de la variante sequentielle."
        },
        "causal_conclusion": "Aucun gain causal attribuable aux voix n'est demontre: protocole, cardinalite et appariement different. Les voix garantissent seulement la partition effective et quatre bornes candidates conservees.",
    }
    outputs = {
        "fyh512-sequential-evaluation-pairs": pairs,
        "fyh512-sequential-evaluation-missing": missing,
        "fyh512-sequential-evaluation-surplus": surplus,
        "fyh512-sequential-identification-evaluation": identification_rows,
        "fyh512-sequential-asr-evaluation": asr_rows,
    }
    for name, rows in outputs.items():
        (RESULTS / f"{name}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if rows:
            write_csv(RESULTS / f"{name}.csv", rows)
    (RESULTS / "fyh512-sequential-evaluation-summary.json").write_text(
        json.dumps(evaluation_summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evaluation_summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
