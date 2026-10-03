from __future__ import annotations

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


class ResearchJobTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = Path(self.directory.name, "podmix.sqlite3")
        self.database_patch = patch("dev_server.DATABASE", self.database)
        self.database_patch.start()
        dev_server.initialize()

    def tearDown(self):
        self.database_patch.stop()
        self.directory.cleanup()

    def insert_job(self, status="queued", context=None, request_key=None, duration=600):
        created = dev_server.now()
        with dev_server.connect() as database:
            database.execute(
                """
                INSERT INTO jobs (
                  id, episode_id, status, stage, progress, tracks_json, error,
                  operation, context_json, request_key, created_at, updated_at, duration
                ) VALUES (?, ?, ?, 'Test', 25, '[]', NULL, 'research', ?, ?, ?, ?, ?)
                """,
                ("job-1", "episode-1", status, json.dumps(context or {}),
                 request_key, created, created, duration),
            )

    def test_running_research_is_requeued_after_restart(self):
        self.insert_job("running")
        dev_server.initialize()
        job = dev_server.get_job("job-1")
        self.assertEqual("queued", job["status"])
        self.assertEqual("job-1", dev_server.claim_next_job()["id"])

    def test_queued_research_claims_newest_episode_first(self):
        created = dev_server.now()
        with dev_server.connect() as database:
            for job_id, episode_id, published_timestamp in [
                ("old-job", "old-episode", 1700000000),
                ("new-job", "new-episode", 1800000000),
            ]:
                database.execute(
                    """
                    INSERT INTO jobs (
                      id, episode_id, status, stage, progress, tracks_json, error,
                      operation, context_json, request_key, created_at, updated_at, duration
                    ) VALUES (?, ?, 'queued', 'Test', 0, '[]', NULL, 'research', ?, NULL, ?, ?, 600)
                    """,
                    (
                        job_id,
                        episode_id,
                        json.dumps({"publishedTimestamp": published_timestamp}),
                        created,
                        created,
                    ),
                )
        self.assertEqual("new-job", dev_server.claim_next_job()["id"])

    def test_rss_published_date_is_parsed_for_queue_priority(self):
        timestamp = dev_server.parse_published_timestamp("Wed, 05 Aug 2026 23:00:00 +0300")
        self.assertGreater(timestamp, dev_server.parse_published_timestamp("Thu, 02 Jul 2026 23:00:00 +0300"))
        self.assertGreater(timestamp, 0)

    def test_worker_researches_metadata_without_media_path(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "description": "00:00 Artist A — Opening\n04:12 Artist B — Second",
        })
        dev_server.run_research_job("job-1")
        job = dev_server.get_job("job-1")
        self.assertEqual("completed", job["status"])
        self.assertEqual([0, 252], [track["time"] for track in job["tracks"]])
        self.assertEqual("research", job["operation"])

    def test_untimed_rss_titles_remain_pending(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "description": "1. Artist A — Opening\n2. Artist B — Second",
        })
        with (
            patch("dev_server._discover_web_candidates", return_value=(None, [], [])),
            patch("dev_server.search_external_tracklist", return_value={"candidates": []}),
            patch("dev_server.openai_timestamping_enabled", return_value=False),
        ):
            tracks = dev_server._automatic_tracklist(
                "job-1", 600, dev_server.get_job_context("job-1"),
            )
        self.assertEqual([None, None], [track["time"] for track in tracks])
        self.assertTrue(all(track["timestampStatus"] == "pending" for track in tracks))

    def test_skips_ai_consolidation_without_explicit_web_timestamps(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "enableExternalTracklists": True,
            "title": "Captive Soul 098",
            "description": "1. Cherry — Buka\n2. Timeless — Free Your Mind",
        }, duration=3572)
        with (
            patch("dev_server.search_external_tracklist", return_value={"candidates": []}),
            patch("dev_server._discover_web_candidates", return_value=(None, [
                {
                    "kind": "web-search:1001tracklists.com",
                    "url": "https://www.1001tracklists.com/tracklist/example.html",
                    "title": "Korolova - Captive Soul 098",
                    "text": "Korolova Captive Soul 098 Cherry Buka Timeless Free Your Mind",
                }
            ], [])),
            patch("dev_server.openai_timestamping_enabled", return_value=True),
            patch("dev_server.consolidate_timestamping_sources") as consolidate,
        ):
            tracks = dev_server._automatic_tracklist(
                "job-1", 3572, dev_server.get_job_context("job-1"),
            )
        consolidate.assert_not_called()
        self.assertEqual([None, None], [track["time"] for track in tracks])

    def test_web_queue_skips_slow_server_searches_when_rss_titles_exist(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "enableExternalTracklists": True,
            "webQueue": True,
            "title": "Captive Soul 098",
            "description": "1. Cherry — Buka\n2. Timeless — Free Your Mind",
        }, duration=3572)
        with (
            patch("dev_server.search_external_tracklist") as search,
            patch("dev_server._discover_web_candidates") as discover,
            patch("dev_server.consolidate_timestamping_sources") as consolidate,
        ):
            tracks = dev_server._automatic_tracklist(
                "job-1", 3572, dev_server.get_job_context("job-1"),
            )
        search.assert_not_called()
        discover.assert_not_called()
        consolidate.assert_not_called()
        self.assertEqual([None, None], [track["time"] for track in tracks])

    def test_web_claim_enriches_empty_candidates_from_episode_title(self):
        self.insert_job(
            status="web_pending",
            context={
                "webQueue": True,
                "title": "Captive Soul 097",
                "referenceFirstTrack": {"artist": "Enai", "title": "That’s My Sh*t"},
            },
        )
        with patch("dev_server.search_episode_tracklist_candidates", return_value=[{
            "url": "https://www.1001tracklists.com/tracklist/1rl2ft59/korolova-captive-soul-097-2026-07-24.html",
            "title": "Korolova - Captive Soul 097 2026-07-24 - 1001Tracklists",
            "domain": "1001tracklists.com",
        }]) as search:
            job = dev_server.claim_web_timestamp_job()
        search.assert_called_once_with("Captive Soul 097", [], 8)
        self.assertEqual(1, len(job["webCandidates"]))
        self.assertIn("1rl2ft59", job["webCandidates"][0]["url"])

    def test_web_claim_prioritizes_the_episode_cached_1001_url(self):
        cached_url = "https://www.1001tracklists.com/tracklist/16tmxt29/find-your-harmony-503.html"
        self.insert_job(
            status="web_pending",
            context={
                "webQueue": True,
                "title": "Find Your Harmony Episode #503",
                "preferred1001Url": cached_url,
            },
        )
        with patch("dev_server.search_episode_tracklist_candidates") as search:
            job = dev_server.claim_web_timestamp_job()
        search.assert_not_called()
        self.assertEqual(cached_url, job["webCandidates"][0]["url"])
        self.assertEqual("URL 1001Tracklists mémorisée", job["webCandidates"][0]["title"])

    def test_web_claim_uses_newest_episode_first(self):
        created = dev_server.now()
        with dev_server.connect() as database:
            for job_id, episode_id, published_timestamp in [
                ("old-web", "old-episode", 1700000000),
                ("new-web", "new-episode", 1800000000),
            ]:
                database.execute(
                    """
                    INSERT INTO jobs (
                      id, episode_id, status, stage, progress, tracks_json, error,
                      operation, context_json, request_key, created_at, updated_at, duration
                    ) VALUES (?, ?, 'web_pending', 'Test', 82, '[]', NULL, 'research', ?, NULL, ?, ?, 600)
                    """,
                    (
                        job_id,
                        episode_id,
                        json.dumps({
                            "webQueue": True,
                            "title": episode_id,
                            "publishedTimestamp": published_timestamp,
                        }),
                        created,
                        created,
                    ),
                )
        with patch("dev_server.search_episode_tracklist_candidates", return_value=[]):
            self.assertEqual("new-web", dev_server.claim_web_timestamp_job()["id"])

    def test_web_fallback_uses_explicit_timestamps(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "enableExternalTracklists": True,
            "title": "Captive Soul 098",
            "description": "1. Cherry — Buka\n2. Timeless — Free Your Mind",
        }, duration=3572)
        discovery = {
            "source": "1001tracklists",
            "sourceUrl": "https://www.1001tracklists.com/tracklist/example",
            "candidates": [
                {"artist": "Cherry", "title": "Buka", "providedTime": 103},
                {"artist": "Timeless", "title": "Free Your Mind", "providedTime": 612},
            ],
        }
        with patch("dev_server.search_external_tracklist", return_value=discovery):
            tracks = dev_server._automatic_tracklist(
                "job-1", 3572, dev_server.get_job_context("job-1"),
            )
        self.assertEqual([103, 612], [track["time"] for track in tracks])
        self.assertTrue(all(track["timestampSource"] == "external" for track in tracks))

    def test_android_web_completion_preserves_rss_titles(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "referenceFirstTrack": {"artist": "Cherry (UA)", "title": "Buka [Captive Soul]"},
        }, duration=3572)
        dev_server.update_job("job-1", tracks=[
            {
                "artist": "Cherry (UA)",
                "title": "Buka [Captive Soul]",
                "time": None,
                "timestampStatus": "pending",
            },
            {
                "artist": "Anyma & Volkoder",
                "title": "Other Dimension [ÆDEN]",
                "time": None,
                "timestampStatus": "pending",
            },
        ])
        job = dev_server.complete_web_timestamp_job("job-1", {
            "sourceUrl": "https://www.1001tracklists.com/tracklist/example",
            "candidates": [
                {"artist": "Cherry", "title": "Buka", "providedTime": 103},
                {"artist": "Anyma & VolkerDer", "title": "Other Dimension", "providedTime": 859},
            ],
        })
        self.assertEqual("completed", job["status"])
        self.assertEqual([103, 859], [track["time"] for track in job["tracks"]])
        self.assertEqual("Cherry (UA)", job["tracks"][0]["artist"])
        self.assertEqual("Other Dimension [ÆDEN]", job["tracks"][1]["title"])

    def test_youtube_web_completion_marks_youtube_source(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "referenceFirstTrack": {"artist": "Artist A", "title": "Opening"},
        }, duration=600)
        dev_server.update_job("job-1", tracks=[
            {"artist": "Artist A", "title": "Opening", "time": None, "timestampStatus": "pending"},
            {"artist": "Artist B", "title": "Second", "time": None, "timestampStatus": "pending"},
        ])
        job = dev_server.complete_web_timestamp_job("job-1", {
            "sourceUrl": "https://www.youtube.com/watch?v=fixture",
            "candidates": [
                {"artist": "Artist A", "title": "Opening", "providedTime": 0},
                {"artist": "Artist B", "title": "Second", "providedTime": 240},
            ],
        })
        self.assertTrue(all(track["timestampSource"] == "youtube" for track in job["tracks"]))
        self.assertIn("YouTube", job["tracks"][0]["evidence"][0])

    def test_web_failure_queues_audio_fallback_for_known_musical_tracklist(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "webQueue": True,
            "musical": True,
            "sourceKind": "podcast",
            "audioUrl": "https://cdn.example/mix.mp3",
            "rssTracks": [
                {"artist": "Artist A", "title": "Opening"},
                {"artist": "Artist B", "title": "Second"},
            ],
        })
        dev_server.update_job("job-1", tracks=[
            {"artist": "Artist A", "title": "Opening", "time": None, "timestampStatus": "pending"},
            {"artist": "Artist B", "title": "Second", "time": None, "timestampStatus": "pending"},
        ])

        # Le fallback ne s'enclenche qu'après l'échec des méthodes Web, même
        # lorsqu'il est activé dans le conteneur de production.
        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker") as notify:
            job = dev_server.fail_web_timestamp_job("job-1", "aucun timestamp Web")

        self.assertEqual("queued", job["status"])
        self.assertEqual(83, job["progress"])
        self.assertTrue(dev_server.get_job_context("job-1")["audioFallbackPending"])
        notify.assert_called_once()

    def test_audio_fallback_uses_published_media_when_rss_audio_is_missing(self):
        self.assertEqual(
            "https://soundcloud.com/trance-empire/set-436",
            dev_server.audio_fallback_source_url({
                "sourceUrl": "https://thetranceempire.example/436",
                "publishedLinks": [
                    "https://www.1001tracklists.com/tracklist/example",
                    "https://soundcloud.com/trance-empire/set-436",
                ],
            }),
        )

    def test_rss_enclosure_is_recovered_for_the_requested_episode(self):
        context = {
            "feedUrl": "https://feed.example/show.xml",
            "sourceKind": "podcast",
        }
        imported = {
            "episodes": [
                {"id": "another", "audioUrl": "https://cdn.example/another.mp3"},
                {"id": "episode-1", "audioUrl": "https://cdn.example/mix.mp3"},
            ],
        }
        with patch("dev_server.import_feed", return_value=imported) as import_rss:
            audio_url = dev_server.recover_episode_audio_url(context, "episode-1")

        self.assertEqual("https://cdn.example/mix.mp3", audio_url)
        import_rss.assert_called_once_with("https://feed.example/show.xml", kind="podcast", limit=500)

    def test_web_failure_queues_audio_fallback_when_only_rss_feed_is_available(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "feedUrl": "https://feed.example/show.xml",
            "rssTracks": [
                {"artist": "Artist A", "title": "Opening"},
                {"artist": "Artist B", "title": "Second"},
            ],
        })
        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker"):
            job = dev_server.fail_web_timestamp_job("job-1", "aucun timestamp Web")

        self.assertEqual("queued", job["status"])
        self.assertTrue(dev_server.get_job_context("job-1")["audioFallbackPending"])

    def test_web_failure_queues_fallback_when_a_known_tracklist_and_audio_exist(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "sourceKind": "podcast",
            "audioUrl": "https://cdn.example/talk.mp3",
            "rssTracks": [
                {"artist": "Speaker", "title": "Chapter one"},
                {"artist": "Speaker", "title": "Chapter two"},
            ],
        })
        dev_server.update_job("job-1", tracks=[
            {"artist": "Speaker", "title": "Chapter one", "time": None},
            {"artist": "Speaker", "title": "Chapter two", "time": None},
        ])

        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker"):
            job = dev_server.fail_web_timestamp_job("job-1", "aucun timestamp Web")

        self.assertEqual("queued", job["status"])

    def test_web_failure_queues_trance_podcast_imported_without_musical_flag(self):
        # Les podcasts importés avant l'ajout du champ ``musical`` doivent
        # tout de même pouvoir atteindre le fallback s'ils portent clairement
        # une tracklist de mix électronique.
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "sourceKind": "podcast",
            "title": "The Trance Empire 436",
            "description": "Two hours of the best Trance music. Tracklist.",
            "audioUrl": "https://cdn.example/trance.mp3",
            "rssTracks": [
                {"artist": "Artist A", "title": "Opening"},
                {"artist": "Artist B", "title": "Second"},
            ],
        })
        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker"):
            job = dev_server.fail_web_timestamp_job("job-1", "aucun timestamp Web")

        self.assertEqual("queued", job["status"])

    def test_web_failure_queues_legacy_trance_job_without_source_kind(self):
        self.insert_job(status="web_pending", context={
            "automaticTracklist": True,
            "title": "The Trance Empire 436",
            "description": "Trance mix tracklist",
            "audioUrl": "https://cdn.example/trance.mp3",
            "rssTracks": [{"artist": "A", "title": "One"}, {"artist": "B", "title": "Two"}],
        })
        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker"):
            job = dev_server.fail_web_timestamp_job("job-1", "aucun timestamp Web")

        self.assertEqual("queued", job["status"])

    def test_failed_legacy_audio_fallback_can_be_requeued_after_a_fix(self):
        self.insert_job(status="failed", context={
            "automaticTracklist": True,
            "audioFallbackAttempted": True,
            "sourceKind": "legacy-rss",
            "title": "The Trance Empire 436",
            "description": "Trance mix tracklist",
            "audioUrl": "https://cdn.example/trance.mp3",
            "rssTracks": [{"artist": "A", "title": "One"}, {"artist": "B", "title": "Two"}],
        })
        with patch("dev_server.AUDIO_FALLBACK_ENABLED", True), patch("dev_server.notify_worker"):
            job = dev_server.fail_web_timestamp_job("job-1", "reprendre le fallback")

        self.assertEqual("queued", job["status"])

    def test_audio_fallback_worker_completes_with_audio_timestamps(self):
        self.insert_job(status="queued", context={
            "audioFallbackPending": True,
            "audioFallbackAttempted": True,
            "musical": True,
            "sourceKind": "dj",
            "sourceUrl": "https://www.youtube.com/watch?v=fixture",
        })
        dev_server.update_job("job-1", tracks=[
            {"artist": "Artist A", "title": "Opening", "time": None, "timestampStatus": "pending"},
            {"artist": "Artist B", "title": "Second", "time": None, "timestampStatus": "pending"},
        ])
        analyzed = [
            {
                "artist": "Artist A", "title": "Opening", "providedTime": 0,
                "confidence": 96, "evidence": ["alignement audio"],
            },
            {
                "artist": "Artist B", "title": "Second", "providedTime": 247.36,
                "confidence": 88, "evidence": ["alignement audio"],
            },
        ]

        with patch("dev_server.analyze_known_tracklist", return_value=analyzed) as analyze:
            dev_server.run_research_job("job-1")

        job = dev_server.get_job("job-1")
        self.assertEqual("completed", job["status"])
        self.assertEqual([0, 247.36], [track["time"] for track in job["tracks"]])
        self.assertTrue(all(track["timestampSource"] == "audio" for track in job["tracks"]))
        self.assertEqual([96, 88], [track["confidence"] for track in job["tracks"]])
        self.assertFalse(dev_server.get_job_context("job-1")["audioFallbackPending"])
        analyze.assert_called_once()

    def test_audio_fallback_worker_recovers_rss_audio_before_analysis(self):
        self.insert_job(status="queued", context={
            "audioFallbackPending": True,
            "feedUrl": "https://feed.example/show.xml",
            "sourceKind": "podcast",
        })
        dev_server.update_job("job-1", tracks=[
            {"artist": "Artist A", "title": "Opening", "time": None},
            {"artist": "Artist B", "title": "Second", "time": None},
        ])
        analyzed = [
            {"artist": "Artist A", "title": "Opening", "providedTime": 0},
            {"artist": "Artist B", "title": "Second", "providedTime": 250},
        ]
        with (
            patch("dev_server.recover_episode_audio_url", return_value="https://cdn.example/mix.mp3") as recover,
            patch("dev_server.analyze_known_tracklist", return_value=analyzed) as analyze,
        ):
            dev_server.run_research_job("job-1")

        recover.assert_called_once_with(dev_server.get_job_context("job-1"), "episode-1")
        self.assertEqual("https://cdn.example/mix.mp3", analyze.call_args.kwargs["audio_url"])
        self.assertEqual("https://cdn.example/mix.mp3", dev_server.get_job_context("job-1")["audioUrl"])


    def test_published_smart_link_is_explored_before_general_search(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "title": "THE TRANCE EMPIRE 434 with Rodman",
            "description": (
                "<a href='https://lnk.to/TTE434'>Choose your player</a>\n"
                "1. Evgeny Ivshin — Solaria\n2. Marsh — Too High"
            ),
        }, duration=7173)
        discovery = {
            "sourceUrl": "https://youtu.be/Be-upm4wCHs",
            "extractor": "Youtube",
            "sourceText": "00:00 Evgeny Ivshin - Solaria\n04:30 Marsh - Too High",
            "candidates": [
                {"artist": "Evgeny Ivshin", "title": "Solaria", "providedTime": 0},
                {"artist": "Marsh", "title": "Too High", "providedTime": 270},
            ],
        }
        with (
            patch("dev_server.extract_published_links", return_value=["https://youtu.be/Be-upm4wCHs"]),
            patch("dev_server._discover_published_source", return_value=discovery) as published,
            patch("dev_server.search_external_tracklist") as general_search,
        ):
            tracks = dev_server._automatic_tracklist(
                "job-1", 7173, dev_server.get_job_context("job-1"),
            )
        self.assertEqual([0, 270], [track["time"] for track in tracks])
        published.assert_called_once_with("https://youtu.be/Be-upm4wCHs")
        general_search.assert_not_called()
        self.assertEqual(
            ["https://youtu.be/Be-upm4wCHs"],
            dev_server.get_job_context("job-1")["publishedLinks"],
        )

    def test_external_timestamp_validation_rejects_all_zero_values(self):
        candidates = [
            {"artist": "Artist A", "title": "Opening", "providedTime": 0},
            {"artist": "Artist B", "title": "Second", "providedTime": 0},
            {"artist": "Artist C", "title": "Final", "providedTime": 0},
        ]
        self.assertFalse(dev_server._authoritative_external_candidates(candidates, None, 600))

    def test_external_timestamp_validation_rejects_mostly_zero_values(self):
        candidates = [
            {"artist": "Artist A", "title": "Opening", "providedTime": 0},
            {"artist": "Artist B", "title": "Second", "providedTime": 0},
            {"artist": "Artist C", "title": "Third", "providedTime": 0},
            {"artist": "Artist D", "title": "Fourth", "providedTime": 240},
            {"artist": "Artist E", "title": "Final", "providedTime": 480},
        ]
        self.assertFalse(dev_server._authoritative_external_candidates(candidates, None, 600))

    def test_external_timestamp_validation_allows_zero_start(self):
        candidates = [
            {"artist": "Artist A", "title": "Opening", "providedTime": 0},
            {"artist": "Artist B", "title": "Second", "providedTime": 240},
        ]
        self.assertTrue(dev_server._authoritative_external_candidates(candidates, None, 600))

    def test_partial_rss_timestamps_trigger_external_completion(self):
        self.insert_job(context={
            "automaticTracklist": True,
            "enableExternalTracklists": True,
            "title": "Partial timestamps",
            "description": "00:00 Artist A — Opening\n2. Artist B — Second",
        })
        discovery = {
            "source": "mixesdb",
            "sourceUrl": "https://www.mixesdb.com/example",
            "candidates": [
                {"artist": "Artist A", "title": "Opening", "providedTime": 0},
                {"artist": "Artist B", "title": "Second", "providedTime": 240},
            ],
        }
        with patch("dev_server.search_external_tracklist", return_value=discovery) as search:
            tracks = dev_server._automatic_tracklist(
                "job-1", 600, dev_server.get_job_context("job-1"),
            )
        search.assert_called_once()
        self.assertEqual([0, 240], [track["time"] for track in tracks])
        self.assertTrue(all(track["timestampSource"] == "external" for track in tracks))

    def test_schema_has_no_upload_reference(self):
        with dev_server.connect() as database:
            columns = {row[1] for row in database.execute("PRAGMA table_info(jobs)")}
            tables = {row[0] for row in database.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
        self.assertNotIn("upload_id", columns)
        self.assertNotIn("uploads", tables)

    def test_legacy_audio_jobs_are_detached_and_stopped(self):
        self.database.unlink()
        with sqlite3.connect(self.database) as database:
            database.executescript("""
            CREATE TABLE uploads (
              id TEXT PRIMARY KEY, filename TEXT NOT NULL, path TEXT NOT NULL,
              media_type TEXT NOT NULL, size INTEGER NOT NULL, created_at TEXT NOT NULL
            );
            CREATE TABLE jobs (
              id TEXT PRIMARY KEY, episode_id TEXT NOT NULL, upload_id TEXT NOT NULL,
              status TEXT NOT NULL, stage TEXT NOT NULL, progress INTEGER NOT NULL,
              tracks_json TEXT NOT NULL DEFAULT '[]', error TEXT,
              operation TEXT NOT NULL DEFAULT 'analyze',
              context_json TEXT NOT NULL DEFAULT '{}', request_key TEXT,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL, duration REAL
            );
            INSERT INTO jobs VALUES (
              'old-job', 'episode', 'upload', 'queued', 'Audio', 5, '[]', NULL,
              'download_analyze', '{}', NULL, '2026-01-01', '2026-01-01', 3600
            );
            """)
        dev_server.initialize()
        job = dev_server.get_job("old-job")
        self.assertEqual("cancelled", job["status"])
        self.assertEqual("research", job["operation"])
        with dev_server.connect() as database:
            columns = {row[1] for row in database.execute("PRAGMA table_info(jobs)")}
        self.assertNotIn("upload_id", columns)


if __name__ == "__main__":
    unittest.main()
