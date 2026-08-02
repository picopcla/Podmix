"""Parsing et alignement d'une tracklist textuelle sur des transitions audio."""

from __future__ import annotations

import re

TIMESTAMP = re.compile(r"^\s*[\[(]?(\d{1,2}):(\d{2})(?::(\d{2}))?[\])]?(?:\s+|[-–—]\s*)")
SEPARATOR = re.compile(r"\s+[-–—]\s+")
NUMBERED = re.compile(r"^\s*\d{1,3}[.)]\s+")
TRACKLIST_HEADER = re.compile(r"\btrack\s*list\b", re.IGNORECASE)


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


def align_tracklist(candidates: list[dict], transitions: list[dict], duration: float | None) -> list[dict]:
    if not candidates:
        return []
    transition_times = sorted({0.0, *(float(track["time"]) for track in transitions)})
    duration = duration or (transition_times[-1] if transition_times else 0)

    def ordinal_time(index: int) -> float:
        if len(candidates) == 1:
            return 0
        if len(transition_times) >= len(candidates):
            anchor_index = round(index * (len(transition_times) - 1) / (len(candidates) - 1))
            return transition_times[anchor_index]
        expected = duration * index / len(candidates) if duration else index * 240
        return min(transition_times, key=lambda value: abs(value - expected)) if len(transition_times) > 1 else expected

    tracks = []
    for index, candidate in enumerate(candidates):
        provided = candidate["providedTime"]
        if provided is not None:
            nearby = [time for time in transition_times if abs(time - provided) <= 12]
            aligned = min(nearby, key=lambda value: abs(value - provided)) if nearby else provided
            delta = abs(aligned - provided)
            confidence = 94 if delta <= 3 else 86 if delta <= 12 else 75
            evidence = [f"Timestamp fourni : {provided:.1f} s"]
            if aligned != provided:
                evidence.append(f"Recalé sur une transition audio : {aligned:.1f} s")
        else:
            aligned = ordinal_time(index)
            confidence = 72 if aligned in transition_times else 58
            evidence = ["Ordre de la tracklist", f"Transition audio : {aligned:.1f} s"]
        tracks.append({
            "id": 3000 + index,
            "time": round(aligned, 2),
            "artist": candidate["artist"],
            "title": candidate["title"],
            "confidence": confidence,
            "source": "detected",
            "verified": False,
            "evidence": evidence,
        })
    return tracks
