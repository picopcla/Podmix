from __future__ import annotations

from pathlib import Path
import json
import sys
import unittest
from unittest import mock

import numpy as np


LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[1]
sys.path[:0] = [str(ROOT / "server"), str(LAB)]
import audio_fallback_joint_a as joint  # noqa: E402


def anchor(index: int, second: float, name: str, confidence: float = 0.99):
    return joint.BoundaryAnchor(index, second, confidence, "next", name, name)


def candidate(track: int, second: float, score: float = 20.0):
    return joint.LandmarkCandidate(track, 0, second, 1.0, score, 40)


def presence(item: joint.LandmarkCandidate):
    return joint.LandmarkPresence(
        item.track, item.anchor, item.anchor, item.anchor + 20.0, item.score, item.votes,
    )


def bounded_presence(
    item: joint.LandmarkCandidate, start: float, end: float,
) -> joint.LandmarkPresence:
    return joint.LandmarkPresence(
        item.track, item.anchor, start, end, item.score, item.votes,
    )


class JointSelectionATests(unittest.TestCase):
    def test_frozen_interpretation_stays_at_095_and_four_candidates(self):
        tracks = json.loads((LAB / "fyh512_title_candidates.json").read_text())["tracks"]
        trace = json.loads(
            (LAB / "results/fyh512-existing-engine-complete-voice-trace-frozen.json").read_text()
        )
        anchors, decisions = joint.interpret_voice_announcements(trace["voice"]["events"], tracks)
        self.assertEqual(
            [(item.track_index, item.time_seconds) for item in anchors],
            [(0, 120.0), (11, 2365.68), (23, 5349.38), (31, 6975.72)],
        )
        self.assertEqual(sum(row["decision"] == "candidate_anchor" for row in decisions), 4)

    def test_first_chronological_anchor_does_not_block_better_set(self):
        novelty = np.zeros(int(700 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        candidates = [
            anchor(3, 200.0, "first", 0.95),
            anchor(1, 250.0, "second", 0.96),
            anchor(2, 350.0, "third", 0.97),
        ]
        accepted, decisions = joint.validate_boundary_anchors(
            candidates, 5, 700.0, {}, {}, novelty,
        )
        self.assertEqual(sorted(accepted), [1, 2])
        self.assertEqual(
            {row["detection_id"]: row["decision"] for row in decisions},
            {"first": "abstain", "second": "accepted", "third": "accepted"},
        )

    def test_two_landmark_chains_choose_the_voice_compatible_one(self):
        bad, good = candidate(1, 300.0, 21.0), candidate(1, 100.0, 20.0)
        two, three = candidate(2, 250.0), candidate(3, 400.0)
        sets = [[bad, good], [two], [three]]
        presences = {item: presence(item) for row in sets for item in row}
        novelty = np.zeros(int(550 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        chosen, _, _, _, voices, trace = joint.select_joint_landmarks_and_voice(
            sets, presences, [anchor(0, 70.0, "voice")], novelty, 550.0, [],
        )
        self.assertEqual(chosen[1], good)
        self.assertEqual(sorted(voices), [0])
        self.assertEqual(trace["selected_voice_detection_ids"], ["voice"])

    def test_alternative_chain_is_tried_after_adjacent_correction_conflict(self):
        one = candidate(1, 100.0)
        locally_best = candidate(2, 260.0, 30.0)
        compatible = candidate(2, 250.0, 20.0)
        three, four = candidate(3, 400.0), candidate(4, 520.0)
        sets = [[one], [locally_best, compatible], [three], [four]]
        presences = {
            one: bounded_presence(one, 100.0, 150.0),
            locally_best: bounded_presence(locally_best, 500.0, 530.0),
            compatible: bounded_presence(compatible, 290.0, 320.0),
            three: bounded_presence(three, 400.0, 430.0),
            four: bounded_presence(four, 520.0, 550.0),
        }
        novelty = np.zeros(int(650 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        chosen, _, _, _, voices, trace = joint.select_joint_landmarks_and_voice(
            sets, presences, [anchor(1, 230.0, "voice")], novelty, 650.0, [],
        )
        self.assertEqual(chosen[2], compatible)
        self.assertEqual(sorted(voices), [1])
        voice_row = next(
            row for row in trace["alternatives_examined"]
            if row["voice_detection_ids"] == ["voice"]
        )
        self.assertGreater(
            voice_row["landmark_chain_search"]["adjacent_conflict_rejections"], 0,
        )

    def test_alternative_chain_is_tried_after_real_optimizer_rejection(self):
        locally_best = candidate(2, 120.0, 30.0)
        feasible = candidate(2, 45.0, 20.0)
        presences = {item: presence(item) for item in (locally_best, feasible)}
        novelty = np.zeros(int(350 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        chosen, _, _, _, voices, trace = joint.select_joint_landmarks_and_voice(
            [[], [locally_best, feasible], []], presences,
            [anchor(2, 145.0, "voice")], novelty, 350.0, [],
        )
        self.assertEqual(chosen[2], feasible)
        self.assertEqual(sorted(voices), [2])
        voice_row = next(
            row for row in trace["alternatives_examined"]
            if row["voice_detection_ids"] == ["voice"]
        )
        self.assertEqual(
            voice_row["landmark_chain_search"]["optimizer_constraint_rejections"], 1,
        )

    def test_real_conflict_still_abstains(self):
        item = anchor(1, 500.0, "conflict")
        fixed = {2: joint.LandmarkPresence(2, 100.0, 100.0, 130.0, 20.0, 50)}
        accepted, decisions = joint.validate_boundary_anchors([item], 3, 900.0, fixed, {})
        self.assertEqual(accepted, {})
        self.assertEqual(decisions[0]["reason"], "landmark_presence_conflict")

    def test_joint_true_conflict_abstains_from_the_voice_candidate(self):
        item = candidate(2, 100.0)
        novelty = np.zeros(int(500 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        chosen, _, _, _, voices, trace = joint.select_joint_landmarks_and_voice(
            [[], [item], []], {item: presence(item)},
            [anchor(1, 500.0, "conflict")], novelty, 500.0, [],
        )
        self.assertEqual(chosen, {2: item})
        self.assertEqual(voices, {})
        self.assertEqual(trace["selected_voice_detection_ids"], [])
        conflict_row = next(
            row for row in trace["alternatives_examined"]
            if row["voice_detection_ids"] == ["conflict"]
        )
        self.assertEqual(
            conflict_row["landmark_chain_search"]["direct_conflict_candidates_filtered"], 1,
        )

    def test_candidate_order_does_not_change_selection(self):
        landmarks = [
            candidate(1, 100.0), candidate(1, 90.0, 18.0),
            candidate(2, 250.0), candidate(2, 240.0, 18.0),
            candidate(3, 400.0), candidate(3, 390.0, 18.0),
        ]
        presences = {item: presence(item) for item in landmarks}
        novelty = np.zeros(int(550 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        voices = [anchor(0, 70.0, "a", 0.98), anchor(2, 400.0, "b", 0.97)]
        sets = [landmarks[0:2], landmarks[2:4], landmarks[4:6]]
        first = joint.select_joint_landmarks_and_voice(
            sets, presences, voices, novelty, 550.0, [],
        )
        second = joint.select_joint_landmarks_and_voice(
            [list(reversed(row)) for row in sets], presences,
            list(reversed(voices)), novelty, 550.0, [],
        )
        self.assertEqual(first[0], second[0])
        self.assertEqual(first[4], second[4])

    def test_chain_limit_causes_explicit_abstention_without_truncation_claim(self):
        item = candidate(1, 100.0)
        novelty = np.zeros(int(300 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        with mock.patch.object(joint, "JOINT_MAX_LANDMARK_CHAINS_PER_VOICE_SUBSET", 1):
            with self.assertRaisesRegex(
                joint.AudioFallbackError, "tronquée à 1 chaînes landmarks.*abstention",
            ):
                joint.select_joint_landmarks_and_voice(
                    [[item]], {item: presence(item)}, [], novelty, 300.0, [],
                )

    def test_fallback_without_voice_is_the_base_landmark_selection(self):
        sets = [[candidate(1, 100.0)], [candidate(2, 250.0)], [candidate(3, 400.0)]]
        self.assertEqual(
            joint.select_ordered_landmarks(sets),
            joint.select_ordered_landmarks([list(reversed(row)) for row in sets]),
        )

    def test_150_195_conflict_keeps_150_deterministically(self):
        novelty = np.zeros(int(500 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        accepted, _ = joint.validate_boundary_anchors(
            [anchor(1, 150.0, "a"), anchor(2, 195.0, "b")],
            4, 500.0, {}, {}, novelty,
        )
        self.assertEqual(sorted(accepted), [1])
        self.assertEqual(accepted[1].time_seconds, 150.0)

    def test_unannounced_titles_are_retained(self):
        novelty = np.zeros(int(500 / joint.HOP_SECONDS) + 1, dtype=np.float32)
        novelty[int(280 / joint.HOP_SECONDS)] = 10.0
        novelty[int(390 / joint.HOP_SECONDS)] = 10.0
        result = joint.detect_constrained_transitions(
            novelty, 4, 500.0, {}, {1: anchor(1, 150.0, "one")},
        )
        self.assertEqual(len(result), 4)
        self.assertEqual(result[1], 150.0)
        self.assertGreater(result[2], result[1])
        self.assertGreater(result[3], result[2])


if __name__ == "__main__":
    unittest.main()
