package com.podmix.service

import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refines DJ mix timestamps by matching each track against its Deezer preview
 * via chroma cross-correlation.
 *
 * Replaces AudioTransitionDetector for live sets (RMS energy is fundamentally
 * wrong for seamless DJ mixes — it detects musical drops, not DJ transitions).
 *
 * Triggered automatically after a liveset download when trackRefinementStatus
 * is "chroma_pending" (set by TrackRepository.saveTracks for livesets without
 * real timestamps).
 */
@Singleton
class ChromaTimestampRefiner @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao,
    private val chromaExtractor: ChromaExtractor,
    private val deezerPreviewFetcher: DeezerPreviewFetcher,
    private val chromaCorrelator: ChromaCorrelator
) {
    companion object {
        private const val TAG = "ChromaRefiner"
    }

    /** Scope indépendant du ViewModel — survit à la navigation et au background. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per-episode progress 0-100, collected by ViewModels for the progress bar. */
    private val _progress = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val progress: StateFlow<Map<Int, Int>> = _progress.asStateFlow()

    private fun setProgress(id: Int, pct: Int) { _progress.value = _progress.value + (id to pct) }
    private fun clearProgress(id: Int)          { _progress.value = _progress.value - id }

    // ── Public entry point ─────────────────────────────────────────────────────

    /** Lance le refinement en fire-and-forget dans un scope indépendant. */
    fun refine(episodeId: Int) {
        scope.launch { doRefine(episodeId) }
    }

    private suspend fun doRefine(episodeId: Int) {
        val episode   = episodeDao.getById(episodeId) ?: return
        val localPath = episode.localAudioPath ?: run {
            Log.w(TAG, "Episode $episodeId has no local audio — skip chroma refinement")
            return
        }
        val tracks = trackDao.getByEpisodeId(episodeId).sortedBy { it.position }
        if (tracks.isEmpty()) {
            Log.w(TAG, "Episode $episodeId has no tracks — skip")
            return
        }
        if (tracks.all { it.source == "chroma_matched" }) {
            Log.i(TAG, "Episode $episodeId already chroma-matched — skip")
            episodeDao.updateRefinementStatus(episodeId, "done")
            return
        }

        Log.i(TAG, "Chroma refinement: '${episode.title}' — ${tracks.size} tracks")
        episodeDao.updateRefinementStatus(episodeId, "refining")
        setProgress(episodeId, 0)

        try {
            // ── Windowed approach: per track, seek + decode 6min window ────────
            val WINDOW_BEFORE = 180f  // seconds before estimated position
            val WINDOW_AFTER  = 180f  // seconds after  estimated position
            val WINDOW_DUR    = WINDOW_BEFORE + WINDOW_AFTER

            // Save original estimated timestamps for rollback during post-validation
            val originalTs: Map<Int, Float> = tracks.associate { it.id to it.startTimeSec }

            // Monotonicity guard: track start must be after the previous matched track.
            var lastMatchedTs = -1f

            var matched = 0
            tracks.forEachIndexed { idx, track ->
                setProgress(episodeId, 5 + idx * 85 / tracks.size)

                val artist = track.artist.ifBlank { "" }
                val title  = track.title
                if (title.isBlank()) return@forEachIndexed

                val estimatedTs = track.startTimeSec

                // Skip impossible timestamps (beyond episode duration)
                val dur = episode.durationSeconds
                if (dur > 0 && estimatedTs > dur.toFloat()) {
                    Log.w(TAG, "[$idx/${tracks.size}] '$artist - $title' estimated ts ${estimatedTs.toInt()}s > duration ${dur}s → skip (impossible)")
                    return@forEachIndexed
                }

                Log.i(TAG, "[$idx/${tracks.size}] '$artist - $title' ~${estimatedTs.toInt()}s")

                // 1. Fetch Deezer preview (4-strategy cascade in DeezerPreviewFetcher)
                val previewPcm = deezerPreviewFetcher.fetchPreviewPcm(artist, title)
                if (previewPcm == null) {
                    Log.d(TAG, "  → no Deezer preview, skip")
                    return@forEachIndexed
                }
                val previewChroma = chromaExtractor.extractFromPcm(previewPcm)
                if (previewChroma.isEmpty()) {
                    Log.d(TAG, "  → empty preview chroma, skip")
                    return@forEachIndexed
                }

                // 2. Extract mix chroma window (floored at lastMatchedTs+5s)
                val windowStartRaw = (estimatedTs - WINDOW_BEFORE).coerceAtLeast(0f)
                val windowStart = if (lastMatchedTs >= 0f)
                    windowStartRaw.coerceAtLeast(lastMatchedTs + 5f)
                else
                    windowStartRaw
                val windowChroma = chromaExtractor.extractWindowFromFile(
                    path = localPath,
                    startSec = windowStart,
                    durationSec = WINDOW_DUR
                )
                if (windowChroma == null) {
                    Log.w(TAG, "  → window extraction failed, skip")
                    return@forEachIndexed
                }

                // 3. Multi-probe correlate with median-smoothed mix chroma
                val probeResult = chromaCorrelator.findBestMatchMultiProbe(windowChroma, previewChroma)
                if (probeResult != null) {
                    val absoluteTs = windowStart + probeResult.timestampSec
                    if (lastMatchedTs >= 0f && absoluteTs <= lastMatchedTs) {
                        Log.w(TAG, "  → match @ ${absoluteTs.toInt()}s rejected (monotonicity: last=${lastMatchedTs.toInt()}s)")
                        return@forEachIndexed
                    }
                    trackDao.update(track.copy(startTimeSec = absoluteTs, source = "chroma_matched"))
                    Log.i(TAG, "  → matched @ ${absoluteTs.toInt()}s (est=${estimatedTs.toInt()}s, Δ=${(absoluteTs - estimatedTs).toInt()}s, conf=${"%.0f".format(probeResult.confidence * 100)}%, score=${"%.3f".format(probeResult.avgScore)})")
                    lastMatchedTs = absoluteTs
                    matched++
                } else {
                    Log.d(TAG, "  → no consensus, keeping ts=${estimatedTs.toInt()}s")
                }
            }

            Log.i(TAG, "Chroma refinement done: $matched/${tracks.size} tracks matched")

            // ── Post-match consistency validation ──────────────────────────────
            // Checks inter-track gaps on chroma_matched tracks.
            // Gap < 30s or > 1200s = impossible → rollback to estimated + mark suspect.
            // Also flags any track with ts > episode duration as suspect.
            setProgress(episodeId, 92)
            validateConsistency(episodeId, originalTs, episode.durationSeconds)

        } catch (e: Exception) {
            Log.e(TAG, "Refinement error for episode $episodeId: ${e.message}", e)
        } finally {
            setProgress(episodeId, 100)
            episodeDao.updateRefinementStatus(episodeId, "done")
            clearProgress(episodeId)
        }
    }

    /**
     * Post-match consistency check:
     * 1. Any track (matched or not) with ts > episode duration → chroma_suspect + redistribute
     * 2. Inter-track gap among chroma_matched: gap < 30s or > 1200s → rollback + suspect
     * 3. Final pass: redistribute any track still beyond duration
     */
    private suspend fun validateConsistency(episodeId: Int, originalTs: Map<Int, Float>, durationSec: Int) {
        val refined = trackDao.getByEpisodeId(episodeId).sortedBy { it.position }
        var suspects = 0

        refined.forEachIndexed { idx, track ->
            // Rule 1: timestamp beyond episode duration → impossible
            if (durationSec > 0 && track.startTimeSec > durationSec.toFloat()) {
                trackDao.update(track.copy(source = "chroma_suspect"))
                Log.w(TAG, "  ⚠ '${track.title}' ts=${track.startTimeSec.toInt()}s > duration=${durationSec}s → suspect")
                suspects++
                return@forEachIndexed
            }

            // Rule 2: gap check (chroma_matched only)
            if (track.source != "chroma_matched") return@forEachIndexed

            val prev = refined.take(idx).lastOrNull { it.source == "chroma_matched" }
                ?: return@forEachIndexed

            val gap = track.startTimeSec - prev.startTimeSec
            if (gap < 30f || gap > 1200f) {
                val fallback = originalTs[track.id] ?: track.startTimeSec
                trackDao.update(track.copy(startTimeSec = fallback, source = "chroma_suspect"))
                Log.w(TAG, "  ⚠ '${track.title}' gap=${gap.toInt()}s → suspect, rollback to est=${fallback.toInt()}s")
                suspects++
            }
        }

        if (suspects > 0) Log.i(TAG, "Post-validation: $suspects track(s) flagged as chroma_suspect")

        // Rule 3: redistribute any track still beyond duration (includes freshly flagged ones)
        if (durationSec > 0) redistributeBeyondDuration(episodeId, durationSec)
    }

    /**
     * Redistributes tracks whose startTimeSec exceeds episode duration.
     * Groups them after the last valid anchor and spreads them evenly toward the end.
     *
     * Example: 13 tracks, last valid at 3573s, duration=3641s, 2 tracks beyond:
     *   → track 12 = 3573 + (3631-3573)/3*1 = ~3592s
     *   → track 13 = 3573 + (3631-3573)/3*2 = ~3611s
     */
    suspend fun redistributeBeyondDuration(episodeId: Int, durationSec: Int) {
        if (durationSec <= 0) return
        val tracks = trackDao.getByEpisodeId(episodeId).sortedBy { it.position }
        val dur = durationSec.toFloat()

        val beyond = tracks.filter { it.startTimeSec > dur }
        if (beyond.isEmpty()) return

        // Last valid anchor = highest ts still within duration
        val lastValidTs = tracks
            .filter { it.startTimeSec <= dur }
            .maxOfOrNull { it.startTimeSec } ?: 0f

        // Spread evenly in [lastValidTs, dur - 5s], leaving a 5s gap before the end
        val window = (dur - 5f - lastValidTs).coerceAtLeast(0f)
        val step = if (beyond.size > 1) window / beyond.size else window / 2f

        beyond.forEachIndexed { i, track ->
            val newTs = (lastValidTs + step * (i + 1)).coerceAtMost(dur - 5f).coerceAtLeast(lastValidTs + 5f)
            if (newTs != track.startTimeSec) {
                trackDao.update(track.copy(startTimeSec = newTs, source = "chroma_suspect"))
                Log.i(TAG, "  ↻ redistribute '${track.title}' ${track.startTimeSec.toInt()}s → ${newTs.toInt()}s (dur=${durationSec}s)")
            }
        }
    }
}
