// Exemple d'intégration des services de tracklist YouTube dans un ViewModel existant
// Ce fichier montre comment ajouter l'analyse des commentaires YouTube à l'application PodMix

package com.podmix.ui.screens.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podmix.domain.model.Episode
import com.podmix.service.EnhancedTracklistService
import com.podmix.service.TracklistDetectionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exemple de ViewModel pour l'écran de détail d'épisode avec analyse YouTube.
 */
@HiltViewModel
class EpisodeDetailViewModelExample @Inject constructor(
    private val enhancedTracklistService: EnhancedTracklistService
) : ViewModel() {
    
    data class UiState(
        val episode: Episode? = null,
        val isAnalyzing: Boolean = false,
        val analysisResult: TracklistDetectionResult? = null,
        val showAnalysisResults: Boolean = false,
        val errorMessage: String? = null
    )
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    /**
     * Charge un épisode et vérifie automatiquement s'il a une tracklist YouTube.
     */
    fun loadEpisode(episode: Episode) {
        _uiState.update { it.copy(episode = episode) }
        
        // Vérifier si c'est un épisode YouTube avec potentielle tracklist
        if (!episode.youtubeVideoId.isNullOrBlank()) {
            checkForYouTubeTracklist(episode)
        }
    }
    
    /**
     * Vérifie si l'épisode a une tracklist dans les commentaires YouTube.
     */
    private fun checkForYouTubeTracklist(episode: Episode) {
        viewModelScope.launch {
            try {
                val hasTracklist = enhancedTracklistService.hasLikelyYouTubeTracklist(
                    episode.youtubeVideoId!!,
                    episode.title
                )
                
                if (hasTracklist) {
                    // Mettre à jour l'UI pour suggérer l'analyse
                    _uiState.update { it.copy(
                        errorMessage = "Tracklist potentielle détectée dans les commentaires YouTube"
                    )}
                }
            } catch (e: Exception) {
                // Ignorer les erreurs de vérification
            }
        }
    }
    
    /**
     * Lance l'analyse des commentaires YouTube pour l'épisode courant.
     */
    fun analyzeYouTubeComments() {
        val episode = _uiState.value.episode ?: return
        val videoId = episode.youtubeVideoId ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, errorMessage = null) }
            
            try {
                val result = enhancedTracklistService.detectAndResolveTimestamps(
                    episodeTitle = episode.title,
                    podcastName = episode.podcastName,
                    description = episode.description,
                    youtubeVideoId = videoId,
                    audioUrl = episode.audioUrl,
                    episodeDurationSec = episode.durationSeconds
                )
                
                _uiState.update { it.copy(
                    isAnalyzing = false,
                    analysisResult = result,
                    showAnalysisResults = result.success
                )}
                
                if (result.success) {
                    // Option: Sauvegarder les pistes dans la base de données
                    // saveTracksToDatabase(episode.id, result.tracks)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isAnalyzing = false,
                    errorMessage = "Erreur d'analyse: ${e.message}"
                )}
            }
        }
    }
    
    /**
     * Analyse automatique lors du chargement (optionnel).
     */
    fun analyzeAutomaticallyIfNeeded() {
        val episode = _uiState.value.episode ?: return
        val videoId = episode.youtubeVideoId ?: return
        
        // Ne pas analyser si déjà des pistes ou si analyse en cours
        if (episode.tracks.isNotEmpty() || _uiState.value.isAnalyzing) {
            return
        }
        
        viewModelScope.launch {
            try {
                // Vérifier rapidement si ça vaut la peine d'analyser
                val hasTracklist = enhancedTracklistService.hasLikelyYouTubeTracklist(
                    videoId,
                    episode.title
                )
                
                if (hasTracklist) {
                    // Lancer l'analyse en arrière-plan
                    analyzeYouTubeComments()
                }
            } catch (e: Exception) {
                // Ignorer les erreurs en mode automatique
            }
        }
    }
    
    /**
     * Cache les résultats d'analyse.
     */
    fun hideAnalysisResults() {
        _uiState.update { it.copy(showAnalysisResults = false) }
    }
}

// Exemple d'UI Composable pour l'intégration
/*
@Composable
fun EpisodeDetailScreenExample(
    viewModel: EpisodeDetailViewModelExample = hiltViewModel(),
    episodeId: Int
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(episodeId) {
        // Charger l'épisode depuis la base de données
        val episode = loadEpisodeFromDatabase(episodeId)
        viewModel.loadEpisode(episode)
        viewModel.analyzeAutomaticallyIfNeeded()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // En-tête de l'épisode
        uiState.episode?.let { episode ->
            EpisodeHeader(episode)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bouton d'analyse YouTube
            if (!episode.youtubeVideoId.isNullOrBlank()) {
                YouTubeAnalysisSection(
                    isAnalyzing = uiState.isAnalyzing,
                    analysisResult = uiState.analysisResult,
                    onAnalyzeClick = { viewModel.analyzeYouTubeComments() },
                    onHideResults = { viewModel.hideAnalysisResults() }
                )
            }
            
            // Message d'erreur
            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            // Résultats d'analyse
            if (uiState.showAnalysisResults) {
                uiState.analysisResult?.let { result ->
                    TracklistResultsSection(result)
                }
            }
        }
    }
}

@Composable
fun YouTubeAnalysisSection(
    isAnalyzing: Boolean,
    analysisResult: TracklistDetectionResult?,
    onAnalyzeClick: () -> Unit,
    onHideResults: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Analyse YouTube",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Recherche des tracklists dans les commentaires",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (isAnalyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = onAnalyzeClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Analyser"
                        )
                    }
                }
            }
            
            // Résultats précédents
            analysisResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = if (result.success) "✅ ${result.trackCount} pistes trouvées" 
                                  else "❌ Aucune piste trouvée",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (result.success) {
                            Text(
                                text = "Source: ${result.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    IconButton(onClick = onHideResults) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fermer"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TracklistResultsSection(result: TracklistDetectionResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Tracklist détectée",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            LazyColumn {
                items(result.tracks) { track ->
                    TracklistItem(track)
                }
            }
            
            if (result.hasExactTimestamps) {
                Text(
                    text = "✅ Timestamps exacts disponibles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun TracklistItem(track: ParsedTrack) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Timestamp
        Text(
            text = formatTimestamp(track.startTimeSec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(60.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Informations de la piste
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Bouton d'action
        IconButton(onClick = { /* Ajouter aux favoris, rechercher sur Spotify, etc. */ }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Actions"
            )
        }
    }
}

fun formatTimestamp(seconds: Float): String {
    val minutes = (seconds / 60).toInt()
    val secs = (seconds % 60).toInt()
    return String.format("%02d:%02d", minutes, secs)
}
*/