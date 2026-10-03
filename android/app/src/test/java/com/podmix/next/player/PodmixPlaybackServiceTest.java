package com.podmix.next.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;

import org.junit.Test;

public class PodmixPlaybackServiceTest {
    @Test
    public void recoversOnlyARequestedPlaybackThatIsBufferingLongEnough() {
        assertTrue(PodmixPlaybackService.shouldRecoverPlayback(
            true, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_NONE, 35_000));
        assertFalse(PodmixPlaybackService.shouldRecoverPlayback(
            true, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_NONE, 34_999));
        assertFalse(PodmixPlaybackService.shouldRecoverPlayback(
            false, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_NONE, 30_000));
        assertFalse(PodmixPlaybackService.shouldRecoverPlayback(
            true, Player.STATE_READY, Player.PLAYBACK_SUPPRESSION_REASON_NONE, 30_000));
        assertFalse(PodmixPlaybackService.shouldRecoverPlayback(
            true, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, 30_000));
    }

    @Test
    public void castPositionKeepsAbsoluteEpisodeTimeForContinuousEpisodes() {
        assertEquals(95_000, PodmixPlayerPlugin.localPositionForCastAbsolute(true, 95_000, 60_000));
        assertEquals(35_000, PodmixPlayerPlugin.localPositionForCastAbsolute(false, 95_000, 60_000));
        assertEquals(0, PodmixPlayerPlugin.localPositionForCastAbsolute(false, 10_000, 60_000));
    }

    @Test
    public void loopsOnlyTheRequestedContinuousTrackAtItsEnd() {
        assertTrue(PodmixPlaybackService.shouldLoopContinuousTrack(
            Player.REPEAT_MODE_ONE, true, 60_000, 90_000, 90_000));
        assertFalse(PodmixPlaybackService.shouldLoopContinuousTrack(
            Player.REPEAT_MODE_OFF, true, 60_000, 90_000, 90_000));
        assertFalse(PodmixPlaybackService.shouldLoopContinuousTrack(
            Player.REPEAT_MODE_ONE, false, 60_000, 90_000, 90_000));
        assertFalse(PodmixPlaybackService.shouldLoopContinuousTrack(
            Player.REPEAT_MODE_ONE, true, 60_000, 90_000, 89_999));
    }

    @Test
    public void onlyAnEstablishedLiveRadioIsReloadedWhenPlaybackResumes() {
        assertTrue(PodmixPlaybackService.shouldRestartLiveOnPlay(
            true, false, Player.STATE_READY));
        assertTrue(PodmixPlaybackService.shouldRestartLiveOnPlay(
            true, false, Player.STATE_IDLE));
        assertTrue(PodmixPlaybackService.shouldRestartLiveOnPlay(
            true, false, Player.STATE_ENDED));
        assertFalse(PodmixPlaybackService.shouldRestartLiveOnPlay(
            true, false, Player.STATE_BUFFERING));
        assertFalse(PodmixPlaybackService.shouldRestartLiveOnPlay(
            true, true, Player.STATE_READY));
        assertFalse(PodmixPlaybackService.shouldRestartLiveOnPlay(
            false, false, Player.STATE_READY));
    }

}
