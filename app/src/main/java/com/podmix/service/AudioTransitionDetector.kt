package com.podmix.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.TrackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

@Singleton
class AudioTransitionDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val trackDao: TrackDao
) {
    companion object {
        private const val TAG = "TransitionDetector"
        private const val WINDOW_SEC = 2
        private const val MIN_TRACK_GAP_SEC = 45
        private const val ENERGY_DROP_RATIO = 0.45f
        private const val SMOOTH_RADIUS = 4
    }

    // Progression par épisodeId (0-100). Collecté par les ViewModels.
    private val _progress = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val progress: StateFlow<Map<Int, Int>> = _progress.asStateFlow()

    private fun setProgress(episodeId: Int, pct: Int) {
        _progress.value = _progress.value + (episodeId to pct)
    }

    private fun clearProgress(episodeId: Int) {
        _progress.value = _progress.value - episodeId
    }

    /**
     * Affine les timestamps TTE d'un épisode en détectant les transitions audio.
     * L'épisode doit être téléchargé localement (localAudioPath != null).
     * Les titres des tracks ne changent pas — seuls les startTimeSec sont mis à jour.
     */
    suspend fun refineTracklist(episodeId: Int) = withContext(Dispatchers.IO) {
        val episode = episodeDao.getById(episodeId) ?: return@withContext
        val localPath = episode.localAudioPath ?: run {
            Log.w(TAG, "Episode $episodeId has no local audio, skip refinement")
            return@withContext
        }

        val tracks = trackDao.getByEpisodeId(episodeId)
        if (tracks.isEmpty()) {
            Log.w(TAG, "Episode $episodeId has no tracks to refine")
            return@withContext
        }

        Log.i(TAG, "Starting refinement for '${episode.title}' — ${tracks.size} tracks")
        episodeDao.updateRefinementStatus(episodeId, "refining")
        setProgress(episodeId, 0)

        try {
            val energyWindows = extractEnergyWindows(localPath, episodeId, episode.durationSeconds)
            if (energyWindows.isEmpty()) {
                Log.w(TAG, "No energy windows extracted for episode $episodeId")
                episodeDao.updateRefinementStatus(episodeId, "done")
                clearProgress(episodeId)
                return@withContext
            }

            setProgress(episodeId, 90)
            val transitionTimestamps = findTransitions(energyWindows, tracks.size)
            Log.i(TAG, "Detected ${transitionTimestamps.size} transitions for ${tracks.size} tracks")

            val sorted = tracks.sortedBy { it.position }
            sorted.forEachIndexed { idx, track ->
                val newStart = transitionTimestamps.getOrNull(idx) ?: track.startTimeSec
                val newEnd = transitionTimestamps.getOrNull(idx + 1)
                trackDao.update(track.copy(startTimeSec = newStart, endTimeSec = newEnd, source = "audio_refined"))
            }

            setProgress(episodeId, 100)
            episodeDao.updateRefinementStatus(episodeId, "done")
            clearProgress(episodeId)
            Log.i(TAG, "Refinement done for episode $episodeId")

        } catch (e: Exception) {
            Log.e(TAG, "Refinement failed for episode $episodeId: ${e.message}")
            episodeDao.updateRefinementStatus(episodeId, "pending")
            clearProgress(episodeId)
        }
    }

    /**
     * Décode l'audio et retourne l'énergie RMS par fenêtre de WINDOW_SEC secondes.
     * Calcule le RMS à la volée (O(1) mémoire) — pas d'accumulation de shorts boxés.
     * Émet la progression 0-89% via setProgress().
     */
    private fun extractEnergyWindows(path: String, episodeId: Int, durationSec: Int): List<Float> {
        val totalWindows = if (durationSec > 0) durationSec / WINDOW_SEC else 0
        val extractor = MediaExtractor()
        val energyWindows = mutableListOf<Float>()

        try {
            extractor.setDataSource(path)

            val audioTrackIdx = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return emptyList()

            extractor.selectTrack(audioTrackIdx)
            val format = extractor.getTrackFormat(audioTrackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val windowSamples = sampleRate * WINDOW_SEC * channelCount

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eos = false

            // Rolling accumulator — O(1) mémoire, pas de boxing
            var sumSq = 0.0
            var samplesInWindow = 0

            while (!eos) {
                // Envoyer données compressées
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // Lire PCM décodé — traiter sample par sample sans copier en liste
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val shortBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                    while (shortBuf.hasRemaining()) {
                        val s = shortBuf.get().toFloat() / 32768f
                        sumSq += s * s
                        samplesInWindow++

                        if (samplesInWindow >= windowSamples) {
                            energyWindows.add(sqrt(sumSq / samplesInWindow).toFloat())
                            sumSq = 0.0
                            samplesInWindow = 0

                            if (totalWindows > 0) {
                                val pct = ((energyWindows.size.toFloat() / totalWindows) * 89f)
                                    .toInt().coerceIn(0, 89)
                                setProgress(episodeId, pct)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()

        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction error: ${e.message}")
        } finally {
            extractor.release()
        }

        return energyWindows
    }

    /**
     * Trouve les points de transition dans la courbe d'énergie.
     * Retourne une liste de timestamps en secondes, commençant toujours par 0f.
     * Si moins de transitions que de tracks attendues, complète avec répartition égale.
     */
    private fun findTransitions(energyWindows: List<Float>, expectedTracks: Int): List<Float> {
        if (energyWindows.size < 2) return listOf(0f)

        // Lissage par moyenne glissante
        val smoothed = energyWindows.indices.map { i ->
            val from = max(0, i - SMOOTH_RADIUS)
            val to = minOf(energyWindows.size - 1, i + SMOOTH_RADIUS)
            energyWindows.subList(from, to + 1).average().toFloat()
        }

        val minGapWindows = MIN_TRACK_GAP_SEC / WINDOW_SEC
        val transitions = mutableListOf(0f)
        var lastWindowIdx = 0

        for (i in 1 until smoothed.size - 1) {
            if (i - lastWindowIdx < minGapWindows) continue

            // Contexte local pour comparer
            val localFrom = max(0, i - 20)
            val localTo = minOf(smoothed.size - 1, i + 20)
            val localMax = smoothed.subList(localFrom, localTo + 1).max()!!
            if (localMax <= 0f) continue

            val dropRatio = 1f - (smoothed[i] / localMax)
            val isLocalMin = smoothed[i] <= smoothed[i - 1] && smoothed[i] <= smoothed[i + 1]

            if (dropRatio >= ENERGY_DROP_RATIO && isLocalMin) {
                transitions.add(i * WINDOW_SEC.toFloat())
                lastWindowIdx = i
                Log.d(TAG, "Transition @ ${i * WINDOW_SEC}s (drop=${(dropRatio * 100).toInt()}%)")
            }
        }

        // Si pas assez de transitions détectées → compléter avec répartition égale
        val totalSec = energyWindows.size * WINDOW_SEC.toFloat()
        if (transitions.size < expectedTracks) {
            Log.w(TAG, "Only ${transitions.size} transitions for $expectedTracks tracks — filling with equal split")
            val equalStep = totalSec / expectedTracks
            return (0 until expectedTracks).map { it * equalStep }
        }

        // Si trop de transitions → garder les N plus marquées (sauf 0f)
        return if (transitions.size > expectedTracks) {
            listOf(0f) + transitions.drop(1).take(expectedTracks - 1)
        } else {
            transitions
        }
    }
}
