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
        val token = getToken() ?: return@withContext null

        // Cascade: strict field filters → free text → first artist only
        val firstArtist = artist
            .split(Regex("""\s+[xX,&]\s+|\s+feat\.|\s+ft\.|\s+vs\.|\s+presents\s"""))
            .firstOrNull()?.trim() ?: artist

        val queries = buildList {
            add("artist:${artist.trim()} track:${title.trim()}")          // 1. strict
            add("${artist.trim()} ${title.trim()}")                        // 2. free text
            if (firstArtist != artist) {
                add("artist:${firstArtist} track:${title.trim()}")         // 3. premier artiste strict
                add("${firstArtist} ${title.trim()}")                      // 4. premier artiste libre
            }
        }

        for ((idx, query) in queries.withIndex()) {
            try {
                val url = "$SEARCH_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=track&limit=3"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search failed (${response.code}) for query: $query")
                    continue
                }
                val json = JSONObject(response.body!!.string())
                val items = json.getJSONObject("tracks").getJSONArray("items")
                if (items.length() == 0) {
                    Log.d(TAG, "No results for: $query")
                    continue
                }

                // Validate each result: artist returned must share words with searched artist
                // Strict check for free-text queries (idx >= 1) to avoid false positives
                val artistWords = artist.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
                val titleWords  = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

                for (i in 0 until items.length()) {
                    val track = items.getJSONObject(i)
                    val returnedArtists = track.getJSONArray("artists")
                    val returnedArtistNames = (0 until returnedArtists.length())
                        .map { returnedArtists.getJSONObject(it).getString("name").lowercase() }
                        .joinToString(" ")
                    val returnedTitle = track.getString("name").lowercase()

                    val returnedArtistWords = returnedArtistNames.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
                    val returnedTitleWords  = returnedTitle.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

                    val artistMatch = artistWords.intersect(returnedArtistWords).size.toDouble() /
                        artistWords.size.coerceAtLeast(1)
                    val titleMatch  = titleWords.intersect(returnedTitleWords).size.toDouble() /
                        titleWords.size.coerceAtLeast(1)

                    // Free-text queries require both artist AND title to partially match
                    val minArtistSim = if (idx == 0) 0.0 else 0.4   // strict query: trust Spotify filter
                    val minTitleSim  = if (idx == 0) 0.0 else 0.4

                    if (artistMatch >= minArtistSim && titleMatch >= minTitleSim) {
                        val spotifyUrl = track.getJSONObject("external_urls").getString("spotify")
                        Log.i(TAG, "Match (artist=${"%.2f".format(artistMatch)} title=${"%.2f".format(titleMatch)}) via q[$idx]: $spotifyUrl")
                        return@withContext spotifyUrl
                    } else {
                        Log.d(TAG, "Rejected result '${returnedArtistNames} — $returnedTitle' (artist=${"%.2f".format(artistMatch)} title=${"%.2f".format(titleMatch)}) for '$artist — $title'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search error for '$query': ${e.message}")
            }
        }
        null
    }

    /**
     * Search Spotify for a track and save the URL in Room.
     * Call this after a track is favorited.
     */
    suspend fun searchAndSave(trackId: Int, artist: String, title: String) {
        Log.i(TAG, "searchAndSave: id=$trackId, '$artist - $title'")
        com.podmix.AppLogger.spotify("SEARCH #$trackId", "'$artist – $title'")
        val t0 = System.currentTimeMillis()
        val url = searchTrack(artist, title)
        val ms = System.currentTimeMillis() - t0
        Log.i(TAG, "searchAndSave result: ${url ?: "NOT FOUND"}")
        if (url != null) {
            trackDao.updateSpotifyUrl(trackId, url)
            Log.i(TAG, "Saved Spotify URL for track $trackId")
            com.podmix.AppLogger.spotify("FOUND #$trackId ${ms}ms", url)
        } else {
            com.podmix.AppLogger.err("SPOTIFY", "NOT_FOUND #$trackId ${ms}ms", "'$artist – $title'")
        }
    }
}
