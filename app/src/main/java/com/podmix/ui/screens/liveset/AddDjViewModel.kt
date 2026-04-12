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
import com.podmix.service.SetMatcher
import com.podmix.service.SoundCloudArtistScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class SortMode { MOST_VIEWED, MOST_RECENT }

/** Set retourné par SoundCloudArtistScraper */
data class ScSet(
    val title: String,
    val url: String,   // "https://soundcloud.com/artist/track-slug"
    val date: String   // peut être vide
)

/** Set fusionné SC + 1001TL — source de vérité dans le ViewModel */
data class DiscoveredSet(
    val title: String,
    val date: String,
    val viewCount: Int,
    val soundcloudUrl: String?,   // URL canonique SC depuis page artiste SC
    val tracklistUrl: String?,    // URL page tracklist 1001TL
    val youtubeVideoId: String?   // depuis 1001TL page artiste
) {
    /** Identifiant stable unique pour Compose key et toggleSelection */
    val id: String get() = soundcloudUrl ?: tracklistUrl ?: title
}

data class DiscoveredSetUiItem(
    val set: DiscoveredSet,
    val isAlreadyImported: Boolean,
    val isSelected: Boolean = false
) {
    val sourceLabel: String get() = when {
        set.soundcloudUrl != null && set.tracklistUrl != null -> "SC+TL"
        set.soundcloudUrl != null -> "SC"
        else -> "1001TL"
    }

    /** Vert = audio + tracklist, Orange = audio sans tracklist, Rouge = pas d'audio */
    val hasAudio: Boolean get() = set.soundcloudUrl != null || set.youtubeVideoId != null
    val hasTracklist: Boolean get() = set.tracklistUrl != null
}

@HiltViewModel
class AddDjViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistPageScraper: ArtistPageScraper,
    private val soundCloudArtistScraper: SoundCloudArtistScraper,
    private val episodeDao: EpisodeDao,
    private val djRepository: DjRepository,
    private val trackRepository: TrackRepository,
    private val youTubeStreamResolver: com.podmix.service.YouTubeStreamResolver,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val TAG = "AddDjVM"

    private val existingDjId: Int? = savedStateHandle.get<Int>("djId")?.takeIf { it > 0 }
    private var currentDjId: Int? = existingDjId

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sets = MutableStateFlow<List<DiscoveredSetUiItem>>(emptyList())
    val sets: StateFlow<List<DiscoveredSetUiItem>> = _sets.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.MOST_VIEWED)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    val selectedCount: StateFlow<Int> = _sets
        .map { list -> list.count { it.isSelected } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var rawSets: List<DiscoveredSetUiItem> = emptyList()
    private var searchJob: Job? = null
    private var importJob: Job? = null

    private var alreadyImportedVideoIds: Set<String> = emptySet()
    private var alreadyImportedScUrls: Set<String> = emptySet()
    private var alreadyImportedTitles: Set<String> = emptySet()

    init {
        if (existingDjId != null) {
            viewModelScope.launch {
                // 1. Charger les épisodes déjà importés
                val episodes = episodeDao.getByPodcastIdSuspend(existingDjId)
                alreadyImportedVideoIds = episodes.mapNotNull { it.youtubeVideoId }.toSet()
                alreadyImportedScUrls = episodes.mapNotNull { it.soundcloudTrackUrl }.toSet()
                alreadyImportedTitles = episodes.map { it.title }.toSet()
                // 2. Auto-search — même coroutine, séquentiel, pas de conflit
                val djName = djRepository.getDjById(existingDjId)?.name
                if (!djName.isNullOrBlank()) {
                    _query.value = djName
                    search(djName)
                }
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
            // Lancer 1001TL et SoundCloud en parallèle — supervisorScope pour que l'échec d'un n'annule pas l'autre
            val (tlSets, scSets) = supervisorScope {
                val tlDeferred = async {
                    try {
                        val artistPageUrl = findArtistPage(q) ?: return@async null
                        Log.i(TAG, "1001TL artist page: $artistPageUrl")
                        artistPageScraper.scrapeArtistSets(artistPageUrl)
                    } catch (e: Exception) {
                        Log.w(TAG, "1001TL scrape failed: ${e.message}")
                        null
                    }
                }
                val scDeferred = async {
                    try {
                        soundCloudArtistScraper.findAndScrape(q)
                    } catch (e: Exception) {
                        Log.w(TAG, "SC scrape failed: ${e.message}")
                        null
                    }
                }
                Pair(tlDeferred.await(), scDeferred.await())
            }

            if (tlSets.isNullOrEmpty() && scSets.isNullOrEmpty()) {
                _errorMessage.value = "Artiste non trouvé sur 1001Tracklists ni SoundCloud"
                _isLoading.value = false
                return
            }

            Log.i(TAG, "1001TL: ${tlSets?.size ?: 0} sets, SC: ${scSets?.size ?: 0} sets")

            val merged = SetMatcher.merge(tlSets ?: emptyList(), scSets ?: emptyList())
            Log.i(TAG, "Merged: ${merged.size} sets")

            rawSets = merged.map { set ->
                DiscoveredSetUiItem(
                    set = set,
                    isAlreadyImported = (set.youtubeVideoId != null && set.youtubeVideoId in alreadyImportedVideoIds)
                        || (set.soundcloudUrl != null && set.soundcloudUrl in alreadyImportedScUrls)
                        || set.title in alreadyImportedTitles
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

    fun toggleSelectAll() {
        val allSelected = rawSets.filter { !it.isAlreadyImported }.all { it.isSelected }
        rawSets = rawSets.map { item ->
            if (!item.isAlreadyImported) item.copy(isSelected = !allSelected) else item
        }
        applySortAndEmit()
    }

    fun toggleSelection(setId: String) {
        Log.i(TAG, "toggleSelection: $setId")
        rawSets = rawSets.map { item ->
            if (item.set.id == setId && !item.isAlreadyImported)
                item.copy(isSelected = !item.isSelected)
            else item
        }
        applySortAndEmit()
    }

    fun importSelected(onComplete: (djId: Int) -> Unit) {
        if (importJob?.isActive == true) return
        val selected = rawSets.filter { it.isSelected }
        if (selected.isEmpty()) return

        importJob = viewModelScope.launch {
            episodeDao.deleteDuplicatesByTitle()

            if (currentDjId == null) {
                currentDjId = djRepository.addDj(_query.value.trim())
            }
            val djId = currentDjId!!

            selected.forEach { item ->
                try {
                    importSet(djId, item.set)
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed for ${item.set.title}: ${e.message}")
                }
            }

            onComplete(djId)
        }
    }

    private suspend fun importSet(djId: Int, set: DiscoveredSet) {
        // Dedup by title
        if (episodeDao.getByTitleAndPodcastId(set.title, djId) != null) {
            Log.i(TAG, "Skipping duplicate (title): ${set.title}")
            return
        }
        // Dedup by YouTube video ID
        if (set.youtubeVideoId != null && episodeDao.getByYoutubeVideoId(set.youtubeVideoId, djId) != null) {
            Log.i(TAG, "Skipping duplicate (ytId): ${set.title}")
            return
        }

        val episode = EpisodeEntity(
            podcastId = djId,
            title = set.title,
            audioUrl = "",
            datePublished = parseDate(set.date),
            durationSeconds = 0,
            artworkUrl = null,
            episodeType = "liveset",
            youtubeVideoId = set.youtubeVideoId,
            soundcloudTrackUrl = set.soundcloudUrl,
            tracklistPageUrl = set.tracklistUrl
        )
        episodeDao.insert(episode)

        // Update local dedup tracking
        if (set.youtubeVideoId != null) alreadyImportedVideoIds = alreadyImportedVideoIds + set.youtubeVideoId
        if (set.soundcloudUrl != null) alreadyImportedScUrls = alreadyImportedScUrls + set.soundcloudUrl
        alreadyImportedTitles = alreadyImportedTitles + set.title
        rawSets = rawSets.map { item ->
            if (item.set.id == set.id) item.copy(isAlreadyImported = true, isSelected = false) else item
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
