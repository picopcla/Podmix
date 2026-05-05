package com.podmix.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YTDjSearch"

/** Résultat d'une vidéo YouTube (live set candidat) */
data class YtDjSet(
    val title: String,
    val videoId: String,
    val durationSec: Long,   // 0 si inconnu
    val viewCount: Long      // 0 si inconnu
)

/**
 * Recherche des live sets DJ sur YouTube via NewPipe SearchInfo.
 * Utilisé comme fallback quand SoundCloud retourne < 20 résultats valides.
 */
@Singleton
class YouTubeDjSetSearcher @Inject constructor() {

    private val MIN_DURATION_SEC = 45 * 60L  // 45 min

    /**
     * Cherche "[artist] live set" sur YouTube, filtre > 45 min.
     * Retourne jusqu'à [maxResults] sets valides.
     */
    suspend fun search(artistName: String, maxResults: Int = 20): List<YtDjSet> =
        withContext(Dispatchers.IO) {
            try {
                NewPipe.init(DownloaderImpl.getInstance())
            } catch (_: Exception) { /* déjà initialisé */ }

            val results = mutableListOf<YtDjSet>()
            val queries = listOf(
                "$artistName live set",
                "$artistName dj set",
                "$artistName mix"
            )

            for (query in queries) {
                if (results.size >= maxResults) break
                try {
                    Log.i(TAG, "Searching YouTube: '$query'")
                    val info = SearchInfo.getInfo(
                        ServiceList.YouTube,
                        ServiceList.YouTube.searchQHFactory.fromQuery(query)
                    )
                    val items = info.relatedItems
                        .filterIsInstance<StreamInfoItem>()

                    for (item in items) {
                        if (results.size >= maxResults) break
                        val dur = item.duration   // secondes
                        val id = extractVideoId(item.url) ?: continue
                        // Filtre durée : >= 45 min (ou inconnue → on garde)
                        if (dur > 0 && dur < MIN_DURATION_SEC) {
                            Log.d(TAG, "Skip short (${dur}s): '${item.name}'")
                            continue
                        }
                        // Skip podcasts, singles, séries numérotées
                        if (isPodcastOrSingle(item.name)) {
                            Log.d(TAG, "Skip podcast/single: '${item.name}'")
                            continue
                        }
                        Log.i(TAG, "YT result: '${item.name}' (${dur}s) → $id")
                        results += YtDjSet(
                            title = item.name,
                            videoId = id,
                            durationSec = dur,
                            viewCount = item.viewCount
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "YT search failed for '$query': ${e.message}")
                }
            }

            Log.i(TAG, "YT search '$artistName': ${results.size} sets found")
            results
        }

    private fun extractVideoId(url: String): String? =
        Regex("""(?:youtube\.com/watch\?v=|youtu\.be/)([a-zA-Z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1)

    /**
     * Filtre complet — même logique que SoundCloudArtistScraper.isPodcastTitle().
     * Exclut : singles, podcasts radio numérotés, séries avec numéro d'épisode.
     */
    private fun isPodcastOrSingle(title: String): Boolean {
        val t = title.lowercase()

        // Singles
        val singleKeywords = listOf(
            "original mix", "extended mix", "radio edit", "club mix",
            "vip mix", "dub mix", "rework", "remaster",
            " feat.", " ft.", " featuring "
        )
        if (singleKeywords.any { t.contains(it) }) return true

        // Radio / podcast explicites
        val radioKeywords = listOf(
            "podcast", "radio show", "radio mix", "weekly", "monthly",
            "sessions ep", "radio episode", "radio #",
            "presents go on air", "go on air",
            "interview", "talk show", "q&a", "documentary"
        )
        if (radioKeywords.any { t.contains(it) }) return true

        // "radio" seul comme mot
        if (Regex("""\bradio\b""").containsMatchIn(t)) return true

        // "episode", "ep.", "vol.", "#NN"
        val episodePattern = Regex(
            """\b(episode|ep\.?|vol\.?)\s*\d+\b|#\s*\d{2,}""",
            RegexOption.IGNORE_CASE
        )
        if (episodePattern.containsMatchIn(title)) return true

        // Série numérotée : titre se termine par entier > 19, sans contexte live/venue
        val liveContext = listOf("live", "at", "@", "festival", "stage", "club", "arena",
            "techno", "trance", "set", "mix", "boiler", "cercle")
        val hasLiveContext = liveContext.any { t.contains(it) }
        if (!hasLiveContext) {
            val seriesPattern = Regex("""\b([2-9]\d{1,3}|[1-9]\d{2,})\s*(?:[-–|({].*)?$""")
            val yearPattern   = Regex("""\b(19|20)\d{2}\s*(?:[-–|({].*)?$""")
            if (seriesPattern.containsMatchIn(title.trim()) && !yearPattern.containsMatchIn(title.trim())) return true
        }

        return false
    }
}
