#!/usr/bin/env python3
"""FYH512: fingerprints réellement locaux puis transitions contraintes."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
RESULTS = ROOT / "results"
CACHE = ROOT / "cache" / "fyh512_local_landmarks"
sys.path.insert(0, str(REPO / "server"))

from audio_fallback import (  # noqa: E402
    LANDMARK_SPEEDS, SAMPLE_RATE, _decode_to_f32, _download_direct,
    _transition_candidates, extract_features, landmark_constellation,
    landmark_hashes, landmark_index, landmark_presence,
    landmark_reference_candidates, pure_transition_novelty,
)
from catalog import search_deezer  # noqa: E402


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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
        for row in rows:
            writer.writerow({key: json.dumps(value, ensure_ascii=False, separators=(",", ":"))
                             if isinstance(value, (dict, list)) else value
                             for key, value in row.items()})


def interval_sample_bounds(start: float, end: float, sample_rate: int,
                           total_samples: int) -> tuple[int, int]:
    """Traduit exactement les bornes gelées; aucun feature global n'entre ici."""
    first, last = int(start * sample_rate), int(end * sample_rate)
    if start < 0 or end <= start or first < 0 or last > total_samples or first >= last:
        raise ValueError("Bornes locales invalides")
    return first, last


def local_to_absolute(local_seconds: float, interval_start: float,
                      interval_end: float) -> float:
    """Ajoute start exactement une fois et refuse tout résultat hors intervalle."""
    duration = interval_end - interval_start
    if local_seconds < -1e-9 or local_seconds > duration + 1e-9:
        raise ValueError("Temps local hors intervalle")
    absolute = interval_start + local_seconds
    if absolute < interval_start - 1e-9 or absolute > interval_end + 1e-9:
        raise AssertionError("Double offset ou dépassement de borne")
    return round(absolute, 6)


def retained_candidates(candidates: list[tuple[float, float]], minimum_score: float,
                        separation: float) -> list[tuple[float, float]]:
    selected: list[tuple[float, float]] = []
    for candidate in sorted(candidates, key=lambda item: item[1], reverse=True):
        if candidate[1] >= minimum_score and all(abs(candidate[0] - kept[0]) >= separation for kept in selected):
            selected.append(candidate)
    return sorted(selected)


def bounded_window(left: dict, right: dict, duration: float, settings: dict) -> tuple[float, float]:
    padding = float(settings["presence_edge_padding_seconds"])
    if left["presence_end_local_seconds"] <= right["presence_start_local_seconds"]:
        start = left["presence_end_local_seconds"] - padding
        end = right["presence_start_local_seconds"] + padding
    else:
        start = right["presence_start_local_seconds"] - padding
        end = left["presence_end_local_seconds"] + padding
    start, end = max(0.0, start), min(duration, end)
    minimum, maximum = float(settings["minimum_window_seconds"]), float(settings["maximum_window_seconds"])
    center = (start + end) / 2
    if end - start < minimum:
        start, end = max(0.0, center - minimum / 2), min(duration, center + minimum / 2)
    if end - start > maximum:
        start, end = max(0.0, center - maximum / 2), min(duration, center + maximum / 2)
    return round(start, 6), round(end, 6)


def select_in_window(candidates: list[tuple[float, float]], start: float, end: float,
                     settings: dict) -> tuple[tuple[float, float] | None, str, list[tuple[float, float]]]:
    local = sorted((item for item in candidates if start <= item[0] <= end), key=lambda item: item[1], reverse=True)
    if not local:
        return None, "abstention_aucun_signal_spectral_dans_fenetre", local
    best = local[0]
    if best[1] < float(settings["spectral_salience_minimum"]):
        return None, "abstention_saillance_insuffisante", local
    for runner in local[1:]:
        close_score = (runner[1] >= best[1] * float(settings["ambiguous_runner_score_ratio"])
                       or best[1] - runner[1] <= float(settings["ambiguous_runner_score_delta"]))
        if close_score and abs(runner[0] - best[0]) >= float(settings["ambiguous_runner_minimum_separation_seconds"]):
            return None, "abstention_maxima_spectraux_ambigus", local
    return best, "retenue_maximum_spectral_dans_fenetre_contrainte", local


def prepare_references(tracks: list[dict], work: Path) -> tuple[list[dict], dict[int, dict[float, list]]]:
    CACHE.mkdir(parents=True, exist_ok=True)
    rows, events = [], {}
    for index, track in enumerate(tracks, 1):
        cached_audio, cached_pcm = CACHE / f"candidate-{index:02d}.audio", CACHE / f"candidate-{index:02d}.f32"
        row = {"candidate_number": index, "artist": track["artist"], "title": track["title"],
               "method": "apercu_deezer_public_assiste", "catalog_status": "not_queried",
               "catalog_score": None, "preview_available": False, "cache_reused": False,
               "intervals_queried": 5, "independent": False}
        try:
            if cached_pcm.exists() and cached_pcm.stat().st_size > SAMPLE_RATE * 4 * 8:
                row.update(catalog_status="accepted_cached_public_preview", preview_available=True, cache_reused=True)
            else:
                match = search_deezer(track["artist"], track["title"])
                row["catalog_score"] = (match or {}).get("score")
                preview = str((match or {}).get("previewUrl") or "")
                if preview and int((match or {}).get("score") or 0) >= 70:
                    temporary = work / f"candidate-{index:02d}.audio"
                    _download_direct(preview, temporary)
                    _decode_to_f32(temporary, cached_pcm)
                    shutil.copyfile(temporary, cached_audio)
                    row.update(catalog_status="accepted_public_preview", preview_available=True)
                else:
                    row["catalog_status"] = "no_accepted_preview"
            if row["preview_available"]:
                features = extract_features(np.memmap(cached_pcm, dtype="<f4", mode="r"))
                peaks = landmark_constellation(features.spectral)
                events[index] = {speed: landmark_hashes(peaks, speed) for speed in LANDMARK_SPEEDS}
                row["preview_samples_queried"] = cached_pcm.stat().st_size // 4
                row["preview_sha256"] = sha256(cached_audio) if cached_audio.exists() else None
        except Exception as error:
            row.update(catalog_status="failed", failure_type=type(error).__name__)
        rows.append(row)
    return rows, events


def recognize_interval(interval: dict, local_features, local_duration: float,
                       tracks: list[dict], reference_events: dict[int, dict[float, list]],
                       protocol: dict) -> tuple[list[dict], list[dict]]:
    mix_events = landmark_hashes(landmark_constellation(local_features.spectral))
    mix_index = landmark_index(mix_events)
    all_rows, accepted = [], []
    ambiguity_ratio = float(protocol["local_recognition"]["same_track_ambiguity_ratio"])
    ambiguity_separation = float(protocol["local_recognition"]["same_track_ambiguity_minimum_separation_seconds"])
    for number, track in enumerate(tracks, 1):
        by_speed = reference_events.get(number)
        if not by_speed:
            all_rows.append({"interval_id": interval["interval_id"], "candidate_number": number,
                             "artist": track["artist"], "title": track["title"],
                             "decision": "abstention_sans_apercu", "independent": False})
            continue
        candidates = landmark_reference_candidates(number, 0, by_speed, mix_index, local_duration)
        present = []
        for candidate in candidates:
            presence = landmark_presence(candidate, by_speed[candidate.speed], mix_index)
            if (presence is not None and 0 <= candidate.anchor <= local_duration
                    and 0 <= presence.start <= local_duration and 0 <= presence.end <= local_duration):
                present.append((candidate, presence))
        present.sort(key=lambda item: (item[0].score, item[0].votes), reverse=True)
        ambiguous = bool(len(present) > 1
                         and present[1][0].score >= present[0][0].score * ambiguity_ratio
                         and abs(present[1][0].anchor - present[0][0].anchor) >= ambiguity_separation)
        if not present:
            all_rows.append({"interval_id": interval["interval_id"], "candidate_number": number,
                             "artist": track["artist"], "title": track["title"],
                             "decision": "abstention_aucun_landmark_qualifie", "independent": False})
        for rank, (candidate, presence) in enumerate(present, 1):
            decision = ("abstention_plusieurs_positions_pour_meme_candidat" if ambiguous
                        else "presence_assistee_acceptee" if rank == 1
                        else "abstention_position_secondaire")
            row = {
                "recognition_id": f"{interval['interval_id']}-C{number:02d}-R{rank}",
                "interval_id": interval["interval_id"], "candidate_number": number,
                "artist": track["artist"], "title": track["title"], "candidate_rank": rank,
                "local_anchor_seconds": round(candidate.anchor, 6),
                "absolute_anchor_seconds": local_to_absolute(candidate.anchor, interval["start_seconds"], interval["end_seconds"]),
                "presence_start_local_seconds": round(presence.start, 6),
                "presence_end_local_seconds": round(presence.end, 6),
                "presence_start_absolute_seconds": local_to_absolute(presence.start, interval["start_seconds"], interval["end_seconds"]),
                "presence_end_absolute_seconds": local_to_absolute(presence.end, interval["start_seconds"], interval["end_seconds"]),
                "landmark_score": candidate.score, "landmark_votes": candidate.votes,
                "landmark_events": presence.events, "speed": candidate.speed,
                "decision": decision, "confidence_kind": "score_interne_landmarks_pas_probabilite",
                "independent": False, "assisted": True,
            }
            all_rows.append(row)
            if decision == "presence_assistee_acceptee":
                accepted.append(row)
    return all_rows, sorted(accepted, key=lambda row: row["presence_start_local_seconds"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    args = parser.parse_args()
    protocol_path = ROOT / "fyh512_local_landmarks_protocol.json"
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    source = json.loads((ROOT / "fyh512_source.json").read_text(encoding="utf-8"))
    tracks = json.loads((ROOT / "fyh512_title_candidates.json").read_text(encoding="utf-8"))["tracks"]
    media = args.media.resolve()
    if media.stat().st_size != source["audio_size_bytes"] or sha256(media) != source["audio_sha256"]:
        raise ValueError("Le media ne correspond pas a FYH512")
    bounds = [float(value) for value in protocol["fixed_voice_bounds_seconds"]]
    intervals = [{"interval_id": f"I{index:03d}", "start_seconds": start, "end_seconds": end,
                  "duration_seconds": round(end - start, 6),
                  "start_source": "audio_start" if index == 1 else f"frozen_voice_{index - 1}",
                  "end_source": "decoded_audio_end" if index == len(bounds) - 1 else f"frozen_voice_{index}"}
                 for index, (start, end) in enumerate(zip(bounds, bounds[1:]), 1)]

    with tempfile.TemporaryDirectory(prefix="fyh512-local-landmarks-") as directory:
        work = Path(directory)
        pcm = work / "fyh512.f32"
        _decode_to_f32(media, pcm)
        samples = np.memmap(pcm, dtype="<f4", mode="r")
        expected_samples = int(bounds[-1] * SAMPLE_RATE)
        if abs(samples.size - expected_samples) > 1:
            raise ValueError(f"Duree decodee inattendue: {samples.size / SAMPLE_RATE:.6f}")
        reference_rows, reference_events = prepare_references(tracks, work)
        recognition_rows, accepted_rows, spectral_rows = [], [], []
        baseline_rows, window_rows, transition_rows, trace_rows = [], [], [], []
        baseline = protocol["baseline"]
        window_settings = protocol["transition_windows"]

        for interval in intervals:
            start, end = interval["start_seconds"], interval["end_seconds"]
            first, last = interval_sample_bounds(start, end, SAMPLE_RATE, samples.size)
            # SLICING EXPLICITE EXIGE: toutes les features et nouveautés de cet
            # intervalle proviennent exclusivement de cette tranche.
            local_samples = samples[first:last]
            local_features = extract_features(local_samples)
            local_duration = local_samples.size / SAMPLE_RATE
            local_novelty = pure_transition_novelty(local_features)
            candidates = _transition_candidates(local_novelty, local_duration)
            for rank, (time_value, score) in enumerate(sorted(candidates, key=lambda item: item[1], reverse=True), 1):
                spectral_rows.append({"interval_id": interval["interval_id"], "rank_by_score": rank,
                                      "local_time_seconds": round(time_value, 6),
                                      "absolute_time_seconds": local_to_absolute(time_value, start, end),
                                      "internal_salience": round(score, 6)})
            retained = retained_candidates(candidates, float(baseline["retained_robust_salience_minimum"]),
                                           float(baseline["minimum_retained_separation_seconds"]))
            edge = float(baseline["edge_exclusion_seconds"])
            retained = [item for item in retained if edge <= item[0] <= local_duration - edge]
            for time_value, score in retained:
                baseline_rows.append({"transition_id": f"B{len(baseline_rows) + 1:03d}",
                                      "interval_id": interval["interval_id"],
                                      "local_time_seconds": round(time_value, 6),
                                      "absolute_time_seconds": local_to_absolute(time_value, start, end),
                                      "internal_salience": round(score, 6),
                                      "origin": "precedent_spectral_local"})

            recognized, accepted = recognize_interval(interval, local_features, local_duration,
                                                       tracks, reference_events, protocol)
            recognition_rows.extend(recognized)
            accepted_rows.extend(accepted)
            windows = []
            for left, right in zip(accepted, accepted[1:]):
                window_id = f"W{len(window_rows) + 1:03d}"
                if right["candidate_number"] <= left["candidate_number"]:
                    row = {"window_id": window_id, "interval_id": interval["interval_id"],
                           "left_recognition_id": left["recognition_id"], "right_recognition_id": right["recognition_id"],
                           "left_candidate_number": left["candidate_number"], "right_candidate_number": right["candidate_number"],
                           "decision": "abstention_ordre_candidats_contradictoire", "selected_local_seconds": None}
                    window_rows.append(row)
                    continue
                window_start, window_end = bounded_window(left, right, local_duration, window_settings)
                selected, decision, window_candidates = select_in_window(candidates, window_start, window_end, window_settings)
                row = {"window_id": window_id, "interval_id": interval["interval_id"],
                       "left_recognition_id": left["recognition_id"], "right_recognition_id": right["recognition_id"],
                       "left_candidate_number": left["candidate_number"], "right_candidate_number": right["candidate_number"],
                       "window_start_local_seconds": window_start, "window_end_local_seconds": window_end,
                       "window_start_absolute_seconds": local_to_absolute(window_start, start, end),
                       "window_end_absolute_seconds": local_to_absolute(window_end, start, end),
                       "spectral_candidates": [{"local_time_seconds": round(item[0], 6), "score": round(item[1], 6)} for item in window_candidates],
                       "decision": decision, "selected_local_seconds": round(selected[0], 6) if selected else None}
                window_rows.append(row)
                windows.append((window_start, window_end, row))
                if selected:
                    time_value, score = selected
                    transition_rows.append({"transition_id": f"T{len(transition_rows) + 1:03d}",
                                            "interval_id": interval["interval_id"],
                                            "local_time_seconds": round(time_value, 6),
                                            "absolute_time_seconds": local_to_absolute(time_value, start, end),
                                            "internal_salience": round(score, 6),
                                            "origin": "anchor_constrained_spectral",
                                            "anchor_recognition_ids": [left["recognition_id"], right["recognition_id"]],
                                            "window_id": window_id, "reason": decision})
            for item in retained:
                time_value, score = item
                if any(window_start <= time_value <= window_end for window_start, window_end, _ in windows):
                    continue
                transition_rows.append({"transition_id": f"T{len(transition_rows) + 1:03d}",
                                        "interval_id": interval["interval_id"],
                                        "local_time_seconds": round(time_value, 6),
                                        "absolute_time_seconds": local_to_absolute(time_value, start, end),
                                        "internal_salience": round(score, 6),
                                        "origin": "local_spectral_fallback", "anchor_recognition_ids": [],
                                        "window_id": None, "reason": "hors_fenetre_contrainte"})
            trace_rows.append({"interval_id": interval["interval_id"], "voice_start_source": interval["start_source"],
                               "voice_end_source": interval["end_source"], "start_seconds": start, "end_seconds": end,
                               "first_sample_inclusive": first, "last_sample_exclusive": last,
                               "samples_queried": last - first, "features_scope": "strictement_interval_local",
                               "candidate_titles_queried": len(tracks), "previews_available": len(reference_events),
                               "accepted_presence_ids": [row["recognition_id"] for row in accepted],
                               "window_ids": [row[2]["window_id"] for row in windows],
                               "transition_ids": [row["transition_id"] for row in transition_rows if row["interval_id"] == interval["interval_id"]]})

    RESULTS.mkdir(exist_ok=True)
    outputs = {"intervals": intervals, "recognition-requests": reference_rows,
               "recognition-candidates": recognition_rows, "presences": accepted_rows,
               "spectral-candidates": spectral_rows, "baseline-transitions": baseline_rows,
               "transition-windows": window_rows,
               "transitions": sorted(transition_rows, key=lambda row: row["absolute_time_seconds"]),
               "trace": trace_rows}
    hashes = {}
    for name, rows in outputs.items():
        path = RESULTS / f"fyh512-local-landmarks-{name}-frozen.json"
        write_json(path, rows)
        write_csv(path.with_suffix(".csv"), rows)
        hashes[path.name] = sha256(path)
    summary = {"episode_id": source["episode_id"], "protocol_sha256": sha256(protocol_path),
               "reference_times_loaded": False, "known_titles_assisted": True,
               "independent_titles_identified": 0, "voice_bounds_seconds": bounds,
               "decoded_samples": samples.size, "decoded_duration_seconds": samples.size / SAMPLE_RATE,
               "intervals": len(intervals), "candidate_titles": len(tracks),
               "candidate_previews_available": len(reference_events),
               "candidate_previews_missing": len(tracks) - len(reference_events),
               "local_recognition_attempts": len(tracks) * len(intervals),
               "accepted_assisted_presences": len(accepted_rows),
               "recognition_abstention_rows": sum(row["decision"].startswith("abstention") for row in recognition_rows),
               "constrained_windows": len(window_rows),
               "window_abstentions": sum(row["decision"].startswith("abstention") for row in window_rows),
               "anchor_constrained_transitions": sum(row["origin"] == "anchor_constrained_spectral" for row in transition_rows),
               "fallback_local_transitions": sum(row["origin"] == "local_spectral_fallback" for row in transition_rows),
               "baseline_local_transitions": len(baseline_rows), "new_transitions": len(transition_rows),
               "audd_calls": 0, "audd_cost": 0, "track_count_target": None,
               "output_sha256": hashes}
    write_json(RESULTS / "fyh512-local-landmarks-summary-frozen.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
