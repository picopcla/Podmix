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

/** Media sources found on a 1001TL individual tracklist page. */
data class TracklistMediaSource(
    val youtubeVideoId: String?,        // e.g. "FQj71mhobYw"
    val soundcloudTrackUrl: String?,    // e.g. "https://api.soundcloud.com/tracks/2280834686"
    val mixcloudKey: String?            // e.g. "/ArtistName/TrackName/"
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
            // Try structured row selectors first
            var rows = document.querySelectorAll('div.tlpItem, div.tl-item, li.tlpItem, .cTlTog, .tl-item, div[class*="tlp"]');
            // Also try direct tracklist links as fallback (1001TL renders content via JS)
            var directLinks = document.querySelectorAll('a[href*="/tracklist/"]');

            if (rows.length === 0 && directLinks.length === 0 && attempt < 20) {
                setTimeout(function() { tryExtract(attempt + 1); }, 500);
                return;
            }

            var sets = [];

            if (rows.length > 0) {
                rows.forEach(function(row) {
                    var link = row.querySelector('a[href*="/tracklist/"]');
                    if (!link) return;
                    var href = link.href || '';
                    var title = link.innerText.trim() || link.title || '';
                    if (!title || !href) return;

                    var dateEl = row.querySelector('span.date, .tlpDateTimeLabel, time, .cTlDate');
                    var date = dateEl ? dateEl.innerText.trim() : '';

                    var viewEl = row.querySelector('.tlpViewCnt, .viewCount, span[class*="view"], .cTlViewCnt');
                    var views = 0;
                    if (viewEl) {
                        var v = viewEl.innerText.replace(/[^0-9]/g, '');
                        views = parseInt(v) || 0;
                    }

                    var youtubeId = '';
                    row.querySelectorAll('a[href*="youtube.com"]').forEach(function(a) {
                        var m = a.href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                        if (m && !youtubeId) youtubeId = m[1];
                    });

                    sets.push({ title: title, date: date, url: href, views: views, youtubeId: youtubeId });
                });
            } else {
                // Fallback: direct tracklist links (works when 1001TL doesn't use expected row classes)
                directLinks.forEach(function(link) {
                    var title = link.innerText.trim();
                    if (title && link.href.indexOf('1001tracklists.com/tracklist/') > -1) {
                        // Try to find date/views in parent container
                        var parent = link.parentElement;
                        var date = '';
                        var views = 0;
                        if (parent) {
                            var dateEl = parent.querySelector('span.date, time, [class*="date"]');
                            if (dateEl) date = dateEl.innerText.trim();
                            var viewEl = parent.querySelector('[class*="view"], [class*="cnt"]');
                            if (viewEl) views = parseInt(viewEl.innerText.replace(/[^0-9]/g, '')) || 0;
                        }
                        sets.push({ title: title, date: date, url: link.href, views: views, youtubeId: '' });
                    }
                });
            }

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
                @JavascriptInterface
                fun onDebug(msg: String) {
                    Log.d(TAG, "DEBUG: $msg")
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

    /**
     * Load the individual tracklist page and extract all media sources:
     * YouTube video ID, SoundCloud track URL, or Mixcloud key.
     */
    suspend fun getMediaSourceFromTracklistPage(tracklistUrl: String): TracklistMediaSource? {
        return withTimeoutOrNull(20_000) {
            withContext(Dispatchers.Main) {
                getMediaSourceOnMainThread(tracklistUrl)
            }
        }
    }

    private suspend fun getMediaSourceOnMainThread(url: String): TracklistMediaSource? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: TracklistMediaSource?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            val extractJs = """
                (function tryExtract(attempt) {
                    var ytId = '';
                    var scTrackUrl = '';
                    var mcKey = '';

                    function extractYtId(s) {
                        if (!s) return '';
                        var m = s.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                        if (!m) m = s.match(/youtu\.be\/([A-Za-z0-9_-]{11})/);
                        if (!m) m = s.match(/\/embed\/([A-Za-z0-9_-]{11})/);
                        return m ? m[1] : '';
                    }

                    // SoundCloud: extract from widget iframe url= param (track only, not artist page)
                    function extractScTrackUrl(src) {
                        if (!src || !src.includes('soundcloud')) return '';
                        // Widget iframe: url= param contains the real track URL
                        var m = src.match(/[?&]url=([^&]+)/);
                        if (m) {
                            var decoded = decodeURIComponent(m[1]);
                            if (decoded.includes('/tracks/')) return decoded;
                        }
                        return '';
                    }

                    function extractMcKey(s) {
                        if (!s) return '';
                        var m = s.match(/mixcloud\.com\/widget\/iframe\/\?feed=([^&"'\s]+)/);
                        if (m) return decodeURIComponent(m[1]);
                        return '';
                    }

                    // 1. iframes
                    var iframes = document.querySelectorAll('iframe');
                    var iframeSrcs = [];
                    iframes.forEach(function(f) {
                        var src = f.src || f.getAttribute('data-src') || '';
                        if (src) iframeSrcs.push(src);
                        if (!ytId) ytId = extractYtId(src);
                        if (!scTrackUrl) scTrackUrl = extractScTrackUrl(src);
                        if (!mcKey) mcKey = extractMcKey(src);
                    });
                    Android.onDebug('IFRAMES(' + iframes.length + '): ' + iframeSrcs.slice(0,3).join(' | '));

                    // 2. <a> links
                    document.querySelectorAll('a').forEach(function(a) {
                        var h = a.href || '';
                        if (!ytId && (h.includes('youtube') || h.includes('youtu.be'))) ytId = extractYtId(h);
                        // SC permalink: soundcloud.com/artist/track (2 path segments after domain)
                        if (!scTrackUrl && h.includes('soundcloud.com')) {
                            var m = h.match(/soundcloud\.com\/([^/?#]+)\/([^/?#]+)/);
                            if (m && m[2] !== 'sets' && m[1] !== 'player' && m[1] !== 'pages') {
                                scTrackUrl = 'https://soundcloud.com/' + m[1] + '/' + m[2];
                            }
                        }
                    });

                    // 3. data-* attributes
                    if (!ytId) {
                        document.querySelectorAll('[data-ytid],[data-youtube-id],[data-video-id],[data-yt-id]').forEach(function(el) {
                            if (!ytId) ytId = el.getAttribute('data-ytid') || el.getAttribute('data-youtube-id') ||
                                              el.getAttribute('data-video-id') || el.getAttribute('data-yt-id') || '';
                        });
                    }

                    // 4. Inline scripts
                    if (!ytId) {
                        document.querySelectorAll('script:not([src])').forEach(function(s) {
                            if (ytId) return;
                            var m = s.textContent.match(/"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"/);
                            if (!m) m = s.textContent.match(/ytid\s*=\s*["']([A-Za-z0-9_-]{11})["']/);
                            if (m) ytId = m[1];
                        });
                    }

                    var anyFound = ytId || scTrackUrl || mcKey;
                    if (!anyFound && attempt < 20) {
                        setTimeout(function() { tryExtract(attempt + 1); }, 500);
                        return;
                    }

                    Android.onDebug('SOURCES — YT:' + ytId + ' SC:' + scTrackUrl + ' MC:' + mcKey);
                    Android.onSourcesFound(ytId, scTrackUrl, mcKey);
                })(0);
            """.trimIndent()

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onSourcesFound(ytId: String, scTrackUrl: String, mcKey: String) {
                    val result = TracklistMediaSource(
                        youtubeVideoId = ytId.takeIf { it.length == 11 },
                        soundcloudTrackUrl = scTrackUrl.takeIf { it.isNotBlank() },
                        mixcloudKey = mcKey.takeIf { it.isNotBlank() }
                    )
                    Log.i(TAG, "TracklistMediaSource: YT=${result.youtubeVideoId} SC=${result.soundcloudTrackUrl} MC=${result.mixcloudKey}")
                    resume(result)
                }
                @JavascriptInterface
                fun onDebug(msg: String) {
                    Log.d(TAG, "PROBE: $msg")
                }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(extractJs, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) resume(null)
                }
            }

            cont.invokeOnCancellation { resume(null) }
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
