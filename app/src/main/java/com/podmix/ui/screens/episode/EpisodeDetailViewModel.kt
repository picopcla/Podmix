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
import com.podmix.service.ChromaTimestampRefiner
import com.podmix.service.DownloadState
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.OnDeviceAudioSplitService
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
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
    private val downloadManager: EpisodeDownloadManager,
    private val onDeviceAudioSplitService: OnDeviceAudioSplitService,
    private val chromaTimestampRefiner: ChromaTimestampRefiner
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

    /** true quand l'analyse audio on-device est en cours (wig wag orange) */
    private val _isAudioAnalyzing = MutableStateFlow(false)
    val isAudioAnalyzing: StateFlow<Boolean> = _isAudioAnalyzing.asStateFlow()

    /** Message de progression de l'analyse on-device */
    private val _audioAnalysisStatus = MutableStateFlow("")
    val audioAnalysisStatus: StateFlow<String> = _audioAnalysisStatus.asStateFlow()

    val tracks: StateFlow<List<Track>> = trackRepository.getTracksForEpisode(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Progression chroma refinement (0-100) ou null si pas en cours. */
    val chromaRefinementPct: StateFlow<Int?> = chromaTimestampRefiner.progress
        .map { it[episodeId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playerState = playerController.playerState

    val downloadState: StateFlow<DownloadState> = downloadManager.states
        .map { it[episodeId] ?: DownloadState.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadState.Idle)

    init {
        viewModelScope.launch(Dispatchers.IO) {
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
                    soundcloudTrackUrl = e.soundcloudTrackUrl,
                    trackRefinementStatus = e.trackRefinementStatus,
                    isFavorite = e.isFavorite,
                    tracklistSourceName = e.tracklistSourceName
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
                    withContext(Dispatchers.IO) { detectTracks() }
                }
            }
        }
    }

    private suspend fun detectTracks() {
        val ep = _episode.value ?: return

        val onStatus: (String) -> Unit = { _detectStatus.value = it }

        val isLiveSet = ep.episodeType == "liveset"

        // Sources différentes selon type d'épisode — évite les SKIPPED immédiats parasites
        _sourceResults.value = if (isLiveSet) {
            listOf(
                SourceResult("1001Tracklists", SourceStatus.PENDING),
                SourceResult("Description YouTube", SourceStatus.PENDING),
                SourceResult("Commentaires YouTube", SourceStatus.PENDING),
                SourceResult("Mixcloud", SourceStatus.PENDING),
                SourceResult("MixesDB", SourceStatus.PENDING),
                SourceResult("yt-dlp", SourceStatus.PENDING),
                SourceResult("Shazam/IA", SourceStatus.PENDING),
                SourceResult("Analyse Audio", SourceStatus.PENDING),
            )
        } else {
            // Podcasts : pipeline séquentiel 1001TL → Description RSS → Shazam seulement
            listOf(
                SourceResult("1001Tracklists", SourceStatus.PENDING),
                SourceResult("Description YouTube", SourceStatus.PENDING),
                SourceResult("Shazam/IA", SourceStatus.PENDING),
            )
        }

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

            val found = trackRepository.detectAndSaveTracks(
                episodeId = ep.id,
                description = description,
                episodeTitle = ep.title,
                podcastName = _podcast.value?.name,
                episodeDurationSec = ep.durationSeconds,
                onStatus = onStatus,
                onSourceResult = onSourceResult,
                isLiveSet = ep.episodeType == "liveset",
                youtubeVideoId = ep.youtubeVideoId,
                audioUrl = ep.audioUrl.takeIf { it.isNotBlank() },
                mixcloudKey = ep.mixcloudKey
            )

            // ── Analyse on-device si aucune source n'a trouvé de tracklist ──
            if (!found) {
                runOnDeviceAudioSplit(ep, onSourceResult)
            } else {
                // Marquer la source "Analyse Audio" comme skippée (pas nécessaire)
                onSourceResult(SourceResult("Analyse Audio", SourceStatus.SKIPPED,
                    reason = "tracklist trouvée par autre source"))
            }
        } finally {
            _detectStatus.value = ""
            _isAudioAnalyzing.value = false
            _audioAnalysisStatus.value = ""
            // Recharger l'épisode pour récupérer tracklistSourceName mis à jour par la détection
            episodeDao.getById(episodeId)?.let { e ->
                _episode.value = _episode.value?.copy(tracklistSourceName = e.tracklistSourceName)
            }
        }
    }

    /**
     * Analyse audio on-device : résout l'URL audio puis lance [OnDeviceAudioSplitService].
     * Affiche le wig wag orange durant l'analyse.
     */
    private suspend fun runOnDeviceAudioSplit(
        ep: com.podmix.domain.model.Episode,
        onSourceResult: (SourceResult) -> Unit
    ) {
        // Résoudre l'URL audio
        val audioUrl: String? = when {
            !ep.localAudioPath.isNullOrBlank() -> "file://${ep.localAudioPath}"
            ep.audioUrl.isNotBlank() -> ep.audioUrl
            !ep.youtubeVideoId.isNullOrBlank() -> {
                _detectStatus.value = "🔗 Résolution URL YouTube..."
                try {
                    youTubeStreamResolver.resolve(ep.youtubeVideoId)
                } catch (e: Exception) {
                    android.util.Log.w("EpisodeVM", "resolveAudioUrl failed: ${e.message}")
                    null
                }
            }
            else -> null
        }

        if (audioUrl.isNullOrBlank()) {
            onSourceResult(SourceResult("Analyse Audio", SourceStatus.SKIPPED,
                reason = "aucune source audio résolvable"))
            return
        }

        val tStart = System.currentTimeMillis()
        onSourceResult(SourceResult("Analyse Audio", SourceStatus.RUNNING))
        _isAudioAnalyzing.value = true

        try {
            val tracks = onDeviceAudioSplitService.analyze(
                audioUrl = audioUrl,
                durationSec = ep.durationSeconds,
                onProgress = { pct, msg ->
                    _detectStatus.value = "🎵 On-device $pct% — $msg"
                    _audioAnalysisStatus.value = msg
                }
            )

            val elapsed = System.currentTimeMillis() - tStart
            if (tracks.size >= 2) {
                trackRepository.saveTracksForEpisode(ep.id, tracks, hasTimestamps = true)
                onSourceResult(SourceResult("Analyse Audio", SourceStatus.SUCCESS,
                    trackCount = tracks.size, elapsedMs = elapsed))
            } else {
                onSourceResult(SourceResult("Analyse Audio", SourceStatus.FAILED,
                    elapsedMs = elapsed, reason = "transitions insuffisantes (${tracks.size})"))
            }
        } catch (e: Exception) {
            android.util.Log.e("EpisodeVM", "On-device analysis error: ${e.message}", e)
            onSourceResult(SourceResult("Analyse Audio", SourceStatus.FAILED,
                reason = e.message ?: "erreur inconnue"))
        } finally {
            _isAudioAnalyzing.value = false
            _audioAnalysisStatus.value = ""
        }
    }

    fun play() {
        val ep = _episode.value
        val pod = _podcast.value
        android.util.Log.i("EpisodeVM", "play() ep=${ep?.title}, pod=${pod?.name}, videoId=${ep?.youtubeVideoId}")
        if (ep == null || pod == null) return

        // Toggle play/pause si c'est déjà l'épisode actif
        if (playerController.playerState.value.currentEpisode?.id == ep.id) {
            when {
                playerController.playerState.value.isPlaying -> playerController.pause()
                playerController.isPlayerReady -> playerController.resume()
                else -> {
                    // ExoPlayer IDLE (ex: retour background, service tué) → relancer
                    playerController.playEpisode(ep, pod)
                    playerController.updateTracks(tracks.value)
                }
            }
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
        val isLoaded = pState.currentEpisode?.id == ep.id && playerController.isPlayerReady

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

    /**
     * Force la détection avec une URL 1001TL connue (fournie manuellement par l'user).
     * Purge les tracks existants, supprime le statut "no_tracklist" si présent, puis lance.
     */
    fun setTracklistUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            trackRepository.deleteTracksForEpisode(episodeId)
            // Lever le blocage "no_tracklist" pour permettre la détection
            episodeDao.updateRefinementStatus(episodeId, "none")
            val ep = episodeDao.getById(episodeId) ?: return@launch
            val podcast = _podcast.value
            _detectStatus.value = "🔍 Scraping $url..."
            trackRepository.detectAndSaveTracks(
                episodeId = episodeId,
                description = ep.description,
                episodeTitle = ep.title,
                podcastName = podcast?.name,
                episodeDurationSec = ep.durationSeconds,
                onStatus = { _detectStatus.value = it },
                isLiveSet = ep.episodeType == "liveset",
                youtubeVideoId = ep.youtubeVideoId,
                audioUrl = ep.audioUrl.takeIf { it.isNotBlank() },
                tracklistPageUrl = url.trim()
            )
            _detectStatus.value = ""
        }
    }

    fun redetectTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            trackRepository.deleteTracksForEpisode(episodeId)
            detectTracks()
            // Si liveset déjà téléchargé et pipeline n'a trouvé que des timestamps estimés
            // → déclencher le chroma refinement immédiatement (pas besoin de re-télécharger)
            // Si épisode déjà téléchargé et chroma_pending → lancer refinement
            // (fire-and-forget dans son propre scope — survit à la navigation)
            val ep = episodeDao.getById(episodeId)
            if (ep?.trackRefinementStatus == "chroma_pending"
                && !ep.localAudioPath.isNullOrBlank()
            ) {
                chromaTimestampRefiner.refine(episodeId)  // non-suspend, fire-and-forget
            }
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
