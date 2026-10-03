"""Procedure 1 experimentale : empreintes landmarks pour sets techno/trance.

Le fichier reste hors des chemins de production. Il reconnait les portions
effectivement jouees de references publiques, meme sous une autre piste, puis
place la coupe au milieu de la coexistence (ou du trou de reconnaissance) de
deux morceaux consecutifs.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.ndimage import maximum_filter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from audio_fallback import (  # noqa: E402
    HOP_SECONDS,
    _transition_candidates,
    _decode_to_f32,
    extract_features,
    pure_transition_novelty,
)


DOWNSAMPLE = 4
LANDMARK_SECONDS = HOP_SECONDS * DOWNSAMPLE
MAX_HASH_OCCURRENCES = 12
MIN_WEIGHTED_SCORE = 10.0
MIN_RAW_VOTES = 35


@dataclass(frozen=True)
class Peak:
    frame: int
    frequency: int
    strength: float


@dataclass(frozen=True)
class HashEvent:
    key: tuple[int, int, int]
    frame: int
    delta: int


@dataclass(frozen=True)
class Candidate:
    track: int
    reference: str
    anchor: float
    speed: float
    score: float
    votes: int


@dataclass(frozen=True)
class Presence:
    track: int
    reference: str
    anchor: float
    speed: float
    start: float
    end: float
    events: int
    score: float


def constellation(spectral: np.ndarray) -> list[Peak]:
    """Extrait des maxima locaux, sans conserver leur amplitude absolue."""
    usable = spectral[: spectral.shape[0] // DOWNSAMPLE * DOWNSAMPLE]
    reduced = usable.reshape(-1, DOWNSAMPLE, usable.shape[1]).mean(axis=1)
    local_maximum = maximum_filter(reduced, size=(5, 3), mode="nearest")
    threshold = float(np.percentile(reduced, 72))
    mask = (reduced >= local_maximum - 1e-7) & (reduced >= threshold)
    peaks: list[Peak] = []
    for frame in range(reduced.shape[0]):
        frequencies = np.flatnonzero(mask[frame])
        if frequencies.size > 4:
            strongest = np.argsort(reduced[frame, frequencies])[-4:]
            frequencies = frequencies[strongest]
        peaks.extend(
            Peak(frame, int(frequency), float(reduced[frame, frequency]))
            for frequency in frequencies
        )
    return sorted(peaks, key=lambda peak: (peak.frame, peak.frequency))


def landmark_hashes(peaks: list[Peak], speed: float = 1.0) -> list[HashEvent]:
    """Relie chaque pic aux pics suivants, comme une constellation Shazam."""
    if not peaks:
        return []
    times = np.asarray([peak.frame for peak in peaks])
    pitch_shift = round(12 * math.log2(speed))
    events: list[HashEvent] = []
    for peak in peaks:
        first = int(np.searchsorted(times, peak.frame + 2))
        last = int(np.searchsorted(times, peak.frame + 25))
        targets = sorted(
            peaks[first:last], key=lambda target: target.strength, reverse=True
        )[:6]
        for target in targets:
            delta = round((target.frame - peak.frame) / speed)
            frequency = peak.frequency + pitch_shift
            target_frequency = target.frequency + pitch_shift
            if (
                0 <= frequency < 60
                and 0 <= target_frequency < 60
                and 2 <= delta <= 24
            ):
                events.append(HashEvent(
                    (frequency, target_frequency, delta),
                    round(peak.frame / speed),
                    delta,
                ))
    return events


def build_index(events: list[HashEvent]) -> dict[tuple[int, int, int], list[HashEvent]]:
    index: dict[tuple[int, int, int], list[HashEvent]] = defaultdict(list)
    for event in events:
        index[event.key].append(event)
    return index


def reference_candidates(
    track: int,
    reference: Path,
    reference_events: dict[float, list[HashEvent]],
    mix_index: dict[tuple[int, int, int], list[HashEvent]],
    duration: float,
) -> list[Candidate]:
    candidates: list[Candidate] = []
    for speed, events in reference_events.items():
        weighted: dict[int, float] = defaultdict(float)
        raw: dict[int, int] = defaultdict(int)
        for event in events:
            matches = mix_index.get(event.key, ())
            if not matches or len(matches) > MAX_HASH_OCCURRENCES:
                continue
            weight = 1.0 / len(matches)
            for match in matches:
                offset = round((match.frame - event.frame) / 2) * 2
                weighted[offset] += weight
                raw[offset] += 1
        for offset, score in sorted(
            weighted.items(), key=lambda item: item[1], reverse=True
        )[:8]:
            anchor = offset * LANDMARK_SECONDS
            votes = raw[offset]
            if (
                score >= MIN_WEIGHTED_SCORE
                and votes >= MIN_RAW_VOTES
                and -60.0 <= anchor < duration
            ):
                candidates.append(Candidate(
                    track=track,
                    reference=reference.name,
                    anchor=anchor,
                    speed=speed,
                    score=round(score, 3),
                    votes=votes,
                ))
    candidates.sort(key=lambda candidate: (candidate.score, candidate.votes), reverse=True)
    selected: list[Candidate] = []
    for candidate in candidates:
        if all(abs(candidate.anchor - other.anchor) >= 6.0 for other in selected):
            selected.append(candidate)
        if len(selected) == 8:
            break
    return selected


def select_ordered(candidate_sets: list[list[Candidate]]) -> dict[int, Candidate]:
    """Choisit le plus long chemin respectant strictement la tracklist."""
    nodes = sorted(
        (candidate for candidates in candidate_sets for candidate in candidates),
        key=lambda candidate: (candidate.track, candidate.anchor),
    )
    if not nodes:
        return {}
    states: list[tuple[int, float, int | None]] = []
    for index, candidate in enumerate(nodes):
        # Le score brut croit avec la longueur et la richesse de la reference.
        # Son logarithme le rend comparable entre un extrait de 30 s et un
        # extended mix, tandis qu'une piste sautee coute une vraie unite.
        best = (1, math.log1p(candidate.score), None)
        for parent_index, parent in enumerate(nodes[:index]):
            if parent.track >= candidate.track:
                continue
            track_delta = candidate.track - parent.track
            if candidate.anchor - parent.anchor < 45.0 * track_delta:
                continue
            length, score, _ = states[parent_index]
            proposal = (
                length + 1,
                score
                + math.log1p(candidate.score)
                - 1.0 * max(0, track_delta - 1),
                parent_index,
            )
            if proposal[:2] > best[:2]:
                best = proposal
        states.append(best)
    cursor = max(range(len(nodes)), key=lambda index: states[index][:2])
    chosen: dict[int, Candidate] = {}
    while cursor is not None:
        candidate = nodes[cursor]
        chosen[candidate.track] = candidate
        cursor = states[cursor][2]
    return chosen


def presence_for(
    candidate: Candidate,
    reference_events: list[HashEvent],
    mix_index: dict[tuple[int, int, int], list[HashEvent]],
) -> Presence | None:
    evidence: list[float] = []
    for event in reference_events:
        matches = mix_index.get(event.key, ())
        if not matches or len(matches) > MAX_HASH_OCCURRENCES:
            continue
        for match in matches:
            offset = (match.frame - event.frame) * LANDMARK_SECONDS
            if abs(offset - candidate.anchor) <= 1.1:
                evidence.extend((
                    match.frame * LANDMARK_SECONDS,
                    (match.frame + match.delta) * LANDMARK_SECONDS,
                ))
    if len(evidence) < MIN_RAW_VOTES:
        return None
    start, end = np.percentile(np.asarray(evidence), [3, 97])
    return Presence(
        track=candidate.track,
        reference=candidate.reference,
        anchor=candidate.anchor,
        speed=candidate.speed,
        start=round(float(start), 2),
        end=round(float(end), 2),
        events=len(evidence) // 2,
        score=candidate.score,
    )


def transitions_from_presence(
    presences: dict[int, Presence],
    track_count: int,
    audio_candidates: list[tuple[float, float]],
) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    for incoming in range(2, track_count + 1):
        outgoing_presence = presences.get(incoming - 1)
        incoming_presence = presences.get(incoming)
        if outgoing_presence is None or incoming_presence is None:
            results.append({
                "track": incoming,
                "status": "missing_reference",
            })
            continue
        left = outgoing_presence.end
        right = incoming_presence.start
        transition_type = "overlap" if left >= right else "recognition_gap"
        midpoint = (left + right) / 2
        position = midpoint
        method = "presence_midpoint"
        # Un trou large signifie que les landmarks n'observent pas toute la
        # transition. On ne suit pas le pic le plus fort (souvent un drop) :
        # on accroche seulement le changement structurel le plus proche du
        # milieu, et jamais au-dela de 30 secondes.
        if transition_type == "recognition_gap" and right - left > 90.0:
            local = [
                candidate for candidate in audio_candidates
                if left <= candidate[0] <= right
            ]
            if local:
                nearest = min(local, key=lambda candidate: abs(candidate[0] - midpoint))
                if abs(nearest[0] - midpoint) <= 30.0:
                    position = nearest[0]
                    method = "presence_midpoint_snapped_to_local_change"
        results.append({
            "track": incoming,
            "status": "ok",
            "type": transition_type,
            "outgoing_presence_end": left,
            "incoming_presence_start": right,
            "position": round(position, 2),
            "width": round(abs(left - right), 2),
            "method": method,
            "confidence": (
                "high"
                if transition_type == "overlap" or abs(left - right) <= 90.0
                else "medium"
                if abs(left - right) <= 180.0
                else "low"
            ),
        })
    return results


def nearest_audio_change(
    expected: float,
    lower: float,
    upper: float,
    audio_candidates: list[tuple[float, float]],
    tolerance: float,
) -> float:
    local = [
        candidate for candidate in audio_candidates
        if lower <= candidate[0] <= upper
    ]
    if not local:
        return expected
    nearest = min(local, key=lambda candidate: abs(candidate[0] - expected))
    return nearest[0] if abs(nearest[0] - expected) <= tolerance else expected


def fill_missing_blocks(
    transitions: list[dict[str, object]],
    presences: dict[int, Presence],
    track_count: int,
    duration: float,
    audio_candidates: list[tuple[float, float]],
) -> list[dict[str, object]]:
    """Complete automatiquement les trous entre ancres, a faible confiance."""
    by_track = {int(result["track"]): result for result in transitions}
    matched = sorted(presences)
    for left_track, right_track in zip(matched, matched[1:]):
        count = right_track - left_track
        if count <= 1:
            continue
        left = presences[left_track]
        right = presences[right_track]
        targets = list(range(left_track + 1, right_track + 1))
        lower = left.end
        right_cue = float(np.median([right.anchor, right.start]))
        upper = max(lower + 45.0 * count, right.start + 30.0)

        first_expected = lower
        first = nearest_audio_change(
            first_expected,
            lower,
            min(upper, lower + 150.0),
            audio_candidates,
            60.0,
        )
        last = nearest_audio_change(
            right_cue,
            max(first + 45.0 * (count - 1), right_cue - 60.0),
            min(duration - 5.0, right_cue + 60.0),
            audio_candidates,
            45.0,
        )
        if last < first + 45.0 * (count - 1):
            first = lower
            last = upper
        positions = [first]
        for index in range(1, count - 1):
            expected = first + (last - first) * index / (count - 1)
            minimum = positions[-1] + 45.0
            maximum = last - 45.0 * (count - index - 1)
            positions.append(nearest_audio_change(
                expected, minimum, maximum, audio_candidates, 60.0
            ))
        positions.append(last)
        for track, position in zip(targets, positions):
            by_track[track] = {
                "track": track,
                "status": "ok",
                "type": "anchored_gap_fill",
                "position": round(position, 2),
                "method": "ordered_local_change_between_landmarks",
                "confidence": "low",
            }

    if matched and matched[-1] < track_count:
        left = presences[matched[-1]]
        targets = list(range(matched[-1] + 1, track_count + 1))
        lower = max(left.anchor, left.start)
        upper = duration - 20.0
        previous = lower
        for index, track in enumerate(targets, start=1):
            expected = lower + (upper - lower) * index / (len(targets) + 1)
            position = nearest_audio_change(
                expected, previous + 45.0, upper, audio_candidates, 75.0
            )
            by_track[track] = {
                "track": track,
                "status": "ok",
                "type": "trailing_gap_fill",
                "position": round(position, 2),
                "method": "ordered_local_change_after_last_landmark",
                "confidence": "low",
            }
            previous = position
    return [by_track[track] for track in range(2, track_count + 1)]


def find_references(directory: Path, prefix: str, number: int) -> list[Path]:
    stem = f"{prefix}{number:02d}"
    return sorted(
        path for path in directory.glob(f"{stem}*")
        if path.is_file() and path.suffix not in {".f32", ".part"}
    )


def run(args: argparse.Namespace) -> dict[str, object]:
    os.environ["PODMIX_FFMPEG"] = str(args.ffmpeg)
    speeds = (0.94, 0.97, 1.0, 1.03, 1.06)
    with tempfile.TemporaryDirectory(prefix="podmix-landmarks-") as temporary:
        root = Path(temporary)
        mix_pcm = root / "mix.f32"
        _decode_to_f32(args.mix, mix_pcm)
        mix = extract_features(np.memmap(mix_pcm, dtype="<f4", mode="r"))
        duration = mix.frames * HOP_SECONDS
        mix_events = landmark_hashes(constellation(mix.spectral))
        mix_index = build_index(mix_events)
        novelty = pure_transition_novelty(mix)
        audio_candidates = _transition_candidates(novelty, duration)

        candidate_sets: list[list[Candidate]] = []
        cached_events: dict[tuple[str, float], list[HashEvent]] = {}
        for track in range(1, args.track_count + 1):
            candidates: list[Candidate] = []
            for reference in find_references(args.references, args.prefix, track):
                reference_pcm = root / f"reference-{track}.f32"
                _decode_to_f32(reference, reference_pcm)
                features = extract_features(np.memmap(reference_pcm, dtype="<f4", mode="r"))
                peaks = constellation(features.spectral)
                by_speed = {speed: landmark_hashes(peaks, speed) for speed in speeds}
                cached_events.update({(reference.name, speed): events for speed, events in by_speed.items()})
                candidates.extend(reference_candidates(
                    track, reference, by_speed, mix_index, duration
                ))
            candidate_sets.append(candidates)

        chosen = select_ordered(candidate_sets)
        presences: dict[int, Presence] = {}
        for track, candidate in chosen.items():
            presence = presence_for(
                candidate,
                cached_events[(candidate.reference, candidate.speed)],
                mix_index,
            )
            if presence is not None:
                presences[track] = presence
        transitions = transitions_from_presence(
            presences, args.track_count, audio_candidates
        )
        transitions = fill_missing_blocks(
            transitions,
            presences,
            args.track_count,
            duration,
            audio_candidates,
        )
        return {
            "duration": round(duration, 2),
            "matched_tracks": len(presences),
            "candidate_summary": [
                {
                    "track": track,
                    "count": len(candidates),
                    "best": candidates[0].__dict__ if candidates else None,
                }
                for track, candidates in enumerate(candidate_sets, start=1)
            ],
            "presences": [presence.__dict__ for _, presence in sorted(presences.items())],
            "transitions": transitions,
        }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mix", type=Path, required=True)
    parser.add_argument("--references", type=Path, required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--track-count", type=int, required=True)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
