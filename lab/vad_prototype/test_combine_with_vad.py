#!/usr/bin/env python3

from __future__ import annotations

import copy
import json
from pathlib import Path
import unittest

import combine_with_vad as combined


class CombinedVadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol = combined.load_json(combined.PROTOCOL_PATH)
        cls.base = combined.load_base_boundaries(combined.load_json(combined.EPISODES_PATH), cls.protocol)
        cls.detections = combined.load_json(combined.DETECTIONS_PATH)

    def test_protocol_was_frozen_before_evaluation(self):
        self.assertTrue(self.protocol["frozen_before_combined_evaluation"])
        self.assertIn("ne constituent ni une reference exhaustive", self.protocol["non_tuning_statement"])

    def test_base_reproduces_all_ptr_boundaries_in_source_order(self):
        self.assertEqual(42, len(self.base))
        by_episode = {}
        for row in self.base:
            by_episode.setdefault(row["episode_id"], []).append(row)
        self.assertEqual(22, len(by_episode["pure-trance-radio-492"]))
        self.assertEqual(20, len(by_episode["pure-trance-radio-493"]))
        self.assertEqual([2, 3, 4], [row["track_number"] for row in by_episode["pure-trance-radio-492"][:3]])
        self.assertEqual([82.0, 388.0, 658.0], [row["base_time_seconds"] for row in by_episode["pure-trance-radio-492"][:3]])

    def test_without_voice_every_time_is_preserved_exactly(self):
        rows = combined.combine_boundaries(self.base, [], self.protocol)
        self.assertEqual(
            [row["base_time_seconds"] for row in rows],
            [row["automatic_combined_time_seconds"] for row in rows],
        )
        self.assertTrue(all(row["decision"] == "abstention" for row in rows))

    def test_low_confidence_detection_cannot_move_boundary(self):
        detection = {
            "episode_id": "pure-trance-radio-492",
            "detection_id": "test-low",
            "algorithm_time_seconds": 83.0,
            "confidence": self.protocol["automatic_complement"]["minimum_internal_confidence"] - 0.001,
        }
        rows = combined.combine_boundaries(self.base, [detection], self.protocol)
        first = rows[0]
        self.assertEqual(82.0, first["automatic_combined_time_seconds"])
        self.assertEqual("abstention_confidence_below_minimum", first["reason"])

    def test_ambiguous_candidates_force_abstention(self):
        detections = [
            {"episode_id": "pure-trance-radio-492", "detection_id": "test-a", "algorithm_time_seconds": 83.0, "confidence": 0.99},
            {"episode_id": "pure-trance-radio-492", "detection_id": "test-b", "algorithm_time_seconds": 84.0, "confidence": 0.99},
        ]
        rows = combined.combine_boundaries(self.base, detections, self.protocol)
        self.assertEqual(82.0, rows[0]["automatic_combined_time_seconds"])
        self.assertEqual("abstention_ambiguous_multiple_detections", rows[0]["reason"])

    def test_negative_or_too_large_move_is_rejected(self):
        detections = [
            {"episode_id": "pure-trance-radio-492", "detection_id": "test-negative", "algorithm_time_seconds": 81.0, "confidence": 0.99},
            {"episode_id": "pure-trance-radio-492", "detection_id": "test-large", "algorithm_time_seconds": 88.0, "confidence": 0.99},
        ]
        rows = combined.combine_boundaries(self.base, detections, self.protocol)
        self.assertEqual(82.0, rows[0]["automatic_combined_time_seconds"])

    def test_real_output_preserves_cardinality_titles_order_and_no_voice_times(self):
        rows = combined.combine_boundaries(self.base, self.detections, self.protocol)
        self.assertEqual(len(self.base), len(rows))
        self.assertEqual(
            [(row["boundary_id"], row["track_title"]) for row in self.base],
            [(row["boundary_id"], row["track_title"]) for row in rows],
        )
        for base_row, row in zip(self.base, rows):
            if row["decision"] == "abstention":
                self.assertEqual(base_row["base_time_seconds"], row["automatic_combined_time_seconds"])
        for episode_id in combined.TARGET_EPISODES:
            episode_rows = [row for row in rows if row["episode_id"] == episode_id]
            times = [row["automatic_combined_time_seconds"] for row in episode_rows]
            self.assertEqual(times, sorted(times))
            self.assertEqual(len(times), len(set(times)))

    def test_human_corrections_remain_a_separate_layer(self):
        rows = combined.combine_boundaries(self.base, self.detections, self.protocol)
        ptr493_t09 = next(row for row in rows if row["boundary_id"] == "pure-trance-radio-493-t09")
        self.assertEqual(2350.0, ptr493_t09["automatic_combined_time_seconds"])
        self.assertEqual(2353.08, ptr493_t09["existing_human_time_seconds"])
        self.assertEqual("existing_human_correction", ptr493_t09["automatic_plus_human_source"])

    def test_same_reference_evaluation_keeps_perfect_raw_baseline_visible(self):
        rows = combined.enrich_rows_for_output(
            combined.combine_boundaries(self.base, self.detections, self.protocol)
        )
        summary = combined.evaluate(rows, self.protocol)
        baseline = summary["scenarios"]["raw_base"]["overall"]
        self.assertEqual(42, baseline["matched_boundaries"])
        self.assertEqual(0.0, baseline["mean_absolute_error_seconds"])
        self.assertEqual(0, baseline["missing_references"])
        self.assertEqual(0, baseline["unmatched_boundaries"])

    def test_duplicate_detection_is_rejected_instead_of_silently_deduplicated(self):
        duplicate = copy.deepcopy(self.detections[0])
        with self.assertRaises(ValueError):
            combined.combine_boundaries(self.base, [duplicate, copy.deepcopy(duplicate)], self.protocol)


if __name__ == "__main__":
    unittest.main()
