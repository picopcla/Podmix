# DJ Import & LED Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Réparer la sélection des sets, simplifier l'import (insert immédiat en DB), et résoudre l'audio en background au chargement de la page DJ.

**Architecture:** L'import devient une simple insertion DB (rapide, sans résolution réseau). La résolution audio (SC → DDG SC → YT) se fait dans `DjDetailViewModel.init` pour tous les épisodes sans source. Le LED réactif fonctionne via les Flows Room existants.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, Coroutines (supervisorScope)

---

## File Map

| Fichier | Rôle du changement |
|---|---|
| `data/local/entity/EpisodeEntity.kt` | Ajouter colonne `tracklistPageUrl: String?` |
| `data/local/PodMixDatabase.kt` | Version 5→6, MIGRATION_5_6 |
| `ui/screens/liveset/AddDjScreen.kt` | Supprimer gate `hasAudio` sur la sélection |
| `ui/screens/liveset/AddDjViewModel.kt` | `importSet` = insert immédiat, supprimer résolution audio |
| `ui/screens/liveset/DjDetailViewModel.kt` | `init` → `resolveAudioForUnresolved()` en background |

---

### Task 1 : Ajouter `tracklistPageUrl` dans EpisodeEntity + migration DB

**Files:**
- Modify: `app/src/main/java/com/podmix/data/local/entity/EpisodeEntity.kt`
- Modify: `app/src/main/java/com/podmix/data/local/PodMixDatabase.kt`

- [ ] **Step 1 : Ajouter le champ dans EpisodeEntity**

Remplacer le fichier `EpisodeEntity.kt` entier :

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
    val tracklistPageUrl: String? = null
)
```

- [ ] **Step 2 : Bumper la version DB et ajouter la migration dans PodMixDatabase.kt**

```kotlin
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, TrackEntity::class],
    version = 6,
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
    }
}
```

- [ ] **Step 3 : Enregistrer la migration dans le DatabaseModule**

Chercher `DatabaseModule.kt` et ajouter `MIGRATION_5_6` à la liste des migrations :

```
Glob: **/di/DatabaseModule.kt
```

Dans ce fichier, la ligne `.addMigrations(MIGRATION_3_4, MIGRATION_4_5)` devient :
```kotlin
.addMigrations(
    PodMixDatabase.MIGRATION_3_4,
    PodMixDatabase.MIGRATION_4_5,
    PodMixDatabase.MIGRATION_5_6
)
```

- [ ] **Step 4 : Build pour vérifier**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Attendu : `BUILD SUCCESSFUL` (ou seulement des warnings)

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/data/local/entity/EpisodeEntity.kt \
        app/src/main/java/com/podmix/data/local/PodMixDatabase.kt \
        app/src/main/java/com/podmix/di/DatabaseModule.kt
git commit -m "feat: add tracklistPageUrl column to episodes (DB migration 5→6)"
```

---

### Task 2 : Supprimer le gate `hasAudio` dans AddDjScreen

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt`

- [ ] **Step 1 : Corriger l'onClick dans le LazyColumn**

Ligne ~140, remplacer :
```kotlin
onClick = { if (!item.isAlreadyImported && item.hasAudio) viewModel.toggleSelection(item.set.id) }
```
par :
```kotlin
onClick = { if (!item.isAlreadyImported) viewModel.toggleSelection(item.set.id) }
```

- [ ] **Step 2 : Corriger le `clickable` dans SetRow**

Dans la fonction `SetRow`, remplacer :
```kotlin
.clickable(enabled = !item.isAlreadyImported && item.hasAudio, onClick = onClick)
```
par :
```kotlin
.clickable(enabled = !item.isAlreadyImported, onClick = onClick)
```

- [ ] **Step 3 : Supprimer `hasAudio` et `hasTracklist` de `DiscoveredSetUiItem` si plus utilisés**

Ces propriétés ne servent plus dans AddDjScreen (dots supprimés). Elles peuvent rester dans le ViewModel pour usage futur — ne pas les supprimer.

- [ ] **Step 4 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjScreen.kt
git commit -m "fix: allow selecting any non-imported set regardless of audio availability"
```

---

### Task 3 : Simplifier `importSet` dans AddDjViewModel

L'import doit juste insérer l'épisode en DB avec les données brutes connues, sans résolution réseau.

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt`

- [ ] **Step 1 : Simplifier `importSet`**

Remplacer la méthode `importSet` entière par :

```kotlin
private suspend fun importSet(djId: Int, set: DiscoveredSet) {
    // Dedup
    if (set.soundcloudUrl != null) {
        // pas de query dédiée — on utilise le title
    }
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
    episodeDao.insert(episode)

    // Mise à jour du dedup local
    if (set.youtubeVideoId != null) alreadyImportedVideoIds = alreadyImportedVideoIds + set.youtubeVideoId
    if (set.soundcloudUrl != null) alreadyImportedScUrls = alreadyImportedScUrls + set.soundcloudUrl
    alreadyImportedTitles = alreadyImportedTitles + set.title
    rawSets = rawSets.map { item ->
        if (item.set.id == set.id) item.copy(isAlreadyImported = true, isSelected = false) else item
    }
    applySortAndEmit()
}
```

- [ ] **Step 2 : Simplifier `importSelected` — supprimer le downloadManager et l'importProgress**

Remplacer `importSelected` entier par :

```kotlin
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
```

- [ ] **Step 3 : Nettoyer les imports inutilisés**

Supprimer dans les imports de `AddDjViewModel.kt` :
- `import com.podmix.service.EpisodeDownloadManager` (si `downloadManager` n'est plus utilisé)
- `import com.podmix.domain.model.SourceResult`
- `import com.podmix.domain.model.SourceStatus`

Vérifier que `_importProgress`, `_sourceResults`, `importProgress`, `sourceResults` peuvent être supprimés de la classe si plus utilisés depuis le Screen.

> Note : `AddDjScreen.kt` ne collecte plus `importProgress` ni `sourceResults` depuis la session précédente — ils peuvent être retirés du ViewModel également.

- [ ] **Step 4 : Retirer `downloadManager` du constructeur Hilt si plus utilisé**

Supprimer le paramètre `private val downloadManager: EpisodeDownloadManager` du constructeur si plus référencé dans la classe.

- [ ] **Step 5 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | grep -E "error:|BUILD"
```

Si erreur de compilation sur des imports manquants ou propriétés non résolues — corriger avant de continuer.

- [ ] **Step 6 : Commit**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/AddDjViewModel.kt
git commit -m "feat: importSet inserts episode immediately, audio resolution delegated to DjDetailViewModel"
```

---

### Task 4 : Résolution audio automatique dans DjDetailViewModel

Au chargement de la page DJ, résoudre silencieusement en background les épisodes sans source audio.

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt`

- [ ] **Step 1 : Ajouter `ArtistPageScraper` et `YouTubeStreamResolver` au constructeur**

```kotlin
@HiltViewModel
class DjDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val djRepository: DjRepository,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    playerController: PlayerController,
    downloadManager: EpisodeDownloadManager,
    private val artistPageScraper: com.podmix.service.ArtistPageScraper,
    private val youTubeStreamResolver: com.podmix.service.YouTubeStreamResolver
) : ViewModel() {
```

- [ ] **Step 2 : Appeler `resolveAudioForUnresolved()` dans `init`**

```kotlin
init {
    viewModelScope.launch {
        _dj.value = djRepository.getDjById(djId)
        resolveAudioForUnresolved()
    }
}
```

- [ ] **Step 3 : Implémenter `resolveAudioForUnresolved()`**

```kotlin
private fun resolveAudioForUnresolved() {
    viewModelScope.launch {
        val episodes = episodeDao.getByPodcastIdSuspend(djId)
        val unresolved = episodes.filter { ep ->
            ep.soundcloudTrackUrl.isNullOrBlank()
                && ep.youtubeVideoId.isNullOrBlank()
                && ep.mixcloudKey.isNullOrBlank()
                && ep.localAudioPath == null
        }
        if (unresolved.isEmpty()) return@launch
        Log.d("DjDetailVM", "Resolving audio for ${unresolved.size} unresolved episodes")

        // Résolution en parallèle, échec isolé par épisode
        kotlinx.coroutines.supervisorScope {
            unresolved.forEach { ep ->
                launch {
                    try {
                        resolveAudioForEpisode(ep)
                    } catch (e: Exception) {
                        Log.w("DjDetailVM", "Audio resolution failed for '${ep.title}': ${e.message}")
                    }
                }
            }
        }
    }
}

private suspend fun resolveAudioForEpisode(ep: com.podmix.data.local.entity.EpisodeEntity) {
    var scUrl: String? = null
    var ytId: String? = null
    var mcKey: String? = null

    // 1. Probe page 1001TL si URL connue
    if (!ep.tracklistPageUrl.isNullOrBlank()) {
        val src = artistPageScraper.getMediaSourceFromTracklistPage(ep.tracklistPageUrl)
        scUrl = src?.soundcloudTrackUrl
        ytId = src?.youtubeVideoId
        mcKey = src?.mixcloudKey
        Log.d("DjDetailVM", "1001TL probe '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
    }

    // 2. DDG SoundCloud
    if (scUrl == null && ytId == null && mcKey == null) {
        val djName = _dj.value?.name ?: ""
        scUrl = youTubeStreamResolver.searchFirstSoundCloudUrl("$djName ${ep.title}")
        Log.d("DjDetailVM", "DDG SC '${ep.title}': $scUrl")
    }

    // 3. YouTube en dernier recours
    if (scUrl == null && ytId == null && mcKey == null) {
        val djName = _dj.value?.name ?: ""
        ytId = youTubeStreamResolver.searchFirstVideoId("$djName ${ep.title}")
        Log.d("DjDetailVM", "YT fallback '${ep.title}': $ytId")
    }

    if (scUrl != null || ytId != null || mcKey != null) {
        episodeDao.update(ep.copy(
            soundcloudTrackUrl = scUrl ?: ep.soundcloudTrackUrl,
            youtubeVideoId = ytId ?: ep.youtubeVideoId,
            mixcloudKey = mcKey ?: ep.mixcloudKey
        ))
        Log.i("DjDetailVM", "Resolved '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
    } else {
        Log.w("DjDetailVM", "No audio source found for '${ep.title}'")
    }
}
```

- [ ] **Step 4 : Supprimer l'ancienne méthode `refresh()` et `_isRefreshing`**

La méthode `refresh()` et le state `_isRefreshing`/`isRefreshing` ne sont plus utilisés (le bouton Refresh a été supprimé de la TopAppBar lors d'une session précédente). Les retirer si c'est le cas.

> Vérifier d'abord dans `DjDetailScreen.kt` que `isRefreshing` n'est plus collecté. Si c'est encore là, laisser pour éviter de casser la compilation.

- [ ] **Step 5 : Build complet + install**

```bash
cd /C/APP/Podmix && ./gradlew installDebug 2>&1 | tail -20
```

Attendu : `BUILD SUCCESSFUL` + `Installed on 1 device.`

- [ ] **Step 6 : Commit final**

```bash
git add app/src/main/java/com/podmix/ui/screens/liveset/DjDetailViewModel.kt
git commit -m "feat: auto-resolve audio in background when DJ page loads (SC→DDG→YT)"
```

---

## Self-Review

**Spec coverage :**
- ✅ Sélection libre (Task 2)
- ✅ Import immédiat en DB (Task 3)
- ✅ Navigation vers page DJ après import (déjà géré par `onComplete(djId)`)
- ✅ `tracklistPageUrl` persisté (Task 1 + Task 3)
- ✅ Résolution audio background (Task 4)
- ✅ LED réactif via Flow Room (déjà implémenté dans `EpisodeRow.kt`)
- ✅ Lecture immédiate si audio présent (déjà implémenté dans `EpisodeDetailViewModel`)
- ✅ Pas de LED sur AddDjScreen (Task 2 + suppression déjà faite)

**Placeholders :** Aucun.

**Cohérence des types :**
- `EpisodeEntity.tracklistPageUrl: String?` défini en Task 1, utilisé en Task 3 (`set.tracklistUrl`) et Task 4 (`ep.tracklistPageUrl`)
- `DiscoveredSet.tracklistUrl: String?` — champ existant dans le ViewModel
- `ArtistPageScraper.getMediaSourceFromTracklistPage(url: String): TracklistMediaSource?` — méthode existante
- `YouTubeStreamResolver.searchFirstSoundCloudUrl(query: String): String?` — méthode existante
- `YouTubeStreamResolver.searchFirstVideoId(query: String): String?` — méthode existante
