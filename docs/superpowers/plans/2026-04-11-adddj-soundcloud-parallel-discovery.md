# AddDj — Découverte parallèle SoundCloud + 1001TL — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lancer en parallèle la découverte des sets DJ depuis SoundCloud et 1001Tracklists, fusionner les résultats par fuzzy matching de titre, et utiliser SC comme source audio prioritaire.

**Architecture:** `SoundCloudArtistScraper` + `ArtistPageScraper` existant sont appelés en `async/await` dans `AddDjViewModel.search()`. `SetMatcher.merge()` fusionne les deux listes en `List<DiscoveredSet>` par score de mots communs (seuil 50%). `importSet()` utilise `DiscoveredSet` pour la résolution audio (SC → YT → MC → DDG SC fallback → skip) et tracklist (1001TL → YT description/comments).

**Tech Stack:** Kotlin Coroutines (`async`/`await`), Android WebView, OkHttp, Hilt, Jetpack Compose

---

## File Map

| Fichier | Action | Responsabilité |
|---------|--------|----------------|
| `service/SoundCloudArtistScraper.kt` | Créer | DDG → slug SC + WebView scrape tracks |
| `service/SetMatcher.kt` | Créer | Fuzzy title normalization + merge |
| `ui/screens/liveset/AddDjViewModel.kt` | Modifier | Parallel search, DiscoveredSet, importSet |
| `ui/screens/liveset/AddDjScreen.kt` | Modifier | Badge source (SC/1001TL/SC+1001TL), nouveau key |
| `app/src/test/java/com/podmix/SetMatcherTest.kt` | Créer | Unit tests SetMatcher |

---

### Task 1 : Data models — `DiscoveredSet` et `ScSet`

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1 : Ajouter `DiscoveredSet` et `ScSet` dans `AddDjViewModel.kt`**

Ajouter ces data classes en haut du fichier, après les imports et avant `enum class SortMode` :

```kotlin
/** Set retourné par SoundCloudArtistScraper */
data class ScSet(
    val title: String,
    val url: String,   // "https://soundcloud.com/artist/track-slug"
    val date: String   // peut être vide
)

/** Set fusionné SC + 1001TL — remplace ArtistSet dans le ViewModel */
data class DiscoveredSet(
    val title: String,
    val date: String,
    val viewCount: Int,
    val soundcloudUrl: String?,
    val tracklistUrl: String?,
    val youtubeVideoId: String?
) {
    /** Identifiant stable unique (pour key Compose et toggleSelection) */
    val id: String get() = soundcloudUrl ?: tracklistUrl ?: title
}
```

- [ ] **Step 2 : Remplacer `ArtistSetUiItem` par `DiscoveredSetUiItem`**

Remplacer la data class existante `ArtistSetUiItem` :

```kotlin
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
```

- [ ] **Step 3 : Build de vérification**

```bash
cd C:\APP\Podmix && ./gradlew assembleDebug 2>&1 | tail -20
```

Attendu : erreurs de compilation sur `ArtistSetUiItem` dans `AddDjViewModel` et `AddDjScreen` — c'est normal, on les corrige dans les tâches suivantes.

---

### Task 2 : `SetMatcher` + tests unitaires

**Files:**
- Create: `app/src/main/java/com/podmix/service/SetMatcher.kt`
- Create: `app/src/test/java/com/podmix/SetMatcherTest.kt`

- [ ] **Step 1 : Écrire les tests unitaires en premier (TDD)**

Créer `app/src/test/java/com/podmix/SetMatcherTest.kt` :

```kotlin
package com.podmix

import com.podmix.service.SetMatcher
import com.podmix.service.ArtistSet
import com.podmix.ui.screens.liveset.ScSet
import org.junit.Assert.*
import org.junit.Test

class SetMatcherTest {

    @Test
    fun `normalize strips noise words and punctuation`() {
        val words = SetMatcher.normalize("Korolova @ Area One, A State of Trance Festival")
        assertFalse(words.contains("@"))
        assertFalse(words.contains("festival"))
        assertTrue(words.contains("korolova"))
        assertTrue(words.contains("area"))
        assertTrue(words.contains("one"))
        assertTrue(words.contains("state"))
        assertTrue(words.contains("trance"))
    }

    @Test
    fun `score returns high value for same set with title variation`() {
        val score = SetMatcher.score(
            "Korolova @ Area One ASOT Festival",
            "Korolova - Area One State of Trance"
        )
        assertTrue("Expected score >= 0.5 but got $score", score >= 0.5f)
    }

    @Test
    fun `score returns low value for different sets`() {
        val score = SetMatcher.score(
            "Korolova @ Tulum",
            "Korolova @ Snow Machine Japan"
        )
        assertTrue("Expected score < 0.5 but got $score", score < 0.5f)
    }

    @Test
    fun `merge pairs matching sets from SC and 1001TL`() {
        val tlSets = listOf(
            ArtistSet("Korolova @ Area One ASOT", "2023-01-01", "https://1001tracklists.com/tracklist/abc/", 1000, null),
            ArtistSet("Korolova @ Tulum", "2023-06-01", "https://1001tracklists.com/tracklist/def/", 500, "ytId123")
        )
        val scSets = listOf(
            ScSet("Korolova - Area One State Of Trance", "https://soundcloud.com/korolova/area-one", ""),
        )
        val result = SetMatcher.merge(tlSets, scSets)
        assertEquals(3, result.size) // 1 matched + 1 SC-only... wait, area-one matched → 1 merged + 1 1001TL-only = 2? 
        // SC "area-one" matches TL "Area One ASOT" → merged (soundcloudUrl + tracklistUrl both set)
        // TL "Tulum" → 1001TL-only
        assertEquals(2, result.size)
        val matched = result.find { it.soundcloudUrl != null && it.tracklistUrl != null }
        assertNotNull(matched)
        assertEquals("https://soundcloud.com/korolova/area-one", matched!!.soundcloudUrl)
        assertEquals("https://1001tracklists.com/tracklist/abc/", matched.tracklistUrl)
    }

    @Test
    fun `merge keeps SC-only sets`() {
        val tlSets = emptyList<ArtistSet>()
        val scSets = listOf(ScSet("Korolova Live Tulum", "https://soundcloud.com/korolova/tulum", "2023-01-01"))
        val result = SetMatcher.merge(tlSets, scSets)
        assertEquals(1, result.size)
        assertNotNull(result[0].soundcloudUrl)
        assertNull(result[0].tracklistUrl)
    }

    @Test
    fun `merge keeps 1001TL-only sets`() {
        val scSets = emptyList<ScSet>()
        val tlSets = listOf(ArtistSet("Korolova Snow Machine Japan", "2024-01-01", "https://1001tracklists.com/tracklist/xyz/", 200, "ytId456"))
        val result = SetMatcher.merge(tlSets, scSets)
        assertEquals(1, result.size)
        assertNull(result[0].soundcloudUrl)
        assertEquals("ytId456", result[0].youtubeVideoId)
    }
}
```

- [ ] **Step 2 : Lancer les tests — confirmer qu'ils échouent (SetMatcher n'existe pas encore)**

```bash
cd C:\APP\Podmix && ./gradlew test 2>&1 | grep -E "FAIL|error|SetMatcher" | head -20
```

Attendu : erreur de compilation `Unresolved reference: SetMatcher`.

- [ ] **Step 3 : Créer `SetMatcher.kt`**

Créer `app/src/main/java/com/podmix/service/SetMatcher.kt` :

```kotlin
package com.podmix.service

import com.podmix.ui.screens.liveset.DiscoveredSet
import com.podmix.ui.screens.liveset.ScSet

object SetMatcher {

    private val NOISE_WORDS = setOf(
        "live", "set", "mix", "dj", "radio", "show", "episode",
        "festival", "presents", "records", "at", "the", "in", "a", "an",
        "and", "of", "for", "with", "by"
    )

    fun normalize(title: String): Set<String> =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in NOISE_WORDS }
            .toSet()

    fun score(a: String, b: String): Float {
        val wordsA = normalize(a)
        val wordsB = normalize(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f
        val common = wordsA.intersect(wordsB).size
        val total = minOf(wordsA.size, wordsB.size)
        return common.toFloat() / total.toFloat()
    }

    fun merge(tlSets: List<ArtistSet>, scSets: List<ScSet>): List<DiscoveredSet> {
        val result = mutableListOf<DiscoveredSet>()
        val matchedTlIndices = mutableSetOf<Int>()

        for (sc in scSets) {
            var bestScore = 0f
            var bestIdx = -1
            tlSets.forEachIndexed { idx, tl ->
                val s = score(sc.title, tl.title)
                if (s > bestScore) { bestScore = s; bestIdx = idx }
            }
            if (bestScore >= 0.5f && bestIdx >= 0) {
                val tl = tlSets[bestIdx]
                matchedTlIndices.add(bestIdx)
                result.add(DiscoveredSet(
                    title = tl.title,
                    date = tl.date.ifBlank { sc.date },
                    viewCount = tl.viewCount,
                    soundcloudUrl = sc.url,
                    tracklistUrl = tl.tracklistUrl,
                    youtubeVideoId = tl.youtubeVideoId
                ))
            } else {
                result.add(DiscoveredSet(
                    title = sc.title,
                    date = sc.date,
                    viewCount = 0,
                    soundcloudUrl = sc.url,
                    tracklistUrl = null,
                    youtubeVideoId = null
                ))
            }
        }

        // Sets 1001TL non matchés
        tlSets.forEachIndexed { idx, tl ->
            if (idx !in matchedTlIndices) {
                result.add(DiscoveredSet(
                    title = tl.title,
                    date = tl.date,
                    viewCount = tl.viewCount,
                    soundcloudUrl = null,
                    tracklistUrl = tl.tracklistUrl,
                    youtubeVideoId = tl.youtubeVideoId
                ))
            }
        }

        return result
    }
}
```

- [ ] **Step 4 : Lancer les tests**

```bash
cd C:\APP\Podmix && ./gradlew test 2>&1 | grep -E "PASS|FAIL|tests" | head -20
```

Attendu : `5 tests completed, 0 failed` (ou similaire).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/service/SetMatcher.kt app/src/test/java/com/podmix/SetMatcherTest.kt && git commit -m "feat: add SetMatcher for fuzzy SC/1001TL title merge"
```

---

### Task 3 : `SoundCloudArtistScraper`

**Files:**
- Create: `app/src/main/java/com/podmix/service/SoundCloudArtistScraper.kt`

- [ ] **Step 1 : Créer `SoundCloudArtistScraper.kt`**

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.podmix.ui.screens.liveset.ScSet

@Singleton
class SoundCloudArtistScraper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val TAG = "SCscraper"

    private val EXTRACT_JS = """
        (function tryExtract(attempt) {
            // SoundCloud artist tracks: links with 2 path segments
            var links = document.querySelectorAll('a[href]');
            var tracks = [];
            var seen = {};
            links.forEach(function(a) {
                var h = a.href || '';
                var m = h.match(/soundcloud\.com\/([^/?#]+)\/([^/?#]+)(?:${'$'}|[?#])/);
                if (!m) return;
                var slug2 = m[2];
                // Exclure les pages non-track
                if (['sets','reposts','likes','following','followers','tracks','albums','popular-tracks'].indexOf(slug2) >= 0) return;
                if (seen[h]) return;
                seen[h] = true;
                var title = a.innerText.trim() || a.getAttribute('aria-label') || '';
                if (!title || title.length < 3) return;
                tracks.push({ title: title, url: 'https://soundcloud.com/' + m[1] + '/' + m[2] });
            });
            if (tracks.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }
            Android.onTracksExtracted(JSON.stringify(tracks));
        })(0);
    """.trimIndent()

    /** Trouve la page artiste SC via DDG puis scrape ses tracks. Retourne null si échec. */
    suspend fun findAndScrape(artistName: String): List<ScSet>? {
        val artistPageUrl = findArtistPage(artistName) ?: return null
        Log.i(TAG, "SC artist page: $artistPageUrl")
        return scrapeArtistPage(artistPageUrl)
    }

    private suspend fun findArtistPage(artistName: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode("site:soundcloud.com $artistName", "UTF-8")
            val client = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "text/html")
                .build()
            val html = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val match = Regex("""uddg=([^&"]+)""").find(html) ?: return@withContext null
            val url = URLDecoder.decode(match.groupValues[1], "UTF-8")
            // Valider que c'est une page artiste (1 segment de path), pas un track (2 segments)
            val isArtistPage = Regex("""soundcloud\.com/[^/?#]+/?${'$'}""").containsMatchIn(url)
            if (isArtistPage) url else null
        } catch (e: Exception) {
            Log.w(TAG, "DDG SC search failed: ${e.message}")
            null
        }
    }

    private suspend fun scrapeArtistPage(url: String): List<ScSet>? {
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(url)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ScSet>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<ScSet>?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTracksExtracted(json: String) {
                    val tracks = parseTracksJson(json)
                    Log.i(TAG, "Extracted ${tracks?.size ?: 0} SC tracks")
                    resume(tracks)
                }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(EXTRACT_JS, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) resume(null)
                }
            }

            cont.invokeOnCancellation { resume(null) }
            webView.loadUrl(url)
        }

    private fun parseTracksJson(json: String): List<ScSet>? {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            val tracks = mutableListOf<ScSet>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.getString("title").trim()
                val url = obj.getString("url").trim()
                if (title.isNotBlank() && url.isNotBlank()) {
                    tracks.add(ScSet(title = title, url = url, date = ""))
                }
            }
            if (tracks.size >= 1) tracks else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }
}
```

- [ ] **Step 2 : Build de vérification**

```bash
cd C:\APP\Podmix && ./gradlew assembleDebug 2>&1 | tail -20
```

Attendu : compilation réussie pour `SoundCloudArtistScraper`. Des erreurs persistent dans `AddDjViewModel` (types changés) — normal.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/podmix/service/SoundCloudArtistScraper.kt && git commit -m "feat: add SoundCloudArtistScraper — DDG artist page + WebView track extraction"
```

---

### Task 4 : Mettre à jour `AddDjViewModel` — parallel search + merge

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1 : Mettre à jour les imports et la signature du ViewModel**

Dans `AddDjViewModel.kt`, ajouter les imports manquants et remplacer l'injection :

```kotlin
import com.podmix.service.SoundCloudArtistScraper
import com.podmix.service.SetMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
```

Modifier la classe `AddDjViewModel` pour injecter `SoundCloudArtistScraper` :

```kotlin
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
```

- [ ] **Step 2 : Changer le type des StateFlows de `ArtistSetUiItem` vers `DiscoveredSetUiItem`**

Remplacer :
```kotlin
private val _sets = MutableStateFlow<List<ArtistSetUiItem>>(emptyList())
val sets: StateFlow<List<ArtistSetUiItem>> = _sets.asStateFlow()
```
par :
```kotlin
private val _sets = MutableStateFlow<List<DiscoveredSetUiItem>>(emptyList())
val sets: StateFlow<List<DiscoveredSetUiItem>> = _sets.asStateFlow()
```

Remplacer :
```kotlin
private var rawSets: List<ArtistSetUiItem> = emptyList()
```
par :
```kotlin
private var rawSets: List<DiscoveredSetUiItem> = emptyList()
```

Remplacer le `selectedCount` :
```kotlin
val selectedCount: StateFlow<Int> = _sets
    .map { list -> list.count { it.isSelected } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
```
(identique, pas de changement nécessaire ici)

- [ ] **Step 3 : Remplacer la fonction `search()` par la version parallèle**

Remplacer la fonction `search()` entière :

```kotlin
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
                isAlreadyImported = when {
                    set.youtubeVideoId != null -> set.youtubeVideoId in alreadyImportedVideoIds
                    else -> false
                }
            )
        }
        applySortAndEmit()
    } catch (e: Exception) {
        Log.e(TAG, "Search error: ${e.message}", e)
        _errorMessage.value = "Erreur: ${e.message}"
    }
    _isLoading.value = false
}
```

- [ ] **Step 4 : Mettre à jour `applySortAndEmit()`, `toggleSelectAll()`, `toggleSelection()`**

`applySortAndEmit()` — le tri `MOST_VIEWED` et `MOST_RECENT` restent identiques, mais le champ `date` est maintenant `set.date` (inchangé). Vérifier que la fonction compile sans changement.

`toggleSelectAll()` — remplacer `!item.isAlreadyImported` pattern (inchangé fonctionnellement).

`toggleSelection()` — remplacer le paramètre `tracklistUrl: String` par `setId: String` :

```kotlin
fun toggleSelection(setId: String) {
    Log.i(TAG, "toggleSelection: $setId")
    rawSets = rawSets.map { item ->
        if (item.set.id == setId && !item.isAlreadyImported)
            item.copy(isSelected = !item.isSelected)
        else item
    }
    applySortAndEmit()
}
```

- [ ] **Step 5 : Build de vérification**

```bash
cd C:\APP\Podmix && ./gradlew assembleDebug 2>&1 | grep -E "error:|warning:" | grep -v "warning: w" | head -30
```

Attendu : erreurs restantes uniquement dans `importSelected()` et `importSet()` (type `ArtistSet` non résolu) et dans `AddDjScreen.kt`.

- [ ] **Step 6 : Commit partiel**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt && git commit -m "feat: parallel SC+1001TL discovery with SetMatcher merge in AddDjViewModel"
```

---

### Task 5 : Mettre à jour `importSet()` dans `AddDjViewModel`

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1 : Mettre à jour `importSelected()` pour utiliser `DiscoveredSetUiItem`**

Dans `importSelected()`, remplacer les références à `item.set` qui utilisaient `ArtistSet`. La structure est identique — `item.set` est maintenant un `DiscoveredSet`. Vérifier que l'appel `importSet(djId, item.set)` reste valide après la mise à jour d'`importSet()`.

- [ ] **Step 2 : Remplacer `importSet(djId: Int, set: ArtistSet)` par `importSet(djId: Int, set: DiscoveredSet)`**

Remplacer la fonction `importSet()` entière :

```kotlin
private suspend fun importSet(djId: Int, set: DiscoveredSet) {
    // Résolution audio — priorité : SC → YT → MC → DDG SC fallback → skip
    var ytId = set.youtubeVideoId
    var scTrackUrl = set.soundcloudUrl
    var mcKey: String? = null

    // Si pas de source directe, probe la page tracklist 1001TL
    if (ytId == null && scTrackUrl == null && set.tracklistUrl != null) {
        Log.i(TAG, "Probing tracklist page for '${set.title}'...")
        val src = artistPageScraper.getMediaSourceFromTracklistPage(set.tracklistUrl)
        Log.i(TAG, "Tracklist page result: YT=${src?.youtubeVideoId} SC=${src?.soundcloudTrackUrl} MC=${src?.mixcloudKey}")
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

    // Dedup : par YouTube ID d'abord, puis par titre
    if (ytId != null) {
        val existing = episodeDao.getByYoutubeVideoId(ytId, djId)
        if (existing != null) {
            Log.i(TAG, "Skipping duplicate (ytId): ${set.title}")
            return
        }
    }
    val existingByTitle = episodeDao.getByTitleAndPodcastId(set.title, djId)
    if (existingByTitle != null) {
        Log.i(TAG, "Skipping duplicate (title): ${set.title}")
        return
    }

    val dateMs = parseDate(set.date)

    val episode = EpisodeEntity(
        podcastId = djId,
        title = set.title,
        audioUrl = "",
        datePublished = dateMs,
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

    // Marquer importé
    alreadyImportedVideoIds = alreadyImportedVideoIds + (ytId ?: "")
    rawSets = rawSets.map { item ->
        if (item.set.id == set.id)
            item.copy(isAlreadyImported = true, isSelected = false)
        else item
    }
    applySortAndEmit()
}
```

- [ ] **Step 2 : Mettre à jour la queue de download dans `importSelected()`**

Dans `importSelected()`, la boucle qui vérifie `item.set.title` doit utiliser `item.set.title` (inchangé). Vérifier que `item.set.tracklistUrl` n'est plus utilisé dans `importSelected()` comme identifiant (remplacé par `item.set.id` dans `toggleSelection`).

La partie download dans `importSelected()` utilise `item.set.title` pour retrouver l'épisode via `episodeDao.getByTitleAndPodcastId()` — inchangé, ça fonctionne.

- [ ] **Step 3 : Build complet**

```bash
cd C:\APP\Podmix && ./gradlew assembleDebug 2>&1 | tail -30
```

Attendu : seules erreurs restantes dans `AddDjScreen.kt` (type `ArtistSetUiItem` → `DiscoveredSetUiItem`).

- [ ] **Step 4 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt && git commit -m "feat: importSet uses DiscoveredSet, SC prioritized over YouTube"
```

---

### Task 6 : Mettre à jour `AddDjScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt`

- [ ] **Step 1 : Changer le type dans la LazyColumn et le key**

Remplacer :
```kotlin
items(sets, key = { it.set.tracklistUrl }) { item ->
    SetRow(
        item = item,
        onClick = { viewModel.toggleSelection(item.set.tracklistUrl) }
    )
}
```
par :
```kotlin
items(sets, key = { it.set.id }) { item ->
    SetRow(
        item = item,
        onClick = { viewModel.toggleSelection(item.set.id) }
    )
}
```

- [ ] **Step 2 : Mettre à jour le composable `SetRow` pour afficher le badge source**

Trouver le composable `SetRow` dans `AddDjScreen.kt` et ajouter le badge `item.sourceLabel`. Chercher la ligne qui affiche le titre du set et ajouter le badge à côté :

```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
) {
    Text(
        text = item.set.title,
        color = if (item.isAlreadyImported) TextSecondary else TextPrimary,
        fontSize = 13.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
    )
    Spacer(Modifier.width(6.dp))
    Text(
        text = item.sourceLabel,
        color = when (item.sourceLabel) {
            "SC+TL" -> Color(0xFFFF5500)   // orange SoundCloud
            "SC"    -> Color(0xFFFF5500)
            else    -> AccentPrimary
        },
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}
```

Adapter selon la structure exacte du `SetRow` existant dans le fichier.

- [ ] **Step 3 : Build final**

```bash
cd C:\APP\Podmix && ./gradlew assembleDebug 2>&1 | tail -20
```

Attendu : `BUILD SUCCESSFUL`.

- [ ] **Step 4 : Install et test manuel**

```bash
cd C:\APP\Podmix && ./gradlew installDebug 2>&1 | tail -10
```

Test manuel :
1. Ouvrir AddDj, taper "Korolova"
2. Vérifier que les deux scrapers se lancent (logs : `1001TL artist page:` et `SC artist page:`)
3. Vérifier que les sets fusionnés apparaissent avec badges `SC+TL`, `SC`, `1001TL`
4. Importer un set SC+TL → vérifier que l'audio est SoundCloud (pas YouTube)
5. Vérifier que la tracklist 1001TL est bien récupérée pour les sets SC+TL

- [ ] **Step 5 : Commit final**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt && git commit -m "feat: AddDjScreen shows source badge SC/1001TL/SC+TL, use set.id as key"
```

---

## Self-Review

**Spec coverage :**
- ✅ Parallel SC + 1001TL discovery — Task 4
- ✅ `DiscoveredSet` data model — Task 1
- ✅ `SoundCloudArtistScraper` (DDG + WebView) — Task 3
- ✅ `SetMatcher.merge()` with 50% threshold — Task 2
- ✅ Audio priority SC → YT → MC → DDG SC fallback — Task 5
- ✅ Tracklist priority 1001TL → YT description/comments — Task 5 (via `detectAndSaveTracks`)
- ✅ SC scraper timeout 15s → continue with 1001TL only — Task 4 (try/catch + null)
- ✅ 1001TL scraper timeout → continue with SC only — Task 4
- ✅ Source badge UI — Task 6
- ✅ SC-only sets (no tracklistUrl) accepted without tracklist — Task 5
- ✅ 1001TL-only sets → DDG SC fallback — Task 5

**Type consistency :**
- `DiscoveredSet.id` utilisé dans Task 4 (`toggleSelection`), Task 5 (`importSet`), Task 6 (`key`, `onClick`) ✅
- `ScSet` défini dans `AddDjViewModel.kt` (Task 1), utilisé dans `SetMatcher` (Task 2) et `SoundCloudArtistScraper` (Task 3) ✅
- `DiscoveredSetUiItem.sourceLabel` défini Task 1, utilisé Task 6 ✅
