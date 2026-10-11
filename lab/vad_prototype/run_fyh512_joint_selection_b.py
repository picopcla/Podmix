#!/usr/bin/env python3
"""Replay FYH512 through final correction B, from validated A base."""

from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import shutil
import sys
from urllib.parse import urlsplit, urlunsplit


ROOT = Path(__file__).resolve().parents[2]
LAB = Path(__file__).resolve().parent
RESULTS = LAB / "results"
CACHE = LAB / "cache" / "fyh512_existing_references"
FULL_PROTOCOL = LAB / "full_episode_protocol.json"
AUDIO_SHA256 = "47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69"
BASELINE_SHA256 = "e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208"
IMMEDIATE_BASE_SHA256 = "199c10e9c35552206a81ddf782e689986e7f36628415b5a18ae1bd033f37070a"
BASE_COMMIT = "3b52f08f9c35a18b088c308f979b9fe1730d5f09"
BASE_ENGINE_SHA256 = "a290559d2d9b55068581e5c1f956abdeb33ab179e3ea3f7400fcf6a62f33d47a"
BASE_REFERENCE_MANIFEST = RESULTS / "fyh512-existing-engine-plus-voice-freeze-manifest.json"
REFERENCE_PROVENANCE = RESULTS / "fyh512-reference-cache-provenance.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_url(value: str) -> str:
    parsed = urlsplit(value)
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--vad-python", required=True)
    parser.add_argument("--asr-python", required=True)
    parser.add_argument("--voice-work", type=Path, default=LAB / "output" / "fyh512_complete_voice")
    parser.add_argument("--replay-trace", type=Path)
    args = parser.parse_args()
    media = args.media.resolve()
    if sha256(media) != AUDIO_SHA256:
        raise ValueError("Le media FYH512 ne correspond pas au SHA-256 gele")

    sys.path[:0] = [str(ROOT / "server"), str(LAB)]
    import audio_fallback_joint_b as engine  # noqa: PLC0415

    source = json.loads((LAB / "fyh512_source.json").read_text(encoding="utf-8"))
    title_source = json.loads((LAB / "fyh512_title_candidates.json").read_text(encoding="utf-8"))
    tracks = [{"artist": row["artist"], "title": row["title"]} for row in title_source["tracks"]]
    protocol_path = LAB / "fyh512_joint_selection_b_protocol.json"
    if args.replay_trace is None:
        raise ValueError("Cette expérience exige le replay explicite des 10 événements ASR gelés")
    replay_trace_path = args.replay_trace.resolve()
    replay_trace = json.loads(replay_trace_path.read_text(encoding="utf-8"))
    replay_events = replay_trace["voice"]["events"]
    if len(replay_events) != 10:
        raise ValueError("Le replay doit contenir exactement les 10 événements ASR de la passe complète")
    base_reference_manifest = json.loads(BASE_REFERENCE_MANIFEST.read_text(encoding="utf-8"))
    expected_references = {
        item["url"]: (item["sha256"], item["bytes"])
        for item in base_reference_manifest["reference_inventory"]
    }
    if len(expected_references) != 22:
        raise ValueError("Le manifest de base ne contient pas les 22 références attendues")
    CACHE.mkdir(parents=True, exist_ok=True)
    RESULTS.mkdir(exist_ok=True)
    for url, expected in expected_references.items():
        cached = CACHE / f"{hashlib.sha256(url.encode()).hexdigest()}.audio"
        if not cached.is_file() or (sha256(cached), cached.stat().st_size) != expected:
            raise ValueError(
                "Cache original 1391d7ab incomplet ou différent; comparaison contrôlée bloquée"
            )

    original_urls = engine._track_reference_urls
    original_landmarks = engine.landmark_transition_corrections
    original_detect = engine.detect_constrained_transitions

    def run(enabled: bool) -> tuple[list[dict], dict]:
        trace: dict = {"voice_enabled": enabled, "voice": {}}
        references = [{"track_number": index, "urls_returned": [], "downloads": []}
                      for index in range(1, len(tracks) + 1)]
        targets: dict[str, list[dict]] = {}
        reference_call = 0

        def local_source(audio_url, source_url, destination, alternate_urls=None):
            shutil.copyfile(media, destination)
            trace["source_transport"] = {"mode": "hash_verified_local_cache_copy", "sha256": sha256(destination),
                                         "bytes": destination.stat().st_size}

        def traced_urls(track):
            nonlocal reference_call
            urls = original_urls(track)
            row = references[reference_call]
            row["urls_returned"] = [safe_url(url) for url in urls]
            for url in urls:
                targets.setdefault(url, []).append(row)
            reference_call += 1
            return urls

        def traced_download(url, destination):
            row = targets[url].pop(0)
            normalized = safe_url(url)
            cached = CACHE / f"{hashlib.sha256(normalized.encode()).hexdigest()}.audio"
            item = {"url": normalized, "query_parameters_recorded": False}
            row["downloads"].append(item)
            try:
                expected = expected_references.get(normalized)
                if expected is None:
                    raise ValueError("URL absente de l'inventaire de base 1391d7ab")
                if not cached.is_file():
                    raise ValueError("octets originaux absents; comparaison contrôlée bloquée")
                actual = (sha256(cached), cached.stat().st_size)
                if actual != expected:
                    raise ValueError("cache différent de 1391d7ab; comparaison contrôlée bloquée")
                shutil.copyfile(cached, destination)
                item["transport"] = "base_1391d7ab_byte_exact_local_reference_cache"
            except Exception as error:
                item.update({"status": "failed", "error_type": type(error).__name__, "error": str(error)})
                raise
            item.update({"status": "obtained", "bytes": destination.stat().st_size, "sha256": sha256(destination)})

        def traced_landmarks(*values, **kwargs):
            result = original_landmarks(*values, **kwargs)
            corrections, confidence, matched, presences = result[:4]
            trace["landmarks"] = {
                "reference_feature_counts": [len(row) for row in values[1]],
                "corrections": {str(key): value for key, value in corrections.items()},
                "confidence": {str(key): value for key, value in confidence.items()},
                "matched_references": matched,
                "presences": {str(key): asdict(value) for key, value in presences.items()},
            }
            return result

        def traced_detect(*values, **kwargs):
            result = original_detect(*values, **kwargs)
            boundaries = result[0] if isinstance(result, tuple) else result
            trace["constrained_detection"] = {
                "boundaries_final": boundaries,
                "presence_track_numbers": sorted(values[3]),
                "voice_anchor_track_indexes": sorted(values[4]) if len(values) > 4 else [],
            }
            if isinstance(result, tuple):
                trace["adjacent_correction_dp"] = result[1]
            return result

        def complete_voice_detector(source_path, pcm_path, known_tracks):
            del source_path, pcm_path, known_tracks
            trace["voice"]["replay"] = {
                "condition": "automatic_interpretation_only_no_new_vad_or_asr_claimed",
                "source_trace": replay_trace_path.name,
                "source_trace_sha256": sha256(replay_trace_path),
                "event_count": len(replay_events),
            }
            trace["voice"]["events"] = replay_events
            return replay_events

        engine._download_source = local_source
        engine._track_reference_urls = traced_urls
        engine._download_reference = traced_download
        engine.landmark_transition_corrections = traced_landmarks
        engine.detect_constrained_transitions = traced_detect
        progress = []
        outputs = engine.analyze_known_tracklist(
            audio_url=source["audio_url"], source_url=source["episode_url"], tracks=tracks,
            duration_seconds=float(source["rss_duration_seconds"]), source_urls=[source["episode_url"]],
            on_progress=lambda stage, value: progress.append({"stage": stage, "progress": value}),
            voice_detector=complete_voice_detector if enabled else None, voice_trace=trace["voice"],
        )
        trace["references"] = references
        trace["progress"] = progress
        return outputs, trace

    disabled, disabled_trace = run(False)
    prefix = "fyh512-joint-selection-b"
    disabled_path = RESULTS / f"{prefix}-disabled-frozen.json"
    write_json(disabled_path, disabled)
    if sha256(disabled_path) != BASELINE_SHA256:
        raise ValueError("Le mode voix desactive ne reproduit pas le baseline exact")

    enabled, enabled_trace = run(True)
    enabled_path = RESULTS / f"{prefix}-frozen.json"
    disabled_trace_path = RESULTS / f"{prefix}-disabled-trace-frozen.json"
    enabled_trace_path = RESULTS / f"{prefix}-trace-frozen.json"
    write_json(enabled_path, enabled)
    write_json(disabled_trace_path, disabled_trace)
    write_json(enabled_trace_path, enabled_trace)
    inventory = sorted({
        (item["url"], item.get("sha256"), item.get("bytes"))
        for run_trace in (disabled_trace, enabled_trace)
        for row in run_trace["references"] for item in row["downloads"] if item.get("status") == "obtained"
    })
    manifest = {
        "reference_times_loaded": False,
        "classification": "validated_a_plus_final_b_corrections_inside_same_dp",
        "base_commit": BASE_COMMIT,
        "base_engine_sha256": BASE_ENGINE_SHA256,
        "audio_sha256": AUDIO_SHA256,
        "baseline_sha256": BASELINE_SHA256,
        "immediate_frozen_voice_base_sha256": IMMEDIATE_BASE_SHA256,
        "voice_replay": True,
        "replay_condition": "no new VAD or ASR claimed; exact 10-event trace replayed automatically through the delivered hook",
        "replay_source_sha256": sha256(replay_trace_path),
        "inputs": {path.relative_to(LAB).as_posix(): sha256(path) for path in (
            LAB / "fyh512_source.json", LAB / "fyh512_title_candidates.json", protocol_path,
            FULL_PROTOCOL, BASE_REFERENCE_MANIFEST, REFERENCE_PROVENANCE,
        )},
        "engine_copy_sha256": sha256(LAB / "audio_fallback_joint_b.py"),
        "detector_sha256": sha256(LAB / "complete_voice_detector.py"),
        "runner_sha256": sha256(Path(__file__)),
        "tests_sha256": sha256(LAB / "test_joint_selection_b.py"),
        "reference_cache_policy": "base_1391d7ab_byte_exact_or_block_without_network_refetch",
        "outputs": {path.name: sha256(path) for path in (disabled_path, enabled_path, disabled_trace_path, enabled_trace_path)},
        "reference_inventory": [{"url": url, "sha256": digest, "bytes": size} for url, digest, size in inventory],
    }
    write_json(RESULTS / f"{prefix}-freeze-manifest.json", manifest)
    print(json.dumps({"disabled_reproduces_baseline": True,
                      "voice_candidates": len(enabled_trace["voice"]["events"]),
                      "voice_anchors": enabled_trace["voice"].get("boundary_anchors"),
                      "output_sha256": manifest["outputs"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
