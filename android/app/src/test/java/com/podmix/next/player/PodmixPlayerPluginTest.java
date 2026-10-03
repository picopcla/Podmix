package com.podmix.next.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PodmixPlayerPluginTest {
    @Test
    public void onlyPlayStateConfirmsBosePlayback() {
        assertTrue(PodmixPlayerPlugin.isBosePlayingStatus("PLAY_STATE"));
        assertFalse(PodmixPlayerPlugin.isBosePlayingStatus("BUFFERING_STATE"));
        assertFalse(PodmixPlayerPlugin.isBosePlayingStatus("PAUSE_STATE"));
        assertFalse(PodmixPlayerPlugin.isBosePlayingStatus("STOP_STATE"));
        assertFalse(PodmixPlayerPlugin.isBosePlayingStatus(""));
    }
}
