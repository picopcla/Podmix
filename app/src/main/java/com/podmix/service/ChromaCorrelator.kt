package com.podmix.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds where a short audio clip (Deezer preview) occurs within a longer mix
 * by sliding-window cosine similarity on chroma feature sequences.
 *
 * Both input arrays are formatted as FloatArray[numFrames × CHROMA_BINS],
 * row-major (frame 0 occupies indices [0..11], frame 1 [12..23], etc.).
 */
@Singleton
class ChromaCorrelator @Inject constructor() {

    companion object {
        private const val TAG  = "ChromaCorrelator"
        private const val BINS = ChromaExtractor.CHROMA_BINS   // 12

        /** Minimum average cosine similarity to accept a single-probe match. */
        const val MIN_SCORE = 0.52f

        /** Lower threshold per probe (shorter probe = noisier score). */
        private const val MIN_SCORE_PROBE = 0.46f

        /** Skip this many leading preview frames (avoids silent/transient intro). */
        private const val SKIP_FRAMES = 15      // 15 × 32ms = 480ms

        /** Number of preview frames used for the sliding match (single-probe legacy). */
        private const val MATCH_FRAMES = 220    // 220 × 32ms ≈ 7s of preview

        /** Number of frames per multi-probe sonde (~3.5s each). */
        private const val PROBE_FRAMES = 110    // 110 × 32ms ≈ 3.5s

        /** First-pass stride in frames (coarse scan). */
        private const val STRIDE = 6            // 6 × 32ms = 192ms

        /** Refine window around best coarse hit, ±REFINE frames with stride 1. */
        private const val REFINE = STRIDE + 2

        /** Two probe results agreeing within this many frames = consensus (~2s). */
        private const val CONSENSUS_FRAMES = 64
    }

    // ── Result type ────────────────────────────────────────────────────────────

    data class ProbeResult(
        /** Absolute timestamp within the mix window (seconds from window start). */
        val timestampSec: Float,
        /** Average cosine score of agreeing probes. */
        val avgScore: Float,
        /** Fraction of probes that voted for this position (0..1). */
        val confidence: Float
    )

    // ── Multi-probe entry point ────────────────────────────────────────────────

    /**
     * Multi-probe matching: takes 3 independent sondes from different parts of
     * the Deezer preview, finds the best mix position for each, then votes for
     * consensus. Dramatically reduces false positives versus single-probe.
     *
     * Requires ≥2 probes to agree (within ±2s) to accept a match, unless only
     * 1 probe found a result with score ≥ 0.70 (high-confidence single hit).
     *
     * @param mixChroma      FloatArray[N×12] — mix window chroma
     * @param previewChroma  FloatArray[M×12] — Deezer preview chroma (~30s)
     * @param hopSec         seconds per chroma frame
     */
    /**
     * 5-frame median filter on chroma vectors (applied to mix window only).
     * Reduces DJ EQ/reverb transients without blurring genuine transitions.
     * Preview is already clean (studio recording) — no smoothing needed there.
     */
    private fun smoothChroma(chroma: FloatArray): FloatArray {
        val frames = chroma.size / BINS
        val out    = chroma.copyOf()
        val win    = FloatArray(5)
        for (f in 0 until frames) {
            for (b in 0 until BINS) {
                for (k in -2..2) {
                    win[k + 2] = chroma[((f + k).coerceIn(0, frames - 1)) * BINS + b]
                }
                win.sort()
                out[f * BINS + b] = win[2] // median of 5
            }
        }
        return out
    }

    fun findBestMatchMultiProbe(
        mixChroma: FloatArray,
        previewChroma: FloatArray,
        hopSec: Float = ChromaExtractor.HOP_SEC
    ): ProbeResult? {
        val smoothed      = smoothChroma(mixChroma)
        val mixFrames     = smoothed.size / BINS
        val previewFrames = previewChroma.size / BINS

        // Build probe start offsets: beginning, 1/3, 2/3 of preview (skip first 15f)
        val spread = (previewFrames - SKIP_FRAMES - PROBE_FRAMES).coerceAtLeast(0)
        val rawOffsets = if (spread > 0) listOf(
            SKIP_FRAMES,
            SKIP_FRAMES + spread / 3,
            SKIP_FRAMES + spread * 2 / 3
        ) else listOf(SKIP_FRAMES)

        // Keep only offsets where the probe fits inside the preview
        val probeOffsets = rawOffsets.filter { it + PROBE_FRAMES <= previewFrames }
        if (probeOffsets.isEmpty()) {
            Log.w(TAG, "Preview too short for multi-probe ($previewFrames frames)")
            return null
        }

        data class Hit(val adjustedPos: Int, val score: Float)
        val hits = mutableListOf<Hit>()

        for (pOffset in probeOffsets) {
            val maxPos = mixFrames - PROBE_FRAMES
            if (maxPos <= 0) continue

            // Coarse pass (on smoothed mix)
            var bestScore = -1f
            var bestPos   = 0
            var pos = 0
            while (pos <= maxPos) {
                val s = score(smoothed, pos, previewChroma, pOffset, PROBE_FRAMES)
                if (s > bestScore) { bestScore = s; bestPos = pos }
                pos += STRIDE
            }
            // Refine pass
            val rFrom = maxOf(0, bestPos - REFINE)
            val rTo   = minOf(maxPos, bestPos + REFINE)
            for (p in rFrom..rTo) {
                val s = score(smoothed, p, previewChroma, pOffset, PROBE_FRAMES)
                if (s > bestScore) { bestScore = s; bestPos = p }
            }

            // adjustedPos = mix frame where preview frame-0 would be
            val adjustedPos = bestPos - pOffset
            Log.d(TAG, "  probe@${pOffset}f → mixPos=$bestPos adj=$adjustedPos score=${"%.3f".format(bestScore)}")

            if (bestScore >= MIN_SCORE_PROBE && adjustedPos >= -pOffset) {
                hits.add(Hit(adjustedPos, bestScore))
            }
        }

        if (hits.isEmpty()) return null

        // ── Consensus vote ─────────────────────────────────────────────────────
        // For each hit, find all others within CONSENSUS_FRAMES frames of it.
        var bestGroup = emptyList<Hit>()
        for (anchor in hits) {
            val group = hits.filter { abs(it.adjustedPos - anchor.adjustedPos) <= CONSENSUS_FRAMES }
            if (group.size > bestGroup.size ||
                (group.size == bestGroup.size &&
                    group.sumOf { it.score.toDouble() } > bestGroup.sumOf { it.score.toDouble() })) {
                bestGroup = group
            }
        }

        // Accept if ≥2 probes agree, OR single probe with high confidence score
        val minVotes = if (probeOffsets.size >= 2) 2 else 1
        val highConfidenceSingle = bestGroup.size == 1 && bestGroup[0].score >= 0.70f

        if (bestGroup.size < minVotes && !highConfidenceSingle) {
            Log.d(TAG, "  → no consensus (${hits.size} hits, best group=${bestGroup.size}/${probeOffsets.size})")
            return null
        }

        // Weighted-average position from agreeing probes
        val totalScore    = bestGroup.sumOf { it.score.toDouble() }.toFloat()
        val consensusPos  = (bestGroup.sumOf { it.adjustedPos * it.score.toDouble() } / totalScore).toFloat()
        val avgScore      = totalScore / bestGroup.size
        val confidence    = bestGroup.size.toFloat() / probeOffsets.size.toFloat()
        val ts            = consensusPos * hopSec

        Log.d(TAG, "  → consensus: ${bestGroup.size}/${probeOffsets.size} probes agree, " +
            "avgScore=${"%.3f".format(avgScore)}, ts=${ts.toInt()}s, confidence=${"%.0f".format(confidence * 100)}%")

        return ProbeResult(timestampSec = ts, avgScore = avgScore, confidence = confidence)
    }

    // ── Legacy single-probe (kept for reference / fallback) ───────────────────

    fun findBestMatch(
        mixChroma: FloatArray,
        previewChroma: FloatArray,
        hopSec: Float = ChromaExtractor.HOP_SEC
    ): Float? {
        val mixFrames     = mixChroma.size / BINS
        val previewFrames = previewChroma.size / BINS

        val pStart  = minOf(SKIP_FRAMES, previewFrames / 4)
        val pEnd    = minOf(pStart + MATCH_FRAMES, previewFrames)
        val qLen    = pEnd - pStart
        if (qLen < 10) return null

        val maxPos = mixFrames - qLen
        if (maxPos <= 0) return null

        var bestScore = -1f
        var bestPos   = 0
        var pos = 0
        while (pos <= maxPos) {
            val s = score(mixChroma, pos, previewChroma, pStart, qLen)
            if (s > bestScore) { bestScore = s; bestPos = pos }
            pos += STRIDE
        }
        val rFrom = maxOf(0, bestPos - REFINE)
        val rTo   = minOf(maxPos, bestPos + REFINE)
        for (p in rFrom..rTo) {
            val s = score(mixChroma, p, previewChroma, pStart, qLen)
            if (s > bestScore) { bestScore = s; bestPos = p }
        }

        val ts = bestPos * hopSec
        Log.d(TAG, "bestPos=$bestPos score=${"%.3f".format(bestScore)} ts=${ts.toInt()}s")
        if (bestScore < MIN_SCORE) return null
        return ts
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    /**
     * Average cosine similarity between mix frames [mixStart .. mixStart+len]
     * and preview frames [previewStart .. previewStart+len].
     */
    private fun score(
        mix: FloatArray, mixStart: Int,
        preview: FloatArray, previewStart: Int,
        len: Int
    ): Float {
        var total = 0f
        for (f in 0 until len) {
            val mBase = (mixStart + f) * BINS
            val pBase = (previewStart + f) * BINS

            var dot = 0f
            var mSq = 0f
            for (c in 0 until BINS) {
                val m = mix[mBase + c]
                val p = preview[pBase + c]
                dot += m * p
                mSq += m * m
            }
            val mNorm = sqrt(mSq)
            total += if (mNorm > 1e-8f) dot / mNorm else 0f
        }
        return total / len
    }
}
