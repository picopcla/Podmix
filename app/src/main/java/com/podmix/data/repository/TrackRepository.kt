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
import com.podmix.service.TracklistService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val tracklistService: TracklistService,
    private val tracklistApi: TracklistApi,
    private val mixcloudApi: MixcloudApi
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
        audioUrl: String? = null
    ): Boolean {
        val existing = trackDao.getByEpisodeId(episodeId)
        if (existing.isNotEmpty()) {
            val allUniform = existing.all { it.source == "uniform" }
            if (!allUniform) return true
        }

        val query = buildString {
            podcastName?.let { append("$it ") }
            append(episodeTitle)
        }

        val t0 = System.currentTimeMillis()
        fun elapsed() = "+${System.currentTimeMillis() - t0}ms"
        fun status(pct: Int, msg: String) {
            val line = "[$pct%] $msg"
            Log.i("TrackRepo", line)
            onStatus?.invoke(line)
        }

        Log.i("TrackRepo", "detectAndSaveTracks: isLiveSet=$isLiveSet, ytId=$youtubeVideoId, audioUrl=${audioUrl?.take(50)}, title=$episodeTitle")

        // Health check rapide du serveur Shazam (fail fast)
        val shazamServerUp = withContext(Dispatchers.IO) {
            try {
                val conn = java.net.URL("http://10.0.2.2:8099/health").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.responseCode == 200
            } catch (_: Exception) { false }
        }
        Log.i("TrackRepo", "Shazam server up=$shazamServerUp ${elapsed()}")

        if (isLiveSet) {
            // LIVE SETS: Description YouTube → yt-dlp → Mixcloud → MixesDB → Shazam/IA

            // 1. Description YouTube (instant)
            val t1 = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.RUNNING))
            status(5, "📄 Analyse description YouTube... ${elapsed()}")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            val descElapsed = System.currentTimeMillis() - t1
            if (fromDesc.isNotEmpty()) {
                val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }
                saveTracks(episodeId, fromDesc, hasTimestamps, episodeDurationSec,
                    if (hasTimestamps) "timestamped" else "uniform")
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed))
                listOf("yt-dlp", "Mixcloud", "MixesDB", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${fromDesc.size} tracks — description (timestamps=${hasTimestamps}) ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.FAILED,
                elapsedMs = descElapsed, reason = "aucun track détecté"))
            status(20, "Description: rien trouvé — passage yt-dlp ${elapsed()}")

            // 2. yt-dlp chapters (serveur requis)
            if (youtubeVideoId != null && shazamServerUp) {
                val t2 = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.RUNNING))
                status(25, "🎬 yt-dlp chapters YouTube... ${elapsed()}")
                val fromChapters = tryYtDlpChapters(youtubeVideoId)
                val ytdlpElapsed = System.currentTimeMillis() - t2
                if (fromChapters != null && fromChapters.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromChapters, true, 0, "ytdlp")
                    onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SUCCESS,
                        trackCount = fromChapters.size, elapsedMs = ytdlpElapsed))
                    listOf("Mixcloud", "MixesDB", "Shazam/IA").forEach {
                        onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                    }
                    status(100, "✅ ${fromChapters.size} tracks — yt-dlp ${elapsed()}")
                    return true
                }
                val ytdlpReason = if (fromChapters == null) "erreur serveur" else "pas de chapters"
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.FAILED,
                    elapsedMs = ytdlpElapsed, reason = ytdlpReason))
                status(38, "yt-dlp: $ytdlpReason ${elapsed()}")
            } else {
                val reason = if (youtubeVideoId == null) "pas de vidéo YouTube" else "serveur hors ligne"
                onSourceResult?.invoke(SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = reason))
                status(25, "⚠️ yt-dlp skippé: $reason ${elapsed()}")
            }

            // 3. Mixcloud API (~1s)
            val t3 = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.RUNNING))
            status(42, "🔍 Mixcloud: $query ${elapsed()}")
            val fromMixcloud = tryMixcloud(query)
            val mixcloudElapsed = System.currentTimeMillis() - t3
            if (fromMixcloud != null && fromMixcloud.size >= 3) {
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, fromMixcloud, true, 0, "mixcloud")
                onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.SUCCESS,
                    trackCount = fromMixcloud.size, elapsedMs = mixcloudElapsed))
                listOf("MixesDB", "Shazam/IA").forEach {
                    onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED))
                }
                status(100, "✅ ${fromMixcloud.size} tracks — Mixcloud ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("Mixcloud", SourceStatus.FAILED,
                elapsedMs = mixcloudElapsed, reason = "aucun résultat"))
            status(55, "Mixcloud: pas de résultat ${elapsed()}")

            // 4. MixesDB (serveur requis)
            if (shazamServerUp) {
                val t4 = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.RUNNING))
                status(58, "📚 MixesDB: $query ${elapsed()}")
                val fromMixesDb = tryMixesDb(query)
                val mixesdbElapsed = System.currentTimeMillis() - t4
                if (fromMixesDb != null && fromMixesDb.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromMixesDb, false, episodeDurationSec, "mixesdb")
                    onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.SUCCESS,
                        trackCount = fromMixesDb.size, elapsedMs = mixesdbElapsed))
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED))
                    status(100, "✅ ${fromMixesDb.size} tracks — MixesDB ${elapsed()}")
                    return true
                }
                onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.FAILED,
                    elapsedMs = mixesdbElapsed, reason = "aucun résultat"))
                status(70, "MixesDB: pas de résultat ${elapsed()}")
            } else {
                onSourceResult?.invoke(SourceResult("MixesDB", SourceStatus.SKIPPED, reason = "serveur hors ligne"))
                status(58, "⚠️ MixesDB skippé: serveur hors ligne ${elapsed()}")
            }

            // 5. Shazam/IA (3-5 min, dernier recours)
            if (youtubeVideoId != null && shazamServerUp) {
                val t5 = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                status(73, "🎵 Shazam/IA — analyse audio... ${elapsed()}")
                val fromShazam = tryShazamAnalysisVerbose(youtubeVideoId) { pct, msg ->
                    status(73 + (pct * 0.22f).toInt(), "🎵 Shazam $msg ${elapsed()}")
                }
                val shazamElapsed = System.currentTimeMillis() - t5
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
                status(73, "⚠️ Serveur Shazam hors ligne — skip ${elapsed()}")
            } else {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = "pas de vidéo YouTube"))
            }

        } else {
            // PODCASTS DJ: Description → Shazam pour timestamps précis

            // 1. Description locale — instant
            status(10, "📄 Analyse description... ${elapsed()}")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            status(30, "Description: ${fromDesc.size} tracks trouvés ${elapsed()}")

            if (fromDesc.size >= 3) {
                val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }

                if (hasTimestamps) {
                    saveTracks(episodeId, fromDesc, true, episodeDurationSec, "timestamped")
                    status(100, "✅ ${fromDesc.size} tracks — timestamps description ${elapsed()}")
                    return true
                }

                // Pas de timestamps — afficher d'abord version uniforme
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")

                val shazamUrl = if (youtubeVideoId != null) "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
                if (shazamUrl != null && shazamServerUp) {
                    status(40, "🎵 Shazam pour timestamps précis... ${elapsed()}")
                    val fromShazam = tryShazamAnalysisByUrl(shazamUrl)
                    if (fromShazam != null && fromShazam.size >= 3) {
                        trackDao.deleteByEpisode(episodeId)
                        saveTracks(episodeId, fromShazam, true, 0, "shazam")
                        status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
                        return true
                    }
                    status(90, "Shazam: pas de résultat — timestamps estimés ${elapsed()}")
                } else if (shazamUrl != null) {
                    status(40, "⚠️ Serveur Shazam hors ligne — timestamps estimés ${elapsed()}")
                }

                status(100, "✅ ${fromDesc.size} tracks — estimé ${elapsed()}")
                return true
            }

            // Pas de description — Shazam direct
            val shazamUrl2 = if (youtubeVideoId != null) "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
            if (shazamUrl2 != null && shazamServerUp) {
                status(50, "🎵 Shazam direct (pas de description)... ${elapsed()}")
                val fromShazam = tryShazamAnalysisByUrl(shazamUrl2)
                if (fromShazam != null && fromShazam.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam, true, 0, "shazam")
                    status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
                    return true
                }
            } else if (shazamUrl2 != null) {
                status(50, "⚠️ Serveur Shazam hors ligne ${elapsed()}")
            }

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

    private suspend fun saveTracks(
        episodeId: Int, tracks: List<ParsedTrack>,
        hasTimestamps: Boolean, durationSec: Int, source: String
    ) {
        val entities = tracks.mapIndexed { index, p ->
            val startSec = if (hasTimestamps) p.startTimeSec
            else if (durationSec > 0) (durationSec.toFloat() * index / tracks.size)
            else 0f
            TrackEntity(
                episodeId = episodeId, position = index,
                title = p.title, artist = p.artist,
                startTimeSec = startSec, source = source
            )
        }
        trackDao.insertAll(entities)
    }

    private suspend fun tryShazamAnalysis(videoId: String): List<ParsedTrack>? {
        return tryShazamAnalysisByUrl("https://www.youtube.com/watch?v=$videoId")
    }

    private suspend fun tryShazamAnalysisVerbose(
        videoId: String,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<ParsedTrack>? {
        return try {
            onProgress?.invoke(5, "envoi URL YouTube...")
            val response = kotlinx.coroutines.withTimeout(300_000) { // 5 min max
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
            val response = kotlinx.coroutines.withTimeout(300_000) { // 5 min max
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

    private suspend fun tryMixesDb(query: String): List<ParsedTrack>? {
        return try {
            val response = kotlinx.coroutines.withTimeout(10_000) {
                withContext(Dispatchers.IO) {
                    tracklistApi.getMixesDb(query)
                }
            }
            response.tracks?.takeIf { it.isNotEmpty() }?.map { t ->
                ParsedTrack(t.artist, t.title, t.startTimeSec.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "MixesDB: ${e.message}")
            null
        }
    }

    private suspend fun try1001Tracklists(query: String): List<ParsedTrack>? {
        return try {
            val response = kotlinx.coroutines.withTimeout(60_000) {
                withContext(Dispatchers.IO) {
                    tracklistApi.getTracklist(query)
                }
            }
            response.tracks?.takeIf { it.isNotEmpty() }?.map { t ->
                ParsedTrack(t.artist, t.title, t.startTimeSec.toFloat())
            }
        } catch (e: Exception) {
            Log.d("TrackRepo", "1001TL: ${e.message}")
            null
        }
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
