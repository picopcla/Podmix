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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import okhttp3.OkHttpClient
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
    private val mixcloudStreamResolver: MixcloudStreamResolver,
    private val okHttpClient: OkHttpClient
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

    /** Compte les retries réseau pour l'URL courante — reset à chaque nouveau playMedia() */
    private var networkRetryCount = 0
    private val MAX_NETWORK_RETRIES = 3

    val exoPlayer: ExoPlayer by lazy {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                60 * 1000,       // minBufferMs: 1 minute (stabilité réseau)
                8 * 60 * 1000,   // maxBufferMs: 8 minutes
                1_500,           // bufferForPlaybackMs: 1.5s — démarrage rapide
                10_000           // bufferForPlaybackAfterRebufferMs: 10s — BT earbuds
                                 // prennent ~6s pour se reconnecter après une coupure audio ;
                                 // 3s causait une reprise trop tôt → nouveau stall immédiat
            )
            .build()

        // OkHttpDataSource with platform-specific headers to prevent 403/throttle cuts
        val ytOkHttpClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val newReq = when {
                    host.contains("googlevideo.com") || host.contains("youtube.com") ->
                        req.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/112.0.0.0 Mobile Safari/537.36")
                            .header("Origin", "https://www.youtube.com")
                            .header("Referer", "https://www.youtube.com/")
                            .build()
                    host.contains("mixcloud.com") || host.contains("audio4.mixcloud.com")
                        || host.contains("stream.mixcloud.com") ->
                        req.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                            .header("Origin", "https://www.mixcloud.com")
                            .header("Referer", "https://www.mixcloud.com/")
                            .build()
                    else -> req
                }
                chain.proceed(newReq)
            }
            .build()

        // OkHttp pour HTTP/HTTPS, DefaultDataSource wraps pour file:// et content://
        val httpDataSourceFactory = OkHttpDataSource.Factory(ytOkHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
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
                        val ep = _playerState.value.currentEpisode
                        val epInfo = ep?.let { "'${it.title.take(50)}'" } ?: "?"
                        com.podmix.AppLogger.player(if (isPlaying) "PLAYING" else "PAUSED", epInfo)
                        _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) startProgressSync() else stopProgressSync()
                        startTrackSync()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val ep = _playerState.value.currentEpisode
                        val epInfo = ep?.let { "'${it.title.take(50)}'" } ?: "?"
                        com.podmix.AppLogger.err("PLAYER", "ERROR code=${error.errorCode}", "$epInfo | ${error.message}")
                        Log.e("PodMix", "Player error: ${error.errorCode} - ${error.message}")
                        val episode = _playerState.value.currentEpisode
                        val podcast = _playerState.value.currentPodcast
                        val pos = exoPlayer.currentPosition

                        when (error.errorCode) {
                            // Re-resolve on 403 / expired URL
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                                if (episode != null && podcast != null) {
                                    when {
                                        episode.mixcloudKey != null -> {
                                            Log.w("PodMix", "HTTP error, invalidating+re-resolving Mixcloud URL for ${episode.mixcloudKey}")
                                            com.podmix.AppLogger.resolve("MC_REAUTH", "HTTP ${error.message ?: "?"} → invalidate+re-resolving ${episode.mixcloudKey}")
                                            mixcloudStreamResolver.invalidateCache(episode.mixcloudKey)
                                            scope.launch {
                                                val newUrl = mixcloudStreamResolver.resolveForStreaming(episode.mixcloudKey)
                                                if (newUrl != null) {
                                                    mainHandler.post {
                                                        pendingSeekMs = pos
                                                        playMedia(episode.copy(audioUrl = newUrl), podcast)
                                                    }
                                                } else {
                                                    com.podmix.AppLogger.err("RESOLVE", "MC_REAUTH_FAIL", episode.mixcloudKey)
                                                }
                                            }
                                        }
                                        !episode.soundcloudTrackUrl.isNullOrBlank() -> {
                                            Log.w("PodMix", "HLS expired, re-resolving SoundCloud ${episode.soundcloudTrackUrl}")
                                            com.podmix.AppLogger.resolve("SC_REAUTH", "HLS expired → re-resolving ${episode.soundcloudTrackUrl?.takeLast(60)}")
                                            scope.launch {
                                                val newUrl = youTubeStreamResolver.resolveSoundCloudTrack(episode.soundcloudTrackUrl)
                                                if (newUrl != null) mainHandler.post { pendingSeekMs = pos; playMedia(episode.copy(audioUrl = newUrl), podcast) }
                                            }
                                        }
                                        episode.youtubeVideoId != null -> {
                                            Log.w("PodMix", "HTTP error, re-resolving YouTube URL for ${episode.youtubeVideoId}")
                                            com.podmix.AppLogger.resolve("YT_REAUTH", "403 → invalidate+re-resolve ${episode.youtubeVideoId}")
                                            youTubeStreamResolver.invalidateCache(episode.youtubeVideoId)
                                            scope.launch {
                                                val newUrl = youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                                                if (newUrl != null) mainHandler.post { pendingSeekMs = pos; playMedia(episode.copy(audioUrl = newUrl), podcast) }
                                            }
                                        }
                                    }
                                }
                            }
                            // Corrupted/unreadable local file — delete and fall back to streaming
                            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                                val localPath = episode?.localAudioPath
                                if (localPath != null && episode != null && podcast != null) {
                                    Log.w("PodMix", "Corrupted local file $localPath — deleting and re-streaming")
                                    com.podmix.AppLogger.err("PLAYER", "CORRUPT_LOCAL → delete+restream", localPath)
                                    scope.launch {
                                        java.io.File(localPath).delete()
                                        episodeDao.clearLocalAudioPath(episode.id)
                                        mainHandler.post {
                                            // Re-play via URL resolution (no local file now)
                                            playEpisode(episode.copy(localAudioPath = null), podcast)
                                        }
                                    }
                                }
                            }
                            // Retry on transient network errors — max 3 fois puis abandon
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                                networkRetryCount++
                                if (networkRetryCount <= MAX_NETWORK_RETRIES) {
                                    val delayMs = (networkRetryCount * 3000L).coerceAtMost(10_000L)
                                    Log.w("PodMix", "Network error, retry $networkRetryCount/$MAX_NETWORK_RETRIES in ${delayMs}ms (re-resolve URL)")
                                    com.podmix.AppLogger.err("PLAYER", "NET_ERR retry $networkRetryCount/$MAX_NETWORK_RETRIES in ${delayMs}ms re-resolve", epInfo)
                                    // Re-resolve URL on each retry — force fresh DNS lookup + new HLS token.
                                    // Fixes the case where OkHttp's connection pool is bound to a stale
                                    // Network handle after SIM/Wi-Fi/roaming switch — retry on the SAME
                                    // URL would just hit the same broken socket. Re-resolution forces
                                    // ExoPlayer to build a fresh MediaItem and a fresh DataSource.
                                    if (episode != null && podcast != null) {
                                        scope.launch {
                                            kotlinx.coroutines.delay(delayMs)
                                            val newUrl = when {
                                                !episode.soundcloudTrackUrl.isNullOrBlank() -> {
                                                    youTubeStreamResolver.resolveSoundCloudTrack(episode.soundcloudTrackUrl)
                                                }
                                                !episode.mixcloudKey.isNullOrBlank() -> {
                                                    mixcloudStreamResolver.invalidateCache(episode.mixcloudKey)
                                                    mixcloudStreamResolver.resolveForStreaming(episode.mixcloudKey)
                                                }
                                                !episode.youtubeVideoId.isNullOrBlank() -> {
                                                    youTubeStreamResolver.invalidateCache(episode.youtubeVideoId)
                                                    youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                                                }
                                                else -> episode.audioUrl // direct RSS / radio stream — just re-prepare
                                            }
                                            mainHandler.post {
                                                pendingSeekMs = pos
                                                if (newUrl != null) {
                                                    playMedia(episode.copy(audioUrl = newUrl), podcast)
                                                } else {
                                                    // Resolution failed — fall back to plain re-prepare on same URL
                                                    exoPlayer.prepare()
                                                    exoPlayer.seekTo(pos)
                                                    exoPlayer.play()
                                                }
                                            }
                                        }
                                    } else {
                                        mainHandler.postDelayed({
                                            exoPlayer.prepare()
                                            exoPlayer.seekTo(pos)
                                            exoPlayer.play()
                                        }, delayMs)
                                    }
                                } else {
                                    Log.e("PodMix", "Network error: max retries ($MAX_NETWORK_RETRIES) reached, giving up")
                                    com.podmix.AppLogger.err("PLAYER", "NET_ERR GIVE_UP after $MAX_NETWORK_RETRIES retries", epInfo)
                                    // Last resort: if SoundCloud/Mixcloud CDN unreachable, try YouTube
                                    if (episode != null && podcast != null && !episode.youtubeVideoId.isNullOrBlank()) {
                                        Log.w("PodMix", "CDN unreachable — YouTube last-resort fallback ${episode.youtubeVideoId}")
                                        com.podmix.AppLogger.resolve("NET_GIVE_UP → YT_FALLBACK", episode.youtubeVideoId ?: "")
                                        networkRetryCount = 0
                                        scope.launch {
                                            val ytUrl = youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                                            if (ytUrl != null) mainHandler.post {
                                                pendingSeekMs = pos
                                                playMedia(episode.copy(audioUrl = ytUrl), podcast)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        val ep = _playerState.value.currentEpisode
                        val epInfo = ep?.let { "'${it.title.take(50)}'" } ?: "?"
                        val stateName = when (state) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "STATE_$state"
                        }
                        com.podmix.AppLogger.player("STATE_$stateName", epInfo)
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
        com.podmix.AppLogger.player("PLAY_REQUEST", "'${episode.title.take(60)}' | podcast='${podcast.name}' seekMs=$seekToMs")
        if (seekToMs > 0L) pendingSeekMs = seekToMs
        _playerState.value = _playerState.value.copy(
            currentEpisode = episode,
            currentPodcast = podcast
        )

        // Fichier local disponible → lecture directe, pas de résolution réseau
        val localPath = episode.localAudioPath
        if (localPath != null && java.io.File(localPath).exists()) {
            Log.i("PodMix", "Playing from local file: $localPath")
            com.podmix.AppLogger.player("PLAY_LOCAL", localPath)
            playMedia(episode.copy(audioUrl = android.net.Uri.fromFile(java.io.File(localPath)).toString()), podcast)
            return
        }

        if (!episode.soundcloudTrackUrl.isNullOrBlank()) {
            // SoundCloud — HLS stream, no throttle, re-resolve on expiry
            scope.launch {
                Log.i("PodMix", "Resolving SoundCloud HLS for ${episode.soundcloudTrackUrl}...")
                com.podmix.AppLogger.resolve("SC_START", episode.soundcloudTrackUrl ?: "")
                val t0 = System.currentTimeMillis()
                val hlsUrl = youTubeStreamResolver.resolveSoundCloudTrack(episode.soundcloudTrackUrl)
                val ms = System.currentTimeMillis() - t0
                if (hlsUrl != null) {
                    Log.i("PodMix", "SoundCloud HLS OK, streaming...")
                    com.podmix.AppLogger.resolve("SC_OK ${ms}ms", hlsUrl.take(80))
                    playMedia(episode.copy(audioUrl = hlsUrl), podcast)
                } else if (!episode.youtubeVideoId.isNullOrBlank()) {
                    Log.w("PodMix", "SoundCloud failed, falling back to YouTube ${episode.youtubeVideoId}")
                    com.podmix.AppLogger.err("RESOLVE", "SC_FAIL ${ms}ms → YT fallback", episode.youtubeVideoId ?: "")
                    val t1 = System.currentTimeMillis()
                    val ytUrl = youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                    if (ytUrl != null) { com.podmix.AppLogger.resolve("YT_OK ${System.currentTimeMillis()-t1}ms", episode.youtubeVideoId ?: ""); playMedia(episode.copy(audioUrl = ytUrl), podcast) }
                    else { com.podmix.AppLogger.err("RESOLVE", "ALL_FAIL SC+YT"); pendingSeekMs = null }
                } else {
                    Log.e("PodMix", "SoundCloud resolution failed, no fallback")
                    com.podmix.AppLogger.err("RESOLVE", "SC_FAIL ${ms}ms NO_FALLBACK")
                    pendingSeekMs = null
                }
            }
        } else if (episode.mixcloudKey != null) {
            // Mixcloud primary (HLS preferred for streaming), YouTube fallback
            scope.launch {
                Log.i("PodMix", "Resolving Mixcloud HLS for ${episode.mixcloudKey}...")
                com.podmix.AppLogger.resolve("MC_START", episode.mixcloudKey ?: "")
                val t0 = System.currentTimeMillis()
                val url = mixcloudStreamResolver.resolveForStreaming(episode.mixcloudKey)
                if (url != null) {
                    Log.i("PodMix", "Mixcloud resolved OK (${if (url.contains(".m3u8")) "HLS" else "direct"}), playing...")
                    com.podmix.AppLogger.resolve("MC_OK ${System.currentTimeMillis()-t0}ms", url.take(80))
                    playMedia(episode.copy(audioUrl = url), podcast)
                } else if (!episode.youtubeVideoId.isNullOrBlank()) {
                    Log.w("PodMix", "Mixcloud failed, trying YouTube fallback...")
                    com.podmix.AppLogger.err("RESOLVE", "MC_FAIL → YT fallback ${episode.youtubeVideoId}")
                    val t1 = System.currentTimeMillis()
                    val ytUrl = youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                    if (ytUrl != null) {
                        Log.i("PodMix", "YouTube fallback OK, playing...")
                        com.podmix.AppLogger.resolve("YT_OK ${System.currentTimeMillis()-t1}ms", episode.youtubeVideoId ?: "")
                        playMedia(episode.copy(audioUrl = ytUrl), podcast)
                    } else {
                        Log.e("PodMix", "All stream resolution failed for ${episode.mixcloudKey}")
                        com.podmix.AppLogger.err("RESOLVE", "ALL_FAIL MC+YT")
                        pendingSeekMs = null
                    }
                } else {
                    Log.e("PodMix", "Mixcloud resolution failed, no YouTube fallback")
                    com.podmix.AppLogger.err("RESOLVE", "MC_FAIL NO_FALLBACK")
                    pendingSeekMs = null
                }
            }
        } else if (!episode.youtubeVideoId.isNullOrBlank()) {
            // YouTube only
            scope.launch {
                Log.i("PodMix", "Resolving YouTube audio for ${episode.youtubeVideoId}...")
                com.podmix.AppLogger.resolve("YT_START", episode.youtubeVideoId ?: "")
                val t0 = System.currentTimeMillis()
                val resolvedUrl = youTubeStreamResolver.resolveForStreaming(episode.youtubeVideoId)
                val ms = System.currentTimeMillis() - t0
                if (resolvedUrl != null) {
                    Log.i("PodMix", "Resolved OK, playing...")
                    com.podmix.AppLogger.resolve("YT_OK ${ms}ms", episode.youtubeVideoId ?: "")
                    playMedia(episode.copy(audioUrl = resolvedUrl), podcast)
                } else {
                    Log.e("PodMix", "Failed to resolve YouTube audio for ${episode.youtubeVideoId}")
                    com.podmix.AppLogger.err("RESOLVE", "YT_FAIL ${ms}ms", episode.youtubeVideoId ?: "")
                    pendingSeekMs = null
                }
            }
        } else {
            // Direct URL (podcasts RSS)
            com.podmix.AppLogger.player("PLAY_DIRECT", episode.audioUrl.take(80))
            playMedia(episode, podcast)
        }
    }

    private fun playMedia(episode: Episode, podcast: Podcast) {
        mainHandler.post {
            listenedMarked = false
            networkRetryCount = 0  // reset retry counter pour chaque nouvelle URL
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
                currentPodcast = podcast
            )
        }
    }

    /** True si ExoPlayer a un media chargé et peut reprendre la lecture. */
    val isPlayerReady: Boolean
        get() = exoPlayer.playbackState == Player.STATE_READY ||
                exoPlayer.playbackState == Player.STATE_BUFFERING

    fun pause() = mainHandler.post { exoPlayer.pause() }
    fun resume() = mainHandler.post { exoPlayer.play() }

    /**
     * Called by PodMixMediaService when Android Auto (or another external controller)
     * starts an episode outside of [playEpisode]. Syncs playerState so progress
     * saving works, and resumes from the last saved position.
     */
    fun notifyExternalPlay(episode: Episode, podcast: Podcast) {
        mainHandler.post {
            listenedMarked = false
            _playerState.value = _playerState.value.copy(
                currentEpisode = episode,
                currentPodcast = podcast,
                currentTracks = emptyList(),
                favoritesMode = false
            )
            if (episode.progressSeconds > 60) {
                val seekMs = episode.progressSeconds * 1000L
                if (exoPlayer.playbackState == Player.STATE_READY ||
                    exoPlayer.playbackState == Player.STATE_BUFFERING) {
                    exoPlayer.seekTo(seekMs)
                } else {
                    pendingSeekMs = seekMs
                }
            }
        }
    }

    fun seekTo(ms: Long) = mainHandler.post {
        exoPlayer.seekTo(ms)
        // Resume if paused
        if (!exoPlayer.isPlaying) exoPlayer.play()
    }

    fun seekBack() = mainHandler.post { exoPlayer.seekBack() }
    fun seekForward() = mainHandler.post { exoPlayer.seekForward() }

    fun seekToTrack(track: Track, episodeType: String) = mainHandler.post {
        val seekMs = (track.startTimeSec * 1000).toLong()
        val trackIdx = _playerState.value.currentTracks.indexOfFirst { it.id == track.id }
        if (trackIdx >= 0) {
            _playerState.value = _playerState.value.copy(currentTrackIndex = trackIdx)
        }
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

    fun setCurrentTrackIndex(idx: Int) {
        _playerState.value = _playerState.value.copy(currentTrackIndex = idx)
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

            // NOTE: replaceMediaItem intentionnellement retiré — causait un reset du buffer
            // (~4 tracks en arrière) à chaque changement de track. Le playerState.currentTrackIndex
            // est suffisant pour l'UI. La notification Android Auto affiche le titre de l'épisode.
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
                if (!listenedMarked && duration > 0 && exoPlayer.currentPosition >= duration * 0.98) {
                    runCatching { episodeDao.toggleListened(episode.id) }
                    listenedMarked = true
                }
            }
        }
    }

    private fun stopProgressSync() { progressJob?.cancel() }
}
