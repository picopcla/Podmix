from __future__ import annotations

import sys
import unittest
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from live_sets import AUDIO_FORMAT_SELECTOR, LIVE_SET_RESOLVE_CACHE_TTL_SECONDS, YOUTUBE_PROGRESSIVE_FORMAT_SELECTOR, _deduplicate, _is_probable_set, _score, live_set_resolve_options, parse_live_set_tracklist, search_queries, youtube_progressive_resolve_options


class LiveSetSearchTests(unittest.TestCase):
    def test_keeps_the_artist_and_year_in_the_first_query(self):
        self.assertEqual("Tom Roland 2026", search_queries("Tom Roland 2026")[0])

    def test_rejects_short_videos_but_keeps_unknown_duration(self):
        self.assertFalse(_is_probable_set("Daxon live set", 300))
        self.assertTrue(_is_probable_set("Daxon live set", 0))

    def test_deduplicates_same_set_across_providers(self):
        items = [
            {"title": "Daxon Live 2026", "duration": 3600, "url": "https://www.youtube.com/watch?v=one"},
            {"title": "Daxon Live 2026", "duration": 3610, "url": "https://soundcloud.com/daxon/live"},
        ]
        self.assertEqual(1, len(_deduplicate(items)))

    def test_manual_tracklist_keeps_explicit_timestamps(self):
        result = parse_live_set_tracklist("00:00 Daxon — Opening\n04:32 Ava — Horizon")
        self.assertEqual(2, len(result["tracks"]))
        self.assertEqual(272, result["tracks"][1]["time"])
        self.assertEqual("manuel", result["origin"])

    def test_ranks_the_named_artist_over_a_multi_dj_compilation(self):
        query = "Armin Tomorrowland 2026"
        official = _score(query, {"title": "Armin van Buuren WE1 Tomorrowland 2026", "channel": "Tomorrowland and Armin van Buuren", "duration": 8808})
        compilation = _score(query, {"title": "Tomorrowland 2026 Tiesto David Guetta Martin Garrix Armin van Buuren", "channel": "DJ Dragon", "duration": 13938})
        self.assertGreater(official, compilation)

    def test_live_set_resolution_cache_is_long_enough_for_track_navigation(self):
        self.assertGreaterEqual(LIVE_SET_RESOLVE_CACHE_TTL_SECONDS, 15 * 60)

    def test_youtube_resolution_prefers_audio_capable_player_client(self):
        options = live_set_resolve_options("https://www.youtube.com/watch?v=example")
        self.assertEqual(
            ["android_vr", "android"],
            options["extractor_args"]["youtube"]["player_client"],
        )
        self.assertTrue(options["format"].startswith("bestaudio[ext=m4a]"))

    def test_soundcloud_resolution_does_not_force_a_youtube_client(self):
        options = live_set_resolve_options("https://soundcloud.com/example/live")
        self.assertNotIn("extractor_args", options)

    def test_youtube_progressive_fallback_uses_seekable_android_stream(self):
        options = youtube_progressive_resolve_options("https://www.youtube.com/watch?v=example")
        self.assertEqual(["android"], options["extractor_args"]["youtube"]["player_client"])
        self.assertEqual(YOUTUBE_PROGRESSIVE_FORMAT_SELECTOR, options["format"])

    def test_audio_selector_keeps_cross_provider_fallbacks(self):
        self.assertIn("bestaudio[ext=m4a]", AUDIO_FORMAT_SELECTOR)
        self.assertIn("bestaudio[ext=mp3]", AUDIO_FORMAT_SELECTOR)
        self.assertIn("bestaudio[ext=webm]", AUDIO_FORMAT_SELECTOR)
        self.assertTrue(AUDIO_FORMAT_SELECTOR.endswith("bestaudio/best"))


if __name__ == "__main__":
    unittest.main()
