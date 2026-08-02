"""Recherche web bornée et mise en cache pour trouver des pages de tracklist."""

from __future__ import annotations

import hashlib
import json
import ipaddress
import re
import socket
import unicodedata
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs, quote_plus, unquote, urlparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup

CACHE_DIR = Path(__file__).parent / "data" / "websearch_cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)
CACHE_TTL = timedelta(days=14)
ALLOWED_RESULT_HOSTS = {
    "1001tracklists.com",
    "www.1001tracklists.com",
    "mixesdb.com",
    "www.mixesdb.com",
    "mixcloud.com",
    "www.mixcloud.com",
    "soundcloud.com",
    "www.soundcloud.com",
    "youtube.com",
    "www.youtube.com",
    "youtu.be",
}


def _cache_path(query: str) -> Path:
    digest = hashlib.sha256(query.casefold().encode()).hexdigest()
    return CACHE_DIR / f"{digest}.json"


def _read_cache(query: str) -> list[dict] | None:
    path = _cache_path(query)
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        created = datetime.fromisoformat(payload["createdAt"])
        if datetime.now(timezone.utc) - created > CACHE_TTL:
            return None
        return payload["results"]
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None


def _write_cache(query: str, results: list[dict]) -> None:
    _cache_path(query).write_text(json.dumps({
        "query": query,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "results": results,
    }, ensure_ascii=False), encoding="utf-8")


def _result_url(raw: str) -> str:
    value = unquote(raw.replace("&amp;", "&"))
    parsed = urlparse(value)
    if parsed.hostname and parsed.hostname.endswith("duckduckgo.com"):
        value = parse_qs(parsed.query).get("uddg", [value])[0]
        parsed = urlparse(value)
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in ALLOWED_RESULT_HOSTS:
        return ""
    return value


def _public_address(url: str) -> str:
    host = urlparse(url).hostname or ""
    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        }
    except (socket.gaierror, ValueError):
        return ""
    public = sorted(str(address) for address in addresses if address.is_global)
    return public[0] if public else ""


def _with_addresses(results: list[dict]) -> list[dict]:
    return [{**result, "address": result.get("address") or _public_address(result["url"])} for result in results]


def _tokens(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKD", value.casefold())
    ascii_value = "".join(character for character in normalized if not unicodedata.combining(character))
    return {token for token in re.findall(r"[a-z0-9]+", ascii_value) if len(token) > 1}


def _cached_candidates(query: str, limit: int) -> list[dict]:
    query_tokens = _tokens(query)
    if len(query_tokens) < 2:
        return []
    numeric_tokens = {token for token in query_tokens if token.isdigit()}
    matches: list[tuple[float, dict]] = []
    seen: set[str] = set()
    for path in CACHE_DIR.glob("*.json"):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            created = datetime.fromisoformat(payload["createdAt"])
            if datetime.now(timezone.utc) - created > CACHE_TTL:
                continue
            for result in payload["results"]:
                url = _result_url(str(result.get("url") or ""))
                if not url or url in seen:
                    continue
                haystack = " ".join([
                    str(payload.get("query") or ""),
                    str(result.get("title") or ""),
                    str(result.get("snippet") or ""),
                ])
                haystack_tokens = _tokens(haystack)
                if numeric_tokens and not numeric_tokens.issubset(haystack_tokens):
                    continue
                overlap = len(query_tokens & haystack_tokens)
                score = overlap / len(query_tokens)
                if overlap >= 2 and score >= 0.65:
                    seen.add(url)
                    matches.append((score, {**result, "url": url}))
        except (KeyError, TypeError, ValueError, json.JSONDecodeError, OSError):
            continue
    matches.sort(key=lambda item: item[0], reverse=True)
    return _with_addresses([result for _, result in matches[:limit]])


def search_tracklist_candidates(query: str, limit: int = 8) -> list[dict]:
    term = re.sub(r"\s+", " ", query).strip()
    if len(term) < 3 or len(term) > 180:
        raise ValueError("La recherche doit contenir entre 3 et 180 caractères")
    cached = _read_cache(term)
    if cached:
        return _with_addresses(cached[:limit])
    related_cache = _cached_candidates(term, limit)
    if related_cache:
        return related_cache
    if cached is not None:
        return []

    search_query = f'"{term}" tracklist 1001tracklists'
    request = Request(
        f"https://html.duckduckgo.com/html/?q={quote_plus(search_query)}",
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                          "AppleWebKit/537.36 Chrome/124 Safari/537.36 Podmix/2.0",
            "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
        },
    )
    with urlopen(request, timeout=12) as response:
        html = response.read(2_000_001).decode("utf-8", errors="replace")
    if len(html) > 2_000_000:
        raise ValueError("Réponse de recherche trop volumineuse")

    soup = BeautifulSoup(html, "html.parser")
    results: list[dict] = []
    seen: set[str] = set()
    for block in soup.select(".result"):
        anchor = block.select_one(".result__a")
        if anchor is None:
            continue
        url = _result_url(str(anchor.get("href") or ""))
        if not url or url in seen:
            continue
        seen.add(url)
        snippet = block.select_one(".result__snippet")
        results.append({
            "url": url,
            "title": anchor.get_text(" ", strip=True),
            "snippet": snippet.get_text(" ", strip=True) if snippet else "",
            "domain": (urlparse(url).hostname or "").removeprefix("www."),
        })
        if len(results) >= max(1, min(limit, 10)):
            break
    results = _with_addresses(results)
    _write_cache(term, results)
    return results
