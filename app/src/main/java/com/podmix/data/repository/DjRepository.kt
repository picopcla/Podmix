package com.podmix.data.repository

import android.util.Log
import com.podmix.data.api.DuckDuckGoApi
import com.podmix.data.api.MixcloudApi
import com.podmix.data.api.YouTubeSearchApi
import com.podmix.data.prefs.AppPreferences
import com.podmix.service.YouTubeStreamResolver
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.local.entity.PodcastEntity
import com.podmix.domain.model.Podcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DjRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val mixcloudApi: MixcloudApi,
    private val duckDuckGoApi: DuckDuckGoApi,
    private val trackRepository: TrackRepository,
    private val okHttpClient: OkHttpClient,
    private val youTubeSearchApi: YouTubeSearchApi,
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val applicationScope: CoroutineScope,
    private val appPreferences: AppPreferences
) {
    companion object {
        private val EXCLUDE_KEYWORDS = listOf(
            "episode", "ep.", "ep ", " #", "sessions ep",
            "presents go on air", "go on air",
            "vonyc sessions", "club elite sessions",
            "podcast", "weekly show", "weekly mix",
            "tribute", "remix)", "music video",
            "official video", "official audio", "lyric video",
            "tutorial", "interview", "reaction"
        )
    }

    fun getDjs(): Flow<List<Podcast>> =
        podcastDao.getByType("dj").map { entities ->
            entities.map { e ->
                val count = podcastDao.episodeCount(e.id)
                Podcast(e.id, e.name, e.logoUrl, e.description, e.rssFeedUrl, e.type, count)
            }
        }

    suspend fun getDjById(id: Int): Podcast? {
        val e = podcastDao.getById(id) ?: return null
        val count = podcastDao.episodeCount(e.id)
        return Podcast(e.id, e.name, e.logoUrl, e.description, e.rssFeedUrl, e.type, count)
    }

    suspend fun addDj(name: String): Int {
        val photoUrl = findDjPhoto(name)
        return podcastDao.insert(PodcastEntity(name = name, logoUrl = photoUrl, type = "dj")).toInt()
    }

    private suspend fun findDjPhoto(name: String): String? {
        // 1. DuckDuckGo
        try {
            val ddg = duckDuckGoApi.search("$name DJ").image
            if (!ddg.isNullOrBlank()) return ddg
        } catch (_: Exception) {}

        // 2. Mixcloud user avatar
        try {
            val mc = mixcloudApi.search(name, "cloudcast")
            val firstSet = mc.data?.firstOrNull()
            val pic = firstSet?.pictures?.extraLarge ?: firstSet?.pictures?.large
            if (!pic.isNullOrBlank()) return pic
        } catch (_: Exception) {}

        // 3. YouTube thumbnail from first search result
        try {
            val yt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                youTubeSearchApi.search(name, 1)
            }
            val thumb = yt.firstOrNull()?.thumbnail
            if (!thumb.isNullOrBlank()) return thumb
        } catch (_: Exception) {}

        return null
    }

    suspend fun refreshDjPhoto(djId: Int) {
        var dj = podcastDao.getById(djId) ?: return
        if (dj.logoUrl.isNullOrBlank()) {
            val photo = findDjPhoto(dj.name)
            if (photo != null) {
                podcastDao.update(dj.copy(logoUrl = photo))
            }
        }
    }

    suspend fun refreshDj(djId: Int) {
        var dj = podcastDao.getById(djId) ?: return

        // If DJ has no photo, try to find one
        if (dj.logoUrl.isNullOrBlank()) {
            val photo = findDjPhoto(dj.name)
            if (photo != null) {
                dj = dj.copy(logoUrl = photo)
                podcastDao.update(dj)
            }
        }

        // Collect all sets from both sources
        data class SetCandidate(
            val title: String,
            val mixcloudKey: String? = null,
            val youtubeVideoId: String? = null,
            val durationSeconds: Int = 0,
            val datePublished: Long = System.currentTimeMillis(),
            val artworkUrl: String? = null,
            val description: String? = null
        )

        val allSets = mutableListOf<SetCandidate>()

        // Strategy 1: Mixcloud (primary source for DJ live sets)
        try {
            val response = mixcloudApi.search(dj.name, "cloudcast")
            val sets = (response.data ?: emptyList())
                .filter { mc ->
                    val hasSlug = (mc.slug?.length ?: 0) > 0
                    val longEnough = (mc.audioLength ?: 0) > 1200
                    val isSet = isLiveSet(mc.name)
                    hasSlug && longEnough && isSet
                }

            for (mc in sets) {
                val dateMs = try {
                    mc.createdTime?.let { parseIso8601(it) }
                } catch (_: Exception) { null } ?: System.currentTimeMillis()

                val artwork = mc.pictures?.extraLarge
                    ?: mc.pictures?.w640
                    ?: mc.pictures?.large
                    ?: dj.logoUrl

                allSets.add(SetCandidate(
                    title = mc.name,
                    mixcloudKey = mc.key,
                    durationSeconds = mc.audioLength ?: 0,
                    datePublished = dateMs,
                    artworkUrl = artwork
                ))
            }
            Log.d("DjRepo", "Mixcloud: ${allSets.size} sets for ${dj.name}")
        } catch (e: Exception) {
            Log.w("DjRepo", "Mixcloud search failed: ${e.message}")
        }

        // Strategy 2: YouTube via innertube (additional sets, dedup with Mixcloud)
        try {
            val mixcloudTitles = allSets.map { it.title.lowercase() }
            val seen = mutableSetOf<String>()

            val queries = listOf(
                dj.name,
                "${dj.name} live",
            )

            val allResults = mutableListOf<com.podmix.data.api.YouTubeSearchResult>()
            for (query in queries) {
                val results = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    youTubeSearchApi.search(query, 20)
                }
                for (r in results) {
                    if (r.videoId !in seen) {
                        seen.add(r.videoId)
                        allResults.add(r)
                    }
                }
            }

            val ytSets = allResults
                .filter { it.durationSeconds > 1200 && isLiveSet(it.title) }
                .filter { yt ->
                    // Dedup: skip if title is very similar to a Mixcloud result
                    val ytLower = yt.title.lowercase()
                    mixcloudTitles.none { mcTitle ->
                        titleSimilarity(ytLower, mcTitle) > 0.6
                    }
                }

            for (item in ytSets) {
                // Skip description fetch during import (slow) — will be fetched during tracklist detection
                allSets.add(SetCandidate(
                    title = item.title,
                    youtubeVideoId = item.videoId,
                    durationSeconds = item.durationSeconds,
                    datePublished = parseUploadDate(item.publishedText),
                    artworkUrl = item.thumbnail ?: dj.logoUrl,
                    description = null
                ))
            }
            Log.d("DjRepo", "YouTube: ${ytSets.size} additional sets for ${dj.name}")
        } catch (e: Exception) {
            Log.w("DjRepo", "YouTube search failed: ${e.message}")
        }

        // Sort by date, keep N most recent (configured in settings)
        val maxLivesets = appPreferences.maxLivesetEpisodes.first()
        val topSets = allSets
            .sortedByDescending { it.datePublished }
            .take(maxLivesets)

        var inserted = 0
        for (set in topSets) {
            // Check for existing by mixcloudKey or youtubeVideoId
            if (set.mixcloudKey != null) {
                val existing = episodeDao.getByMixcloudKey(set.mixcloudKey, djId)
                if (existing != null) continue
            }
            if (set.youtubeVideoId != null) {
                val existing = episodeDao.getByYoutubeVideoId(set.youtubeVideoId, djId)
                if (existing != null) continue
            }
            // Also dedup by title
            val existingEps = episodeDao.getByPodcastIdSuspend(djId)
            if (existingEps.any { it.title == set.title }) continue

            val episode = EpisodeEntity(
                podcastId = djId,
                title = set.title,
                audioUrl = "",
                datePublished = set.datePublished,
                durationSeconds = set.durationSeconds,
                artworkUrl = set.artworkUrl ?: dj.logoUrl,
                episodeType = "liveset",
                youtubeVideoId = set.youtubeVideoId,
                description = set.description,
                mixcloudKey = set.mixcloudKey
            )
            val episodeId = episodeDao.insert(episode).toInt()
            inserted++

            // Detect tracklist in background
            val title = set.title
            val djName = dj.name
            val dur = set.durationSeconds
            val desc = set.description
            val mcKey = set.mixcloudKey
            applicationScope.launch {
                try {
                    // For Mixcloud sets, try to get sections tracklist directly
                    if (mcKey != null) {
                        tryMixcloudSections(episodeId, mcKey)
                    }
                    // Then fall through to general detection (1001TL priority for live sets)
                    trackRepository.detectAndSaveTracks(episodeId, desc, title, djName, dur, isLiveSet = true, youtubeVideoId = set.youtubeVideoId)
                } catch (e: Exception) {
                    Log.w("DjRepo", "Tracklist detect failed: ${e.message}")
                }
            }
        }

        podcastDao.update(dj.copy(lastCheckedAt = System.currentTimeMillis()))

        // Auto-clean: remove oldest listened sets beyond the configured limit
        episodeDao.deleteOldestListened(djId, maxLivesets)

        // Retroactive Mixcloud enrichment: YouTube-only episodes already in DB
        // may have a Mixcloud counterpart that wasn't found during initial import
        // (title similarity miss, or Mixcloud search failed at import time).
        // For each YouTube-only episode, search Mixcloud by DJ name + title and
        // link the mixcloudKey if found — streaming will then use Mixcloud (no throttle).
        applicationScope.launch {
            enrichYouTubeOnlyWithMixcloud(djId, dj.name)
        }

        if (inserted == 0 && topSets.isEmpty()) {
            Log.w("DjRepo", "No sets found for ${dj.name}")
        } else {
            Log.i("DjRepo", "Inserted $inserted new sets for ${dj.name}")
        }
    }

    /**
     * For each YouTube-only episode (no mixcloudKey), search Mixcloud by DJ name + episode title.
     * If a match is found (title similarity > 0.55), save the mixcloudKey so future streaming
     * uses Mixcloud instead of the throttled YouTube progressive stream.
     */
    private suspend fun enrichYouTubeOnlyWithMixcloud(djId: Int, djName: String) {
        val ytOnly = episodeDao.getYouTubeOnlyEpisodes(djId)
        if (ytOnly.isEmpty()) return
        Log.i("DjRepo", "Enrichment: ${ytOnly.size} YouTube-only episodes for $djName")

        for (ep in ytOnly) {
            try {
                val query = "$djName ${ep.title}"
                val response = mixcloudApi.search(djName, "cloudcast")
                val match = (response.data ?: emptyList())
                    .filter { (it.audioLength ?: 0) > 1200 }
                    .maxByOrNull { mc ->
                        titleSimilarity(ep.title.lowercase(), (mc.name ?: "").lowercase())
                    } ?: continue

                val sim = titleSimilarity(ep.title.lowercase(), (match.name ?: "").lowercase())
                val key = match.key ?: continue
                if (sim > 0.55 && key.isNotBlank()) {
                    // Verify not already used by another episode
                    val conflict = episodeDao.getByMixcloudKey(key, djId)
                    if (conflict != null && conflict.id != ep.id) continue

                    episodeDao.updateMixcloudKey(ep.id, key)
                    Log.i("DjRepo", "  ✓ enriched '${ep.title}' → Mixcloud $key (sim=${"%.2f".format(sim)})")
                } else {
                    Log.d("DjRepo", "  ✗ '${ep.title}' best sim=${"%.2f".format(sim)} (${match.name}) — skip")
                }
            } catch (e: Exception) {
                Log.w("DjRepo", "  enrichment failed for '${ep.title}': ${e.message}")
            }
        }
    }

    private suspend fun tryMixcloudSections(episodeId: Int, mixcloudKey: String) {
        try {
            val sections = mixcloudApi.getSections(mixcloudKey.trimStart('/'))
            val sectionData = sections.data
            if (sectionData.isNullOrEmpty()) return

            val tracks = sectionData.mapNotNull { section ->
                val artist = section.track?.artist?.name
                    ?: section.artistName
                    ?: return@mapNotNull null
                val title = section.track?.name
                    ?: section.songName
                    ?: return@mapNotNull null
                com.podmix.service.ParsedTrack(
                    artist = artist,
                    title = title,
                    startTimeSec = section.startTime.toFloat()
                )
            }

            if (tracks.size >= 3) {
                val trackDao = trackRepository.getTrackDao()
                trackDao.deleteByEpisode(episodeId)
                val entities = tracks.mapIndexed { index, t ->
                    com.podmix.data.local.entity.TrackEntity(
                        episodeId = episodeId, position = index,
                        title = t.title, artist = t.artist,
                        startTimeSec = t.startTimeSec, source = "mixcloud"
                    )
                }
                trackDao.insertAll(entities)
                Log.d("DjRepo", "Saved ${entities.size} Mixcloud section tracks for episode $episodeId")
            }
        } catch (e: Exception) {
            Log.d("DjRepo", "Mixcloud sections failed for $mixcloudKey: ${e.message}")
        }
    }

    private fun isLiveSet(title: String): Boolean {
        val lower = title.lowercase()
        return EXCLUDE_KEYWORDS.none { lower.contains(it) }
    }

    /**
     * Simple title similarity based on common words ratio.
     */
    private fun titleSimilarity(a: String, b: String): Double {
        val wordsA = a.split(Regex("[\\s\\-_]+")).filter { it.length > 2 }.toSet()
        val wordsB = b.split(Regex("[\\s\\-_]+")).filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        val common = wordsA.intersect(wordsB).size
        return common.toDouble() / minOf(wordsA.size, wordsB.size)
    }

    fun parseDate(dateStr: String?): Long = parseUploadDate(dateStr)

    private fun parseUploadDate(dateStr: String?): Long {
        if (dateStr == null) return System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val regex = Regex("(\\d+)\\s+(year|month|week|day|hour)s?\\s+ago", RegexOption.IGNORE_CASE)
        val match = regex.find(dateStr) ?: return now
        val amount = match.groupValues[1].toLongOrNull() ?: return now
        val unit = match.groupValues[2].lowercase()
        val ms = when (unit) {
            "year" -> amount * 365 * 86400000L
            "month" -> amount * 30 * 86400000L
            "week" -> amount * 7 * 86400000L
            "day" -> amount * 86400000L
            "hour" -> amount * 3600000L
            else -> 0L
        }
        return now - ms
    }

    private fun parseIso8601(dateStr: String): Long? {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
            sdf.parse(dateStr.replace("Z", "+0000"))?.time
        } catch (_: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.parse(dateStr)?.time
            } catch (_: Exception) { null }
        }
    }
}
