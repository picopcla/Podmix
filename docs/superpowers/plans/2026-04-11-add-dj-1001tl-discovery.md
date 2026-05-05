# 1001TL-Based DJ Set Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace YouTube search in AddDjScreen with 1001TL artist-page scraping so users can browse, select, and bulk-import sets with pre-fetched tracklists.

**Architecture:** A new `ArtistPageScraper` WebView service extracts the set list from `1001tracklists.com/dj/[slug]/`. `AddDjViewModel` is rewritten to drive a two-phase flow: DDG artist-page lookup → set list display with checkboxes → bulk import using existing `TracklistWebScraper`. `DjDetailScreen` gets a "+" FAB for adding more sets to an existing DJ.

**Tech Stack:** Kotlin coroutines, Android WebView, OkHttp (already in project), Hilt `@Singleton`, Jetpack Compose, existing `TracklistWebScraper` + `DjRepository` + `EpisodeDao`.

---

## File Map

| File | Action |
|------|--------|
| `service/ArtistPageScraper.kt` | CREATE |
| `ui/screens/liveset/AddDjViewModel.kt` | REWRITE |
| `ui/screens/liveset/AddDjScreen.kt` | REWRITE |
| `ui/screens/liveset/DjDetailScreen.kt` | MODIFY — add "+" FAB |
| `navigation/NavGraph.kt` | MODIFY — add `add_sets/{djId}` route |

---

## Task 1: Create ArtistPageScraper

**Files:**
- Create: `app/src/main/java/com/podmix/service/ArtistPageScraper.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.podmix.service

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class ArtistSet(
    val title: String,
    val date: String,           // "2021-02-26" or empty
    val tracklistUrl: String,   // full 1001TL tracklist URL
    val viewCount: Int,         // for "Most Viewed" sort; 0 if unavailable
    val youtubeVideoId: String? // extracted from page if present
)

@Singleton
class ArtistPageScraper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "ArtistPageScraper"

    // Extract set list from the 1001TL artist/DJ page.
    // Each row in the listing is a tracklist link with metadata.
    private val EXTRACT_JS = """
        (function tryExtract(attempt) {
            var rows = document.querySelectorAll('div.tlpItem, div.tl-item, li.tlpItem');
            if (rows.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }
            var sets = [];
            rows.forEach(function(row) {
                // title + tracklist URL
                var link = row.querySelector('a[href*="/tracklist/"]');
                if (!link) return;
                var href = link.href || '';
                var title = link.innerText.trim() || link.title || '';
                if (!title || !href) return;

                // date — try multiple selectors
                var dateEl = row.querySelector('span.date, .tlpDateTimeLabel, time');
                var date = dateEl ? dateEl.innerText.trim() : '';

                // view count
                var viewEl = row.querySelector('.tlpViewCnt, .viewCount, span[class*="view"]');
                var views = 0;
                if (viewEl) {
                    var v = viewEl.innerText.replace(/[^0-9]/g, '');
                    views = parseInt(v) || 0;
                }

                // YouTube ID from any link in the row
                var youtubeId = '';
                row.querySelectorAll('a[href*="youtube.com"]').forEach(function(a) {
                    var m = a.href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                    if (m && !youtubeId) youtubeId = m[1];
                });

                sets.push({ title: title, date: date, url: href, views: views, youtubeId: youtubeId });
            });
            Android.onSetsExtracted(JSON.stringify(sets));
        })(0);
    """.trimIndent()

    suspend fun scrapeArtistSets(artistPageUrl: String): List<ArtistSet>? {
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(artistPageUrl)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ArtistSet>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<ArtistSet>?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onSetsExtracted(json: String) {
                    Log.d(TAG, "JS callback, json length=${json.length}")
                    val sets = parseSetsJson(json)
                    Log.i(TAG, "Extracted ${sets?.size ?: 0} sets from artist page")
                    resume(sets)
                }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Page finished: $url — injecting JS")
                    view.evaluateJavascript(EXTRACT_JS, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "WebView error: ${error.description}")
                        resume(null)
                    }
                }
            }

            cont.invokeOnCancellation { resume(null) }
            Log.i(TAG, "Loading artist page: $url")
            webView.loadUrl(url)
        }

    private fun parseSetsJson(json: String): List<ArtistSet>? {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            val sets = mutableListOf<ArtistSet>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.optString("url", "")
                val title = obj.optString("title", "").trim()
                if (title.isBlank() || !url.contains("1001tracklists.com/tracklist/")) continue
                sets.add(ArtistSet(
                    title = title,
                    date = obj.optString("date", ""),
                    tracklistUrl = url,
                    viewCount = obj.optInt("views", 0),
                    youtubeVideoId = obj.optString("youtubeId", "").takeIf { it.isNotBlank() }
                ))
            }
            if (sets.size >= 1) sets else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/podmix/service/ArtistPageScraper.kt
git commit -m "feat: add ArtistPageScraper — WebView scraper for 1001TL artist pages"
```

---

## Task 2: Rewrite AddDjViewModel

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1: Replace entire file**

```kotlin
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

    // If existing DJ, pre-load its already-imported video IDs
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
            // 1. Find artist page via DDG
            val artistPageUrl = findArtistPage(q)
            if (artistPageUrl == null) {
                _errorMessage.value = "Artiste non trouvé sur 1001Tracklists"
                _isLoading.value = false
                return
            }
            Log.i(TAG, "Artist page: $artistPageUrl")

            // 2. Scrape set list
            val sets = artistPageScraper.scrapeArtistSets(artistPageUrl)
            if (sets.isNullOrEmpty()) {
                _errorMessage.value = "Aucun set trouvé sur la page artiste"
                _isLoading.value = false
                return
            }
            Log.i(TAG, "Found ${sets.size} sets")

            // 3. Mark already-imported
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
```

- [ ] **Step 2: Check for `saveTracksForEpisode` in TrackRepository**

```bash
grep -n "saveTracksForEpisode\|fun saveTracks" app/src/main/java/com/podmix/data/repository/TrackRepository.kt | head -10
```

If `saveTracksForEpisode` does not exist, add this public method to `TrackRepository.kt` right before the `detectAndSaveTracks` function:

```kotlin
suspend fun saveTracksForEpisode(episodeId: Int, tracks: List<com.podmix.service.ParsedTrack>, hasTimestamps: Boolean) {
    saveTracks(episodeId, tracks, hasTimestamps, 0, "1001tl")
}
```

- [ ] **Step 3: Update NetworkModule to inject ArtistPageScraper into AddDjViewModel**

`AddDjViewModel` uses `@Inject constructor` with `@HiltViewModel` — Hilt provides `ArtistPageScraper` automatically since it is a `@Singleton @Inject constructor` class. No `NetworkModule` change needed. But verify there's no manual provider for the old `AddDjViewModel` in `NetworkModule.kt`:

```bash
grep -n "AddDjViewModel\|YouTubeSearchApi" app/src/main/java/com/podmix/di/NetworkModule.kt
```

If `YouTubeSearchApi` was only used by `AddDjViewModel` and nothing else, you may leave it (unused imports do not break the build).

- [ ] **Step 4: Build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep "error:" | head -20
```

Expected: no errors. Fix any import issues (e.g., `OkHttpClient` may need `@Named` qualifier if there are multiple bindings — check existing NetworkModule for the binding name).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt
git add app/src/main/java/com/podmix/data/repository/TrackRepository.kt
git commit -m "feat: rewrite AddDjViewModel — 1001TL artist discovery + checkbox import"
```

---

## Task 3: Rewrite AddDjScreen

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt`

- [ ] **Step 1: Replace entire file**

```kotlin
package com.podmix.ui.screens.liveset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.podmix.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDjScreen(
    onBack: () -> Unit,
    onDjAdded: (Int) -> Unit,
    viewModel: AddDjViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val sets by viewModel.sets.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val selectedCount = viewModel.selectedCount()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = { Text("Ajouter des live sets", color = TextPrimary, fontSize = 16.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
                )
            },
            bottomBar = {
                if (selectedCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Background)
                            .padding(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.importSelected { djId -> onDjAdded(djId) } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Text(
                                "Importer ($selectedCount)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Search field
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.setQuery(it) },
                    label = { Text("Nom de l'artiste ou festival", color = TextSecondary) },
                    leadingIcon = {
                        if (isLoading)
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        else
                            Icon(Icons.Default.Search, null, tint = TextSecondary)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentPrimary,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCard,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = SurfaceCard
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Error message
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }

                // Sort toggle
                if (sets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortChip(
                            label = "Most Viewed",
                            selected = sortMode == SortMode.MOST_VIEWED,
                            onClick = { viewModel.setSortMode(SortMode.MOST_VIEWED) }
                        )
                        SortChip(
                            label = "Most Recent",
                            selected = sortMode == SortMode.MOST_RECENT,
                            onClick = { viewModel.setSortMode(SortMode.MOST_RECENT) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Set list
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sets, key = { it.set.tracklistUrl }) { item ->
                        SetRow(
                            item = item,
                            onClick = { viewModel.toggleSelection(item.set.tracklistUrl) }
                        )
                    }
                    item { Spacer(Modifier.height(160.dp)) }
                }
            }
        }

        // Import progress overlay
        importProgress?.let { progress ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(SurfaceCard, RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    CircularProgressIndicator(color = AccentPrimary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Import ${progress.current}/${progress.total}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        progress.currentTitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) AccentPrimary else SurfaceCard,
                RoundedCornerShape(20.dp)
            )
            .border(1.dp, if (selected) AccentPrimary else SurfaceSecondary, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SetRow(item: ArtistSetUiItem, onClick: () -> Unit) {
    val alpha = if (item.isAlreadyImported) 0.4f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isAlreadyImported, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isSelected || item.isAlreadyImported)
                Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                item.isAlreadyImported -> TextSecondary.copy(alpha = 0.4f)
                item.isSelected -> AccentPrimary
                else -> TextSecondary
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.set.title,
                color = TextPrimary.copy(alpha = alpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.set.date.isNotBlank()) {
                    Text(item.set.date, color = TextSecondary.copy(alpha = alpha), fontSize = 11.sp)
                }
                if (item.set.viewCount > 0) {
                    Text("${item.set.viewCount} views", color = TextSecondary.copy(alpha = alpha), fontSize = 11.sp)
                }
                if (item.isAlreadyImported) {
                    Text("✓ importé", color = AccentPrimary.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
    HorizontalDivider(color = SurfaceSecondary, thickness = 0.5.dp)
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep "error:" | head -20
```
Expected: no errors. Common issue: `SurfaceSecondary` import — check `com.podmix.ui.theme.*` exports this color.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt
git commit -m "feat: rewrite AddDjScreen — checkbox set list with 1001TL discovery"
```

---

## Task 4: Add "+" FAB to DjDetailScreen + "add more" navigation

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailScreen.kt`
- Modify: `app/src/main/java/com/podmix/navigation/NavGraph.kt`

- [ ] **Step 1: Add `onAddMore` callback to DjDetailScreen signature**

Change the function signature from:
```kotlin
fun DjDetailScreen(
    onEpisodeClick: (Int, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: DjDetailViewModel = hiltViewModel()
)
```
to:
```kotlin
fun DjDetailScreen(
    onEpisodeClick: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onAddMore: () -> Unit = {},
    viewModel: DjDetailViewModel = hiltViewModel()
)
```

- [ ] **Step 2: Add FloatingActionButton to DjDetailScreen Scaffold**

In `DjDetailScreen`, add `floatingActionButton` to the `Scaffold`:

```kotlin
Scaffold(
    containerColor = Background,
    floatingActionButton = {
        FloatingActionButton(
            onClick = onAddMore,
            containerColor = AccentPrimary,
            contentColor = Color.White
        ) {
            Icon(Icons.Default.Add, contentDescription = "Ajouter des sets")
        }
    },
    topBar = { /* existing */ }
) { /* existing */ }
```

Add the missing imports at the top:
```kotlin
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 3: Add `add_sets/{djId}` route to NavGraph**

In `NavGraph.kt`, add a new `Screen` object after the existing `AddDj` object:

```kotlin
object AddSets : Screen("add_sets/{djId}") {
    fun createRoute(djId: Int) = "add_sets/$djId"
}
```

Then add the composable (after the existing `AddDj` composable):

```kotlin
composable(
    Screen.AddSets.route,
    arguments = listOf(navArgument("djId") { type = NavType.IntType })
) {
    AddDjScreen(
        onBack = { navController.popBackStack() },
        onDjAdded = { navController.popBackStack() }
    )
}
```

Update the `DjDetailScreen` composable call to pass `onAddMore`:

```kotlin
DjDetailScreen(
    onEpisodeClick = { djId, eid -> navController.navigate(Screen.EpisodeDetail.createRoute(djId, eid)) },
    onBack = { navController.popBackStack() },
    onAddMore = {
        val djId = it.arguments?.getInt("djId") ?: return@DjDetailScreen
        navController.navigate(Screen.AddSets.createRoute(djId))
    }
)
```

Wait — the `djId` is in the `DjDetailViewModel`, not available in the composable call directly. The cleaner approach: expose the djId from `DjDetailViewModel`:

Check if `DjDetailViewModel` already exposes djId:
```bash
grep -n "djId\|savedState" app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt | head -10
```

If `DjDetailViewModel` uses `savedStateHandle["djId"]`, expose it:
```kotlin
val djId: Int = savedStateHandle["djId"] ?: 0
```

Then in the `DjDetailScreen` Scaffold:
```kotlin
floatingActionButton = {
    FloatingActionButton(onClick = { onAddMore(viewModel.djId) }, ...) { ... }
}
```

Update `onAddMore` signature to `onAddMore: (Int) -> Unit = {}`.

And in `NavGraph.kt`:
```kotlin
DjDetailScreen(
    onEpisodeClick = { djId, eid -> navController.navigate(Screen.EpisodeDetail.createRoute(djId, eid)) },
    onBack = { navController.popBackStack() },
    onAddMore = { djId -> navController.navigate(Screen.AddSets.createRoute(djId)) }
)
```

- [ ] **Step 4: Build full project**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/DjDetailScreen.kt
git add app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt
git add app/src/main/java/com/podmix/navigation/NavGraph.kt
git commit -m "feat: add + FAB to DjDetailScreen and add_sets route for adding more sets"
```

---

## Task 5: Install and verify on device

- [ ] **Step 1: Install**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Test flow**
1. Open app → Podcasts tab → Live section → "+"
2. Type "Korolova" → wait ~5s for 1001TL artist page to load
3. Verify: list of sets appears with checkboxes
4. Toggle "Most Recent" → order changes
5. Check 2-3 sets → "Importer (3)" button appears
6. Tap import → progress overlay shows
7. After import: navigates to DjDetailScreen with sets listed

- [ ] **Step 3: Test "add more" flow**
1. Open DjDetailScreen for Korolova → "+" FAB appears
2. Tap "+" → AddDjScreen opens with already-imported sets greyed out
3. Select a new set → import → returns to DjDetailScreen with new set added

- [ ] **Step 4: Check logs for JS extraction**

```bash
adb logcat -d -s ArtistPageScraper:D ArtistPageScraper:I AddDjVM:I 2>&1 | head -30
```

If `Extracted 0 sets from artist page` — the JS selectors need adjustment. Check what the 1001TL artist page actually renders:

```bash
adb logcat -d -s ArtistPageScraper:D 2>&1 | grep "Page finished"
```

Then temporarily add a debug JS dump to `EXTRACT_JS` to log the DOM structure and adjust selectors.

- [ ] **Step 5: Bump version and final commit**

In `app/build.gradle.kts`:
```kotlin
versionCode = 18
versionName = "1.6.0"
```

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.6.0 (1001TL DJ set discovery)"
```
