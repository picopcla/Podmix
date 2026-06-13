package com.podmix.v2.playback

import com.podmix.v2.domain.model.PlayableMedia
import kotlinx.coroutines.flow.Flow

interface PlaybackCoordinator {
    val currentMedia: Flow<PlayableMedia?>
    suspend fun play(media: PlayableMedia)
    suspend fun pause()
}

data class PlayerShellState(
    val currentMediaTitle: String? = null,
    val isPlaying: Boolean = false
)
