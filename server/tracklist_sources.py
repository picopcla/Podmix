"""Orchestration des sources externes de tracklists."""

from __future__ import annotations

import os

try:
    from .mixesdb import search_tracklist as search_mixesdb
    from .tl1001 import search_tracklist as search_1001
except ImportError:
    from mixesdb import search_tracklist as search_mixesdb
    from tl1001 import search_tracklist as search_1001

SEARCHERS = {
    "mixesdb": search_mixesdb,
    "1001tracklists": search_1001,
}


def configured_sources() -> list[str]:
    raw = os.environ.get(
        "PODMIX_TRACKLIST_SOURCES",
        "mixesdb,1001tracklists",
    )
    names = []
    for value in raw.split(","):
        name = value.strip().casefold()
        if name in SEARCHERS and name not in names:
            names.append(name)
    return names or ["mixesdb", "1001tracklists"]


def search_external_tracklist(query: str) -> dict:
    attempts: list[dict] = []
    for name in configured_sources():
        try:
            result = SEARCHERS[name](query)
            attempts.append({
                "source": name,
                "status": "found" if result.get("candidates") else "empty",
            })
            if result.get("candidates"):
                return {**result, "source": name, "attempts": attempts}
        except Exception as error:
            attempts.append({
                "source": name,
                "status": "failed",
                "error": type(error).__name__,
            })
    return {
        "query": query,
        "source": "",
        "sourceUrl": "",
        "title": "",
        "candidateCount": 0,
        "candidates": [],
        "attempts": attempts,
    }

