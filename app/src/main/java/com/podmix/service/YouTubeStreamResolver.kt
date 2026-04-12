package com.podmix.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeStreamResolver @Inject constructor() {

    private val cache = mutableMapOf<String, Pair<String, Long>>()
    private val maxAge = 4 * 60 * 60 * 1000L // 4 hours
    private var initialized = false

    fun ensureInitialized() {
        if (!initialized) {
            try {
                NewPipe.init(DownloaderImpl.getInstance())
            } catch (_: Exception) {}
            initialized = true
        }
    }

    fun invalidateCache(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolve(videoId: String): String? {
        val cached = cache[videoId]
        if (cached != null && System.currentTimeMillis() - cached.second < maxAge) {
            return cached.first
        }

        return withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(url)

                // Target ~128kbps — high enough for quality, avoids throttle from max-bitrate streams
                val audioStream = info.audioStreams
                    .filter { it.averageBitrate > 0 }
                    .minByOrNull { kotlin.math.abs(it.averageBitrate - 128) }
                    ?: info.audioStreams.maxByOrNull { it.averageBitrate }

                audioStream?.content?.also { streamUrl ->
                    cache[videoId] = streamUrl to System.currentTimeMillis()
                    Log.i("StreamResolver", "Resolved $videoId OK (${audioStream.averageBitrate}kbps)")
                }
            } catch (e: Exception) {
                Log.e("StreamResolver", "Failed to resolve $videoId: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Get video description (for tracklist parsing)
     */
    suspend fun getDescription(videoId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(url)
                info.description?.content
            } catch (e: Exception) {
                Log.e("StreamResolver", "Failed to get description for $videoId: ${e.message}")
                null
            }
        }
    }

    /**
     * Recherche la première vidéo YouTube via DDG.
     * Retourne le videoId (11 chars) ou null.
     */
    suspend fun searchFirstVideoId(query: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode("site:youtube.com $query", "UTF-8")
                val url = java.net.URL("https://html.duckduckgo.com/html/?q=$encoded")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.setRequestProperty("Accept", "text/html")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val videoId = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/)([a-zA-Z0-9_-]{11})""")
                    .find(html)?.groupValues?.get(1)
                Log.i("StreamResolver", "searchFirstVideoId('$query') → $videoId")
                videoId
            } catch (e: Exception) {
                Log.e("StreamResolver", "searchFirstVideoId failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Recherche le premier track SoundCloud via DDG.
     * Retourne une URL canonique "https://soundcloud.com/artist/track" ou null.
     */
    suspend fun searchFirstSoundCloudUrl(query: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode("site:soundcloud.com $query", "UTF-8")
                val url = java.net.URL("https://html.duckduckgo.com/html/?q=$encoded")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.setRequestProperty("Accept", "text/html")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // Cherche une URL soundcloud.com/artist/track (exactement 2 segments de path)
                val scUrl = Regex("""https://soundcloud\.com/([a-zA-Z0-9_-]+)/([a-zA-Z0-9_-]+)(?=[^/"])""")
                    .find(html)?.value
                    ?.takeIf { it.isNotBlank() }
                Log.i("StreamResolver", "searchFirstSoundCloudUrl('$query') → $scUrl")
                scUrl
            } catch (e: Exception) {
                Log.e("StreamResolver", "searchFirstSoundCloudUrl failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Resolve a SoundCloud track URL to a direct stream URL via NewPipe.
     *
     * Accepts:
     * - Canonical permalink: "https://soundcloud.com/artist/track" → passed directly to NewPipe
     * - API URL: "https://api.soundcloud.com/tracks/NNN" → converted via SoundcloudParsingHelper
     */
    suspend fun resolveSoundCloudTrack(trackUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                val resolvedUrl = if (trackUrl.startsWith("https://soundcloud.com/") &&
                    trackUrl.removePrefix("https://soundcloud.com/").count { it == '/' } >= 1) {
                    // Already a canonical permalink — use directly
                    trackUrl
                } else {
                    // API URL (api.soundcloud.com/tracks/NNN) — convert via embed player
                    toCanonicalSoundCloudUrl(trackUrl)
                }
                if (resolvedUrl == null) {
                    Log.e("StreamResolver", "Could not convert SC URL: $trackUrl")
                    return@withContext null
                }
                Log.i("StreamResolver", "SoundCloud resolving: $resolvedUrl")
                val info = StreamInfo.getInfo(resolvedUrl)
                val audioStream = info.audioStreams
                    .filter { it.averageBitrate > 0 }
                    .maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams.firstOrNull()
                audioStream?.content?.also {
                    Log.i("StreamResolver", "SoundCloud OK (${audioStream.averageBitrate}kbps): ${resolvedUrl.takeLast(60)}")
                }
            } catch (e: Exception) {
                Log.e("StreamResolver", "SoundCloud resolve failed for $trackUrl: ${e.message}")
                null
            }
        }
    }

    /**
     * Convert "https://api.soundcloud.com/tracks/NNN" to a canonical SoundCloud permalink URL.
     * Uses the embed player widget which SoundCloud's own oEmbed response references.
     */
    private fun toCanonicalSoundCloudUrl(apiUrl: String): String? {
        return try {
            org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper
                .resolveUrlWithEmbedPlayer(apiUrl)
        } catch (e: Exception) {
            Log.w("StreamResolver", "SoundcloudParsingHelper failed for $apiUrl: ${e.message}")
            null
        }
    }

    suspend fun getChapters(videoId: String): List<com.podmix.service.ParsedTrack> {
        return withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(url)
                info.streamSegments.map { seg ->
                    ParsedTrack("", seg.title, seg.startTimeSeconds.toFloat())
                }
            } catch (e: Exception) {
                Log.e("StreamResolver", "Failed to get chapters for $videoId: ${e.message}")
                emptyList()
            }
        }
    }
}
