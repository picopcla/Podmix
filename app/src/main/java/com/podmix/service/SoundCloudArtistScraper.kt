package com.podmix.service

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.podmix.ui.screens.liveset.ScSet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class SoundCloudArtistScraper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val TAG = "SCscraper"

    private val EXTRACT_JS = """
        (function tryExtract(attempt) {
            var links = document.querySelectorAll('a[href]');
            var tracks = [];
            var seen = {};
            links.forEach(function(a) {
                var h = a.href || '';
                var m = h.match(/soundcloud\.com\/([^/?#]+)\/([^/?#]+)(?:${'$'}|[?#])/);
                if (!m) return;
                var slug2 = m[2];
                if (['sets','reposts','likes','following','followers','tracks','albums','popular-tracks'].indexOf(slug2) >= 0) return;
                if (seen[h]) return;
                seen[h] = true;
                var title = a.innerText.trim() || a.getAttribute('aria-label') || '';
                if (!title || title.length < 3) return;
                tracks.push({ title: title, url: 'https://soundcloud.com/' + m[1] + '/' + m[2] });
            });
            if (tracks.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }
            Android.onTracksExtracted(JSON.stringify(tracks));
        })(0);
    """.trimIndent()

    /** Trouve la page artiste SC via DDG puis scrape ses tracks. Retourne null si échec. */
    suspend fun findAndScrape(artistName: String): List<ScSet>? {
        val artistPageUrl = findArtistPage(artistName) ?: return null
        Log.i(TAG, "SC artist page: $artistPageUrl")
        return scrapeArtistPage(artistPageUrl)
    }

    private suspend fun findArtistPage(artistName: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode("site:soundcloud.com $artistName", "UTF-8")
            val client = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "text/html")
                .build()
            val html = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val match = Regex("""uddg=([^&"]+)""").find(html) ?: return@withContext null
            val url = URLDecoder.decode(match.groupValues[1], "UTF-8")
            // Valider page artiste : exactement 1 segment de path (soundcloud.com/slug)
            val isArtistPage = Regex("""soundcloud\.com/[^/?#]+/?$""").containsMatchIn(url)
            if (isArtistPage) url else null
        } catch (e: Exception) {
            Log.w(TAG, "DDG SC search failed: ${e.message}")
            null
        }
    }

    private suspend fun scrapeArtistPage(url: String): List<ScSet>? {
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(url)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ScSet>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<ScSet>?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTracksExtracted(json: String) {
                    val tracks = parseTracksJson(json)
                    Log.i(TAG, "Extracted ${tracks?.size ?: 0} SC tracks")
                    resume(tracks)
                }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(EXTRACT_JS, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) resume(null)
                }
            }

            cont.invokeOnCancellation { resume(null) }
            webView.loadUrl(url)
        }

    private fun parseTracksJson(json: String): List<ScSet>? {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            val tracks = mutableListOf<ScSet>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.getString("title").trim()
                val url = obj.getString("url").trim()
                if (title.isNotBlank() && url.isNotBlank()) {
                    tracks.add(ScSet(title = title, url = url, date = ""))
                }
            }
            if (tracks.isNotEmpty()) tracks else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }
}
