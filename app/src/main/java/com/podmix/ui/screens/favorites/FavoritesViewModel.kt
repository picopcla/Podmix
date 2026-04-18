package com.podmix.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.FavoriteWithInfo
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.DeezerService
import com.podmix.service.FavoritePlayItem
import com.podmix.service.PlayerController
import com.podmix.service.SpotifyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val trackDao: TrackDao,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val playerController: PlayerController,
    private val spotifyService: SpotifyService,
    private val deezerService: DeezerService
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteWithInfo>> = trackDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playerState = playerController.playerState

    init {
        backfillSpotify()
        backfillDeezer()

        playerController.onTrackEnded = {
            viewModelScope.launch {
                val next = playerController.advanceFavorite() ?: return@launch
                playFavoriteItem(next)
            }
        }
    }

    private suspend fun playFavoriteItem(item: FavoritePlayItem) {
        val episodeEntity = episodeDao.getById(item.episodeId) ?: return
        val podcastEntity = podcastDao.getById(item.podcastId) ?: return

        val episode = Episode(
            id = episodeEntity.id,
            podcastId = episodeEntity.podcastId,
            title = episodeEntity.title,
            audioUrl = episodeEntity.audioUrl,
            datePublished = episodeEntity.datePublished,
            durationSeconds = episodeEntity.durationSeconds,
            progressSeconds = episodeEntity.progressSeconds,
            isListened = episodeEntity.isListened,
            artworkUrl = episodeEntity.artworkUrl,
            episodeType = episodeEntity.episodeType,
            youtubeVideoId = episodeEntity.youtubeVideoId,
            description = episodeEntity.description,
            mixcloudKey = episodeEntity.mixcloudKey,
            localAudioPath = episodeEntity.localAudioPath,
            soundcloudTrackUrl = episodeEntity.soundcloudTrackUrl
        )
        val podcast = Podcast(
            id = podcastEntity.id,
            name = podcastEntity.name,
            logoUrl = podcastEntity.logoUrl,
            description = podcastEntity.description,
            rssFeedUrl = podcastEntity.rssFeedUrl,
            type = podcastEntity.type,
            episodeCount = 0
        )
        val tracks = trackDao.getByEpisodeId(item.episodeId).map { t ->
            com.podmix.domain.model.Track(
                id = t.id, episodeId = t.episodeId, position = t.position,
                title = t.title, artist = t.artist,
                startTimeSec = t.startTimeSec, endTimeSec = t.endTimeSec,
                isFavorite = t.isFavorite, spotifyUrl = t.spotifyUrl,
                deezerUrl = t.deezerUrl
            )
        }
        val seekMs = (item.startTimeSec * 1000).toLong()
        playerController.playEpisode(episode, podcast, seekToMs = seekMs)
        playerController.updateTracks(tracks)
    }

    fun playFromFavorite(fav: FavoriteWithInfo, startChain: Boolean = true) {
        viewModelScope.launch {
            if (startChain) {
                val allFavs = favorites.value
                val items = allFavs.map { f ->
                    FavoritePlayItem(
                        episodeId = f.episodeId,
                        podcastId = f.podcastId,
                        startTimeSec = f.startTimeSec,
                        title = f.title,
                        artist = f.artist
                    )
                }
                val idx = allFavs.indexOfFirst { it.id == fav.id }.coerceAtLeast(0)
                playerController.setFavoritesPlaylist(items, idx)
            }
            playFavoriteItem(
                FavoritePlayItem(
                    episodeId = fav.episodeId,
                    podcastId = fav.podcastId,
                    startTimeSec = fav.startTimeSec,
                    title = fav.title,
                    artist = fav.artist
                )
            )
        }
    }

    fun playAllFavorites() {
        val list = favorites.value
        if (list.isNotEmpty()) {
            playFromFavorite(list.first(), startChain = true)
        }
    }

    fun backfillSpotify() {
        viewModelScope.launch {
            val allFavs = trackDao.getAllFavoritesSuspend()
            allFavs.filter { it.spotifyUrl == null }.forEach { fav ->
                spotifyService.searchAndSave(fav.id, fav.artist, fav.title)
            }
        }
    }

    fun backfillDeezer() {
        viewModelScope.launch {
            val allFavs = trackDao.getAllFavoritesSuspend()
            allFavs.filter { it.deezerUrl == null }.forEach { fav ->
                deezerService.searchAndSave(fav.id, fav.artist, fav.title)
            }
        }
    }

    /** Force la re-détection Deezer pour TOUS les favoris, même ceux déjà traités. */
    fun forceRefreshDeezer() {
        viewModelScope.launch {
            val allFavs = trackDao.getAllFavoritesSuspend()
            android.util.Log.i("FavoritesVM", "Force Deezer refresh for ${allFavs.size} favorites")
            allFavs.forEach { fav ->
                deezerService.searchAndSave(fav.id, fav.artist, fav.title)
            }
        }
    }

    fun toggleFavorite(trackId: Int) {
        viewModelScope.launch {
            trackDao.toggleFavorite(trackId)
        }
    }

    fun pauseResume() {
        if (playerController.playerState.value.isPlaying) {
            playerController.pause()
        } else {
            playerController.resume()
        }
    }
}
