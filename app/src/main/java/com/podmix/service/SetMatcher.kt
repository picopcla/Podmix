package com.podmix.service

import com.podmix.ui.screens.liveset.DiscoveredSet
import com.podmix.ui.screens.liveset.ScSet

object SetMatcher {

    private val NOISE_WORDS = setOf(
        "live", "set", "mix", "dj", "radio", "show", "episode",
        "festival", "presents", "records", "at", "the", "in", "a", "an",
        "and", "of", "for", "with", "by"
    )

    fun normalize(title: String): Set<String> =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in NOISE_WORDS }
            .toSet()

    fun score(a: String, b: String): Float {
        val wordsA = normalize(a)
        val wordsB = normalize(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f
        val common = wordsA.intersect(wordsB).size
        val total = minOf(wordsA.size, wordsB.size)
        return common.toFloat() / total.toFloat()
    }

    fun merge(tlSets: List<ArtistSet>, scSets: List<ScSet>): List<DiscoveredSet> {
        val result = mutableListOf<DiscoveredSet>()
        val matchedTlIndices = mutableSetOf<Int>()

        for (sc in scSets) {
            var bestScore = 0f
            var bestIdx = -1
            tlSets.forEachIndexed { idx, tl ->
                val s = score(sc.title, tl.title)
                if (s > bestScore) { bestScore = s; bestIdx = idx }
            }
            if (bestScore >= 0.5f && bestIdx >= 0) {
                val tl = tlSets[bestIdx]
                matchedTlIndices.add(bestIdx)
                result.add(DiscoveredSet(
                    title = tl.title,
                    date = tl.date.ifBlank { sc.date },
                    viewCount = tl.viewCount,
                    soundcloudUrl = sc.url,
                    tracklistUrl = tl.tracklistUrl,
                    youtubeVideoId = tl.youtubeVideoId
                ))
            } else {
                result.add(DiscoveredSet(
                    title = sc.title,
                    date = sc.date,
                    viewCount = 0,
                    soundcloudUrl = sc.url,
                    tracklistUrl = null,
                    youtubeVideoId = null
                ))
            }
        }

        // Sets 1001TL non matchés
        tlSets.forEachIndexed { idx, tl ->
            if (idx !in matchedTlIndices) {
                result.add(DiscoveredSet(
                    title = tl.title,
                    date = tl.date,
                    viewCount = tl.viewCount,
                    soundcloudUrl = null,
                    tracklistUrl = tl.tracklistUrl,
                    youtubeVideoId = tl.youtubeVideoId
                ))
            }
        }

        return result
    }
}
