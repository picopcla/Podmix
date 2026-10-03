from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import dev_server
from share_pages import ShareValidationError, normalize_share_payload, render_share_page


PAYLOAD = {
    "sourceKind": "podcast",
    "sourceTitle": "Mix du soir",
    "episodeTitle": "Épisode 42",
    "artist": "Artiste test",
    "trackTitle": "Titre test",
    "sourceUrl": "https://publisher.example/episode-42",
    "audioUrl": "https://cdn.publisher.example/episode-42.mp3",
    "artworkUrl": "https://images.publisher.example/cover.jpg",
    "spotifyUrl": "https://open.spotify.com/track/abc",
    "deezerUrl": "https://www.deezer.com/track/123",
    "startSeconds": 125,
    "endSeconds": 300,
}


class SharePayloadTests(unittest.TestCase):
    def test_podcast_keeps_the_original_audio_url(self):
        share = normalize_share_payload(PAYLOAD)
        self.assertEqual("https://cdn.publisher.example/episode-42.mp3", share["audio_url"])
        self.assertEqual(125, share["start_seconds"])

    def test_dj_share_never_uses_a_podmix_audio_relay(self):
        share = normalize_share_payload({**PAYLOAD, "sourceKind": "dj"})
        self.assertEqual("", share["audio_url"])

    def test_rejects_non_https_and_oversized_passages(self):
        with self.assertRaises(ShareValidationError):
            normalize_share_payload({**PAYLOAD, "sourceUrl": "http://publisher.example/episode"})
        with self.assertRaises(ShareValidationError):
            normalize_share_payload({**PAYLOAD, "endSeconds": 125 + 20 * 60 + 1})

    def test_ignores_insecure_optional_catalog_links(self):
        share = normalize_share_payload({**PAYLOAD, "artworkUrl": "http://images.example/cover.jpg"})
        self.assertEqual("", share["artwork_url"])

    def test_rendered_page_contains_attribution_and_bounded_player(self):
        share = normalize_share_payload(PAYLOAD)
        page = render_share_page(share, "https://podmix.mb4.fr/s/token", "https://podmix.mb4.fr/podmix/favicon.svg").decode()
        self.assertIn("Artiste test", page)
        self.assertIn("publisher.example/episode-42", page)
        self.assertIn("a.currentTime>=e", page.replace(" ", ""))
        self.assertIn("Podmix ne conserve aucune copie", page)


class ShareStorageTests(unittest.TestCase):
    def test_share_stores_metadata_only_and_reads_with_token(self):
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "podmix.sqlite3"
            with patch.object(dev_server, "DATABASE", database), patch.object(dev_server, "SHARE_PUBLIC_BASE", "https://podmix.test/s"):
                dev_server.initialize()
                created = dev_server.create_share(PAYLOAD)
                self.assertTrue(created["shareUrl"].startswith("https://podmix.test/s/"))
                self.assertIsNotNone(dev_server.get_share(created["id"]))
                with dev_server.connect() as connection:
                    columns = {row[1] for row in connection.execute("PRAGMA table_info(share_pages)")}
                    self.assertNotIn("media_path", columns)
                    self.assertEqual(1, connection.execute("SELECT count(*) FROM share_pages").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
