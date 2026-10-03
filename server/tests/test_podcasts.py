from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from podcasts import search_podcasts


class _Response:
    def __init__(self, payload: dict):
        self.payload = json.dumps(payload).encode()

    def read(self):
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class PodcastSearchTests(unittest.TestCase):
    def test_only_results_with_an_rss_feed_are_returned(self):
        payload = {
            "results": [
                {
                    "collectionId": 42,
                    "collectionName": "Le bon podcast",
                    "artistName": "Podmix",
                    "feedUrl": "https://example.com/feed.xml",
                    "artworkUrl600": "https://example.com/cover.jpg",
                    "primaryGenreName": "Musique",
                    "trackCount": 12,
                },
                {"collectionId": 99, "collectionName": "Sans flux"},
            ]
        }
        with patch("podcasts.urlopen", return_value=_Response(payload)):
            results = search_podcasts("podmix")

        self.assertEqual(["Le bon podcast"], [item["title"] for item in results])
        self.assertEqual("https://example.com/feed.xml", results[0]["feedUrl"])

    def test_short_query_does_not_call_the_directory(self):
        with patch("podcasts.urlopen") as request:
            self.assertEqual([], search_podcasts("p"))
        request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
