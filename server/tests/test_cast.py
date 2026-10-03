from __future__ import annotations

import sys
import unittest
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import dev_server


class CastDurationTests(unittest.TestCase):
    def test_known_client_duration_is_kept(self):
        self.assertEqual(
            3600,
            dev_server.resolve_cast_duration("https://media.example/show.mp3", 120, 3600),
        )

    def test_server_never_probes_missing_duration(self):
        self.assertEqual(
            0,
            dev_server.resolve_cast_duration("https://media.example/show.mp3", 1200, 0),
        )

    def test_start_position_is_not_mistaken_for_duration(self):
        self.assertEqual(
            0,
            dev_server.resolve_cast_duration("https://media.example/show.mp3", 1200, 1200),
        )

    def test_normalizes_googlevideo_m4a_to_audio_mp4_for_bose(self):
        self.assertEqual("audio/mp4", dev_server.bose_audio_content_type("video/mp4"))
        self.assertEqual(
            "audio/mp4; charset=binary",
            dev_server.bose_audio_content_type("video/mp4; charset=binary"),
        )

    def test_keeps_existing_audio_content_type(self):
        self.assertEqual("audio/mpeg", dev_server.bose_audio_content_type("audio/mpeg"))

    def test_bose_cast_detects_a_live_set_stream(self):
        url = "https://podmix.mb4.fr/podmix-api/v1/live-sets/stream?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabc"
        parsed = dev_server.urlparse(url)
        self.assertTrue(parsed.path.rstrip("/").endswith("/v1/live-sets/stream"))
        self.assertEqual("https://www.youtube.com/watch?v=abc", dev_server.parse_qs(parsed.query)["url"][0])


if __name__ == "__main__":
    unittest.main()
