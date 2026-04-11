package com.podmix.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import com.podmix.domain.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class FavoritePlayItem(
    val episodeId: Int,
    val podcastId: Int,
    val startTimeSec: Float,
    val title: String,
    val artist: String
)

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val currentEpisode: Episode? = null,
    val currentPodcast: Podcast? = null,
    val currentTracks: List<Track> = emptyList(),
    val isLoopEnabled: Boolean = false,
    val currentTrackIndex: Int = -1,
    val favoritesMode: Boolean = false,
    val favoritesList: List<FavoritePlayItem> = emptyList(),
    val favoritesIndex: Int = -1
)

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val mixcloudStreamResolver: MixcloudStreamResolver
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState

    private var progressJob: Job? = null
    private var trackSyncJob: Job? = null
    private var listenedMarked = false
    private var pendingSeekMs: Long? = null

    var onTrackEnded: (() -> Unit)? = null

    val exoPlayer: ExoPlayer by lazy {
        // Large buffer: 2min min, 10min max, 30s before playback starts, 5s after rebuffer
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2 * 60 * 1000,   // minBufferMs: 2 minutes
                10 * 60 * 1000,  // maxBufferMs: 10 minutes
                5_000,           // bufferForPlaybackMs: 5s before starting
                10_000           // bufferForPlaybackAfterRebufferMs: 10s after rebuffer
            )
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also { player ->
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) startProgressSync() else stopProgressSync()
                        startTrackSync()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("PodMix", "Player error: ${error.errorCode} - ${error.message}")
                        // Auto-retry on network errors
                        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                            val pos = exoPlayer.currentPosition
                            mainHandler.postDelayed({
                                exoPlayer.prepare()
                                exoPlayer.seekTo(pos)
                                exoPlayer.play()
                            }, 2000)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        updatePosition()
                        when (state) {
                            Player.STATE_READY -> {
                                pendingSeekMs?.let { ms ->
                                    pendingSeekMs = null
                                    exoPlayer.seekTo(ms)
                                }
                            }
                            Player.STATE_ENDED -> {
                                if (_playerState.value.isLoopEnabled) {
                                    exoPlayer.seekTo(0)
                                    exoPlayer.play()
                                } else {
                                    onTrackEnded?.invoke()
                                }
                            }
                        }
                    }
                })
            }
    }

    fun playEpisode(episode: Episode, podcast: Podcast, seekToMs: Long = 0L) {
        if (seekToMs > 0L) pendingSeekMs = seekToMs
        _playerState.value = _playerState.value.copy(
            currentEpisode = episode,
            currentPodcast = podcast
        )

        if (episode.mixcloudKey != null) {
            // Mixcloud primary, YouTube fallback
            scope.launch {
                Log.i("PodMix", "Resolving Mixcloud audio for ${episode.mixcloudKey}...")
                val url = mixcloudStreamResolver.resolve(episode.mixcloudKey)
                if (url != null) {
                    Log.i("PodMix", "Mixcloud resolved OK, playing...")
                    playMedia(episode.copy(audioUrl = url), podcast)
                } else if (!episode.youtubeVideoId.isNullOrBlank()) {
                    Log.w("PodMix", "Mixcloud failed, trying YouTube fallback...")
                    val ytUrl = youTubeStreamResolver.resolve(episode.youtubeVideoId)
                    if (ytUrl != null) {
                        Log.i("PodMix", "YouTube fallback OK, playing...")
                        playMedia(episode.copy(audioUrl = ytUrl), podcast)
                    } else {
                        Log.e("PodMix", "All stream resolution failed for ${episode.mixcloudKey}")
                        pendingSeekMs = null
                    }
                } else {
                    Log.e("PodMix", "Mixcloud resolution failed, no YouTube fallback")
                    pendingSeekMs = null
                }
            }
        } else if (!episode.youtubeVideoId.isNullOrBlank()) {
            // YouTube only
            scope.launch {
                Log.i("PodMix", "Resolving YouTube audio for ${episode.youtubeVideoId}...")
                val resolvedUrl = youTubeStreamResolver.resolve(episode.youtubeVideoId)
                if (resolvedUrl != null) {
                    Log.i("PodMix", "Resolved OK, playing...")
                    playMedia(episode.copy(audioUrl = resolvedUrl), podcast)
                } else {
                    Log.e("PodMix", "Failed to resolve YouTube audio for ${episode.youtubeVideoId}")
                    pendingSeekMs = null
                }
            }
        } else {
            // Direct URL (podcasts RSS)
            playMedia(episode, podcast)
        }
    }

    private fun playMedia(episode: Episode, podcast: Podcast) {
        mainHandler.post {
            listenedMarked = false
            val mediaItem = MediaItem.Builder()
                .setUri(episode.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(podcast.name)
                        .setArtworkUri(episode.artworkUrl?.let { Uri.parse(it) }
                            ?: podcast.logoUrl?.let { Uri.parse(it) })
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (pendingSeekMs == null && episode.progressSeconds > 60) {
                exoPlayer.seekTo(episode.progressSeconds * 1000L)
            }
            exoPlayer.play()
            _playerState.value = _playerState.value.copy(
                currentEpisode = episode,
                currentPodcast = podcast,
                currentTrackIndex = -1
            )
        }
    }

    fun pause() = mainHandler.post { exoPlayer.pause() }
    fun resume() = mainHandler.post { exoPlayer.play() }

    fun seekTo(ms: Long) = mainHandler.post {
        exoPlayer.seekTo(ms)
        // Resume if paused
        if (!exoPlayer.isPlaying) exoPlayer.play()
    }

    fun seekBack() = mainHandler.post { exoPlayer.seekBack() }
    fun seekForward() = mainHandler.post { exoPlayer.seekForward() }

    fun seekToTrack(track: Track, episodeType: String) = mainHandler.post {
        val preRollMs = when (episodeType) {
            "liveset" -> 30_000L
            else      -> 10_000L
        }
        val rawMs = (track.startTimeSec * 1000).toLong()
        val seekMs = (rawMs - preRollMs).coerceAtLeast(0L)
        exoPlayer.seekTo(seekMs)
        if (!exoPlayer.isPlaying) exoPlayer.play()
    }

    fun updateTracks(tracks: List<Track>) {
        _playerState.value = _playerState.value.copy(currentTracks = tracks, currentTrackIndex = -1)
        startTrackSync()
    }

    fun updateTracksKeepIndex(tracks: List<Track>) {
        _playerState.value = _playerState.value.copy(currentTracks = tracks)
    }

    fun toggleLoop() {
        val enabled = !_playerState.value.isLoopEnabled
        _playerState.value = _playerState.value.copy(isLoopEnabled = enabled)
    }

    fun setFavoritesPlaylist(items: List<FavoritePlayItem>, startIndex: Int) {
        _playerState.value = _playerState.value.copy(
            favoritesMode = true,
            favoritesList = items,
            favoritesIndex = startIndex
        )
    }

    fun clearFavoritesMode() {
        _playerState.value = _playerState.value.copy(
            favoritesMode = false,
            favoritesList = emptyList(),
            favoritesIndex = -1
        )
    }

    fun advanceFavorite(): FavoritePlayItem? {
        val state = _playerState.value
        if (!state.favoritesMode) return null
        val nextIdx = state.favoritesIndex + 1
        if (nextIdx >= state.favoritesList.size) {
            clearFavoritesMode()
            return null
        }
        _playerState.value = _playerState.value.copy(favoritesIndex = nextIdx)
        return state.favoritesList[nextIdx]
    }

    fun nextTrack() {
        val state = _playerState.value
        val tracks = state.currentTracks
        if (tracks.isEmpty()) { seekForward(); return }
        val nextIdx = state.currentTrackIndex + 1
        if (nextIdx < tracks.size) {
            seekTo((tracks[nextIdx].startTimeSec * 1000).toLong())
        }
    }

    fun prevTrack() {
        val state = _playerState.value
        val tracks = state.currentTracks
        if (tracks.isEmpty()) { seekBack(); return }
        val prevIdx = (state.currentTrackIndex - 1).coerceAtLeast(0)
        seekTo((tracks[prevIdx].startTimeSec * 1000).toLong())
    }

    private fun syncTrackByTime() {
        val state = _playerState.value
        val tracks = state.currentTracks
        if (tracks.isEmpty()) return

        val posSec = exoPlayer.currentPosition / 1000f
        val oldIdx = state.currentTrackIndex

        // Find current track (iterate backwards to find the last track that started)
        var newIdx = -1
        for (i in tracks.indices.reversed()) {
            if (posSec >= tracks[i].startTimeSec) {
                newIdx = i
                break
            }
        }

        // Update index
        if (newIdx != oldIdx) {
            _playerState.value = _playerState.value.copy(currentTrackIndex = newIdx)

            // Loop per-track: if we naturally moved to next track and loop is on
            if (state.isLoopEnabled && oldIdx >= 0 && newIdx > oldIdx) {
                val loopMs = (tracks[oldIdx].startTimeSec * 1000).toLong()
                mainHandler.post {
                    exoPlayer.seekTo(loopMs)
                    if (!exoPlayer.isPlaying) exoPlayer.play()
                }
                _playerState.value = _playerState.value.copy(currentTrackIndex = oldIdx)
            }
        }
    }

    private fun updatePosition() {
        _playerState.value = _playerState.value.copy(
            currentPosition = exoPlayer.currentPosition,
            duration = exoPlayer.duration.coerceAtLeast(0L)
        )
    }

    private fun startTrackSync() {
        trackSyncJob?.cancel()
        trackSyncJob = scope.launch {
            while (true) {
                delay(500)
                syncTrackByTime()
                updatePosition()
            }
        }
    }

    private fun stopTrackSync() { trackSyncJob?.cancel() }

    private fun startProgressSync() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(15_000)
                val episode = _playerState.value.currentEpisode ?: continue
                val positionSec = (exoPlayer.currentPosition / 1000).toInt()
                runCatching { episodeDao.updateProgress(episode.id, positionSec) }

                val duration = exoPlayer.duration
                if (!listenedMarked && duration > 0 && exoPlayer.currentPosition >= duration * 0.9) {
                    runCatching { episodeDao.toggleListened(episode.id) }
                    listenedMarked = true
                }
            }
        }
    }

    private fun stopProgressSync() { progressJob?.cancel() }
}
