package com.podmix

import org.junit.Test

import org.junit.Assert.*
import com.podmix.domain.model.SourceResult
import com.podmix.domain.model.SourceStatus

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

class SourceResultTest {

    @Test fun pending_source_has_zero_tracks() {
        val r = SourceResult("Description YouTube", SourceStatus.PENDING)
        assertEquals(0, r.trackCount)
        assertEquals(0L, r.elapsedMs)
        assertEquals("", r.reason)
    }

    @Test fun success_source_carries_count_and_elapsed() {
        val r = SourceResult("Mixcloud", SourceStatus.SUCCESS, trackCount = 14, elapsedMs = 843L)
        assertEquals(SourceStatus.SUCCESS, r.status)
        assertEquals(14, r.trackCount)
        assertEquals(843L, r.elapsedMs)
    }

    @Test fun skipped_source_carries_reason() {
        val r = SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = "pas de chapters")
        assertEquals("pas de chapters", r.reason)
    }

    @Test fun all_statuses_are_defined() {
        val statuses = SourceStatus.values()
        assertTrue(statuses.contains(SourceStatus.PENDING))
        assertTrue(statuses.contains(SourceStatus.RUNNING))
        assertTrue(statuses.contains(SourceStatus.SUCCESS))
        assertTrue(statuses.contains(SourceStatus.FAILED))
        assertTrue(statuses.contains(SourceStatus.SKIPPED))
    }
}

class SourceEmissionTest {

    @Test fun success_result_has_correct_fields() {
        val t0 = System.currentTimeMillis()
        Thread.sleep(5)
        val elapsed = System.currentTimeMillis() - t0
        val r = SourceResult("Description YouTube", SourceStatus.SUCCESS, trackCount = 14, elapsedMs = elapsed)
        assertEquals(SourceStatus.SUCCESS, r.status)
        assertTrue(r.trackCount > 0)
        assertTrue(r.elapsedMs > 0)
    }

    @Test fun skipped_result_preserves_reason() {
        val r = SourceResult("yt-dlp", SourceStatus.SKIPPED, reason = "serveur hors ligne")
        assertEquals(SourceStatus.SKIPPED, r.status)
        assertEquals("serveur hors ligne", r.reason)
    }

    @Test fun failed_result_has_zero_tracks() {
        val r = SourceResult("Mixcloud", SourceStatus.FAILED, trackCount = 0, elapsedMs = 950L)
        assertEquals(0, r.trackCount)
        assertEquals(SourceStatus.FAILED, r.status)
    }
}