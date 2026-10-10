#!/usr/bin/env python3
"""Create conservative per-method and consensus suggestions; never alter app data."""

from __future__ import annotations

import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "output"
RESULTS = ROOT / "results"


def containing(segments: list[dict], label: str, local_time: float):
    return next(
        (s for s in segments if s["label"] == label and s["start"] <= local_time <= s["end"]),
        None,
    )


def next_segment(segments: list[dict], segment: dict):
    try:
        return segments[segments.index(segment) + 1]
    except (ValueError, IndexError):
        return None


def compact_segments(record: dict) -> list[dict]:
    offset = record["window"][0]
    return [
        {
            "label": s["label"],
            "start": round(offset + s["start"], 3),
            "end": round(offset + s["end"], 3),
        }
        for s in record["segments"]
    ]


def base_row(record: dict, method: str) -> dict:
    boundary = record["id"]
    clip_root = f"output/excerpts/{boundary}"
    return {
        "boundary_id": boundary,
        "episode_id": record["episode_id"],
        "episode_title": record["episode_title"],
        "source_url": record["source_url"],
        "tracklist_url": record["tracklist_url"],
        "track_number": record["track_number"],
        "track_title": record["track_title"],
        "split": record["split"],
        "original_time_seconds": record["original_time"],
        "window_start_seconds": record["window"][0],
        "window_end_seconds": record["window"][1],
        "method": method,
        "excerpt_before": f"{clip_root}-before.ogg",
        "excerpt_after": f"{clip_root}-after.ogg",
        "automatic_candidate": False,
        "candidate_time_seconds": None,
        "candidate_delta_seconds": None,
    }


def main() -> int:
    config = json.loads((ROOT / "episodes.json").read_text())
    policy = config["protocol"]["suggestion_policy"]
    silero_files = sorted((OUTPUT / "raw" / "silero").glob("*.json"))
    rows = []
    counts = {}
    for silero_file in silero_files:
        ina_file = OUTPUT / "raw" / "ina" / silero_file.name
        if not ina_file.exists():
            continue
        silero = json.loads(silero_file.read_text())
        ina = json.loads(ina_file.read_text())
        local_time = ina["original_time"] - ina["window"][0]
        ina_speech = containing(ina["segments"], "speech", local_time)
        ina_next = next_segment(ina["segments"], ina_speech) if ina_speech else None
        silero_speech = containing(silero["segments"], "speech", local_time)

        for record, method in ((ina, "ina"), (silero, "silero")):
            row = base_row(record, method)
            row["segments"] = compact_segments(record)
            if method == "ina" and ina_speech and ina_next and ina_next["label"] == "music":
                delta = ina_speech["end"] - local_time
                music_duration = ina_next["end"] - ina_next["start"]
                eligible = (
                    policy["min_positive_delta_seconds"] <= delta <= policy["max_positive_delta_seconds"]
                    and music_duration >= policy["min_return_to_music_seconds"]
                )
                if eligible:
                    candidate_time = round(record["original_time"] + delta, 3)
                    human_confirmed = record["id"] in policy.get("human_transition_confirmed_boundaries", [])
                    if policy.get("requires_human_transition_confirmation", False) and not human_confirmed:
                        row.update(
                            decision="abstention", new_time_seconds=None, delta_seconds=None,
                            automatic_candidate=True,
                            candidate_time_seconds=candidate_time,
                            candidate_delta_seconds=round(delta, 3),
                            reason=(
                                "Candidat automatique INA parole-vers-musique non confirme par ecoute humaine; "
                                "chant, rap, jingle ou voice-over ne peuvent pas etre exclus."
                            ),
                            uncertainty="elevee_sans_ecoute_humaine",
                        )
                    else:
                        row.update(
                            decision="suggestion",
                            new_time_seconds=candidate_time,
                            delta_seconds=round(delta, 3),
                            reason="INA classe le temps original dans une plage parole, puis un retour continu a la musique; transition confirmee humainement.",
                            uncertainty="moyenne",
                        )
                else:
                    row.update(
                        decision="abstention", new_time_seconds=None, delta_seconds=None,
                        reason="Le motif parole-vers-musique ne respecte pas les seuils conservateurs geles.",
                        uncertainty="elevee",
                    )
            elif method == "silero":
                row.update(
                    decision="abstention", new_time_seconds=None, delta_seconds=None,
                    reason=(
                        "Silero detecte une parole au temps original mais ne distingue pas musique et non-parole; retour musique non confirme."
                        if silero_speech else
                        "Silero ne place pas le temps original dans une plage de parole."
                    ),
                    uncertainty="elevee",
                )
            else:
                row.update(
                    decision="abstention", new_time_seconds=None, delta_seconds=None,
                    reason="INA ne confirme pas une parole englobant le temps original suivie immediatement de musique.",
                    uncertainty="moyenne_sans_ecoute_humaine",
                )
            rows.append(row)

        consensus = base_row(ina, "consensus_ina_silero")
        consensus["segments"] = {
            "ina": compact_segments(ina),
            "silero": compact_segments(silero),
        }
        delta = ina_speech["end"] - local_time if ina_speech else None
        music_ok = bool(
            ina_next and ina_next["label"] == "music"
            and ina_next["end"] - ina_next["start"] >= policy["min_return_to_music_seconds"]
        )
        models_agree = bool(
            ina_speech and silero_speech
            and abs(ina_speech["end"] - silero_speech["end"])
            <= policy["max_model_end_disagreement_seconds"]
        )
        eligible = bool(
            delta is not None and music_ok and models_agree
            and policy["min_positive_delta_seconds"] <= delta <= policy["max_positive_delta_seconds"]
        )
        if eligible:
            candidate_time = round(ina["original_time"] + delta, 3)
            human_confirmed = ina["id"] in policy.get("human_transition_confirmed_boundaries", [])
            if policy.get("requires_human_transition_confirmation", False) and not human_confirmed:
                consensus.update(
                    decision="abstention", new_time_seconds=None, delta_seconds=None,
                    automatic_candidate=True,
                    candidate_time_seconds=candidate_time,
                    candidate_delta_seconds=round(delta, 3),
                    reason=(
                        "Candidat automatique: parole englobante et fin proches pour INA/Silero, puis musique INA; "
                        "abstention sans ecoute humaine capable d'exclure chant, rap, jingle ou voice-over."
                    ),
                    uncertainty="elevee_sans_ecoute_humaine",
                )
            else:
                consensus.update(
                    decision="suggestion",
                    new_time_seconds=candidate_time,
                    delta_seconds=round(delta, 3),
                    reason="Parole englobant la frontiere confirmee par les deux modeles; INA confirme ensuite au moins 5 s de musique; transition confirmee humainement.",
                    uncertainty="moyenne",
                )
        else:
            reasons = []
            if not ina_speech:
                reasons.append("INA ne voit pas de parole englobante")
            if not silero_speech:
                reasons.append("Silero ne voit pas de parole englobante")
            if not music_ok:
                reasons.append("retour musique INA insuffisant")
            if ina_speech and silero_speech and not models_agree:
                reasons.append("fins de parole en desaccord de plus de 2 s")
            if delta is not None and not (policy["min_positive_delta_seconds"] <= delta <= policy["max_positive_delta_seconds"]):
                reasons.append("delta hors seuil")
            consensus.update(
                decision="abstention", new_time_seconds=None, delta_seconds=None,
                reason="; ".join(reasons) or "preuves insuffisantes",
                uncertainty="moyenne_a_elevee",
            )
        rows.append(consensus)

    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "boundary-results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n")
    fields = [k for k in rows[0] if k != "segments"] + ["segments"] if rows else []
    with (RESULTS / "boundary-results.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            out = dict(row)
            out["segments"] = json.dumps(out["segments"], ensure_ascii=False, separators=(",", ":"))
            writer.writerow(out)
    for row in rows:
        key = f'{row["method"]}:{row["decision"]}'
        counts[key] = counts.get(key, 0) + 1
    summary = {
        "episodes": len({r["episode_id"] for r in rows}),
        "boundaries": len({r["boundary_id"] for r in rows}),
        "rows": len(rows),
        "counts": counts,
        "automatic_candidates": {
            method: sum(1 for row in rows if row["method"] == method and row["automatic_candidate"])
            for method in ("ina", "silero", "consensus_ina_silero")
        },
        "precision": None,
        "precision_limit": "Non mesurable: aucune reference humaine par ecoute n'est disponible.",
    }
    (RESULTS / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
