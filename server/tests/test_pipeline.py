from __future__ import annotations

import sys
import unittest
import os
import tempfile
from unittest.mock import Mock, patch
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from discovery import _page_metadata, candidates_from_info, tracklist_text_from_info, validate_media_url
from discovery import _cookie_file
from bs4 import BeautifulSoup

from catalog import _spotify_track_from_page, search_deezer, search_spotify
from feeds import _safe_url, import_feed
from mixesdb import parse_mixesdb_html
from openai_timestamping import extract_tracklist_with_openai
from tl1001 import _tracklist_urls_from_ajax, parse_tracklist_html, validate_tracklist_url
from tracklist import align_tracklist, apply_external_timestamps, first_track_match_score, parse_tracklist


class TracklistPipelineTests(unittest.TestCase):
    def test_youtube_page_description_is_a_metadata_fallback(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.geturl.return_value = "https://www.youtube.com/watch?v=fixture"
        response.headers = {"Content-Type": "text/html; charset=utf-8"}
        response.read.return_value = (
            b'<meta property="og:title" content="TTE Fixture">'
            b'<script>{"attributedDescription":{"content":"00:00 Artist A - One\\n04:00 Artist B - Two","commandRuns":[]}}</script>'
        )
        opener = Mock()
        opener.open.return_value = response
        with patch("discovery.build_opener", return_value=opener):
            info = _page_metadata("https://youtu.be/fixture")
        self.assertEqual("TTE Fixture", info["title"])
        self.assertIn("04:00 Artist B - Two", info["description"])

    def test_youtube_page_comments_are_used_when_description_is_empty(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.geturl.return_value = "https://www.youtube.com/watch?v=fixture"
        response.headers = {"Content-Type": "text/html; charset=utf-8"}
        response.read.return_value = (
            b'<meta property="og:title" content="Find Your Harmony 507">'
            b'<script>var ytInitialData = {"contents":{"commentRenderer":'
            b'{"contentText":{"runs":[{"text":"00:00 Artist A - One\\n"},'
            b'{"text":"04:00 Artist B - Two"}]}}}};</script>'
        )
        opener = Mock()
        opener.open.return_value = response
        with patch("discovery.build_opener", return_value=opener):
            info = _page_metadata("https://youtu.be/fixture")
        self.assertEqual([0, 240], [item["providedTime"] for item in candidates_from_info(info)])

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

    def test_deezer_accepts_a_short_punctuated_artist_name(self):
        payload = {"data": [{
            "id": 44,
            "title": "Ciento",
            "link": "https://www.deezer.com/track/44",
            "artist": {"name": "th;en"},
            "album": {"cover_big": "https://example.test/ciento.jpg"},
        }]}
        with patch("catalog._get_json", return_value=payload):
            result = search_deezer("TH;EN", "Ciento [Now]")
        self.assertIsNotNone(result)
        self.assertEqual("https://www.deezer.com/track/44", result["url"])

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

    def test_align_marks_rss_timestamps_as_authoritative(self):
        tracks = align_tracklist(
            [{"artist": "Artist A", "title": "Opening", "providedTime": 42.0}],
            [],
            600,
            timestamp_source="rss",
        )
        self.assertEqual("rss", tracks[0]["timestampSource"])
        self.assertEqual(1.0, tracks[0]["timestampScore"])
        self.assertEqual("provided", tracks[0]["timestampStatus"])
        self.assertEqual(42.0, tracks[0]["time"])

    def test_align_marks_missing_rss_timestamps_as_provisional(self):
        tracks = align_tracklist(
            [{"artist": "Artist A", "title": "Opening", "providedTime": None}],
            [],
            600,
        )
        self.assertEqual("provisional", tracks[0]["timestampSource"])
        self.assertEqual("pending", tracks[0]["timestampStatus"])
        self.assertIsNone(tracks[0]["time"])
        self.assertLess(tracks[0]["timestampScore"], 1.0)

    def test_rss_import_keeps_untimestamped_tracklist_unaligned(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit):
                return b"""<?xml version="1.0"?>
                <rss><channel><title>Captive Soul</title>
                  <item>
                    <guid>episode-098</guid>
                    <title>Captive Soul 098</title>
                    <itunes:duration xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">00:59:32</itunes:duration>
                    <description>01. Cherry (UA) - Buka [Captive Soul]&lt;br /&gt;02. Corren Cavini x EAST-97 - Darkness Into Day [Purified]</description>
                    <enclosure url="https://audio.example/098.mp3" />
                  </item>
                </channel></rss>"""

        with (
            patch("feeds.socket.getaddrinfo", return_value=[(None, None, None, None, ("93.184.216.34", 443))]),
            patch("feeds.build_opener") as opener,
        ):
            opener.return_value.open.return_value = FakeResponse()
            feed = import_feed("https://podcast.example/feed.xml")
        self.assertEqual("Captive Soul 098", feed["episodes"][0]["title"])
        self.assertEqual([], feed["episodes"][0]["tracks"])

    def test_parses_timestamped_and_numbered_tracks(self):
        tracks = parse_tracklist(
            "00:00 Artist A — Opening\n"
            "04:12 Artist B - Second\n"
            "3. Artist C — Final"
        )
        self.assertEqual(3, len(tracks))
        self.assertEqual(252, tracks[1]["providedTime"])
        self.assertEqual("Artist C", tracks[2]["artist"])

    def test_parses_youtube_timestamps_at_end_of_tracklist_lines(self):
        tracks = parse_tracklist(
            "01. Sunlounger & okafuwa - Children Of The Cosmos [Black Hole] (00:02:16)\n"
            "02. Ruslan Radriges - You Set Me Free [Interplay] (00:06:04)",
            structured_only=True,
        )
        self.assertEqual([136, 364], [track["providedTime"] for track in tracks])


    def test_parses_youtube_number_then_timestamp_comments(self):
        tracks = parse_tracklist(
            "01 01:43 Cherry - Buka\n"
            "02 05:38 Corren Cavini & EAST-97 - Darkness Into Day",
            structured_only=True,
        )
        self.assertEqual(2, len(tracks))
        self.assertEqual(103, tracks[0]["providedTime"])
        self.assertEqual("Cherry", tracks[0]["artist"])
        self.assertEqual(338, tracks[1]["providedTime"])

    def test_first_track_match_ignores_mix_metadata(self):
        score = first_track_match_score(
            [{"artist": "Cherry", "title": "Buka"}],
            {"artist": "Cherry (UA)", "title": "Buka [Captive Soul]"},
        )
        self.assertGreaterEqual(score, 0.5)

    def test_external_timestamps_preserve_established_rss_titles(self):
        merged = apply_external_timestamps(
            [
                {"artist": "Cherry (UA)", "title": "Buka [Captive Soul]", "providedTime": None},
                {"artist": "Anyma & Volkoder", "title": "Other Dimension [ÆDEN]", "providedTime": None},
            ],
            [
                {"artist": "Cherry", "title": "Buka", "providedTime": 103},
                {"artist": "Anyma & VolkerDer", "title": "Other Dimension", "providedTime": 859},
            ],
        )
        self.assertEqual("Cherry (UA)", merged[0]["artist"])
        self.assertEqual("Other Dimension [ÆDEN]", merged[1]["title"])
        self.assertEqual([103, 859], [track["providedTime"] for track in merged])

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

    def test_explicit_timestamp_is_never_recalculated(self):
        candidates = parse_tracklist("00:00 A — One\n00:22 B — Two\n00:39 C — Three")
        result = align_tracklist(
            candidates,
            [{"time": 20.02}, {"time": 40.03}],
            60,
        )
        self.assertEqual([0.0, 22.0, 39.0], [track["time"] for track in result])

    def test_extracts_chapters_from_media_metadata(self):
        tracks = candidates_from_info({
            "chapters": [
                {"start_time": 0, "title": "A — One"},
                {"start_time": 75, "title": "B — Two"},
            ]
        })
        self.assertEqual(2, len(tracks))
        self.assertEqual(75, tracks[1]["providedTime"])

    def test_prioritizes_timestamped_comments_from_media_metadata(self):
        info = {
            "description": "Nice episode",
            "comments": [
                {"text": "great mix"},
                {"text": "01 01:43 Cherry - Buka\n02 05:38 Corren Cavini - Darkness Into Day"},
                {"text": "8:56 what is this?"},
                {"text": "25:55 Wicked track!\n40:10 This track right here is fire!!!!!!"},
            ],
        }
        tracks = candidates_from_info(info)
        self.assertEqual(2, len(tracks))
        self.assertEqual([103, 338], [track["providedTime"] for track in tracks])
        source_text = tracklist_text_from_info(info)
        self.assertIn("Cherry - Buka", source_text)
        self.assertNotIn("great mix", source_text)
        self.assertIn("Wicked track!", source_text)

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

    def test_does_not_invent_timestamps_for_untimed_tracklist(self):
        candidates = parse_tracklist("1. A - One\n2. B - Two\n3. C - Three", structured_only=True)
        tracks = align_tracklist(candidates, [], 360)
        self.assertEqual([None, None, None], [track["time"] for track in tracks])

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

    def test_1001_parser_accepts_rendered_div_fields(self):
        html = """
        <html><head><title>Rendered fixture</title></head><body>
          <article class="tlpItem"><div class="cueValueField">01:43</div>
            <div class="trackValue">Cherry — Buka</div></article>
          <article class="tlpItem"><div class="timing">06:00</div>
            <div class="trackValue">Corren Cavini — Darkness Into Day</div></article>
        </body></html>
        """
        result = parse_tracklist_html(
            html,
            "https://www.1001tracklists.com/tracklist/abc123/rendered.html",
        )
        self.assertEqual(2, result["candidateCount"])
        self.assertEqual([103, 360], [track["providedTime"] for track in result["candidates"]])

        fallback = parse_tracklist_html(
            '<li class="tlpTog"><div class="timing">10:12</div>'
            '<div class="trackValue">Timeless — Free Your Mind</div></li>',
            "https://www.1001tracklists.com/tracklist/abc123/fallback.html",
        )
        self.assertEqual(612, fallback["candidates"][0]["providedTime"])

    def test_1001_parser_ignores_mashup_ingredients_with_false_zero_cues(self):
        html = """
        <div class="tlpItem tlpTog"><input id="tlp_parent_cue_seconds" value="120">
          <span class="trackValue">DJ One — Main Mashup</span></div>
        <div class="tlpItem tlpSubTog"><input id="tlp_child_cue_seconds" value="0">
          <span class="trackValue">DJ Two — Mashup Ingredient</span></div>
        <div class="tlpItem tlpTog"><input id="tlp_next_cue_seconds" value="360">
          <span class="trackValue">DJ Three — Next Track</span></div>
        """
        result = parse_tracklist_html(
            html,
            "https://www.1001tracklists.com/tracklist/abc123/mashup.html",
        )
        self.assertEqual([120, 360], [track["providedTime"] for track in result["candidates"]])

    def test_1001_ajax_results_build_tracklist_urls(self):
        urls = _tracklist_urls_from_ajax({
            "data": [
                {
                    "properties": {
                        "id_unique": "1cgr4nqk",
                        "url_name": "korolova captive soul 098 2026 08 07",
                    },
                },
                {"informal": "ignored"},
            ],
        })
        self.assertEqual(
            ["https://www.1001tracklists.com/tracklist/1cgr4nqk/korolova-captive-soul-098-2026-08-07.html"],
            urls,
        )

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

    def test_openai_timestamping_is_disabled_without_api_key(self):
        with (
            patch.dict(os.environ, {}, clear=True),
            patch("openai_timestamping.LOCAL_PROPERTIES_PATHS", ()),
        ):
            result = extract_tracklist_with_openai(
                "00:00 Artist A - One\n04:10 Artist B - Two",
                title="Fixture",
            )
        self.assertFalse(result["available"])
        self.assertEqual([], result["candidates"])

    def test_openai_timestamping_can_be_explicitly_disabled(self):
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "test-key",
            "PODMIX_OPENAI_TIMESTAMPING": "0",
        }, clear=True):
            result = extract_tracklist_with_openai(
                "00:00 Artist A - One\n04:10 Artist B - Two",
                title="Fixture",
            )
        self.assertFalse(result["available"])
        self.assertEqual([], result["candidates"])

    def test_openai_timestamping_can_read_local_properties_key(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit):
                return (
                    b'{"choices":[{"message":{"content":"{\\"selectedSourceIndex\\":0,'
                    b'\\"confidence\\":0.91,\\"rationale\\":\\"explicit timestamps\\",'
                    b'\\"tracks\\":['
                    b'{\\"timestamp\\":\\"01:30\\",\\"artist\\":\\"Artist A\\",\\"title\\":\\"One\\",\\"sourceIndex\\":0},'
                    b'{\\"timestamp\\":300,\\"artist\\":\\"Artist B\\",\\"title\\":\\"Two\\",\\"sourceIndex\\":0}'
                    b']}"}}]}'
                )

        with tempfile.TemporaryDirectory() as tmpdir:
            properties_path = Path(tmpdir) / "local.properties"
            properties_path.write_text("NOUS_PORTAL_API=test-key\n", encoding="utf-8")
            with (
                patch.dict(os.environ, {}, clear=True),
                patch("openai_timestamping.LOCAL_PROPERTIES_PATHS", (properties_path,)),
                patch("openai_timestamping.urlopen", return_value=FakeResponse()),
            ):
                result = extract_tracklist_with_openai(
                    "messy page text with enough context to trigger extraction",
                    title="Fixture",
                )
        self.assertEqual(2, result["candidateCount"])

    def test_openai_timestamping_normalizes_structured_response(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit):
                return (
                    b'{"output_text":"{\\"selectedSourceIndex\\":0,'
                    b'\\"confidence\\":0.91,\\"rationale\\":\\"explicit timestamps\\",'
                    b'\\"tracks\\":['
                    b'{\\"timestamp\\":\\"01:30\\",\\"artist\\":\\"Artist A\\",\\"title\\":\\"One\\",\\"sourceIndex\\":0},'
                    b'{\\"timestamp\\":300,\\"artist\\":\\"Artist B\\",\\"title\\":\\"Two\\",\\"sourceIndex\\":0}'
                    b']}"}'
                )

        with (
            patch.dict(os.environ, {
                "OPENAI_API_KEY": "test-key",
            }, clear=True),
            patch("openai_timestamping.urlopen", return_value=FakeResponse()),
        ):
            result = extract_tracklist_with_openai(
                "messy page text with enough context to trigger extraction",
                title="Fixture",
            )
        self.assertEqual(2, result["candidateCount"])
        self.assertEqual(90, result["candidates"][0]["providedTime"])
        self.assertEqual(300, result["candidates"][1]["providedTime"])

if __name__ == "__main__":
    unittest.main()
