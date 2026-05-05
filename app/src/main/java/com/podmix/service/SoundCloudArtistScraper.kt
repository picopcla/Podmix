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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        (function() {
            var nonArtist1 = ['pages','legal','press','imprint','blog','jobs','charts','mobile',
                'discover','you','notifications','stream','messages','settings','upload',
                'feed','sc-player','ubo','search','tags'];
            var nonArtist2 = ['sets','reposts','likes','following','followers','tracks',
                'albums','popular-tracks'];

            function collectTracks() {
                var links = document.querySelectorAll('a[href]');
                var tracks = [];
                var seen = {};
                links.forEach(function(a) {
                    var h = a.href || '';
                    var m = h.match(/soundcloud\.com\/([^/?#]+)\/([^/?#]+)(?:${'$'}|[?#])/);
                    if (!m) return;
                    var slug1 = m[1]; var slug2 = m[2];
                    if (nonArtist1.indexOf(slug1) >= 0) return;
                    if (nonArtist2.indexOf(slug2) >= 0) return;
                    if (seen[h]) return;
                    seen[h] = true;
                    var title = a.innerText.trim() || a.getAttribute('aria-label') || '';
                    if (!title || title.length < 3) return;
                    // Date
                    var container = a.closest('li, article, div.soundList__item, div.trackItem, div.userStreamItem, div.sound__body, div[class*="Sound"], div[class*="soundItem"]');
                    var date = '';
                    if (container) {
                        var timeEl = container.querySelector('time[datetime], time[pubdate], time[title]');
                        if (timeEl) date = timeEl.getAttribute('datetime') || timeEl.getAttribute('pubdate') || timeEl.getAttribute('title') || '';
                    }
                    if (!date) {
                        var par = a.parentElement;
                        for (var p = 0; p < 8 && par; p++) {
                            var t = par.querySelector('time[datetime], time[pubdate]');
                            if (t) { date = t.getAttribute('datetime') || t.getAttribute('pubdate') || ''; break; }
                            par = par.parentElement;
                        }
                    }
                    if (date.length > 10) date = date.substring(0, 10);
                    // Durée
                    var duration = '';
                    if (container) {
                        var els = container.querySelectorAll('span, abbr, time');
                        for (var ei = 0; ei < els.length; ei++) {
                            var txt = (els[ei].innerText || els[ei].textContent || '').trim();
                            if (/^\d{1,2}:\d{2}(:\d{2})?${'$'}/.test(txt)) { duration = txt; break; }
                        }
                    }
                    tracks.push({ title: title, url: 'https://soundcloud.com/' + m[1] + '/' + m[2], date: date, duration: duration });
                });
                return tracks;
            }

            var lastCount = 0;
            var stableRounds = 0;
            var totalAttempts = 0;
            var MAX_ATTEMPTS = 100;   // 100 × 700ms ≈ 70s max
            var STABLE_STOP = 30;     // 30 rounds stables × 700ms = 21s sans nouveau contenu → stop

            function tryExtract() {
                totalAttempts++;
                // Aider le scroll natif Kotlin avec un scroll JS complémentaire
                window.scrollTo(0, document.body.scrollHeight);
                document.documentElement.scrollTop = 999999;
                var tracks = collectTracks();
                if (totalAttempts % 5 === 0) {
                    Android.onDebug('attempt=' + totalAttempts + ' tracks=' + tracks.length + ' stable=' + stableRounds);
                }
                if (tracks.length > lastCount) {
                    stableRounds = 0;
                    lastCount = tracks.length;
                } else if (tracks.length > 0) {
                    stableRounds++;
                }
                if ((stableRounds >= STABLE_STOP && tracks.length > 0) || tracks.length >= 300 || totalAttempts >= MAX_ATTEMPTS) {
                    Android.onDebug('DONE: ' + tracks.length + ' tracks after ' + totalAttempts + ' attempts');
                    Android.onTracksExtracted(JSON.stringify(tracks));
                    return;
                }
                setTimeout(tryExtract, 700);
            }
            tryExtract();
        })();
    """.trimIndent()

    // JS pour extraire les slugs utilisateurs de la page de recherche SC (/search/people)
    private val SEARCH_USERS_JS = """
        (function trySearch(attempt) {
            // SC charge les résultats de manière asynchrone — on attend jusqu'à 6s
            var links = document.querySelectorAll('a[href]');
            var slugs = [];
            var seen = {};
            var sys = ['search','pages','legal','press','imprint','blog','jobs','charts',
                       'mobile','discover','you','notifications','stream','messages',
                       'settings','upload','feed','presse','societe','contact',
                       'help','terms','privacy','about','login','register',
                       'likes','reposts','following','followers','tracks','albums'];
            links.forEach(function(a) {
                var href = a.href || '';
                var m = href.match(/soundcloud\.com\/([^/?#]+)(?:${'$'}|[?#])/);
                if (!m) return;
                var slug = m[1].toLowerCase();
                if (sys.indexOf(slug) >= 0) return;
                if (seen[slug]) return;
                // Exclure les liens qui ressemblent à des tracks (contiennent un 2e segment)
                var path = href.replace(/^https?:\/\/soundcloud\.com\//, '');
                if (path.indexOf('/') >= 0) return; // page artiste = 1 seul segment
                seen[slug] = true;
                slugs.push(slug);
            });
            if (slugs.length === 0 && attempt < 12) {
                setTimeout(function() { trySearch(attempt + 1); }, 500);
                return;
            }
            Android.onDebug('search/people: ' + slugs.length + ' users after ' + attempt + ' attempts');
            Android.onUsersExtracted(JSON.stringify(slugs.slice(0, 10)));
        })(0);
    """.trimIndent()

    /** Trouve la page artiste SC (slug direct → DDG → fallback WebView SC), scrape ses tracks. */
    suspend fun findAndScrape(artistName: String): List<ScSet>? {
        // 0. Sonde directe : tente le slug normalisé AVANT DDG (≈500ms, zéro scrape)
        var artistPageUrl = findArtistPageDirectSlug(artistName)
        // 1. DDG (HTTP ~1s) si sonde directe échoue
        if (artistPageUrl == null) {
            artistPageUrl = findArtistPageViaDDG(artistName)
        }
        // 2. Fallback WebView SC si DDG échoue (bot-block DDG, slug exotique, etc.)
        if (artistPageUrl == null) {
            Log.w(TAG, "DDG failed for '$artistName', trying SC native WebView search")
            artistPageUrl = findArtistPageViaSCSearch(artistName)
        }
        if (artistPageUrl == null) return null
        // /tracks = liste complète des uploads (vs page principale = seulement Popular Tracks ~10)
        val tracksPageUrl = artistPageUrl.trimEnd('/') + "/tracks"
        Log.i(TAG, "SC tracks page: $tracksPageUrl")
        val raw = scrapeArtistPage(tracksPageUrl) ?: return null
        return filterSets(raw)  // suspend — fetch HTTP pour durées manquantes
    }

    /**
     * Utilise soundcloud.com/search/people?q=[query] pour trouver le bon artiste.
     * Supporte la recherche partielle ("otta" → "giuseppeottaviani").
     * Score chaque slug retourné contre la requête, retourne le meilleur.
     */
    private suspend fun findArtistPageViaSCSearch(artistName: String): String? {
        val encoded = URLEncoder.encode(artistName, "UTF-8")
        val searchUrl = "https://soundcloud.com/search/people?q=$encoded"
        Log.i(TAG, "SC search/people: $searchUrl")

        val slugs = withTimeoutOrNull(10_000) {
            withContext(Dispatchers.Main) {
                scrapeUsersOnMainThread(searchUrl)
            }
        }

        if (slugs.isNullOrEmpty()) {
            Log.w(TAG, "SC search/people: aucun résultat pour '$artistName'")
            return null
        }

        // Scorer chaque slug contre la requête
        val queryTokens = artistName.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim().split(Regex("\\s+"))
            .filter { it.length >= 2 }

        data class Scored(val slug: String, val score: Int)
        val scored = slugs.map { slug ->
            val s = slug.lowercase()
            val score = queryTokens.sumOf { token -> if (s.contains(token)) token.length else 0 }
            Scored(slug, score)
        }.sortedByDescending { it.score }

        Log.i(TAG, "SC slugs scorés: ${scored.take(5).map { "${it.slug}(${it.score})" }}")

        val best = scored.firstOrNull()
        return if (best != null && best.score > 0) {
            Log.i(TAG, "SC artiste retenu: ${best.slug} (score=${best.score})")
            "https://soundcloud.com/${best.slug}"
        } else if (slugs.isNotEmpty()) {
            // Aucun token ne matche (requête trop courte?) → on prend quand même le premier résultat SC
            Log.w(TAG, "SC: score=0, fallback premier résultat: ${slugs.first()}")
            "https://soundcloud.com/${slugs.first()}"
        } else null
    }

    /**
     * Sonde directe : normalise le nom en slug SC et vérifie HEAD.
     * "Korolova" → soundcloud.com/korolova (~500ms, pas de DDG/WebView).
     * "Carl Craig" → soundcloud.com/carl-craig ET soundcloud.com/carlcraig
     * SC retourne 404 pour les slugs inexistants (SPA redirige vers /search sinon).
     */
    private suspend fun findArtistPageDirectSlug(artistName: String): String? = withContext(Dispatchers.IO) {
        val base = artistName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val variants = listOf(base, base.replace("-", "")).distinct()

        val probeClient = okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        for (slug in variants) {
            val url = "https://soundcloud.com/$slug"
            try {
                val resp = probeClient.newCall(
                    Request.Builder().url(url)
                        .head()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                        .build()
                ).execute()
                val finalUrl = resp.request.url.toString()
                // SC redirige les slugs invalides vers /search ou /discover
                if (resp.isSuccessful
                    && !finalUrl.contains("/search")
                    && !finalUrl.contains("/discover")
                    && finalUrl.contains("soundcloud.com/$slug")) {
                    Log.i(TAG, "Direct slug probe ✓ $url")
                    return@withContext url
                }
                Log.d(TAG, "Direct slug probe: $url → ${resp.code} (final=$finalUrl)")
            } catch (e: Exception) {
                Log.d(TAG, "Direct slug probe failed for $slug: ${e.message}")
            }
        }
        null
    }

    /** Fallback : DDG site:soundcloud.com [query] avec scoring multi-candidats. */
    private suspend fun findArtistPageViaDDG(artistName: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode("site:soundcloud.com $artistName", "UTF-8")
            val client = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            val html = client.newCall(
                Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encoded")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "text/html").build()
            ).execute().use { it.body?.string() } ?: return@withContext null

            val systemSlugs = setOf("pages","legal","press","imprint","blog","jobs","charts",
                "mobile","discover","you","notifications","stream","messages","settings",
                "upload","feed","presse","societe","contact","help","terms","privacy",
                "about","login","register","search","tags")
            val queryTokens = artistName.lowercase().replace(Regex("[^a-z0-9 ]")," ")
                .trim().split(Regex("\\s+")).filter { it.length >= 2 }

            data class Scored(val slug: String, val score: Int)
            val scored = Regex("""uddg=([^&"]+)""").findAll(html)
                .map { URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .filter { it.contains("soundcloud.com") }
                .take(8)
                .mapNotNull { url ->
                    val slug = Regex("""soundcloud\.com/([^/?#]+)""").find(url)
                        ?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                    if (slug in systemSlugs) return@mapNotNull null
                    val score = queryTokens.sumOf { t -> if (slug.contains(t)) t.length else 0 }
                    Scored(slug, score)
                }
                .sortedByDescending { it.score }
                .toList()

            val minScore = queryTokens.sumOf { it.length } // tous les tokens doivent matcher
            val best = scored.firstOrNull()
            if (best == null || best.score < minScore) {
                Log.w(TAG, "DDG fallback: score insuffisant pour '$artistName' (best=${best?.slug}/${best?.score}, min=$minScore)")
                return@withContext null
            }
            Log.i(TAG, "DDG fallback: ${best.slug} (score=${best.score})")
            "https://soundcloud.com/${best.slug}"
        } catch (e: Exception) {
            Log.w(TAG, "DDG fallback failed: ${e.message}")
            null
        }
    }

    private suspend fun scrapeUsersOnMainThread(url: String): List<String>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            var resumed = false

            fun resume(result: List<String>?) {
                if (!resumed) {
                    resumed = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
                    cont.resume(result)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onUsersExtracted(json: String) {
                    try {
                        val arr = org.json.JSONArray(json)
                        val slugs = (0 until arr.length()).map { arr.getString(it) }
                        Log.i(TAG, "SC search users: $slugs")
                        resume(slugs)
                    } catch (e: Exception) {
                        Log.e(TAG, "onUsersExtracted parse error: ${e.message}")
                        resume(null)
                    }
                }
                @JavascriptInterface
                fun onDebug(msg: String) { Log.d(TAG, "JS_SEARCH: $msg") }
            }, "Android")

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "SC search page finished: $url")
                    view.evaluateJavascript(SEARCH_USERS_JS, null)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) resume(null)
                }
            }
            cont.invokeOnCancellation { resume(null) }
            webView.loadUrl(url)
        }

    private suspend fun scrapeArtistPage(url: String): List<ScSet>? {
        return withTimeoutOrNull(75_000) {
            withContext(Dispatchers.Main) {
                scrapeOnMainThread(url)
            }
        }
    }

    private suspend fun scrapeOnMainThread(url: String): List<ScSet>? =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var resumed = false

            fun resume(result: List<ScSet>?) {
                if (!resumed) {
                    resumed = true
                    handler.post { webView.destroy() }
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
                @JavascriptInterface
                fun onDebug(msg: String) {
                    Log.d(TAG, "JS: $msg")
                }
            }, "Android")

            // Grande fenêtre visible = SC charge plus de contenu initialement
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            // Forcer une grande taille de layout pour que l'IntersectionObserver SC voie plus d'items
            webView.layout(0, 0, 1080, 8000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Page finished, injecting EXTRACT_JS")
                    view.evaluateJavascript(EXTRACT_JS, null)

                    // Fling natif toutes les 1.5s pour déclencher l'infinite scroll SC
                    // (window.scrollTo en JS ne suffit pas — SC utilise IntersectionObserver)
                    var flingCount = 0
                    val flingRunnable = object : Runnable {
                        override fun run() {
                            if (resumed || flingCount >= 60) return
                            view.scrollBy(0, 2000)
                            view.flingScroll(0, 4000)
                            flingCount++
                            Log.d(TAG, "fling #$flingCount, scrollY=${view.scrollY}")
                            handler.postDelayed(this, 1500)
                        }
                    }
                    handler.postDelayed(flingRunnable, 1500) // 1ère impulsion après 1.5s
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
                val date = obj.optString("date", "").trim()
                val durationStr = obj.optString("duration", "").trim()
                val durationSec = parseDurationStr(durationStr)
                if (title.isNotBlank() && url.isNotBlank()) {
                    tracks.add(ScSet(title = title, url = url, date = date, durationSec = durationSec))
                }
            }
            if (tracks.isNotEmpty()) tracks else null
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            null
        }
    }

    /** Parse "H:MM:SS" ou "MM:SS" → secondes. Retourne 0 si non parseable. */
    private fun parseDurationStr(s: String): Int {
        if (s.isBlank()) return 0
        val parts = s.trim().split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                else -> 0
            }
        } catch (_: NumberFormatException) { 0 }
    }

    // ── Filtrage qualité ─────────────────────────────────────────────────────────

    /**
     * Niveau 0 — Titre clairement bogus : liens de navigation SC (Société, Service presse…)
     * ou titre trop court pour être un vrai set.
     */
    fun isBogusTitle(title: String): Boolean {
        val t = title.trim()
        if (t.length < 5) return true
        val knownNoise = setOf(
            "société", "service presse", "presse", "imprint", "legal",
            "contact", "company", "about us", "terms", "privacy", "blog",
            "jobs", "help center", "cookies", "copyright", "community",
            "soundcloud", "tracks", "likes", "reposts", "following", "followers"
        )
        return knownNoise.contains(t.lowercase())
    }

    /**
     * Niveau 1 — Détection podcast/single par le titre (instant, zero network).
     *
     * Détecte :
     * - "Episode 123", "Ep.45", "#12", "Vol. 3"  → série radio/podcast
     * - Mots-clés radio explicites : podcast, weekly, monthly, sessions, radio show...
     * - Titres = [Mots] + [entier > 19] sans contexte live/venue (ex: "Captive Soul 47")
     * - Mots-clés single : "Original Mix", "Extended Mix", "Remix", "Radio Edit", etc.
     */
    fun isPodcastTitle(title: String): Boolean {
        val t = title.lowercase()

        // Mots-clés single audio (pas un set)
        val singleKeywords = listOf(
            "original mix", "extended mix", "radio edit", "club mix",
            "vip mix", "dub mix", "rework", "remaster",
            " feat.", " ft.", " featuring "
        )
        if (singleKeywords.any { t.contains(it) }) return true

        // Mots-clés radio explicites
        val radioKeywords = listOf(
            "podcast", "radio show", "radio mix", "weekly", "monthly",
            "sessions ep", "radio episode", "radio #",
            "presents go on air", "go on air"
        )
        if (radioKeywords.any { t.contains(it) }) return true

        // "radio" seul comme mot entier : "Wake Your Mind Radio 432", "WYM Radio", etc.
        if (Regex("""\bradio\b""").containsMatchIn(t)) return true

        // Patterns épisode numérotés : "Episode 123", "Ep.45", "Ep 12", "#45", "Vol. 3"
        val episodePattern = Regex(
            """\b(episode|ep\.?|vol\.?)\s*\d+\b|#\s*\d{2,}""",
            RegexOption.IGNORE_CASE
        )
        if (episodePattern.containsMatchIn(title)) return true

        // Heuristique série : titre se termine par un entier > 19 SANS contexte live/venue
        // EXCEPTION : les années (1900-2099) ne sont PAS des numéros de série
        val liveContext = listOf("live", "at", "@", "festival", "stage", "club", "arena",
            "techno", "trance", "set", "mix", "boiler", "cercle")
        val hasLiveContext = liveContext.any { t.contains(it) }
        if (!hasLiveContext) {
            // Nombre > 19 en fin de titre OU suivi de texte non-significatif (tiret, pipe, parenthèse)
            val seriesPattern = Regex("""\b(0\d+|[2-9]\d{1,3}|[1-9]\d{2,})\s*(?:[-–|({].*)?$""")
            val yearPattern   = Regex("""\b(19|20)\d{2}\s*(?:[-–|({].*)?$""")  // années exclues
            if (seriesPattern.containsMatchIn(title.trim()) && !yearPattern.containsMatchIn(title.trim())) return true
        }

        return false
    }

    /**
     * Niveau 2 — Durée de la piste SC via la page HTML.
     * Essaie dans l'ordre :
     *  1. window.__sc_hydration JSON (data.duration en ms) — le plus fiable
     *  2. <meta property="music:duration" content="..."> (en secondes)
     * Retourne la durée en secondes, ou 0 si non disponible.
     */
    private suspend fun fetchTrackDurationSec(trackUrl: String): Int = withContext(Dispatchers.IO) {
        try {
            val client = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val html = client.newCall(
                Request.Builder()
                    .url(trackUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", "https://soundcloud.com/")
                    .build()
            ).execute().use { resp ->
                val body = resp.body ?: return@use ""
                val source = body.source()
                val sb = StringBuilder()
                val buf = okio.Buffer()
                // Lire jusqu'à __sc_hydration ou 64 KB max (hydration est dans le body)
                while (sb.length < 65_536) {
                    if (source.exhausted()) break
                    source.read(buf, 4096)
                    val chunk = buf.readUtf8()
                    sb.append(chunk)
                    if (sb.contains("__sc_hydration", ignoreCase = false)) break
                }
                sb.toString()
            }

            // 1. Essai window.__sc_hydration — "duration" en millisecondes
            val hydrationMs = Regex(""""duration"\s*:\s*(\d{4,})""").find(html)
                ?.groupValues?.get(1)?.toLongOrNull()
            if (hydrationMs != null && hydrationMs > 0) {
                val sec = (hydrationMs / 1000).toInt()
                Log.d(TAG, "duration(hydration) $trackUrl → ${sec}s")
                return@withContext sec
            }

            // 2. Fallback <meta property="music:duration"> — en secondes
            val metaRegex = Regex(
                """<meta[^>]*(?:property="music:duration"[^>]*content="(\d+)"|content="(\d+)"[^>]*property="music:duration")""",
                RegexOption.IGNORE_CASE
            )
            val metaMatch = metaRegex.find(html)
            val metaSec = metaMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: metaMatch?.groupValues?.get(2)?.toIntOrNull()
            if (metaSec != null && metaSec > 0) {
                Log.d(TAG, "duration(meta) $trackUrl → ${metaSec}s")
                return@withContext metaSec
            }

            Log.d(TAG, "no duration found for $trackUrl (html=${html.length}B)")
            0
        } catch (e: Exception) {
            Log.d(TAG, "fetchDuration failed for $trackUrl: ${e.message}")
            0
        }
    }

    /**
     * Filtre qualité sur une liste de ScSet (3 niveaux + fallback) :
     * 0. Titre bogus (nav SC, trop court)
     * 1. Titre podcast/single (mots-clés + pattern épisode numéroté)
     * 2. Durée < 45 min — si DOM n'a pas capturé la durée (0), fetch HTTP la page pour la lire
     * Fallback : si < 20 résultats valides, on réintègre les séries numérotées (ex: WYM)
     *            uniquement si leur durée réelle est >= 45 min (vrais DJ mixes)
     */
    suspend fun filterSets(sets: List<ScSet>): List<ScSet> {
        val MIN_DURATION_SEC = 45 * 60

        // Niveau 0 : bogus
        val afterBogus = sets.filter { set ->
            val bogus = isBogusTitle(set.title)
            if (bogus) Log.i(TAG, "Excluded bogus: '${set.title}'")
            !bogus
        }
        Log.i(TAG, "After bogus: ${afterBogus.size}/${sets.size}")

        // Niveau 1 : podcast/single
        val afterTitle = afterBogus.filter { set ->
            val excl = isPodcastTitle(set.title)
            if (excl) Log.i(TAG, "Excluded podcast: '${set.title}'")
            !excl
        }
        Log.i(TAG, "After title: ${afterTitle.size}/${afterBogus.size}")

        // Niveau 2 : durée — fetch HTTP pour les tracks sans durée DOM (max 5 en parallèle)
        val durationSemaphore = Semaphore(5)
        suspend fun resolveDuration(set: ScSet): ScSet {
            if (set.durationSec != 0) return set
            val fetched = durationSemaphore.withPermit { fetchTrackDurationSec(set.url) }
            Log.i(TAG, "Fetched duration: '${set.title.take(50)}' → ${fetched}s")
            return set.copy(durationSec = fetched)
        }

        val resolved = coroutineScope { afterTitle.map { async { resolveDuration(it) } }.awaitAll() }
        val afterDur = resolved.filter { set ->
            val keep = set.durationSec == 0 || set.durationSec >= MIN_DURATION_SEC
            if (!keep) Log.i(TAG, "Excluded short (${set.durationSec}s): '${set.title.take(50)}'")
            keep
        }
        Log.i(TAG, "After duration: ${afterDur.size}/${afterTitle.size}")

        return afterDur
    }

    /** Indique si les résultats SC sont insuffisants → déclencher fallback YouTube */
    fun needsYoutubeFallback(scResults: List<ScSet>): Boolean = scResults.size < 20
}
