import unittest

from evaluate_fyh512_existing_engine import match_free


class ExistingEngineEvaluationTests(unittest.TestCase):
    def test_free_greedy_matching_uses_smallest_errors_once(self):
        detections = [{"number": 1, "rss_time_seconds": 100.0}, {"number": 2, "rss_time_seconds": 108.0}]
        references = [{"number": 1, "artist": "a", "title": "x", "youtube_time_seconds": 0, "rss_time_seconds": 101.0},
                      {"number": 2, "artist": "b", "title": "y", "youtube_time_seconds": 0, "rss_time_seconds": 105.0}]
        pairs, missing, surplus = match_free(detections, references)
        self.assertEqual([(row["algorithm_track_number"], row["reference_number"]) for row in pairs], [(1, 1), (2, 2)])
        self.assertEqual(missing, []); self.assertEqual(surplus, [])


if __name__ == "__main__":
    unittest.main()
