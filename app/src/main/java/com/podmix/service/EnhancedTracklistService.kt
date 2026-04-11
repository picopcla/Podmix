package com.podmix.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service amélioré pour la détection de tracklists qui combine:
 * 1. Analyse de la description YouTube
 * 2. Analyse des commentaires YouTube
 * 3. Recherche sur 1001Tracklists
 * 4. Serveur Python d'analyse audio
 * 
 * Priorité: Commentaires > Description > 1001TL > Analyse audio
 */
@Singleton
class EnhancedTracklistService @Inject constructor(
    private val tracklistService: TracklistService,
    private val youTubeCommentsService: YouTubeCommentsService,
    private val audioAnalysisService: AudioAnalysisService
) {
    
    private val TAG = "EnhancedTracklistService"
    
    /**
     * Détecte les tracklists d'un épisode en utilisant toutes les sources disponibles.
     * @param episodeTitle Titre de l'épisode
     * @param podcastName Nom du podcast (optionnel)
     * @param description Description de l'épisode (optionnel)
     * @param youtubeVideoId ID de la vidéo YouTube (optionnel)
     * @param audioUrl URL audio (optionnel)
     * @return Liste des pistes détectées avec timestamps
     */
    suspend fun detectTracklist(
        episodeTitle: String,
        podcastName: String? = null,
        description: String? = null,
        youtubeVideoId: String? = null,
        audioUrl: String? = null
    ): List<ParsedTrack> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Détection de tracklist pour: $episodeTitle")
        
        val allTracks = mutableListOf<ParsedTrack>()
        
        // Stratégie 1: Commentaires YouTube (si vidéo YouTube disponible)
        if (!youtubeVideoId.isNullOrBlank()) {
            Log.d(TAG, "Analyse des commentaires YouTube...")
            try {
                val fromComments = youTubeCommentsService.extractTracklistFromVideo(youtubeVideoId)
                if (fromComments.isNotEmpty()) {
                    Log.d(TAG, "Trouvé ${fromComments.size} pistes dans les commentaires YouTube")
                    allTracks.addAll(fromComments)
                    
                    // Si on a une tracklist complète des commentaires, on s'arrête
                    if (fromComments.size >= 5) {
                        Log.d(TAG, "Tracklist complète trouvée dans les commentaires")
                        return@withContext fromComments
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur analyse commentaires: ${e.message}")
            }
        }
        
        // Stratégie 2: Description (YouTube ou RSS)
        if (!description.isNullOrBlank()) {
            Log.d(TAG, "Analyse de la description...")
            val fromDescription = tracklistService.detect(description, podcastName, episodeTitle)
            if (fromDescription.isNotEmpty()) {
                Log.d(TAG, "Trouvé ${fromDescription.size} pistes dans la description")
                
                // Fusionner avec les pistes des commentaires (éviter les doublons)
                val newTracks = fromDescription.filterNot { track ->
                    allTracks.any { existing ->
                        existing.artist == track.artist && existing.title == track.title
                    }
                }
                allTracks.addAll(newTracks)
            }
        }
        
        // Stratégie 3: 1001Tracklists (seulement si peu de pistes trouvées)
        if (allTracks.size < 3) {
            Log.d(TAG, "Recherche sur 1001Tracklists...")
            val query = buildString {
                podcastName?.let { append("$it ") }
                append(episodeTitle)
            }
            
            val from1001 = tracklistService.scrapeFrom1001Tracklists(query)
            if (from1001.isNotEmpty()) {
                Log.d(TAG, "Trouvé ${from1001.size} pistes sur 1001Tracklists")
                
                val newTracks = from1001.filterNot { track ->
                    allTracks.any { existing ->
                        existing.artist == track.artist && existing.title == track.title
                    }
                }
                allTracks.addAll(newTracks)
            }
        }
        
        // Stratégie 4: Analyse audio via serveur Python (seulement si URL audio disponible)
        if (allTracks.isEmpty() && !audioUrl.isNullOrBlank()) {
            Log.d(TAG, "Analyse audio via serveur Python...")
            try {
                // Vérifier si le serveur est disponible
                if (audioAnalysisService.isServerAvailable()) {
                    val result = audioAnalysisService.analyzeAudio(audioUrl, episodeTitle)
                    if (result.tracklistFound) {
                        Log.d(TAG, "Tracklist détectée via analyse audio")
                        // Note: Pour l'instant, l'analyse audio retourne seulement une confirmation
                        // Dans une version future, elle pourrait retourner les pistes détectées
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur analyse audio: ${e.message}")
            }
        }
        
        // Trier par timestamp
        val sortedTracks = allTracks.sortedBy { it.startTimeSec }
        Log.d(TAG, "Total: ${sortedTracks.size} pistes détectées")
        
        return@withContext sortedTracks
    }
    
    /**
     * Vérifie si une vidéo YouTube a probablement une tracklist.
     * Utile pour décider s'il faut analyser les commentaires.
     */
    suspend fun hasLikelyYouTubeTracklist(
        youtubeVideoId: String,
        videoTitle: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext youTubeCommentsService.hasLikelyTracklist(youtubeVideoId, videoTitle)
    }
    
    /**
     * Résout les timestamps manquants en utilisant iTunes pour les durées.
     */
    suspend fun resolveTimestamps(
        tracks: List<ParsedTrack>,
        episodeDurationSec: Int
    ): List<ParsedTrack> = withContext(Dispatchers.IO) {
        return@withContext tracklistService.resolveTimestamps(tracks, episodeDurationSec)
    }
    
    /**
     * Version asynchrone de la détection pour intégration facile.
     */
    suspend fun detectAsync(
        episodeTitle: String,
        podcastName: String? = null,
        description: String? = null,
        youtubeVideoId: String? = null,
        audioUrl: String? = null
    ): TracklistDetectionResult {
        val tracks = detectTracklist(episodeTitle, podcastName, description, youtubeVideoId, audioUrl)
        
        return TracklistDetectionResult(
            success = tracks.isNotEmpty(),
            trackCount = tracks.size,
            tracks = tracks,
            source = when {
                youtubeVideoId != null && tracks.isNotEmpty() -> "youtube_comments"
                description != null && tracks.isNotEmpty() -> "description"
                tracks.isNotEmpty() -> "1001tracklists"
                else -> "none"
            }
        )
    }
    
    /**
     * Détecte et résout les timestamps en une seule opération.
     */
    suspend fun detectAndResolveTimestamps(
        episodeTitle: String,
        podcastName: String? = null,
        description: String? = null,
        youtubeVideoId: String? = null,
        audioUrl: String? = null,
        episodeDurationSec: Int = 0
    ): TracklistDetectionResult {
        val tracks = detectTracklist(episodeTitle, podcastName, description, youtubeVideoId, audioUrl)
        
        val resolvedTracks = if (tracks.isNotEmpty() && episodeDurationSec > 0) {
            resolveTimestamps(tracks, episodeDurationSec)
        } else {
            tracks
        }
        
        return TracklistDetectionResult(
            success = resolvedTracks.isNotEmpty(),
            trackCount = resolvedTracks.size,
            tracks = resolvedTracks,
            source = when {
                youtubeVideoId != null && tracks.isNotEmpty() -> "youtube_comments"
                description != null && tracks.isNotEmpty() -> "description"
                tracks.isNotEmpty() -> "1001tracklists"
                else -> "none"
            },
            hasExactTimestamps = resolvedTracks.any { it.startTimeSec > 0 }
        )
    }
}

/**
 * Résultat de la détection de tracklist.
 */
data class TracklistDetectionResult(
    val success: Boolean,
    val trackCount: Int,
    val tracks: List<ParsedTrack>,
    val source: String, // "youtube_comments", "description", "1001tracklists", "audio_analysis", "none"
    val hasExactTimestamps: Boolean = false,
    val message: String = if (success) "Trouvé $trackCount pistes" else "Aucune piste trouvée"
)