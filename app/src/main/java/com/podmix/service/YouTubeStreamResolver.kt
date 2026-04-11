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

    private fun ensureInit() {
        if (!initialized) {
            try {
                NewPipe.init(DownloaderImpl.getInstance())
            } catch (_: Exception) {}
            initialized = true
        }
    }

    suspend fun resolve(videoId: String): String? {
        val cached = cache[videoId]
        if (cached != null && System.currentTimeMillis() - cached.second < maxAge) {
            return cached.first
        }

        return withContext(Dispatchers.IO) {
            try {
                ensureInit()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(url)

                val audioStream = info.audioStreams
                    .maxByOrNull { it.averageBitrate }

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
                ensureInit()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(url)
                info.description?.content
            } catch (e: Exception) {
                Log.e("StreamResolver", "Failed to get description for $videoId: ${e.message}")
                null
            }
        }
    }
}
