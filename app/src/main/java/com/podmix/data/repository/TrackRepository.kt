package com.podmix.data.repository

import android.util.Log
import com.podmix.data.api.MixcloudApi
import com.podmix.data.api.TracklistApi
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.local.entity.TrackEntity
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus
import com.podmix.domain.model.Track
import com.podmix.service.ParsedTrack
import com.podmix.service.TracklistWebScraper
import com.podmix.service.TracklistService
import com.podmix.service.YouTubeCommentsService
import com.podmix.service.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val tracklistService: TracklistService,
    private val tracklistApi: TracklistApi,
    private val mixcloudApi: MixcloudApi,
    private val youTubeCommentsService: YouTubeCommentsService,
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val okHttpClient: OkHttpClient,
    private val tracklistWebScraper: TracklistWebScraper
) {

    fun getTracksForEpisode(episodeId: Int): Flow<List<Track>> {
        return trackDao.getByEpisodeIdFlow(episodeId).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun getTrackDao() = trackDao

    suspend fun detectAndSaveTracks(
        episodeId: Int,
        description: String?,
        episodeTitle: String,
        podcastName: String? = null,
        episodeDurationSec: Int = 0,
        onStatus: ((String) -> Unit)? = null,
        onSourceResult: ((SourceResult) -> Unit)? = null,
        isLiveSet: Boolean = false,
        youtubeVideoId: String? = null,
        audioUrl: String? = null,
        tracklistPageUrl: String? = null
    ): Boolean {
        val existing = trackDao.getByEpisodeId(episodeId)
        if (existing.isNotEmpty()) {
            val allUniform = existing.all { it.source == "uniform" }
            if (!allUniform) return true
        }

        // Ne préfixer avec le nom du podcast que s'il n'est pas déjà dans le titre
        // (évite "KOROLOVA Korolova - Live @..." qui pollue les recherches DDG)
        val query = buildString {
            if (podcastName != null &&
                !episodeTitle.contains(podcastName, ignoreCase = true)) {
                append("$podcastName ")
            }
            append(episodeTitle)
        }.take(120) // cap pour éviter les requêtes trop longues

        val t0 = System.currentTimeMillis()
        fun elapsed() = "+${System.currentTimeMillis() - t0}ms"
        fun status(pct: Int, msg: String) {
            val line = "[$pct%] $msg"
            Log.i("TrackRepo", line)
            onStatus?.invoke(line)
        }

        Log.i("TrackRepo", "detectAndSaveTracks: isLiveSet=$isLiveSet, ytId=$youtubeVideoId, audioUrl=${audioUrl?.take(50)}, title=$episodeTitle")

        // Health check rapide du serveur (fail fast, 1.5s max)
        val shazamServerUp = withContext(Dispatchers.IO) {
            try {
                val conn = java.net.URL("http://192.168.10.5:8099/health").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.responseCode == 200
            } catch (_: Exception) { false }
        }
        Log.i("TrackRepo", "Server up=$shazamServerUp ${elapsed()}")

        if (isLiveSet) {
            // ── LIVE SETS ──────────────────────────────────────────────────────────
            // Ordre : on-device d'abord, serveur en dernier recours
            //
            // ON-DEVICE (pas de serveur nécessaire)
            // 1. 1001Tracklists        — WebView (source la plus fiable, ~8s)
            // 2. Description YouTube  — parsing local (instant)
            // 3. Commentaires YouTube — NewPipe (~3-5s)
            // 4. Mixcloud             — Retrofit direct
            // 5. MixesDB              — API MediaWiki direct (~2s)
            //
            // SERVEUR (fallback uniquement)
            // 6. yt-dlp chapters      — serveur si dispo
            // 7. Shazam/IA            — fingerprinting audio, dernier recours

            // ── 1. 1001Tracklists (WebView on-device) ──
            val t1001 = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.RUNNING))
            status(5, "🌐 1001Tracklists WebView... ${elapsed()}")
            val from1001 = try1001TL(query, knownUrl = tracklistPageUrl)
            val elapsed1001 = System.currentTimeMillis() - t1001
            val has1001Timestamps = from1001?.any { it.startTimeSec > 0f } == true
            if (from1001 != null && from1001.size >= 3 && has1001Timestamps) {
                // 1001TL avec timestamps → résultat parfait, on s'arrête
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, from1001, true, 0, "1001tl")
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SUCCESS,
                    trackCount = from1001.size, elapsedMs = elapsed1001))
                listOf("Description YouTube", "Commentaires YouTube", "Mixcloud", "MixesDB", "yt-dlp", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${from1001.size} tracks — 1001Tracklists ${elapsed()}")
                return true
            }
            if (from1001 != null && from1001.size >= 3) {
                // 1001TL sans timestamps → tracklist trouvée, on s'arrête (pas de chasse aux timestamps)
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, from1001, false, episodeDurationSec, "1001tl")
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SUCCESS,
                    trackCount = from1001.size, elapsedMs = elapsed1001))
                listOf("Description YouTube", "Commentaires YouTube", "Mixcloud", "MixesDB", "yt-dlp", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${from1001.size} tracks — 1001Tracklists (sans timestamps) ${elapsed()}")
                return true
            } else {
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.FAILED,
                    elapsedMs = elapsed1001, reason = "aucun résultat"))
                status(30, "1001TL: rien trouvé ${elapsed()}")
            }

            // ── 2. Description YouTube (instant, local) ──
            val tD = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.RUNNING))
            status(35, "📄 Analyse description... ${elapsed()}")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            val descElapsed = System.currentTimeMillis() - tD
            if (fromDesc.isNotEmpty()) {
                val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }
                saveTracks(episodeId, fromDesc, hasTimestamps, episodeDurationSec,
                    if (hasTimestamps) "timestamped" else "uniform")
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed))
                listOf("Commentaires YouTube", "yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${fromDesc.size} tracks — description ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.FAILED,
                elapsedMs = descElapsed, reason = "aucun track détecté"))
            status(40, "Description: rien trouvé ${elapsed()}")

            // ── 3. Commentaires YouTube (NewPipe on-device) ──
            if (youtubeVideoId != null) {
                val tC = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("Commentaires YouTube", SourceStatus.RUNNING))
                status(45, "💬 Commentaires YouTube (on-device)... ${elapsed()}")
                val fromComments = tryCommentsOnDevice(youtubeVideoId)
                val commentsElapsed = System.currentTimeMillis() - tC
                if (fromComments != null && fromComments.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromComments, true, 0, "comments")
                    onSourceResult?.invoke(SourceResult("Commentaires YouTube", SourceStatus.SUCCESS,
                        trackCount = fromComments.size, elapsedMs = commentsElapsed))
                    listOf("yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA").forEach {
                        onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                    }
                    status(100, "✅ ${fromComments.size} tracks — commentaires YouTube ${elapsed()}")
                    return true
                }
                val commentsReason = if (fromComments == null) "erreur NewPipe" else "aucun commentaire avec tracklist"
                onSourceResult?.invoke(SourceResult("Commentaires YouTube", SourceStatus.FAILED,
                    elapsedMs = commentsElapsed, reason = commentsReason))
                status(55, "Commentaires on-device: $commentsReason ${elapsed()}")
            } else {
                onSourceResult?.invoke(SourceResult("Commentaires YouTube", SourceStatus.SKIPPED, reason = "pas de vidéo YouTube"))
            }

            // ── 4. Mixcloud (Retrofit direct) ──
            val tM = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.RUNNING))
            status(60, "🔍 Mixcloud: $query ${elapsed()}")
            val fromMixcloud = tryMixcloud(query)
            val mixcloudElapsed = System.currentTimeMillis() - tM
            if (fromMixcloud != null && fromMixcloud.size >= 3) {
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, fromMixcloud, true, 0, "mixcloud")
                onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.SUCCESS,
                    trackCount = fromMixcloud.size, elapsedMs = mixcloudElapsed))
                listOf("yt-dlp", "MixesDB", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${fromMixcloud.size} tracks — Mixcloud ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.FAILED,
                elapsedMs = mixcloudElapsed, reason = "aucun résultat"))
            status(65, "Mixcloud: pas de résultat ${elapsed()}")

            // ── 5. MixesDB (API directe on-device) ──
            val tMDB = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.RUNNING))
            status(70, "📚 MixesDB: $query ${elapsed()}")
            val fromMixesDb = tryMixesDbDirect(query)
            val mixesdbElapsed = System.currentTimeMillis() - tMDB
            if (fromMixesDb != null && fromMixesDb.size >= 3) {
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, fromMixesDb, false, episodeDurationSec, "mixesdb")
                onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.SUCCESS,
                    trackCount = fromMixesDb.size, elapsedMs = mixesdbElapsed))
                listOf("yt-dlp", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${fromMixesDb.size} tracks — MixesDB ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.FAILED,
                elapsedMs = mixesdbElapsed, reason = "aucun résultat"))
            status(75, "MixesDB: pas de résultat ${elapsed()}")

            // ── 6. yt-dlp chapters (serveur, fallback) ──
            if (youtubeVideoId != null && shazamServerUp) {
                val tY = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.RUNNING))
                status(80, "🎬 yt-dlp chapters (serveur)... ${elapsed()}")
                val fromChapters = tryYtDlpChapters(youtubeVideoId)
                val ytdlpElapsed = System.currentTimeMillis() - tY
                if (fromChapters != null && fromChapters.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromChapters, true, 0, "ytdlp")
                    onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SUCCESS,
                        trackCount = fromChapters.size, elapsedMs = ytdlpElapsed))
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED))
                    status(100, "✅ ${fromChapters.size} tracks — yt-dlp ${elapsed()}")
                    return true
                }
                val ytdlpReason = if (fromChapters == null) "erreur serveur" else "pas de chapters"
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.FAILED,
                    elapsedMs = ytdlpElapsed, reason = ytdlpReason))
                status(85, "yt-dlp: $ytdlpReason ${elapsed()}")
            } else {
                val reason = if (youtubeVideoId == null) "pas de vidéo YouTube" else "serveur hors ligne"
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = reason))
            }

            // ── 6. Shazam/IA (serveur, dernier recours) ──
            if (youtubeVideoId != null && shazamServerUp) {
                val tS = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                status(78, "🎵 Shazam/IA — analyse audio... ${elapsed()}")
                val fromShazam = tryShazamAnalysisVerbose(youtubeVideoId) { pct, msg ->
                    status(78 + (pct * 0.20f).toInt(), "🎵 Shazam $msg ${elapsed()}")
                }
                val shazamElapsed = System.currentTimeMillis() - tS
                if (fromShazam != null && fromShazam.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam, true, 0, "shazam")
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                        trackCount = fromShazam.size, elapsedMs = shazamElapsed))
                    status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
                    return true
                }
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
                    elapsedMs = shazamElapsed, reason = "pas de résultat"))
                status(98, "Shazam: pas de résultat ${elapsed()}")
            } else if (youtubeVideoId != null) {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = "serveur hors ligne"))
            } else {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = "pas de vidéo YouTube"))
            }

            // ── Fallback final : 1001TL sans timestamps (meilleur que rien) ──
            if (from1001 != null && from1001.size >= 3) {
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, from1001, false, episodeDurationSec, "1001tl")
                status(100, "✅ ${from1001.size} tracks — 1001TL (sans timestamps) ${elapsed()}")
                return true
            }

        } else {
            // ── PODCASTS ────────────────────────────────────────────────────────
            // Pipeline : Description RSS → 1001Tracklists (DDG) → Shazam
            // Sources non applicables aux podcasts → SKIPPED immédiatement

            listOf("Commentaires YouTube", "Mixcloud", "MixesDB", "yt-dlp").forEach {
                onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED, reason = "non applicable aux podcasts"))
            }

            // ── 1. Description RSS ──
            val tD = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.RUNNING))
            status(10, "📄 Analyse description... ${elapsed()}")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            val descElapsed = System.currentTimeMillis() - tD
            status(30, "Description: ${fromDesc.size} tracks trouvés ${elapsed()}")

            val descHasTimestamps = fromDesc.any { it.startTimeSec > 0f }
            if (fromDesc.size >= 3 && descHasTimestamps) {
                // Description avec timestamps → parfait, terminé
                saveTracks(episodeId, fromDesc, true, episodeDurationSec, "timestamped")
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed))
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SKIPPED,
                    reason = "description RSS suffisante"))
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "description RSS suffisante"))
                status(100, "✅ ${fromDesc.size} tracks — timestamps description ${elapsed()}")
                return true
            }

            // Marquer la description (sans timestamps ou insuffisante)
            if (fromDesc.size >= 3) {
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed, reason = "sans timestamps"))
            } else {
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.FAILED,
                    elapsedMs = descElapsed,
                    reason = if (fromDesc.isEmpty()) "aucun track détecté" else "trop peu de tracks (${fromDesc.size})"))
            }

            // ── 2. 1001Tracklists via DDG ──
            val t1001 = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.RUNNING))
            status(35, "🌐 1001Tracklists... ${elapsed()}")
            val from1001 = try1001TL(query, knownUrl = null)
            val elapsed1001 = System.currentTimeMillis() - t1001
            if (from1001 != null && from1001.size >= 3) {
                val has1001Timestamps = from1001.any { it.startTimeSec > 0f }
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, from1001, has1001Timestamps, episodeDurationSec, "1001tl")
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SUCCESS,
                    trackCount = from1001.size, elapsedMs = elapsed1001))
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "1001TL suffisant"))
                status(100, "✅ ${from1001.size} tracks — 1001Tracklists ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.FAILED,
                elapsedMs = elapsed1001, reason = "aucun résultat"))
            status(50, "1001TL: rien trouvé ${elapsed()}")

            // ── 3. Description sans timestamps + Shazam pour améliorer ──
            if (fromDesc.size >= 3) {
                // Afficher d'abord la version uniforme (feedback immédiat)
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                val shazamUrl = if (youtubeVideoId != null)
                    "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
                if (shazamUrl != null && shazamServerUp) {
                    val tS = System.currentTimeMillis()
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                    status(60, "🎵 Shazam pour timestamps précis... ${elapsed()}")
                    val fromShazam = tryShazamAnalysisByUrl(shazamUrl)
                    val shazamElapsed = System.currentTimeMillis() - tS
                    if (fromShazam != null && fromShazam.size >= 3) {
                        trackDao.deleteByEpisode(episodeId)
                        saveTracks(episodeId, fromShazam, true, 0, "shazam")
                        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                            trackCount = fromShazam.size, elapsedMs = shazamElapsed))
                        status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
                        return true
                    }
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
                        elapsedMs = shazamElapsed, reason = "pas de résultat"))
                    status(90, "Shazam: pas de résultat — timestamps estimés ${elapsed()}")
                } else {
                    val reason = if (shazamUrl == null) "pas de source audio" else "serveur hors ligne"
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = reason))
                }
                status(100, "✅ ${fromDesc.size} tracks — estimé ${elapsed()}")
                return true
            }

            // ── 4. Pas de description — Shazam direct ──
            val shazamUrl2 = if (youtubeVideoId != null)
                "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
            if (shazamUrl2 != null && shazamServerUp) {
                val tS2 = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                status(50, "🎵 Shazam direct (pas de description)... ${elapsed()}")
                val fromShazam2 = tryShazamAnalysisByUrl(shazamUrl2)
                val shazamElapsed2 = System.currentTimeMillis() - tS2
                if (fromShazam2 != null && fromShazam2.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam2, true, 0, "shazam")
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                        trackCount = fromShazam2.size, elapsedMs = shazamElapsed2))
                    status(100, "✅ ${fromShazam2.size} tracks — Shazam ${elapsed()}")
                    return true
                }
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
                    elapsedMs = shazamElapsed2, reason = "pas de résultat"))
            } else if (shazamUrl2 != null) {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "serveur hors ligne"))
                status(50, "⚠️ Serveur hors ligne ${elapsed()}")
            } else {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "pas de source audio"))
            }

            // Fallback : description uniforme si quelques tracks partiels
            if (fromDesc.isNotEmpty()) {
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                status(100, "✅ ${fromDesc.size} tracks — estimé ${elapsed()}")
                return true
            }
        }

        if (existing.isNotEmpty()) return true
        onStatus?.invoke("Aucune tracklist trouvée")
        return false
    }

    // ── On-device sources ──────────────────────────────────────────────────────

    private suspend fun tryCommentsOnDevice(videoId: String): List<ParsedTrack>? {
        return try {
            withContext(Dispatchers.IO) {
                val tracks = youTubeCommentsService.extractTracklistFromComments(videoId, maxComments = 200)
                tracks.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "Comments on-device: ${e.message}")
            null
        }
    }

    private suspend fun try1001TL(query: String, knownUrl: String? = null): List<ParsedTrack>? {
        return try {
            val tracklistUrl: String

            if (!knownUrl.isNullOrBlank() && knownUrl.contains("1001tracklists.com/tracklist/")) {
                // URL directe connue — pas besoin de DDG
                Log.i("TrackRepo", "1001TL using known URL: $knownUrl")
                tracklistUrl = knownUrl
            } else {
                // Nettoyer le titre : supprimer les suffixes YouTube descriptifs après " / " ou " | "
                val cleanQuery = query
                    .replace(Regex("""\s*/\s*.+$"""), "")
                    .replace(Regex("""\s*\|\s*.+$"""), "")
                    .trim()

                // Trouver l'URL via DuckDuckGo
                val searchUrl = "https://html.duckduckgo.com/html/?q=" +
                    URLEncoder.encode("site:1001tracklists.com $cleanQuery", "UTF-8")
                Log.i("TrackRepo", "1001TL DDG search: $searchUrl")
                val ddgClient = okHttpClient.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val ddgReq = okhttp3.Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "text/html")
                    .build()
                val ddgHtml = withContext(Dispatchers.IO) {
                    ddgClient.newCall(ddgReq).execute().use { it.body?.string() }
                } ?: run { Log.w("TrackRepo", "1001TL: DDG empty response"); return null }

                Log.i("TrackRepo", "1001TL DDG response size=${ddgHtml.length}")
                val urlMatch = Regex("""uddg=([^&"]+)""").find(ddgHtml)
                    ?: run { Log.w("TrackRepo", "1001TL: no uddg link in DDG response"); return null }
                val candidate = java.net.URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                Log.i("TrackRepo", "1001TL candidate URL: $candidate")
                if (!candidate.contains("1001tracklists.com/tracklist/")) {
                    Log.w("TrackRepo", "1001TL: URL not a tracklist page")
                    return null
                }
                tracklistUrl = candidate
            }

            // Scraper via WebView (page JS-rendered — extrait DOM complet avec timestamps)
            Log.i("TrackRepo", "1001TL launching WebView scraper: $tracklistUrl")
            tracklistWebScraper.scrape1001TL(tracklistUrl)
        } catch (e: Exception) {
            Log.w("TrackRepo", "1001TL exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryMixesDbDirect(query: String): List<ParsedTrack>? {
        return withContext(Dispatchers.IO) {
            try {
                val client = okHttpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val ua = "Mozilla/5.0 (Android; PodMix)"

                // 1. Search page
                val searchUrl = "https://www.mixesdb.com/api.php?action=query&list=search" +
                    "&srsearch=${URLEncoder.encode(query, "UTF-8")}&format=json&srlimit=1"
                val searchResp = client.newCall(
                    okhttp3.Request.Builder().url(searchUrl).header("User-Agent", ua).build()
                ).execute()
                val searchJson = searchResp.body?.string() ?: return@withContext null
                val results = JSONObject(searchJson)
                    .getJSONObject("query").getJSONArray("search")
                if (results.length() == 0) return@withContext null

                val pageId = results.getJSONObject(0).getInt("pageid")

                // 2. Fetch wikitext
                val parseUrl = "https://www.mixesdb.com/api.php?action=parse&pageid=$pageId&prop=wikitext&format=json"
                val parseResp = client.newCall(
                    okhttp3.Request.Builder().url(parseUrl).header("User-Agent", ua).build()
                ).execute()
                val parseJson = parseResp.body?.string() ?: return@withContext null
                val wikitext = JSONObject(parseJson)
                    .getJSONObject("parse").getJSONObject("wikitext").getString("*")

                parseMixesDbWikitext(wikitext)
            } catch (e: Exception) {
                Log.d("TrackRepo", "MixesDB direct: ${e.message}")
                null
            }
        }
    }

    private fun parseMixesDbWikitext(wikitext: String): List<ParsedTrack>? {
        val wikiLink = Regex("""\[\[(?:[^|\]]+\|)?([^\]]+)]]""")
        fun clean(s: String) = wikiLink.replace(s) { it.groupValues[1] }.trim()

        val tracks = mutableListOf<ParsedTrack>()
        for (line in wikitext.lines()) {
            val cells = line.trim().split("||").map { it.trim() }
            when {
                cells.size >= 3 -> {
                    val artist = clean(cells[1])
                    val title = clean(cells[2])
                    if (artist.isNotBlank() && title.isNotBlank())
                        tracks.add(ParsedTrack(artist, title, 0f))
                }
                cells.size == 2 && " - " in cells[1] -> {
                    val parts = cells[1].split(" - ", limit = 2)
                    val artist = clean(parts[0])
                    val title = clean(parts[1])
                    if (artist.isNotBlank() && title.isNotBlank())
                        tracks.add(ParsedTrack(artist, title, 0f))
                }
            }
        }
        return if (tracks.size >= 3) tracks else null
    }

    // ── Server-side sources ────────────────────────────────────────────────────

    private suspend fun tryYtDlpChapters(videoId: String): List<ParsedTrack>? {
        return try {
            val response = kotlinx.coroutines.withTimeout(15_000) {
                withContext(Dispatchers.IO) {
                    tracklistApi.getChapters(videoId)
                }
            }
            response.tracks?.takeIf { it.isNotEmpty() }?.map { c ->
                ParsedTrack("", c.title, c.startTimeSec.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "yt-dlp chapters: ${e.message}")
            null
        }
    }

    private suspend fun tryShazamAnalysisVerbose(
        videoId: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<ParsedTrack>? {
        return try {
            onProgress?.invoke(5, "envoi URL YouTube...")
            val response = kotlinx.coroutines.withTimeout(300_000) {
                withContext(Dispatchers.IO) {
                    tracklistApi.analyzeByUrl("https://www.youtube.com/watch?v=$videoId")
                }
            }
            onProgress?.invoke(95, "réponse reçue")
            response.tracks?.takeIf { it.isNotEmpty() }?.map { t ->
                ParsedTrack(t.artist, t.title, t.startTimeSec.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "Shazam verbose: ${e.message}")
            null
        }
    }

    private suspend fun tryShazamAnalysisByUrl(url: String): List<ParsedTrack>? {
        return try {
            val response = kotlinx.coroutines.withTimeout(300_000) {
                withContext(Dispatchers.IO) {
                    tracklistApi.analyzeByUrl(url)
                }
            }
            response.tracks?.takeIf { it.isNotEmpty() }?.map { t ->
                ParsedTrack(t.artist, t.title, t.startTimeSec.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "Shazam analysis: ${e.message}")
            null
        }
    }

    private suspend fun tryMixcloud(query: String): List<ParsedTrack>? {
        return try {
            val response = withContext(Dispatchers.IO) {
                mixcloudApi.search(query, "cloudcast")
            }
            val first = response.data?.firstOrNull() ?: return null
            val key = first.key
            val sections = withContext(Dispatchers.IO) {
                mixcloudApi.getSections(key)
            }
            val data = sections.data ?: return null
            if (data.isEmpty()) return null
            data.mapIndexed { i, s ->
                val artist = s.track?.artist?.name ?: s.artistName ?: "Unknown"
                val title = s.track?.name ?: s.songName ?: "Track ${i + 1}"
                ParsedTrack(artist, title, s.startTime.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "Mixcloud: ${e.message}")
            null
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    suspend fun saveTracksForEpisode(episodeId: Int, tracks: List<ParsedTrack>, hasTimestamps: Boolean) {
        saveTracks(episodeId, tracks, hasTimestamps, 0, "1001tl")
    }

    private suspend fun saveTracks(
        episodeId: Int, tracks: List<ParsedTrack>,
        hasTimestamps: Boolean, durationSec: Int, source: String
    ) {
        val effectiveDuration = if (durationSec > 0) durationSec else 5400 // 90 min par défaut pour les live sets
        val entities = tracks.mapIndexed { index, p ->
            val startSec = if (hasTimestamps) p.startTimeSec
            else (effectiveDuration.toFloat() * index / tracks.size)
            TrackEntity(
                episodeId = episodeId, position = index,
                title = p.title, artist = p.artist,
                startTimeSec = startSec, source = source
            )
        }
        trackDao.insertAll(entities)
    }

    suspend fun getTracksCountForEpisode(episodeId: Int): Int {
        return trackDao.getByEpisodeId(episodeId).size
    }

    suspend fun deleteTracksForEpisode(episodeId: Int) {
        trackDao.deleteByEpisode(episodeId)
    }

    suspend fun toggleFavorite(trackId: Int) {
        trackDao.toggleFavorite(trackId)
    }

    private fun TrackEntity.toDomain() = Track(
        id = id, episodeId = episodeId, position = position,
        title = title, artist = artist,
        startTimeSec = startTimeSec, endTimeSec = endTimeSec,
        isFavorite = isFavorite, spotifyUrl = spotifyUrl
    )
}
