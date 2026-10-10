#!/usr/bin/env python3
"""Run the frozen existing engine copy with voice disabled, then enabled."""

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
AUDIO_SHA256 = "47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69"
BASELINE_SHA256 = "e0200bae398de872f1b861f5db4fc1781c302cf193acd27a6935d56a37b2e208"


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
    args = parser.parse_args()
    media = args.media.resolve()
    if sha256(media) != AUDIO_SHA256:
        raise ValueError("Le media FYH512 ne correspond pas au SHA-256 gele")

    sys.path[:0] = [str(ROOT / "server"), str(LAB)]
    import audio_fallback_voice as engine  # noqa: PLC0415

    source = json.loads((LAB / "fyh512_source.json").read_text(encoding="utf-8"))
    title_source = json.loads((LAB / "fyh512_title_candidates.json").read_text(encoding="utf-8"))
    tracks = [{"artist": row["artist"], "title": row["title"]} for row in title_source["tracks"]]
    voice_path = RESULTS / "fyh512-reliable-voices-asr-frozen.json"
    voice_events = json.loads(voice_path.read_text(encoding="utf-8"))
    CACHE.mkdir(parents=True, exist_ok=True)
    RESULTS.mkdir(exist_ok=True)

    original_urls = engine._track_reference_urls
    original_download = engine._download_reference
    original_landmarks = engine.landmark_transition_corrections
    original_detect = engine.detect_constrained_transitions
    original_coherent = engine.boundaries_are_coherent

    def run(enabled: bool) -> tuple[list[dict], dict]:
        trace: dict = {"voice_enabled": enabled, "voice": {}}
        references = [{"track_number": index, "urls_returned": [], "downloads": []}
                      for index in range(1, len(tracks) + 1)]
        targets: dict[str, list[dict]] = {}
        reference_call = 0

        def local_source(audio_url, source_url, destination, alternate_urls=None):
            shutil.copyfile(media, destination)
            trace["source_transport"] = {
                "mode": "hash_verified_local_cache_copy", "sha256": sha256(destination),
                "bytes": destination.stat().st_size,
            }

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
            cache_key = hashlib.sha256(normalized.encode()).hexdigest()
            cached = CACHE / f"{cache_key}.audio"
            item = {"url": normalized, "query_parameters_recorded": False}
            row["downloads"].append(item)
            try:
                if cached.is_file():
                    shutil.copyfile(cached, destination)
                    item["transport"] = "frozen_local_reference_cache"
                else:
                    original_download(url, destination)
                    shutil.copyfile(destination, cached)
                    item["transport"] = "public_reference_download_then_cache"
            except Exception as error:
                item.update({"status": "failed", "error_type": type(error).__name__, "error": str(error)})
                raise
            item.update({"status": "obtained", "bytes": destination.stat().st_size,
                         "sha256": sha256(destination)})

        def traced_landmarks(*values, **kwargs):
            result = original_landmarks(*values, **kwargs)
            corrections, confidence, matched, presences = result
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
            trace["constrained_detection"] = {
                "boundaries_before_adjacent_corrections": result,
                "presence_track_numbers": sorted(values[3]),
                "voice_anchor_track_indexes": sorted(values[4]) if len(values) > 4 else [],
            }
            return result

        def traced_coherent(*values, **kwargs):
            result = original_coherent(*values, **kwargs)
            trace["coherence_verification"] = {"accepted": result, "candidate_boundaries": values[0]}
            return result

        def frozen_voice_detector(source_path, pcm_path, known_tracks):
            trace["voice_input"] = {
                "mode": "replay_of_frozen_local_automatic_vad_asr",
                "path": "results/fyh512-reliable-voices-asr-frozen.json",
                "sha256": sha256(voice_path), "event_count": len(voice_events),
                "manual_semantic_mapping": False,
            }
            return voice_events

        engine._download_source = local_source
        engine._track_reference_urls = traced_urls
        engine._download_reference = traced_download
        engine.landmark_transition_corrections = traced_landmarks
        engine.detect_constrained_transitions = traced_detect
        engine.boundaries_are_coherent = traced_coherent
        progress = []
        outputs = engine.analyze_known_tracklist(
            audio_url=source["audio_url"], source_url=source["episode_url"], tracks=tracks,
            duration_seconds=float(source["rss_duration_seconds"]), source_urls=[source["episode_url"]],
            on_progress=lambda stage, value: progress.append({"stage": stage, "progress": value}),
            voice_detector=frozen_voice_detector if enabled else None,
            voice_trace=trace["voice"],
        )
        trace["references"] = references
        trace["progress"] = progress
        return outputs, trace

    disabled, disabled_trace = run(False)
    disabled_path = RESULTS / "fyh512-existing-engine-voice-disabled-frozen.json"
    write_json(disabled_path, disabled)
    if sha256(disabled_path) != BASELINE_SHA256:
        raise ValueError("Le mode voix desactive ne reproduit pas le baseline exact")

    enabled, enabled_trace = run(True)
    enabled_path = RESULTS / "fyh512-existing-engine-plus-voice-frozen.json"
    disabled_trace_path = RESULTS / "fyh512-existing-engine-voice-disabled-trace-frozen.json"
    enabled_trace_path = RESULTS / "fyh512-existing-engine-plus-voice-trace-frozen.json"
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
        "classification": "existing_engine_plus_automatic_voice_interpretation_from_known_tracklist",
        "audio_sha256": AUDIO_SHA256,
        "baseline_sha256": BASELINE_SHA256,
        "voice_asr_sha256": sha256(voice_path),
        "engine_copy_sha256": sha256(LAB / "audio_fallback_voice.py"),
        "runner_sha256": sha256(Path(__file__)),
        "outputs": {path.name: sha256(path) for path in (disabled_path, enabled_path, disabled_trace_path, enabled_trace_path)},
        "reference_inventory": [{"url": url, "sha256": digest, "bytes": size} for url, digest, size in inventory],
    }
    write_json(RESULTS / "fyh512-existing-engine-plus-voice-freeze-manifest.json", manifest)
    print(json.dumps({"disabled_reproduces_baseline": True, "voice_anchors": enabled_trace["voice"].get("boundary_anchors"),
                      "output_sha256": manifest["outputs"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
