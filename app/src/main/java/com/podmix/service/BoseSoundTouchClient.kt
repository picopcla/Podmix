package com.podmix.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoseSoundTouchClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val xmlType = "application/xml".toMediaType()

    suspend fun playUrl(ip: String, url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val xml = """<ContentItem source="LOCAL_INTERNET_RADIO" location="$url"><itemName>$title</itemName></ContentItem>"""
        post(ip, "/select", xml)
    }

    suspend fun pressKey(ip: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val xmlPress = """<key state="press" sender="Gabbo">$key</key>"""
        val xmlRelease = """<key state="release" sender="Gabbo">$key</key>"""
        post(ip, "/key", xmlPress) && post(ip, "/key", xmlRelease)
    }

    suspend fun setVolume(ip: String, volume: Int): Boolean = withContext(Dispatchers.IO) {
        val xml = """<volume><targetvolume>${volume.coerceIn(0, 100)}</targetvolume></volume>"""
        post(ip, "/volume", xml)
    }

    suspend fun getVolume(ip: String): Int? = withContext(Dispatchers.IO) {
        val body = get(ip, "/volume") ?: return@withContext null
        val match = Regex("<actualvolume>(\\d+)</actualvolume>").find(body)
        match?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun getDeviceInfo(ip: String): String? = withContext(Dispatchers.IO) {
        get(ip, "/info")
    }

    suspend fun getNowPlaying(ip: String): String? = withContext(Dispatchers.IO) {
        get(ip, "/now_playing")
    }

    private fun post(ip: String, path: String, xml: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$ip:8090$path")
                .post(xml.toRequestBody(xmlType))
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful.also { response.close() }
        } catch (_: Exception) {
            false
        }
    }

    private fun get(ip: String, path: String): String? {
        return try {
            val request = Request.Builder()
                .url("http://$ip:8090$path")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.body?.string()
        } catch (_: Exception) {
            null
        }
    }
}
