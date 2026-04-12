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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
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
    private val episodeDao: EpisodeDao
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
            val streamUrl = resolveUrl(episode)
                ?: throw Exception("Impossible de résoudre l'URL audio")
            val file = getOutputFile(episode.id)
            Log.i("Download", "downloadSuspend → ${file.absolutePath}")
            downloadToFile(streamUrl, file) { progress ->
                onProgress(progress)
                setState(episode.id, DownloadState.Downloading(progress))
            }
            episodeDao.updateLocalAudioPath(episode.id, file.absolutePath)
            setState(episode.id, DownloadState.Downloaded(file.absolutePath))
            Log.i("Download", "Done: ${file.absolutePath} (${file.length() / 1_048_576}MB)")
            true
        } catch (e: Exception) {
            Log.e("Download", "Error for episode ${episode.id}: ${e.message}")
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

    private suspend fun downloadToFile(url: String, file: File, onProgress: (Float) -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = okhttp3.Request.Builder().url(url).build()
        okHttpClient.newBuilder()
            .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Réponse vide")
                val contentLength = body.contentLength()

                file.parentFile?.mkdirs()
                var downloaded = 0L

                file.outputStream().buffered().use { out ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(32 * 1024)
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
