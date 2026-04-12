package com.podmix.ui.screens.liveset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.repository.DjRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.ArtistPageScraper
import com.podmix.service.DownloadState
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.PlayerController
import com.podmix.service.YouTubeStreamResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

@HiltViewModel
class DjDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val djRepository: DjRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    playerController: PlayerController,
    downloadManager: EpisodeDownloadManager,
    private val artistPageScraper: ArtistPageScraper,
    private val youTubeStreamResolver: YouTubeStreamResolver
) : ViewModel() {

    val playerState = playerController.playerState
    val downloadStates: StateFlow<Map<Int, DownloadState>> = downloadManager.states

    val episodeIdsWithTracks: StateFlow<Set<Int>> = trackDao.getEpisodeIdsWithTracks()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val djId: Int = savedStateHandle["djId"] ?: 0

    private val _dj = MutableStateFlow<Podcast?>(null)
    val dj: StateFlow<Podcast?> = _dj

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val sets: StateFlow<List<Episode>> = episodeDao.getByPodcastId(djId)
        .map { list ->
            list.map { e ->
                Episode(
                    id = e.id,
                    podcastId = e.podcastId,
                    title = e.title,
                    audioUrl = e.audioUrl,
                    datePublished = e.datePublished,
                    durationSeconds = e.durationSeconds,
                    progressSeconds = e.progressSeconds,
                    isListened = e.isListened,
                    artworkUrl = e.artworkUrl,
                    episodeType = e.episodeType,
                    youtubeVideoId = e.youtubeVideoId,
                    description = e.description,
                    mixcloudKey = e.mixcloudKey,
                    localAudioPath = e.localAudioPath,
                    soundcloudTrackUrl = e.soundcloudTrackUrl
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _dj.value = djRepository.getDjById(djId)
            resolveAudioForUnresolved()
        }
    }

    private fun resolveAudioForUnresolved() {
        viewModelScope.launch {
            val episodes = episodeDao.getByPodcastIdSuspend(djId)
            val unresolved = episodes.filter { ep ->
                ep.soundcloudTrackUrl.isNullOrBlank()
                    && ep.youtubeVideoId.isNullOrBlank()
                    && ep.mixcloudKey.isNullOrBlank()
                    && ep.localAudioPath == null
            }
            if (unresolved.isEmpty()) return@launch
            android.util.Log.d("DjDetailVM", "Resolving audio for ${unresolved.size} unresolved episodes")

            supervisorScope {
                unresolved.forEach { ep ->
                    launch {
                        try {
                            resolveAudioForEpisode(ep)
                        } catch (e: Exception) {
                            android.util.Log.w("DjDetailVM", "Audio resolution failed for '${ep.title}': ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveAudioForEpisode(ep: com.podmix.data.local.entity.EpisodeEntity) {
        var scUrl: String? = null
        var ytId: String? = null
        var mcKey: String? = null

        // 1. Probe 1001TL tracklist page if URL is known
        if (!ep.tracklistPageUrl.isNullOrBlank()) {
            val src = artistPageScraper.getMediaSourceFromTracklistPage(ep.tracklistPageUrl)
            scUrl = src?.soundcloudTrackUrl
            ytId = src?.youtubeVideoId
            mcKey = src?.mixcloudKey
            android.util.Log.d("DjDetailVM", "1001TL probe '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
        }

        // 2. DDG SoundCloud search
        if (scUrl == null && ytId == null && mcKey == null) {
            val djName = _dj.value?.name ?: ""
            scUrl = youTubeStreamResolver.searchFirstSoundCloudUrl("$djName ${ep.title}")
            android.util.Log.d("DjDetailVM", "DDG SC '${ep.title}': $scUrl")
        }

        // 3. YouTube as last resort
        if (scUrl == null && ytId == null && mcKey == null) {
            val djName = _dj.value?.name ?: ""
            ytId = youTubeStreamResolver.searchFirstVideoId("$djName ${ep.title}")
            android.util.Log.d("DjDetailVM", "YT fallback '${ep.title}': $ytId")
        }

        if (scUrl != null || ytId != null || mcKey != null) {
            episodeDao.update(ep.copy(
                soundcloudTrackUrl = scUrl ?: ep.soundcloudTrackUrl,
                youtubeVideoId = ytId ?: ep.youtubeVideoId,
                mixcloudKey = mcKey ?: ep.mixcloudKey
            ))
            android.util.Log.i("DjDetailVM", "Resolved '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
        } else {
            android.util.Log.w("DjDetailVM", "No audio source found for '${ep.title}'")
        }
    }

    fun deleteEpisode(episodeId: Int) {
        viewModelScope.launch {
            episodeDao.delete(episodeId)
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            podcastDao.delete(djId)
            onDone()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Rafraîchir uniquement la photo du DJ, pas les épisodes
                djRepository.refreshDjPhoto(djId)
                _dj.value = djRepository.getDjById(djId)
            } catch (_: Exception) { }
            _isRefreshing.value = false
        }
    }
}
