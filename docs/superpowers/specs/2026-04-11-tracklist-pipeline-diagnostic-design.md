# Design : Pipeline Tracklist étendu + Bandeau Diagnostic

**Date :** 2026-04-11  
**Scope :** Ajout yt-dlp (chapters YouTube) + MixesDB + bandeau diagnostic collapsible dans l'app Android

---

## 1. Objectif

Étendre le pipeline de détection de tracklist liveset avec deux nouvelles sources (yt-dlp, MixesDB) et exposer l'état de chaque source dans un bandeau collapsible persistant dans l'écran épisode.

---

## 2. Data Model

### `SourceResult` (nouveau fichier : `domain/model/SourceResult.kt`)

```kotlin
enum class SourceStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class SourceResult(
    val source: String,       // "Description YouTube", "yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA"
    val status: SourceStatus,
    val trackCount: Int = 0,
    val elapsedMs: Long = 0,
    val reason: String = ""   // ex: "pas de chapters", "serveur hors ligne"
)
```

### Callback ajouté à `detectAndSaveTracks`

```kotlin
onSourceResult: ((SourceResult) -> Unit)? = null
```

Chaque source émet son `SourceResult` dès qu'elle termine, sans attendre la fin du pipeline.

---

## 3. Pipeline liveset (ordre priorité)

| # | Source | Implémentation | Durée | Serveur requis |
|---|--------|---------------|-------|----------------|
| 1 | Description YouTube | `TracklistService.detect()` — Android natif | ~0ms | non |
| 2 | yt-dlp chapters | `GET /chapters?video_id=XYZ` → serveur local | ~2s | oui |
| 3 | Mixcloud API | `MixcloudApi` — Android natif | ~1s | non |
| 4 | MixesDB | `GET /mixesdb?q=QUERY` → serveur local | ~1-3s | oui |
| 5 | Shazam/IA | `POST /analyze` → serveur local | 3-5 min | oui |

**Règle :** dès qu'une source retourne ≥3 tracks, les suivantes sont marquées SKIPPED et le pipeline s'arrête.  
**Sources 2, 4, 5** : skippées si `shazamServerUp == false` (health check existant réutilisé).

---

## 4. Serveur local (`audio_timestamp_server.py`)

### Endpoint `/chapters` (nouveau)

```
GET /chapters?video_id=dQw4w9WgXcQ
```

Implémentation :
```python
import yt_dlp

def get_youtube_chapters(video_id: str) -> list[dict]:
    with yt_dlp.YoutubeDL({'quiet': True, 'skip_download': True}) as ydl:
        info = ydl.extract_info(f"https://youtube.com/watch?v={video_id}", download=False)
        chapters = info.get('chapters') or []
        return [{"title": c["title"], "startTimeSec": c["start_time"]} for c in chapters]
```

Retourne `[]` si pas de chapters (pas d'erreur).

### Endpoint `/mixesdb` (nouveau)

```
GET /mixesdb?q=KOROLOVA+Snow+Attack
```

Implémentation :
1. `GET https://www.mixesdb.com/api.php?action=query&list=search&srsearch=QUERY&format=json` → récupère `pageid`
2. `GET https://www.mixesdb.com/api.php?action=parse&pageid=ID&prop=wikitext&format=json` → extrait wikitext
3. Parse wikitext pour extraire les lignes `| artist || title || startTime`

Retourne `[]` si pas de résultat, parse échoue, ou format wikitext inattendu (le format MixesDB n'est pas documenté officiellement — le parser doit être défensif).

---

## 5. ViewModel (`EpisodeDetailViewModel`)

```kotlin
private val _sourceResults = MutableStateFlow<List<SourceResult>>(emptyList())
val sourceResults: StateFlow<List<SourceResult>> = _sourceResults.asStateFlow()
```

À l'appel de `detectAndSaveTracks`, initialiser la liste avec toutes les sources en PENDING :

```kotlin
_sourceResults.value = listOf(
    SourceResult("Description YouTube", SourceStatus.PENDING),
    SourceResult("yt-dlp", SourceStatus.PENDING),
    SourceResult("Mixcloud", SourceStatus.PENDING),
    SourceResult("MixesDB", SourceStatus.PENDING),
    SourceResult("Shazam/IA", SourceStatus.PENDING),
)
```

Le callback `onSourceResult` met à jour l'entrée correspondante par `source` name :

```kotlin
val onSourceResult: (SourceResult) -> Unit = { result ->
    _sourceResults.update { list ->
        list.map { if (it.source == result.source) result else it }
    }
}
```

---

## 6. UI : `TracklistDiagnosticBanner`

**Fichier :** `ui/screens/episode/TracklistDiagnosticBanner.kt`

**Comportement :**
- Ouvert pendant la détection — lignes apparaissent au fur et à mesure
- Auto-collapse 3s après fin si ≥1 source = SUCCESS
- Reste ouvert si toutes = FAILED
- Flèche ▼/▲ pour toggle manuel

**Icônes par statut :**
| Statut | Icône |
|--------|-------|
| PENDING | ○ |
| RUNNING | ⏳ |
| SUCCESS | ✅ |
| FAILED | ❌ |
| SKIPPED | ⏭ |

**Format ligne :**
```
✅ Description YouTube   14 tracks   +8ms
⏭ yt-dlp                skippé (pas de chapters)
❌ Mixcloud              0 tracks   +923ms
```

**Intégration dans `EpisodeDetailScreen` :** entre le bloc infos épisode et la `LazyColumn` tracklist.

---

## 7. Fichiers touchés

| Fichier | Action |
|---------|--------|
| `domain/model/SourceResult.kt` | Créer |
| `data/repository/TrackRepository.kt` | Ajouter callback + 2 nouvelles sources |
| `data/api/TracklistApi.kt` | Ajouter `/chapters` et `/mixesdb` endpoints |
| `ui/screens/episode/EpisodeDetailViewModel.kt` | Ajouter `sourceResults` StateFlow + callback |
| `ui/screens/episode/TracklistDiagnosticBanner.kt` | Créer composant Compose |
| `ui/screens/episode/EpisodeDetailScreen.kt` | Insérer le bandeau |
| `audio_timestamp_server.py` | Ajouter `/chapters` et `/mixesdb` handlers |

---

## 8. Hors scope

- Persistence DB des résultats de diagnostic
- Support yt-dlp pour les podcasts (non-liveset)
- MixesDB pour les podcasts
- Historique des tentatives par épisode
