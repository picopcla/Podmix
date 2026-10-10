import hashlib
import json
import unittest
from pathlib import Path

from analyze_fyh512_initial_plus_voice import BASELINE, build


class InitialPlusVoiceTest(unittest.TestCase):
    def test_baseline_exact_and_complement_trace(self):
        self.assertEqual(
            hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
            "d8369ba170b98fd96c933520b0519b7f1fc0eefd75ebd0c770f0c26772de3378",
        )
        completed, trace, summary = build()
        self.assertEqual(summary["baseline_points"], 32)
        self.assertEqual(summary["reliable_voice_detections"], 4)
        self.assertEqual(summary["below_threshold_voice_detections"], 6)
        self.assertEqual(summary["spectral_points_replaced"], 4)
        self.assertEqual(summary["voice_points_added_without_replacement"], 0)
        self.assertEqual(len(completed), 32)
        self.assertEqual(sum(row["action"] == "retained" for row in trace), 28)
        self.assertEqual(sum(row["action"] == "replaced_by_voice" for row in trace), 4)
        self.assertEqual(sum(row["action"] == "reconciled_replaces_spectral" for row in trace), 4)
        self.assertEqual(sum(row["action"] == "ignored" for row in trace), 6)
        self.assertTrue(all(row["independent_title"] is None for row in completed))

    def test_voice_times_replace_only_nearby_spectral_points(self):
        completed, trace, _ = build()
        voice_rows = [row for row in completed if row["algorithm_origin"].startswith("reliable_voice")]
        self.assertEqual([row["algorithm_time_seconds"] for row in voice_rows], [120.0, 2365.68, 5349.38, 6975.72])
        reconciled = [row for row in trace if row["action"] == "reconciled_replaces_spectral"]
        self.assertTrue(all(row["absolute_delta_seconds"] <= 45.0 for row in reconciled))


if __name__ == "__main__":
    unittest.main()
