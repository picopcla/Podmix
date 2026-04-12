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

@Singleton
class TracklistWebScraper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "WebScraper"

    // JS injecté après chargement — attend que les tracks soient dans le DOM
    // puis les extrait et les passe à l'interface Android
    private val EXTRACT_JS = """
        (function tryExtract(attempt) {
            var items = document.querySelectorAll('div.tlpItem, div.tlpTog');
            if (items.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }
            var tracks = [];
            items.forEach(function(el) {
                var val = el.querySelector('span.trackValue');
                if (!val) return;
                var text = val.innerText.trim();
                if (!text) return;
                // Source fiable : input hidden id="tlpXXXX_cue_seconds" value en secondes
                var cueInput = el.querySelector('input[id$="_cue_seconds"]');
                var timeSec = cueInput ? parseInt(cueInput.value) || 0 : 0;
                tracks.push({ text: text, timeSec: timeSec });
            });
            Android.onTracksExtracted(JSON.stringify(tracks));
        })(0);
    """.trimIndent()

    /**
     * Charge la page 1001TL via WebView (Main thread) et extrait les tracks.
     * Timeout : 15s. Retourne null si échec ou timeout.
     */
    suspend fun scrape1001TL(url: String): List<ParsedTrack>? {
        return withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(url)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ParsedTrack>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<ParsedTrack>?) {
                if (!resumed) {
                    resumed = true
                    // webView.destroy() MUST run on main thread — post it, don't block
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        webView.destroy()
                    }
                    cont.resume(result)
                }
            }

            // Interface Android exposée au JS
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTracksExtracted(json: String) {
                    Log.d(TAG, "JS callback received, json length=${json.length}")
                    val tracks = parseTracksJson(json)
                    Log.i(TAG, "Extracted ${tracks?.size ?: 0} tracks from 1001TL")
                    resume(tracks)
                }
                @JavascriptInterface
                fun onDebug(msg: String) {
                    Log.d(TAG, "JS_DEBUG: ${msg.take(600)}")
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

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "WebView error: ${error.description}")
                        resume(null)
                    }
                }
            }

            cont.invokeOnCancellation { resume(null) }

            Log.i(TAG, "Loading: $url")
            webView.loadUrl(url)
        }

    private fun parseTracksJson(json: String): List<ParsedTrack>? {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            val tracks = mutableListOf<ParsedTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.getString("text").trim()
                val timeSec = obj.optInt("timeSec", 0).toFloat()
                val (artist, title) = splitArtistTitle(text)
                if (title.isNotBlank()) {
                    tracks.add(ParsedTrack(artist, title, timeSec))
                }
            }
            val withTimestamps = tracks.count { it.startTimeSec > 0f }
            Log.i(TAG, "Parsed ${tracks.size} tracks, $withTimestamps avec timestamps")
            if (tracks.size >= 3) tracks else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    private fun splitArtistTitle(text: String): Pair<String, String> {
        val separators = listOf(" - ", " – ", " — ")
        for (sep in separators) {
            val idx = text.indexOf(sep)
            if (idx > 0) return text.substring(0, idx).trim() to text.substring(idx + sep.length).trim()
        }
        return "" to text
    }
}
