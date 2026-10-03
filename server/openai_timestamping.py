"""Consolidation optionnelle de tracklists par IA depuis des textes collectés."""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

try:
    from .tracklist import first_track_match_score, parse_tracklist
except ImportError:
    from tracklist import first_track_match_score, parse_tracklist

OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
NOUS_CHAT_COMPLETIONS_URL = "https://inference-api.nousresearch.com/v1/chat/completions"
DEFAULT_MODEL = "Hermes-4-70B"
MAX_INPUT_CHARS = 24_000
MAX_RESPONSE_BYTES = 1_000_000
PROJECT_ROOT = Path(__file__).resolve().parents[1]
LOCAL_PROPERTIES_PATHS = (
    Path("local.properties"),
    Path("android/local.properties"),
    PROJECT_ROOT / "local.properties",
    PROJECT_ROOT / "android" / "local.properties",
)
LOCAL_PROPERTIES_KEY_NAMES = (
    "OPENAI_API_KEY",
    "NOUS_PORTAL_API",
    "nousportalApi",
)


def _local_properties_value() -> str:
    for path in LOCAL_PROPERTIES_PATHS:
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        for line in lines:
            stripped = line.strip()
            if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
                continue
            name, value = stripped.split("=", 1)
            if name.strip() in LOCAL_PROPERTIES_KEY_NAMES:
                return value.strip()
    return ""


def _ai_api_key() -> str:
    return os.environ.get("OPENAI_API_KEY", "").strip() or _local_properties_value()


def _ai_provider() -> str:
    if _local_properties_value() and not os.environ.get("OPENAI_API_KEY", "").strip():
        return "nous"
    return os.environ.get("PODMIX_AI_PROVIDER", "openai").strip().lower()


def openai_timestamping_enabled() -> bool:
    setting = os.environ.get("PODMIX_OPENAI_TIMESTAMPING", "auto").strip().lower()
    return (
        setting not in {"0", "false", "no", "off", "disabled"}
        and bool(_ai_api_key())
    )


def _response_text(payload: dict) -> str:
    if isinstance(payload.get("output_text"), str):
        return payload["output_text"]
    chunks: list[str] = []
    for item in payload.get("output") or []:
        for content in item.get("content") or []:
            text = content.get("text")
            if isinstance(text, str):
                chunks.append(text)
    return "\n".join(chunks)


def _seconds(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)) and value >= 0:
        return float(value)
    if not isinstance(value, str):
        return None
    match = re.search(r"(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)", value)
    if not match:
        return None
    if match.group(3):
        return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3))
    return int(match.group(1)) * 60 + int(match.group(2))


def _normalize_candidates(items: object) -> list[dict]:
    if not isinstance(items, list):
        return []
    candidates: list[dict] = []
    for item in items[:80]:
        if not isinstance(item, dict):
            continue
        artist = re.sub(r"\s+", " ", str(item.get("artist") or "")).strip()
        title = re.sub(r"\s+", " ", str(item.get("title") or "")).strip()
        if not title or len(title) > 180 or len(artist) > 120:
            continue
        candidates.append({
            "artist": artist or "Artiste inconnu",
            "title": title,
            "providedTime": _seconds(item.get("timestamp")),
        })
    return candidates


def _json_schema() -> dict:
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "selectedSourceIndex": {"type": ["integer", "null"]},
            "confidence": {"type": "number"},
            "rationale": {"type": "string"},
            "tracks": {
                "type": "array",
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "timestamp": {"type": ["string", "number", "null"]},
                        "artist": {"type": "string"},
                        "title": {"type": "string"},
                        "sourceIndex": {"type": ["integer", "null"]},
                    },
                    "required": ["timestamp", "artist", "title", "sourceIndex"],
                },
            },
        },
        "required": ["selectedSourceIndex", "confidence", "rationale", "tracks"],
    }


def _call_openai_responses(prompt: str, api_key: str) -> dict | None:
    body = {
        "model": os.environ.get("PODMIX_OPENAI_MODEL", DEFAULT_MODEL),
        "input": prompt,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "podmix_tracklist",
                "strict": True,
                "schema": _json_schema(),
            },
        },
    }
    request = Request(
        os.environ.get("PODMIX_OPENAI_API_URL", OPENAI_RESPONSES_URL),
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=30) as response:
            payload = json.loads(response.read(MAX_RESPONSE_BYTES).decode("utf-8"))
        return json.loads(_response_text(payload))
    except (OSError, URLError, json.JSONDecodeError):
        return None


def _call_nous_chat(prompt: str, api_key: str) -> dict | None:
    body = {
        "model": os.environ.get("PODMIX_NOUS_MODEL", os.environ.get("PODMIX_OPENAI_MODEL", DEFAULT_MODEL)),
        "messages": [
            {
                "role": "system",
                "content": "Return only valid JSON matching the requested schema. No markdown.",
            },
            {"role": "user", "content": prompt},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "podmix_tracklist",
                "strict": True,
                "schema": _json_schema(),
            },
        },
    }
    request = Request(
        os.environ.get("PODMIX_NOUS_API_URL", NOUS_CHAT_COMPLETIONS_URL),
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "podmix/1.0",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=30) as response:
            payload = json.loads(response.read(MAX_RESPONSE_BYTES).decode("utf-8"))
        message = (((payload.get("choices") or [{}])[0].get("message") or {}).get("content") or "")
        return json.loads(message)
    except (OSError, URLError, json.JSONDecodeError):
        return None


def _call_ai(prompt: str) -> dict | None:
    api_key = _ai_api_key()
    if not api_key:
        return None
    if _ai_provider() == "nous":
        return _call_nous_chat(prompt, api_key)
    return _call_openai_responses(prompt, api_key)


def _empty_result(episode_title: str, *, available: bool, evidence: list[str] | None = None) -> dict:
    return {
        "source": _ai_provider(),
        "sourceUrl": "",
        "title": episode_title,
        "candidateCount": 0,
        "candidates": [],
        "available": available,
        "evidence": evidence or [],
    }


def _source_label(source: dict, index: int) -> str:
    kind = str(source.get("kind") or f"source-{index}")
    url = str(source.get("url") or "")
    return f"{kind}: {url}" if url else kind


def extract_tracklist_with_openai(
    text: str,
    *,
    title: str = "",
    source_url: str = "",
) -> dict:
    """Compatibilité : extrait depuis un seul texte via la consolidation centrale."""

    return consolidate_timestamping_sources(
        episode_title=title,
        reference_first_track=None,
        sources=[{"kind": "text", "url": source_url, "title": title, "text": text}],
    )


def consolidate_timestamping_sources(
    *,
    episode_title: str,
    reference_first_track: dict | None = None,
    sources: list[dict],
) -> dict:
    """Extrait une tracklist depuis toutes les sources texte collectées.

    Le modèle est optionnel et validé strictement : pas d'appel sans clé API, rejet
    si le premier morceau ne colle pas à la référence ou si les timestamps sont
    décroissants. PODMIX_OPENAI_TIMESTAMPING=0 permet de le désactiver.
    """

    if not openai_timestamping_enabled():
        return _empty_result(episode_title, available=False)
    usable_sources = [
        source for source in sources
        if len(re.sub(r"\s+", " ", str(source.get("text") or "")).strip()) >= 40
    ][:8]
    if not usable_sources:
        return _empty_result(episode_title, available=True)
    blocks = []
    per_source_budget = max(1_500, MAX_INPUT_CHARS // max(1, len(usable_sources)))
    for index, source in enumerate(usable_sources):
        text = re.sub(r"\s+", " ", str(source.get("text") or "")).strip()
        blocks.append(
            f"[SOURCE {index}] {_source_label(source, index)}\n"
            f"Title: {source.get('title') or ''}\n"
            f"Text:\n{text[:per_source_budget]}"
        )
    reference = ""
    if reference_first_track:
        reference = (
            f"Reference first track: {reference_first_track.get('artist', '')} - "
            f"{reference_first_track.get('title', '')}\n"
        )
    prompt = (
        "You consolidate timestamping sources for a music podcast or DJ mix. "
        "Extract one coherent tracklist from the provided source texts. Prefer "
        "explicit timestamps and the source that matches the episode title. "
        "Return only tracks explicitly present in the source texts. Do not infer, "
        "search, reorder, or invent missing timestamps. If sources disagree, pick "
        "the source whose first track best matches the reference.\n\n"
        f"Episode title: {episode_title}\n{reference}\n"
        + "\n\n".join(blocks)
    )
    decoded = _call_ai(prompt)
    if decoded is None:
        return _empty_result(episode_title, available=True)
    candidates = _normalize_candidates(decoded.get("tracks"))
    if len(candidates) < 2:
        return _empty_result(episode_title, available=True)
    if reference_first_track and first_track_match_score(candidates, reference_first_track) < 0.5:
        return _empty_result(
            episode_title,
            available=True,
            evidence=["IA rejetée : premier morceau incohérent"],
        )
    provided_times = [
        candidate["providedTime"] for candidate in candidates
        if candidate.get("providedTime") is not None
    ]
    if provided_times and provided_times != sorted(provided_times):
        return _empty_result(
            episode_title,
            available=True,
            evidence=["IA rejetée : timestamps décroissants"],
        )
    selected_index = decoded.get("selectedSourceIndex")
    selected = usable_sources[selected_index] if isinstance(selected_index, int) and 0 <= selected_index < len(usable_sources) else {}
    provider_label = "Nous Portal" if _ai_provider() == "nous" else "OpenAI"
    evidence = [f"{provider_label} : consolidation des sources Web/RSS"]
    rationale = re.sub(r"\s+", " ", str(decoded.get("rationale") or "")).strip()
    if rationale:
        evidence.append(f"{provider_label} : {rationale[:180]}")
    return {
        "source": _ai_provider(),
        "sourceUrl": str(selected.get("url") or ""),
        "title": str(selected.get("title") or episode_title),
        "candidateCount": len(candidates),
        "candidates": candidates,
        "available": True,
        "confidence": float(decoded.get("confidence") or 0),
        "evidence": evidence,
    }
