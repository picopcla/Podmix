# Guide d'Extraction des Tracklists depuis les Commentaires YouTube

## Introduction

Les commentaires YouTube sont souvent une source riche de tracklists complètes avec timestamps précis. Ce guide explique comment intégrer cette fonctionnalité dans PodMix pour améliorer la détection automatique des pistes dans les mixes et sets.

## Architecture

Le système utilise trois services principaux :

1. **YouTubeCommentsService** - Extrait et analyse les commentaires YouTube
2. **EnhancedTracklistService** - Combine toutes les sources de tracklists
3. **YouTubeStreamResolver** - Récupère les descriptions et métadonnées YouTube

## Installation

### Dépendances

Le système utilise NewPipe Extractor pour accéder aux données YouTube. Les dépendances sont déjà configurées dans le projet.

### Configuration Dagger

Les services sont automatiquement injectés via Dagger Hilt :

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideYouTubeCommentsService(...): YouTubeCommentsService
    
    @Provides
    @Singleton
    fun provideEnhancedTracklistService(...): EnhancedTracklistService
}
```

## Utilisation

### 1. Extraction basique des commentaires

```kotlin
class MyViewModel @Inject constructor(
    private val youTubeCommentsService: YouTubeCommentsService
) : ViewModel() {
    
    suspend fun analyzeVideo(videoId: String) {
        val tracks = youTubeCommentsService.extractTracklistFromVideo(videoId)
        if (tracks.isNotEmpty()) {
            println("Trouvé ${tracks.size} pistes dans les commentaires")
            tracks.forEach { track ->
                println("${track.startTimeSec}s - ${track.artist} - ${track.title}")
            }
        }
    }
}
```

### 2. Utilisation du service amélioré

```kotlin
class EpisodeDetailViewModel @Inject constructor(
    private val enhancedTracklistService: EnhancedTracklistService
) : ViewModel() {
    
    suspend fun detectTracklistForEpisode(episode: Episode) {
        val result = enhancedTracklistService.detectAndResolveTimestamps(
            episodeTitle = episode.title,
            podcastName = episode.podcastName,
            description = episode.description,
            youtubeVideoId = episode.youtubeVideoId,
            audioUrl = episode.audioUrl,
            episodeDurationSec = episode.durationSeconds
        )
        
        when {
            result.success -> {
                println("✅ ${result.trackCount} pistes trouvées (source: ${result.source})")
                if (result.hasExactTimestamps) {
                    println("📊 Timestamps exacts disponibles")
                }
            }
            else -> {
                println("❌ Aucune piste trouvée")
            }
        }
    }
}
```

### 3. Vérification préalable

Avant d'analyser tous les commentaires, vous pouvez vérifier si une vidéo a probablement une tracklist :

```kotlin
suspend fun shouldAnalyzeComments(videoId: String, videoTitle: String): Boolean {
    return enhancedTracklistService.hasLikelyYouTubeTracklist(videoId, videoTitle)
}
```

## Formats de Tracklists Supportés

Le système détecte plusieurs formats courants dans les commentaires YouTube :

### Format 1: Timestamps simples
```
00:00 Artist - Title
05:30 Another Artist - Another Title
10:45 Third Artist - Third Title
```

### Format 2: Timestamps entre crochets
```
[00:00] Artist - Title
[05:30] Another Artist - Another Title
```

### Format 3: Numérotation + timestamps
```
1. 00:00 Artist - Title
2. 05:30 Another Artist - Another Title
```

### Format 4: Timestamps entre parenthèses
```
Artist - Title (00:00)
Another Artist - Another Title (05:30)
```

### Format 5: Section "Tracklist:"
```
Tracklist:
00:00 Artist - Title
05:30 Another Artist - Another Title
10:45 Third Artist - Third Title
```

## Intégration avec l'UI Existante

### Option 1: Bouton "Analyser les commentaires"

Ajoutez un bouton dans l'écran de détail de l'épisode :

```kotlin
@Composable
fun EpisodeDetailScreen(
    viewModel: EpisodeDetailViewModel,
    episode: Episode
) {
    // ...
    
    Button(
        onClick = {
            viewModel.analyzeYouTubeComments(episode.youtubeVideoId)
        },
        enabled = !episode.youtubeVideoId.isNullOrBlank()
    ) {
        Text("Analyser les commentaires YouTube")
    }
    
    // Afficher les résultats
    val analysisResult by viewModel.analysisResult.collectAsState()
    analysisResult?.let { result ->
        if (result.success) {
            TracklistResults(result.tracks)
        }
    }
}
```

### Option 2: Analyse automatique

Analyse automatique lors du chargement d'un épisode YouTube :

```kotlin
class EpisodeDetailViewModel @Inject constructor(
    private val enhancedTracklistService: EnhancedTracklistService
) : ViewModel() {
    
    init {
        viewModelScope.launch {
            episode?.let { ep ->
                if (!ep.youtubeVideoId.isNullOrBlank()) {
                    // Vérifier si une analyse est nécessaire
                    if (ep.tracks.isEmpty()) {
                        analyzeAutomatically(ep)
                    }
                }
            }
        }
    }
    
    private suspend fun analyzeAutomatically(episode: Episode) {
        val hasTracklist = enhancedTracklistService.hasLikelyYouTubeTracklist(
            episode.youtubeVideoId!!,
            episode.title
        )
        
        if (hasTracklist) {
            _uiState.update { it.copy(isAnalyzing = true) }
            
            val result = enhancedTracklistService.detectAndResolveTimestamps(
                episodeTitle = episode.title,
                podcastName = episode.podcastName,
                description = episode.description,
                youtubeVideoId = episode.youtubeVideoId,
                episodeDurationSec = episode.durationSeconds
            )
            
            if (result.success) {
                // Sauvegarder les pistes dans la base de données
                saveTracksToDatabase(episode.id, result.tracks)
            }
            
            _uiState.update { it.copy(
                isAnalyzing = false,
                analysisResult = result
            )}
        }
    }
}
```

## Performance et Optimisation

### Cache
- Les descriptions YouTube sont mises en cache pendant 4 heures
- Les résultats d'analyse peuvent être stockés localement

### Limites
- Analyse maximum de 100 commentaires par défaut
- Timeout de 30 secondes pour la récupération des commentaires
- Filtrage des doublons basé sur "Artist - Title"

### Détection intelligente
Le système arrête l'analyse dès qu'une tracklist complète (≥5 pistes) est trouvée.

## Dépannage

### Problème: Aucun commentaire récupéré
1. Vérifier la connexion Internet
2. Vérifier que la vidéo n'est pas privée ou restreinte
3. Vérifier que NewPipe Extractor est correctement initialisé

### Problème: Tracklists non détectées
1. Vérifier le format des timestamps dans les commentaires
2. Augmenter le nombre de commentaires analysés (`maxComments` parameter)
3. Vérifier les logs pour les erreurs d'analyse

### Problème: Performances lentes
1. Réduire `maxComments` (par défaut 100)
2. Utiliser `hasLikelyYouTubeTracklist()` pour filtrer les vidéos
3. Mettre en cache les résultats d'analyse

## Exemples d'Utilisation Réels

### Exemple 1: Mix Techno
```
Commentaire YouTube:
Tracklist:
00:00 Amelie Lens - Follow
06:45 Charlotte de Witte - Selected
12:30 Enrico Sangiuliano - Symbiosis
18:15 Adam Beyer - Your Mind
```

### Exemple 2: Set de DJ
```
Commentaire YouTube:
Setlist:
1. 00:00 Eric Prydz - Opus
2. 04:30 Deadmau5 - Strobe
3. 09:15 Above & Beyond - Sun & Moon
```

### Exemple 3: Format personnalisé
```
Commentaire YouTube:
[00:00] CamelPhat - Breathe
[03:45] ARTBAT - Horizon
[07:30] Mind Against - Atlas
```

## Améliorations Futures

1. **Apprentissage automatique** : Détection de nouveaux formats de tracklists
2. **OCR des images** : Analyse des screenshots de tracklists partagés
3. **Collaboration communautaire** : Partage des tracklists détectées
4. **Correction automatique** : Correction des erreurs de formatage
5. **Intégration Shazam** : Vérification des pistes détectées

## Ressources

- [Code source YouTubeCommentsService](app/src/main/java/com/podmix/service/YouTubeCommentsService.kt)
- [Code source EnhancedTracklistService](app/src/main/java/com/podmix/service/EnhancedTracklistService.kt)
- [Documentation NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [Guide d'intégration complet](AUDIO_TIMESTAMPING_GUIDE.md)