# DJ Import & LED Flow — Design Spec

> **For agentic workers:** Use superpowers:executing-plans to implement this plan.

**Goal:** Réparer le flux sélection→import→LED sur la page DJ, avec résolution audio automatique en background.

---

## Problème actuel

`AddDjScreen` ligne 140 : `if (!item.isAlreadyImported && item.hasAudio)` — bloque toute sélection si SoundCloud n'a pas retourné d'URL directe. La sélection doit être indépendante de la disponibilité audio.

---

## Flux cible

```
1. AddDjScreen : recherche SC + 1001TL → liste tous les sets trouvés
2. Utilisateur coche les sets voulus (tout set non-importé est sélectionnable)
3. "Importer (N)" → sauvegarde immédiate en DB → navigation vers page DJ
4. Background : résolution audio + tracklist pour chaque épisode importé
5. Page DJ : LED réactif (🔴→🟠→🟢) mis à jour par les Flows DB
6. Tap sur 🟠 ou 🟢 → lecture immédiate
```

---

## Phase 1 — AddDjScreen (sélection)

### Règles
- Tout set non-encore-importé est **sélectionnable** (suppression du gate `hasAudio`)
- Pas de LED (dots couleur) sur cette page
- Checkbox + label source (SC / SC+TL / 1001TL)
- Sets déjà importés : grisés + non-cliquables (`isAlreadyImported = true`)

### Fichier à modifier
- `AddDjScreen.kt` : ligne 140 — supprimer `&& item.hasAudio`
- `AddDjScreen.kt` : `SetRow` — `clickable(enabled = !item.isAlreadyImported)` (supprimer `&& item.hasAudio`)

---

## Phase 2 — Import (AddDjViewModel)

### Comportement
- `importSelected` : pour chaque set sélectionné, sauvegarde `EpisodeEntity` en DB **immédiatement** (sans attendre résolution audio/tracklist)
- Champs initiaux : `soundcloudTrackUrl = set.soundcloudUrl`, `youtubeVideoId = set.youtubeVideoId`, le reste null
- Après insertion en DB → appelle `onComplete(djId)` pour naviguer vers page DJ
- La résolution audio + tracklist est déléguée à `DjDetailViewModel`

### Ce qui change
- Supprimer la résolution audio synchrone dans `importSet` (probe 1001TL, DDG SC, YT fallback)
- Supprimer le `downloadManager.startDownload` dans `importSelected`
- `importSet` devient : insérer l'épisode en DB avec les données brutes disponibles (SC URL ou YT ID si connus), retourner l'episodeId

---

## Phase 3 — Résolution automatique au chargement de la page DJ (DjDetailViewModel)

### Déclenchement
- Dans `init` de `DjDetailViewModel`, après chargement des épisodes, lancer silencieusement `resolveAudioForUnresolved()`
- Pas de spinner global, pas de bouton refresh
- Résolution par épisode en parallèle (`launch` indépendants)

### Algorithme de résolution audio (par épisode)

```
Si soundcloudTrackUrl != null → déjà résolu, skip
Si youtubeVideoId != null → déjà résolu, skip (YT est acceptable)
Si mixcloudKey != null → déjà résolu, skip

Sinon, si tracklistUrl connu (stocker dans EpisodeEntity.description temporairement ou champ dédié) :
  1. Probe page 1001TL → extrait SC URL / YT ID / Mixcloud key
  2. Si trouvé → episodeDao.update(episode.copy(soundcloudTrackUrl=... ou youtubeVideoId=...))

Si toujours rien :
  3. DDG SoundCloud : searchFirstSoundCloudUrl("$djName $episodeTitle")
  4. Si trouvé → episodeDao.update(episode.copy(soundcloudTrackUrl=...))

Si toujours rien :
  5. YouTube search : searchFirstVideoId("$djName $episodeTitle")
  6. Si trouvé → episodeDao.update(episode.copy(youtubeVideoId=...))

Si tout échoue → épisode reste sans source (🔴)
```

### Détection tracklist
- En parallèle de la résolution audio, pour chaque épisode sans tracklist :
  `trackRepository.detectAndSaveTracks(episodeId, ...)`
- Silencieux, pas de status affiché sur la page DJ

---

## Phase 4 — LED réactif (EpisodeRow)

### Logique LED (déjà implémentée dans EpisodeRow.kt)
```kotlin
val hasAudio = episode.audioUrl.isNotBlank()
    || !episode.youtubeVideoId.isNullOrBlank()
    || episode.mixcloudKey != null
    || !episode.soundcloudTrackUrl.isNullOrBlank()
    || episode.localAudioPath != null

val dotColor = when {
    !hasAudio -> Color(0xFFFF4444)      // 🔴 pas d'audio
    !hasTracklist -> Color(0xFFFF9800)  // 🟠 audio sans tracklist
    else -> Color(0xFF4CAF50)           // 🟢 audio + tracklist
}
```

### Réactivité
- `DjDetailViewModel.sets` est un `StateFlow` basé sur `episodeDao.getByPodcastId(djId)` (Flow Room)
- `episodeIdsWithTracks` est un `StateFlow` basé sur `trackDao.getEpisodeIdsWithTracks()` (Flow Room)
- Quand `episodeDao.update()` est appelé en background → Room émet → Flow met à jour → LED se met à jour automatiquement

---

## Phase 5 — Lecture (EpisodeDetailViewModel)

### Règles (déjà implémentées)
- `hasSource` inclut `soundcloudTrackUrl` → pas de recherche YouTube au tap
- 🟠 ou 🟢 → `playEpisode()` → SoundCloud HLS ou YouTube selon source disponible
- 🔴 → message "❌ Aucune source audio" (3s)

---

## Champ supplémentaire nécessaire : `tracklistUrl` sur `EpisodeEntity`

Pour que `DjDetailViewModel` puisse probing la page 1001TL après import, l'URL doit être persistée.

**Option retenue** : stocker `tracklistUrl` dans `EpisodeEntity.description` (champ existant, String?) comme prefixe `"1001TL:https://..."` si la description réelle est absente.

**Alternative plus propre** : ajouter colonne `tracklistPageUrl` dans `EpisodeEntity` + migration Room.

→ **Choisir l'alternative propre** : colonne `tracklistPageUrl String?` dans `EpisodeEntity`, migration Room version+1.

---

## Fichiers à modifier

| Fichier | Changement |
|---|---|
| `AddDjScreen.kt` | Supprimer gate `hasAudio` (2 endroits) |
| `AddDjViewModel.kt` | `importSet` = insert immédiat sans résolution audio |
| `EpisodeEntity.kt` | Ajouter colonne `tracklistPageUrl: String?` |
| `PodMixDatabase.kt` | Migration version+1 |
| `DjDetailViewModel.kt` | `init` → `resolveAudioForUnresolved()` en background |
| `DjRepository.kt` | Ou `DjDetailViewModel` directement : logique résolution audio |
| `EpisodeRow.kt` | Déjà OK |
| `EpisodeDetailViewModel.kt` | Déjà OK |

---

## Ce qui ne change pas
- Logique de merge SC + 1001TL dans `SetMatcher`
- `SoundCloudArtistScraper`, `ArtistPageScraper`
- `PlayerController` (résolution HLS SC déjà prioritaire)
- LED sur `EpisodeRow` (déjà correcte)
