import unittest

from analyze_fyh512_local_landmarks import (
    bounded_window, interval_sample_bounds, local_to_absolute, select_in_window,
)


SETTINGS = {
    "presence_edge_padding_seconds": 30.0,
    "minimum_window_seconds": 24.0,
    "maximum_window_seconds": 240.0,
    "spectral_salience_minimum": 3.0,
    "ambiguous_runner_score_ratio": 0.85,
    "ambiguous_runner_score_delta": 0.5,
    "ambiguous_runner_minimum_separation_seconds": 12.0,
}


class LocalLandmarkTests(unittest.TestCase):
    def test_exact_interval_slice_and_contiguous_bounds(self):
        self.assertEqual(interval_sample_bounds(120.0, 2365.68, 8000, 60_000_000),
                         (960_000, 18_925_440))
        left = interval_sample_bounds(0.0, 120.0, 8000, 60_000_000)
        right = interval_sample_bounds(120.0, 2365.68, 8000, 60_000_000)
        self.assertEqual(left[1], right[0])

    def test_local_offset_added_exactly_once(self):
        self.assertEqual(local_to_absolute(217.92, 120.0, 2365.68), 337.92)
        with self.assertRaises(ValueError):
            local_to_absolute(2365.68 + 217.92, 120.0, 2365.68)

    def test_absolute_bounds(self):
        self.assertEqual(local_to_absolute(0.0, 5349.38, 6975.72), 5349.38)
        self.assertEqual(local_to_absolute(1626.34, 5349.38, 6975.72), 6975.72)
        with self.assertRaises(ValueError):
            local_to_absolute(-0.1, 5349.38, 6975.72)

    def test_progressive_overlap_window_is_not_an_anchor_start(self):
        left = {"presence_end_local_seconds": 140.0}
        right = {"presence_start_local_seconds": 100.0}
        self.assertEqual(bounded_window(left, right, 500.0, SETTINGS), (70.0, 170.0))

    def test_ambiguous_spectral_candidates_abstain(self):
        selected, reason, _ = select_in_window([(100.0, 4.0), (125.0, 3.8)], 80.0, 150.0, SETTINGS)
        self.assertIsNone(selected)
        self.assertEqual(reason, "abstention_maxima_spectraux_ambigus")


if __name__ == "__main__":
    unittest.main()
