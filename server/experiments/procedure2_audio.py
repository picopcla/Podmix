"""Prototype hors production de la procedure audio 2 pour mixes techno/trance.

Le module n'est importe par aucun chemin de production. Il aligne des
references longues a 16 kHz, calcule un croisement de presence entre deux
morceaux consecutifs et propose une correction locale par bass swap.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np


SAMPLE_RATE = 16_000
FRAME_SIZE = 2_048
HOP_SIZE = 512
HOP_SECONDS = HOP_SIZE / SAMPLE_RATE
SPECTRAL_BINS = 96
SPECTRAL_LOW = 30.0
SPECTRAL_HIGH = 8_000.0
SPECTRAL_BINS_PER_OCTAVE = SPECTRAL_BINS / math.log2(SPECTRAL_HIGH / SPECTRAL_LOW)


@dataclass(frozen=True)
class Features:
    chroma: np.ndarray
    spectral: np.ndarray
    bass_energy: np.ndarray

    @property
    def frames(self) -> int:
        return int(self.chroma.shape[0])


@dataclass(frozen=True)
class Alignment:
    anchor: float
    speed: float
    first_reference: float
    first_mix: float
    windows: int
    median_z: float


def decode(ffmpeg: Path, source: Path, destination: Path, limit: float | None = None) -> None:
    command = [
        str(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(source),
    ]
    if limit is not None:
        command.extend(["-t", str(limit)])
    command.extend([
        "-map", "0:a:0", "-ac", "1", "-ar", str(SAMPLE_RATE),
        "-f", "f32le", str(destination),
    ])
    subprocess.run(command, check=True)


def extract_features(samples: np.ndarray) -> Features:
    samples = np.asarray(samples, dtype=np.float32)
    frames = 1 + max(0, (samples.size - FRAME_SIZE) // HOP_SIZE)
    frequencies = np.fft.rfftfreq(FRAME_SIZE, 1 / SAMPLE_RATE)
    window = np.hanning(FRAME_SIZE).astype(np.float32)

    spectral_map = np.zeros((frequencies.size, SPECTRAL_BINS), dtype=np.float32)
    usable = (frequencies >= SPECTRAL_LOW) & (frequencies <= SPECTRAL_HIGH)
    positions = np.floor(
        np.log2(frequencies[usable] / SPECTRAL_LOW)
        / math.log2(SPECTRAL_HIGH / SPECTRAL_LOW)
        * SPECTRAL_BINS
    ).astype(int)
    spectral_map[np.flatnonzero(usable), np.clip(positions, 0, SPECTRAL_BINS - 1)] = 1.0

    chroma_map = np.zeros((frequencies.size, 12), dtype=np.float32)
    harmonic = (frequencies >= 55.0) & (frequencies <= 4_500.0)
    midi = np.rint(69 + 12 * np.log2(frequencies[harmonic] / 440.0)).astype(int)
    chroma_map[np.flatnonzero(harmonic), np.mod(midi, 12)] = 1.0
    bass_mask = (frequencies >= 30.0) & (frequencies <= 220.0)

    chroma = np.empty((frames, 12), dtype=np.float32)
    spectral = np.empty((frames, SPECTRAL_BINS), dtype=np.float32)
    bass_energy = np.empty(frames, dtype=np.float32)
    for first in range(0, frames, 1_024):
        count = min(1_024, frames - first)
        offsets = (first + np.arange(count))[:, None] * HOP_SIZE + np.arange(FRAME_SIZE)[None, :]
        block = samples[offsets] * window
        power = np.abs(np.fft.rfft(block, axis=1)).astype(np.float32) ** 2
        log_power = np.log1p(power)
        block_spectral = log_power @ spectral_map
        block_chroma = log_power @ chroma_map
        spectral[first:first + count] = block_spectral / np.maximum(
            np.linalg.norm(block_spectral, axis=1, keepdims=True), 1e-7
        )
        chroma[first:first + count] = block_chroma / np.maximum(
            np.linalg.norm(block_chroma, axis=1, keepdims=True), 1e-7
        )
        bass_energy[first:first + count] = np.log1p(np.sum(power[:, bass_mask], axis=1))
    return Features(chroma=chroma, spectral=spectral, bass_energy=bass_energy)


def resample_time(template: np.ndarray, speed: float) -> np.ndarray:
    size = max(16, int(round(template.shape[0] / speed)))
    old = np.linspace(0.0, 1.0, template.shape[0])
    new = np.linspace(0.0, 1.0, size)
    return np.vstack([np.interp(new, old, template[:, column]) for column in range(template.shape[1])]).T.astype(np.float32)


def resample_chroma(template: np.ndarray, speed: float) -> np.ndarray:
    stretched = resample_time(template, speed)
    semitones = 12.0 * math.log2(speed)
    low = math.floor(semitones)
    fraction = semitones - low
    shifted = (1 - fraction) * np.roll(stretched, low, axis=1) + fraction * np.roll(stretched, low + 1, axis=1)
    return shifted / np.maximum(np.linalg.norm(shifted, axis=1, keepdims=True), 1e-7)


def resample_spectral(template: np.ndarray, speed: float) -> np.ndarray:
    stretched = resample_time(template, speed)
    shift = SPECTRAL_BINS_PER_OCTAVE * math.log2(speed)
    bins = np.arange(stretched.shape[1], dtype=np.float32)
    shifted = np.vstack([
        np.interp(bins - shift, bins, row, left=0.0, right=0.0) for row in stretched
    ]).astype(np.float32)
    return shifted / np.maximum(np.linalg.norm(shifted, axis=1, keepdims=True), 1e-7)


def fft_similarity(mix: np.ndarray, template: np.ndarray) -> np.ndarray:
    valid = mix.shape[0] - template.shape[0] + 1
    if valid <= 0:
        return np.empty(0, dtype=np.float32)
    full = mix.shape[0] + template.shape[0] - 1
    fft_size = 1 << (full - 1).bit_length()
    correlated = np.fft.irfft(
        np.fft.rfft(mix, fft_size, axis=0) * np.fft.rfft(template[::-1], fft_size, axis=0),
        fft_size,
        axis=0,
    )
    score = np.sum(correlated[template.shape[0] - 1:template.shape[0] - 1 + valid], axis=1)
    return (score / template.shape[0]).astype(np.float32)


def align_reference(
    mix: Features,
    reference: Features,
    expected: float,
    duration: float,
    reference_offset: float = 0.0,
    search_radius: float | None = None,
) -> Alignment | None:
    # Le rang dans la tracklist n'est qu'un a priori faible : deux titres tres
    # longs ou une intro radio suffisent a decaler la position de plus de cinq
    # minutes. On autorise donc au moins deux intervalles moyens de derive.
    radius = duration if search_radius is None else search_radius
    region_start = max(0.0, expected - radius)
    region_end = min(duration, expected + radius)
    lo = int(region_start / HOP_SECONDS)
    hi = min(mix.frames, int(region_end / HOP_SECONDS))
    short_reference = reference.frames * HOP_SECONDS < 60.0
    window = int(round((6.0 if short_reference else 12.0) / HOP_SECONDS))
    step = int(round((4.0 if short_reference else 10.0) / HOP_SECONDS))
    minimum_windows = 4 if short_reference else 3
    separation = int(round(25.0 / HOP_SECONDS))
    hits: list[dict[str, float]] = []
    for start in range(0, reference.frames - window + 1, step):
        reference_second = reference_offset + start * HOP_SECONDS
        for speed in (0.94, 0.97, 1.0, 1.03, 1.06):
            chroma = resample_chroma(reference.chroma[start:start + window], speed)
            spectral = resample_spectral(reference.spectral[start:start + window], speed)
            scores = 0.25 * fft_similarity(mix.chroma[lo:hi], chroma)
            scores += 0.75 * fft_similarity(mix.spectral[lo:hi], spectral)
            median = float(np.median(scores))
            deviation = float(np.median(np.abs(scores - median))) * 1.4826 + 1e-6
            available = scores.copy()
            for _ in range(2):
                frame = int(np.argmax(available))
                z = (float(available[frame]) - median) / deviation
                if z >= 3.0:
                    mix_second = region_start + frame * HOP_SECONDS
                    hits.append({
                        "anchor": mix_second - reference_second / speed,
                        "reference": reference_second,
                        "mix": mix_second,
                        "speed": speed,
                        "z": z,
                    })
                available[max(0, frame - separation):min(available.size, frame + separation + 1)] = -np.inf

    clusters: list[list[dict[str, float]]] = []
    for hit in sorted(hits, key=lambda item: item["anchor"]):
        candidates = [
            cluster for cluster in clusters
            if abs(hit["anchor"] - np.mean([item["anchor"] for item in cluster])) <= 7.0
        ]
        if candidates:
            min(
                candidates,
                key=lambda cluster: abs(hit["anchor"] - np.mean([item["anchor"] for item in cluster])),
            ).append(hit)
        else:
            clusters.append([hit])

    coherent: list[list[dict[str, float]]] = []
    for cluster in clusters:
        unique: dict[float, dict[str, float]] = {}
        for hit in cluster:
            key = round(hit["reference"], 3)
            if key not in unique or hit["z"] > unique[key]["z"]:
                unique[key] = hit
        sequence = sorted(unique.values(), key=lambda item: item["reference"])
        runs: list[list[dict[str, float]]] = []
        run: list[dict[str, float]] = []
        for hit in sequence:
            if not run or (
                hit["mix"] >= run[-1]["mix"] - 2.0
                and hit["reference"] - run[-1]["reference"] <= 21.0
            ):
                run.append(hit)
            else:
                runs.append(run)
                run = [hit]
        runs.append(run)
        best = max(runs, key=len)
        if len(best) >= minimum_windows:
            coherent.append(best)
    if not coherent:
        return None

    # Priorite a la trajectoire la plus longue et la plus distinctive. La
    # proximite du rang ne departage que des trajectoires equivalentes.
    sequence = max(
        coherent,
        key=lambda items: (
            len(items),
            float(np.median([item["z"] for item in items])),
            -abs(float(np.median([item["anchor"] for item in items])) - expected),
        ),
    )
    first = min(sequence, key=lambda item: item["reference"])
    return Alignment(
        anchor=float(np.median([item["anchor"] for item in sequence])),
        speed=float(np.median([item["speed"] for item in sequence])),
        first_reference=first["reference"],
        first_mix=first["mix"],
        windows=len(sequence),
        median_z=float(np.median([item["z"] for item in sequence])),
    )


def edge_templates(features: Features, incoming: bool) -> list[tuple[np.ndarray, np.ndarray]]:
    half = int(round(6.0 / HOP_SECONDS))
    window = 2 * half
    step = int(round(4.0 / HOP_SECONDS))
    edge = int(round(180.0 / HOP_SECONDS))
    lo = 0 if incoming else max(0, features.frames - edge)
    hi = min(features.frames, edge) if incoming else features.frames
    return [
        (features.chroma[start:start + window], features.spectral[start:start + window])
        for start in range(lo, max(lo, hi - window + 1), step)
    ]


def best_presence(
    chroma: np.ndarray,
    spectral: np.ndarray,
    templates: list[tuple[np.ndarray, np.ndarray]],
) -> float:
    return max(
        0.25 * float(np.mean(np.sum(chroma * candidate_chroma, axis=1)))
        + 0.75 * float(np.mean(np.sum(spectral * candidate_spectral, axis=1)))
        for candidate_chroma, candidate_spectral in templates
    )


def robust_unit(values: np.ndarray) -> np.ndarray:
    low, high = np.percentile(values, [10, 90])
    return np.clip((values - low) / (high - low + 1e-6), 0.0, 1.0)


def matched_presence_run(
    mix: Features,
    reference: Features,
    alignment: Alignment,
) -> list[tuple[float, float]]:
    """Retourne la plus longue suite de fenetres reellement reconnues.

    Contrairement a la duree catalogue, cette suite decrit uniquement la
    portion du morceau effectivement jouee dans le set.
    """
    window = int(round(6.0 / HOP_SECONDS))
    step = int(round(2.0 / HOP_SECONDS))
    offsets = (-90.0, -60.0, -30.0, 30.0, 60.0, 90.0)
    matches: list[tuple[float, float]] = []
    for start in range(0, reference.frames - window + 1, step):
        reference_second = start * HOP_SECONDS
        predicted_second = alignment.anchor + reference_second / alignment.speed
        predicted = int(predicted_second / HOP_SECONDS)
        chroma = resample_chroma(reference.chroma[start:start + window], alignment.speed)
        spectral = resample_spectral(reference.spectral[start:start + window], alignment.speed)

        def local_score(frame: int) -> float | None:
            if frame < 0 or frame + chroma.shape[0] > mix.frames:
                return None
            return (
                0.25 * float(np.mean(np.sum(mix.chroma[frame:frame + chroma.shape[0]] * chroma, axis=1)))
                + 0.75 * float(np.mean(np.sum(mix.spectral[frame:frame + spectral.shape[0]] * spectral, axis=1)))
            )

        direct = local_score(predicted)
        negatives = [
            score for offset in offsets
            if (score := local_score(int((predicted_second + offset) / HOP_SECONDS))) is not None
        ]
        if direct is None or len(negatives) < 3:
            continue
        median = float(np.median(negatives))
        deviation = float(np.median(np.abs(np.asarray(negatives) - median))) * 1.4826 + 1e-5
        z = (direct - median) / deviation
        if z >= 2.5 and direct >= 0.78:
            matches.append((predicted_second + 3.0, z))

    runs: list[list[tuple[float, float]]] = []
    run: list[tuple[float, float]] = []
    for match in matches:
        if not run or match[0] - run[-1][0] <= 8.0:
            run.append(match)
        else:
            if run:
                runs.append(run)
            run = [match]
    if run:
        runs.append(run)
    valid = [candidate for candidate in runs if len(candidate) >= 4]
    if not valid:
        return []
    return max(valid, key=lambda candidate: (len(candidate), np.median([item[1] for item in candidate])))


def transition_candidate(
    mix: Features,
    outgoing: Features,
    incoming: Features,
    outgoing_alignment: Alignment,
    incoming_alignment: Alignment,
    outgoing_duration: float,
    allow_audio_confirmation: bool,
) -> dict[str, float | str]:
    outgoing_run = matched_presence_run(mix, outgoing, outgoing_alignment)
    incoming_run = matched_presence_run(mix, incoming, incoming_alignment)
    density_candidate: float | None = None
    if outgoing_run and incoming_run:
        density_candidate = (outgoing_run[-1][0] + incoming_run[0][0]) / 2
    outgoing_end = (
        outgoing_alignment.anchor
        + outgoing_duration / outgoing_alignment.speed
    )
    incoming_start = incoming_alignment.anchor
    interval_start = max(20.0, min(outgoing_end, incoming_start))
    interval_end = max(outgoing_end, incoming_start)
    geometry = (interval_start + interval_end) / 2

    outgoing_templates = edge_templates(outgoing, incoming=False)
    incoming_templates = edge_templates(incoming, incoming=True)
    start = max(20.0, interval_start - 15.0)
    end = interval_end + 15.0
    half = int(round(6.0 / HOP_SECONDS))
    rows: list[tuple[float, float, float]] = []
    for second in np.arange(start, end + 0.1, 1.0):
        frame = int(second / HOP_SECONDS)
        chroma = mix.chroma[frame - half:frame + half]
        spectral = mix.spectral[frame - half:frame + half]
        if chroma.shape[0] != 2 * half:
            continue
        rows.append((
            second,
            best_presence(chroma, spectral, outgoing_templates),
            best_presence(chroma, spectral, incoming_templates),
        ))
    values = np.asarray(rows)
    kernel = np.ones(9, dtype=np.float64) / 9
    out_curve = robust_unit(np.convolve(values[:, 1], kernel, mode="same"))
    in_curve = robust_unit(np.convolve(values[:, 2], kernel, mode="same"))
    difference = in_curve - out_curve
    crossing_candidates: list[tuple[float, float, int]] = []
    for index in range(10, len(values) - 10):
        second = float(values[index, 0])
        if not interval_start <= second <= interval_end:
            continue
        trend = (
            in_curve[index + 10] - in_curve[index - 10]
            - out_curve[index + 10] + out_curve[index - 10]
        )
        crossing_candidates.append((abs(float(difference[index])), -float(trend), index))
    directional = [candidate for candidate in crossing_candidates if -candidate[1] > 0.15]
    if crossing_candidates:
        _, _, selected = min(directional or crossing_candidates)
        base = float(values[selected, 0])
        method = "presence_crossing"
    else:
        base = geometry
        method = "projected_geometry"

    # Les corrections restent locales : elles ne peuvent jamais attirer le
    # point vers un break ou un drop situe loin du croisement d'empreintes.
    cue_start = max(interval_start, base - 45.0)
    cue_end = min(interval_end, base + 45.0)
    candidates = np.arange(cue_start, cue_end + 0.1, 1.0)
    bass_scores: list[float] = []
    novelty_scores: list[float] = []
    for second in candidates:
        frame = int(second / HOP_SECONDS)
        flank = int(round(15.0 / HOP_SECONDS))
        core = int(round(5.0 / HOP_SECONDS))
        if frame - flank < 0 or frame + flank >= mix.frames:
            bass_scores.append(-math.inf)
            novelty_scores.append(-math.inf)
            continue
        before_bass = float(np.mean(mix.bass_energy[frame - flank:frame - core]))
        core_bass = float(np.mean(mix.bass_energy[frame - core:frame + core]))
        after_bass = float(np.mean(mix.bass_energy[frame + core:frame + flank]))
        bass_scores.append(abs(after_bass - before_bass) + 0.5 * max(0.0, core_bass - (before_bass + after_bass) / 2))
        before = np.mean(mix.spectral[frame - flank:frame], axis=0)
        after = np.mean(mix.spectral[frame:frame + flank], axis=0)
        before /= np.linalg.norm(before) + 1e-9
        after /= np.linalg.norm(after) + 1e-9
        novelty_scores.append(1.0 - float(before @ after))
    bass = float(candidates[int(np.argmax(bass_scores))])
    novelty = float(candidates[int(np.argmax(novelty_scores))])
    # La geometrie issue des deux trajectoires reste la reference. Les indices
    # audio ne la corrigent que s'ils concordent tous dans son voisinage.
    fused = float(np.median([geometry, base, bass, novelty]))
    interval_span = interval_end - interval_start
    if density_candidate is not None:
        # Une reference peut etre tronquee, commencer par une longue intro ou
        # ne reconnaitre qu'un fragment du live edit. La mediane evite qu'une
        # seule de ces trois hypotheses tire la coupe loin de la transition.
        final = float(np.median([density_candidate, geometry, incoming_start]))
        final_method = "reference_consensus"
    elif interval_span > 180.0:
        final = incoming_start
        final_method = "long_overlap_incoming_anchor"
    elif (
        allow_audio_confirmation
        and abs(base - geometry) <= 15.0
        and abs(bass - geometry) <= 15.0
    ):
        final = fused
        final_method = "geometry_confirmed_by_audio"
    else:
        final = geometry
        final_method = "projected_geometry"
    return {
        "method": method,
        "final_method": final_method,
        "interval_start": round(interval_start, 2),
        "interval_end": round(interval_end, 2),
        "geometry": round(geometry, 2),
        "outgoing_presence_end": round(outgoing_run[-1][0], 2) if outgoing_run else None,
        "incoming_presence_start": round(incoming_run[0][0], 2) if incoming_run else None,
        "density_candidate": round(density_candidate, 2) if density_candidate is not None else None,
        "base": round(base, 2),
        "bass": round(bass, 2),
        "novelty": round(novelty, 2),
        "fused": round(fused, 2),
        "final": round(final, 2),
    }


def find_media(directory: Path, stem: str) -> Path:
    candidates = [
        item for item in directory.glob(f"{stem}.*")
        if item.suffix not in {".part", ".f32"}
    ]
    if not candidates:
        raise FileNotFoundError(stem)
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--set", dest="selected_sets", action="append")
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    directory = Path(config["media_directory"])
    ffmpeg = Path(config["ffmpeg"])

    with tempfile.TemporaryDirectory(prefix="podmix-procedure2-") as temporary:
        temporary_path = Path(temporary)
        for set_config in config["sets"]:
            set_name = set_config["name"]
            if args.selected_sets and set_name not in args.selected_sets:
                continue
            mix_f32 = temporary_path / f"{set_name}.f32"
            decode(ffmpeg, find_media(directory, set_config["audio"]), mix_f32, set_config.get("limit"))
            mix = extract_features(np.memmap(mix_f32, dtype=np.float32, mode="r"))
            references: dict[int, Features] = {}
            reference_durations: dict[int, float] = {}
            reference_is_short: dict[int, bool] = {}
            alignments: dict[int, Alignment | None] = {}
            for track in set_config["tracks"]:
                number = int(track["number"])
                ref_f32 = temporary_path / "reference.f32"
                decode(ffmpeg, find_media(directory / "refs", track["reference"]), ref_f32)
                reference = extract_features(np.memmap(ref_f32, dtype=np.float32, mode="r"))
                references[number] = reference
                decoded_duration = reference.frames * HOP_SECONDS
                reference_durations[number] = float(track.get("duration", decoded_duration))
                reference_is_short[number] = decoded_duration < 60.0
                expected = (number - 1) * float(set_config["duration"]) / int(set_config["track_count"])
                # Recherche hierarchique : le voisinage du rang est moins
                # ambigu, mais on l'elargit si aucune trajectoire physique n'y
                # est reconnue. Cela couvre les intros et titres non listes
                # sans sacrifier les matches locaux deja distinctifs.
                alignment = align_reference(
                    mix,
                    reference,
                    expected,
                    float(set_config["duration"]),
                    float(track.get("offset", 0.0)),
                    300.0,
                )
                if alignment is None or alignment.anchor < -60.0:
                    alignment = align_reference(
                        mix,
                        reference,
                        expected,
                        float(set_config["duration"]),
                        float(track.get("offset", 0.0)),
                        max(
                            600.0,
                            2.25 * float(set_config["duration"]) / int(set_config["track_count"]),
                        ),
                    )
                if alignment is not None and not (-60.0 <= alignment.anchor < float(set_config["duration"])):
                    alignment = None
                alignments[number] = alignment
                print(json.dumps({
                    "event": "alignment",
                    "set": set_name,
                    "track": number,
                    "alignment": alignment.__dict__ if alignment else None,
                }), flush=True)

            for outgoing_number, incoming_number in set_config["pairs"]:
                outgoing_alignment = alignments.get(outgoing_number)
                incoming_alignment = alignments.get(incoming_number)
                if not outgoing_alignment or not incoming_alignment:
                    result: dict[str, object] = {"status": "reject", "reason": "missing_alignment"}
                elif (
                    incoming_alignment.anchor <= outgoing_alignment.anchor
                    or incoming_alignment.first_mix <= outgoing_alignment.first_mix + 30.0
                ):
                    result = {"status": "reject", "reason": "non_monotonic_alignment"}
                elif reference_is_short[outgoing_number] or reference_is_short[incoming_number]:
                    result = {"status": "reject", "reason": "short_reference_unknown_offset"}
                else:
                    result = {
                        "status": "ok",
                        **transition_candidate(
                            mix,
                            references[outgoing_number],
                            references[incoming_number],
                            outgoing_alignment,
                            incoming_alignment,
                            reference_durations[outgoing_number],
                            not (
                                reference_is_short[outgoing_number]
                                or reference_is_short[incoming_number]
                            ),
                        ),
                    }
                print(json.dumps({
                    "event": "transition",
                    "set": set_name,
                    "outgoing": outgoing_number,
                    "incoming": incoming_number,
                    **result,
                }), flush=True)


if __name__ == "__main__":
    main()
