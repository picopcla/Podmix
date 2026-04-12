package com.podmix.service

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class YouTubeWebViewExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "YTWebViewExtractor"
        // Prefer AAC 128kbps (140) or Opus 160kbps (251) — best quality for audio-only
        private val PREFERRED_ITAGS = listOf("140", "251", "250", "249", "141", "139")
    }

    private var cachedWebView: WebView? = null

    /**
     * Loads the YouTube video in a headless WebView (real Chrome engine).
     * The YouTube JS runs, decodes the throttle-bypass 'n' parameter, and the
     * player fires a request to googlevideo.com. We intercept that URL before
     * it leaves the device — already decoded, unthrottled.
     *
     * We strip the `range=` parameter so the download covers the full file.
     */
    suspend fun extractAudioUrl(videoId: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context)
                    var resumed = false

                    webView.settings.apply {
                        javaScriptEnabled = true
                        @Suppress("SetJavaScriptEnabled")
                        mediaPlaybackRequiresUserGesture = false
                        // Mobile Chrome UA — makes YouTube serve the mobile player
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S916B) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                        domStorageEnabled = true
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // Trigger playback via JS once the page is ready
                            view.evaluateJavascript(
                                """
                                (function() {
                                    var v = document.querySelector('video');
                                    if (v) { v.play(); return 'play()'; }
                                    // Fallback: click the play button
                                    var btn = document.querySelector('.ytp-large-play-button, .ytp-play-button');
                                    if (btn) { btn.click(); return 'click'; }
                                    return 'no element';
                                })()
                                """.trimIndent()
                            ) { result -> Log.i(TAG, "JS play result: $result") }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()

                            if (!url.contains("googlevideo.com/videoplayback")) return null

                            val itag = Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)
                            val mime = Regex("""[?&]mime=([^&]+)""").find(url)?.groupValues?.get(1)
                            Log.i(TAG, "videoplayback: itag=$itag mime=$mime")

                            // Audio-only itags: 139/140/141 (AAC m4a), 249/250/251 (Opus webm)
                            val isAudio = itag != null && itag.toIntOrNull() in
                                setOf(139, 140, 141, 249, 250, 251)

                            if (isAudio && !resumed) {
                                resumed = true
                                val fullUrl = url.replace(Regex("""[&?]range=\d+-\d+"""), "")
                                Log.i(TAG, "Captured audio URL itag=$itag: ${fullUrl.take(120)}...")
                                cont.resume(fullUrl)
                                view.post { view.stopLoading(); view.destroy() }
                            }
                            return null
                        }
                    }

                    // Watch URL — page complète avec le player vidéo
                    val watchUrl = "https://www.youtube.com/watch?v=$videoId"
                    Log.i(TAG, "Loading watch page for $videoId")
                    webView.loadUrl(watchUrl)

                    cont.invokeOnCancellation {
                        webView.post { webView.stopLoading(); webView.destroy() }
                    }
                }
            }.also {
                if (it == null) Log.w(TAG, "Timeout — no audio stream intercepted for $videoId")
            }
        }
    }
}
