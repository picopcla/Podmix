#!/usr/bin/env python3
"""Detect DJ-voice endings globally and compare them with CueNation afterwards."""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parent
EPISODES = ROOT / "episodes.json"
PROTOCOL = ROOT / "full_episode_protocol.json"
OUTPUT = ROOT / "output" / "full_episode"
RESULTS = ROOT / "results"
RESPONSES = ROOT.parents[1] / "reponses"
REPORT_NAME = "podmix-vad-decoupe-complete-ptr-2026-10-10-1555.md"


def overlap(a: dict, b: dict) -> float:
    return max(0.0, min(a["end"], b["end"]) - max(a["start"], b["start"]))


def nearest_silero(ina: dict, silero_speech: list[dict], params: dict):
    eligible = []
    for segment in silero_speech:
        shared = overlap(ina, segment)
        end_gap = abs(segment["end"] - ina["end"])
        if shared >= params["min_silero_overlap_seconds"] and end_gap <= params["max_ina_silero_end_disagreement_seconds"]:
            eligible.append((end_gap, -shared, segment))
    return min(eligible, default=None, key=lambda item: (item[0], item[1], item[2]["end"]))


def confidence(ina: dict, music: dict, silero: dict, params: dict) -> tuple[float, dict]:
    weights = params["confidence_weights"]
    scales = params["confidence_scales"]
    music_duration = music["end"] - music["start"]
    speech_duration = ina["end"] - ina["start"]
    shared = overlap(ina, silero)
    end_gap = abs(ina["end"] - silero["end"])
    components = {
        "music_continuity": min(1.0, music_duration / scales["music_continuity_seconds"]),
        "model_end_agreement": max(0.0, 1.0 - end_gap / params["max_ina_silero_end_disagreement_seconds"]),
        "speech_overlap": min(1.0, shared / max(params["min_silero_overlap_seconds"], min(ina["end"] - ina["start"], 3.0))),
        "ina_speech_duration": min(1.0, speech_duration / scales["ina_speech_duration_seconds"]),
    }
    score = sum(weights[name] * value for name, value in components.items())
    return round(score, 4), {name: round(value, 4) for name, value in components.items()}


def detect(episode: dict, protocol: dict) -> list[dict]:
    episode_id = episode["id"]
    ina = json.loads((OUTPUT / f"{episode_id}-ina.json").read_text())
    silero = json.loads((OUTPUT / f"{episode_id}-silero.json").read_text())
    params = protocol["detector"]
    silero_speech = [item for item in silero["segments"] if item["label"] == "speech"]
    candidates = []
    for index, segment in enumerate(ina["segments"][:-1]):
        following = ina["segments"][index + 1]
        speech_duration = segment["end"] - segment["start"]
        music_duration = following["end"] - following["start"]
        if segment["label"] not in params["ina_speech_labels"]:
            continue
        if following["label"] not in params["ina_music_labels"]:
            continue
        if speech_duration < params["min_ina_speech_seconds"] or music_duration < params["min_return_to_music_seconds"]:
            continue
        match = nearest_silero(segment, silero_speech, params)
        if match is None:
            continue
        end_gap, negative_shared, silero_segment = match
        score, components = confidence(segment, following, silero_segment, params)
        candidates.append({
            "episode_id": episode_id,
            "episode_title": episode["title"],
            "algorithm_time_seconds": round(segment["end"], 3),
            "confidence": score,
            "ina_speech_start_seconds": round(segment["start"], 3),
            "ina_speech_end_seconds": round(segment["end"], 3),
            "ina_speech_duration_seconds": round(speech_duration, 3),
            "ina_music_end_seconds": round(following["end"], 3),
            "ina_music_duration_seconds": round(music_duration, 3),
            "silero_speech_start_seconds": round(silero_segment["start"], 3),
            "silero_speech_end_seconds": round(silero_segment["end"], 3),
            "model_end_gap_seconds": round(end_gap, 3),
            "speech_overlap_seconds": round(-negative_shared, 3),
            "confidence_components": components,
        })
    deduplicated = []
    for candidate in sorted(candidates, key=lambda item: item["algorithm_time_seconds"]):
        if deduplicated and candidate["algorithm_time_seconds"] - deduplicated[-1]["algorithm_time_seconds"] <= params["deduplication_seconds"]:
            if candidate["confidence"] > deduplicated[-1]["confidence"]:
                deduplicated[-1] = candidate
        else:
            deduplicated.append(candidate)
    for index, item in enumerate(deduplicated, 1):
        item["detection_id"] = f"{episode_id}-d{index:03d}"
    return deduplicated


def normal_ci(value: float, standard_error: float) -> list[float | None]:
    if not math.isfinite(standard_error):
        return [None, None]
    return [round(value - 1.96 * standard_error, 6), round(value + 1.96 * standard_error, 6)]


def regression(
    matched: list[dict],
    reference_key: str = "cuesheet_time_seconds",
    error_key: str = "signed_error_seconds",
) -> dict:
    pairs = [
        (row[reference_key], row[error_key])
        for row in matched
        if row["detected"]
    ]
    n = len(pairs)
    if n < 3:
        return {"n": n, "available": False, "reason": "Moins de trois appariements."}
    xs = [pair[0] for pair in pairs]
    ys = [pair[1] for pair in pairs]
    x_mean = statistics.mean(xs)
    y_mean = statistics.mean(ys)
    sxx = sum((x - x_mean) ** 2 for x in xs)
    slope = sum((x - x_mean) * (y - y_mean) for x, y in pairs) / sxx
    intercept = y_mean - slope * x_mean
    residuals = [y - (intercept + slope * x) for x, y in pairs]
    sse = sum(value * value for value in residuals)
    syy = sum((y - y_mean) ** 2 for y in ys)
    sigma2 = sse / (n - 2)
    slope_se = math.sqrt(sigma2 / sxx)
    intercept_se = math.sqrt(sigma2 * (1 / n + x_mean * x_mean / sxx))
    mean_se = statistics.stdev(ys) / math.sqrt(n)
    mean_ci = normal_ci(y_mean, mean_se)
    slope_ci = normal_ci(slope, slope_se)
    return {
        "n": n,
        "available": True,
        "mean_signed_offset_seconds": round(y_mean, 6),
        "mean_signed_offset_ci95_seconds": mean_ci,
        "constant_offset_detected": bool(mean_ci[0] is not None and (mean_ci[0] > 0 or mean_ci[1] < 0)),
        "intercept_seconds": round(intercept, 6),
        "intercept_ci95_seconds": normal_ci(intercept, intercept_se),
        "slope_seconds_per_second": round(slope, 9),
        "slope_seconds_per_hour": round(slope * 3600, 6),
        "slope_ci95_seconds_per_hour": [round(value * 3600, 6) for value in normal_ci(slope, slope_se)],
        "drift_detected": bool(slope_ci[0] is not None and (slope_ci[0] > 0 or slope_ci[1] < 0)),
        "r_squared": round(1 - sse / syy, 6) if syy else 0.0,
        "test_limit": "Intervalles normaux 95 %, exploratoires; les non-detections ne participent pas a la regression."
    }


def error_metrics(rows: list[dict], absolute_error_key: str) -> dict:
    errors = [row[absolute_error_key] for row in rows if row["detected"]]
    return {
        "mean_absolute_error_seconds": round(statistics.mean(errors), 3) if errors else None,
        "median_absolute_error_seconds": round(statistics.median(errors), 3) if errors else None,
        "share_absolute_error_lt_1": round(sum(value < 1 for value in errors) / len(errors), 4) if errors else None,
        "share_absolute_error_lt_3": round(sum(value < 3 for value in errors) / len(errors), 4) if errors else None,
        "share_absolute_error_lt_10": round(sum(value < 10 for value in errors) / len(errors), 4) if errors else None,
        "error_share_denominator": len(errors),
    }


def compare(episode: dict, detections: list[dict], protocol: dict) -> tuple[list[dict], dict]:
    tolerance = protocol["comparison"]["maximum_tolerance_seconds"]
    validated = {
        (item["episode_id"], item["track_number"]): item
        for item in protocol.get("human_validated_corrections", [])
    }
    explicitly_non_validated = {
        (item["episode_id"], item["track_number"]): item
        for item in protocol.get("explicitly_non_validated_candidates", [])
    }
    unused = {item["detection_id"]: item for item in detections}
    rows = []
    for track in sorted(episode["tracks"], key=lambda item: item["time"]):
        cue = float(track["time"])
        validation = validated.get((episode["id"], track["number"]))
        non_validation = explicitly_non_validated.get((episode["id"], track["number"]))
        corrected_reference = validation["corrected_time_seconds"] if validation else cue
        eligible = [item for item in unused.values() if abs(item["algorithm_time_seconds"] - cue) <= tolerance]
        nearest = min(eligible, default=None, key=lambda item: (abs(item["algorithm_time_seconds"] - cue), -item["confidence"], item["algorithm_time_seconds"]))
        signed_before = round(nearest["algorithm_time_seconds"] - cue, 3) if nearest else None
        signed_after = round(nearest["algorithm_time_seconds"] - corrected_reference, 3) if nearest else None
        row = {
            "episode_id": episode["id"],
            "track_number": track["number"],
            "track_title": track["title"],
            "cuesheet_time_seconds": cue,
            "detected": nearest is not None,
            "detection_id": nearest["detection_id"] if nearest else None,
            "algorithm_time_seconds": nearest["algorithm_time_seconds"] if nearest else None,
            "confidence": nearest["confidence"] if nearest else None,
            "signed_error_seconds": signed_before,
            "absolute_error_seconds": abs(signed_before) if signed_before is not None else None,
            "human_validation_status": (
                validation["status"] if validation else
                "explicitly_not_validated" if non_validation else
                "not_applicable"
            ),
            "human_validation_source": validation["source"] if validation else None,
            "human_validation_at": validation["validated_at"] if validation else None,
            "validated_corrected_time_seconds": validation["corrected_time_seconds"] if validation else None,
            "lab_reference_time_seconds": corrected_reference,
            "signed_error_with_validated_corrections_seconds": signed_after,
            "absolute_error_with_validated_corrections_seconds": abs(signed_after) if signed_after is not None else None,
        }
        rows.append(row)
        if nearest:
            unused.pop(nearest["detection_id"])
    baseline_metrics = error_metrics(rows, "absolute_error_seconds")
    corrected_metrics = error_metrics(rows, "absolute_error_with_validated_corrections_seconds")
    summary = {
        "episode_id": episode["id"],
        "reference_boundaries": len(rows),
        "algorithm_detections": len(detections),
        "matched_boundaries": baseline_metrics["error_share_denominator"],
        "undetected_boundaries": len(rows) - baseline_metrics["error_share_denominator"],
        "unmatched_algorithm_detections": len(unused),
        "potential_false_detections": [
            {
                "detection_id": item["detection_id"],
                "algorithm_time_seconds": item["algorithm_time_seconds"],
                "confidence": item["confidence"],
            }
            for item in sorted(unused.values(), key=lambda value: value["algorithm_time_seconds"])
        ],
        **baseline_metrics,
        "regression": regression(rows),
        "comparison_scenarios": {
            "without_validated_corrections": {
                **baseline_metrics,
                "regression": regression(rows),
            },
            "with_validated_corrections": {
                **corrected_metrics,
                "regression": regression(
                    rows,
                    reference_key="lab_reference_time_seconds",
                    error_key="signed_error_with_validated_corrections_seconds",
                ),
            },
        },
    }
    return rows, summary


def csv_value(value):
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return value


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        return
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({key: csv_value(value) for key, value in row.items()})


def timestamp(seconds: float | None) -> str:
    if seconds is None:
        return "—"
    hours, remainder = divmod(float(seconds), 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{int(hours):02d}:{int(minutes):02d}:{secs:06.3f}"


def percent(value: float | None) -> str:
    return "—" if value is None else f"{100 * value:.1f} %"


def md(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def build_report(
    protocol: dict,
    episodes: list[dict],
    detections: list[dict],
    comparisons: list[dict],
    summaries: list[dict],
) -> str:
    episode_map = {episode["id"]: episode for episode in episodes}
    summary_map = {item["episode_id"]: item for item in summaries}
    matched_by_detection = {
        row["detection_id"]: row for row in comparisons if row["detected"]
    }
    lines = [
        "# Podmix VAD — découpe complète de PTR492 et PTR493",
        "",
        "## Statut",
        "",
        "**Expérience de laboratoire terminée sur les deux épisodes; deux corrections sont validées "
        "dans les sorties du laboratoire uniquement.** "
        "Les médias RSS complets ont été segmentés par INA puis Silero sur CPU. Les deux candidats "
        "historiques `PTR492 +0,740 s` et `PTR493 +3,080 s` réapparaissent comme sorties brutes de "
        "l'algorithme gelé. Emmanuel a confirmé leur découpe à l'écoute puis répondu « Oui » à "
        "16:07 le 10 octobre 2026 à la demande d'appliquer les deux corrections en labo. Elles sont "
        "donc marquées **validées pour le labo**, avec leurs temps originaux conservés.",
        "",
        "Aucune production, base SQLite, donnée applicative, chapitre, API, dépendance de production, "
        "service, conteneur, APK, configuration, déploiement ou branche `main` n'a été modifié. "
        "Aucun audio n'est ajouté au dépôt et aucune fusion n'est effectuée.",
        "",
        "## Méthode gelée avant comparaison",
        "",
        f"Le protocole a été figé le `{protocol['frozen_at']}` dans "
        "`lab/vad_prototype/full_episode_protocol.json`, avant la génération du premier tableau "
        "d'écarts. Le détecteur ne lit aucun temps CueNation. Il analyse chaque épisode par fenêtres "
        f"de {protocol['windowing']['window_seconds']} s, recouvertes de "
        f"{protocol['windowing']['overlap_seconds']} s; seul le cœur de chaque fenêtre est conservé, "
        "ce qui produit une segmentation globale continue.",
        "",
        "Paramètres fixes :",
        "",
        f"- INA `sm`, sans genre, batch {protocol['ina']['batch_size']}, ratio énergie "
        f"{protocol['ina']['energy_ratio']};",
        f"- Silero 16 kHz, seuil {protocol['silero']['threshold']}, seuil de sortie "
        f"{protocol['silero']['negative_threshold']}, parole minimale "
        f"{protocol['silero']['min_speech_ms']} ms, silence minimal "
        f"{protocol['silero']['min_silence_ms']} ms et marge "
        f"{protocol['silero']['speech_pad_ms']} ms;",
        f"- candidat = fin d'une plage INA de parole d'au moins "
        f"{protocol['detector']['min_ina_speech_seconds']:.2f} s, immédiatement suivie d'au moins "
        f"{protocol['detector']['min_return_to_music_seconds']:.1f} s de musique, avec recouvrement "
        f"Silero ≥ {protocol['detector']['min_silero_overlap_seconds']:.2f} s et fins des deux modèles "
        f"séparées de ≤ {protocol['detector']['max_ina_silero_end_disagreement_seconds']:.1f} s;",
        f"- candidats distants de ≤ {protocol['detector']['deduplication_seconds']:.1f} s regroupés en "
        "gardant la confiance la plus forte;",
        "- confiance de 0 à 1 : continuité musicale INA (35 %), accord des fins INA/Silero (35 %), "
        "recouvrement de parole (20 %) et durée de parole INA (10 %). Cette valeur est un score "
        "interne, pas une probabilité calibrée.",
        "",
        "La comparaison est postérieure : pour chaque temps CueNation croissant, la détection non "
        "encore utilisée la plus proche est retenue si elle est à 20 s ou moins. L'écart signé vaut "
        "`temps_algorithme - temps_cuesheet`. Une détection non appariée est seulement une fausse "
        "détection **potentielle**, faute d'annotation humaine de la voix et des transitions.",
        "",
        "## Résultats synthétiques",
        "",
        "| Épisode | Références | Détections | Appariées | Non détectées | Détections sans équivalent | Médiane abs. | Moyenne abs. | < 1 s | < 3 s | < 10 s |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for episode in episodes:
        summary = summary_map[episode["id"]]
        lines.append(
            f"| {episode['id'].replace('pure-trance-radio-', 'PTR')} | "
            f"{summary['reference_boundaries']} | {summary['algorithm_detections']} | "
            f"{summary['matched_boundaries']} | {summary['undetected_boundaries']} | "
            f"{summary['unmatched_algorithm_detections']} | "
            f"{summary['median_absolute_error_seconds']:.3f} s | "
            f"{summary['mean_absolute_error_seconds']:.3f} s | "
            f"{percent(summary['share_absolute_error_lt_1'])} | "
            f"{percent(summary['share_absolute_error_lt_3'])} | "
            f"{percent(summary['share_absolute_error_lt_10'])} |"
        )
    lines += [
        "",
        "Les parts et les moyennes utilisent seulement les frontières appariées : 11 pour PTR492 et "
        "9 pour PTR493. Les non-détections ne sont donc pas transformées artificiellement en erreurs "
        "de 20 s.",
        "",
        "## Écarts avec et sans les deux corrections validées",
        "",
        "Les appariements et les sorties algorithmiques sont inchangés. Le scénario « avec » remplace "
        "uniquement les deux références validées par `82,740 s` et `2 353,080 s`; toutes les autres "
        "références restent les temps CueNation d'origine.",
        "",
        "| Épisode | Frontière validée | Écart sans correction | Écart avec correction | Moyenne abs. sans | Moyenne abs. avec | < 1 s sans | < 1 s avec | < 3 s sans | < 3 s avec |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    validated_by_episode = {
        item["episode_id"]: item for item in protocol.get("human_validated_corrections", [])
    }
    for episode in episodes:
        summary = summary_map[episode["id"]]
        before = summary["comparison_scenarios"]["without_validated_corrections"]
        after = summary["comparison_scenarios"]["with_validated_corrections"]
        correction = validated_by_episode[episode["id"]]
        lines.append(
            f"| {episode['id'].replace('pure-trance-radio-', 'PTR')} | piste "
            f"{correction['track_number']} : {correction['original_time_seconds']:.3f} → "
            f"{correction['corrected_time_seconds']:.3f} s | "
            f"{correction['delta_seconds']:+.3f} s | +0.000 s | "
            f"{before['mean_absolute_error_seconds']:.3f} s | "
            f"{after['mean_absolute_error_seconds']:.3f} s | "
            f"{percent(before['share_absolute_error_lt_1'])} | "
            f"{percent(after['share_absolute_error_lt_1'])} | "
            f"{percent(before['share_absolute_error_lt_3'])} | "
            f"{percent(after['share_absolute_error_lt_3'])} |"
        )
    lines += [
        "",
        "Ces chiffres décrivent deux scénarios de référence dans le laboratoire; ils ne prouvent pas "
        "un alignement absolu entre CueNation et l'audio RSS.",
        "",
        "## Toutes les frontières détectées",
        "",
    ]
    for episode in episodes:
        episode_id = episode["id"]
        label = episode_id.replace("pure-trance-radio-", "PTR")
        lines += [
            f"### {label}",
            "",
            "| ID | Temps absolu | Secondes | Confiance | Équivalent CueNation |",
            "|---|---:|---:|---:|---|",
        ]
        for item in (value for value in detections if value["episode_id"] == episode_id):
            matched = matched_by_detection.get(item["detection_id"])
            equivalent = (
                f"piste {matched['track_number']}, écart {matched['signed_error_seconds']:+.3f} s"
                if matched else "aucun à ±20 s (potentielle fausse détection)"
            )
            lines.append(
                f"| {item['detection_id'].rsplit('-', 1)[-1]} | {timestamp(item['algorithm_time_seconds'])} | "
                f"{item['algorithm_time_seconds']:.3f} | {item['confidence']:.4f} | {equivalent} |"
            )
        lines.append("")
    lines += [
        "## Comparaison frontière par frontière",
        "",
    ]
    for episode in episodes:
        episode_id = episode["id"]
        label = episode_id.replace("pure-trance-radio-", "PTR")
        lines += [
            f"### {label}",
            "",
            "| Piste | Titre | CueNation original | Correction labo | Algorithme | Confiance | Écart sans | Écart avec |",
            "|---:|---|---:|---:|---:|---:|---:|---:|",
        ]
        for row in (value for value in comparisons if value["episode_id"] == episode_id):
            if row["detected"]:
                algorithm = f"{timestamp(row['algorithm_time_seconds'])} ({row['algorithm_time_seconds']:.3f} s)"
                confidence_value = f"{row['confidence']:.4f}"
                error = f"{row['signed_error_seconds']:+.3f} s"
                corrected_error = f"{row['signed_error_with_validated_corrections_seconds']:+.3f} s"
            else:
                algorithm, confidence_value, error, corrected_error = "non détectée", "—", "—", "—"
            correction = (
                f"{timestamp(row['validated_corrected_time_seconds'])} "
                f"({row['validated_corrected_time_seconds']:.3f} s)"
                if row["validated_corrected_time_seconds"] is not None else "—"
            )
            lines.append(
                f"| {row['track_number']} | {md(row['track_title'])} | "
                f"{timestamp(row['cuesheet_time_seconds'])} ({row['cuesheet_time_seconds']:.3f} s) | "
                f"{correction} | {algorithm} | {confidence_value} | {error} | {corrected_error} |"
            )
        lines.append("")
    lines += [
        "## Décalage constant et dérive",
        "",
    ]
    for episode in episodes:
        label = episode["id"].replace("pure-trance-radio-", "PTR")
        regression_data = summary_map[episode["id"]]["regression"]
        offset_result = "détecté" if regression_data["constant_offset_detected"] else "non détecté"
        drift_result = "détectée" if regression_data["drift_detected"] else "non détectée"
        lines.append(
            f"- **{label}** : écart signé moyen {regression_data['mean_signed_offset_seconds']:+.3f} s, "
            f"IC 95 % [{regression_data['mean_signed_offset_ci95_seconds'][0]:+.3f}; "
            f"{regression_data['mean_signed_offset_ci95_seconds'][1]:+.3f}] — décalage constant "
            f"exploratoire {offset_result}. Pente {regression_data['slope_seconds_per_hour']:+.3f} s/h, "
            f"IC 95 % [{regression_data['slope_ci95_seconds_per_hour'][0]:+.3f}; "
            f"{regression_data['slope_ci95_seconds_per_hour'][1]:+.3f}], "
            f"R² {regression_data['r_squared']:.3f} — dérive {drift_result}."
        )
    lines += [
        "",
        "Ces tests sont exploratoires, utilisent seulement les frontières détectées et des intervalles "
        "normaux malgré de petits effectifs. Pour PTR492, le biais moyen négatif est détectable dans "
        "les 11 appariements, mais la pente n'est pas significative; il ne prouve ni un décalage du "
        "RSS ni une erreur de CueNation. Pour PTR493, ni décalage constant ni dérive ne sont détectés.",
        "",
        "## Réserve d'alignement et interprétation",
        "",
        "L'alignement entre la cuesheet et l'enclosure RSS reste **non vérifié**. La durée RSS dépasse "
        "la durée CueNation d'environ 2,6 s pour PTR492 et 3,8 s pour PTR493. Un écart au temps "
        "CueNation ne peut donc pas être présenté sans réserve comme une erreur de l'algorithme. En "
        "outre, INA et Silero peuvent confondre voix DJ, chant, rap, jingle ou voice-over : les 14 et "
        "9 détections sans équivalent sont potentielles, pas des faux positifs confirmés.",
        "",
        "Le détecteur retrouve notamment `82,740 s` sur PTR492 et `2 353,080 s` sur PTR493. Ces deux "
        "frontières sont validées par l'écoute d'Emmanuel pour le laboratoire uniquement. Les autres "
        "candidats `PTR493 +4,140 s`, `+1,160 s`, `+2,780 s` et `JOC +2,500 s` restent explicitement "
        "non validés. Aucune de ces mentions n'autorise une modification des chapitres réels.",
        "",
        "## Reproductibilité et livrables",
        "",
        "- `lab/vad_prototype/full_episode_protocol.json` : protocole gelé;",
        "- `lab/vad_prototype/run_full_episode.py` : segmentation complète, séquentielle et CPU;",
        "- `lab/vad_prototype/analyze_full_episode.py` : détection, appariement, statistiques et rapport;",
        "- `lab/vad_prototype/results/full-episode-detections.{csv,json}` : toutes les détections;",
        "- `lab/vad_prototype/results/full-episode-comparison.{csv,json}` : les 42 références;",
        "- `lab/vad_prototype/results/full-episode-summary.json` : synthèse et régressions.",
        "",
        "Les checkpoints complets restent dans `lab/vad_prototype/output/full_episode/`, ignoré par "
        "Git avec les médias. Les exécutions ont été séquentielles sous `nice -n 10`, CPU seul, avec "
        "deux threads. PTR492 : Silero 29,533 s et INA 260,744 s mur; PTR493 : Silero 26,076 s et "
        "INA 223,893 s mur. Aucun nouvel extrait audio n'a été nécessaire.",
        "",
        "## Conclusion",
        "",
        "La découpe globale est techniquement reproductible mais insuffisante pour une activation en production : "
        "elle apparie 11/22 frontières de PTR492 et 9/20 de PTR493 à ±20 s, avec respectivement 14 "
        "et 9 détections supplémentaires potentielles. Les deux corrections validées sont appliquées "
        "uniquement aux sorties comparatives du laboratoire; Podmix reste inchangé.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    episodes_config = json.loads(EPISODES.read_text())
    protocol = json.loads(PROTOCOL.read_text())
    selected = [episode for episode in episodes_config["episodes"] if episode["id"] in protocol["episodes"]]
    selected.sort(key=lambda episode: protocol["episodes"].index(episode["id"]))
    tracked_detections_path = RESULTS / "full-episode-detections.json"
    tracked_detections = (
        json.loads(tracked_detections_path.read_text())
        if tracked_detections_path.exists() else []
    )
    all_detections = []
    all_comparisons = []
    summaries = []
    for episode in selected:
        raw_available = all(
            (OUTPUT / f"{episode['id']}-{method}.json").exists()
            for method in ("ina", "silero")
        )
        detections = (
            detect(episode, protocol) if raw_available else
            [item for item in tracked_detections if item["episode_id"] == episode["id"]]
        )
        if not detections:
            raise FileNotFoundError(
                f"Aucune segmentation brute ou detection suivie pour {episode['id']}"
            )
        comparisons, summary = compare(episode, detections, protocol)
        all_detections.extend(detections)
        all_comparisons.extend(comparisons)
        summaries.append(summary)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "full-episode-detections.json").write_text(json.dumps(all_detections, ensure_ascii=False, indent=2) + "\n")
    (RESULTS / "full-episode-comparison.json").write_text(json.dumps(all_comparisons, ensure_ascii=False, indent=2) + "\n")
    summary_doc = {
        "protocol": "full_episode_protocol.json",
        "matching": protocol["comparison"],
        "alignment_warning": "L'alignement cuesheet CueNation / audio RSS n'est pas verifie; les ecarts ne sont donc pas assimilables sans reserve a des erreurs de l'algorithme.",
        "human_validation": {
            "scope": "lab_only",
            "corrections": protocol.get("human_validated_corrections", []),
            "explicitly_non_validated_candidates": protocol.get(
                "explicitly_non_validated_candidates", []
            ),
        },
        "episodes": summaries,
    }
    (RESULTS / "full-episode-summary.json").write_text(json.dumps(summary_doc, ensure_ascii=False, indent=2) + "\n")
    write_csv(RESULTS / "full-episode-detections.csv", all_detections)
    write_csv(RESULTS / "full-episode-comparison.csv", all_comparisons)
    RESPONSES.mkdir(exist_ok=True)
    (RESPONSES / REPORT_NAME).write_text(
        build_report(protocol, selected, all_detections, all_comparisons, summaries),
        encoding="utf-8",
    )
    print(json.dumps(summary_doc, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
