import unittest

from experiments.procedure1_landmarks import (
    Candidate,
    Presence,
    fill_missing_blocks,
    select_ordered,
    transitions_from_presence,
)


def presence(track, anchor, start, end):
    return Presence(
        track=track,
        reference=f"track-{track}",
        anchor=anchor,
        speed=1.0,
        start=start,
        end=end,
        events=100,
        score=20.0,
    )


class Procedure1LandmarksTests(unittest.TestCase):
    def test_global_selection_prefers_long_ordered_path(self):
        candidates = [
            [
                Candidate(1, "one", 100, 1.0, 12, 50),
                Candidate(1, "false", 900, 1.0, 30, 80),
            ],
            [Candidate(2, "two", 400, 1.0, 15, 60)],
            [Candidate(3, "three", 700, 1.0, 15, 60)],
        ]
        selected = select_ordered(candidates)
        self.assertEqual([selected[index].anchor for index in (1, 2, 3)], [100, 400, 700])

    def test_presence_midpoint_and_local_snap(self):
        presences = {
            1: presence(1, 0, 0, 100),
            2: presence(2, 140, 160, 220),
            3: presence(3, 400, 430, 500),
        }
        transitions = transitions_from_presence(
            presences,
            3,
            [(340.0, 2.0)],
        )
        self.assertEqual(transitions[0]["position"], 130.0)
        self.assertEqual(transitions[0]["confidence"], "high")
        self.assertEqual(transitions[1]["position"], 340.0)
        self.assertEqual(
            transitions[1]["method"],
            "presence_midpoint_snapped_to_local_change",
        )

    def test_missing_tracks_are_filled_between_landmarks(self):
        presences = {
            1: presence(1, 0, 0, 100),
            4: presence(4, 500, 550, 700),
        }
        initial = transitions_from_presence(presences, 4, [])
        completed = fill_missing_blocks(
            initial,
            presences,
            4,
            800,
            [(120.0, 1.0), (320.0, 1.0), (520.0, 1.0)],
        )
        self.assertEqual([item["position"] for item in completed], [120.0, 320.0, 520.0])
        self.assertTrue(all(item["confidence"] == "low" for item in completed))


if __name__ == "__main__":
    unittest.main()
