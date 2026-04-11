package com.podmix

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}

class PreRollSeekTest {

    private fun computeSeekMs(startTimeSec: Float, episodeType: String, durationMs: Long = Long.MAX_VALUE): Long {
        val preRollMs = when (episodeType) {
            "liveset" -> 30_000L
            else      -> 10_000L
        }
        val rawMs = (startTimeSec * 1000).toLong()
        return (rawMs - preRollMs).coerceAtLeast(0L).coerceAtMost(durationMs)
    }

    @Test fun liveset_preroll_is_30s() {
        assertEquals(0L, computeSeekMs(10f, "liveset"))        // 10s - 30s = 0 (coerce)
        assertEquals(30_000L, computeSeekMs(60f, "liveset"))   // 60s - 30s = 30s
    }

    @Test fun podcast_preroll_is_10s() {
        assertEquals(0L, computeSeekMs(5f, "podcast"))         // 5s - 10s = 0 (coerce)
        assertEquals(50_000L, computeSeekMs(60f, "podcast"))   // 60s - 10s = 50s
    }

    @Test fun duration_cap() {
        assertEquals(100L, computeSeekMs(60f, "podcast", durationMs = 100L))
    }
}