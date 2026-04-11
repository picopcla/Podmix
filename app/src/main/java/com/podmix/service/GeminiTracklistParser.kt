package com.podmix.service

import android.util.Log
import com.podmix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiTracklistParser @Inject constructor() {

    private val TAG = "GeminiParser"

    private val cache = HashMap<Int, List<ParsedTrack>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val PROMPT_PREFIX = """
Extract the music tracklist from this YouTube comment.
Return ONLY a JSON array, no other text.
Each object: {"artist": "...", "title": "...", "startTimeSec": 0}
Rules:
- Convert MM:SS or HH:MM:SS timestamps to seconds. 0 if none.
- Empty string for artist if unknown.
- Ignore non-track lines (descriptions, links, etc.).

Comment:
""".trimIndent()

    /**
     * Returns null on any error (network, rate limit, malformed JSON, empty result).
     * Results are cached by comment hash to avoid duplicate API calls.
     */
    suspend fun parseComment(text: String): List<ParsedTrack>? = withContext(Dispatchers.IO) {
        val key = text.hashCode()
        cache[key]?.let { return@withContext it }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY not set — skipping AI parse")
            return@withContext null
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", PROMPT_PREFIX + text)
                            })
                        })
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val responseText = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gemini API error: ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            } ?: return@withContext null

            val tracks = parseGeminiResponse(responseText)
            if (tracks != null) cache[key] = tracks
            tracks
        } catch (e: Exception) {
            Log.w(TAG, "Gemini parse failed: ${e.message}")
            null
        }
    }

    private fun parseGeminiResponse(responseText: String): List<ParsedTrack>? {
        return try {
            val root = JSONObject(responseText)
            val text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Strip markdown code fences if Gemini adds them
            val json = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val arr = JSONArray(json)
            val tracks = mutableListOf<ParsedTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val artist = obj.optString("artist", "").trim()
                val title = obj.optString("title", "").trim()
                val timeSec = obj.optDouble("startTimeSec", 0.0).toFloat()
                if (title.isNotBlank()) {
                    tracks.add(ParsedTrack(artist, title, timeSec))
                }
            }
            if (tracks.size >= 3) tracks else null
        } catch (e: Exception) {
            Log.w(TAG, "Response parse error: ${e.message}")
            null
        }
    }
}
