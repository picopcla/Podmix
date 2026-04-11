package com.podmix.service

import android.util.Base64
import android.util.Log
import com.podmix.data.local.dao.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val trackDao: TrackDao
) {
    companion object {
        private const val TAG = "SpotifyService"
        private const val CLIENT_ID = "88773977a9ce48ceab85307d16ccde4f"
        private const val CLIENT_SECRET = "fdbd1c6600184dff9f3eb94ea133a7e7"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    }

    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0L
    private val tokenMutex = Mutex()

    private suspend fun getToken(): String? = tokenMutex.withLock {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return accessToken
        }
        return withContext(Dispatchers.IO) {
            try {
                val credentials = "$CLIENT_ID:$CLIENT_SECRET"
                val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
                val body = FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .build()
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(body)
                    .header("Authorization", "Basic $encoded")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token request failed: ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body!!.string())
                accessToken = json.getString("access_token")
                val expiresIn = json.getLong("expires_in")
                tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000
                accessToken
            } catch (e: Exception) {
                Log.e(TAG, "Token error: ${e.message}")
                null
            }
        }
    }

    suspend fun searchTrack(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext null
            val query = "$artist $title".trim()
            val url = "$SEARCH_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=track&limit=1"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Search failed: ${response.code}")
                return@withContext null
            }
            val json = JSONObject(response.body!!.string())
            val items = json.getJSONObject("tracks").getJSONArray("items")
            if (items.length() == 0) return@withContext null
            val track = items.getJSONObject(0)
            val spotifyUrl = track.getJSONObject("external_urls").getString("spotify")
            spotifyUrl
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
            null
        }
    }

    /**
     * Search Spotify for a track and save the URL in Room.
     * Call this after a track is favorited.
     */
    suspend fun searchAndSave(trackId: Int, artist: String, title: String) {
        Log.i(TAG, "searchAndSave: id=$trackId, '$artist - $title'")
        val url = searchTrack(artist, title)
        Log.i(TAG, "searchAndSave result: ${url ?: "NOT FOUND"}")
        if (url != null) {
            trackDao.updateSpotifyUrl(trackId, url)
            Log.i(TAG, "Saved Spotify URL for track $trackId")
        }
    }
}
