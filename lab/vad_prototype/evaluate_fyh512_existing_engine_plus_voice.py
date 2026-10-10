#!/usr/bin/env python3
"""Evaluate frozen existing-engine plus voice outputs after the freeze."""

from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import statistics


LAB = Path(__file__).resolve().parent
RESULTS = LAB / "results"
TOLERANCE = 30.0
SPLIT_YOUTUBE = 3565.0
OFFSET_BEFORE = 1.3455
OFFSET_AFTER = 1.7265


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rss_reference(row: dict) -> float:
    offset = OFFSET_BEFORE if row["youtube_time_seconds"] < SPLIT_YOUTUBE else OFFSET_AFTER
    return round(row["youtube_time_seconds"] + offset, 4)


def rss_to_youtube(value: float) -> float:
    offset = OFFSET_BEFORE if value < SPLIT_YOUTUBE + OFFSET_BEFORE else OFFSET_AFTER
    return round(value - offset, 4)


def match_free(detections: list[dict], references: list[dict]):
    unused_d, unused_r = set(range(len(detections))), set(range(len(references)))
    candidates = sorted(
        (abs(detections[di]["rss_time_seconds"] - references[ri]["rss_time_seconds"]), di, ri)
        for di in unused_d for ri in unused_r
        if abs(detections[di]["rss_time_seconds"] - references[ri]["rss_time_seconds"]) <= TOLERANCE
    )
    pairs = []
    for _, di, ri in candidates:
        if di not in unused_d or ri not in unused_r:
            continue
        unused_d.remove(di); unused_r.remove(ri)
        detection, reference = detections[di], references[ri]
        error = round(detection["rss_time_seconds"] - reference["rss_time_seconds"], 4)
        pairs.append({
            "reference_number": reference["number"], "artist": reference["artist"], "title": reference["title"],
            "youtube_reference_seconds": reference["youtube_time_seconds"],
            "algorithm_track_number": detection["number"], "rss_algorithm_seconds": detection["rss_time_seconds"],
            "algorithm_youtube_seconds": rss_to_youtube(detection["rss_time_seconds"]),
            "signed_error_seconds": error, "absolute_error_seconds": abs(error),
        })
    pairs.sort(key=lambda row: row["reference_number"])
    missing = [references[index] for index in sorted(unused_r)]
    surplus = [detections[index] for index in sorted(unused_d, key=lambda index: detections[index]["rss_time_seconds"])]
    return pairs, missing, surplus


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=keys, lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)


def main() -> int:
    manifest = json.loads((RESULTS / "fyh512-existing-engine-plus-voice-freeze-manifest.json").read_text())
    if manifest["reference_times_loaded"] is not False:
        raise ValueError("Les sorties n'ont pas ete gelees avant evaluation")
    for name, expected in manifest["outputs"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelee modifiee: {name}")

    reference_data = json.loads((LAB / "fyh512_reference.json").read_text())
    references = [{**row, "rss_time_seconds": rss_reference(row)} for row in reference_data["tracks"]]

    def evaluate(name: str):
        frozen = json.loads((RESULTS / name).read_text())
        detections = [{"number": index, "artist": row["artist"], "title": row["title"],
                       "rss_time_seconds": float(row["providedTime"]), "confidence": row["confidence"]}
                      for index, row in enumerate(frozen, 1)]
        return (*match_free(detections, references), detections)

    baseline_pairs, baseline_missing, baseline_surplus, _ = evaluate("fyh512-existing-engine-voice-disabled-frozen.json")
    pairs, missing, surplus, detections = evaluate("fyh512-existing-engine-plus-voice-frozen.json")
    baseline_by_ref = {row["reference_number"]: row for row in baseline_pairs}
    by_ref = {row["reference_number"]: row for row in pairs}
    comparison = []
    lines = []
    for reference in references:
        pair = by_ref.get(reference["number"])
        baseline_pair = baseline_by_ref.get(reference["number"])
        if pair and not baseline_pair:
            delta = "gagne"
        elif baseline_pair and not pair:
            delta = "perdu"
        elif pair and baseline_pair:
            delta = "conserve"
        else:
            delta = "toujours_non_trouve"
        algorithm = pair["algorithm_youtube_seconds"] if pair else None
        comparison.append({
            "reference_number": reference["number"], "artist": reference["artist"], "title": reference["title"],
            "youtube_reference_seconds": reference["youtube_time_seconds"],
            "algorithm_youtube_seconds": algorithm, "status": "trouve" if pair else "non trouve",
            "absolute_error_seconds": pair["absolute_error_seconds"] if pair else None, "versus_baseline": delta,
        })
        lines.append(f'{round(reference["youtube_time_seconds"]):d}|{round(algorithm):d}' if algorithm is not None
                     else f'{round(reference["youtube_time_seconds"]):d}|non trouvé')
    errors = [row["absolute_error_seconds"] for row in pairs]
    gained = [row["reference_number"] for row in comparison if row["versus_baseline"] == "gagne"]
    lost = [row["reference_number"] for row in comparison if row["versus_baseline"] == "perdu"]
    baseline_trace = json.loads((RESULTS / "fyh512-existing-engine-trace-frozen.json").read_text())
    baseline_inventory = {
        item["url"]: item.get("sha256") for row in baseline_trace["references"]
        for item in row["downloads"] if item.get("status") == "obtained"
    }
    current_inventory = {item["url"]: item["sha256"] for item in manifest["reference_inventory"]}
    changed_references = sorted(
        url for url in set(baseline_inventory) | set(current_inventory)
        if baseline_inventory.get(url) != current_inventory.get(url)
    )
    summary = {
        "known_reference_not_blind": True, "frozen_outputs_verified_by_sha256": True,
        "recognition_classification": "moteur existant assiste par tracklist connue plus interpretation automatique des annonces",
        "matching_rule": "appariement libre glouton un-a-un par erreur absolue croissante",
        "matching_tolerance_seconds": TOLERANCE,
        "alignment": {"split_youtube_seconds": SPLIT_YOUTUBE, "offset_before_seconds": OFFSET_BEFORE,
                      "offset_after_seconds": OFFSET_AFTER},
        "baseline": {"matched": len(baseline_pairs), "missing": len(baseline_missing), "surplus": len(baseline_surplus)},
        "existing_engine_plus_voice": {"reference_count": len(references), "algorithm_output_count": len(detections),
            "matched": len(pairs), "missing": len(missing), "surplus": len(surplus),
            "mean_absolute_error_seconds_on_matched": round(statistics.mean(errors), 4),
            "median_absolute_error_seconds_on_matched": round(statistics.median(errors), 4),
            "matched_under_1_second": sum(value < 1 for value in errors),
            "matched_under_5_seconds": sum(value < 5 for value in errors),
            "matched_under_10_seconds": sum(value < 10 for value in errors),
            "matched_under_30_seconds": sum(value < 30 for value in errors),
            "statistics_denominator": len(pairs)},
        "gained_reference_numbers": gained, "lost_reference_numbers": lost,
        "reference_inventory": {
            "baseline_obtained_files": len(baseline_inventory),
            "current_obtained_files": len(current_inventory),
            "identical_hashes": not changed_references,
            "changed_public_paths": changed_references,
            "causality_note": (
                "Cinq fichiers SoundCloud ont change de hash; les deux modes de cette comparaison utilisent toutefois "
                "exactement le meme cache courant, et le mode desactive reproduit le JSON baseline au hash exact."
            ),
        },
        "verdict": "gain" if len(pairs) > len(baseline_pairs) else "recul" if len(pairs) < len(baseline_pairs) else "aucun_gain_net",
    }
    outputs = {
        "fyh512-existing-engine-plus-voice-pairs": pairs,
        "fyh512-existing-engine-plus-voice-missing": missing,
        "fyh512-existing-engine-plus-voice-surplus": surplus,
        "fyh512-existing-engine-plus-voice-comparison-32": comparison,
    }
    for name, rows in outputs.items():
        write_json(RESULTS / f"{name}.json", rows); write_csv(RESULTS / f"{name}.csv", rows)
    write_json(RESULTS / "fyh512-existing-engine-plus-voice-evaluation-summary.json", summary)
    (RESULTS / "fyh512-existing-engine-plus-voice-32-lines.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
