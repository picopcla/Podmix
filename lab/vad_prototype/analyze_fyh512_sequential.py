#!/usr/bin/env python3
"""Pipeline gelable voix -> intervalles -> identification -> transitions FYH512."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unicodedata

import numpy as np

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
RESULTS = ROOT / "results"
sys.path.insert(0, str(REPO / "server"))

from audio_fallback import (  # noqa: E402
    HOP_SECONDS, SAMPLE_RATE, _decode_to_f32, _download_direct,
    _transition_candidates, extract_features, landmark_transition_corrections,
    pure_transition_novelty,
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
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(value, ensure_ascii=False, separators=(",", ":"))
                             if isinstance(value, (dict, list)) else value
                             for key, value in row.items()})


def words(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKD", value.casefold())
    plain = "".join(char for char in normalized if not unicodedata.combining(char))
    ignored = {"and", "the", "feat", "pres", "remix", "mix", "with"}
    return {item for item in re.findall(r"[a-z0-9]+", plain) if len(item) >= 3 and item not in ignored}


def asr_mentions(asr_rows: list[dict], tracks: list[dict]) -> list[dict]:
    mentions = []
    for row in asr_rows:
        transcript_words = words(row["transcript"])
        for track in tracks:
            title_words, artist_words = words(track["title"]), words(track["artist"])
            title_overlap = sorted(title_words & transcript_words)
            artist_overlap = sorted(artist_words & transcript_words)
            coverage = len(title_overlap) / max(1, len(title_words))
            if ((len(title_overlap) >= 2 and coverage >= 0.6)
                    or (title_overlap and artist_overlap and coverage >= 0.5)):
                mentions.append({
                    "detection_id": row["detection_id"], "voice_end_seconds": row["voice_end_seconds"],
                    "candidate_number": track["number"], "candidate_artist": track["artist"],
                    "candidate_title": track["title"], "title_overlap": title_overlap,
                    "artist_overlap": artist_overlap, "title_token_coverage": round(coverage, 4),
                    "classification": "asr_annonce_assiste_par_candidats",
                    "assignment": "abstention_orientation_precedent_suivant_ou_liste_multiple",
                })
    return mentions


def interval_for(time_value: float, intervals: list[dict]) -> str | None:
    for index, interval in enumerate(intervals):
        if interval["start_seconds"] <= time_value < interval["end_seconds"] or (
                index == len(intervals) - 1 and time_value <= interval["end_seconds"]):
            return interval["interval_id"]
    return None


def retained_candidates(candidates: list[tuple[float, float]], minimum_score: float,
                        separation: float) -> list[tuple[float, float]]:
    selected: list[tuple[float, float]] = []
    for candidate in sorted(candidates, key=lambda item: item[1], reverse=True):
        if candidate[1] >= minimum_score and all(abs(candidate[0] - kept[0]) >= separation for kept in selected):
            selected.append(candidate)
    return sorted(selected)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", required=True, type=Path)
    args = parser.parse_args()
    protocol = json.loads((ROOT / "fyh512_sequential_protocol.json").read_text(encoding="utf-8"))
    source = json.loads((ROOT / "fyh512_source.json").read_text(encoding="utf-8"))
    tracks = json.loads((ROOT / "fyh512_title_candidates.json").read_text(encoding="utf-8"))["tracks"]
    voices = json.loads((RESULTS / "fyh512-voice-detections-frozen.json").read_text(encoding="utf-8"))
    asr_rows = json.loads((RESULTS / "fyh512-reliable-voices-asr-frozen.json").read_text(encoding="utf-8"))
    media = args.media.resolve()
    if media.stat().st_size != source["audio_size_bytes"] or sha256(media) != source["audio_sha256"]:
        raise ValueError("Le media ne correspond pas a FYH512")
    reliable = [row for row in voices if row["passes_reliable_threshold"]]
    threshold = protocol["voice_to_intervals"]["reliable_internal_confidence_minimum"]
    if threshold != 0.95 or any(float(row["confidence"]) < threshold for row in reliable):
        raise ValueError("Seuil voix modifie")
    if os.environ.get("PODMIX_AUDD_API_TOKEN", "").strip():
        raise RuntimeError("Le protocole interdit tout appel AudD, meme si un jeton apparait")

    with tempfile.TemporaryDirectory(prefix="fyh512-sequential-") as directory:
        work = Path(directory)
        pcm = work / "fyh512.f32"
        _decode_to_f32(media, pcm)
        samples = np.memmap(pcm, dtype="<f4", mode="r")
        duration = samples.size / SAMPLE_RATE
        boundary_times = [0.0, *[float(row["algorithm_time_seconds"]) for row in reliable], duration]
        boundary_sources = ["audio_start", *[row["detection_id"] for row in reliable], "decoded_audio_end"]
        intervals = [{
            "interval_id": f"I{index:03d}", "start_seconds": round(start, 6),
            "end_seconds": round(end, 6), "duration_seconds": round(end - start, 6),
            "start_source": boundary_sources[index - 1], "end_source": boundary_sources[index],
            "boundary_convention": "fin_de_phrase_candidate_sauf_extremites_audio",
            "coverage": "continu_sans_trou", "voice_identity": "non_confirmee",
        } for index, (start, end) in enumerate(zip(boundary_times, boundary_times[1:]), 1)]

        # Identification d'abord: ASR assisté et landmarks de candidats connus.
        mentions = asr_mentions(asr_rows, tracks)
        mix_features = extract_features(samples)
        references: list[list] = [[] for _ in tracks]
        reference_rows = []
        for index, track in enumerate(tracks):
            status = {
                "candidate_number": track["number"], "candidate_artist": track["artist"],
                "candidate_title": track["title"], "method": "deezer_preview_landmark_assisted",
                "catalog_status": "unavailable_or_rejected", "catalog_score": None,
                "preview_downloaded": False,
            }
            try:
                match = search_deezer(track["artist"], track["title"])
                if match:
                    status["catalog_score"] = match.get("score")
                preview = str((match or {}).get("previewUrl") or "")
                if preview and int((match or {}).get("score") or 0) >= 70:
                    source_path, pcm_path = work / f"reference-{index}.audio", work / f"reference-{index}.f32"
                    _download_direct(preview, source_path)
                    _decode_to_f32(source_path, pcm_path)
                    references[index].append(extract_features(np.memmap(pcm_path, dtype="<f4", mode="r")))
                    status["catalog_status"], status["preview_downloaded"] = "accepted_public_preview", True
            except Exception as error:
                status["catalog_status"], status["failure_type"] = "failed", type(error).__name__
            reference_rows.append(status)
        global_novelty = pure_transition_novelty(mix_features)
        global_candidates = _transition_candidates(global_novelty, duration)
        _, _, matched_count, presences = landmark_transition_corrections(
            mix_features, references, duration, global_candidates, include_presences=True)
        identification_rows = []
        for track_number, presence in sorted(presences.items()):
            track = tracks[track_number - 1]
            identification_rows.append({
                "recognition_id": f"L{track_number:03d}", "interval_id": interval_for(presence.anchor, intervals),
                "candidate_number": track_number, "artist": track["artist"], "title": track["title"],
                "method": "landmark_assiste_par_candidats_et_ordre",
                "provenance": "apercu_deezer_public_catalogue_score_ge_70",
                "anchor_seconds": round(presence.anchor, 3), "presence_start_seconds": presence.start,
                "presence_end_seconds": presence.end, "landmark_score": round(presence.score, 6),
                "landmark_events": presence.events, "independent": False,
                "confidence_kind": "score_interne_landmarks_pas_probabilite",
            })

        # Transitions ensuite, recalculées séparément dans chaque intervalle.
        transition_rows, candidate_rows, selected_times = [], [], []
        settings = protocol["interval_transition_detection"]
        for interval in intervals:
            start, end = interval["start_seconds"], interval["end_seconds"]
            first_sample, last_sample = int(start * SAMPLE_RATE), int(end * SAMPLE_RATE)
            local_duration = (last_sample - first_sample) / SAMPLE_RATE
            local_novelty = pure_transition_novelty(extract_features(samples[first_sample:last_sample]))
            candidates = _transition_candidates(local_novelty, local_duration)
            kept = retained_candidates(candidates, float(settings["retained_robust_salience_minimum"]),
                                       float(settings["minimum_retained_separation_seconds"]))
            kept_times, edge = {round(item[0], 6) for item in kept}, float(settings["edge_exclusion_seconds"])
            for local_time, score in candidates:
                absolute = start + local_time
                retained = round(local_time, 6) in kept_times and edge <= local_time <= local_duration - edge
                decision = ("retained_fixed_salience_and_separation" if retained else
                            "abstention_interval_edge" if local_time < edge or local_time > local_duration - edge
                            else "abstention_below_fixed_salience_or_nonmaximum_suppression")
                row = {
                    "interval_id": interval["interval_id"], "local_time_seconds": round(local_time, 6),
                    "absolute_time_seconds": round(absolute, 6), "internal_salience": round(score, 6),
                    "confidence_kind": "robust_novelty_z_not_probability", "retained": retained,
                    "decision": decision, "track_count_target": None,
                }
                candidate_rows.append(row)
                if retained:
                    transition_rows.append({"transition_id": f"T{len(transition_rows) + 1:03d}", **row})
                    selected_times.append((absolute, interval["interval_id"], score))

        pieces = []
        for interval in intervals:
            internal = [time for time, interval_id, _ in selected_times if interval_id == interval["interval_id"]]
            starts, ends = [interval["start_seconds"], *internal], [*internal, interval["end_seconds"]]
            for start, end in zip(starts, ends):
                matches = [row for row in identification_rows if start <= row["anchor_seconds"] < end]
                unique = matches[0] if len(matches) == 1 else None
                pieces.append({
                    "piece_id": f"P{len(pieces) + 1:03d}", "interval_id": interval["interval_id"],
                    "start_seconds": round(start, 6), "end_seconds": round(end, 6),
                    "duration_seconds": round(end - start, 6),
                    "start_origin": "voice_interval_boundary" if start == interval["start_seconds"] else "retained_local_spectral_transition",
                    "independent_artist": None, "independent_title": None,
                    "independent_identification_status": "abstention_no_configured_identifier",
                    "assisted_artist": unique["artist"] if unique else None,
                    "assisted_title": unique["title"] if unique else None,
                    "assisted_identification_status": ("candidate_landmark_unique_in_piece" if unique else
                        "abstention_multiple_candidate_landmarks" if len(matches) > 1 else "abstention_no_candidate_landmark"),
                    "recognition_ids": [row["recognition_id"] for row in matches],
                })

        trace = []
        for interval in intervals:
            trace.append({
                "interval_id": interval["interval_id"], "voice_source_start": interval["start_source"],
                "voice_source_end": interval["end_source"], "start_seconds": interval["start_seconds"],
                "end_seconds": interval["end_seconds"], "recognition_requests": reference_rows,
                "recognition_results": [row for row in identification_rows if row["interval_id"] == interval["interval_id"]],
                "asr_mentions": [row for row in mentions if interval["start_source"] == row["detection_id"] or interval["end_source"] == row["detection_id"]],
                "pieces": [row["piece_id"] for row in pieces if row["interval_id"] == interval["interval_id"]],
                "retained_transitions": [row["transition_id"] for row in transition_rows if row["interval_id"] == interval["interval_id"]],
                "transition_abstentions": sum(1 for row in candidate_rows if row["interval_id"] == interval["interval_id"] and not row["retained"]),
            })

    RESULTS.mkdir(exist_ok=True)
    values = {"intervals": intervals, "pieces": pieces, "transitions": transition_rows,
              "transition-candidates": candidate_rows, "identifications": identification_rows,
              "recognition-requests": reference_rows, "asr-mentions": mentions, "trace": trace}
    paths = {}
    for name, value in values.items():
        path = RESULTS / f"fyh512-sequential-{name}-frozen.json"
        write_json(path, value)
        write_csv(path.with_suffix(".csv"), value)
        paths[name] = path
    summary = {
        "episode_id": source["episode_id"], "reference_times_loaded": False,
        "known_reference_warning": protocol["known_reference_warning"], "media_sha256": source["audio_sha256"],
        "decoded_duration_seconds": round(duration, 6), "voice_threshold": threshold,
        "reliable_voice_boundaries": len(reliable), "intervals": len(intervals),
        "coverage_seconds": round(sum(row["duration_seconds"] for row in intervals), 6),
        "audd_token_present": False, "audd_calls": 0, "audd_cost": 0,
        "asr_voice_segments": len(asr_rows), "asr_candidate_mentions": len(mentions),
        "candidate_previews_accepted": sum(row["preview_downloaded"] for row in reference_rows),
        "candidate_landmark_presences": matched_count, "independent_titles_identified": 0,
        "assisted_titles_identified": sum(bool(row["assisted_title"]) for row in pieces),
        "local_transition_candidates": len(candidate_rows), "local_transitions_retained": len(transition_rows),
        "pieces": len(pieces), "track_count_target": None,
        "processing_order": ["reliable_voice_boundaries", "intervals", "identification", "local_transitions"],
        "output_sha256": {path.name: sha256(path) for path in paths.values()},
    }
    write_json(RESULTS / "fyh512-sequential-summary-frozen.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
