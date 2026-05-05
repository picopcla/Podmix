# DJ Flow Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Éliminer la pollution de la liste DJ, centraliser l'enrichissement audio+tracklist dans un singleton, et nettoyer tous les ViewModels.

**Architecture:** `EpisodeEnrichmentService` (@Singleton, scope propre) est appelé une fois à l'import depuis `AddDjViewModel`. `DjDetailViewModel` devient read-only. `RefreshWorker` ne touche plus aux DJs.

**Tech Stack:** Kotlin, Room, Hilt, Coroutines (SupervisorJob), Android

---

## File Map

| Fichier | Action |
|---|---|
| `data/local/entity/EpisodeEntity.kt` | Ajouter `enrichedAt: Long?` |
| `data/local/PodMixDatabase.kt` | Version 6→7, MIGRATION_6_7 |
| `di/DatabaseModule.kt` | Enregistrer MIGRATION_6_7 |
| `service/EpisodeEnrichmentService.kt` | CRÉER — singleton d'enrichissement |
| `ui/screens/liveset/AddDjViewModel.kt` | Injecter service, appeler enrich(), retirer youTubeStreamResolver/trackRepository |
| `ui/screens/liveset/DjDetailViewModel.kt` | Supprimer tout le réseau, garder uniquement lecture DB |
| `ui/screens/liveset/DjDetailScreen.kt` | Supprimer bouton Refresh |
| `service/RefreshWorker.kt` | Supprimer section DJ Mixcloud |
| `ui/screens/episode/EpisodeDetailViewModel.kt` | Guard enrichedAt sur detectTracks() |

---

### Task 1 : Ajouter `enrichedAt` dans EpisodeEntity + migration DB v6→v7

**Files:**
- Modify: `app/src/main/java/com/podmix/data/local/entity/EpisodeEntity.kt`
- Modify: `app/src/main/java/com/podmix/data/local/PodMixDatabase.kt`
- Modify: `app/src/main/java/com/podmix/di/DatabaseModule.kt`

- [ ] **Step 1 : Ajouter `enrichedAt` dans EpisodeEntity**

Ajouter en dernier champ (après `tracklistPageUrl`) :

```kotlin
val tracklistPageUrl: String? = null,
val enrichedAt: Long? = null
```

Le fichier complet devient :

```kotlin
package com.podmix.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = PodcastEntity::class,
        parentColumns = ["id"],
        childColumns = ["podcastId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("podcastId")]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val podcastId: Int,
    val title: String,
    val audioUrl: String,
    val datePublished: Long? = null,
    val durationSeconds: Int = 0,
    val progressSeconds: Int = 0,
    val isListened: Boolean = false,
    val artworkUrl: String? = null,
    val episodeType: String = "podcast",
    val youtubeVideoId: String? = null,
    val guid: String? = null,
    val description: String? = null,
    val mixcloudKey: String? = null,
    val localAudioPath: String? = null,
    val soundcloudTrackUrl: String? = null,
    val tracklistPageUrl: String? = null,
    val enrichedAt: Long? = null
)
```

- [ ] **Step 2 : Bumper la version et ajouter MIGRATION_6_7 dans PodMixDatabase.kt**

```kotlin
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, TrackEntity::class],
    version = 7,
    exportSchema = false
)
abstract class PodMixDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun trackDao(): TrackDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN localAudioPath TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN soundcloudTrackUrl TEXT")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN tracklistPageUrl TEXT")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN enrichedAt INTEGER")
            }
        }
    }
}
```

- [ ] **Step 3 : Enregistrer MIGRATION_6_7 dans DatabaseModule.kt**

```kotlin
Room.databaseBuilder(context, PodMixDatabase::class.java, "podmix.db")
    .addMigrations(
        PodMixDatabase.MIGRATION_3_4,
        PodMixDatabase.MIGRATION_4_5,
        PodMixDatabase.MIGRATION_5_6,
        PodMixDatabase.MIGRATION_6_7
    )
    .fallbackToDestructiveMigration()
    .build()
```

- [ ] **Step 4 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/data/local/entity/EpisodeEntity.kt \
        app/src/main/java/com/podmix/data/local/PodMixDatabase.kt \
        app/src/main/java/com/podmix/di/DatabaseModule.kt
git commit -m "feat: add enrichedAt column to episodes (DB migration 6→7)"
```

---

### Task 2 : Créer EpisodeEnrichmentService

**Files:**
- Create: `app/src/main/java/com/podmix/service/EpisodeEnrichmentService.kt`

- [ ] **Step 1 : Créer le fichier**

```kotlin
package com.podmix.service

import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.repository.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpisodeEnrichmentService @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val trackRepository: TrackRepository,
    private val artistPageScraper: ArtistPageScraper,
    private val youTubeStreamResolver: YouTubeStreamResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "EnrichmentService"

    /** Lance l'enrichissement audio + tracklist en arrière-plan. Non-bloquant. */
    fun enrich(episodeId: Int, djName: String) {
        scope.launch {
            try {
                val ep = episodeDao.getById(episodeId) ?: return@launch
                if (ep.enrichedAt != null) {
                    Log.d(TAG, "Already enriched: $episodeId, skipping")
                    return@launch
                }

                // Phase 1 : résoudre l'audio si manquant
                resolveAudio(ep, djName)

                // Phase 2 : détecter la tracklist (relit l'épisode pour avoir l'audio résolu)
                val latest = episodeDao.getById(episodeId) ?: return@launch
                trackRepository.detectAndSaveTracks(
                    episodeId = episodeId,
                    description = latest.description,
                    episodeTitle = latest.title,
                    podcastName = djName,
                    episodeDurationSec = latest.durationSeconds,
                    isLiveSet = true,
                    youtubeVideoId = latest.youtubeVideoId,
                    tracklistPageUrl = latest.tracklistPageUrl
                )

                // Marquer comme enrichi
                val final = episodeDao.getById(episodeId) ?: return@launch
                episodeDao.update(final.copy(enrichedAt = System.currentTimeMillis()))
                Log.i(TAG, "Enrichment complete for '${ final.title}'")

            } catch (e: Exception) {
                Log.w(TAG, "Enrichment failed for $episodeId: ${e.message}")
            }
        }
    }

    private suspend fun resolveAudio(ep: EpisodeEntity, djName: String) {
        // Déjà une source audio → rien à faire
        if (!ep.soundcloudTrackUrl.isNullOrBlank()
            || !ep.youtubeVideoId.isNullOrBlank()
            || ep.mixcloudKey != null
            || ep.localAudioPath != null) return

        var scUrl: String? = null
        var ytId: String? = null
        var mcKey: String? = null

        // 1. Probe page 1001TL si URL connue
        if (!ep.tracklistPageUrl.isNullOrBlank()) {
            try {
                val src = artistPageScraper.getMediaSourceFromTracklistPage(ep.tracklistPageUrl)
                scUrl = src?.soundcloudTrackUrl
                ytId = src?.youtubeVideoId
                mcKey = src?.mixcloudKey
                Log.d(TAG, "1001TL probe '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
            } catch (e: Exception) {
                Log.w(TAG, "1001TL probe failed: ${e.message}")
            }
        }

        // 2. DDG SoundCloud
        if (scUrl == null && ytId == null && mcKey == null) {
            try {
                scUrl = youTubeStreamResolver.searchFirstSoundCloudUrl("$djName ${ep.title}")
                Log.d(TAG, "DDG SC '${ep.title}': $scUrl")
            } catch (e: Exception) {
                Log.w(TAG, "DDG SC failed: ${e.message}")
            }
        }

        // 3. YouTube en dernier recours
        if (scUrl == null && ytId == null && mcKey == null) {
            try {
                ytId = youTubeStreamResolver.searchFirstVideoId("$djName ${ep.title}")
                Log.d(TAG, "YT fallback '${ep.title}': $ytId")
            } catch (e: Exception) {
                Log.w(TAG, "YT search failed: ${e.message}")
            }
        }

        if (scUrl != null || ytId != null || mcKey != null) {
            episodeDao.update(ep.copy(
                soundcloudTrackUrl = scUrl ?: ep.soundcloudTrackUrl,
                youtubeVideoId = ytId ?: ep.youtubeVideoId,
                mixcloudKey = mcKey ?: ep.mixcloudKey
            ))
            Log.i(TAG, "Audio resolved for '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
        } else {
            Log.w(TAG, "No audio source found for '${ep.title}'")
        }
    }
}
```

- [ ] **Step 2 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Attendu : `BUILD SUCCESSFUL`

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/podmix/service/EpisodeEnrichmentService.kt
git commit -m "feat: add EpisodeEnrichmentService — singleton for background audio+tracklist enrichment"
```

---

### Task 3 : Brancher EpisodeEnrichmentService dans AddDjViewModel

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1 : Lire le fichier actuel**

```bash
# Lire le constructeur et importSet
```

- [ ] **Step 2 : Modifier le constructeur**

Remplacer le constructeur par (supprimer `youTubeStreamResolver` et `trackRepository`, ajouter `enrichmentService`) :

```kotlin
@HiltViewModel
class AddDjViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistPageScraper: ArtistPageScraper,
    private val soundCloudArtistScraper: SoundCloudArtistScraper,
    private val episodeDao: EpisodeDao,
    private val djRepository: DjRepository,
    private val enrichmentService: EpisodeEnrichmentService,
    private val okHttpClient: OkHttpClient
) : ViewModel() {
```

- [ ] **Step 3 : Modifier `importSet` pour appeler `enrich()`**

Après `episodeDao.insert(episode)`, ajouter :

```kotlin
val episodeId = episodeDao.insert(episode).toInt()
enrichmentService.enrich(episodeId, _query.value.trim())
```

Le `importSet` complet devient :

```kotlin
private suspend fun importSet(djId: Int, set: DiscoveredSet) {
    if (episodeDao.getByTitleAndPodcastId(set.title, djId) != null) {
        Log.i(TAG, "Skipping duplicate (title): ${set.title}")
        return
    }
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
    val episodeId = episodeDao.insert(episode).toInt()
    enrichmentService.enrich(episodeId, _query.value.trim())

    if (set.youtubeVideoId != null) alreadyImportedVideoIds = alreadyImportedVideoIds + set.youtubeVideoId
    if (set.soundcloudUrl != null) alreadyImportedScUrls = alreadyImportedScUrls + set.soundcloudUrl
    alreadyImportedTitles = alreadyImportedTitles + set.title
    rawSets = rawSets.map { item ->
        if (item.set.id == set.id) item.copy(isAlreadyImported = true, isSelected = false) else item
    }
    applySortAndEmit()
}
```

- [ ] **Step 4 : Supprimer les imports devenus inutilisés**

Vérifier et supprimer dans les imports :
- `import com.podmix.data.repository.TrackRepository` (si plus utilisé)
- `import com.podmix.service.YouTubeStreamResolver` ou son FQN (si plus utilisé)

- [ ] **Step 5 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 6 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt
git commit -m "feat: call EpisodeEnrichmentService at import, remove direct audio resolution from AddDjViewModel"
```

---

### Task 4 : Nettoyer DjDetailViewModel — supprimer tout le réseau

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt`

- [ ] **Step 1 : Lire le fichier actuel**

Lire `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt`

- [ ] **Step 2 : Réécrire le fichier entier**

Le nouveau `DjDetailViewModel.kt` — read-only, aucun réseau :

```kotlin
package com.podmix.ui.screens.liveset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.repository.DjRepository
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.service.DownloadState
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DjDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val djRepository: DjRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    playerController: PlayerController,
    downloadManager: EpisodeDownloadManager
) : ViewModel() {

    val playerState = playerController.playerState
    val downloadStates: StateFlow<Map<Int, DownloadState>> = downloadManager.states

    val episodeIdsWithTracks: StateFlow<Set<Int>> = trackDao.getEpisodeIdsWithTracks()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val djId: Int = savedStateHandle["djId"] ?: 0

    private val _dj = MutableStateFlow<Podcast?>(null)
    val dj: StateFlow<Podcast?> = _dj

    val sets: StateFlow<List<Episode>> = episodeDao.getByPodcastId(djId)
        .map { list ->
            list.map { e ->
                Episode(
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
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            _dj.value = djRepository.getDjById(djId)
        }
    }

    fun deleteEpisode(episodeId: Int) {
        viewModelScope.launch {
            episodeDao.delete(episodeId)
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            podcastDao.delete(djId)
            onDone()
        }
    }
}
```

- [ ] **Step 3 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 4 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt
git commit -m "refactor: DjDetailViewModel is now read-only — remove all background network logic"
```

---

### Task 5 : Nettoyer DjDetailScreen — supprimer le bouton Refresh

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailScreen.kt`

- [ ] **Step 1 : Lire le fichier**

Lire `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailScreen.kt`

- [ ] **Step 2 : Supprimer les lignes liées à `isRefreshing` et au bouton Refresh**

Supprimer :
```kotlin
val isRefreshing by viewModel.isRefreshing.collectAsState()
```

Dans la TopAppBar `actions`, supprimer :
```kotlin
IconButton(onClick = { onAddMore(viewModel.djId) }) {
    Icon(Icons.Default.Add, contentDescription = "Ajouter des sets", tint = AccentPrimary)
}
IconButton(onClick = { viewModel.delete(onBack) }) {
    Icon(Icons.Default.Delete, "Supprimer", tint = TextPrimary)
}
if (isRefreshing) {
    CircularProgressIndicator(
        color = AccentPrimary,
        modifier = Modifier.size(20.dp).padding(end = 8.dp)
    )
} else {
    IconButton(onClick = { viewModel.refresh() }) {
        Icon(Icons.Default.Refresh, "Refresh", tint = AccentPrimary)
    }
}
```

Remplacer par (garder Add et Delete, supprimer Refresh) :
```kotlin
IconButton(onClick = { onAddMore(viewModel.djId) }) {
    Icon(Icons.Default.Add, contentDescription = "Ajouter des sets", tint = AccentPrimary)
}
IconButton(onClick = { viewModel.delete(onBack) }) {
    Icon(Icons.Default.Delete, "Supprimer", tint = TextPrimary)
}
```

- [ ] **Step 3 : Supprimer les imports devenus inutilisés**

Si après suppression `Icons.Default.Refresh` et `CircularProgressIndicator` ne sont plus utilisés, supprimer leurs imports.

- [ ] **Step 4 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/DjDetailScreen.kt
git commit -m "refactor: remove Refresh button from DjDetailScreen — DJ page is read-only"
```

---

### Task 6 : Supprimer la section DJ du RefreshWorker

**Files:**
- Modify: `app/src/main/java/com/podmix/service/RefreshWorker.kt`

- [ ] **Step 1 : Lire le fichier**

Lire `app/src/main/java/com/podmix/service/RefreshWorker.kt`

- [ ] **Step 2 : Supprimer le bloc DJ Mixcloud**

Supprimer tout le bloc (environ lignes 113-158) :
```kotlin
// --- Refresh DJs (Mixcloud primary) ---
try {
    val djs = podcastDao.getByTypeSuspend("dj")
    for (dj in djs) {
        try {
            val response = mixcloudApi.search(dj.name, "cloudcast")
            ...
        }
    }
} catch (e: Exception) {
    Log.e(TAG, "Failed to load DJs: ${e.message}")
}
```

- [ ] **Step 3 : Supprimer les membres devenus inutiles**

Si `mixcloudApi` n'est plus utilisé dans `doWork()`, supprimer :
- La variable `val mixcloudApi = createMixcloudApi()` dans `doWork()`
- La méthode privée `createMixcloudApi()` en bas du fichier
- L'import `com.podmix.data.api.MixcloudApi` si plus utilisé

- [ ] **Step 4 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/service/RefreshWorker.kt
git commit -m "fix: remove DJ auto-import from RefreshWorker — DJs are user-curated only"
```

---

### Task 7 : Guard `enrichedAt` dans EpisodeDetailViewModel

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt`

- [ ] **Step 1 : Lire le fichier**

Lire `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt`

- [ ] **Step 2 : Modifier le guard dans `init`**

Trouver ce bloc dans `init` :

```kotlin
val podType = _podcast.value?.type
if (podType != "emission" && podType != "radio") {
    val existingTracks = trackRepository.getTracksCountForEpisode(episodeId)
    if (existingTracks == 0) {
        detectTracks()
    }
}
```

Le remplacer par :

```kotlin
val podType = _podcast.value?.type
if (podType != "emission" && podType != "radio") {
    val existingTracks = trackRepository.getTracksCountForEpisode(episodeId)
    // Ne pas relancer si déjà enrichi (enrichedAt != null) OU si des tracks existent déjà
    if (existingTracks == 0 && e?.enrichedAt == null) {
        detectTracks()
    }
}
```

Note : `e` est la variable `EpisodeEntity` déjà chargée plus haut dans le même `init` block — elle est accessible ici.

- [ ] **Step 3 : Build + install**

```bash
cd /C/APP/Podmix && ./gradlew installDebug 2>&1 | tail -12
```

Attendu : `BUILD SUCCESSFUL` + `Installed on 1 device.`

- [ ] **Step 4 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt
git commit -m "fix: skip detectTracks() if episode already enriched (guard on enrichedAt)"
```

---

## Self-Review

**Spec coverage :**
- ✅ RefreshWorker DJ supprimé (Task 6)
- ✅ EpisodeEnrichmentService créé avec phase audio + tracklist (Task 2)
- ✅ Appelé à l'import dans AddDjViewModel (Task 3)
- ✅ DjDetailViewModel read-only (Task 4)
- ✅ Bouton Refresh supprimé (Task 5)
- ✅ Guard enrichedAt dans EpisodeDetailViewModel (Task 7)
- ✅ Migration enrichedAt v6→v7 (Task 1)

**Cohérence des types :**
- `EpisodeEnrichmentService.enrich(episodeId: Int, djName: String)` — défini Task 2, appelé Task 3
- `episodeDao.getById(episodeId): EpisodeEntity?` — existant
- `trackRepository.detectAndSaveTracks(episodeId, description, episodeTitle, podcastName, episodeDurationSec, isLiveSet, youtubeVideoId, tracklistPageUrl)` — signature complète avec `tracklistPageUrl` ajouté précédemment
- `e?.enrichedAt` dans Task 7 référence `val e = episodeDao.getById(episodeId)` déjà dans scope

**Placeholders :** Aucun.
