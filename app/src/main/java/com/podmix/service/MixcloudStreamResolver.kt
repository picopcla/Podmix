package com.podmix.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixcloudStreamResolver @Inject constructor() {

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GRAPHQL_URL = "https://app.mixcloud.com/graphql"
        private const val XOR_KEY = "IFYOUWANTTHEARTISTSTOGETPAIDDONOTDOWNLOADFROMMIXCLOUD"
    }

    /** Cache for direct URL (download). Key = mixcloudKey, value = (url, fetchTimeMs) */
    private val cacheUrl = mutableMapOf<String, Pair<String, Long>>()
    /** Cache for HLS URL (streaming). Separate so we keep both types. */
    private val cacheHls = mutableMapOf<String, Pair<String, Long>>()
    /** CDN signed URLs expire in ~1h — cache for 50 min to stay safe. */
    private val maxAge = 50 * 60 * 1000L

    /**
     * Resolve for DOWNLOAD — returns direct M4A/MP3 URL.
     * Falls back to HLS if no direct URL available.
     */
    suspend fun resolve(mixcloudKey: String): String? = resolveInternal(mixcloudKey, preferHls = false)

    /**
     * Resolve for STREAMING — prefers HLS (more resilient: small segments, adaptive bitrate).
     * Falls back to direct URL if no HLS available.
     */
    suspend fun resolveForStreaming(mixcloudKey: String): String? = resolveInternal(mixcloudKey, preferHls = true)

    private suspend fun resolveInternal(mixcloudKey: String, preferHls: Boolean): String? {
        val cache = if (preferHls) cacheHls else cacheUrl
        val now = System.currentTimeMillis()
        val cached = cache[mixcloudKey]
        if (cached != null && now - cached.second < maxAge) return cached.first

        return withContext(Dispatchers.IO) {
            try {
                val parts = mixcloudKey.trim('/').split('/')
                if (parts.size < 2) return@withContext null
                val username = parts[0]
                val slug = parts[1]

                val query = JSONObject().apply {
                    put("query", """
                        query PlayerControlQuery(${'$'}lookup: CloudcastLookup!) {
                            cloudcastLookup(lookup: ${'$'}lookup) {
                                streamInfo { url hlsUrl dashUrl }
                            }
                        }
                    """.trimIndent())
                    put("variables", JSONObject().apply {
                        put("lookup", JSONObject().apply {
                            put("username", username)
                            put("slug", slug)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(GRAPHQL_URL)
                    .post(query.toString().toRequestBody("application/json".toMediaType()))
                    .header("Referer", "https://www.mixcloud.com$mixcloudKey")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .header("Origin", "https://www.mixcloud.com")
                    .build()

                val response = fastClient.newCall(request).execute()
                val json = JSONObject(response.body!!.string())
                val streamInfo = json.getJSONObject("data")
                    .getJSONObject("cloudcastLookup")
                    .getJSONObject("streamInfo")

                // Decode all available URLs
                val directUrl = streamInfo.optString("url").takeIf { it.isNotBlank() }?.let { decodeUrl(it) }
                val hlsUrl    = streamInfo.optString("hlsUrl").takeIf { it.isNotBlank() }?.let { decodeUrl(it) }

                // Store both in their respective caches
                val ts = System.currentTimeMillis()
                directUrl?.let { cacheUrl[mixcloudKey] = it to ts }
                hlsUrl?.let    { cacheHls[mixcloudKey] = it to ts }

                val result = if (preferHls) (hlsUrl ?: directUrl) else (directUrl ?: hlsUrl)
                Log.d("MixcloudResolver", "resolve preferHls=$preferHls → ${result?.take(60)}")
                result
            } catch (e: Exception) {
                Log.e("MixcloudResolver", "Failed: ${e.message}")
                null
            }
        }
    }

    /** Invalidate cached URLs (call after 403 to force re-resolve). */
    fun invalidateCache(mixcloudKey: String) {
        cacheUrl.remove(mixcloudKey)
        cacheHls.remove(mixcloudKey)
    }

    private fun decodeUrl(encoded: String): String? {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val sb = StringBuilder()
            for (i in bytes.indices) {
                sb.append((bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.length].code).toChar())
            }
            sb.toString().takeIf { it.startsWith("http") }
        } catch (e: Exception) { null }
    }
}
