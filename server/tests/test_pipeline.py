from __future__ import annotations

import sys
import unittest
import os
import tempfile
from unittest.mock import patch
from pathlib import Path

import numpy as np

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from discovery import candidates_from_info, validate_media_url
from discovery import _cookie_file
from acoustid import identify_segment
from beatgrid import snap_to_grid
from bs4 import BeautifulSoup

from catalog import _spotify_track_from_page, search_deezer, search_spotify
from feeds import _safe_url
from mixesdb import parse_mixesdb_html
from refiner import score_acoustic_validation, subsequence_dtw
from tl1001 import parse_tracklist_html, validate_tracklist_url
from tracklist import align_tracklist, parse_tracklist


class TracklistPipelineTests(unittest.TestCase):
    def test_deezer_match_exposes_track_artwork(self):
        with patch("catalog._get_json", return_value={
            "data": [{
                "id": 42,
                "link": "https://www.deezer.com/track/42",
                "title": "Mon titre",
                "artist": {"name": "Mon artiste"},
                "album": {"cover_big": "https://cdn.example/cover.jpg"},
            }],
        }):
            result = search_deezer("Mon artiste", "Mon titre")
        self.assertIsNotNone(result)
        self.assertEqual("https://cdn.example/cover.jpg", result["artworkUrl"])

    def test_deezer_normalizes_html_artist_and_label_suffix(self):
        with patch("catalog._get_json", return_value={
            "data": [{
                "id": 43,
                "link": "https://www.deezer.com/track/43",
                "title": "Under The Sun",
                "artist": {"name": "C-Systems"},
                "album": {},
            }],
        }) as request:
            result = search_deezer("C-Systems &amp; MIDI Kittyy", "Under The Sun [Black Hole]")
        self.assertIsNotNone(result)
        self.assertIn("C-Systems+%26+MIDI+Kittyy+Under+The+Sun", request.call_args.args[0])

    def test_spotify_public_page_extracts_exact_track(self):
        page = BeautifulSoup(
            '<div data-testid="track-row" aria-label="Under The Sun" '
            'aria-labelledby="listrow-title-track-spotify:track:6gspj8nSetk1wTCQ2XuBDW-2">'
            '<img src="https://i.scdn.co/cover.jpg"></div>',
            "html.parser",
        )
        result = _spotify_track_from_page(page, "C-Systems & MIDI Kittyy", "Under The Sun")
        self.assertIsNotNone(result)
        self.assertEqual("https://open.spotify.com/track/6gspj8nSetk1wTCQ2XuBDW", result["url"])

    def test_spotify_reuses_legacy_search_cascade(self):
        empty = {"tracks": {"items": []}}
        found = {"tracks": {"items": [{
            "id": "6gspj8nSetk1wTCQ2XuBDW",
            "name": "Under The Sun",
            "artists": [{"name": "C-Systems"}, {"name": "MIDI Kittyy"}],
            "external_urls": {"spotify": "https://open.spotify.com/track/6gspj8nSetk1wTCQ2XuBDW"},
            "album": {"images": [{"url": "https://i.scdn.co/cover.jpg"}]},
        }]}}
        with patch("catalog._spotify_access_token", return_value="token"), patch(
            "catalog._get_json", side_effect=[empty, found]
        ) as request:
            result = search_spotify("C-Systems & MIDI Kittyy", "Under The Sun [Black Hole]")
        self.assertIsNotNone(result)
        self.assertEqual("https://open.spotify.com/track/6gspj8nSetk1wTCQ2XuBDW", result["url"])
        self.assertEqual(2, request.call_count)
        self.assertIn("C-Systems+%26+MIDI+Kittyy+Under+The+Sun", request.call_args.args[0])

    def test_spotify_retries_with_primary_artist(self):
        empty = {"tracks": {"items": []}}
        found = {"tracks": {"items": [{
            "id": "1234567890123456789012",
            "name": "A New Dawn",
            "artists": [{"name": "Solarstone"}, {"name": "Future Disciple"}],
            "external_urls": {"spotify": "https://open.spotify.com/track/1234567890123456789012"},
            "album": {"images": []},
        }]}}
        with patch("catalog._spotify_access_token", return_value="token"), patch(
            "catalog._get_json", side_effect=[empty, empty, found]
        ) as request:
            result = search_spotify("Solarstone vs. Future Disciple", "A New Dawn")
        self.assertIsNotNone(result)
        self.assertEqual(3, request.call_count)
        self.assertIn("artist%3ASolarstone+track%3AA+New+Dawn", request.call_args.args[0])

    def test_empty_cookie_jar_is_not_passed_to_ytdlp(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as cookie:
            cookie.write("# Netscape HTTP Cookie File\n")
            path = cookie.name
        try:
            with patch.dict(os.environ, {"YTDLP_COOKIE_FILE": path}):
                self.assertIsNone(_cookie_file())
        finally:
            os.unlink(path)

    def test_parses_timestamped_and_numbered_tracks(self):
        tracks = parse_tracklist(
            "00:00 Artist A — Opening\n"
            "04:12 Artist B - Second\n"
            "3. Artist C — Final"
        )
        self.assertEqual(3, len(tracks))
        self.assertEqual(252, tracks[1]["providedTime"])
        self.assertEqual("Artist C", tracks[2]["artist"])

    def test_parses_unnumbered_tracks_after_published_tracklist_header(self):
        tracks = parse_tracklist(
            """
            <p>Two hours of the best Trance music.</p>
            <p>TTE 432 Tracklist:</p>
            <p>John Grand - Walk Away [Enhanced Progressive]</p>
            <p>Marten Lou & Rivo - Technicolor [Warner Music]</p>
            <p>Everything TTE</p>
            """,
            structured_only=True,
        )
        self.assertEqual(2, len(tracks))
        self.assertEqual("John Grand", tracks[0]["artist"])
        self.assertEqual("Walk Away [Enhanced Progressive]", tracks[0]["title"])

    def test_aligns_close_timestamp_to_audio_transition(self):
        candidates = parse_tracklist("00:00 A — One\n00:22 B — Two\n00:39 C — Three")
        result = align_tracklist(
            candidates,
            [{"time": 20.02}, {"time": 40.03}],
            60,
        )
        self.assertEqual([0.0, 20.02, 40.03], [track["time"] for track in result])
        self.assertGreaterEqual(result[1]["confidence"], 90)

    def test_extracts_chapters_from_media_metadata(self):
        tracks = candidates_from_info({
            "chapters": [
                {"start_time": 0, "title": "A — One"},
                {"start_time": 75, "title": "B — Two"},
            ]
        })
        self.assertEqual(2, len(tracks))
        self.assertEqual(75, tracks[1]["providedTime"])

    def test_rejects_prose_with_an_em_dash_as_media_tracklist(self):
        tracks = candidates_from_info({
            "description": "The video was directed by Simon West — who later made several films."
        })
        self.assertEqual([], tracks)

    def test_keeps_colon_in_numbered_artist_name(self):
        tracks = parse_tracklist("13. 19:26, Bittermind - Dancing", structured_only=True)
        self.assertEqual(1, len(tracks))
        self.assertIsNone(tracks[0]["providedTime"])
        self.assertEqual("19:26, Bittermind", tracks[0]["artist"])

    def test_distributes_untimed_tracklist_across_known_duration(self):
        candidates = parse_tracklist("1. A - One\n2. B - Two\n3. C - Three", structured_only=True)
        tracks = align_tracklist(candidates, [], 360)
        self.assertEqual([0.0, 120.0, 240.0], [track["time"] for track in tracks])

    def test_1001_parser_and_url_allowlist(self):
        html = """
        <html><head><title>Fixture</title></head><body>
          <div class="tlpItem"><span class="cueValueField">04:12</span>
            <span class="trackValue">Artist — Track</span></div>
          <div class="tlpItem"><input id="cue_2_cue_seconds" value="315">
            <span class="trackValue">Second Artist — Second Track</span></div>
        </body></html>
        """
        result = parse_tracklist_html(
            html,
            "https://www.1001tracklists.com/tracklist/abc123/example.html",
        )
        self.assertEqual(2, result["candidateCount"])
        self.assertEqual(252, result["candidates"][0]["providedTime"])
        self.assertEqual(315, result["candidates"][1]["providedTime"])
        with self.assertRaises(ValueError):
            validate_tracklist_url("https://127.0.0.1/tracklist/abc123/example.html")
        with self.assertRaises(ValueError):
            validate_media_url("file:///etc/passwd")
        with self.assertRaises(ValueError):
            _safe_url("https://127.0.0.1/private-feed.xml")

    def test_subsequence_dtw_finds_embedded_chroma(self):
        rng = np.random.default_rng(42)
        query = rng.random((12, 160), dtype=np.float32)
        reference = rng.random((12, 700), dtype=np.float32)
        reference[:, 320:480] = query
        match = subsequence_dtw(query, reference)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertLessEqual(abs(match[0] - 320), 8)
        self.assertLess(match[2], 0.01)

    def test_acoustic_validation_combines_catalog_and_audio_scores(self):
        accepted = score_acoustic_validation(92, 0.18)
        rejected_audio = score_acoustic_validation(96, 0.71)
        rejected_metadata = score_acoustic_validation(55, 0.12)
        self.assertTrue(accepted["accepted"])
        self.assertFalse(rejected_audio["accepted"])
        self.assertFalse(rejected_metadata["accepted"])
        self.assertGreater(accepted["confidence"], 80)

    def test_transition_prefers_nearby_downbeat(self):
        snapped, evidence = snap_to_grid(
            31.4,
            {
                "engine": "test",
                "beats": [30.0, 30.5, 31.0, 31.5, 32.0],
                "downbeats": [30.0, 32.0],
            },
        )
        self.assertEqual(32.0, snapped)
        self.assertIn("temps fort", evidence or "")

    def test_parses_mixesdb_minutes_and_untimed_rows(self):
        html = """
        <h2>Notes</h2><p>Example</p>
        <h2>Tracklist</h2>
        <ol>
          <li>[000] Artist A - Opening</li>
          <li>[005] Artist B - Second</li>
          <li>Artist C - Final</li>
        </ol>
        """
        result = parse_mixesdb_html(html, "Fixture")
        self.assertEqual(3, result["candidateCount"])
        self.assertEqual(300, result["candidates"][1]["providedTime"])
        self.assertEqual("Final", result["candidates"][2]["title"])

    def test_acoustid_is_optional_without_server_key(self):
        with patch.dict("os.environ", {}, clear=True):
            result = identify_segment(Path("/file/not/read/without/key.wav"), 0)
        self.assertFalse(result["available"])
        self.assertIn("ACOUSTID_API_KEY", result["message"])


if __name__ == "__main__":
    unittest.main()
