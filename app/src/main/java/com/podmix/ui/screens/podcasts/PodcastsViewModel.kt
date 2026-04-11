package com.podmix.ui.screens.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.repository.DjRepository
import com.podmix.data.repository.PodcastRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    repository: PodcastRepository,
    djRepository: DjRepository,
    private val podcastDao: PodcastDao,
    private val playerController: PlayerController
) : ViewModel() {

    val podcasts: StateFlow<List<Podcast>> = repository.getPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val djs: StateFlow<List<Podcast>> = djRepository.getDjs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emissions: StateFlow<List<Podcast>> = repository.getEmissions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val radios: StateFlow<List<Podcast>> = repository.getRadios()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playRadio(radio: Podcast) {
        val episode = Episode(
            id = -radio.id,
            podcastId = radio.id,
            title = radio.name,
            audioUrl = radio.rssFeedUrl ?: "",
            datePublished = null,
            durationSeconds = 0,
            progressSeconds = 0,
            isListened = false,
            artworkUrl = radio.logoUrl,
            episodeType = "radio",
            youtubeVideoId = null,
            description = null,
            mixcloudKey = null
        )
        playerController.playEpisode(episode, radio)
    }

    fun deleteRadio(radioId: Int) {
        viewModelScope.launch {
            podcastDao.delete(radioId)
        }
    }
}
