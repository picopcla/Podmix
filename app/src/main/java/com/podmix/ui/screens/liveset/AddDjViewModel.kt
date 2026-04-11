package com.podmix.ui.screens.liveset

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.repository.DjRepository
import com.podmix.data.repository.TrackRepository
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import com.podmix.service.ArtistPageScraper
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.SetMatcher
import com.podmix.service.SoundCloudArtistScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
}

data class ImportProgress(val current: Int, val total: Int, val currentTitle: String)

@HiltViewModel
class AddDjViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistPageScraper: ArtistPageScraper,
    private val soundCloudArtistScraper: SoundCloudArtistScraper,
    private val episodeDao: EpisodeDao,
    private val djRepository: DjRepository,
    private val trackRepository: TrackRepository,
    private val youTubeStreamResolver: com.podmix.service.YouTubeStreamResolver,
    private val downloadManager: EpisodeDownloadManager,
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

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _sourceResults = MutableStateFlow<List<SourceResult>>(emptyList())
    val sourceResults: StateFlow<List<SourceResult>> = _sourceResults.asStateFlow()

    val selectedCount: StateFlow<Int> = _sets
        .map { list -> list.count { it.isSelected } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var rawSets: List<DiscoveredSetUiItem> = emptyList()
    private var searchJob: Job? = null
    private var importJob: Job? = null

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
            // Lancer 1001TL et SoundCloud en parallèle
            val (tlSets, scSets) = coroutineScope {
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
        Log.i(TAG, "importSelected: ${rawSets.count { it.isSelected }} sets")
        val selected = rawSets.filter { it.isSelected }
        if (selected.isEmpty()) return

        importJob = viewModelScope.launch {
            episodeDao.deleteDuplicatesByTitle()

            if (currentDjId == null) {
                currentDjId = djRepository.addDj(_query.value.trim())
            }
            val djId = currentDjId!!

            selected.forEachIndexed { index, item ->
                _importProgress.value = ImportProgress(index + 1, selected.size, item.set.title)
                _sourceResults.value = emptyList()
                try {
                    importSet(djId, item.set)
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed for ${item.set.title}: ${e.message}")
                }
            }

            // Queue background downloads — SC (HLS) ne nécessite pas de download
            selected.forEach { item ->
                val ep = episodeDao.getByTitleAndPodcastId(item.set.title, djId) ?: return@forEach
                if (ep.soundcloudTrackUrl != null) return@forEach
                if (ep.localAudioPath != null && java.io.File(ep.localAudioPath).exists()) return@forEach
                downloadManager.startDownload(com.podmix.domain.model.Episode(
                    id = ep.id, podcastId = ep.podcastId, title = ep.title,
                    audioUrl = ep.audioUrl ?: "", datePublished = ep.datePublished,
                    durationSeconds = ep.durationSeconds, progressSeconds = ep.progressSeconds,
                    isListened = ep.isListened, artworkUrl = ep.artworkUrl,
                    episodeType = ep.episodeType, youtubeVideoId = ep.youtubeVideoId,
                    description = ep.description, mixcloudKey = ep.mixcloudKey,
                    localAudioPath = ep.localAudioPath, soundcloudTrackUrl = ep.soundcloudTrackUrl
                ))
            }

            _importProgress.value = null
            onComplete(djId)
        }
    }

    private suspend fun importSet(djId: Int, set: DiscoveredSet) {
        // Résolution audio — priorité : SC (page artiste SC) → YT (1001TL) → probe tracklist → DDG SC → skip
        var ytId = set.youtubeVideoId
        var scTrackUrl = set.soundcloudUrl  // URL canonique SC depuis SoundCloudArtistScraper
        var mcKey: String? = null

        // Si pas de source directe mais tracklistUrl connu → probe la page 1001TL
        if (ytId == null && scTrackUrl == null && set.tracklistUrl != null) {
            Log.i(TAG, "Probing tracklist page for '${set.title}'...")
            val src = artistPageScraper.getMediaSourceFromTracklistPage(set.tracklistUrl)
            Log.i(TAG, "Tracklist probe: YT=${src?.youtubeVideoId} SC=${src?.soundcloudTrackUrl} MC=${src?.mixcloudKey}")
            ytId = src?.youtubeVideoId
            scTrackUrl = src?.soundcloudTrackUrl
            mcKey = src?.mixcloudKey
        }

        // Fallback DDG SoundCloud si toujours rien
        if (ytId == null && scTrackUrl == null && mcKey == null) {
            val scQuery = buildSoundCloudQuery(_query.value.trim(), set.title)
            Log.i(TAG, "DDG SC fallback: '$scQuery'")
            scTrackUrl = youTubeStreamResolver.searchFirstSoundCloudUrl(scQuery)
            Log.i(TAG, "DDG SC result: $scTrackUrl")
        }

        // Toujours rien → skip
        if (ytId == null && scTrackUrl == null && mcKey == null) {
            Log.i(TAG, "No media source for '${set.title}', skipping")
            return
        }

        // Dedup
        if (ytId != null && episodeDao.getByYoutubeVideoId(ytId, djId) != null) {
            Log.i(TAG, "Skipping duplicate (ytId): ${set.title}")
            return
        }
        if (episodeDao.getByTitleAndPodcastId(set.title, djId) != null) {
            Log.i(TAG, "Skipping duplicate (title): ${set.title}")
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
            youtubeVideoId = ytId,
            mixcloudKey = mcKey,
            soundcloudTrackUrl = scTrackUrl
        )
        val episodeId = episodeDao.insert(episode).toInt()

        trackRepository.detectAndSaveTracks(
            episodeId = episodeId,
            description = null,
            episodeTitle = set.title,
            podcastName = null,
            episodeDurationSec = 0,
            isLiveSet = true,
            youtubeVideoId = ytId,
            onSourceResult = { result ->
                _sourceResults.value = _sourceResults.value
                    .filter { it.source != result.source } + result
            }
        )

        alreadyImportedVideoIds = alreadyImportedVideoIds + (ytId ?: "")
        rawSets = rawSets.map { item ->
            if (item.set.id == set.id)
                item.copy(isAlreadyImported = true, isSelected = false)
            else item
        }
        applySortAndEmit()
    }

    private fun buildSoundCloudQuery(djName: String, title: String): String {
        val titleWithoutDj = title.replace(Regex("(?i)${Regex.escape(djName)}\\s*[@-]?\\s*"), "").trim()
        val cleaned = titleWithoutDj
            .replace(Regex("""[@,()|\-/]"""), " ")
            .replace(Regex("""\b(live|set|mix|dj|radio|show|episode|festival|presents?|records?)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        val keywords = cleaned.split(" ").filter { it.length > 2 }.take(3).joinToString(" ")
        return if (keywords.isNotBlank()) "$djName $keywords" else djName
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fmt.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
    }
}
