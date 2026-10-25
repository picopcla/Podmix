from __future__ import annotations

import io
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import dev_server


class CatalogLinksTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = Path(self.directory.name, "podmix.sqlite3")
        self.database_patch = patch.object(dev_server, "DATABASE", self.database)
        self.database_patch.start()
        dev_server.initialize()

    def tearDown(self):
        self.database_patch.stop()
        self.directory.cleanup()

    def post_links(self, payload: dict) -> tuple[dict, int]:
        body = json.dumps(payload).encode()
        handler = object.__new__(dev_server.Handler)
        handler.path = "/v1/catalog/links"
        handler.headers = {"Content-Length": str(len(body))}
        handler.rfile = io.BytesIO(body)
        captured: dict = {}
        handler.json_response = lambda response, status=200: captured.update(response=response, status=status)
        handler.do_POST()
        return captured["response"], captured["status"]

    def test_links_endpoint_returns_and_caches_youtube_url(self):
        youtube = {
            "url": "https://www.youtube.com/watch?v=remix",
            "artworkUrl": "https://i.ytimg.com/remix.jpg",
        }
        payload = {"tracks": [{"key": "favorite-1", "artist": "Max Richter", "title": "Nuvole Bianche (Yelow Remix)"}]}
        with (
            patch.object(dev_server, "search_deezer", return_value=None) as deezer_search,
            patch.object(dev_server, "search_spotify", return_value=None) as spotify_search,
            patch.object(dev_server, "search_youtube", return_value=youtube) as youtube_search,
        ):
            first, first_status = self.post_links(payload)
            second, second_status = self.post_links(payload)
        self.assertEqual(200, first_status)
        self.assertEqual(200, second_status)
        self.assertEqual("https://www.youtube.com/watch?v=remix", first["items"][0]["youtubeUrl"])
        self.assertEqual(first, second)
        deezer_search.assert_called_once()
        spotify_search.assert_called_once()
        youtube_search.assert_called_once_with("Max Richter", "Nuvole Bianche (Yelow Remix)")
        with dev_server.connect() as database:
            cached = database.execute(
                "SELECT youtube_url FROM catalog_cache WHERE cache_key = ?",
                ("max richter::nuvole bianche (yelow remix)",),
            ).fetchone()
        self.assertEqual("https://www.youtube.com/watch?v=remix", cached["youtube_url"])

    def test_catalog_cache_migration_is_additive(self):
        legacy_database = Path(self.directory.name, "legacy.sqlite3")
        with sqlite3.connect(legacy_database) as database:
            database.execute(
                "CREATE TABLE catalog_cache (cache_key TEXT PRIMARY KEY, response_json TEXT NOT NULL, created_at TEXT NOT NULL)"
            )
            database.execute(
                "INSERT INTO catalog_cache VALUES (?, ?, ?)",
                ("artist::title", '{"accepted": true}', "2026-10-24T00:00:00+00:00"),
            )
        with patch.object(dev_server, "DATABASE", legacy_database):
            dev_server.initialize()
            with dev_server.connect() as database:
                columns = {row[1] for row in database.execute("PRAGMA table_info(catalog_cache)")}
                row = database.execute(
                    "SELECT response_json FROM catalog_cache WHERE cache_key = 'artist::title'"
                ).fetchone()
        self.assertTrue({"deezer_url", "spotify_url", "youtube_url"} <= columns)
        self.assertEqual('{"accepted": true}', row["response_json"])


if __name__ == "__main__":
    unittest.main()
