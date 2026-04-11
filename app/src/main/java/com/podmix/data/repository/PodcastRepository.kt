package com.podmix.data.repository

import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.local.entity.PodcastEntity
import com.podmix.domain.model.Podcast
import com.prof18.rssparser.RssParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val rssParser: RssParser,
    private val trackRepository: TrackRepository
) {

    fun getPodcasts(): Flow<List<Podcast>> =
        podcastDao.getByType("podcast").map { entities ->
            entities.map { e ->
                val count = podcastDao.episodeCount(e.id)
                Podcast(
                    id = e.id,
                    name = e.name,
                    logoUrl = e.logoUrl,
                    description = e.description,
                    rssFeedUrl = e.rssFeedUrl,
                    type = e.type,
                    episodeCount = count
                )
            }
        }

    fun getEmissions(): Flow<List<Podcast>> =
        podcastDao.getByType("emission").map { entities ->
            entities.map { e ->
                val count = podcastDao.episodeCount(e.id)
                Podcast(
                    id = e.id,
                    name = e.name,
                    logoUrl = e.logoUrl,
                    description = e.description,
                    rssFeedUrl = e.rssFeedUrl,
                    type = e.type,
                    episodeCount = count
                )
            }
        }

    fun getRadios(): Flow<List<Podcast>> =
        podcastDao.getByType("radio").map { entities ->
            entities.map { e ->
                Podcast(
                    id = e.id,
                    name = e.name,
                    logoUrl = e.logoUrl,
                    description = e.description,
                    rssFeedUrl = e.rssFeedUrl,
                    type = e.type,
                    episodeCount = 0
                )
            }
        }

    suspend fun addPodcast(name: String, feedUrl: String, logoUrl: String?): Int {
        val entity = PodcastEntity(
            name = name,
            rssFeedUrl = feedUrl,
            logoUrl = logoUrl,
            type = "podcast"
        )
        return podcastDao.insert(entity).toInt()
    }

    suspend fun addEmission(name: String, feedUrl: String, logoUrl: String?): Int {
        val entity = PodcastEntity(
            name = name,
            rssFeedUrl = feedUrl,
            logoUrl = logoUrl,
            type = "emission"
        )
        return podcastDao.insert(entity).toInt()
    }

    suspend fun addRadio(name: String, streamUrl: String, logoUrl: String?): Int {
        val entity = PodcastEntity(
            name = name,
            rssFeedUrl = streamUrl,
            logoUrl = logoUrl,
            type = "radio"
        )
        return podcastDao.insert(entity).toInt()
    }

    suspend fun getPodcastById(id: Int): Podcast? {
        val e = podcastDao.getById(id) ?: return null
        val count = podcastDao.episodeCount(e.id)
        return Podcast(
            id = e.id,
            name = e.name,
            logoUrl = e.logoUrl,
            description = e.description,
            rssFeedUrl = e.rssFeedUrl,
            type = e.type,
            episodeCount = count
        )
    }

    suspend fun refreshPodcast(podcastId: Int) {
        val podcast = podcastDao.getById(podcastId) ?: return
        val feedUrl = podcast.rssFeedUrl ?: return
        val isEmission = podcast.type == "emission"

        val channel = rssParser.getRssChannel(feedUrl)

        val limit = if (podcast.type == "podcast") 40 else 10
        for (item in channel.items.take(limit)) {
            val guid = item.guid ?: item.link ?: item.title ?: continue
            val audioUrl = item.audio ?: continue

            // Dedup by guid
            val existing = episodeDao.getByGuid(guid, podcastId)
            if (existing != null) continue

            val dateLong = item.pubDate?.let { parsePubDate(it) }

            val episode = EpisodeEntity(
                podcastId = podcastId,
                title = item.title ?: "Untitled",
                audioUrl = audioUrl,
                datePublished = dateLong,
                durationSeconds = parseDuration(item.itunesItemData?.duration),
                artworkUrl = item.itunesItemData?.image ?: podcast.logoUrl,
                guid = guid,
                description = item.description,
                episodeType = if (isEmission) "emission" else "podcast"
            )
            val episodeId = episodeDao.insert(episode).toInt()

            // Auto-detect tracklist in background only for podcasts, NOT emissions
            if (!isEmission) {
                val desc = item.description
                val title = item.title ?: ""
                val podName = podcast.name
                val dur = parseDuration(item.itunesItemData?.duration)
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        trackRepository.detectAndSaveTracks(
                            episodeId = episodeId,
                            description = desc,
                            episodeTitle = title,
                            podcastName = podName,
                            episodeDurationSec = dur
                        )
                    } catch (e: Exception) {
                        Log.w("PodcastRepo", "Tracklist auto-detect failed for $episodeId: ${e.message}")
                    }
                }
            }
        }

        podcastDao.update(podcast.copy(lastCheckedAt = System.currentTimeMillis()))
    }

    private fun parsePubDate(dateStr: String): Long? {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "dd MMM yyyy HH:mm:ss Z"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                return sdf.parse(dateStr)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun parseDuration(duration: String?): Int {
        if (duration == null) return 0
        // Could be seconds as plain number or HH:MM:SS or MM:SS
        duration.toIntOrNull()?.let { return it }
        val parts = duration.split(":")
        return when (parts.size) {
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 +
                    (parts[1].toIntOrNull() ?: 0) * 60 +
                    (parts[2].toIntOrNull() ?: 0)
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 +
                    (parts[1].toIntOrNull() ?: 0)
            else -> 0
        }
    }
}
