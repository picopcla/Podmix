import unittest

from evaluate_fyh512_local_landmarks import match_free, reference_rss, rss_to_youtube


class LocalLandmarkEvaluationTests(unittest.TestCase):
    def test_piecewise_alignment_round_trip(self):
        for youtube in (120.0, 3390.0, 3631.0, 6974.0):
            rss = reference_rss({"youtube_time_seconds": youtube})
            self.assertAlmostEqual(rss_to_youtube(rss), youtube, places=4)

    def test_free_matching_does_not_force_cardinality(self):
        detections = [{"detection_id": "D1", "origin": "test", "rss_time_seconds": 101.0},
                      {"detection_id": "D2", "origin": "test", "rss_time_seconds": 500.0}]
        references = [{"number": 1, "artist": "a", "title": "x", "youtube_time_seconds": 100.0,
                       "rss_time_seconds": 101.3455},
                      {"number": 2, "artist": "b", "title": "y", "youtube_time_seconds": 200.0,
                       "rss_time_seconds": 201.3455}]
        pairs, missing, surplus = match_free(detections, references)
        self.assertEqual(len(pairs), 1)
        self.assertEqual(len(missing), 1)
        self.assertEqual(len(surplus), 1)


if __name__ == "__main__":
    unittest.main()
