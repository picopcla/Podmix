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

    private val cache = mutableMapOf<String, Pair<String, Long>>()
    private val maxAge = 4 * 60 * 60 * 1000L

    suspend fun resolve(mixcloudKey: String): String? {
        val cached = cache[mixcloudKey]
        if (cached != null && System.currentTimeMillis() - cached.second < maxAge) return cached.first

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
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = fastClient.newCall(request).execute()
                val json = JSONObject(response.body!!.string())
                val streamInfo = json.getJSONObject("data")
                    .getJSONObject("cloudcastLookup")
                    .getJSONObject("streamInfo")

                val encodedUrl = streamInfo.optString("url").takeIf { it.isNotBlank() }
                    ?: streamInfo.optString("hlsUrl").takeIf { it.isNotBlank() }
                    ?: return@withContext null

                val decoded = decodeUrl(encodedUrl)
                decoded?.also { cache[mixcloudKey] = it to System.currentTimeMillis() }
            } catch (e: Exception) {
                Log.e("MixcloudResolver", "Failed: ${e.message}")
                null
            }
        }
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
