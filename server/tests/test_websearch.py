from __future__ import annotations

import sys
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from websearch import _cached_candidates, _result_url


class WebSearchTests(unittest.TestCase):
    def test_decodes_duckduckgo_redirect_for_allowed_tracklist(self):
        raw = (
            "//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.1001tracklists.com"
            "%2Ftracklist%2Fabc123%2Fexample.html"
        )
        self.assertEqual(
            "https://www.1001tracklists.com/tracklist/abc123/example.html",
            _result_url(raw),
        )

    def test_rejects_unrelated_or_insecure_results(self):
        self.assertEqual("", _result_url("https://example.com/tracklist"))
        self.assertEqual("", _result_url("http://www.1001tracklists.com/tracklist/a/b.html"))

    def test_finds_same_episode_in_related_cached_query(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = {
                "query": "Captive Soul 097",
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "results": [{
                    "url": "https://www.1001tracklists.com/tracklist/abc123/captive-soul-097.html",
                    "title": "Korolova - Captive Soul 097",
                    "snippet": "16 tracks",
                    "domain": "1001tracklists.com",
                    "address": "158.69.5.7",
                }],
            }
            Path(directory, "candidate.json").write_text(json.dumps(payload), encoding="utf-8")
            with patch("websearch.CACHE_DIR", Path(directory)):
                results = _cached_candidates("Korolova Captive Soul 097", 3)
                wrong_episode = _cached_candidates("Korolova Captive Soul 098", 3)
        self.assertEqual(1, len(results))
        self.assertEqual("158.69.5.7", results[0]["address"])
        self.assertEqual([], wrong_episode)


if __name__ == "__main__":
    unittest.main()
