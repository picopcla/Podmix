from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

import numpy as np


LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path[:0] = [str(ROOT / "server"), str(LAB)]
import audio_fallback_voice as voice  # noqa: E402


class ExistingEnginePlusVoiceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tracks = json.loads((LAB / "fyh512_title_candidates.json").read_text())["tracks"]
        cls.events = json.loads((LAB / "results/fyh512-reliable-voices-asr-frozen.json").read_text())

    def test_fyh512_announcements_are_automatic_and_internal(self):
        anchors, trace = voice.interpret_voice_announcements(self.events, self.tracks)
        self.assertEqual([(row.track_index, row.time_seconds) for row in anchors], [
            (0, 120.0), (11, 2365.68), (23, 5349.38), (31, 6975.72),
        ])
        self.assertTrue(all(row["decision"] == "candidate_anchor" for row in trace))

    def test_recap_jingle_current_previous_and_ambiguous_abstain(self):
        events = [
            {"detection_id": "recap", "voice_end_seconds": 100, "voice_internal_confidence": .99,
             "transcript": "Coming up tonight: Save Me, Shadows and Stay."},
            {"detection_id": "jingle", "voice_end_seconds": 200, "voice_internal_confidence": .99,
             "transcript": "Find Your Harmony."},
            {"detection_id": "current", "voice_end_seconds": 300, "voice_internal_confidence": .99,
             "transcript": "You are listening to Save Me."},
            {"detection_id": "previous", "voice_end_seconds": 400, "voice_internal_confidence": .99,
             "transcript": "That was Save Me."},
            {"detection_id": "ambiguous", "voice_end_seconds": 500, "voice_internal_confidence": .99,
             "transcript": "Next up, more music."},
        ]
        anchors, trace = voice.interpret_voice_announcements(events, self.tracks)
        self.assertEqual(anchors, [])
        self.assertTrue(all(row["decision"] == "abstain" for row in trace))

    def test_intro_nonzero_and_no_preview_offset(self):
        anchors = {
            0: voice.BoundaryAnchor(0, 120.0, .99, "next", "intro", "a"),
            1: voice.BoundaryAnchor(1, 220.0, .99, "next", "one", "b"),
            2: voice.BoundaryAnchor(2, 320.0, .99, "next", "two", "c"),
        }
        novelty = np.zeros(int(400 / voice.HOP_SECONDS) + 1, dtype=np.float32)
        result = voice.detect_constrained_transitions(novelty, 3, 400.0, {}, anchors)
        self.assertEqual(result, [120.0, 220.0, 320.0])
        theoretical = voice._anchored_theoretical_boundaries(3, 400.0, {}, anchors)
        self.assertEqual(theoretical[:3], [120.0, 220.0, 320.0])

    def test_presence_conflict_abstains(self):
        anchor = voice.BoundaryAnchor(1, 500.0, .99, "next", "x", "conflict")
        presence = voice.LandmarkPresence(2, 100.0, 100.0, 130.0, 20.0, 50)
        accepted, trace = voice.validate_boundary_anchors([anchor], 3, 900.0, {2: presence}, {})
        self.assertEqual(accepted, {})
        self.assertEqual(trace[0]["reason"], "landmark_presence_conflict")

    def test_exact_reported_optimizer_conflict_abstains_and_continues(self):
        anchors = [
            voice.BoundaryAnchor(1, 150, .99, "next", "x", "a"),
            voice.BoundaryAnchor(2, 195, .99, "next", "y", "b"),
        ]
        novelty = np.zeros(int(500 / voice.HOP_SECONDS) + 1)
        accepted, trace = voice.validate_boundary_anchors(
            anchors, 4, 500, {}, {},
        )
        result = voice.detect_constrained_transitions(
            novelty, 4, 500, {}, accepted,
        )
        self.assertEqual(sorted(accepted), [1])
        self.assertEqual(trace[0]["decision"], "accepted")
        self.assertEqual(trace[1]["reason"], "optimizer_constraint_conflict")
        self.assertEqual(len(result), 4)

    def test_intro_first_boundary_and_duration_conflicts_use_optimizer(self):
        anchors = [
            voice.BoundaryAnchor(0, 430, .99, "next", "late intro", "intro"),
            voice.BoundaryAnchor(1, 30, .99, "next", "early first", "first"),
            voice.BoundaryAnchor(3, 490, .99, "next", "late end", "end"),
        ]
        accepted, trace = voice.validate_boundary_anchors(anchors, 4, 500, {}, {})
        self.assertEqual(accepted, {})
        self.assertEqual(
            [row["reason"] for row in trace],
            ["optimizer_constraint_conflict"] * 3,
        )

    def test_all_voice_conflicts_fall_back_exactly_to_disabled_mode(self):
        anchors = [
            voice.BoundaryAnchor(1, 500, .99, "next", "presence", "p"),
            voice.BoundaryAnchor(2, 600, .99, "next", "correction", "c"),
        ]
        presences = {2: voice.LandmarkPresence(2, 100, 100, 130, 20, 50)}
        corrections = {2: 200.0}
        accepted, trace = voice.validate_boundary_anchors(
            anchors, 4, 900, presences, corrections,
        )
        self.assertEqual(accepted, {})
        self.assertEqual(
            [row["reason"] for row in trace],
            ["landmark_presence_conflict", "adjacent_presence_conflict"],
        )
        novelty = np.zeros(int(900 / voice.HOP_SECONDS) + 1)
        without_voice = voice.detect_constrained_transitions(
            novelty, 4, 900, presences, {},
        )
        after_abstention = voice.detect_constrained_transitions(
            novelty, 4, 900, presences, accepted,
        )
        self.assertEqual(after_abstention, without_voice)
        self.assertEqual(after_abstention[0], 0.0)

    def test_partial_conflict_keeps_feasible_anchors_and_traces_both(self):
        anchors = [
            voice.BoundaryAnchor(1, 150, .99, "next", "kept", "a"),
            voice.BoundaryAnchor(2, 195, .99, "next", "dropped", "b"),
            voice.BoundaryAnchor(3, 360, .99, "next", "kept", "c"),
        ]
        novelty = np.zeros(int(500 / voice.HOP_SECONDS) + 1)
        accepted, trace = voice.validate_boundary_anchors(
            anchors, 4, 500, {}, {}, novelty,
        )
        self.assertEqual(sorted(accepted), [1, 3])
        self.assertEqual(
            [(row["detection_id"], row["decision"]) for row in trace],
            [("a", "accepted"), ("b", "abstain"), ("c", "accepted")],
        )

    def test_order_conflict_abstains(self):
        anchors = [
            voice.BoundaryAnchor(2, 500.0, .99, "next", "x", "first"),
            voice.BoundaryAnchor(1, 600.0, .99, "next", "y", "second"),
        ]
        accepted, trace = voice.validate_boundary_anchors(anchors, 4, 1000.0, {}, {})
        self.assertIn(2, accepted)
        self.assertEqual(trace[1]["reason"], "anchor_order_conflict")

    def test_unannounced_tracks_remain_in_optimizer(self):
        anchors = {1: voice.BoundaryAnchor(1, 150.0, .99, "next", "x", "one")}
        novelty = np.zeros(int(500 / voice.HOP_SECONDS) + 1, dtype=np.float32)
        for second in (280, 390):
            novelty[int(second / voice.HOP_SECONDS)] = 10.0
        result = voice.detect_constrained_transitions(novelty, 4, 500.0, {}, anchors)
        self.assertEqual(len(result), 4)
        self.assertEqual(result[1], 150.0)
        self.assertGreater(result[2], result[1])
        self.assertGreater(result[3], result[2])


if __name__ == "__main__":
    unittest.main()
