package com.podmix.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.podmix.MainActivity
import com.podmix.R
import com.podmix.data.api.TracklistApi
import com.podmix.data.local.PodMixDatabase
import com.podmix.data.local.entity.EpisodeEntity
import com.prof18.rssparser.RssParser
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "RefreshWorker"
        const val CHANNEL_ID = "podmix_updates"
        const val NOTIFICATION_ID = 42
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background refresh")

        val db = Room.databaseBuilder(
            applicationContext, PodMixDatabase::class.java, "podmix.db"
        ).addMigrations(
            PodMixDatabase.MIGRATION_3_4,
            PodMixDatabase.MIGRATION_4_5,
            PodMixDatabase.MIGRATION_5_6,
            PodMixDatabase.MIGRATION_6_7
        ).fallbackToDestructiveMigration().build()

        val podcastDao = db.podcastDao()
        val episodeDao = db.episodeDao()
        val trackDao = db.trackDao()
        val rssParser = RssParser()

        // Create Retrofit instances for APIs
        val tracklistApi = createTracklistApi()
        val tracklistService = TracklistService(createItunesApi())

        var newEpisodeCount = 0
        val newEpisodeTitles = mutableListOf<String>()

        // --- Refresh podcasts ---
        try {
            val podcasts = podcastDao.getByTypeSuspend("podcast")
            for (podcast in podcasts) {
                try {
                    val feedUrl = podcast.rssFeedUrl ?: continue
                    val channel = rssParser.getRssChannel(feedUrl)

                    for (item in channel.items.take(10)) {
                        val guid = item.guid ?: item.link ?: item.title ?: continue
                        val audioUrl = item.audio ?: continue

                        val existing = episodeDao.getByGuid(guid, podcast.id)
                        if (existing != null) continue

                        val dateLong = item.pubDate?.let { parsePubDate(it) }
                        val durationSec = parseDuration(item.itunesItemData?.duration)

                        val episode = EpisodeEntity(
                            podcastId = podcast.id,
                            title = item.title ?: "Untitled",
                            audioUrl = audioUrl,
                            datePublished = dateLong,
                            durationSeconds = durationSec,
                            artworkUrl = item.itunesItemData?.image ?: podcast.logoUrl,
                            guid = guid,
                            description = item.description,
                            episodeType = "podcast"
                        )
                        val episodeId = episodeDao.insert(episode).toInt()
                        newEpisodeCount++
                        newEpisodeTitles.add(item.title ?: "Untitled")

                        // Auto-detect tracklist via 1001TL API
                        try {
                            detectAndSaveTracksSimple(
                                trackDao, tracklistApi, tracklistService,
                                episodeId, item.description, item.title ?: "",
                                podcast.name, durationSec
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Tracklist detect failed for $episodeId: ${e.message}")
                        }
                    }

                    podcastDao.update(podcast.copy(lastCheckedAt = System.currentTimeMillis()))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh podcast ${podcast.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load podcasts: ${e.message}")
        }

        // Show notification if new content found
        if (newEpisodeCount > 0) {
            showNotification(newEpisodeCount, newEpisodeTitles)
        }

        db.close()
        Log.i(TAG, "Background refresh done: $newEpisodeCount new episodes")
        return Result.success()
    }

    private suspend fun detectAndSaveTracksSimple(
        trackDao: com.podmix.data.local.dao.TrackDao,
        tracklistApi: TracklistApi,
        tracklistService: TracklistService,
        episodeId: Int,
        description: String?,
        episodeTitle: String,
        podcastName: String,
        episodeDurationSec: Int
    ) {
        // Try 1001TL API first
        val query1001 = "$podcastName $episodeTitle"
        try {
            val response = tracklistApi.getTracklist(query1001)
            val tracks = response.tracks
            if (tracks != null && tracks.isNotEmpty()) {
                val entities = tracks.mapIndexed { index, t ->
                    com.podmix.data.local.entity.TrackEntity(
                        episodeId = episodeId, position = index,
                        title = t.title, artist = t.artist,
                        startTimeSec = t.startTimeSec.toFloat(), source = "1001tracklists"
                    )
                }
                trackDao.insertAll(entities)
                return
            }
        } catch (_: Exception) {}

        // Fall back to description parsing
        val parsed = tracklistService.detect(description, podcastName, episodeTitle)
        if (parsed.isNotEmpty()) {
            val hasTimestamps = parsed.any { it.startTimeSec > 0f }
            val entities = parsed.mapIndexed { index, p ->
                val startSec = if (hasTimestamps) p.startTimeSec
                else if (episodeDurationSec > 0) (episodeDurationSec.toFloat() * index / parsed.size)
                else 0f
                com.podmix.data.local.entity.TrackEntity(
                    episodeId = episodeId, position = index,
                    title = p.title, artist = p.artist,
                    startTimeSec = startSec,
                    source = if (hasTimestamps) "timestamped" else "uniform"
                )
            }
            trackDao.insertAll(entities)
        }
    }

    private fun showNotification(count: Int, titles: List<String>) {
        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (required for API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nouveaux \u00e9pisodes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications de nouveaux \u00e9pisodes et sets"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (count == 1 && titles.isNotEmpty()) {
            "Nouveau : ${titles.first()}"
        } else {
            "$count nouveaux \u00e9pisodes"
        }

        val style = NotificationCompat.InboxStyle()
        for (title in titles.take(5)) {
            style.addLine(title)
        }
        if (titles.size > 5) {
            style.setSummaryText("+${titles.size - 5} autres")
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle("PodMix")
            .setContentText(text)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createTracklistApi(): TracklistApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("http://192.168.10.5:8099/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TracklistApi::class.java)
    }

    private fun createItunesApi(): com.podmix.data.api.ItunesApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.podmix.data.api.ItunesApi::class.java)
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
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseDuration(duration: String?): Int {
        if (duration == null) return 0
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
