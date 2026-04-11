# Guide d'Intégration du Timestamping Audio pour PodMix

## Architecture

Le système de timestamping audio pour PodMix utilise une architecture client-serveur :

1. **Serveur Python** (`audio_timestamp_server.py`) - Port 8099
   - Recherche de tracklists sur 1001Tracklists
   - Analyse audio pour détection de transitions
   - API REST simple

2. **Service Android** (`AudioAnalysisService.kt`)
   - Communication avec le serveur Python
   - Intégration avec l'UI existante
   - Gestion des résultats d'analyse

## Installation et Configuration

### 1. Démarrage du Serveur Python

```bash
# Installer les dépendances
pip install httpx beautifulsoup4 aiohttp fake-useragent

# Démarrer le serveur
python audio_timestamp_server.py
```

Le serveur démarre sur `http://localhost:8099` avec les endpoints suivants :
- `GET /` - Informations du service
- `GET /health` - Vérification de santé
- `GET /tracklist?q=QUERY` - Recherche 1001Tracklists
- `POST /analyze` - Analyse audio

### 2. Configuration Android

Le service est déjà configuré dans `NetworkModule.kt` :
```kotlin
@Provides
@Singleton
fun provideAudioAnalysisService(): AudioAnalysisService = AudioAnalysisService()
```

L'URL du serveur est configurable dans `AudioAnalysisService.kt` :
```kotlin
private val SERVER_BASE_URL = "http://localhost:8099"
```

**Note** : Pour les tests sur émulateur Android, utiliser `http://10.0.2.2:8099`.
Pour les tests sur appareil physique, utiliser l'IP locale du PC.

## Utilisation

### 1. Vérifier la disponibilité du serveur

```kotlin
val analysisService: AudioAnalysisService = hiltViewModel()
val isAvailable = analysisService.isServerAvailable()
```

### 2. Rechercher des tracklists

```kotlin
val tracklists = analysisService.searchTracklists("Carl Cox techno mix")
if (tracklists.isNotEmpty()) {
    // Afficher les résultats
    tracklists.forEach { tracklist ->
        println("${tracklist.title} - ${tracklist.artist} (${tracklist.trackCount} tracks)")
    }
}
```

### 3. Analyser un épisode

```kotlin
val episode = // votre objet Episode
val result = analysisService.analyzeEpisode(episode)

when {
    result.success -> {
        println("Succès: ${result.message}")
        result.tracklists.forEach { tracklist ->
            // Traiter les tracklists trouvées
        }
    }
    else -> {
        println("Échec: ${result.message}")
    }
}
```

### 4. Analyser une URL audio directement

```kotlin
val audioUrl = "https://example.com/mix.mp3"
val analysisResult = analysisService.analyzeAudio(audioUrl, "techno mix")

when (analysisResult.status) {
    "processing" -> println("Analyse en cours...")
    "completed" -> println("Analyse terminée: ${analysisResult.message}")
    "error" -> println("Erreur: ${analysisResult.message}")
}
```

## Intégration avec l'UI Existante

### Option 1: Ajouter un bouton "Analyser" dans l'écran de l'épisode

Dans votre ViewModel d'épisode :
```kotlin
fun analyzeEpisode() {
    viewModelScope.launch {
        _uiState.update { it.copy(isAnalyzing = true) }
        
        val result = audioAnalysisService.analyzeEpisode(episode)
        
        _uiState.update { 
            it.copy(
                isAnalyzing = false,
                analysisResult = result,
                showAnalysisResults = result.success
            )
        }
    }
}
```

### Option 2: Analyse automatique lors du chargement

```kotlin
init {
    viewModelScope.launch {
        // Vérifier si le serveur est disponible
        if (audioAnalysisService.isServerAvailable()) {
            // Lancer l'analyse en arrière-plan
            analyzeEpisodeBackground()
        }
    }
}

private fun analyzeEpisodeBackground() {
    viewModelScope.launch(Dispatchers.IO) {
        val result = audioAnalysisService.analyzeEpisode(episode)
        if (result.success) {
            // Mettre à jour la base de données avec les timestamps
            updateEpisodeWithTimestamps(result)
        }
    }
}
```

## Structure des Données

### TracklistResult
```kotlin
data class TracklistResult(
    val url: String,          // URL de la tracklist 1001TL
    val title: String,        // Titre du mix
    val artist: String,       // Nom du DJ/artiste
    val trackCount: Int,      // Nombre de pistes
    val duration: Int         // Durée en secondes
)
```

### AudioAnalysisResult
```kotlin
data class AudioAnalysisResult(
    val status: String,       // "processing", "completed", "error"
    val message: String,      // Message descriptif
    val tracklistFound: Boolean,  // Si une tracklist a été trouvée
    val estimatedTracks: Int, // Nombre estimé de pistes
    val analysisId: String    // ID pour suivre l'analyse
)
```

### EpisodeAnalysisResult
```kotlin
data class EpisodeAnalysisResult(
    val success: Boolean,     // Succès global
    val message: String,      // Message résumé
    val tracklists: List<TracklistResult>,  // Tracklists trouvées
    val analysisResult: AudioAnalysisResult?  // Résultat d'analyse audio
)
```

## Dépannage

### Problème: Le serveur ne répond pas
1. Vérifier que le serveur Python est en cours d'exécution
2. Vérifier le port 8099 n'est pas bloqué par le firewall
3. Pour Android émulateur: utiliser `http://10.0.2.2:8099`
4. Pour appareil physique: utiliser l'IP locale du PC

### Problème: Aucune tracklist trouvée
1. Vérifier la connexion Internet
2. Essayer des requêtes plus spécifiques
3. Vérifier que 1001Tracklists n'est pas bloqué

### Problème: Erreur de timeout
1. Augmenter `TIMEOUT_SECONDS` dans `AudioAnalysisService.kt`
2. Vérifier la vitesse de connexion réseau

## Améliorations Futures

1. **Analyse audio avancée** : Intégrer des algorithmes de détection de transitions
2. **Cache local** : Stocker les résultats d'analyse pour consultation hors ligne
3. **Synchronisation automatique** : Mettre à jour les timestamps lors de la lecture
4. **Interface utilisateur enrichie** : Visualisation des timestamps sur la barre de progression
5. **Support multi-sources** : YouTube, SoundCloud, Mixcloud, etc.

## Ressources

- [Code source du serveur Python](audio_timestamp_server.py)
- [Service Android](app/src/main/java/com/podmix/service/AudioAnalysisService.kt)
- [Module Dagger](app/src/main/java/com/podmix/di/NetworkModule.kt)
- [Service de tracklist existant](app/src/main/java/com/podmix/service/TracklistService.kt)