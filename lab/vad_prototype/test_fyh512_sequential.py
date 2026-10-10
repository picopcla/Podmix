import unittest

from analyze_fyh512_sequential import retained_candidates


class SequentialSelectionTests(unittest.TestCase):
    def test_fixed_threshold_and_separation(self):
        result = retained_candidates([(10, 2.9), (20, 4.0), (50, 5.0), (120, 3.0)], 3.0, 60.0)
        self.assertEqual(result, [(50, 5.0), (120, 3.0)])

    def test_no_forced_cardinality(self):
        self.assertEqual(retained_candidates([(10, 0.6), (80, 2.99)], 3.0, 60.0), [])


if __name__ == "__main__":
    unittest.main()
