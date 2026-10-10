#!/usr/bin/env python3
"""Evaluate already-frozen existing-engine outputs against published times."""

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


def rss_reference(track: dict) -> float:
    offset = OFFSET_BEFORE if track["youtube_time_seconds"] < SPLIT_YOUTUBE else OFFSET_AFTER
    return round(track["youtube_time_seconds"] + offset, 4)


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
        unused_d.remove(di)
        unused_r.remove(ri)
        detection, reference = detections[di], references[ri]
        signed = round(detection["rss_time_seconds"] - reference["rss_time_seconds"], 4)
        pairs.append({
            "reference_number": reference["number"], "artist": reference["artist"],
            "title": reference["title"], "youtube_reference_seconds": reference["youtube_time_seconds"],
            "rss_reference_seconds": reference["rss_time_seconds"], "algorithm_track_number": detection["number"],
            "rss_algorithm_seconds": detection["rss_time_seconds"],
            "algorithm_brought_to_youtube_seconds": rss_to_youtube(detection["rss_time_seconds"]),
            "signed_error_seconds": signed, "absolute_error_seconds": abs(signed),
        })
    pairs.sort(key=lambda row: row["reference_number"])
    return pairs, [references[i] for i in sorted(unused_r)], [detections[i] for i in sorted(unused_d, key=lambda i: detections[i]["rss_time_seconds"])]


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
    manifest_path = RESULTS / "fyh512-existing-engine-freeze-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["reference_times_loaded"] is not False:
        raise ValueError("Le calcul n'a pas été gelé avant comparaison")
    for name, expected in manifest["output_sha256"].items():
        if sha256(RESULTS / name) != expected:
            raise ValueError(f"Sortie gelée modifiée: {name}")

    frozen = json.loads((RESULTS / "fyh512-existing-engine-results-frozen.json").read_text(encoding="utf-8"))
    reference = json.loads((ROOT / "fyh512_reference.json").read_text(encoding="utf-8"))
    references = [{**row, "rss_time_seconds": rss_reference(row)} for row in reference["tracks"]]
    detections = [
        {"number": index, "artist": row["artist"], "title": row["title"],
         "rss_time_seconds": float(row["providedTime"]), "confidence": row["confidence"]}
        for index, row in enumerate(frozen, 1)
    ]
    pairs, missing, surplus = match_free(detections, references)
    by_reference = {row["reference_number"]: row for row in pairs}
    comparison = []
    lines = []
    for row in references:
        pair = by_reference.get(row["number"])
        algo = pair["algorithm_brought_to_youtube_seconds"] if pair else None
        comparison.append({
            "reference_number": row["number"], "artist": row["artist"], "title": row["title"],
            "youtube_reference_seconds": row["youtube_time_seconds"],
            "algorithm_youtube_seconds": algo, "status": "trouve" if pair else "non trouve",
            "absolute_error_seconds": pair["absolute_error_seconds"] if pair else None,
        })
        lines.append(f'{round(row["youtube_time_seconds"]):d}|{round(algo):d}' if algo is not None else f'{round(row["youtube_time_seconds"]):d}|non trouvé')
    errors = [row["absolute_error_seconds"] for row in pairs]
    summary = {
        "known_reference_not_blind": True,
        "frozen_outputs_verified_by_sha256": True,
        "recognition_classification": "reconnaissance assistee par references connues",
        "matching_rule": "appariement libre glouton un-a-un par erreur absolue croissante",
        "matching_tolerance_seconds": TOLERANCE,
        "alignment": {"split_youtube_seconds": SPLIT_YOUTUBE, "offset_before_seconds": OFFSET_BEFORE,
                      "offset_after_seconds": OFFSET_AFTER, "limits": "offsets estimes; timestamps YouTube publies a la seconde"},
        "reference_count": len(references), "algorithm_output_count": len(detections),
        "matched": len(pairs), "missing": len(missing), "surplus": len(surplus),
        "mean_absolute_error_seconds_on_matched": round(statistics.mean(errors), 4) if errors else None,
        "median_absolute_error_seconds_on_matched": round(statistics.median(errors), 4) if errors else None,
        "matched_under_1_second": sum(value < 1 for value in errors),
        "matched_under_5_seconds": sum(value < 5 for value in errors),
        "matched_under_10_seconds": sum(value < 10 for value in errors),
        "matched_under_30_seconds": sum(value < 30 for value in errors),
        "statistics_denominator": len(pairs),
    }
    outputs = {
        "fyh512-existing-engine-pairs": pairs, "fyh512-existing-engine-missing": missing,
        "fyh512-existing-engine-surplus": surplus, "fyh512-existing-engine-comparison-32": comparison,
    }
    for name, rows in outputs.items():
        write_json(RESULTS / f"{name}.json", rows); write_csv(RESULTS / f"{name}.csv", rows)
    write_json(RESULTS / "fyh512-existing-engine-evaluation-summary.json", summary)
    (RESULTS / "fyh512-existing-engine-32-lines.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
