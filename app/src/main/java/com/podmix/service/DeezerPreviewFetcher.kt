package com.podmix.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the free 30-second Deezer preview for a given artist/track pair.
 * No API key required — uses the public search endpoint.
 *
 * Returns raw PCM bytes (8 kHz mono 16-bit LE) ready for ChromaExtractor,
 * or null if the track is not found or on network/decode error.
 */
@Singleton
class DeezerPreviewFetcher @Inject constructor(
    okHttpClient: OkHttpClient,
    private val chromaExtractor: ChromaExtractor
) {
    companion object {
        private const val TAG  = "DeezerPreview"
        private const val BASE = "https://api.deezer.com/search"
    }

    private val client = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch Deezer preview for [artist] / [title] and decode to 8 kHz mono PCM.
     * Returns null on any failure (network, not found, decode error).
     */
    suspend fun fetchPreviewPcm(artist: String, title: String): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val previewUrl = findPreviewUrl(artist, title) ?: return@withContext null

                // Download MP3 preview
                val mp3 = client.newCall(Request.Builder().url(previewUrl).build())
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        resp.body?.bytes()
                    } ?: return@withContext null
                Log.d(TAG, "'$artist - $title' preview: ${mp3.size / 1024}KB")

                // Write to temp file so MediaExtractor can read it
                val tmp = java.io.File.createTempFile("dzpreview_", ".mp3")
                return@withContext try {
                    tmp.writeBytes(mp3)
                    chromaExtractor.decodeToPcmMono8k(tmp.absolutePath)
                } finally {
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchPreviewPcm '$artist - $title': ${e.message}")
                null
            }
        }

    // ── Query normalization ────────────────────────────────────────────────────

    /** Strip "ft. X", "feat. X", "featuring X" from artist name. */
    private fun normalizeArtist(artist: String): String =
        artist.replace(
            Regex("""\s+(ft\.|feat\.|featuring)\s+.+""", RegexOption.IGNORE_CASE), ""
        ).trim()

    /** Strip mix-type suffixes: "(Original Mix)", "(Extended Mix)", "(Radio Edit)", etc. */
    private fun normalizeTitle(title: String): String =
        title.replace(
            Regex("""\s*\((Original Mix|Extended Mix|Extended|Radio Edit|Edit|Club Mix|[^)]*Remix[^)]*)\)\s*""",
                RegexOption.IGNORE_CASE), ""
        ).trim()

    /** Keep only the first segment before "(" or " - " to strip remix info entirely. */
    private fun shortTitle(title: String): String =
        title.split(Regex("""\s*[\(]|\s+-\s+""")).firstOrNull()?.trim()
            ?.takeIf { it.length >= 4 } ?: title

    // ── Cascade search ─────────────────────────────────────────────────────────

    private fun findPreviewUrl(artist: String, title: String): String? {
        // Strategy 1: exact structured query
        searchDeezer("artist:\"${artist.take(40)}\" track:\"${title.take(50)}\""
        )?.let { return it }

        // Strategy 2: normalized artist + normalized title (strip "ft.", "(Original Mix)", etc.)
        val nArtist = normalizeArtist(artist)
        val nTitle  = normalizeTitle(title)
        if (nArtist != artist || nTitle != title) {
            searchDeezer("artist:\"${nArtist.take(40)}\" track:\"${nTitle.take(50)}\""
            )?.let { return it }
        }

        // Strategy 3: normalized artist + short title (strip all remix/edit info)
        val sTitle = shortTitle(nTitle)
        if (sTitle != nTitle) {
            searchDeezer("artist:\"${nArtist.take(40)}\" track:\"${sTitle.take(50)}\""
            )?.let { return it }
        }

        // Strategy 4: title only — catches mis-spelled artists
        return searchDeezer(nTitle.take(60))
    }

    private fun searchDeezer(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val resp = client.newCall(
                Request.Builder().url("$BASE?q=$encoded&limit=5").build()
            ).execute()
            val body = resp.body?.string() ?: return null
            val data = JSONObject(body).optJSONArray("data") ?: return null

            (0 until data.length())
                .asSequence()
                .map { data.getJSONObject(it) }
                .mapNotNull { obj ->
                    obj.optString("preview", "")
                        .takeIf { it.startsWith("http") }
                }
                .firstOrNull()
                .also { if (it == null) Log.d(TAG, "No preview for query: $query") }
        } catch (e: Exception) {
            Log.d(TAG, "searchDeezer '$query': ${e.message}")
            null
        }
    }
}
