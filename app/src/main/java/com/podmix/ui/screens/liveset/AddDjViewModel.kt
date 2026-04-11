package com.podmix.ui.screens.liveset

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.repository.DjRepository
import com.podmix.data.repository.TrackRepository
import com.podmix.service.ArtistPageScraper
import com.podmix.service.ArtistSet
import com.podmix.service.TracklistWebScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class SortMode { MOST_VIEWED, MOST_RECENT }

data class ArtistSetUiItem(
    val set: ArtistSet,
    val isAlreadyImported: Boolean,
    val isSelected: Boolean = false
)

data class ImportProgress(val current: Int, val total: Int, val currentTitle: String)

@HiltViewModel
class AddDjViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistPageScraper: ArtistPageScraper,
    private val tracklistWebScraper: TracklistWebScraper,
    private val episodeDao: EpisodeDao,
    private val djRepository: DjRepository,
    private val trackRepository: TrackRepository,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val TAG = "AddDjVM"

    // Optional existing DJ id (for "add more" flow from DjDetailScreen)
    private val existingDjId: Int? = savedStateHandle.get<Int>("djId")?.takeIf { it > 0 }
    private var currentDjId: Int? = existingDjId

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sets = MutableStateFlow<List<ArtistSetUiItem>>(emptyList())
    val sets: StateFlow<List<ArtistSetUiItem>> = _sets.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.MOST_VIEWED)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private var rawSets: List<ArtistSetUiItem> = emptyList()
    private var searchJob: Job? = null

    // Video IDs already imported for this DJ
    private var alreadyImportedVideoIds: Set<String> = emptySet()

    init {
        if (existingDjId != null) {
            viewModelScope.launch {
                val episodes = episodeDao.getByPodcastIdSuspend(existingDjId)
                alreadyImportedVideoIds = episodes.mapNotNull { it.youtubeVideoId }.toSet()
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(600)
            search(q.trim())
        }
    }

    private suspend fun search(q: String) {
        _isLoading.value = true
        _errorMessage.value = null
        _sets.value = emptyList()
        try {
            // Phase 1: Find artist page via DDG
            val artistPageUrl = findArtistPage(q)
            if (artistPageUrl == null) {
                _errorMessage.value = "Artiste non trouvé sur 1001Tracklists"
                _isLoading.value = false
                return
            }
            Log.i(TAG, "Artist page: $artistPageUrl")

            // Phase 2: Scrape set list
            val sets = artistPageScraper.scrapeArtistSets(artistPageUrl)
            if (sets.isNullOrEmpty()) {
                _errorMessage.value = "Aucun set trouvé sur la page artiste"
                _isLoading.value = false
                return
            }
            Log.i(TAG, "Found ${sets.size} sets")

            rawSets = sets.map { set ->
                ArtistSetUiItem(
                    set = set,
                    isAlreadyImported = set.youtubeVideoId != null && set.youtubeVideoId in alreadyImportedVideoIds
                )
            }
            applySortAndEmit()
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}", e)
            _errorMessage.value = "Erreur: ${e.message}"
        }
        _isLoading.value = false
    }

    private suspend fun findArtistPage(q: String): String? = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://html.duckduckgo.com/html/?q=" +
                URLEncoder.encode("site:1001tracklists.com/dj $q", "UTF-8")
            val client = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "text/html")
                .build()
            val html = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val match = Regex("""uddg=([^&"]+)""").find(html) ?: return@withContext null
            val url = URLDecoder.decode(match.groupValues[1], "UTF-8")
            if (url.contains("1001tracklists.com/dj/")) url else null
        } catch (e: Exception) {
            Log.w(TAG, "DDG search failed: ${e.message}")
            null
        }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        applySortAndEmit()
    }

    private fun applySortAndEmit() {
        _sets.value = when (_sortMode.value) {
            SortMode.MOST_VIEWED -> rawSets.sortedByDescending { it.set.viewCount }
            SortMode.MOST_RECENT -> rawSets.sortedByDescending { it.set.date }
        }
    }

    fun toggleSelection(tracklistUrl: String) {
        rawSets = rawSets.map { item ->
            if (item.set.tracklistUrl == tracklistUrl && !item.isAlreadyImported)
                item.copy(isSelected = !item.isSelected)
            else item
        }
        applySortAndEmit()
    }

    fun selectedCount(): Int = rawSets.count { it.isSelected }

    fun importSelected(onComplete: (djId: Int) -> Unit) {
        val selected = rawSets.filter { it.isSelected }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            // Create DJ if needed
            if (currentDjId == null) {
                currentDjId = djRepository.addDj(_query.value.trim())
            }
            val djId = currentDjId!!

            selected.forEachIndexed { index, item ->
                _importProgress.value = ImportProgress(index + 1, selected.size, item.set.title)
                try {
                    importSet(djId, item.set)
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed for ${item.set.title}: ${e.message}")
                }
            }

            _importProgress.value = null
            onComplete(djId)
        }
    }

    private suspend fun importSet(djId: Int, set: ArtistSet) {
        // Dedup by YouTube video ID
        val ytId = set.youtubeVideoId
        if (ytId != null) {
            val existing = episodeDao.getByYoutubeVideoId(ytId, djId)
            if (existing != null) return
        }

        // Scrape tracklist
        val tracks = withContext(Dispatchers.Main) {
            tracklistWebScraper.scrape1001TL(set.tracklistUrl)
        }

        // Parse date to epoch ms
        val dateMs = parseDate(set.date)

        // Create episode
        val episode = EpisodeEntity(
            podcastId = djId,
            title = set.title,
            audioUrl = "",
            datePublished = dateMs,
            durationSeconds = 0,
            artworkUrl = null,
            episodeType = "liveset",
            youtubeVideoId = ytId,
            description = null
        )
        val episodeId = episodeDao.insert(episode).toInt()

        // Save tracks
        if (!tracks.isNullOrEmpty()) {
            val hasTimestamps = tracks.any { it.startTimeSec > 0f }
            trackRepository.saveTracksForEpisode(episodeId, tracks, hasTimestamps)
        }

        // Mark as imported
        alreadyImportedVideoIds = alreadyImportedVideoIds + (ytId ?: "")
        rawSets = rawSets.map { item ->
            if (item.set.tracklistUrl == set.tracklistUrl)
                item.copy(isAlreadyImported = true, isSelected = false)
            else item
        }
        applySortAndEmit()
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fmt.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
    }
}
