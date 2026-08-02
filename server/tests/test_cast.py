from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

import dev_server


class CastDurationTests(unittest.TestCase):
    def setUp(self):
        dev_server.CAST_DURATION_CACHE.clear()

    def test_known_duration_does_not_probe(self):
        with patch("dev_server.probe_cast_duration") as probe:
            duration = dev_server.resolve_cast_duration("https://media.example/show.mp3", 120, 3600)
        self.assertEqual(3600, duration)
        probe.assert_not_called()

    def test_missing_duration_is_probed_after_safe_redirect_resolution(self):
        response = Mock()
        completed = subprocess.CompletedProcess([], 0, stdout="6924.202350\n", stderr="")
        with (
            patch(
                "dev_server.open_cast_upstream",
                return_value=(response, "https://cdn.example/show.mp3?signature=safe"),
            ) as upstream,
            patch("dev_server.subprocess.run", return_value=completed) as ffprobe,
        ):
            duration = dev_server.resolve_cast_duration("https://media.example/show.mp3", 6231.6, 0)

        self.assertAlmostEqual(6924.20235, duration)
        upstream.assert_called_once()
        response.close.assert_called_once()
        self.assertEqual("https://cdn.example/show.mp3?signature=safe", ffprobe.call_args.args[0][-1])

    def test_probed_duration_is_cached_for_later_tracks(self):
        response = Mock()
        completed = subprocess.CompletedProcess([], 0, stdout="3600\n", stderr="")
        with (
            patch("dev_server.open_cast_upstream", return_value=(response, "https://cdn.example/show.mp3")),
            patch("dev_server.subprocess.run", return_value=completed) as ffprobe,
        ):
            first = dev_server.resolve_cast_duration("https://media.example/show.mp3", 120, 0)
            second = dev_server.resolve_cast_duration("https://media.example/show.mp3", 240, 0)

        self.assertEqual(3600, first)
        self.assertEqual(3600, second)
        ffprobe.assert_called_once()

    def test_unusable_probe_never_turns_start_position_into_duration(self):
        with patch("dev_server.probe_cast_duration", return_value=0):
            duration = dev_server.resolve_cast_duration("https://media.example/show.mp3", 1200, 1200)
        self.assertEqual(0, duration)


if __name__ == "__main__":
    unittest.main()
