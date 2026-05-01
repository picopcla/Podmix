package com.podmix.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Analyse audio on-device pour détecter les transitions entre morceaux.
 *
 * Stratégie : échantillonnage stratégique via MediaExtractor (seek + decode 8s)
 * toutes les 30 secondes, sans télécharger le fichier entier.
 * Pour un mix de 2h → ~240 positions × ~8s = ~30 min d'audio analysé total.
 *
 * Détection : chute de RMS sous 55 % de la médiane = transition.
 * Contrainte : durée minimale entre deux transitions = 90s (1,5 min).
 */
@Singleton
class OnDeviceAudioSplitService @Inject constructor() {

    private val TAG = "OnDeviceAudioSplit"

    private val SAMPLE_WINDOW_SEC = 8f          // fenêtre analysée par position
    private val SAMPLE_STEP_SEC = 30            // interval entre deux positions
    private val MIN_TRACK_DURATION_SEC = 90     // durée minimale d'un morceau
    private val ENERGY_DROP_RATIO = 0.55f       // seuil de chute = transition

    /**
     * Analyse le flux audio et retourne une liste de [ParsedTrack] avec timestamps.
     *
     * @param audioUrl  URL directe HTTP/HTTPS ou chemin local (file://)
     * @param durationSec durée totale connue (0 = détection auto via MediaExtractor)
     * @param onProgress callback (0–100, message)
     */
    suspend fun analyze(
        audioUrl: String,
        durationSec: Int,
        onProgress: (Int, String) -> Unit
    ): List<ParsedTrack> = withContext(Dispatchers.IO) {

        Log.i(TAG, "▶ Début analyse on-device: url=$audioUrl durée=${durationSec}s")
        onProgress(2, "Ouverture du flux audio...")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioUrl)

            // Sélectionner la première piste audio
            val audioTrackIdx = (0 until extractor.trackCount).firstOrNull { idx ->
                extractor.getTrackFormat(idx)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                Log.w(TAG, "Aucune piste audio dans le flux")
                return@withContext emptyList()
            }

            extractor.selectTrack(audioTrackIdx)
            val format = extractor.getTrackFormat(audioTrackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()

            // Durée effective
            val effectiveDurationSec = when {
                durationSec > 0 -> durationSec
                format.containsKey(MediaFormat.KEY_DURATION) ->
                    (format.getLong(MediaFormat.KEY_DURATION) / 1_000_000L).toInt()
                else -> 0
            }
            if (effectiveDurationSec <= 60) {
                Log.w(TAG, "Durée trop courte ou inconnue ($effectiveDurationSec s), abandon")
                return@withContext emptyList()
            }

            onProgress(5, "Format: $mime — ${effectiveDurationSec / 60}min ${effectiveDurationSec % 60}s")

            // Positions d'échantillonnage
            val positions = (0 until effectiveDurationSec step SAMPLE_STEP_SEC)
                .filter { it <= effectiveDurationSec - SAMPLE_WINDOW_SEC.toInt() }
            if (positions.isEmpty()) return@withContext emptyList()

            // Créer le codec
            val codec = try {
                MediaCodec.createDecoderByType(mime).also { c ->
                    c.configure(format, null, null, 0)
                    c.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Impossible de créer le codec $mime: ${e.message}")
                return@withContext emptyList()
            }

            val energyMap = linkedMapOf<Int, Float>() // position(s) → RMS
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                for ((idx, positionSec) in positions.withIndex()) {
                    if (!isActive) break

                    val pct = 5 + (idx * 88 / positions.size)
                    onProgress(pct, "Analyse position ${positionSec / 60}:${"%02d".format(positionSec % 60)}...")

                    // Seek + flush
                    extractor.seekTo(positionSec * 1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()

                    val endUs = (positionSec + SAMPLE_WINDOW_SEC) * 1_000_000L
                    val energySamples = mutableListOf<Float>()
                    var inputEOS = false
                    var outputDone = false
                    var loopLimit = 600

                    while (!outputDone && loopLimit-- > 0) {
                        if (!inputEOS) {
                            val inIdx = codec.dequeueInputBuffer(4_000)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val sz = extractor.readSampleData(inBuf, 0)
                                val ts = extractor.sampleTime
                                if (sz < 0 || ts > endUs) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEOS = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sz, ts, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4_000)
                        when {
                            outIdx >= 0 -> {
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                outBuf.order(ByteOrder.LITTLE_ENDIAN).limit(bufferInfo.size)
                                while (outBuf.remaining() >= 2) {
                                    val s = outBuf.short.toFloat() / 32768f
                                    energySamples.add(s * s)
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                    outputDone = true
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (inputEOS) break
                            }
                        }
                    }

                    if (energySamples.isNotEmpty()) {
                        val rms = sqrt(energySamples.average().toFloat())
                        energyMap[positionSec] = rms
                        Log.d(TAG, "  @${positionSec}s → RMS=${"%.5f".format(rms)} (${energySamples.size} samples)")
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            onProgress(96, "Détection des transitions...")

            val result = detectTransitions(energyMap, effectiveDurationSec)
            Log.i(TAG, "✅ ${result.size} morceaux détectés")
            onProgress(100, "${result.size} morceaux détectés on-device")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Erreur analyse on-device: ${e.message}", e)
            emptyList()
        } finally {
            extractor.release()
        }
    }

    /**
     * Identifie les chutes d'énergie significatives dans la map position→RMS.
     * Retourne une liste de [ParsedTrack] avec les timestamps de chaque morceau.
     */
    private fun detectTransitions(
        energyMap: Map<Int, Float>,
        durationSec: Int
    ): List<ParsedTrack> {
        if (energyMap.size < 5) return emptyList()

        val sorted = energyMap.entries.sortedBy { it.key }
        val values = sorted.map { it.value }

        // Médiane globale
        val median = values.sorted()[values.size / 2]
        val threshold = median * ENERGY_DROP_RATIO

        Log.d(TAG, "Médiane RMS=${"%.5f".format(median)} seuil=${"%.5f".format(threshold)}")

        // Candidats: positions dont l'énergie passe sous le seuil
        val transitionCandidates = mutableListOf<Int>()
        var lastAdded = -MIN_TRACK_DURATION_SEC

        for ((pos, rms) in sorted) {
            if (pos == 0) continue // Le début n'est pas une transition
            if (rms < threshold && pos - lastAdded >= MIN_TRACK_DURATION_SEC) {
                transitionCandidates.add(pos)
                lastAdded = pos
                Log.d(TAG, "  Transition @${pos}s (RMS=${"%.5f".format(rms)})")
            }
        }

        // Toujours inclure le début (position 0 = morceau 1)
        val trackPositions = (listOf(0) + transitionCandidates).sorted()

        return trackPositions.mapIndexed { idx, startSec ->
            ParsedTrack(
                artist = "",
                title = "Morceau ${idx + 1}",
                startTimeSec = startSec.toFloat()
            )
        }
    }
}
