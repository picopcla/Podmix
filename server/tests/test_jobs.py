from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import dev_server


class DurableJobTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database = Path(self.directory.name, "podmix.sqlite3")
        self.database_patch = patch("dev_server.DATABASE", self.database)
        self.database_patch.start()
        dev_server.initialize()
        with dev_server.connect() as database:
            database.execute(
                "INSERT INTO uploads VALUES (?, ?, ?, ?, ?, ?)",
                ("upload-1", "episode.wav", str(Path(self.directory.name, "episode.wav")), "audio/wav", 10, dev_server.now()),
            )

    def tearDown(self):
        self.database_patch.stop()
        self.directory.cleanup()

    def insert_job(self, status: str = "queued", context: dict | None = None, request_key: str | None = None):
        created = dev_server.now()
        with dev_server.connect() as database:
            database.execute(
                """
                INSERT INTO jobs (
                  id, episode_id, upload_id, status, stage, progress, tracks_json,
                  error, operation, context_json, request_key, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, '[]', NULL, 'analyze', ?, ?, ?, ?)
                """,
                (
                    "job-1", "episode-1", "upload-1", status, "Test", 25,
                    json.dumps(context or {}), request_key, created, created,
                ),
            )

    def test_running_job_is_requeued_after_restart_and_claimed(self):
        self.insert_job("running")
        dev_server.initialize()
        recovered = dev_server.get_job("job-1")
        self.assertEqual("queued", recovered["status"])
        self.assertIn("redémarrage", recovered["stage"])

        claimed = dev_server.claim_next_job()
        self.assertEqual("job-1", claimed["id"])
        self.assertEqual("running", dev_server.get_job("job-1")["status"])
        self.assertIsNone(dev_server.claim_next_job())

    def test_published_description_becomes_automatic_tracklist(self):
        self.insert_job(
            context={
                "automaticTracklist": True,
                "description": "00:00 Artist A — Opening\n04:12 Artist B — Second",
            },
        )
        tracks = dev_server._automatic_tracklist(
            "job-1",
            600,
            [{"time": 0}, {"time": 250}],
            dev_server.get_job_context("job-1"),
        )
        self.assertEqual(["Opening", "Second"], [track["title"] for track in tracks])
        self.assertEqual(250, tracks[1]["time"])
        self.assertIn("Tracklist publiée", tracks[0]["evidence"][0])

    def test_request_key_finds_existing_episode_job(self):
        self.insert_job("completed", request_key="episode:show-42")
        existing = dev_server.get_job_by_request_key("episode:show-42")
        self.assertEqual("job-1", existing["id"])
        self.assertIsNone(dev_server.get_job_by_request_key("episode:missing"))

    def test_external_tracklist_falls_back_when_description_is_empty(self):
        self.insert_job(
            context={
                "automaticTracklist": True,
                "enableExternalTracklists": True,
                "title": "Example Mix 42",
            },
        )
        discovery = {
            "source": "mixesdb",
            "sourceUrl": "https://www.mixesdb.com/w/Example_Mix_42",
            "candidates": [
                {"artist": "Artist A", "title": "Opening", "providedTime": 0},
                {"artist": "Artist B", "title": "Second", "providedTime": 240},
            ],
        }
        with patch("dev_server.search_external_tracklist", return_value=discovery):
            tracks = dev_server._automatic_tracklist(
                "job-1",
                600,
                [{"time": 0}, {"time": 242}],
                dev_server.get_job_context("job-1"),
            )
        self.assertEqual(["Opening", "Second"], [track["title"] for track in tracks])
        self.assertIn("MixesDB", tracks[0]["evidence"][0])

    def test_remote_job_downloads_on_worker_before_analysis(self):
        self.insert_job(
            "running",
            context={"remoteAudioUrl": "https://media.example/episode.mp3"},
        )
        dev_server.update_job("job-1", operation="download_analyze")
        audio_path = Path(self.directory.name, "episode.mp3")
        with (
            patch("dev_server.validate_remote_audio_url", return_value="https://media.example/episode.mp3"),
            patch("dev_server.download_remote_audio", return_value=(4096, "audio/mpeg")),
            patch("dev_server.run_job") as run_job,
        ):
            dev_server.run_remote_job("job-1", audio_path)
        run_job.assert_called_once_with("job-1", audio_path)
        self.assertEqual("analyze", dev_server.get_job("job-1")["operation"])
        with dev_server.connect() as database:
            upload = database.execute("SELECT size, media_type FROM uploads WHERE id = 'upload-1'").fetchone()
        self.assertEqual((4096, "audio/mpeg"), tuple(upload))


if __name__ == "__main__":
    unittest.main()
