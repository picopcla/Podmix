package com.podmix.service

import android.util.Log
import com.podmix.domain.model.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service pour l'analyse audio et le timestamping via le serveur Python.
 * Communique avec le serveur HTTP local (localhost:8099) pour :
 * 1. Rechercher des tracklists sur 1001Tracklists
 * 2. Analyser des URLs audio pour détecter les transitions
 * 3. Synchroniser les timestamps avec les métadonnées
 */
@Singleton
class AudioAnalysisService @Inject constructor() {
    
    private val TAG = "AudioAnalysisService"
    
    // Configuration du serveur Python
    // Utiliser l'IP du PC pour que le téléphone puisse accéder via WiFi
    private val SERVER_BASE_URL = "http://192.168.10.5:8099"
    private val TIMEOUT_SECONDS = 30L
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    /**
     * Vérifie si le serveur Python est disponible.
     */
    suspend fun isServerAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_BASE_URL/health")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val isAvailable = response.isSuccessful
            response.close()
            
            Log.d(TAG, "Server health check: ${if (isAvailable) "available" else "unavailable"}")
            return@withContext isAvailable
        } catch (e: Exception) {
            Log.w(TAG, "Server health check failed: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Recherche des tracklists sur 1001Tracklists via le serveur Python.
     * @param query Terme de recherche (nom du DJ, titre du mix, etc.)
     * @return Liste des tracklists trouvées
     */
    suspend fun searchTracklists(query: String): List<TracklistResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$SERVER_BASE_URL/tracklist?q=$encodedQuery"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Tracklist search failed: ${response.code} - ${response.message}")
                response.close()
                return@withContext emptyList()
            }
            
            val responseBody = response.body?.string()
            response.close()
            
            if (responseBody.isNullOrEmpty()) {
                return@withContext emptyList()
            }
            
            // Parse la réponse JSON
            val jsonArray = org.json.JSONArray(responseBody)
            val results = mutableListOf<TracklistResult>()
            
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val result = TracklistResult(
                    url = jsonObject.optString("url", ""),
                    title = jsonObject.optString("title", ""),
                    artist = jsonObject.optString("artist", ""),
                    trackCount = jsonObject.optInt("track_count", 0),
                    duration = jsonObject.optInt("duration_sec", 0)
                )
                results.add(result)
            }
            
            Log.d(TAG, "Found ${results.size} tracklists for query: $query")
            return@withContext results
        } catch (e: Exception) {
            Log.e(TAG, "Error searching tracklists: ${e.message}", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Analyse une URL audio pour détecter les transitions et générer des timestamps.
     * @param audioUrl URL de l'audio à analyser
     * @param query Terme de recherche optionnel pour aider l'analyse
     * @return Résultat de l'analyse
     */
    suspend fun analyzeAudio(audioUrl: String, query: String = ""): AudioAnalysisResult = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("url", audioUrl)
                if (query.isNotEmpty()) {
                    put("query", query)
                }
            }.toString()
            
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$SERVER_BASE_URL/analyze")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Audio analysis failed: ${response.code} - ${response.message}")
                response.close()
                return@withContext AudioAnalysisResult(
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}"
                )
            }
            
            val responseBody = response.body?.string()
            response.close()
            
            if (responseBody.isNullOrEmpty()) {
                return@withContext AudioAnalysisResult(
                    status = "error",
                    message = "Empty response from server"
                )
            }
            
            // Parse la réponse JSON
            val json = JSONObject(responseBody)
            return@withContext AudioAnalysisResult(
                status = json.optString("status", "unknown"),
                message = json.optString("message", ""),
                tracklistFound = json.optBoolean("tracklist_found", false),
                estimatedTracks = json.optInt("estimated_tracks", 0),
                analysisId = json.optString("analysis_id", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing audio: ${e.message}", e)
            return@withContext AudioAnalysisResult(
                status = "error",
                message = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Analyse un épisode pour générer des timestamps automatiquement.
     * Combine la recherche 1001TL avec l'analyse audio si disponible.
     */
    suspend fun analyzeEpisode(episode: Episode): EpisodeAnalysisResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Analyzing episode: ${episode.title}")
        
        // Vérifier d'abord si le serveur est disponible
        if (!isServerAvailable()) {
            return@withContext EpisodeAnalysisResult(
                success = false,
                message = "Serveur d'analyse audio non disponible",
                tracklists = emptyList(),
                analysisResult = null
            )
        }
        
        // Construire la requête de recherche
        val searchQuery = episode.title
        
        // Rechercher des tracklists
        val tracklists = searchTracklists(searchQuery)
        
        // Si aucune tracklist trouvée, essayer avec juste le titre
        val finalTracklists = if (tracklists.isEmpty()) {
            searchTracklists(episode.title)
        } else {
            tracklists
        }
        
        // Si une URL audio est disponible, lancer une analyse
        val audioAnalysis = if (episode.audioUrl.isNotEmpty()) {
            analyzeAudio(episode.audioUrl, searchQuery)
        } else {
            null
        }
        
        return@withContext EpisodeAnalysisResult(
            success = finalTracklists.isNotEmpty() || (audioAnalysis?.tracklistFound == true),
            message = if (finalTracklists.isNotEmpty()) {
                "Trouvé ${finalTracklists.size} tracklist(s)"
            } else if (audioAnalysis?.tracklistFound == true) {
                "Tracklist détectée via analyse audio"
            } else {
                "Aucune tracklist trouvée"
            },
            tracklists = finalTracklists,
            analysisResult = audioAnalysis
        )
    }
}

/**
 * Résultat d'une recherche de tracklist.
 */
data class TracklistResult(
    val url: String,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val duration: Int // en secondes
)

/**
 * Résultat d'une analyse audio.
 */
data class AudioAnalysisResult(
    val status: String, // "processing", "completed", "error"
    val message: String,
    val tracklistFound: Boolean = false,
    val estimatedTracks: Int = 0,
    val analysisId: String = ""
)

/**
 * Résultat complet de l'analyse d'un épisode.
 */
data class EpisodeAnalysisResult(
    val success: Boolean,
    val message: String,
    val tracklists: List<TracklistResult>,
    val analysisResult: AudioAnalysisResult?
)