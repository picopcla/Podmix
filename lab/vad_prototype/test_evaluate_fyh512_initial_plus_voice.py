import unittest

from evaluate_fyh512_initial_plus_voice import match_free


class FreeMatchingTest(unittest.TestCase):
    def test_global_greedy_one_to_one_and_tolerance(self):
        references = [
            {"number": 1, "artist": "a", "title": "a", "youtube_time_seconds": 0, "rss_time_seconds": 100.0},
            {"number": 2, "artist": "b", "title": "b", "youtube_time_seconds": 0, "rss_time_seconds": 130.0},
        ]
        detections = [
            {"detection_id": "d1", "origin": "test", "rss_time_seconds": 104.0},
            {"detection_id": "d2", "origin": "test", "rss_time_seconds": 160.1},
        ]
        pairs, missing, surplus = match_free(detections, references)
        self.assertEqual([(row["detection_id"], row["reference_number"]) for row in pairs], [("d1", 1)])
        self.assertEqual([row["number"] for row in missing], [2])
        self.assertEqual([row["detection_id"] for row in surplus], ["d2"])

    def test_detection_is_used_only_once(self):
        references = [
            {"number": 1, "artist": "a", "title": "a", "youtube_time_seconds": 0, "rss_time_seconds": 100.0},
            {"number": 2, "artist": "b", "title": "b", "youtube_time_seconds": 0, "rss_time_seconds": 110.0},
        ]
        detections = [{"detection_id": "d", "origin": "test", "rss_time_seconds": 106.0}]
        pairs, missing, surplus = match_free(detections, references)
        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0]["reference_number"], 2)
        self.assertEqual(len(missing), 1)
        self.assertEqual(surplus, [])


if __name__ == "__main__":
    unittest.main()
