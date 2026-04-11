package com.podmix.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.TrackDao
import com.podmix.service.PlayerController
import com.podmix.service.SpotifyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val trackDao: TrackDao,
    private val spotifyService: SpotifyService
) : ViewModel() {

    val playerState = playerController.playerState

    fun playPause() {
        if (playerController.playerState.value.isPlaying) playerController.pause()
        else playerController.resume()
    }

    fun seekTo(ms: Long) = playerController.seekTo(ms)
    fun seekBack() = playerController.seekBack()
    fun seekForward() = playerController.seekForward()
    fun toggleLoop() = playerController.toggleLoop()
    fun nextTrack() = playerController.nextTrack()
    fun prevTrack() = playerController.prevTrack()

    fun toggleFavorite(trackId: Int) {
        viewModelScope.launch {
            trackDao.toggleFavorite(trackId)
            // Update the track list in PlayerState so UI reflects the change
            val currentTracks = playerController.playerState.value.currentTracks
            val updated = currentTracks.map { t ->
                if (t.id == trackId) t.copy(isFavorite = !t.isFavorite) else t
            }
            playerController.updateTracksKeepIndex(updated)

            // If track is now a favorite and has no Spotify URL, search Spotify
            val track = updated.find { it.id == trackId }
            if (track != null && track.isFavorite && track.spotifyUrl == null) {
                spotifyService.searchAndSave(trackId, track.artist, track.title)
            }
        }
    }
}
