package com.podmix.service

import android.content.Context
import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.domain.model.Episode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState() // 0f..1f
    data class Downloaded(val path: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Singleton
class EpisodeDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val mixcloudStreamResolver: MixcloudStreamResolver,
    private val okHttpClient: OkHttpClient,
    private val episodeDao: EpisodeDao,
    private val audioTransitionDetector: AudioTransitionDetector,
    private val chromaTimestampRefiner: ChromaTimestampRefiner
) {
    private val _states = MutableStateFlow<Map<Int, DownloadState>>(emptyMap())
    val states: StateFlow<Map<Int, DownloadState>> = _states

    private val activeJobs = mutableMapOf<Int, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getState(episodeId: Int): DownloadState = _states.value[episodeId] ?: DownloadState.Idle

    fun initState(episode: Episode) {
        val path = episode.localAudioPath
        if (path != null && File(path).exists()) {
            setState(episode.id, DownloadState.Downloaded(path))
        }
        // else stays Idle
    }

    /**
     * Suspending version — call from a coroutine to wait for completion.
     * Returns true on success, false on failure.
     */
    suspend fun downloadSuspend(
        episode: Episode,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (activeJobs[episode.id]?.isActive == true) return false

        val path = episode.localAudioPath
        if (path != null && java.io.File(path).exists()) {
            setState(episode.id, DownloadState.Downloaded(path))
            return true
        }

        return try {
            setState(episode.id, DownloadState.Downloading(0f))
            com.podmix.AppLogger.download("START ep#${episode.id}", "'${episode.title.take(50)}'")
            val streamUrl = resolveUrl(episode)
                ?: throw Exception("Impossible de résoudre l'URL audio")
            val file = getOutputFile(episode.id)
            Log.i("Download", "downloadSuspend → ${file.absolutePath}")
            val t0 = System.currentTimeMillis()
            downloadToFile(streamUrl, file) { progress ->
                onProgress(progress)
                setState(episode.id, DownloadState.Downloading(progress))
            }
            episodeDao.updateLocalAudioPath(episode.id, file.absolutePath)
            setState(episode.id, DownloadState.Downloaded(file.absolutePath))
            val mb = file.length() / 1_048_576
            val secs = (System.currentTimeMillis() - t0) / 1000
            Log.i("Download", "Done: ${file.absolutePath} (${mb}MB)")
            com.podmix.AppLogger.download("DONE ep#${episode.id} ${mb}MB ${secs}s", file.absolutePath)
            // Auto-affinage TL si statut pending
            triggerRefinementIfPending(episode.id, file.absolutePath)
            true
        } catch (e: Exception) {
            Log.e("Download", "Error for episode ${episode.id}: ${e.message}")
            com.podmix.AppLogger.err("DOWNLOAD", "FAIL ep#${episode.id}", e.message ?: "")
            setState(episode.id, DownloadState.Error(e.message ?: "Erreur inconnue"))
            getOutputFile(episode.id).delete()
            false
        }
    }

    fun startDownload(episode: Episode) {
        if (activeJobs[episode.id]?.isActive == true) return

        // Already downloaded and file present
        val path = episode.localAudioPath
        if (path != null && File(path).exists()) {
            setState(episode.id, DownloadState.Downloaded(path))
            return
        }

        activeJobs[episode.id] = scope.launch {
            try {
                setState(episode.id, DownloadState.Downloading(0f))
                Log.i("Download", "Resolving URL for episode ${episode.id}…")

                val streamUrl = resolveUrl(episode)
                    ?: throw Exception("Impossible de résoudre l'URL audio")

                val file = getOutputFile(episode.id)
                Log.i("Download", "Downloading to ${file.absolutePath}")

                downloadToFile(streamUrl, file) { progress ->
                    setState(episode.id, DownloadState.Downloading(progress))
                }

                episodeDao.updateLocalAudioPath(episode.id, file.absolutePath)
                setState(episode.id, DownloadState.Downloaded(file.absolutePath))
                Log.i("Download", "Done: ${file.absolutePath} (${file.length() / 1_048_576}MB)")
                // Auto-affinage TL si statut pending
                triggerRefinementIfPending(episode.id, file.absolutePath)

            } catch (e: kotlinx.coroutines.CancellationException) {
                setState(episode.id, DownloadState.Idle)
                getOutputFile(episode.id).delete()
                throw e
            } catch (e: Exception) {
                Log.e("Download", "Error for episode ${episode.id}: ${e.message}")
                setState(episode.id, DownloadState.Error(e.message ?: "Erreur inconnue"))
                getOutputFile(episode.id).delete()
            } finally {
                activeJobs.remove(episode.id)
            }
        }
    }

    fun cancelDownload(episodeId: Int) {
        activeJobs[episodeId]?.cancel()
        activeJobs.remove(episodeId)
        getOutputFile(episodeId).delete()
        setState(episodeId, DownloadState.Idle)
    }

    fun deleteDownload(episodeId: Int) {
        scope.launch {
            getOutputFile(episodeId).delete()
            episodeDao.clearLocalAudioPath(episodeId)
            setState(episodeId, DownloadState.Idle)
        }
    }

    /**
     * Déclenche l'affinage des timestamps si l'épisode a des tracks TTE (status "pending").
     * Appelé automatiquement après chaque téléchargement réussi.
     */
    private fun triggerRefinementIfPending(episodeId: Int, localPath: String) {
        scope.launch {
            val ep = episodeDao.getById(episodeId) ?: return@launch
            when {
                ep.trackRefinementStatus == "pending" && ep.episodeType != "liveset" -> {
                    Log.i("Download", "Audio refinement for episode $episodeId")
                    audioTransitionDetector.refineTracklist(episodeId)
                }
                ep.trackRefinementStatus == "chroma_pending" -> {
                    Log.i("Download", "Chroma refinement for liveset episode $episodeId")
                    chromaTimestampRefiner.refine(episodeId)
                }
            }
        }
    }

    /**
     * À appeler au démarrage de l'app pour affiner les épisodes déjà téléchargés
     * dont le statut est encore "pending" (ex: TL ajoutée après téléchargement).
     */
    fun checkAndRefinePendingOnStartup() {
        scope.launch {
            val pending = episodeDao.getPendingRefinementWithLocalAudio()
            if (pending.isNotEmpty()) {
                Log.i("Download", "Startup: ${pending.size} episodes pending refinement")
                for (ep in pending) {
                    audioTransitionDetector.refineTracklist(ep.id)
                }
                val chromaPending = episodeDao.getChromaPendingWithLocalAudio()
                if (chromaPending.isNotEmpty()) {
                    Log.i("Download", "Startup: ${chromaPending.size} livesets pending chroma refinement")
                    for (ep in chromaPending) {
                        chromaTimestampRefiner.refine(ep.id)
                    }
                }
            }
        }
    }

    private fun setState(episodeId: Int, state: DownloadState) {
        _states.value = _states.value + (episodeId to state)
    }

    private suspend fun resolveUrl(episode: Episode): String? {
        return when {
            !episode.soundcloudTrackUrl.isNullOrBlank() -> {
                Log.i("Download", "Resolving SoundCloud track: ${episode.soundcloudTrackUrl}")
                youTubeStreamResolver.resolveSoundCloudTrack(episode.soundcloudTrackUrl)
            }
            !episode.mixcloudKey.isNullOrBlank() ->
                mixcloudStreamResolver.resolve(episode.mixcloudKey)
            !episode.youtubeVideoId.isNullOrBlank() ->
                youTubeStreamResolver.resolve(episode.youtubeVideoId)
            episode.audioUrl.isNotBlank() -> episode.audioUrl
            else -> null
        }
    }

    fun getOutputFile(episodeId: Int): File {
        val dir = context.getExternalFilesDir("audio") ?: context.filesDir
        return File(dir, "$episodeId.m4a")
    }

    private suspend fun downloadToFile(url: String, file: File, onProgress: (Float) -> Unit) {
        val isMixcloud  = url.contains("mixcloud.com", ignoreCase = true)
        val isYouTube   = url.contains("googlevideo.com", ignoreCase = true)

        // Extract content length from URL params (YouTube embeds it as clen=NNN)
        val clenFromUrl = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

        // Use parallel chunked download for YouTube (bypasses 1.5× playback-rate throttle)
        // Mixcloud CDN doesn't support partial content reliably — use single stream
        if (isYouTube && clenFromUrl > 0) {
            downloadChunked(url, file, clenFromUrl, onProgress)
            return
        }

        // Single-stream fallback (podcasts RSS, Mixcloud)
        downloadSingleStream(url, file, isMixcloud, onProgress)
    }

    /**
     * Parallel 8-chunk download using HTTP Range requests.
     * YouTube CDN supports this and doesn't throttle per-connection bandwidth
     * the same way it throttles a single progressive download.
     *
     * ~8× faster than single-stream for throttled YouTube audio.
     */
    private suspend fun downloadChunked(
        url: String,
        file: File,
        contentLength: Long,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val CHUNKS = 8
        val chunkSize = (contentLength + CHUNKS - 1) / CHUNKS
        val tmpFiles = (0 until CHUNKS).map { i -> File(file.parent, "${file.name}.part$i") }
        val downloaded = AtomicLong(0)

        Log.i("Download", "Chunked: ${contentLength/1_048_576}MB in $CHUNKS parallel chunks")

        val chunkClient = okHttpClient.newBuilder()
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .build()

        try {
            file.parentFile?.mkdirs()

            (0 until CHUNKS).map { i ->
                val start = i * chunkSize
                val end   = minOf(start + chunkSize - 1, contentLength - 1)
                if (start >= contentLength) return@map async { /* skip */ }

                async {
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$start-$end")
                        .build()

                    chunkClient.newCall(req).execute().use { resp ->
                        if (resp.code !in listOf(200, 206)) {
                            throw Exception("Chunk $i HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw Exception("Empty chunk $i")
                        tmpFiles[i].outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(256 * 1024)
                                var bytes: Int
                                while (input.read(buf).also { bytes = it } != -1) {
                                    out.write(buf, 0, bytes)
                                    val dl = downloaded.addAndGet(bytes.toLong())
                                    onProgress(dl.toFloat() / contentLength)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()

            // Merge chunks in order
            file.outputStream().buffered(512 * 1024).use { out ->
                tmpFiles.forEachIndexed { i, tmp ->
                    if (tmp.exists()) {
                        Log.d("Download", "Merging chunk $i (${tmp.length()/1024}KB)")
                        tmp.inputStream().use { it.copyTo(out, bufferSize = 512 * 1024) }
                        tmp.delete()
                    }
                }
            }
            Log.i("Download", "Chunked merge done: ${file.length()/1_048_576}MB")

        } catch (e: Exception) {
            tmpFiles.forEach { it.delete() }
            throw e
        }
    }

    /** Single-connection streaming download (podcasts, Mixcloud). */
    private suspend fun downloadSingleStream(
        url: String,
        file: File,
        isMixcloud: Boolean,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = okhttp3.Request.Builder().url(url)
        if (isMixcloud) {
            requestBuilder
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .header("Referer", "https://www.mixcloud.com/")
                .header("Origin", "https://www.mixcloud.com")
        }

        okHttpClient.newBuilder()
            .readTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
            .build()
            .newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Réponse vide")
                val contentLength = body.contentLength()

                file.parentFile?.mkdirs()
                var downloaded = 0L

                file.outputStream().buffered(512 * 1024).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(512 * 1024)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            out.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (contentLength > 0) onProgress(downloaded.toFloat() / contentLength)
                        }
                    }
                }
            }
    }
}
