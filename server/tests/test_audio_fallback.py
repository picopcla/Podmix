from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from audio_fallback import (
    FeatureSequence,
    LandmarkPresence,
    MatchCandidate,
    _resample_chroma,
    _resample_spectral,
    _soundcloud_reference_score,
    _download_source,
    AudioFallbackError,
    boundaries_are_coherent,
    detect_pure_transitions,
    detect_constrained_transitions,
    landmark_transition_corrections,
    match_reference,
    pure_transition_novelty,
    refine_boundaries,
    select_global_sequence,
    transition_novelty,
)


class AudioFallbackTests(unittest.TestCase):
    @staticmethod
    def _features(spectral, random):
        chroma = random.random((spectral.shape[0], 12), dtype=np.float32)
        chroma /= np.linalg.norm(chroma, axis=1, keepdims=True)
        return FeatureSequence(
            chroma=chroma,
            rms=np.ones(spectral.shape[0], dtype=np.float32) * 0.4,
            bass=np.ones(spectral.shape[0], dtype=np.float32) * 0.5,
            flux=np.zeros(spectral.shape[0], dtype=np.float32),
            spectral=spectral,
        )

    def test_pure_detector_selects_known_number_of_real_transition_peaks(self):
        # Quatre morceaux de longueurs volontairement inégales. Les nombreux
        # petits événements de mix ne doivent pas remplacer les trois ruptures
        # de texture persistantes.
        frames = 15_000
        novelty = np.zeros(frames, dtype=np.float32)
        for center, amplitude in ((2_400, 6.0), (6_700, 4.0), (11_500, 5.0)):
            positions = np.arange(frames, dtype=np.float32)
            novelty += amplitude * np.exp(-((positions - center) / 85.0) ** 2)
        novelty[4_500] = 3.0  # break bref : pas une transition de track.

        boundaries = detect_pure_transitions(
            novelty, track_count=4, duration_seconds=frames * 0.064,
        )

        self.assertEqual(4, len(boundaries))
        self.assertEqual(0.0, boundaries[0])
        for found, expected in zip(boundaries[1:], (2_400, 6_700, 11_500)):
            self.assertAlmostEqual(expected * 0.064, found, delta=3.0)

    def test_download_source_tries_next_published_media_after_403(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "mix.audio"
            def download(url, path):
                if "soundcloud" in url:
                    path.write_bytes(b"partial")
                    raise AudioFallbackError("HTTP 403")
                path.write_bytes(b"youtube-ok")

            with patch("audio_fallback._download_media", side_effect=download) as media:
                _download_source(
                    "",
                    "https://soundcloud.com/example/set",
                    destination,
                    ["https://youtu.be/example"],
                )

            self.assertEqual(b"youtube-ok", destination.read_bytes())
            self.assertEqual(2, media.call_count)

    def test_isolated_anchor_prevents_a_remote_spectral_break(self):
        # Sans ancre, un gros break à 10:40 pourrait être pris pour la
        # deuxième coupe. La présence de la piste 2 vers 3:50 recalcule les
        # durées locales : la coupe 2 reste dans son couloir autour de 7:00.
        duration, tracks = 800.0, 4
        novelty = np.zeros(int(duration / 0.064), dtype=np.float32)
        for seconds, strength in ((230, 4.0), (420, 0.3), (610, 4.0), (650, 30.0)):
            frame = int(seconds / 0.064)
            novelty[frame - 5:frame + 6] = strength
        presence = LandmarkPresence(
            track=2, anchor=260.0, start=260.0, end=290.0,
            score=30.0, events=50,
        )

        boundaries = detect_constrained_transitions(
            novelty, tracks, duration, {2: presence},
        )

        self.assertAlmostEqual(230.0, boundaries[1], delta=2.0)
        self.assertAlmostEqual(420.0, boundaries[2], delta=2.0)
        # Le troisième couloir peut légitimement choisir son pic fort à 10:50.
        self.assertGreater(boundaries[3], 600.0)
        self.assertLess(boundaries[2], 500.0)

    def test_incompatible_close_anchors_are_softened_instead_of_failing(self):
        duration, tracks = 1_200.0, 6
        novelty = np.zeros(int(duration / 0.064), dtype=np.float32)
        presences = {
            4: LandmarkPresence(4, 600.0, 600.0, 620.0, 30.0, 50),
            # Faux match/mashup : deux pistes successives seulement 25 s plus
            # loin. Le maillage doit l'assouplir, pas faire échouer le job.
            5: LandmarkPresence(5, 625.0, 625.0, 645.0, 25.0, 45),
        }

        boundaries = detect_constrained_transitions(
            novelty, tracks, duration, presences,
        )

        self.assertEqual(tracks, len(boundaries))
        self.assertTrue(all(
            right - left >= 69.9
            for left, right in zip(boundaries, boundaries[1:])
        ))

    def test_post_landmark_validation_rejects_an_excessive_gap(self):
        self.assertFalse(boundaries_are_coherent(
            [0.0, 240.0, 785.7, 1_000.0], 1_200.0, 4,
        ))
        self.assertTrue(boundaries_are_coherent(
            [0.0, 240.0, 600.0, 920.0], 1_200.0, 4,
        ))

    def test_pure_novelty_marks_the_middle_of_a_gradual_texture_crossfade(self):
        frames = 7_000
        center, width = 3_500, 1_000
        chroma = np.zeros((frames, 12), dtype=np.float32)
        spectral = np.zeros((frames, 60), dtype=np.float32)
        fade = np.clip((np.arange(frames) - (center - width // 2)) / width, 0.0, 1.0).astype(np.float32)
        chroma[:, 1], chroma[:, 8] = 1.0 - fade, fade
        spectral[:, 8], spectral[:, 40] = 1.0 - fade, fade
        features = FeatureSequence(
            chroma=chroma,
            rms=0.42 - fade * 0.1,
            bass=0.72 - fade * 0.35,
            flux=np.ones(frames, dtype=np.float32) * 0.01,
            spectral=spectral,
        )

        novelty = pure_transition_novelty(features)
        found = int(np.argmax(novelty)) * 0.064

        self.assertAlmostEqual(center * 0.064, found, delta=10.0)

    def test_spectral_resampling_keeps_all_log_frequency_bins(self):
        spectral = np.eye(60, dtype=np.float32)

        stretched = _resample_spectral(spectral, 1.03)

        self.assertEqual(60, stretched.shape[1])

    def test_match_tolerates_dj_speed_change(self):
        random = np.random.default_rng(7)
        preview = random.random((320, 12), dtype=np.float32)
        preview /= np.linalg.norm(preview, axis=1, keepdims=True)
        inserted = _resample_chroma(preview, 1.03)
        mix = random.random((4_000, 12), dtype=np.float32) * 0.08
        mix /= np.linalg.norm(mix, axis=1, keepdims=True)
        start = 1_450
        mix[start:start + inserted.shape[0]] = inserted

        matches = match_reference(mix, preview, top_k=4)

        self.assertTrue(matches)
        self.assertAlmostEqual(start * 0.064, matches[0].anchor_seconds, delta=0.2)
        self.assertAlmostEqual(1.03, matches[0].speed, delta=0.001)

    def test_global_alignment_rejects_out_of_order_local_maximum(self):
        candidates = [
            [MatchCandidate(100, 0.8, 8.0, 1.0)],
            [
                MatchCandidate(50, 0.9, 12.0, 1.0),
                MatchCandidate(300, 0.8, 7.0, 1.0),
            ],
            [MatchCandidate(520, 0.8, 8.0, 1.0)],
        ]

        selected = select_global_sequence(candidates, duration_seconds=700)

        self.assertEqual(300, selected[1].anchor_seconds)
        self.assertEqual([100, 300, 520], [selected[index].anchor_seconds for index in range(3)])

    def test_global_alignment_can_skip_one_false_reference(self):
        candidates = [
            [MatchCandidate(100, 0.8, 8.0, 1.0)],
            [MatchCandidate(40, 0.9, 12.0, 1.0)],
            [MatchCandidate(520, 0.8, 8.0, 1.0)],
        ]

        selected = select_global_sequence(candidates, duration_seconds=700)

        self.assertEqual([0, 2], sorted(selected))

    def test_transition_refinement_finds_long_crossfade_change(self):
        frames = 8_000
        change = 3_100
        fade_frames = 900
        fade_start = change - fade_frames // 2
        fade_end = change + fade_frames // 2
        chroma = np.zeros((frames, 12), dtype=np.float32)
        chroma[:fade_start, 0] = 1.0
        blend = np.linspace(0.0, 1.0, fade_frames, dtype=np.float32)
        chroma[fade_start:fade_end, 0] = 1.0 - blend
        chroma[fade_start:fade_end, 7] = blend
        chroma[fade_end:, 7] = 1.0
        bass = np.ones(frames, dtype=np.float32) * 0.65
        bass[fade_start:fade_end] = 0.65 - blend * 0.30
        bass[fade_end:] = 0.35
        features = FeatureSequence(
            chroma=chroma,
            rms=np.ones(frames, dtype=np.float32) * 0.4,
            bass=bass,
            flux=np.zeros(frames, dtype=np.float32),
        )
        novelty = transition_novelty(features)
        expected_seconds = change * 0.064
        matches = {
            1: MatchCandidate(expected_seconds + 30, 0.8, 7.0, 1.0),
        }

        boundaries = refine_boundaries(
            novelty,
            track_count=2,
            matches=matches,
            duration_seconds=frames * 0.064,
        )

        self.assertAlmostEqual(expected_seconds, boundaries[1], delta=3.0)

    def test_landmarks_correct_only_two_adjacent_recognized_tracks(self):
        random = np.random.default_rng(17)
        parts = []
        for _ in range(3):
            spectral = random.random((900, 60), dtype=np.float32)
            spectral /= np.linalg.norm(spectral, axis=1, keepdims=True)
            parts.append(spectral)
        mix = self._features(np.vstack(parts), random)
        references = [[self._features(part.copy(), random)] for part in parts]

        corrections, confidence, matched = landmark_transition_corrections(
            mix, references, 2_700 * 0.064, [],
        )
        isolated, _, isolated_matched = landmark_transition_corrections(
            mix, [references[0], [], references[2]], 2_700 * 0.064, [],
        )

        self.assertEqual(3, matched)
        self.assertEqual({1, 2}, set(corrections))
        self.assertTrue(all(value == "high" for value in confidence.values()))
        self.assertEqual({}, isolated)
        self.assertEqual(2, isolated_matched)

    def test_soundcloud_reference_score_rejects_another_track_by_same_artist(self):
        exact = _soundcloud_reference_score(
            "Key4050", "Feel Less (Extended Mix)",
            "Key4050 - Feel Less (Extended Mix)", "Bryan Kearney",
        )
        wrong = _soundcloud_reference_score(
            "Key4050", "Feel Less (Extended Mix)",
            "Key4050 - Empty Space (Extended Mix)", "Key4050",
        )

        self.assertGreaterEqual(exact, 0.72)
        self.assertEqual(0.0, wrong)


if __name__ == "__main__":
    unittest.main()
