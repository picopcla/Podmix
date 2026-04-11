package com.podmix.data.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnail: String?,
    val publishedText: String? = null // "4 weeks ago", "3 months ago"
)

@Singleton
class YouTubeSearchApi @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    fun search(query: String, maxResults: Int = 20): List<YouTubeSearchResult> {
        return try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "EgIQAQ%3D%3D") // Videos filter
            }.toString()

            val request = Request.Builder()
                .url(INNERTUBE_URL)
                .post(body.toRequestBody(JSON_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val json = JSONObject(response.body!!.string())
            val contents = json
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            val results = mutableListOf<YouTubeSearchResult>()

            for (i in 0 until contents.length()) {
                val section = contents.getJSONObject(i)
                val items = section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    val video = items.getJSONObject(j).optJSONObject("videoRenderer") ?: continue
                    val title = video.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: continue
                    val videoId = video.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                    val lengthText = video.optJSONObject("lengthText")?.optString("simpleText") ?: "0:00"
                    val channel = video.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                    val thumb = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let {
                        if (it.length() > 0) it.getJSONObject(it.length() - 1).optString("url") else null
                    }
                    val published = video.optJSONObject("publishedTimeText")?.optString("simpleText")

                    results.add(YouTubeSearchResult(
                        videoId = videoId,
                        title = title,
                        channelName = channel,
                        durationSeconds = parseDuration(lengthText),
                        thumbnail = thumb,
                        publishedText = published
                    ))

                    if (results.size >= maxResults) break
                }
            }
            results
        } catch (e: Exception) {
            Log.e("YouTubeSearch", "Search failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseDuration(text: String): Int {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }
}
