package com.podmix.service

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class ArtistSet(
    val title: String,
    val date: String,           // "2021-02-26" or empty
    val tracklistUrl: String,   // full 1001TL tracklist URL
    val viewCount: Int,         // for "Most Viewed" sort; 0 if unavailable
    val youtubeVideoId: String? // extracted from page if present
)

@Singleton
class ArtistPageScraper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "ArtistPageScraper"

    // Extract set list from the 1001TL artist/DJ page.
    // Each row in the listing is a tracklist link with metadata.
    private val EXTRACT_JS = """
        (function tryExtract(attempt) {
            var rows = document.querySelectorAll('div.tlpItem, div.tl-item, li.tlpItem');
            if (rows.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }
            var sets = [];
            rows.forEach(function(row) {
                // title + tracklist URL
                var link = row.querySelector('a[href*="/tracklist/"]');
                if (!link) return;
                var href = link.href || '';
                var title = link.innerText.trim() || link.title || '';
                if (!title || !href) return;

                // date — try multiple selectors
                var dateEl = row.querySelector('span.date, .tlpDateTimeLabel, time');
                var date = dateEl ? dateEl.innerText.trim() : '';

                // view count
                var viewEl = row.querySelector('.tlpViewCnt, .viewCount, span[class*="view"]');
                var views = 0;
                if (viewEl) {
                    var v = viewEl.innerText.replace(/[^0-9]/g, '');
                    views = parseInt(v) || 0;
                }

                // YouTube ID from any link in the row
                var youtubeId = '';
                row.querySelectorAll('a[href*="youtube.com"]').forEach(function(a) {
                    var m = a.href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                    if (m && !youtubeId) youtubeId = m[1];
                });

                sets.push({ title: title, date: date, url: href, views: views, youtubeId: youtubeId });
            });
            Android.onSetsExtracted(JSON.stringify(sets));
        })(0);
    """.trimIndent()

    suspend fun scrapeArtistSets(artistPageUrl: String): List<ArtistSet>? {
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(artistPageUrl)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ArtistSet>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<ArtistSet>?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onSetsExtracted(json: String) {
                    Log.d(TAG, "JS callback, json length=${json.length}")
                    val sets = parseSetsJson(json)
                    Log.i(TAG, "Extracted ${sets?.size ?: 0} sets from artist page")
                    resume(sets)
                }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Page finished: $url — injecting JS")
                    view.evaluateJavascript(EXTRACT_JS, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "WebView error: ${error.description}")
                        resume(null)
                    }
                }
            }

            cont.invokeOnCancellation { resume(null) }
            Log.i(TAG, "Loading artist page: $url")
            webView.loadUrl(url)
        }

    private fun parseSetsJson(json: String): List<ArtistSet>? {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            val sets = mutableListOf<ArtistSet>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.optString("url", "")
                val title = obj.optString("title", "").trim()
                if (title.isBlank() || !url.contains("1001tracklists.com/tracklist/")) continue
                sets.add(ArtistSet(
                    title = title,
                    date = obj.optString("date", ""),
                    tracklistUrl = url,
                    viewCount = obj.optInt("views", 0),
                    youtubeVideoId = obj.optString("youtubeId", "").takeIf { it.isNotBlank() }
                ))
            }
            if (sets.size >= 1) sets else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }
}
