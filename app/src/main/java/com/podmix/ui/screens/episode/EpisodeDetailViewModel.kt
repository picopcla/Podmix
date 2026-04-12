package com.podmix.ui.screens.episode

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.repository.DjRepository
import com.podmix.data.repository.PodcastRepository
import com.podmix.data.repository.TrackRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import com.podmix.domain.model.Track
import com.podmix.service.DownloadState
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltViewModel
class EpisodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val episodeDao: EpisodeDao,
    private val repository: PodcastRepository,
    private val djRepository: DjRepository,
    private val trackRepository: TrackRepository,
    private val playerController: PlayerController,
    private val youTubeStreamResolver: com.podmix.service.YouTubeStreamResolver,
    private val downloadManager: EpisodeDownloadManager
) : ViewModel() {

    private val podcastId: Int = savedStateHandle["podcastId"] ?: 0
    private val episodeId: Int = savedStateHandle["episodeId"] ?: 0

    private val _episode = MutableStateFlow<Episode?>(null)
    val episode: StateFlow<Episode?> = _episode

    private val _podcast = MutableStateFlow<Podcast?>(null)
    val podcast: StateFlow<Podcast?> = _podcast

    private val _detectStatus = MutableStateFlow("")
    val detectStatus: StateFlow<String> = _detectStatus

    private val _sourceResults = MutableStateFlow<List<SourceResult>>(emptyList())
    val sourceResults: StateFlow<List<SourceResult>> = _sourceResults.asStateFlow()

    val tracks: StateFlow<List<Track>> = trackRepository.getTracksForEpisode(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playerState = playerController.playerState

    val downloadState: StateFlow<DownloadState> = downloadManager.states
        .map { it[episodeId] ?: DownloadState.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadState.Idle)

    init {
        viewModelScope.launch {
            val e = episodeDao.getById(episodeId)
            if (e != null) {
                val episode = Episode(
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
                _episode.value = episode
                downloadManager.initState(episode)
            }
            _podcast.value = repository.getPodcastById(podcastId)
                ?: djRepository.getDjById(podcastId)

            // Auto-detect tracks only for podcasts and livesets, NOT emissions
            val podType = _podcast.value?.type
            if (podType != "emission" && podType != "radio") {
                val existingTracks = trackRepository.getTracksCountForEpisode(episodeId)
                // Ne pas relancer si déjà enrichi (enrichedAt != null) OU si des tracks existent déjà
                if (existingTracks == 0 && e?.enrichedAt == null) {
                    detectTracks()
                }
            }
        }
    }

    private suspend fun detectTracks() {
        val ep = _episode.value ?: return

        val onStatus: (String) -> Unit = { _detectStatus.value = it }

        // Initialiser toutes les sources en PENDING (ordre du pipeline)
        _sourceResults.value = listOf(
            SourceResult("1001Tracklists", SourceStatus.PENDING),
            SourceResult("Description YouTube", SourceStatus.PENDING),
            SourceResult("Commentaires YouTube", SourceStatus.PENDING),
            SourceResult("Mixcloud", SourceStatus.PENDING),
            SourceResult("MixesDB", SourceStatus.PENDING),
            SourceResult("yt-dlp", SourceStatus.PENDING),
            SourceResult("Shazam/IA", SourceStatus.PENDING),
        )

        val onSourceResult: (SourceResult) -> Unit = { result ->
            _sourceResults.update { list ->
                list.map { if (it.source == result.source) result else it }
            }
        }

        try {
            // For YouTube episodes, fetch description via NewPipe
            val description = if (!ep.youtubeVideoId.isNullOrBlank() && ep.description.isNullOrBlank()) {
                onStatus("Récupération description YouTube...")
                try {
                    youTubeStreamResolver.getDescription(ep.youtubeVideoId) ?: ep.description
                } catch (_: Exception) {
                    ep.description
                }
            } else {
                ep.description
            }

            trackRepository.detectAndSaveTracks(
                episodeId = ep.id,
                description = description,
                episodeTitle = ep.title,
                podcastName = _podcast.value?.name,
                episodeDurationSec = ep.durationSeconds,
                onStatus = onStatus,
                onSourceResult = onSourceResult,
                isLiveSet = ep.episodeType == "liveset",
                youtubeVideoId = ep.youtubeVideoId,
                audioUrl = ep.audioUrl.takeIf { it.isNotBlank() }
            )
        } finally {
            _detectStatus.value = ""
        }
    }

    fun play() {
        val ep = _episode.value
        val pod = _podcast.value
        android.util.Log.i("EpisodeVM", "play() ep=${ep?.title}, pod=${pod?.name}, videoId=${ep?.youtubeVideoId}")
        if (ep == null || pod == null) return

        // Toggle play/pause si c'est déjà l'épisode actif
        if (playerController.playerState.value.currentEpisode?.id == ep.id) {
            if (playerController.playerState.value.isPlaying) playerController.pause()
            else playerController.resume()
            return
        }

        // Pas de source audio → pas de lecture possible
        val hasSource = !ep.youtubeVideoId.isNullOrBlank()
            || ep.audioUrl.isNotBlank()
            || ep.mixcloudKey != null
            || !ep.soundcloudTrackUrl.isNullOrBlank()
        if (!hasSource) {
            _detectStatus.value = "❌ Aucune source audio"
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000)
                _detectStatus.value = ""
            }
            return
        }

        playerController.playEpisode(ep, pod)
        playerController.updateTracks(tracks.value)
    }

    fun seekToTrack(track: Track) {
        val ep = _episode.value ?: return
        val pod = _podcast.value ?: return
        val rawMs = (track.startTimeSec * 1000).toLong()
        val pState = playerController.playerState.value
        val isLoaded = pState.currentEpisode?.id == ep.id && pState.duration > 0L

        android.util.Log.i("EpisodeVM", "seekToTrack() track=${track.title} isLoaded=$isLoaded ytId=${ep.youtubeVideoId}")

        if (isLoaded) {
            playerController.seekToTrack(track, ep.episodeType)
            return
        }

        // Pas encore chargé — même logique que play() mais avec seekToMs
        val hasSource = !ep.youtubeVideoId.isNullOrBlank()
            || ep.audioUrl.isNotBlank()
            || ep.mixcloudKey != null
            || !ep.soundcloudTrackUrl.isNullOrBlank()

        if (!hasSource) {
            _detectStatus.value = "❌ Aucune source audio"
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000)
                _detectStatus.value = ""
            }
        } else {
            // Highlight the track immediately (optimistic), before YouTube resolves
            val trackIdx = tracks.value.indexOfFirst { it.id == track.id }
            playerController.updateTracks(tracks.value)
            if (trackIdx >= 0) playerController.setCurrentTrackIndex(trackIdx)
            playerController.playEpisode(ep, pod, seekToMs = rawMs)
        }
    }

    fun redetectTracks() {
        viewModelScope.launch {
            trackRepository.deleteTracksForEpisode(episodeId)
            detectTracks()
        }
    }

    fun toggleFavorite(trackId: Int) {
        viewModelScope.launch {
            trackRepository.toggleFavorite(trackId)
        }
    }

    fun startDownload() {
        val ep = _episode.value ?: return
        downloadManager.startDownload(ep)
    }

    fun cancelDownload() {
        downloadManager.cancelDownload(episodeId)
    }

    fun deleteDownload() {
        downloadManager.deleteDownload(episodeId)
    }
}
