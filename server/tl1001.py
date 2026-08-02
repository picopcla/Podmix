"""Recherche et extraction contrôlées de tracklists depuis 1001Tracklists."""

from __future__ import annotations

import re
from urllib.parse import urlencode, urljoin, urlparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup

BASE_URL = "https://www.1001tracklists.com"
TRACKLIST_PATH = re.compile(r"^/tracklist/[a-z0-9]+/[^/?#]+\.html$")
TIMESTAMP = re.compile(r"(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)")
SEPARATOR = re.compile(r"\s+[-–—]\s+", re.UNICODE)
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36 Podmix/2.0"


def validate_tracklist_url(url: str) -> str:
    parsed = urlparse(url.strip())
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or host not in {"1001tracklists.com", "www.1001tracklists.com"}:
        raise ValueError("Seules les URL HTTPS de 1001Tracklists sont acceptées")
    if not TRACKLIST_PATH.match(parsed.path):
        raise ValueError("L’URL doit pointer vers une page /tracklist/…")
    return f"{BASE_URL}{parsed.path}"


def _fetch(url: str, form: dict[str, str] | None = None) -> str:
    request = Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
        "Referer": f"{BASE_URL}/",
        **({"Content-Type": "application/x-www-form-urlencoded"} if form else {}),
    }, data=urlencode(form).encode() if form else None)
    with urlopen(request, timeout=20) as response:
        html = response.read().decode("utf-8", errors="replace")
    lowered = html.casefold()
    if any(marker in lowered for marker in (
        "cf-chl-", "just a moment...", "attention required! | cloudflare",
        "your ip has been blocked",
    )):
        raise RuntimeError("1001Tracklists bloque temporairement l’accès du VPS")
    return html


def _seconds(text: str) -> float | None:
    match = TIMESTAMP.search(text)
    if not match:
        return None
    if match.group(3):
        return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3))
    return int(match.group(1)) * 60 + int(match.group(2))


def _identity(value: str) -> tuple[str, str]:
    cleaned = re.sub(r"\s+", " ", value).strip(" -–—")
    parts = SEPARATOR.split(cleaned, maxsplit=1)
    if len(parts) == 2:
        return parts[0].strip(), parts[1].strip()
    return "Artiste inconnu", cleaned


def parse_tracklist_html(html: str, source_url: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    tracks: list[dict] = []
    seen: set[tuple[str, str, float | None]] = set()
    for element in soup.select("div.tlpItem, div.tlpTog"):
        value_element = element.select_one("span.trackValue")
        value = value_element.get_text(" ", strip=True) if value_element else ""
        if not value:
            meta = element.select_one('meta[itemprop="name"]')
            value = str(meta.get("content") or "").strip() if meta else ""
        if not value:
            continue
        cue_seconds = element.select_one('input[id$="_cue_seconds"]')
        try:
            provided_time = float(cue_seconds.get("value")) if cue_seconds and cue_seconds.get("value") else None
        except (TypeError, ValueError):
            provided_time = None
        if provided_time is None:
            cue = element.select_one("span.cueValueField, span.cueVal, span.timing")
            provided_time = _seconds(cue.get_text(" ", strip=True)) if cue else None
        artist, track_title = _identity(value)
        if not track_title:
            continue
        identity = (artist.casefold(), track_title.casefold(), provided_time)
        if identity in seen:
            continue
        seen.add(identity)
        tracks.append({"artist": artist, "title": track_title, "providedTime": provided_time})
    return {"sourceUrl": source_url, "title": title, "candidateCount": len(tracks), "candidates": tracks}


def scrape_tracklist(url: str) -> dict:
    safe_url = validate_tracklist_url(url)
    return parse_tracklist_html(_fetch(safe_url), safe_url)


def search_tracklist(query: str) -> dict:
    term = re.sub(r"\s+", " ", query).strip()
    if len(term) < 3 or len(term) > 180:
        raise ValueError("La recherche doit contenir entre 3 et 180 caractères")
    search_url = f"{BASE_URL}/search/result.php"
    soup = BeautifulSoup(_fetch(search_url, {
        "search_selection": "1",
        "main_search": term,
    }), "html.parser")
    candidates = []
    for anchor in soup.select('div.bItm.action.oItm a[href*="/tracklist/"], div.bItm a[href*="/tracklist/"], a[href*="/tracklist/"]'):
        absolute = urljoin(BASE_URL, str(anchor.get("href") or ""))
        try:
            safe = validate_tracklist_url(absolute)
        except ValueError:
            continue
        if safe not in candidates:
            candidates.append(safe)
        if len(candidates) >= 10:
            break
    if not candidates:
        return {"query": term, "sourceUrl": "", "title": "", "candidateCount": 0, "candidates": []}
    result = scrape_tracklist(candidates[0])
    return {"query": term, "resultCount": len(candidates), **result}
