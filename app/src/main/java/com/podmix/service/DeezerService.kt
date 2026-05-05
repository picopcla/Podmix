package com.podmix.service

import android.util.Log
import com.podmix.data.local.dao.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeezerService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val trackDao: TrackDao
) {
    companion object {
        private const val TAG = "DeezerService"
        private const val SEARCH_URL = "https://api.deezer.com/search"
    }

    suspend fun searchTrack(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        // Stratégie en cascade : artiste complet → premier artiste → titre seul
        val firstArtist = artist.split(Regex("""\s+[xX,&]\s+|\s+feat\.|\s+ft\.|\s+vs\.|\s+presents\s"""))
            .firstOrNull()?.trim() ?: artist
        val queries = buildList {
            add("$artist $title")                          // 1. complet
            if (firstArtist != artist) add("$firstArtist $title")  // 2. premier artiste seulement
            add(title)                                     // 3. titre seul
        }.distinct()

        for (q in queries) {
            val result = deezerSearch(q)
            if (result != null) {
                Log.i(TAG, "Found (query='$q'): $result")
                return@withContext result
            }
        }
        Log.w(TAG, "No results for '$artist – $title' (tried ${queries.size} queries)")
        null
    }

    private fun deezerSearch(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$SEARCH_URL?q=$encoded&limit=1"
            val client = okHttpClient.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val response = client.newCall(
                Request.Builder().url(url)
                    .header("Accept", "application/json").build()
            ).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null
            val json = JSONObject(body)
            if (json.has("error")) return null
            val data = json.getJSONArray("data")
            if (data.length() == 0) return null
            data.getJSONObject(0).getString("link")
        } catch (e: Exception) {
            Log.e(TAG, "deezerSearch error: ${e.message}")
            null
        }
    }

    suspend fun searchAndSave(trackId: Int, artist: String, title: String) {
        Log.i(TAG, "searchAndSave #$trackId '$artist – $title'")
        com.podmix.AppLogger.deezer("SEARCH #$trackId", "'$artist – $title'")
        val t0 = System.currentTimeMillis()
        val url = searchTrack(artist, title)
        val ms = System.currentTimeMillis() - t0
        if (url != null) {
            trackDao.updateDeezerUrl(trackId, url)
            Log.i(TAG, "Saved Deezer URL for track #$trackId")
            com.podmix.AppLogger.deezer("FOUND #$trackId ${ms}ms", url)
        } else {
            com.podmix.AppLogger.err("DEEZER", "NOT_FOUND #$trackId ${ms}ms", "'$artist – $title'")
        }
    }
}
