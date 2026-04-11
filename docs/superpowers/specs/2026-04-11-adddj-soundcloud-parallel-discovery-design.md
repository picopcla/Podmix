# Design : AddDj — Découverte parallèle SoundCloud + 1001TL

**Date :** 2026-04-11
**Statut :** Approuvé

## Contexte

Le flux de découverte actuel part de 1001TL comme source principale et cherche SoundCloud en fallback. Problème : des sets existent sur SoundCloud sans être liés sur 1001TL, et l'audio SoundCloud est supérieur à YouTube (pas de throttle, HLS propre). L'objectif est de faire de SoundCloud une source de découverte à part entière, en parallèle de 1001TL.

## Architecture

Trois nouveaux composants, deux modifiés :

```
AddDjViewModel
    ├── ArtistPageScraper (existant)   → scrape page artiste 1001TL
    ├── SoundCloudArtistScraper (NEW)  → scrape page artiste SoundCloud
    ├── SetMatcher (NEW)               → fuzzy merge des deux listes
    └── importSet() modifié            → utilise DiscoveredSet
```

### Nouveau modèle central : `DiscoveredSet`

Remplace `ArtistSet` dans `AddDjViewModel` et `ArtistSetUiItem`.

```kotlin
data class DiscoveredSet(
    val title: String,            // titre canonique (1001TL préféré, sinon SC)
    val date: String,
    val viewCount: Int,           // depuis 1001TL, 0 si SC-only
    val soundcloudUrl: String?,   // depuis SC artist page
    val tracklistUrl: String?,    // depuis 1001TL (pour scrape tracklist)
    val youtubeVideoId: String?,  // depuis 1001TL artist page
    val mixcloudKey: String?      // depuis 1001TL artist page
)
```

## Flow de découverte

`AddDjViewModel.search()` lance les deux scrapers en parallèle via `async/await` :

```
search(djName)
  ├── async: DDG site:1001tracklists.com/dj → scrapeArtistSets() → List<ArtistSet>
  ├── async: DDG site:soundcloud.com → SoundCloudArtistScraper.scrapeTracks() → List<ScSet>
  └── awaitBoth → SetMatcher.merge(tlSets, scSets) → List<DiscoveredSet>
```

### SoundCloudArtistScraper (nouveau fichier)

- Trouve le slug via DDG `site:soundcloud.com <nom DJ>` → premier résultat = page artiste
- Charge `soundcloud.com/<slug>` via WebView (SC est JS-rendered)
- JS extrait les tracks visibles : titre, URL SC (`soundcloud.com/artist/track`), date
- Limite : ~20 tracks rendus initialement, pas d'infinite scroll
- Timeout : 15s

### SetMatcher.merge() (nouveau fichier ou objet dans ViewModel)

Algorithme de fusion :

1. Normaliser les titres : lowercase, supprimer ponctuation, mots parasites (`live`, `set`, `mix`, `dj`, `@`, `-`, `festival`, `presents`, `radio`, `show`, `episode`)
2. Pour chaque set SC, trouver le meilleur match 1001TL par score de mots communs
3. Seuil : ≥50% de mots communs = même set → fusionner en un `DiscoveredSet`
4. Sets SC sans match 1001TL → `DiscoveredSet` avec `tracklistUrl = null`
5. Sets 1001TL sans match SC → `DiscoveredSet` avec `soundcloudUrl = null`

## Flow d'import par set

### Résolution audio (priorité décroissante)

```
1. soundcloudUrl présent          → SC (priorité absolue)
2. youtubeVideoId présent         → YouTube
3. mixcloudKey présent            → Mixcloud
4. Aucun source                   → DDG site:soundcloud.com buildSoundCloudQuery(djName, title)
5. Toujours rien                  → skip silencieux
```

### Résolution tracklist (priorité décroissante)

```
1. tracklistUrl présent           → scrape 1001TL via WebView (TracklistWebScraper, existant)
2. Pas de 1001TL + youtubeVideoId → YouTube description + commentaires (pipeline existant)
3. Rien                           → épisode importé sans tracklist
```

Pas de recherche DDG 1001TL par set (trop lent pour N sets). Les sets SC-only sans YouTube connu n'auront pas de tracklist.

## Gestion des erreurs

| Cas | Comportement |
|-----|--------------|
| SC scraper timeout (15s) | Continuer avec 1001TL seul |
| 1001TL scraper timeout (15s) | Continuer avec SC seul |
| Les deux échouent | Message "Artiste non trouvé" |
| Slug SC introuvable via DDG | Continuer sans SC (comportement actuel) |
| SoundCloud ne charge que ~20 tracks | Accepté — pas d'infinite scroll |

L'UI affiche un badge de source (`SC` / `1001TL` / `SC+1001TL`) sur chaque set pour permettre à l'utilisateur de détecter les faux positifs de fuzzy matching.

## Fichiers impactés

| Fichier | Action |
|---------|--------|
| `service/SoundCloudArtistScraper.kt` | Nouveau |
| `service/SetMatcher.kt` | Nouveau |
| `ui/screens/liveset/AddDjViewModel.kt` | Modifié (search, importSet, ArtistSetUiItem → DiscoveredSet) |
| `ui/screens/liveset/AddDjScreen.kt` | Modifié (badge source, DiscoveredSet) |
| `service/ArtistPageScraper.kt` | Non modifié |
| `data/repository/TrackRepository.kt` | Non modifié |
