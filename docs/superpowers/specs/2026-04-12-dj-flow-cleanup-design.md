# DJ Flow Cleanup — Design Spec

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Éliminer toutes les sources de pollution de la liste DJ et centraliser l'enrichissement audio+tracklist dans un seul service appelé une fois à l'import.

---

## Problèmes résolus

| Symptôme | Cause | Fix |
|---|---|---|
| Toute la liste réapparaît | `RefreshWorker` insère tous les sets Mixcloud de chaque DJ | Supprimer section DJ du Worker |
| LEDs restent orange | `detectMissingTracklists()` lance DDG → trouve page artiste, pas tracklist | Supprimer ces appels du `DjDetailViewModel` |
| Recherches YouTube à l'ouverture d'épisode | `detectTracks()` sans guard re-tourne à chaque ouverture | Ajouter colonne `enrichedAt` comme guard |
| Code sale dispersé | Résolution audio + tracklist dans 5 endroits différents | Centraliser dans `EpisodeEnrichmentService` |

---

## Principe central

**Le DJ est 100% user-curated.** Aucune insertion automatique en background. Seuls les podcasts RSS sont rafraîchis par `RefreshWorker`.

---

## Architecture

```
AddDjViewModel.importSelected()
    → episodeDao.insert(episode)          // immédiat
    → enrichmentService.enrich(episodeId) // background, non-bloquant
    → onComplete(djId)                    // navigation immédiate

EpisodeEnrichmentService (@Singleton)
    → résout audio : SC probe 1001TL → DDG SC → YT last resort
    → détecte tracklist : 1001TL direct URL → DDG 1001TL → YT desc/comments
    → episodeDao.update() → Room Flow → LED met à jour automatiquement
    → marque enrichedAt = System.currentTimeMillis()

DjDetailViewModel.init
    → charge DJ name uniquement
    → aucun appel réseau

EpisodeDetailViewModel.detectTracks()
    → guard : if (episode.enrichedAt != null) return
    → sinon : lance enrichissement (fallback pour épisodes anciens)
```

---

## Changements fichier par fichier

### 1. `EpisodeEntity.kt` — ajouter colonne `enrichedAt`

```kotlin
val enrichedAt: Long? = null  // null = pas encore enrichi, non-null = enrichissement terminé
```

### 2. `PodMixDatabase.kt` — migration v6→v7

```sql
ALTER TABLE episodes ADD COLUMN enrichedAt INTEGER
```

### 3. `EpisodeEnrichmentService.kt` — NOUVEAU fichier `@Singleton`

Responsabilité unique : enrichir un épisode (audio + tracklist) en background.

```kotlin
@Singleton
class EpisodeEnrichmentService @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val trackRepository: TrackRepository,
    private val artistPageScraper: ArtistPageScraper,
    private val youTubeStreamResolver: YouTubeStreamResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enrich(episodeId: Int, djName: String) {
        scope.launch {
            try {
                val ep = episodeDao.getById(episodeId) ?: return@launch
                if (ep.enrichedAt != null) return@launch  // déjà enrichi

                // Phase 1 : résoudre l'audio si manquant
                val resolved = resolveAudio(ep, djName)

                // Phase 2 : détecter la tracklist
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
                episodeDao.update(latest.copy(enrichedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.w("EnrichmentService", "Enrichment failed for $episodeId: ${e.message}")
            }
        }
    }

    private suspend fun resolveAudio(
        ep: EpisodeEntity,
        djName: String
    ) {
        // Déjà une source audio → rien à faire
        if (!ep.soundcloudTrackUrl.isNullOrBlank()
            || !ep.youtubeVideoId.isNullOrBlank()
            || ep.mixcloudKey != null) return

        var scUrl: String? = null
        var ytId: String? = null
        var mcKey: String? = null

        // 1. Probe page 1001TL si URL connue
        if (!ep.tracklistPageUrl.isNullOrBlank()) {
            val src = artistPageScraper.getMediaSourceFromTracklistPage(ep.tracklistPageUrl)
            scUrl = src?.soundcloudTrackUrl
            ytId = src?.youtubeVideoId
            mcKey = src?.mixcloudKey
        }

        // 2. DDG SoundCloud
        if (scUrl == null && ytId == null && mcKey == null) {
            scUrl = youTubeStreamResolver.searchFirstSoundCloudUrl("$djName ${ep.title}")
        }

        // 3. YouTube en dernier recours
        if (scUrl == null && ytId == null && mcKey == null) {
            ytId = youTubeStreamResolver.searchFirstVideoId("$djName ${ep.title}")
        }

        if (scUrl != null || ytId != null || mcKey != null) {
            episodeDao.update(ep.copy(
                soundcloudTrackUrl = scUrl ?: ep.soundcloudTrackUrl,
                youtubeVideoId = ytId ?: ep.youtubeVideoId,
                mixcloudKey = mcKey ?: ep.mixcloudKey
            ))
        }
    }
}
```

### 4. `AddDjViewModel.kt` — injecter et appeler `EpisodeEnrichmentService`

Dans `importSet`, après `episodeDao.insert(episode)` :
```kotlin
val episodeId = episodeDao.insert(episode).toInt()
enrichmentService.enrich(episodeId, djName)  // non-bloquant
```

`djName` est accessible via `_query.value.trim()` (nom saisi par l'utilisateur).

Supprimer `private val artistPageScraper` et `private val youTubeStreamResolver` du constructeur du ViewModel si plus utilisés directement.

### 5. `DjDetailViewModel.kt` — nettoyer

- Supprimer `resolveAudioForUnresolved()` et tout son code
- Supprimer `detectMissingTracklists()` et tout son code
- Supprimer `private val artistPageScraper` du constructeur
- Supprimer `private val youTubeStreamResolver` du constructeur
- Supprimer `private val trackRepository` du constructeur
- Supprimer `refresh()`, `_isRefreshing`, `isRefreshing` (remplacés par rien — plus de refresh manuel)
- `init` ne fait que : `_dj.value = djRepository.getDjById(djId)`

### 6. `DjDetailScreen.kt` — supprimer le bouton Refresh

- Supprimer le `IconButton` Refresh et le `CircularProgressIndicator` de la TopAppBar
- Supprimer `val isRefreshing by viewModel.isRefreshing.collectAsState()`

### 7. `RefreshWorker.kt` — supprimer la section DJ

Supprimer le bloc `// --- Refresh DJs (Mixcloud primary) ---` (lignes 113-158).
Garder uniquement le refresh des podcasts RSS.

### 8. `EpisodeDetailViewModel.kt` — guard sur `detectTracks()`

Remplacer la condition actuelle :
```kotlin
if (existingTracks == 0) {
    detectTracks()
}
```
par :
```kotlin
if (existingTracks == 0 && episode.enrichedAt == null) {
    detectTracks()
    // Marquer comme enrichi après détection
    episodeDao.update(episodeDao.getById(episodeId)!!.copy(enrichedAt = System.currentTimeMillis()))
}
```

---

## Ce qui NE change pas

- `SetMatcher`, `SoundCloudArtistScraper`, `ArtistPageScraper` — inchangés
- `PlayerController` — inchangé
- `EpisodeRow` LED logic — inchangée
- `AddDjScreen` UI — inchangée
- `TrackRepository.detectAndSaveTracks` — inchangé (sauf signature déjà étendue)

---

## Migrations DB

- v6 → v7 : `ALTER TABLE episodes ADD COLUMN enrichedAt INTEGER`
- Enregistrer `MIGRATION_6_7` dans `DatabaseModule`

---

## Résultat attendu

1. Import → épisode inséré immédiatement → enrichissement background → LED 🔴 → 🟠 → 🟢
2. Page DJ = lecture seule de la DB, aucun appel réseau au chargement
3. Refresh manuelle supprimée (plus de bouton, plus de logique)
4. `RefreshWorker` ne touche plus aux DJs
5. Ouverture d'épisode : si déjà enrichi (`enrichedAt != null`), aucune détection relancée
