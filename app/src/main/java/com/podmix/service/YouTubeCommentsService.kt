package com.podmix.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service pour extraire les commentaires YouTube et y chercher des tracklists avec timestamps.
 * Les commentaires YouTube sont souvent une source riche de tracklists complètes avec timestamps précis.
 */
@Singleton
class YouTubeCommentsService @Inject constructor(
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val tracklistService: TracklistService,
    private val geminiParser: GeminiTracklistParser
) {
    
    private val TAG = "YouTubeCommentsService"
    
    /**
     * Extrait les commentaires d'une vidéo YouTube et recherche des tracklists avec timestamps.
     * @param videoId ID de la vidéo YouTube
     * @param maxComments Nombre maximum de commentaires à analyser (par défaut 100)
     * @return Liste des pistes trouvées dans les commentaires avec leurs timestamps
     */
    suspend fun extractTracklistFromComments(videoId: String, maxComments: Int = 200): List<ParsedTrack> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Extraction des commentaires pour la vidéo: $videoId (max=$maxComments)")

            youTubeStreamResolver.ensureInitialized()
            val url = "https://www.youtube.com/watch?v=$videoId"
            val commentsInfo = CommentsInfo.getInfo(url)

            var bestTracks = emptyList<ParsedTrack>()
            var scanned = 0

            suspend fun processPage(items: List<*>) {
                for (item in items) {
                    if (item !is CommentsInfoItem) continue
                    val text = item.commentText?.content ?: continue
                    scanned++
                    val tracks = analyzeCommentForTracklist(text)
                    if (tracks.size > bestTracks.size) {
                        bestTracks = tracks
                        Log.d(TAG, "Nouveau meilleur commentaire: ${tracks.size} tracks (commentaire #$scanned)")
                        if (tracks.size >= 5) return // early exit — tracklist complète
                    }
                }
            }

            processPage(commentsInfo.relatedItems)

            var nextPage = commentsInfo.nextPage
            while (nextPage != null && scanned < maxComments && bestTracks.size < 5) {
                val page = CommentsInfo.getMoreItems(commentsInfo, nextPage)
                processPage(page.items)
                nextPage = page.nextPage
            }

            Log.d(TAG, "Résultat: ${bestTracks.size} tracks après scan de $scanned commentaires")
            return@withContext bestTracks.sortedBy { it.startTimeSec }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur extraction commentaires: ${e.message}", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Heuristic: does this comment structurally look like a tracklist?
     * Used to decide whether to spend an API call on Gemini.
     */
    private fun looksLikeTracklist(text: String): Boolean {
        if (text.length < 100) return false
        val lines = text.lines()
        val timestampLines = lines.count { Regex("""\d{1,2}:\d{2}""").containsMatchIn(it) }
        if (timestampLines >= 2) return true
        val numberedLines = lines.count { Regex("""^\d{1,3}[.)]\s""").containsMatchIn(it.trim()) }
        if (numberedLines >= 4) return true
        val dashLines = lines.count { it.contains(" - ") }
        if (dashLines >= 6) return true
        return false
    }

    /**
     * Analyse un commentaire pour trouver une tracklist avec timestamps.
     * Essaie les parsers regex d'abord, puis Gemini AI en dernier recours.
     */
    private suspend fun analyzeCommentForTracklist(commentText: String): List<ParsedTrack> {
        val clean = commentText
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .replace(Regex("""https?://\S+"""), "")
            .trim()

        // Priorité 1 : lignes timestampées
        val timestamped = tracklistService.parseTimestamped(clean)
        if (timestamped.size >= 3) return timestamped

        // Priorité 2 : liste numérotée
        val numbered = tracklistService.parseNumberedList(clean)
        if (numbered.size >= 3) return numbered

        // Priorité 3 : lignes "Artist - Title"
        val plain = tracklistService.parsePlainLines(clean)
        if (plain.size >= 5) return plain

        // Priorité 4 : Gemini AI — uniquement si le commentaire ressemble à une tracklist
        if (looksLikeTracklist(clean)) {
            Log.d(TAG, "Regex failed, trying Gemini AI fallback...")
            val fromGemini = geminiParser.parseComment(clean)
            if (fromGemini != null) {
                Log.i(TAG, "Gemini extracted ${fromGemini.size} tracks")
                return fromGemini
            }
        }

        return emptyList()
    }
    
    /**
     * Parse des formats personnalisés de tracklists trouvés dans les commentaires.
     */
    private fun parseCustomTracklistFormat(text: String): List<ParsedTrack> {
        val tracks = mutableListOf<ParsedTrack>()
        val lines = text.lines()
        
        // Chercher une section "Tracklist:" ou "Setlist:"
        var inTracklistSection = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Détecter le début d'une tracklist
            if (trimmed.contains("tracklist:", ignoreCase = true) || 
                trimmed.contains("setlist:", ignoreCase = true) ||
                trimmed.contains("timestamps:", ignoreCase = true)) {
                inTracklistSection = true
                continue
            }
            
            // Si on est dans la section tracklist, analyser la ligne
            if (inTracklistSection && trimmed.isNotBlank()) {
                // Formats courants:
                // 1. "00:00 Artist - Title"
                // 2. "[00:00] Artist - Title"
                // 3. "1. 00:00 Artist - Title"
                // 4. "Artist - Title (00:00)"
                
                val track = parseTracklistLine(trimmed)
                if (track != null) {
                    tracks.add(track)
                }
                
                // Arrêter si on trouve une ligne vide (fin de la tracklist)
                if (trimmed.isEmpty() && tracks.size > 2) {
                    break
                }
            }
        }
        
        return tracks
    }
    
    /**
     * Parse une ligne de tracklist avec différents formats.
     */
    private fun parseTracklistLine(line: String): ParsedTrack? {
        // Format 1: "00:00 Artist - Title"
        val timestampRegex = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s+(.+)""")
        val match1 = timestampRegex.find(line)
        if (match1 != null) {
            val timeSec = parseTimestamp(match1.groupValues[1], match1.groupValues[2], match1.groupValues[3])
            val trackText = match1.groupValues[4].trim()
            val (artist, title) = splitArtistTitle(trackText)
            if (title.isNotBlank()) {
                return ParsedTrack(artist, title, timeSec)
            }
        }
        
        // Format 2: "[00:00] Artist - Title"
        val bracketRegex = Regex("""\[(\d{1,2}):(\d{2})(?::(\d{2}))?\]\s+(.+)""")
        val match2 = bracketRegex.find(line)
        if (match2 != null) {
            val timeSec = parseTimestamp(match2.groupValues[1], match2.groupValues[2], match2.groupValues[3])
            val trackText = match2.groupValues[4].trim()
            val (artist, title) = splitArtistTitle(trackText)
            if (title.isNotBlank()) {
                return ParsedTrack(artist, title, timeSec)
            }
        }
        
        // Format 3: "Artist - Title (00:00)"
        val parenRegex = Regex("""(.+)\s+\((\d{1,2}):(\d{2})(?::(\d{2}))?\)""")
        val match3 = parenRegex.find(line)
        if (match3 != null) {
            val trackText = match3.groupValues[1].trim()
            val timeSec = parseTimestamp(match3.groupValues[2], match3.groupValues[3], match3.groupValues[4])
            val (artist, title) = splitArtistTitle(trackText)
            if (title.isNotBlank()) {
                return ParsedTrack(artist, title, timeSec)
            }
        }
        
        return null
    }
    
    /**
     * Convertit un timestamp en secondes.
     */
    private fun parseTimestamp(hoursOrMinutes: String, minutesOrSeconds: String, seconds: String): Float {
        return try {
            if (seconds.isNotEmpty()) {
                // Format HH:MM:SS
                (hoursOrMinutes.toIntOrNull() ?: 0) * 3600 +
                (minutesOrSeconds.toIntOrNull() ?: 0) * 60 +
                (seconds.toIntOrNull() ?: 0)
            } else {
                // Format MM:SS
                (hoursOrMinutes.toIntOrNull() ?: 0) * 60 +
                (minutesOrSeconds.toIntOrNull() ?: 0)
            }.toFloat()
        } catch (e: Exception) {
            0f
        }
    }
    
    /**
     * Sépare "Artist - Title [Label]".
     */
    private fun splitArtistTitle(text: String): Pair<String, String> {
        // Retirer les labels entre crochets à la fin (ex: [AREA VERDE], [CAPTIVE SOUL])
        var cleanedText = text.trim()
        val labelRegex = Regex("""\s*\[[^\]]+\]\s*$""")
        cleanedText = labelRegex.replace(cleanedText, "").trim()
        
        // Essayer différents séparateurs
        val separators = listOf(" - ", " – ", " — ", " • ", " | ")
        
        for (separator in separators) {
            val index = cleanedText.indexOf(separator)
            if (index > 0) {
                val artist = cleanedText.substring(0, index).trim()
                val title = cleanedText.substring(index + separator.length).trim()
                return artist to title
            }
        }
        
        // Si aucun séparateur trouvé, considérer tout comme titre
        return "Unknown" to cleanedText
    }
    
    /**
     * Combine l'analyse des commentaires avec la description pour une couverture maximale.
     */
    suspend fun extractTracklistFromVideo(videoId: String): List<ParsedTrack> = withContext(Dispatchers.IO) {
        val allTracks = mutableListOf<ParsedTrack>()
        
        try {
            // 1. Récupérer la description
            Log.d(TAG, "Récupération de la description...")
            val description = youTubeStreamResolver.getDescription(videoId)
            
            if (!description.isNullOrBlank()) {
                // Analyser la description avec le service existant
                val fromDescription = tracklistService.detect(description, null, "YouTube Video")
                if (fromDescription.isNotEmpty()) {
                    Log.d(TAG, "Trouvé ${fromDescription.size} pistes dans la description")
                    allTracks.addAll(fromDescription)
                }
            }
            
            // 2. Analyser les commentaires (seulement si peu de pistes trouvées dans la description)
            if (allTracks.size < 3) {
                Log.d(TAG, "Peu de pistes dans la description, analyse des commentaires...")
                val fromComments = extractTracklistFromComments(videoId, 50)
                if (fromComments.isNotEmpty()) {
                    Log.d(TAG, "Trouvé ${fromComments.size} pistes supplémentaires dans les commentaires")
                    allTracks.addAll(fromComments)
                }
            }
            
            // 3. Dédupliquer et trier
            val uniqueTracks = allTracks.distinctBy { "${it.artist} - ${it.title}" }
            val sortedTracks = uniqueTracks.sortedBy { it.startTimeSec }
            
            Log.d(TAG, "Total final: ${sortedTracks.size} pistes uniques")
            return@withContext sortedTracks
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'extraction complète: ${e.message}")
            return@withContext allTracks
        }
    }
    
    /**
     * Vérifie si une vidéo YouTube a probablement une tracklist dans les commentaires.
     * Basé sur des mots-clés courants dans les titres et descriptions.
     */
    suspend fun hasLikelyTracklist(videoId: String, videoTitle: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            // Vérifier le titre de la vidéo
            val title = videoTitle?.lowercase() ?: ""
            val tracklistKeywords = listOf(
                "mix", "set", "live", "dj", "techno", "house", "trance",
                "tracklist", "setlist", "timestamps", "track list"
            )
            
            if (tracklistKeywords.any { keyword -> title.contains(keyword, ignoreCase = true) }) {
                return@withContext true
            }
            
            // Vérifier la description
            val description = youTubeStreamResolver.getDescription(videoId)?.lowercase() ?: ""
            if (tracklistKeywords.any { keyword -> description.contains(keyword, ignoreCase = true) }) {
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la vérification de tracklist: ${e.message}")
            return@withContext false
        }
    }
}