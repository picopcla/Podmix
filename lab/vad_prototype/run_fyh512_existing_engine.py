#!/usr/bin/env python3
"""Run the unchanged production audio fallback on frozen FYH512 audio.

The only substitution is transport: a hash-verified local copy of the exact
RSS media is copied where ``analyze_known_tracklist`` would download it.  All
audio analysis and public-reference functions are the production functions.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any
from urllib.parse import urlsplit, urlunsplit


ROOT = Path(__file__).resolve().parents[2]
LAB = Path(__file__).resolve().parent
RESULTS = LAB / "results"
ENGINE_COMMIT = "eebd70c45e88e7a632992b0255f1b1d55501c9c2"
ENGINE_SHA256 = "03e23eba207aa06966d2e715220d68036654dbb6a95980145d54934f7f47501d"
AUDIO_SHA256 = "47814fb78f3f0236777b6c5ebf378d23e4938914c474df116c49f618b3fdaf69"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_reference_url(value: str) -> str:
    """Keep the public resource identity without serializing signed queries."""
    parsed = urlsplit(value)
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", type=Path, required=True)
    args = parser.parse_args()
    media = args.media.resolve()
    if sha256(media) != AUDIO_SHA256:
        raise ValueError("Le média FYH512 ne correspond pas au SHA-256 gelé")

    engine_path = ROOT / "server" / "audio_fallback.py"
    engine_bytes = engine_path.read_bytes()
    committed_bytes = subprocess.run(
        ["git", "show", f"{ENGINE_COMMIT}:server/audio_fallback.py"],
        cwd=ROOT, check=True, stdout=subprocess.PIPE,
    ).stdout
    if sha256_bytes(engine_bytes) != ENGINE_SHA256 or engine_bytes != committed_bytes:
        raise ValueError("Le moteur courant diffère du moteur main demandé")

    title_source = json.loads((LAB / "fyh512_title_candidates.json").read_text(encoding="utf-8"))
    tracks = [
        {"artist": str(row["artist"]), "title": str(row["title"])}
        for row in title_source["tracks"]
    ]
    if len(tracks) != 32 or any(set(row) != {"artist", "title"} for row in tracks):
        raise ValueError("L'entrée doit contenir exactement 32 couples artist/title")

    sys.path.insert(0, str(ROOT / "server"))
    import audio_fallback as engine  # noqa: PLC0415

    reference_rows = [
        {"track_number": index, **track, "urls_returned": [], "downloads": []}
        for index, track in enumerate(tracks, 1)
    ]
    progress_rows: list[dict[str, Any]] = []
    trace: dict[str, Any] = {}
    download_targets: dict[str, list[dict[str, Any]]] = {}

    original_track_reference_urls = engine._track_reference_urls
    original_download_reference = engine._download_reference
    original_landmarks = engine.landmark_transition_corrections
    original_detect = engine.detect_constrained_transitions
    original_coherent = engine.boundaries_are_coherent

    reference_call = 0

    def local_source(audio_url, source_url, destination, alternate_urls=None):
        shutil.copyfile(media, destination)
        trace["source_transport"] = {
            "mode": "hash_verified_local_cache_copy",
            "production_arguments": {
                "audio_url": audio_url,
                "source_url": source_url,
                "source_urls": list(alternate_urls or []),
            },
            "bytes": destination.stat().st_size,
            "sha256": sha256(destination),
        }

    def traced_urls(track):
        nonlocal reference_call
        urls = original_track_reference_urls(track)
        row = reference_rows[reference_call]
        row["urls_returned"] = [safe_reference_url(url) for url in urls]
        for url in urls:
            download_targets.setdefault(url, []).append(row)
        reference_call += 1
        return urls

    def traced_download(url, destination):
        target = download_targets[url].pop(0)
        item = {"url": safe_reference_url(url), "query_parameters_recorded": False}
        target["downloads"].append(item)
        try:
            original_download_reference(url, destination)
        except Exception as error:
            item.update({"status": "failed", "error_type": type(error).__name__, "error": str(error)})
            raise
        item.update({
            "status": "obtained", "bytes": destination.stat().st_size,
            "sha256": sha256(destination),
        })

    def traced_landmarks(*values, **kwargs):
        result = original_landmarks(*values, **kwargs)
        corrections, confidence, matched, presences = result
        trace["landmarks"] = {
            "reference_feature_counts": [len(row) for row in values[1]],
            "audio_candidate_count": len(values[3]),
            "corrections": {str(key): value for key, value in corrections.items()},
            "confidence": {str(key): value for key, value in confidence.items()},
            "matched_references": matched,
            "presences": {str(key): asdict(value) for key, value in presences.items()},
        }
        return result

    def traced_detect(*values, **kwargs):
        boundaries = original_detect(*values, **kwargs)
        trace["constrained_detection"] = {
            "track_count": values[1], "duration_seconds": values[2],
            "presence_track_numbers": sorted(values[3]),
            "boundaries_before_adjacent_corrections": boundaries,
        }
        return boundaries

    def traced_coherent(boundaries, duration, track_count):
        result = original_coherent(boundaries, duration, track_count)
        trace["coherence_verification"] = {
            "candidate_boundaries": boundaries, "duration_seconds": duration,
            "track_count": track_count, "accepted": result,
        }
        return result

    engine._download_source = local_source
    engine._track_reference_urls = traced_urls
    engine._download_reference = traced_download
    engine.landmark_transition_corrections = traced_landmarks
    engine.detect_constrained_transitions = traced_detect
    engine.boundaries_are_coherent = traced_coherent

    source = json.loads((LAB / "fyh512_source.json").read_text(encoding="utf-8"))
    call_arguments = {
        "audio_url": source["audio_url"],
        "source_url": source["episode_url"],
        "tracks": tracks,
        "duration_seconds": float(source["rss_duration_seconds"]),
        "source_urls": [source["episode_url"]],
    }
    outputs = engine.analyze_known_tracklist(
        **call_arguments,
        on_progress=lambda stage, value: progress_rows.append({"stage": stage, "progress": value}),
    )

    trace["references"] = reference_rows
    trace["progress"] = progress_rows
    trace["adjacent_corrections_applied"] = (
        trace["landmarks"]["corrections"] if trace["coherence_verification"]["accepted"] else {}
    )
    trace["adjacent_corrections_rejected"] = (
        {} if trace["coherence_verification"]["accepted"] else trace["landmarks"]["corrections"]
    )
    trace["pure_fallback_no_presence"] = not bool(trace["landmarks"]["presences"])

    RESULTS.mkdir(exist_ok=True)
    output_path = RESULTS / "fyh512-existing-engine-results-frozen.json"
    trace_path = RESULTS / "fyh512-existing-engine-trace-frozen.json"
    write_json(output_path, outputs)
    write_json(trace_path, trace)
    manifest = {
        "episode_id": source["episode_id"],
        "reference_times_loaded": False,
        "recognition_classification": "reconnaissance assistee par references connues",
        "engine_commit": ENGINE_COMMIT,
        "engine_path": "server/audio_fallback.py::analyze_known_tracklist",
        "engine_sha256": sha256_bytes(engine_bytes),
        "runner_sha256": sha256(Path(__file__)),
        "input_tracklist_sha256": sha256(LAB / "fyh512_title_candidates.json"),
        "input_runtime_keys": ["artist", "title"],
        "input_track_count": len(tracks),
        "audio_sha256": AUDIO_SHA256,
        "audio_size_bytes": media.stat().st_size,
        "call_arguments_without_callback": call_arguments,
        "output_sha256": {
            output_path.name: sha256(output_path),
            trace_path.name: sha256(trace_path),
        },
    }
    write_json(RESULTS / "fyh512-existing-engine-freeze-manifest.json", manifest)
    print(json.dumps({"outputs": len(outputs), "trace": trace, "manifest": manifest}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
