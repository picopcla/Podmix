package com.podmix.data.repository

import android.util.Log
import com.podmix.data.api.MixcloudApi
import com.podmix.data.api.TracklistApi
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.local.entity.TrackEntity
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

        Log.i("TrackRepo", "detectAndSaveTracks: isLiveSet=$isLiveSet, ytId=$youtubeVideoId, audioUrl=${audioUrl?.take(50)}, title=$episodeTitle")

        if (isLiveSet) {
            // LIVE SETS: Shazam first (via VM), then Mixcloud, then description

            // 1. Shazam analysis via VM (if YouTube video)
            if (youtubeVideoId != null) {
                onStatus?.invoke("🎵 Analyse Shazam en cours (~3 min)...")
                val fromShazam = tryShazamAnalysis(youtubeVideoId)
                if (fromShazam != null && fromShazam.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam, true, 0, "shazam")
                    onStatus?.invoke("${fromShazam.size} tracks — Shazam ✓")
                    return true
                }
            }

            // 2. Mixcloud sections
            onStatus?.invoke("Recherche Mixcloud...")
            val fromMixcloud = tryMixcloud(query)
            if (fromMixcloud != null && fromMixcloud.size >= 3) {
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, fromMixcloud, true, 0, "mixcloud")
                onStatus?.invoke("${fromMixcloud.size} tracks — Mixcloud ✓")
                return true
            }

            // 3. Description (YouTube description with timestamps)
            onStatus?.invoke("Analyse de la description...")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            if (fromDesc.isNotEmpty()) {
                val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }
                saveTracks(episodeId, fromDesc, hasTimestamps, episodeDurationSec,
                    if (hasTimestamps) "timestamped" else "uniform")
                onStatus?.invoke("${fromDesc.size} tracks — description")
                return true
            }
        } else {
            // PODCASTS DJ: Description first (fast), then Shazam for exact timestamps

            // 1. Description locale — instant
            onStatus?.invoke("Analyse de la description...")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }

            if (fromDesc.size >= 3) {
                val hasTimestamps = fromDesc.any { it.startTimeSec > 0f }

                if (hasTimestamps) {
                    // Already has timestamps — save and done
                    saveTracks(episodeId, fromDesc, true, episodeDurationSec, "timestamped")
                    onStatus?.invoke("${fromDesc.size} tracks — description ✓")
                    return true
                }

                // No timestamps — save uniform first (instant display)
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                onStatus?.invoke("${fromDesc.size} tracks — Shazam en cours...")

                // Try Shazam for exact timestamps
                val shazamUrl = if (youtubeVideoId != null) "https://www.youtube.com/watch?v=$youtubeVideoId"
                    else audioUrl
                if (shazamUrl != null) {
                    onStatus?.invoke("🎵 Analyse Shazam...")
                    val fromShazam = tryShazamAnalysisByUrl(shazamUrl)
                    if (fromShazam != null && fromShazam.size >= 3) {
                        trackDao.deleteByEpisode(episodeId)
                        saveTracks(episodeId, fromShazam, true, 0, "shazam")
                        onStatus?.invoke("${fromShazam.size} tracks — Shazam ✓")
                        return true
                    }
                }

                onStatus?.invoke("${fromDesc.size} tracks — estimé")
                return true
            }

            // No description tracks — try Shazam directly
            val shazamUrl2 = if (youtubeVideoId != null) "https://www.youtube.com/watch?v=$youtubeVideoId"
                else audioUrl
            if (shazamUrl2 != null) {
                onStatus?.invoke("🎵 Analyse Shazam...")
                val fromShazam = tryShazamAnalysisByUrl(shazamUrl2)
                if (fromShazam != null && fromShazam.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam, true, 0, "shazam")
                    onStatus?.invoke("${fromShazam.size} tracks — Shazam ✓")
                    return true
                }
            }

            if (fromDesc.isNotEmpty()) {
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                onStatus?.invoke("${fromDesc.size} tracks — estimé")
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

    private suspend fun tryShazamAnalysisByUrl(url: String): List<ParsedTrack>? {
        return try {
            val response = kotlinx.coroutines.withTimeout(600_000) { // 10 min timeout (double-scan)
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
