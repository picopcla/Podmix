package com.podmix.ui.screens.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.repository.PodcastRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    private val playerController: PlayerController
) : ViewModel() {

    val episodeIdsWithTracks: StateFlow<Set<Int>> = trackDao.getEpisodeIdsWithTracks()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val podcastId: Int = savedStateHandle["podcastId"] ?: 0

    private val _podcast = MutableStateFlow<Podcast?>(null)
    val podcast: StateFlow<Podcast?> = _podcast

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val playerState = playerController.playerState

    val episodes: StateFlow<List<Episode>> = episodeDao.getByPodcastId(podcastId)
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
                    mixcloudKey = e.mixcloudKey
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _podcast.value = repository.getPodcastById(podcastId)
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            // CASCADE delete handles episodes + tracks automatically
            podcastDao.delete(podcastId)
            onDone()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshPodcast(podcastId)
                _podcast.value = repository.getPodcastById(podcastId)
            } catch (_: Exception) { }
            _isRefreshing.value = false
        }
    }
}
