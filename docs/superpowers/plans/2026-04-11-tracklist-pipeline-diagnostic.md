# Tracklist Pipeline Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter yt-dlp (chapters YouTube) et MixesDB au pipeline liveset, et exposer l'état de chaque source dans un bandeau collapsible persistant dans l'écran épisode.

**Architecture:** Nouveau data class `SourceResult` émis par `TrackRepository` via callback `onSourceResult`. Le ViewModel accumule les résultats dans un `StateFlow<List<SourceResult>>`. Le composant `TracklistDiagnosticBanner` consomme cette liste et s'auto-collapse 3s après succès.

**Tech Stack:** Kotlin/Compose (Android), Retrofit (HTTP), Python (serveur local port 8099), yt-dlp, MixesDB MediaWiki API

---

## File Map

| Fichier | Action | Responsabilité |
|---------|--------|---------------|
| `app/src/main/java/com/podmix/domain/model/SourceResult.kt` | Créer | Enum `SourceStatus` + data class `SourceResult` |
| `app/src/main/java/com/podmix/data/api/TracklistApi.kt` | Modifier | Ajouter `ChaptersResponse`, `ChaptersTrack`, `MixesDbResponse`, `MixesDbTrack` + 2 endpoints |
| `app/src/main/java/com/podmix/data/repository/TrackRepository.kt` | Modifier | Ajouter `onSourceResult` callback + appels yt-dlp + MixesDB dans pipeline liveset |
| `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt` | Modifier | Ajouter `_sourceResults` StateFlow + wiring callback |
| `app/src/main/java/com/podmix/ui/screens/episode/TracklistDiagnosticBanner.kt` | Créer | Composant Compose bandeau collapsible |
| `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailScreen.kt` | Modifier | Insérer `TracklistDiagnosticBanner` + collecter `sourceResults` |
| `audio_timestamp_server.py` | Modifier | Ajouter handlers GET `/chapters` et GET `/mixesdb` |
| `app/src/test/java/com/podmix/ExampleUnitTest.kt` | Modifier | Tests unitaires SourceResult pipeline logic |

---

## Task 1 : Data model `SourceResult`

**Files:**
- Create: `app/src/main/java/com/podmix/domain/model/SourceResult.kt`
- Modify: `app/src/test/java/com/podmix/ExampleUnitTest.kt`

- [ ] **Step 1 : Écrire le test**

Ajouter à la fin de `app/src/test/java/com/podmix/ExampleUnitTest.kt` :

```kotlin
class SourceResultTest {

    @Test fun pending_source_has_zero_tracks() {
        val r = SourceResult("Description YouTube", SourceStatus.PENDING)
        assertEquals(0, r.trackCount)
        assertEquals(0L, r.elapsedMs)
        assertEquals("", r.reason)
    }

    @Test fun success_source_carries_count_and_elapsed() {
        val r = SourceResult("Mixcloud", SourceStatus.SUCCESS, trackCount = 14, elapsedMs = 843L)
        assertEquals(SourceStatus.SUCCESS, r.status)
        assertEquals(14, r.trackCount)
        assertEquals(843L, r.elapsedMs)
    }

    @Test fun skipped_source_carries_reason() {
        val r = SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = "pas de chapters")
        assertEquals("pas de chapters", r.reason)
    }

    @Test fun all_statuses_are_defined() {
        val statuses = SourceStatus.values()
        assertTrue(statuses.contains(SourceStatus.PENDING))
        assertTrue(statuses.contains(SourceStatus.RUNNING))
        assertTrue(statuses.contains(SourceStatus.SUCCESS))
        assertTrue(statuses.contains(SourceStatus.FAILED))
        assertTrue(statuses.contains(SourceStatus.SKIPPED))
    }
}
```

- [ ] **Step 2 : Vérifier que le test échoue**

```
cd C:/APP/Podmix
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:testDebugUnitTest --tests "com.podmix.SourceResultTest" 2>&1 | tail -10
```

Attendu : FAILED — `SourceResult` et `SourceStatus` pas encore définis.

- [ ] **Step 3 : Créer `SourceResult.kt`**

```kotlin
package com.podmix.domain.model

enum class SourceStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class SourceResult(
    val source: String,
    val status: SourceStatus,
    val trackCount: Int = 0,
    val elapsedMs: Long = 0,
    val reason: String = ""
)
```

- [ ] **Step 4 : Ajouter l'import dans le fichier de test**

En haut de `ExampleUnitTest.kt`, après les imports existants :

```kotlin
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
```

- [ ] **Step 5 : Vérifier que les tests passent**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:testDebugUnitTest --tests "com.podmix.SourceResultTest" 2>&1 | tail -10
```

Attendu : 4 tests PASSED.

- [ ] **Step 6 : Commit**

```bash
git add app/src/main/java/com/podmix/domain/model/SourceResult.kt \
        app/src/test/java/com/podmix/ExampleUnitTest.kt
git commit -m "feat: add SourceResult domain model with SourceStatus enum"
```

---

## Task 2 : Endpoints serveur Python (`/chapters` + `/mixesdb`)

**Files:**
- Modify: `audio_timestamp_server.py`

- [ ] **Step 1 : Ajouter les imports yt-dlp en tête de fichier**

Dans `audio_timestamp_server.py`, après `import logging`, ajouter :

```python
import re
try:
    import yt_dlp
    YT_DLP_AVAILABLE = True
except ImportError:
    YT_DLP_AVAILABLE = False
    logger.warning("yt-dlp not installed. /chapters endpoint will return [].")
```

- [ ] **Step 2 : Installer yt-dlp si absent**

```
pip install yt-dlp
```

Attendu : `Successfully installed yt-dlp-...` ou `already satisfied`.

- [ ] **Step 3 : Ajouter le handler `/chapters` dans `do_GET`**

Dans `do_GET`, après le bloc `elif path == "/tracklist":` (avant le `else: self.send_error(404...)`), insérer :

```python
elif path == "/chapters":
    query = urllib.parse.parse_qs(parsed.query)
    video_id = query.get("video_id", [""])[0]
    if not video_id:
        self.send_error(400, "Missing 'video_id' parameter")
        return
    try:
        tracks = []
        if YT_DLP_AVAILABLE:
            ydl_opts = {
                'quiet': True,
                'skip_download': True,
                'no_warnings': True,
            }
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(
                    f"https://www.youtube.com/watch?v={video_id}",
                    download=False
                )
                chapters = info.get('chapters') or []
                tracks = [
                    {"title": c.get("title", ""), "startTimeSec": int(c.get("start_time", 0))}
                    for c in chapters
                ]
        self.send_response(200)
        self.send_header("Content-type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"tracks": tracks}).encode())
    except Exception as e:
        logger.error(f"Error fetching chapters: {e}")
        self.send_response(200)
        self.send_header("Content-type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"tracks": []}).encode())
```

- [ ] **Step 4 : Ajouter le handler `/mixesdb` dans `do_GET`**

Juste après le bloc `/chapters`, insérer :

```python
elif path == "/mixesdb":
    query_params = urllib.parse.parse_qs(parsed.query)
    q = query_params.get("q", [""])[0]
    if not q:
        self.send_error(400, "Missing 'q' parameter")
        return
    try:
        import urllib.request
        tracks = []

        # 1. Search for the page
        search_url = (
            "https://www.mixesdb.com/api.php?action=query&list=search"
            f"&srsearch={urllib.parse.quote(q)}&format=json&srlimit=1"
        )
        with urllib.request.urlopen(search_url, timeout=5) as resp:
            search_data = json.loads(resp.read().decode())
        results = search_data.get("query", {}).get("search", [])
        if not results:
            raise ValueError("No MixesDB page found")

        page_id = results[0]["pageid"]

        # 2. Fetch wikitext
        parse_url = (
            f"https://www.mixesdb.com/api.php?action=parse&pageid={page_id}"
            "&prop=wikitext&format=json"
        )
        with urllib.request.urlopen(parse_url, timeout=5) as resp:
            parse_data = json.loads(resp.read().decode())
        wikitext = parse_data.get("parse", {}).get("wikitext", {}).get("*", "")

        # 3. Parse tracklist rows (defensive — format not officially documented)
        # Pattern: lines like "| 1 || Artist || Title" or "| Artist - Title"
        for line in wikitext.splitlines():
            line = line.strip()
            cells = [c.strip() for c in line.split("||")]
            if len(cells) >= 3:
                # columns: position, artist, title (optionally more)
                artist = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', cells[1])
                title = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', cells[2])
                if artist and title:
                    tracks.append({"artist": artist, "title": title, "startTimeSec": 0})
            elif len(cells) == 2 and " - " in cells[1]:
                parts = cells[1].split(" - ", 1)
                artist = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', parts[0])
                title = re.sub(r'\[\[([^|]+\|)?([^\]]+)\]\]', r'\2', parts[1])
                if artist and title:
                    tracks.append({"artist": artist, "title": title, "startTimeSec": 0})

        self.send_response(200)
        self.send_header("Content-type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"tracks": tracks}).encode())
    except Exception as e:
        logger.warning(f"MixesDB: {e}")
        self.send_response(200)
        self.send_header("Content-type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"tracks": []}).encode())
```

- [ ] **Step 5 : Mettre à jour la liste des endpoints dans `main()`**

Dans `main()`, dans le bloc des `logger.info`, ajouter après la ligne `/analyze` :

```python
logger.info(f"  GET  http://{HOST}:{PORT}/chapters?video_id=ID - YouTube chapters via yt-dlp")
logger.info(f"  GET  http://{HOST}:{PORT}/mixesdb?q=QUERY - MixesDB tracklist")
```

- [ ] **Step 6 : Tester manuellement le serveur**

```bash
# Terminal 1 — lancer le serveur
cd C:/APP/Podmix
python audio_timestamp_server.py

# Terminal 2 — tester /chapters (vidéo avec chapitres connus)
curl "http://localhost:8099/chapters?video_id=dQw4w9WgXcQ"
# Attendu: {"tracks": [...]} ou {"tracks": []}

# Tester /mixesdb
curl "http://localhost:8099/mixesdb?q=Korolova"
# Attendu: {"tracks": [...]} ou {"tracks": []}

# Tester /health
curl "http://localhost:8099/health"
# Attendu: {"status": "healthy"}
```

- [ ] **Step 7 : Commit**

```bash
git add audio_timestamp_server.py
git commit -m "feat: add /chapters (yt-dlp) and /mixesdb endpoints to local server"
```

---

## Task 3 : API Retrofit — nouveaux endpoints

**Files:**
- Modify: `app/src/main/java/com/podmix/data/api/TracklistApi.kt`

- [ ] **Step 1 : Ajouter les data classes de réponse et les endpoints**

Remplacer le contenu de `TracklistApi.kt` par :

```kotlin
package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

data class TracklistResponse(
    @SerializedName("tracks") val tracks: List<TracklistTrack>?,
    @SerializedName("source") val source: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("error") val error: String?
)

data class TracklistTrack(
    @SerializedName("position") val position: Int,
    @SerializedName("artist") val artist: String,
    @SerializedName("title") val title: String,
    @SerializedName("start_time_sec") val startTimeSec: Int
)

data class ChaptersResponse(
    @SerializedName("tracks") val tracks: List<ChaptersTrack>?
)

data class ChaptersTrack(
    @SerializedName("title") val title: String,
    @SerializedName("startTimeSec") val startTimeSec: Int
)

data class MixesDbResponse(
    @SerializedName("tracks") val tracks: List<MixesDbTrack>?
)

data class MixesDbTrack(
    @SerializedName("artist") val artist: String,
    @SerializedName("title") val title: String,
    @SerializedName("startTimeSec") val startTimeSec: Int
)

interface TracklistApi {
    @GET("tracklist")
    suspend fun getTracklist(@Query("q") query: String): TracklistResponse

    @GET("analyze")
    suspend fun analyzeByUrl(@Query("url") url: String): TracklistResponse

    @GET("chapters")
    suspend fun getChapters(@Query("video_id") videoId: String): ChaptersResponse

    @GET("mixesdb")
    suspend fun getMixesDb(@Query("q") query: String): MixesDbResponse
}
```

- [ ] **Step 2 : Vérifier la compilation**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/podmix/data/api/TracklistApi.kt
git commit -m "feat: add /chapters and /mixesdb Retrofit endpoints"
```

---

## Task 4 : `TrackRepository` — callback `onSourceResult` + emit pour sources existantes

**Files:**
- Modify: `app/src/main/java/com/podmix/data/repository/TrackRepository.kt`
- Modify: `app/src/test/java/com/podmix/ExampleUnitTest.kt`

- [ ] **Step 1 : Écrire les tests pour la logique d'émission**

Ajouter dans `ExampleUnitTest.kt` :

```kotlin
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus

class SourceEmissionTest {

    @Test fun success_result_has_correct_fields() {
        val t0 = System.currentTimeMillis()
        Thread.sleep(5)
        val elapsed = System.currentTimeMillis() - t0
        val r = SourceResult("Description YouTube", SourceStatus.SUCCESS, trackCount = 14, elapsedMs = elapsed)
        assertEquals(SourceStatus.SUCCESS, r.status)
        assertTrue(r.trackCount > 0)
        assertTrue(r.elapsedMs > 0)
    }

    @Test fun skipped_result_preserves_reason() {
        val r = SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = "serveur hors ligne")
        assertEquals(SourceStatus.SKIPPED, r.status)
        assertEquals("serveur hors ligne", r.reason)
    }

    @Test fun failed_result_has_zero_tracks() {
        val r = SourceResult("Mixcloud", SourceStatus.FAILED, trackCount = 0, elapsedMs = 950L)
        assertEquals(0, r.trackCount)
        assertEquals(SourceStatus.FAILED, r.status)
    }
}
```

- [ ] **Step 2 : Vérifier que les tests passent**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:testDebugUnitTest --tests "com.podmix.SourceEmissionTest" 2>&1 | tail -10
```

Attendu : 3 tests PASSED.

- [ ] **Step 3 : Ajouter le paramètre `onSourceResult` à `detectAndSaveTracks`**

Dans `TrackRepository.kt`, ajouter l'import en tête :

```kotlin
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
```

Modifier la signature de `detectAndSaveTracks` — ajouter le paramètre après `audioUrl` :

```kotlin
onSourceResult: ((SourceResult) -> Unit)? = null
```

- [ ] **Step 4 : Remplacer le bloc `isLiveSet` par la version avec émissions**

Remplacer le bloc entier `if (isLiveSet) { ... }` (lignes 77–121 actuelles) par :

```kotlin
if (isLiveSet) {
    // LIVE SETS: Description YouTube → yt-dlp → Mixcloud → MixesDB → Shazam/IA

    // 1. Description YouTube (instant)
    val t1 = System.currentTimeMillis()
    onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.RUNNING))
    status(5, "📄 Analyse description YouTube... ${elapsed()}")
    val fromDesc = withContext(Dispatchers.IO) {
        tracklistService.detect(description, podcastName, episodeTitle)
    }
    val desc_elapsed = System.currentTimeMillis() - t1
    if (fromDesc.isNotEmpty()) {
        val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }
        saveTracks(episodeId, fromDesc, hasTimestamps, episodeDurationSec,
            if (hasTimestamps) "timestamped" else "uniform")
        onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
            trackCount = fromDesc.size, elapsedMs = desc_elapsed))
        listOf("yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA").forEach {
            onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
        }
        status(100, "✅ ${fromDesc.size} tracks — description (timestamps=${hasTimestamps}) ${elapsed()}")
        return true
    }
    onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.FAILED,
        elapsedMs = desc_elapsed, reason = "aucun track détecté"))
    status(20, "Description: rien trouvé — passage yt-dlp ${elapsed()}")

    // 2. yt-dlp chapters (serveur requis)
    if (youtubeVideoId != null && shazamServerUp) {
        val t2 = System.currentTimeMillis()
        onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.RUNNING))
        status(25, "🎬 yt-dlp chapters YouTube... ${elapsed()}")
        val fromChapters = tryYtDlpChapters(youtubeVideoId)
        val ytdlp_elapsed = System.currentTimeMillis() - t2
        if (fromChapters != null && fromChapters.size >= 3) {
            trackDao.deleteByEpisode(episodeId)
            saveTracks(episodeId, fromChapters, true, 0, "ytdlp")
            onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SUCCESS,
                trackCount = fromChapters.size, elapsedMs = ytdlp_elapsed))
            listOf("Mixcloud", "MixesDB", "Shazam/IA").forEach {
                onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
            }
            status(100, "✅ ${fromChapters.size} tracks — yt-dlp ${elapsed()}")
            return true
        }
        val ytdlp_reason = if (fromChapters == null) "erreur serveur" else "pas de chapters"
        onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.FAILED,
            elapsedMs = ytdlp_elapsed, reason = ytdlp_reason))
        status(38, "yt-dlp: $ytdlp_reason ${elapsed()}")
    } else {
        val reason = if (youtubeVideoId == null) "pas de vidéo YouTube" else "serveur hors ligne"
        onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = reason))
        status(25, "⚠️ yt-dlp skippé: $reason ${elapsed()}")
    }

    // 3. Mixcloud API
    val t3 = System.currentTimeMillis()
    onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.RUNNING))
    status(42, "🔍 Mixcloud: $query ${elapsed()}")
    val fromMixcloud = tryMixcloud(query)
    val mixcloud_elapsed = System.currentTimeMillis() - t3
    if (fromMixcloud != null && fromMixcloud.size >= 3) {
        trackDao.deleteByEpisode(episodeId)
        saveTracks(episodeId, fromMixcloud, true, 0, "mixcloud")
        onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.SUCCESS,
            trackCount = fromMixcloud.size, elapsedMs = mixcloud_elapsed))
        listOf("MixesDB", "Shazam/IA").forEach {
            onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
        }
        status(100, "✅ ${fromMixcloud.size} tracks — Mixcloud ${elapsed()}")
        return true
    }
    onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.FAILED,
        elapsedMs = mixcloud_elapsed, reason = "aucun résultat"))
    status(55, "Mixcloud: pas de résultat ${elapsed()}")

    // 4. MixesDB (serveur requis)
    if (shazamServerUp) {
        val t4 = System.currentTimeMillis()
        onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.RUNNING))
        status(58, "📚 MixesDB: $query ${elapsed()}")
        val fromMixesDb = tryMixesDb(query)
        val mixesdb_elapsed = System.currentTimeMillis() - t4
        if (fromMixesDb != null && fromMixesDb.size >= 3) {
            trackDao.deleteByEpisode(episodeId)
            saveTracks(episodeId, fromMixesDb, false, episodeDurationSec, "mixesdb")
            onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.SUCCESS,
                trackCount = fromMixesDb.size, elapsedMs = mixesdb_elapsed))
            onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED))
            status(100, "✅ ${fromMixesDb.size} tracks — MixesDB ${elapsed()}")
            return true
        }
        onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.FAILED,
            elapsedMs = mixesdb_elapsed, reason = "aucun résultat"))
        status(70, "MixesDB: pas de résultat ${elapsed()}")
    } else {
        onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.SKIPPED, reason = "serveur hors ligne"))
        status(58, "⚠️ MixesDB skippé: serveur hors ligne ${elapsed()}")
    }

    // 5. Shazam/IA (dernier recours)
    if (youtubeVideoId != null && shazamServerUp) {
        val t5 = System.currentTimeMillis()
        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
        status(73, "🎵 Shazam/IA — analyse audio... ${elapsed()}")
        val fromShazam = tryShazamAnalysisVerbose(youtubeVideoId) { pct, msg ->
            status(73 + (pct * 0.22f).toInt(), "🎵 Shazam $msg ${elapsed()}")
        }
        val shazam_elapsed = System.currentTimeMillis() - t5
        if (fromShazam != null && fromShazam.size >= 3) {
            trackDao.deleteByEpisode(episodeId)
            saveTracks(episodeId, fromShazam, true, 0, "shazam")
            onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                trackCount = fromShazam.size, elapsedMs = shazam_elapsed))
            status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
            return true
        }
        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
            elapsedMs = shazam_elapsed, reason = "pas de résultat"))
        status(98, "Shazam: pas de résultat ${elapsed()}")
    } else if (youtubeVideoId != null) {
        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = "serveur hors ligne"))
        status(73, "⚠️ Serveur Shazam hors ligne — skip ${elapsed()}")
    } else {
        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = "pas de vidéo YouTube"))
    }
```

- [ ] **Step 5 : Ajouter les deux méthodes privées `tryYtDlpChapters` et `tryMixesDb`**

Ajouter après `tryMixcloud()` (avant `try1001Tracklists`) :

```kotlin
private suspend fun tryYtDlpChapters(videoId: String): List<ParsedTrack>? {
    return try {
        val response = kotlinx.coroutines.withTimeout(15_000) {
            withContext(Dispatchers.IO) {
                tracklistApi.getChapters(videoId)
            }
        }
        response.tracks?.takeIf { it.isNotEmpty() }?.map { c ->
            ParsedTrack("", c.title, c.startTimeSec.toFloat())
        }
    } catch (e: Exception) {
        Log.d("TrackRepo", "yt-dlp chapters: ${e.message}")
        null
    }
}

private suspend fun tryMixesDb(query: String): List<ParsedTrack>? {
    return try {
        val response = kotlinx.coroutines.withTimeout(10_000) {
            withContext(Dispatchers.IO) {
                tracklistApi.getMixesDb(query)
            }
        }
        response.tracks?.takeIf { it.isNotEmpty() }?.map { t ->
            ParsedTrack(t.artist, t.title, t.startTimeSec.toFloat())
        }
    } catch (e: Exception) {
        Log.d("TrackRepo", "MixesDB: ${e.message}")
        null
    }
}
```

- [ ] **Step 6 : Vérifier la compilation**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 7 : Commit**

```bash
git add app/src/main/java/com/podmix/data/repository/TrackRepository.kt \
        app/src/test/java/com/podmix/ExampleUnitTest.kt
git commit -m "feat: add onSourceResult callback + yt-dlp + MixesDB to liveset pipeline"
```

---

## Task 5 : ViewModel — `sourceResults` StateFlow

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt`

- [ ] **Step 1 : Ajouter les imports nécessaires**

Dans `EpisodeDetailViewModel.kt`, ajouter aux imports :

```kotlin
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
```

- [ ] **Step 2 : Ajouter le StateFlow après `_detectStatus`**

Après la ligne `val detectStatus: StateFlow<String> = _detectStatus`, ajouter :

```kotlin
private val _sourceResults = MutableStateFlow<List<SourceResult>>(emptyList())
val sourceResults: StateFlow<List<SourceResult>> = _sourceResults.asStateFlow()
```

- [ ] **Step 3 : Modifier `detectTracks()` pour initialiser et alimenter le StateFlow**

Remplacer la fonction `detectTracks()` entière par :

```kotlin
private suspend fun detectTracks() {
    val ep = _episode.value ?: return

    val onStatus: (String) -> Unit = { _detectStatus.value = it }

    // Initialiser toutes les sources en PENDING
    _sourceResults.value = listOf(
        SourceResult("Description YouTube", SourceStatus.PENDING),
        SourceResult("yt-dlp", SourceStatus.PENDING),
        SourceResult("Mixcloud", SourceStatus.PENDING),
        SourceResult("MixesDB", SourceStatus.PENDING),
        SourceResult("Shazam/IA", SourceStatus.PENDING),
    )

    val onSourceResult: (SourceResult) -> Unit = { result ->
        _sourceResults.update { list ->
            list.map { if (it.source == result.source) result else it }
        }
    }

    try {
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
```

- [ ] **Step 4 : Vérifier la compilation**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt
git commit -m "feat: add sourceResults StateFlow to EpisodeDetailViewModel"
```

---

## Task 6 : Composant `TracklistDiagnosticBanner`

**Files:**
- Create: `app/src/main/java/com/podmix/ui/screens/episode/TracklistDiagnosticBanner.kt`

- [ ] **Step 1 : Créer le fichier**

```kotlin
package com.podmix.ui.screens.episode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import com.podmix.ui.theme.SurfaceSecondary
import com.podmix.ui.theme.TextPrimary
import com.podmix.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TracklistDiagnosticBanner(
    results: List<SourceResult>,
    modifier: Modifier = Modifier
) {
    if (results.isEmpty()) return

    val isDetecting = results.any { it.status == SourceStatus.RUNNING || it.status == SourceStatus.PENDING }
    val hasSuccess = results.any { it.status == SourceStatus.SUCCESS }
    val allDone = results.none { it.status == SourceStatus.RUNNING || it.status == SourceStatus.PENDING }

    var expanded by remember { mutableStateOf(true) }

    // Auto-collapse 3s après succès
    LaunchedEffect(allDone, hasSuccess) {
        if (allDone && hasSuccess) {
            delay(3_000)
            expanded = false
        }
    }
    // Réouvrir si nouvelle détection démarre
    LaunchedEffect(isDetecting) {
        if (isDetecting) expanded = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceSecondary)
    ) {
        // Header cliquable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sources de tracklist",
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Réduire" else "Développer",
                tint = TextSecondary
            )
        }

        // Contenu collapsible
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                results.forEach { result ->
                    SourceResultRow(result)
                }
            }
        }
    }
}

@Composable
private fun SourceResultRow(result: SourceResult) {
    val icon = when (result.status) {
        SourceStatus.PENDING -> "○"
        SourceStatus.RUNNING -> "⏳"
        SourceStatus.SUCCESS -> "✅"
        SourceStatus.FAILED  -> "❌"
        SourceStatus.SKIPPED -> "⏭"
    }
    val detail = when (result.status) {
        SourceStatus.SUCCESS -> "${result.trackCount} tracks   +${result.elapsedMs}ms"
        SourceStatus.FAILED  -> "0 tracks   +${result.elapsedMs}ms"
        SourceStatus.SKIPPED -> if (result.reason.isNotBlank()) "skippé (${result.reason})" else "skippé"
        SourceStatus.RUNNING -> "en cours..."
        SourceStatus.PENDING -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = result.source,
            color = TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = detail,
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
```

- [ ] **Step 2 : Vérifier la compilation**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/episode/TracklistDiagnosticBanner.kt
git commit -m "feat: add TracklistDiagnosticBanner collapsible Compose component"
```

---

## Task 7 : Intégration dans `EpisodeDetailScreen`

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailScreen.kt`

- [ ] **Step 1 : Ajouter la collecte du StateFlow**

Dans `EpisodeDetailScreen`, après la ligne `val isDetecting = detectStatus.isNotBlank()`, ajouter :

```kotlin
val sourceResults by viewModel.sourceResults.collectAsState()
```

- [ ] **Step 2 : Insérer le bandeau dans la LazyColumn**

Après le bloc `item { episode?.description... }` (après `Spacer(Modifier.height(8.dp))`) et avant `// Tracklist section`, insérer :

```kotlin
// Diagnostic banner
if (sourceResults.isNotEmpty()) {
    item {
        TracklistDiagnosticBanner(
            results = sourceResults,
            modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
        )
    }
}
```

- [ ] **Step 3 : Vérifier la compilation complète**

```
JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Temp" ./gradlew assembleDebug 2>&1 | tail -15
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 4 : Installer l'APK**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Attendu : `Success`

- [ ] **Step 5 : Vérifier manuellement**

Ouvrir un épisode liveset dans l'app. Observer :
- Le bandeau "Sources de tracklist" apparaît avec les 5 lignes en ○ PENDING
- Chaque source passe en ⏳ puis ✅/❌/⏭ au fur et à mesure
- Le bandeau se collapse 3s après que la première source réussit
- Un tap sur le header rouvre/ferme le bandeau

- [ ] **Step 6 : Commit final**

```bash
git add app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailScreen.kt
git commit -m "feat: integrate TracklistDiagnosticBanner into EpisodeDetailScreen"
```

---

## Self-Review

**Spec coverage :**
- ✅ yt-dlp `/chapters` endpoint serveur → Task 2
- ✅ MixesDB `/mixesdb` endpoint serveur → Task 2
- ✅ `SourceResult` data class → Task 1
- ✅ `onSourceResult` callback dans `TrackRepository` → Task 4
- ✅ Émission pour toutes les 5 sources (Description, yt-dlp, Mixcloud, MixesDB, Shazam) → Task 4
- ✅ `sourceResults` StateFlow dans ViewModel → Task 5
- ✅ `TracklistDiagnosticBanner` composant → Task 6
- ✅ Intégration dans `EpisodeDetailScreen` → Task 7
- ✅ Auto-collapse 3s après succès → Task 6
- ✅ Reste ouvert si échec total → Task 6 (allDone && hasSuccess condition)
- ✅ Icônes par statut (○⏳✅❌⏭) → Task 6
- ✅ Format ligne : source + statut + count + elapsed → Task 6
- ✅ Ordre pipeline : Desc → yt-dlp → Mixcloud → MixesDB → Shazam → Task 4

**Types :** `SourceResult.source` (String) utilisé comme clé de lookup dans le ViewModel — cohérent avec les noms définis dans TrackRepository ("Description YouTube", "yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA").

**Placeholders :** aucun TBD ou TODO subsistant dans le plan.
