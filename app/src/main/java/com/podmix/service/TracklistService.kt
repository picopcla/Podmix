package com.podmix.service

import android.util.Log
import com.podmix.data.api.ItunesApi
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ParsedTrack(
    val artist: String,
    val title: String,
    val startTimeSec: Float
)

@Singleton
class TracklistService @Inject constructor(
    private val itunesApi: ItunesApi
) {

    private val TAG = "TracklistService"

    private val timestampRegex = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /**
     * Main entry: tries all strategies in order, returns first non-empty result.
     */
    fun detect(description: String?, podcastName: String?, episodeTitle: String): List<ParsedTrack> {
        // 1. Parse timestamps from description (YouTube style: "00:00 Artist - Title")
        val fromTimestamps = if (!description.isNullOrBlank()) parseTimestamped(description) else emptyList()
        if (fromTimestamps.size >= 3) return fromTimestamps

        // 2. Parse numbered list from description (RSS style: "01. Artist - Title [Label]")
        val fromNumbered = if (!description.isNullOrBlank()) parseNumberedList(description) else emptyList()
        if (fromNumbered.size >= 3) return fromNumbered

        // 3. Parse plain "Artist - Title" lines from description
        val fromPlain = if (!description.isNullOrBlank()) parsePlainLines(description) else emptyList()
        if (fromPlain.size >= 3) return fromPlain

        // 4. Scrape 1001Tracklists with episode title + podcast name
        val query1001 = buildString {
            podcastName?.let { append("$it ") }
            append(episodeTitle)
        }
        val from1001 = scrapeFrom1001Tracklists(query1001)
        if (from1001.isNotEmpty()) return from1001

        // 5. Try 1001TL with just episode title
        if (podcastName != null) {
            val from1001b = scrapeFrom1001Tracklists(episodeTitle)
            if (from1001b.isNotEmpty()) return from1001b
        }

        return emptyList()
    }

    /**
     * Resolve timestamps by looking up track durations on iTunes and computing cumulative positions.
     * Falls back to uniform distribution if iTunes lookup fails for most tracks.
     */
    suspend fun resolveTimestamps(tracks: List<ParsedTrack>, episodeDurationSec: Int): List<ParsedTrack> {
        if (tracks.any { it.startTimeSec > 0f }) return tracks

        val resolved = mutableListOf<ParsedTrack>()
        var cumulativeSec = 0f
        var foundCount = 0

        for (track in tracks) {
            resolved.add(track.copy(startTimeSec = cumulativeSec))

            val query = "${track.artist} ${track.title}"
                .replace(" x ", " ").replace(" & ", " ")
                .replace(" feat. ", " ").replace(" ft. ", " ")
            val durationSec = try {
                val result = itunesApi.searchMusic(query)
                val millis = result.results.firstOrNull()?.trackTimeMillis
                if (millis != null && millis > 30_000) {
                    foundCount++
                    millis / 1000f
                } else null
            } catch (_: Exception) { null }

            cumulativeSec += durationSec
                ?: if (episodeDurationSec > 0) (episodeDurationSec.toFloat() / tracks.size) else 210f
        }

        // If we found less than half the tracks on iTunes, fallback to uniform
        if (foundCount < tracks.size / 2 && episodeDurationSec > 0) {
            return tracks.mapIndexed { idx, t ->
                t.copy(startTimeSec = (episodeDurationSec.toLong() * idx / tracks.size).toFloat())
            }
        }

        return resolved
    }

    /**
     * Strategy 1: Timestamped lines (YouTube descriptions)
     * Patterns: 00:00 Artist - Title, [00:00] Artist - Title, etc.
     */
    fun parseTimestamped(text: String): List<ParsedTrack> {
        val results = mutableListOf<ParsedTrack>()
        val lines = cleanHtml(text).lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val match = timestampRegex.find(trimmed) ?: continue
            val startTimeSec = parseTimestampMatch(match)

            var trackText = trimmed
                .replace(Regex("""\[?\(?\d{1,2}:\d{2}(?::\d{2})?\)?\]?"""), "")
                .replace(Regex("""^\d+\.\s*"""), "")
                .replace(Regex("""^[-–—]\s*"""), "")
                .trim()

            if (trackText.isBlank()) continue
            val (artist, title) = splitArtistTitle(trackText)
            if (title.isNotBlank()) {
                results.add(ParsedTrack(artist, title, startTimeSec))
            }
        }
        return results
    }

    /**
     * Strategy 2: Numbered list (common in podcast RSS)
     * Patterns: "01. Artist - Title [Label]", "1) Artist - Title"
     */
    fun parseNumberedList(text: String): List<ParsedTrack> {
        val results = mutableListOf<ParsedTrack>()
        val lines = cleanHtml(text).lines()
        val numberPattern = Regex("""^\d{1,3}[.)]\s*(.+)""")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val match = numberPattern.find(trimmed) ?: continue
            var trackText = match.groupValues[1].trim()

            // Remove [Label] at the end
            trackText = trackText.replace(Regex("""\s*\[.*?]$"""), "").trim()
            // Remove (Label) at the end if it looks like a label
            trackText = trackText.replace(Regex("""\s*\((?!.*(?:remix|mix|edit|version|feat)).*\)$""", RegexOption.IGNORE_CASE), "").trim()

            if (trackText.isBlank()) continue
            val (artist, title) = splitArtistTitle(trackText)
            if (title.isNotBlank()) {
                results.add(ParsedTrack(artist, title, 0f)) // No timestamp
            }
        }
        return results
    }

    /**
     * Strategy 3: Plain "Artist - Title" lines (no numbers, no timestamps)
     */
    fun parsePlainLines(text: String): List<ParsedTrack> {
        val results = mutableListOf<ParsedTrack>()
        val lines = cleanHtml(text).lines()
        val separatorRegex = Regex("""\s+[-–—]\s+""")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.length < 5 || trimmed.length > 200) continue
            if (!separatorRegex.containsMatchIn(trimmed)) continue

            // Remove [Label] and (Label)
            var trackText = trimmed.replace(Regex("""\s*\[.*?]"""), "").trim()
            trackText = trackText.replace(Regex("""\s*\((?!.*(?:remix|mix|edit|version|feat)).*\)$""", RegexOption.IGNORE_CASE), "").trim()

            val (artist, title) = splitArtistTitle(trackText)
            if (artist != "Unknown" && title.isNotBlank() && title.length > 1) {
                results.add(ParsedTrack(artist, title, 0f))
            }
        }
        return results
    }

    /**
     * Strategy 4: Scrape 1001Tracklists (search then scrape)
     */
    fun scrapeFrom1001Tracklists(query: String): List<ParsedTrack> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl =
                "https://www.1001tracklists.com/search/result.php?search_selection=1&main_search=$encoded"

            val searchDoc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10_000)
                .get()

            val firstLink = searchDoc.select("div.bItm a[href*=/tracklist/]").firstOrNull()
                ?: searchDoc.select("a[href*=/tracklist/]").firstOrNull()
                ?: return emptyList()

            val tracklistUrl = firstLink.absUrl("href").ifBlank {
                "https://www.1001tracklists.com${firstLink.attr("href")}"
            }

            scrapeTracklistPage(tracklistUrl)
        } catch (e: Exception) {
            Log.w(TAG, "1001Tracklists scrape failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Scrape a 1001TL tracklist page directly by URL (Jsoup, pas de WebView).
     * Extrait les tracks avec leurs timestamps (span.cueValueField).
     */
    fun scrapeTracklistPage(url: String): List<ParsedTrack> {
        return try {
            val tracklistDoc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10_000)
                .get()

            val trackElements = tracklistDoc.select("div.tlpItem, div.tlpTog")
            val tracks = mutableListOf<ParsedTrack>()

            for (el in trackElements) {
                try {
                    val trackValue = el.select("span.trackValue").text().ifBlank {
                        el.select("meta[itemprop=name]").attr("content").ifBlank {
                            el.text()
                        }
                    }
                    if (trackValue.isBlank()) continue

                    val timeText = el.select("span.cueValueField").text().ifBlank {
                        el.select("span.cueVal").text().ifBlank {
                            el.select("span.timing").text()
                        }
                    }
                    val timeSec = parseTimestampText(timeText)

                    val (artist, title) = splitArtistTitle(trackValue)
                    if (title.isNotBlank()) {
                        tracks.add(ParsedTrack(artist, title, timeSec ?: 0f))
                    }
                } catch (_: Exception) {}
            }
            Log.i(TAG, "scrapeTracklistPage: ${tracks.size} tracks, ${tracks.count { it.startTimeSec > 0f }} avec timestamps")
            tracks
        } catch (e: Exception) {
            Log.w(TAG, "scrapeTracklistPage failed: ${e.message}")
            emptyList()
        }
    }

    // --- Utilities ---

    /** Clean HTML tags, convert <br> to newlines */
    private fun cleanHtml(text: String): String {
        return text
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<p\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<li\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "") // Strip remaining HTML tags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ") // non-breaking space unicode
    }

    /** Split "Artist - Title" or "Artist – Title" */
    private fun splitArtistTitle(text: String): Pair<String, String> {
        // Try various separators: " - ", " – ", " — "
        val match = Regex("""\s+[-–—]\s+""").find(text)
        return if (match != null) {
            val artist = text.substring(0, match.range.first).trim()
            val title = text.substring(match.range.last + 1).trim()
            artist to title
        } else {
            "Unknown" to text
        }
    }

    private fun parseTimestampMatch(match: MatchResult): Float {
        val hasHours = match.groupValues[3].isNotEmpty()
        val hours = if (hasHours) match.groupValues[1].toIntOrNull() ?: 0 else 0
        val minutes = if (hasHours) match.groupValues[2].toIntOrNull() ?: 0
        else match.groupValues[1].toIntOrNull() ?: 0
        val seconds = if (hasHours) match.groupValues[3].toIntOrNull() ?: 0
        else match.groupValues[2].toIntOrNull() ?: 0
        return (hours * 3600 + minutes * 60 + seconds).toFloat()
    }

    private fun parseTimestampText(text: String): Float? {
        if (text.isBlank()) return null
        val match = timestampRegex.find(text) ?: return null
        return parseTimestampMatch(match)
    }
}
