package com.podmix.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Converts audio to chroma feature sequences (12-dim pitch-class profiles).
 *
 * Chroma is robust to EQ, compression, and timbre variations — ideal for
 * matching DJ mix segments against Deezer preview references.
 *
 * Frame rate: HOP_SIZE / SAMPLE_RATE = 256 / 8000 = 32ms per frame.
 */
@Singleton
class ChromaExtractor @Inject constructor() {

    companion object {
        private const val TAG = "ChromaExtractor"
        const val SAMPLE_RATE = 8000        // target Hz after downsampling
        const val FRAME_SIZE  = 512         // FFT size (power of 2)
        const val HOP_SIZE    = 256         // frames between successive FFT windows
        const val CHROMA_BINS = 12
        const val HOP_SEC     = HOP_SIZE.toFloat() / SAMPLE_RATE   // 0.032 s/frame

        private const val MIN_FREQ = 65.0   // C2 ≈ 65 Hz
        private const val MAX_FREQ = 2100.0 // C7 ≈ 2093 Hz
    }

    // Precomputed: for each FFT bin, which chroma class (0-11) or -1 to ignore
    private val binToChroma = IntArray(FRAME_SIZE / 2 + 1) { bin ->
        val freq = bin.toDouble() * SAMPLE_RATE / FRAME_SIZE
        if (freq < MIN_FREQ || freq > MAX_FREQ) -1
        else {
            val midi = 12.0 * log2(freq / 440.0) + 69.0
            ((midi.roundToInt() % 12) + 12) % 12
        }
    }

    // Precomputed Hann window
    private val hannWindow = FloatArray(FRAME_SIZE) { i ->
        0.5f * (1f - cos(2.0 * PI * i / FRAME_SIZE).toFloat())
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Extract chroma from a window of an audio file.
     * Seeks to [startSec] and decodes up to [durationSec] seconds.
     * Much faster than full-file extraction for large files.
     * Returns FloatArray[numFrames × CHROMA_BINS], or null on error.
     */
    fun extractWindowFromFile(
        path: String,
        startSec: Float,
        durationSec: Float,
        onProgress: ((Int) -> Unit)? = null
    ): FloatArray? {
        val extractor = MediaExtractor()
        val chromaOut = ArrayList<Float>(((durationSec / HOP_SEC) * CHROMA_BINS).toInt() + CHROMA_BINS)
        try {
            extractor.setDataSource(path)
            val audioIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioIdx)
            val format   = extractor.getTrackFormat(audioIdx)
            val mime     = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Seek to window start
            val seekUs = (startSec * 1_000_000L).toLong().coerceAtLeast(0L)
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val maxSamples = (durationSec * srcRate * channels).toLong()
            var samplesRead = 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eos  = false

            val srcStep  = srcRate.toDouble() / SAMPLE_RATE
            var inCount  = 0L; var outCount = 0L
            var pendSum  = 0.0; var pendN = 0

            val frameBuf = FloatArray(FRAME_SIZE)
            var frameFill = 0
            val overlap  = FRAME_SIZE - HOP_SIZE
            val re = FloatArray(FRAME_SIZE); val im = FloatArray(FRAME_SIZE)

            while (!eos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf  = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0 || samplesRead >= maxSamples) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val sBuf = codec.getOutputBuffer(outIdx)!!
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sBuf.remaining() >= channels) {
                        var mono = 0.0
                        repeat(channels) { mono += sBuf.get().toDouble() / 32768.0 }
                        mono /= channels
                        samplesRead += channels
                        inCount++; pendSum += mono; pendN++
                        if (inCount.toDouble() >= (outCount + 1) * srcStep) {
                            val s = (pendSum / pendN).toFloat()
                            pendSum = 0.0; pendN = 0; outCount++
                            if (frameFill < FRAME_SIZE) frameBuf[frameFill++] = s
                            if (frameFill == FRAME_SIZE) {
                                computeChromaFrame(frameBuf, re, im, chromaOut)
                                System.arraycopy(frameBuf, HOP_SIZE, frameBuf, 0, overlap)
                                frameFill = overlap
                                onProgress?.invoke(
                                    (samplesRead * 100 / maxSamples).toInt().coerceIn(0, 99)
                                )
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop(); codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "extractWindowFromFile: ${e.message}", e)
            return null
        } finally {
            extractor.release()
        }
        return chromaOut.toFloatArray()
    }

    /**
     * Extract chroma from a local audio file (streaming — constant memory).
     * Returns FloatArray[numFrames × CHROMA_BINS], row-major.
     * Returns null on decode error.
     * [onProgress] called with 0-100 as extraction progresses.
     */
    fun extractFromFile(path: String, onProgress: ((Int) -> Unit)? = null): FloatArray? {
        val extractor = MediaExtractor()
        val chromaOut  = ArrayList<Float>(512 * CHROMA_BINS)
        try {
            extractor.setDataSource(path)
            val audioIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioIdx)
            val format   = extractor.getTrackFormat(audioIdx)
            val mime     = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            // Estimate total duration for progress reporting (may be 0 if not in header)
            val durationUs = try {
                extractor.getTrackFormat(audioIdx).getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) { 0L }
            Log.i(TAG, "extractFromFile: rate=${srcRate}Hz ch=$channels duration=${durationUs/1_000_000}s path=$path")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eos  = false
            var lastProgressPct = -1

            // Fractional resampler state
            val srcStep   = srcRate.toDouble() / SAMPLE_RATE
            var inCount   = 0L   // total mono input samples processed
            var outCount  = 0L   // total output samples emitted
            var pendSum   = 0.0
            var pendN     = 0

            // Frame buffer: accumulates resampled mono samples for one FFT frame
            val frameBuf = FloatArray(FRAME_SIZE)
            var frameFill = 0
            val overlap   = FRAME_SIZE - HOP_SIZE

            val re = FloatArray(FRAME_SIZE)
            val im = FloatArray(FRAME_SIZE)

            while (!eos) {
                // Feed compressed data
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf  = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Read decoded PCM
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf  = codec.getOutputBuffer(outIdx)!!
                    val sBuf    = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                    // Progress based on presentation timestamp
                    if (durationUs > 0 && info.presentationTimeUs > 0) {
                        val pct = ((info.presentationTimeUs.toDouble() / durationUs) * 100).toInt().coerceIn(0, 99)
                        if (pct != lastProgressPct) {
                            lastProgressPct = pct
                            onProgress?.invoke(pct)
                            if (pct % 10 == 0) Log.d(TAG, "extractFromFile: $pct%")
                        }
                    }

                    while (sBuf.remaining() >= channels) {
                        // Mix to mono
                        var mono = 0.0
                        repeat(channels) { mono += sBuf.get().toDouble() / 32768.0 }
                        mono /= channels

                        // Fractional resampling: emit output when enough input consumed
                        inCount++
                        pendSum += mono
                        pendN++
                        if (inCount.toDouble() >= (outCount + 1) * srcStep) {
                            val s = (pendSum / pendN).toFloat()
                            pendSum = 0.0; pendN = 0
                            outCount++

                            // Accumulate into FFT frame
                            if (frameFill < FRAME_SIZE) frameBuf[frameFill++] = s

                            if (frameFill == FRAME_SIZE) {
                                computeChromaFrame(frameBuf, re, im, chromaOut)
                                // Slide: keep overlap samples
                                System.arraycopy(frameBuf, HOP_SIZE, frameBuf, 0, overlap)
                                frameFill = overlap
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "extractFromFile '$path': ${e.message}", e)
            return null
        } finally {
            extractor.release()
        }
        Log.i(TAG, "extractFromFile: ${chromaOut.size / CHROMA_BINS} frames from $path")
        return chromaOut.toFloatArray()
    }

    /**
     * Extract chroma from raw PCM bytes (8 kHz mono 16-bit LE).
     * Used for short clips (Deezer previews already decoded).
     */
    fun extractFromPcm(pcmBytes: ByteArray): FloatArray {
        val n     = pcmBytes.size / 2
        val sBuf  = java.nio.ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(n) { sBuf.get().toFloat() / 32768f }

        val out = ArrayList<Float>(n / HOP_SIZE * CHROMA_BINS)
        val re  = FloatArray(FRAME_SIZE)
        val im  = FloatArray(FRAME_SIZE)
        var off = 0
        while (off + FRAME_SIZE <= n) {
            computeChromaFrame(samples, off, re, im, out)
            off += HOP_SIZE
        }
        return out.toFloatArray()
    }

    /**
     * Decode audio file → 8 kHz mono 16-bit LE PCM bytes.
     * Only for short clips (previews ≤ 60 s). Use extractFromFile for long audio.
     */
    fun decodeToPcmMono8k(path: String): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val audioIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioIdx)
            val format   = extractor.getTrackFormat(audioIdx)
            val mime     = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info   = MediaCodec.BufferInfo()
            var eos    = false
            val output = java.io.ByteArrayOutputStream(srcRate * 30 / 4)  // ~30s estimate

            val srcStep  = srcRate.toDouble() / SAMPLE_RATE
            var inCount  = 0L
            var outCount = 0L
            var pendSum  = 0.0
            var pendN    = 0

            while (!eos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf  = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf  = codec.getOutputBuffer(outIdx)!!
                    val sBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sBuf.remaining() >= channels) {
                        var mono = 0.0
                        repeat(channels) { mono += sBuf.get().toDouble() / 32768.0 }
                        mono /= channels
                        inCount++
                        pendSum += mono
                        pendN++
                        if (inCount.toDouble() >= (outCount + 1) * srcStep) {
                            val s = ((pendSum / pendN) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                            output.write(s.toInt() and 0xFF)
                            output.write((s.toInt() shr 8) and 0xFF)
                            pendSum = 0.0; pendN = 0
                            outCount++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop()
            codec.release()
            return output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "decodeToPcmMono8k: ${e.message}")
            return null
        } finally {
            extractor.release()
        }
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun computeChromaFrame(buf: FloatArray, re: FloatArray, im: FloatArray, out: ArrayList<Float>) {
        for (i in 0 until FRAME_SIZE) { re[i] = buf[i] * hannWindow[i]; im[i] = 0f }
        fft(re, im)
        appendChroma(re, im, out)
    }

    private fun computeChromaFrame(src: FloatArray, offset: Int, re: FloatArray, im: FloatArray, out: ArrayList<Float>) {
        for (i in 0 until FRAME_SIZE) { re[i] = src[offset + i] * hannWindow[i]; im[i] = 0f }
        fft(re, im)
        appendChroma(re, im, out)
    }

    private fun appendChroma(re: FloatArray, im: FloatArray, out: ArrayList<Float>) {
        val chroma = FloatArray(CHROMA_BINS)
        for (bin in 1..FRAME_SIZE / 2) {
            val c = binToChroma[bin]
            if (c >= 0) chroma[c] += re[bin] * re[bin] + im[bin] * im[bin]
        }
        val norm = sqrt(chroma.map { it * it }.sum()).toFloat()
        if (norm > 1e-8f) {
            for (v in chroma) out.add(v / norm)
        } else {
            repeat(CHROMA_BINS) { out.add(0f) }
        }
    }

    /** In-place Cooley-Tukey radix-2 DIT FFT. Length must be a power of 2. */
    internal fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var s = 0
            while (s < n) {
                var cRe = 1f; var cIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[s + k];              val uIm = im[s + k]
                    val vRe = re[s + k + len/2] * cRe - im[s + k + len/2] * cIm
                    val vIm = re[s + k + len/2] * cIm + im[s + k + len/2] * cRe
                    re[s + k]         = uRe + vRe;   im[s + k]         = uIm + vIm
                    re[s + k + len/2] = uRe - vRe;   im[s + k + len/2] = uIm - vIm
                    val nr = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe; cRe = nr
                }
                s += len
            }
            len = len shl 1
        }
    }
}
