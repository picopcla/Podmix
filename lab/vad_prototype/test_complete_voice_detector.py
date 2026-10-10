from __future__ import annotations

import json
from pathlib import Path
import unittest

from complete_voice_detector import detect_candidates


LAB = Path(__file__).resolve().parent


class CompleteVoiceDetectorTests(unittest.TestCase):
    def test_all_candidates_are_emitted_before_anchor_threshold(self):
        ina = {"segments": [
            {"label": "speech", "start": 10.0, "end": 12.0},
            {"label": "music", "start": 12.0, "end": 30.0},
        ]}
        silero = {"segments": [{"label": "speech", "start": 10.1, "end": 12.1}]}
        params = json.loads((LAB / "full_episode_protocol.json").read_text())["detector"]
        candidates = detect_candidates(ina, silero, params)
        self.assertEqual(len(candidates), 1)
        self.assertIn("confidence", candidates[0])
        self.assertNotIn("passes_reliable_threshold", candidates[0])

    def test_protocol_covers_the_full_audio_and_transcribes_every_candidate(self):
        protocol = json.loads((LAB / "fyh512_complete_voice_protocol.json").read_text())
        self.assertEqual(protocol["duration_seconds"], 7216.927375)
        self.assertEqual(protocol["vad_methods_in_execution_order"], ["ina", "silero"])
        self.assertIn("every automatically detected candidate", protocol["transcription"]["scope"])
        self.assertFalse(protocol["interpretation"]["frozen_event_replay"])

    def test_real_run_trace_proves_complete_hook_execution(self):
        path = LAB / "results/fyh512-existing-engine-complete-voice-trace-frozen.json"
        if not path.exists():
            self.skipTest("La passe lourde n'a pas encore ete executee")
        trace = json.loads(path.read_text())
        local = trace["voice"]["local_pass"]
        self.assertTrue(local["coverage_complete"])
        self.assertEqual((local["coverage_start_seconds"], local["coverage_end_seconds"]), (0.0, 7216.927375))
        self.assertEqual(local["windows"], {"ina": 14, "silero": 14})
        self.assertEqual([row["stage"] for row in local["commands"]], ["vad_ina", "vad_silero", "asr"])
        self.assertEqual(local["candidates"], local["transcribed_candidates"])
        self.assertEqual(local["candidates"], len(trace["voice"]["events"]))

    def test_d006_is_transcribed_then_rejected_without_threshold_change(self):
        path = LAB / "results/fyh512-existing-engine-complete-voice-candidate-audit.json"
        if not path.exists():
            self.skipTest("L'evaluation lourde n'a pas encore ete executee")
        rows = json.loads(path.read_text())
        d006 = next(row for row in rows if row["detection_id"].endswith("d006"))
        self.assertEqual(d006["vad_confidence"], 0.8435)
        self.assertTrue(d006["transcribed"])
        self.assertEqual(d006["interpretation_decision"], "abstain")
        self.assertEqual(d006["anchor_decision"], "not_submitted")
        self.assertEqual(d006["anchor_reason"], "voice_confidence_below_threshold")


if __name__ == "__main__":
    unittest.main()
