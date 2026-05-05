package com.podmix.service

import com.podmix.AppLogger
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * OkHttp interceptor — logue TOUTES les requêtes réseau de l'app :
 * Deezer, Spotify, 1001TL, DDG, SoundCloud, iTunes, Mixcloud, Piped, etc.
 *
 * Format : [NET] GET url → 200 (142ms) | body_snippet
 */
class PodmixLoggingInterceptor : Interceptor {

    private val MAX_BODY = 300  // chars du body à logger

    // Domaines à logger en détail (body snippet inclus)
    private val verboseDomains = setOf(
        "deezer.com", "api.spotify.com", "1001tracklists.com",
        "duckduckgo.com", "soundcloud.com", "miroppb.com",
        "itunes.apple.com", "mixcloud.com", "pipedapi"
    )

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url.toString()
        val method = req.method
        val tag = domainTag(url)

        val t0 = System.currentTimeMillis()

        val response: Response
        try {
            response = chain.proceed(req)
        } catch (e: Exception) {
            AppLogger.err("NET", "$method $url → EXCEPTION", e.message ?: e.javaClass.simpleName)
            throw e
        }

        val ms = System.currentTimeMillis() - t0
        val code = response.code

        val verbose = verboseDomains.any { url.contains(it) }
        if (verbose) {
            // Lire body sans consommer (peek)
            val bodySnippet = try {
                val source = response.peekBody(MAX_BODY.toLong())
                source.string().take(MAX_BODY).replace('\n', ' ')
            } catch (_: Exception) { "" }

            val level = if (code in 200..299) "NET" else "ERR/NET"
            AppLogger.log(level, "$method $url → $code (${ms}ms)", bodySnippet)
        } else {
            val level = if (code in 200..299) "NET" else "ERR/NET"
            AppLogger.log(level, "$method $url → $code (${ms}ms)")
        }

        return response
    }

    private fun domainTag(url: String): String = when {
        url.contains("deezer.com")       -> "DEEZER"
        url.contains("spotify.com")      -> "SPOTIFY"
        url.contains("1001tracklists")   -> "1001TL"
        url.contains("duckduckgo")       -> "DDG"
        url.contains("soundcloud.com")   -> "SC"
        url.contains("miroppb")          -> "MIRO"
        url.contains("itunes.apple")     -> "ITUNES"
        url.contains("mixcloud")         -> "MIXCLOUD"
        url.contains("pipedapi")         -> "PIPED"
        else -> "NET"
    }
}
