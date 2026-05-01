package com.podmix.ui.screens.liveset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.prefs.AppPreferences
import com.podmix.data.repository.DjRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.AudioTransitionDetector
import com.podmix.service.ChromaTimestampRefiner
import com.podmix.service.DownloadState
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
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
    playerController: PlayerController,
    downloadManager: EpisodeDownloadManager,
    audioTransitionDetector: AudioTransitionDetector,
    chromaTimestampRefiner: ChromaTimestampRefiner,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val playerState = playerController.playerState
    val downloadStates: StateFlow<Map<Int, DownloadState>> = downloadManager.states
    val refinementProgress: StateFlow<Map<Int, Int>> = combine(
        audioTransitionDetector.progress,
        chromaTimestampRefiner.progress
    ) { rms, chroma -> rms + chroma }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val episodeIdsWithTracks: StateFlow<Set<Int>> = trackDao.getEpisodeIdsWithTracks()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val djId: Int = savedStateHandle["djId"] ?: 0

    private val _dj = MutableStateFlow<Podcast?>(null)
    val dj: StateFlow<Podcast?> = _dj

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Reactive display limit: updates when liveset setting changes
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allSets = appPreferences.maxLivesetEpisodes.flatMapLatest { limit ->
        episodeDao.getByPodcastIdLimited(djId, limit.coerceAtLeast(5))
    }.map { list ->
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
                    soundcloudTrackUrl = e.soundcloudTrackUrl,
                    trackRefinementStatus = e.trackRefinementStatus,
                    isFavorite = e.isFavorite
                )
            }
        }

    val sets: StateFlow<List<Episode>> = combine(allSets, _searchQuery) { list, query ->
        if (query.isBlank()) list
        else {
            val tokens = query.trim().lowercase().split(Regex("\\s+"))
            list.filter { ep ->
                tokens.all { token ->
                    ep.title.contains(token, ignoreCase = true) ||
                    ep.description?.contains(token, ignoreCase = true) == true
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun search(query: String) { _searchQuery.value = query }

    init {
        viewModelScope.launch {
            _dj.value = djRepository.getDjById(djId)
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
}
