#!/usr/bin/env python3
"""Convertit une sauvegarde SQLite Room de Podmix historique en JSON Podmix Next."""

from __future__ import annotations

import argparse
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


def iso_timestamp(milliseconds: int | None) -> str:
    if not milliseconds:
        return ""
    return datetime.fromtimestamp(milliseconds / 1000, timezone.utc).isoformat()


def value(row: sqlite3.Row, key: str, default=None):
    return row[key] if key in row.keys() and row[key] is not None else default


def migrate(database_path: Path) -> dict:
    connection = sqlite3.connect(f"file:{database_path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {"podcasts", "episodes", "tracks"}
    if not required.issubset(tables):
        raise ValueError(f"Base Room invalide : tables manquantes {sorted(required - tables)}")
    podcasts = list(connection.execute("SELECT * FROM podcasts"))
    episodes = list(connection.execute("SELECT * FROM episodes"))
    tracks = list(connection.execute("SELECT * FROM tracks ORDER BY episodeId, position"))
    tracks_by_episode: dict[int, list[dict]] = {}
    favorite_track_ids = []
    for track in tracks:
        track_id = int(track["id"])
        episode_id = int(track["episodeId"])
        track_index = len(tracks_by_episode.get(episode_id, []))
        converted = {
            "id": track_id,
            "time": float(value(track, "startTimeSec", 0)),
            "artist": str(value(track, "artist", "Artiste inconnu")),
            "title": str(value(track, "title", "Titre inconnu")),
            "confidence": 100 if value(track, "source") == "manual" else 80,
            "source": "manual" if value(track, "source") == "manual" else "detected",
            "verified": bool(value(track, "isFavorite", 0)),
            "artworkUrl": value(track, "artworkUrl"),
            "spotifyUrl": value(track, "spotifyUrl"),
            "deezerUrl": value(track, "deezerUrl"),
            "evidence": [f"Migration Room · source {value(track, 'source', 'inconnue')}"],
        }
        tracks_by_episode.setdefault(episode_id, []).append(converted)
        if value(track, "isFavorite", 0):
            favorite_track_ids.append(
                f"legacy-episode:{episode_id}::track::{track_id}::{track_index}"
            )
    episodes_by_podcast: dict[int, list[dict]] = {}
    history = []
    for episode in episodes:
        episode_id = int(episode["id"])
        converted = {
            "id": f"legacy-episode:{episode_id}",
            "title": str(value(episode, "title", "Épisode")),
            "description": str(value(episode, "description", "")),
            "publishedAt": iso_timestamp(value(episode, "datePublished")),
            "duration": str(value(episode, "durationSeconds", 0)),
            "audioUrl": str(value(episode, "audioUrl", "")),
            "artworkUrl": str(value(episode, "artworkUrl", "")),
            "tracks": tracks_by_episode.get(episode_id, []),
        }
        episodes_by_podcast.setdefault(int(episode["podcastId"]), []).append(converted)
        progress = int(value(episode, "progressSeconds", 0))
        if progress > 0 and converted["audioUrl"]:
            history.append({
                "id": converted["id"],
                "title": converted["title"],
                "artist": "",
                "url": converted["audioUrl"],
                "position": progress,
                "playedAt": datetime.now(timezone.utc).isoformat(),
            })
    catalog = []
    favorite_source_ids = []
    for podcast in podcasts:
        podcast_id = int(podcast["id"])
        source_id = f"legacy-source:{podcast_id}"
        kind = "dj" if value(podcast, "type", "podcast") == "dj" else "podcast"
        source = {
            "id": source_id,
            "kind": kind,
            "title": str(value(podcast, "name", "Source Podmix")),
            "description": str(value(podcast, "description", "")),
            "artworkUrl": str(value(podcast, "logoUrl", "")),
            "feedUrl": value(podcast, "rssFeedUrl"),
            "episodes": episodes_by_podcast.get(podcast_id, []),
        }
        catalog.append(source)
        if any(value(episode, "isFavorite", 0) for episode in episodes if int(episode["podcastId"]) == podcast_id):
            favorite_source_ids.append(source_id)
    connection.close()
    return {
        "format": "podmix-next-backup",
        "version": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "catalog": catalog,
        "favoriteSourceIds": favorite_source_ids,
        "favoriteTrackIds": favorite_track_ids,
        "history": history,
        "offlineEpisodes": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("database", type=Path, help="Fichier SQLite Room de l’ancienne APK")
    parser.add_argument("-o", "--output", type=Path, default=Path("podmix-migration.json"))
    args = parser.parse_args()
    payload = migrate(args.database.resolve())
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    print(f"{len(payload['catalog'])} sources migrées vers {args.output}")


if __name__ == "__main__":
    main()
