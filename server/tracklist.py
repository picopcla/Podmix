"""Parsing et normalisation de tracklists textuelles horodatées."""

from __future__ import annotations

import re

TIMESTAMP = re.compile(r"^\s*[\[(]?(\d{1,2}):(\d{2})(?::(\d{2}))?[\])]?(?:\s+|[-–—]\s*)")
TRAILING_TIMESTAMP = re.compile(r"\s*[\[(]?(\d{1,2}):(\d{2})(?::(\d{2}))?:?[\])]?[\s]*$")
SEPARATOR = re.compile(r"\s+[-–—]\s+")
NUMBERED = re.compile(r"^\s*\d{1,3}(?:[.)]|\s+)\s*")
TRACKLIST_HEADER = re.compile(r"\btrack\s*list\b", re.IGNORECASE)
MIX_METADATA = re.compile(r"\[[^\]]+\]|\([^)]{1,40}\)")


def _time(match: re.Match) -> float:
    if match.group(3):
        return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3))
    return int(match.group(1)) * 60 + int(match.group(2))


def _identity(text: str) -> tuple[str, str]:
    cleaned = re.sub(r"^\s*\d{1,3}[.)]\s*", "", text).strip(" -–—")
    parts = SEPARATOR.split(cleaned, maxsplit=1)
    return (parts[0].strip(), parts[1].strip()) if len(parts) == 2 else ("Artiste inconnu", cleaned)


def parse_tracklist(text: str, structured_only: bool = False) -> list[dict]:
    parsed = []
    inside_tracklist = False
    normalized = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    normalized = re.sub(r"</?(?:p|div|li|ul|ol)[^>]*>", "\n", normalized, flags=re.IGNORECASE)
    normalized = re.sub(r"<[^>]+>", " ", normalized)
    for line in normalized.splitlines():
        line = re.sub(r"\s+", " ", line).strip()
        if not line or len(line) > 300:
            continue
        if TRACKLIST_HEADER.search(line):
            inside_tracklist = True
            # Certains flux placent le premier titre après « Tracklist: » sur
            # la même ligne. L'introduction ne doit pas devenir un titre.
            line = re.split(r"\btrack\s*list\b\s*:?", line, maxsplit=1, flags=re.IGNORECASE)[-1].strip()
            if not line:
                continue
        numbered = NUMBERED.match(line)
        content = NUMBERED.sub("", line, count=1).strip()
        timestamp = TIMESTAMP.match(content)
        without_time = content[timestamp.end():].strip() if timestamp else content
        if not timestamp:
            trailing_timestamp = TRAILING_TIMESTAMP.search(content)
            if trailing_timestamp:
                timestamp = trailing_timestamp
                without_time = content[:trailing_timestamp.start()].rstrip(" -–—")
        if structured_only and not timestamp and not numbered and not inside_tracklist:
            continue
        if not timestamp and not numbered and not SEPARATOR.search(without_time):
            continue
        artist, title = _identity(without_time)
        if not title or len(title) > 180 or len(artist) > 120:
            continue
        parsed.append({
            "artist": artist,
            "title": title,
            "providedTime": _time(timestamp) if timestamp else None,
        })
    return parsed


def first_track_match_score(candidates: list[dict], reference: dict | None) -> float:
    if not candidates or not reference:
        return 0.0

    def tokens(value: str) -> set[str]:
        cleaned = MIX_METADATA.sub(" ", value.casefold())
        cleaned = re.sub(r"\b(?:and|feat|ft|pres|presents|vs|versus|x)\b", " ", cleaned)
        return set(re.findall(r"[a-z0-9]+", cleaned))

    reference_tokens = tokens(f"{reference.get('artist', '')} {reference.get('title', '')}")
    if not reference_tokens:
        return 0.0

    best_score = 0.0
    # Chercher l'ancre RSS dans les 3 premières pistes 1001 pour tolérer les Intros/IDs
    for candidate in candidates[:3]:
        candidate_tokens = tokens(f"{candidate.get('artist', '')} {candidate.get('title', '')}")
        if not candidate_tokens:
            continue
        score = len(candidate_tokens & reference_tokens) / max(1, min(len(candidate_tokens), len(reference_tokens)))
        if score > best_score:
            best_score = score

    return best_score


def apply_external_timestamps(reference: list[dict], timestamped: list[dict]) -> list[dict]:
    """Preserve an established tracklist and copy explicit external timestamps by index."""

    if not reference or len(reference) != len(timestamped):
        return timestamped
    if first_track_match_score(timestamped, reference[0]) < 0.5:
        return timestamped
    merged: list[dict] = []
    for index, item in enumerate(reference):
        merged_item = dict(item)
        if timestamped[index].get("providedTime") is not None:
            merged_item["providedTime"] = timestamped[index]["providedTime"]
        merged.append(merged_item)
    return merged


def align_tracklist(
    candidates: list[dict],
    existing_tracks: list[dict],
    duration: float | None,
    timestamp_source: str = "manual",
) -> list[dict]:
    """Build tracks from explicit timestamps only; never invent media positions."""
    del duration
    existing_by_index = {
        index: track for index, track in enumerate(existing_tracks)
        if track.get("time") is not None
    }
    tracks = []
    for index, candidate in enumerate(candidates):
        provided = candidate.get("providedTime")
        existing = existing_by_index.get(index)
        if provided is not None:
            position = round(float(provided), 2)
            confidence = 94 if timestamp_source == "rss" else 90
            evidence = [f"Timestamp fourni : {position:.1f} s"]
            status = "provided"
            score = 1.0 if timestamp_source == "rss" else 0.9
            resolved_source = timestamp_source
        elif existing:
            position = round(float(existing["time"]), 2)
            confidence = int(existing.get("confidence") or 50)
            evidence = list(existing.get("evidence") or ["Repère manuel conservé"])
            status = str(existing.get("timestampStatus") or "manual")
            score = float(existing.get("timestampScore") or 0.5)
            resolved_source = str(existing.get("timestampSource") or "manual")
        else:
            position = None
            confidence = 0
            evidence = ["Titre trouvé · timestamp absent de la source"]
            status = "pending"
            score = 0.0
            resolved_source = "provisional"
        tracks.append({
            "id": 3000 + index,
            "time": position,
            "artist": candidate["artist"],
            "title": candidate["title"],
            "confidence": confidence,
            "source": "detected",
            "timestampSource": resolved_source,
            "timestampScore": score,
            "timestampStatus": status,
            "verified": False,
            "evidence": evidence,
        })
    return tracks
