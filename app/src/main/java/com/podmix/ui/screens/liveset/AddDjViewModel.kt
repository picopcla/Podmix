package com.podmix.ui.screens.liveset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.api.YouTubeSearchApi
import com.podmix.data.api.YouTubeSearchResult
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.local.entity.PodcastEntity
import com.podmix.data.repository.DjRepository
import com.podmix.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddDjViewModel @Inject constructor(
    private val youTubeSearchApi: YouTubeSearchApi,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val djRepository: DjRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    val results: StateFlow<List<YouTubeSearchResult>> = _results

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _addedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val addedVideoIds: StateFlow<Set<String>> = _addedVideoIds

    // Track the DJ id created for this session
    private var currentDjId: Int? = null
    private var searchJob: Job? = null

    // Load already-imported videoIds
    init {
        viewModelScope.launch {
            val allEpisodes = episodeDao.getByPodcastIdSuspend(0) // Will be updated per DJ
            // We'll filter per-DJ when we know the DJ
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            _isSearching.value = true
            try {
                val ytResults = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    youTubeSearchApi.search(q.trim(), 30)
                }
                // Filter: > 20 min, not already added
                val added = _addedVideoIds.value
                _results.value = ytResults
                    .filter { it.durationSeconds > 1200 && it.videoId !in added }
            } catch (_: Exception) {
                _results.value = emptyList()
            }
            _isSearching.value = false
        }
    }

    fun addSet(result: YouTubeSearchResult, onDjCreated: (Int) -> Unit) {
        viewModelScope.launch {
            // Create DJ if not yet created
            if (currentDjId == null) {
                // Extract DJ name from channel or query
                val djName = result.channelName.takeIf { it.isNotBlank() } ?: _query.value.trim()
                currentDjId = djRepository.addDj(djName)
                onDjCreated(currentDjId!!)
            }

            val djId = currentDjId!!

            // Check dedup
            val existing = episodeDao.getByYoutubeVideoId(result.videoId, djId)
            if (existing != null) return@launch

            // Insert episode
            val episode = EpisodeEntity(
                podcastId = djId,
                title = result.title,
                audioUrl = "",
                datePublished = djRepository.parseDate(result.publishedText),
                durationSeconds = result.durationSeconds,
                artworkUrl = result.thumbnail,
                episodeType = "liveset",
                youtubeVideoId = result.videoId,
                description = null
            )
            val episodeId = episodeDao.insert(episode).toInt()

            // Mark as added
            _addedVideoIds.value = _addedVideoIds.value + result.videoId
            // Remove from results
            _results.value = _results.value.filter { it.videoId != result.videoId }

            // Detect tracklist in background
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    trackRepository.detectAndSaveTracks(
                        episodeId = episodeId,
                        description = null,
                        episodeTitle = result.title,
                        podcastName = null,
                        episodeDurationSec = result.durationSeconds,
                        isLiveSet = true,
                        youtubeVideoId = result.videoId
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun getDjId(): Int? = currentDjId

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h${m.toString().padStart(2, '0')}" else "${m}min"
    }
}
