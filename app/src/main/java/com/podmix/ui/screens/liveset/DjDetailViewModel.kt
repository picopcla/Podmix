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
class DjDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val djRepository: DjRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    playerController: PlayerController
) : ViewModel() {

    val playerState = playerController.playerState

    val episodeIdsWithTracks: StateFlow<Set<Int>> = trackDao.getEpisodeIdsWithTracks()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val djId: Int = savedStateHandle["djId"] ?: 0

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
                    mixcloudKey = e.mixcloudKey
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _dj.value = djRepository.getDjById(djId)
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
                djRepository.refreshDj(djId)
                _dj.value = djRepository.getDjById(djId)
            } catch (_: Exception) { }
            _isRefreshing.value = false
        }
    }
}
