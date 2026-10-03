"""Recherche gratuite de tracklists via l'API MediaWiki de MixesDB."""

from __future__ import annotations

import json
import re
import unicodedata
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup

try:
    from .tracklist import parse_tracklist
except ImportError:
    from tracklist import parse_tracklist

API_URL = "https://www.mixesdb.com/w/api.php"
USER_AGENT = "Podmix/1.0 (tracklist research; read-only)"
MAX_RESPONSE_BYTES = 2_000_000
MIXESDB_CUE = re.compile(r"^\[(\d{1,3})(?::(\d{2}))?\]\s*(.+)$")


def _tokens(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKD", value.casefold())
    ascii_value = "".join(
        character for character in normalized
        if not unicodedata.combining(character)
    )
    return {
        token
        for token in re.findall(r"[a-z0-9]+", ascii_value)
        if len(token) > 1
    }


def _get_json(parameters: dict[str, object]) -> dict:
    query = urlencode({**parameters, "format": "json", "utf8": "1"})
    request = Request(
        f"{API_URL}?{query}",
        headers={"Accept": "application/json", "User-Agent": USER_AGENT},
    )
    with urlopen(request, timeout=15) as response:
        content = response.read(MAX_RESPONSE_BYTES + 1)
    if len(content) > MAX_RESPONSE_BYTES:
        raise ValueError("Réponse MixesDB trop volumineuse")
    return json.loads(content.decode("utf-8"))


def parse_mixesdb_html(html: str, title: str = "") -> dict:
    soup = BeautifulSoup(html, "html.parser")
    source_text = soup.get_text("\n", strip=True)

    heading = next(
        (
            item for item in soup.select("h2, h3")
            if "tracklist" in item.get_text(" ", strip=True).casefold()
        ),
        None,
    )
    if heading is None:
        return {
            "sourceUrl": "",
            "title": title,
            "candidateCount": 0,
            "candidates": [],
            "sourceText": source_text,
        }
    ordered_list = None
    for item in heading.find_all_next(["h2", "h3", "ol"]):
        if item.name in {"h2", "h3"}:
            break
        if item.name == "ol":
            ordered_list = item
            break
    if ordered_list is None:
        return {
            "sourceUrl": "",
            "title": title,
            "candidateCount": 0,
            "candidates": [],
            "sourceText": source_text,
        }

    lines: list[str] = []
    for index, item in enumerate(ordered_list.find_all("li", recursive=False), start=1):
        value = re.sub(r"\s+", " ", item.get_text(" ", strip=True)).strip()
        if not value or value == "?":
            continue
        cue = MIXESDB_CUE.match(value)
        if cue:
            minutes = int(cue.group(1))
            seconds = int(cue.group(2) or 0)
            identity = cue.group(3).strip()
            if not identity or identity == "?":
                continue
            total = minutes * 60 + seconds
            hours, remainder = divmod(total, 3600)
            mins, secs = divmod(remainder, 60)
            timestamp = (
                f"{hours}:{mins:02d}:{secs:02d}"
                if hours else f"{mins:02d}:{secs:02d}"
            )
            lines.append(f"{timestamp} {identity}")
        else:
            lines.append(f"{index}. {value}")
    candidates = parse_tracklist("\n".join(lines), structured_only=True)
    return {
        "sourceUrl": "",
        "title": title,
        "candidateCount": len(candidates),
        "candidates": candidates,
        "sourceText": source_text,
    }


def search_tracklist(query: str, limit: int = 6) -> dict:
    term = re.sub(r"\s+", " ", query).strip()
    if len(term) < 3 or len(term) > 180:
        raise ValueError("La recherche doit contenir entre 3 et 180 caractères")
    payload = _get_json({
        "action": "query",
        "list": "search",
        "srsearch": term,
        "srnamespace": 0,
        "srlimit": max(1, min(limit, 10)),
    })
    wanted = _tokens(term)
    wanted_numbers = {token for token in wanted if token.isdigit()}
    ranked: list[tuple[float, str]] = []
    for result in (payload.get("query") or {}).get("search") or []:
        title = str(result.get("title") or "").strip()
        found = _tokens(title)
        if wanted_numbers and not wanted_numbers.issubset(found):
            continue
        overlap = len(wanted & found) / max(1, len(wanted))
        if overlap >= 0.68:
            ranked.append((overlap, title))
    if not ranked:
        return {
            "query": term,
            "source": "mixesdb",
            "sourceUrl": "",
            "title": "",
            "candidateCount": 0,
            "candidates": [],
        }
    ranked.sort(reverse=True)
    title = ranked[0][1]
    page = _get_json({
        "action": "parse",
        "page": title,
        "prop": "text",
    })
    html = str(((page.get("parse") or {}).get("text") or {}).get("*") or "")
    parsed = parse_mixesdb_html(html, title)
    parsed["query"] = term
    parsed["source"] = "mixesdb"
    parsed["sourceUrl"] = (
        "https://www.mixesdb.com/w/"
        + title.replace(" ", "_")
    )
    return parsed
