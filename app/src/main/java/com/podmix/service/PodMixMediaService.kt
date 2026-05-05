package com.podmix.service

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.domain.model.Episode
import com.podmix.domain.model.Podcast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PodMixMediaService : MediaLibraryService() {

    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var youTubeStreamResolver: YouTubeStreamResolver
    @Inject lateinit var mixcloudStreamResolver: MixcloudStreamResolver

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val ROOT_ID = "ROOT"
        private const val PODCASTS_ID = "PODCASTS"
        private const val DJS_ID = "DJS"
        private const val EMISSIONS_ID = "EMISSIONS"
        private const val RADIOS_ID = "RADIOS"
        private const val FAVORIS_ID = "FAVORIS"
        private const val PODCAST_PREFIX = "PODCAST/"
        private const val DJ_PREFIX = "DJ/"
        private const val EMISSION_PREFIX = "EMISSION/"
        private const val RADIO_PREFIX = "RADIO/"
        private const val EPISODE_PREFIX = "EPISODE/"
        private const val FAVORITE_PREFIX = "FAVORITE/"
    }

    private lateinit var trackAwarePlayer: TrackAwarePlayer

    override fun onCreate() {
        super.onCreate()
        trackAwarePlayer = TrackAwarePlayer(playerController.exoPlayer)
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            trackAwarePlayer,
            LibrarySessionCallback()
        ).build()

        // Track changes → update Android Auto metadata via getMediaMetadata() override (no replaceMediaItem = no seek reset)
        serviceScope.launch {
            var lastEpisodeId = -1
            var lastTrackIdx = -1
            playerController.playerState.collect { state ->
                val episodeId = state.currentEpisode?.id ?: -1
                val idx = state.currentTrackIndex
                if (idx >= 0 && (idx != lastTrackIdx || episodeId != lastEpisodeId)) {
                    lastTrackIdx = idx
                    lastEpisodeId = episodeId
                    val track = state.currentTracks.getOrNull(idx) ?: return@collect
                    val label = if (track.artist.isNotBlank())
                        "${track.artist} – ${track.title}"
                    else
                        track.title
                    trackAwarePlayer.updateTrackLabel(label)
                    Log.d("PodMixMS", "AA track: [$idx] $label")
                }
            }
        }
    }

    /** ForwardingPlayer that routes skip commands to track navigation and exposes
     *  current track name via getMediaMetadata() — no replaceMediaItem, no buffer reset. */
    private inner class TrackAwarePlayer(player: Player) : ForwardingPlayer(player) {

        @Volatile private var trackLabel: String? = null

        fun updateTrackLabel(label: String) { trackLabel = label }

        override fun getMediaMetadata(): MediaMetadata {
            val base = super.getMediaMetadata()
            val label = trackLabel ?: return base
            return base.buildUpon()
                .setArtist(label)
                .setSubtitle(label)
                .build()
        }

        override fun seekToNextMediaItem() { playerController.nextTrack() }
        override fun seekToPreviousMediaItem() { playerController.prevTrack() }
        override fun seekToNext() { playerController.nextTrack() }
        override fun seekToPrevious() { playerController.prevTrack() }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun isCommandAvailable(command: Int): Boolean {
            val hasTracks = playerController.playerState.value.currentTracks.isNotEmpty()
            return when (command) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT -> hasTracks
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS -> hasTracks
                else -> super.isCommandAvailable(command)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player ?: run { stopSelf(); return }
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaLibrarySession?.run { player.release(); release() }
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("PodMix")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when {
                    parentId == ROOT_ID -> getRootChildren()
                    parentId == PODCASTS_ID -> getPodcastsList("podcast")
                    parentId == DJS_ID -> getPodcastsList("dj")
                    parentId == EMISSIONS_ID -> getPodcastsList("emission")
                    parentId == RADIOS_ID -> getPodcastsList("radio")
                    parentId == FAVORIS_ID -> getFavorites()
                    parentId.startsWith(PODCAST_PREFIX) -> {
                        val id = parentId.removePrefix(PODCAST_PREFIX).toIntOrNull()
                        if (id != null) getEpisodesForPodcast(id) else emptyList()
                    }
                    parentId.startsWith(DJ_PREFIX) -> {
                        val id = parentId.removePrefix(DJ_PREFIX).toIntOrNull()
                        if (id != null) getEpisodesForPodcast(id) else emptyList()
                    }
                    parentId.startsWith(EMISSION_PREFIX) -> {
                        val id = parentId.removePrefix(EMISSION_PREFIX).toIntOrNull()
                        if (id != null) getEpisodesForPodcast(id) else emptyList()
                    }
                    parentId.startsWith(RADIO_PREFIX) -> {
                        val id = parentId.removePrefix(RADIO_PREFIX).toIntOrNull()
                        if (id != null) getEpisodesForPodcast(id) else emptyList()
                    }
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future {
                val item = resolveMediaItem(mediaId)
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                val exo = playerController.exoPlayer
                // If ExoPlayer is already active (phone playing), do NOT call setMediaItem —
                // that replaces the stream (even with the same URL) → rebuffer → expired URL → broken controls.
                // Android Auto connects to the live MediaSession directly, no resumption needed.
                if (exo.mediaItemCount > 0 && (exo.isPlaying || exo.playWhenReady)) {
                    Log.d("PodMixMS", "onPlaybackResumption: session live, skipping resumption")
                    throw UnsupportedOperationException("Session already active")
                }
                // Cold start: ExoPlayer idle → resolve from saved state
                val episode = playerController.playerState.value.currentEpisode
                val podcast  = playerController.playerState.value.currentPodcast
                if (episode != null && podcast != null) {
                    val item = resolveEpisodeToPlayable(episode.id)
                    if (item != null) {
                        val posMs = playerController.playerState.value.currentPosition
                            .takeIf { it > 0L }
                            ?: (episode.progressSeconds * 1000L).coerceAtLeast(0L)
                        Log.d("PodMixMS", "onPlaybackResumption: cold start ep=${episode.id} pos=${posMs}ms")
                        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, posMs)
                    } else {
                        throw RuntimeException("Cannot resolve episode ${episode.id}")
                    }
                } else {
                    throw RuntimeException("No current episode to resume")
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return serviceScope.future {
                val resolved = mediaItems.mapNotNull { item ->
                    val mediaId = item.mediaId
                    when {
                        mediaId.startsWith(EPISODE_PREFIX) -> {
                            val episodeId = mediaId.removePrefix(EPISODE_PREFIX).toIntOrNull()
                            if (episodeId != null) resolveEpisodeToPlayable(episodeId)
                            else null
                        }
                        mediaId.startsWith(FAVORITE_PREFIX) -> {
                            // Favorite tracks play the episode at the track's start time
                            val parts = mediaId.removePrefix(FAVORITE_PREFIX).split("/")
                            if (parts.size == 2) {
                                val episodeId = parts[0].toIntOrNull()
                                if (episodeId != null) resolveEpisodeToPlayable(episodeId)
                                else null
                            } else null
                        }
                        else -> null
                    }
                }.toMutableList()

                // Sync PlayerController so progress saving & resume work for Android Auto
                val firstMediaId = mediaItems.firstOrNull()?.mediaId
                val firstEpisodeId = when {
                    firstMediaId?.startsWith(EPISODE_PREFIX) == true ->
                        firstMediaId.removePrefix(EPISODE_PREFIX).toIntOrNull()
                    firstMediaId?.startsWith(FAVORITE_PREFIX) == true ->
                        firstMediaId.removePrefix(FAVORITE_PREFIX).split("/").firstOrNull()?.toIntOrNull()
                    else -> null
                }
                if (firstEpisodeId != null) loadAndNotifyExternalPlay(firstEpisodeId)

                resolved
            }
        }

        private suspend fun loadAndNotifyExternalPlay(episodeId: Int) {
            val epEntity = episodeDao.getById(episodeId) ?: return
            val podEntity = podcastDao.getById(epEntity.podcastId) ?: return
            val episode = Episode(
                id = epEntity.id,
                podcastId = epEntity.podcastId,
                title = epEntity.title,
                audioUrl = epEntity.audioUrl,
                datePublished = epEntity.datePublished,
                durationSeconds = epEntity.durationSeconds,
                progressSeconds = epEntity.progressSeconds,
                isListened = epEntity.isListened,
                artworkUrl = epEntity.artworkUrl,
                episodeType = epEntity.episodeType,
                youtubeVideoId = epEntity.youtubeVideoId,
                description = epEntity.description,
                mixcloudKey = epEntity.mixcloudKey,
                localAudioPath = epEntity.localAudioPath,
                soundcloudTrackUrl = epEntity.soundcloudTrackUrl
            )
            val podcast = Podcast(
                id = podEntity.id,
                name = podEntity.name,
                logoUrl = podEntity.logoUrl,
                description = podEntity.description,
                rssFeedUrl = podEntity.rssFeedUrl,
                type = podEntity.type,
                episodeCount = 0
            )
            playerController.notifyExternalPlay(episode, podcast)

            // Charger les tracks pour que skip-next/prev fonctionne depuis Android Auto
            val tracks = trackDao.getByEpisodeId(episodeId)
            if (tracks.isNotEmpty()) {
                val domainTracks = tracks.map { t ->
                    com.podmix.domain.model.Track(
                        id = t.id, episodeId = t.episodeId, position = t.position,
                        title = t.title, artist = t.artist ?: "",
                        startTimeSec = t.startTimeSec, endTimeSec = t.endTimeSec,
                        isFavorite = t.isFavorite, source = t.source ?: ""
                    )
                }
                playerController.updateTracks(domainTracks)
            }
        }

        private fun getRootChildren(): List<MediaItem> = listOf(
            browsableItem(PODCASTS_ID, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            browsableItem(DJS_ID, "DJs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsableItem(EMISSIONS_ID, "Émissions", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsableItem(RADIOS_ID, "Radios", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsableItem(FAVORIS_ID, "Favoris", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        )

        private suspend fun getPodcastsList(type: String): List<MediaItem> {
            // Radios : streams directs — playable immédiatement, pas de drill-down
            if (type == "radio") {
                return podcastDao.getByTypeSuspend("radio").map { radio ->
                    MediaItem.Builder()
                        .setMediaId("$RADIO_PREFIX${radio.id}")
                        .setUri(radio.rssFeedUrl ?: "")   // rssFeedUrl = stream URL pour les radios
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(radio.name)
                                .setArtworkUri(radio.logoUrl?.let { Uri.parse(it) })
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                                .build()
                        )
                        .build()
                }
            }

            val prefix = when (type) {
                "podcast"  -> PODCAST_PREFIX
                "dj"       -> DJ_PREFIX
                "emission" -> EMISSION_PREFIX
                else       -> PODCAST_PREFIX
            }
            val mediaType = when (type) {
                "podcast" -> MediaMetadata.MEDIA_TYPE_PODCAST
                else      -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            }
            return podcastDao.getByTypeSuspend(type).map { podcast ->
                MediaItem.Builder()
                    .setMediaId("$prefix${podcast.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(podcast.name)
                            .setArtworkUri(podcast.logoUrl?.let { Uri.parse(it) })
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(mediaType)
                            .build()
                    )
                    .build()
            }
        }

        private suspend fun getEpisodesForPodcast(podcastId: Int): List<MediaItem> {
            val podcast = podcastDao.getById(podcastId)
            return episodeDao.getByPodcastIdSuspend(podcastId).map { episode ->
                MediaItem.Builder()
                    .setMediaId("$EPISODE_PREFIX${episode.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(episode.title)
                            .setArtist(podcast?.name)
                            .setArtworkUri(
                                (episode.artworkUrl ?: podcast?.logoUrl)?.let { Uri.parse(it) }
                            )
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                            .build()
                    )
                    .build()
            }
        }

        private suspend fun getFavorites(): List<MediaItem> {
            return trackDao.getAllFavoritesSuspend().map { fav ->
                MediaItem.Builder()
                    .setMediaId("$FAVORITE_PREFIX${fav.episodeId}/${fav.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("${fav.artist} - ${fav.title}")
                            .setArtist(fav.podcastName)
                            .setArtworkUri(fav.podcastLogoUrl?.let { Uri.parse(it) })
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build()
                    )
                    .build()
            }
        }

        private suspend fun resolveMediaItem(mediaId: String): MediaItem? {
            return when {
                mediaId == ROOT_ID -> browsableItem(ROOT_ID, "PodMix", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                mediaId == PODCASTS_ID -> browsableItem(PODCASTS_ID, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                mediaId == DJS_ID -> browsableItem(DJS_ID, "DJs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                mediaId == EMISSIONS_ID -> browsableItem(EMISSIONS_ID, "Émissions", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                mediaId == RADIOS_ID -> browsableItem(RADIOS_ID, "Radios", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                mediaId == FAVORIS_ID -> browsableItem(FAVORIS_ID, "Favoris", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                mediaId.startsWith(EPISODE_PREFIX) -> {
                    val id = mediaId.removePrefix(EPISODE_PREFIX).toIntOrNull() ?: return null
                    val episode = episodeDao.getById(id) ?: return null
                    val podcast = podcastDao.getById(episode.podcastId)
                    MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(podcast?.name)
                                .setArtworkUri(
                                    (episode.artworkUrl ?: podcast?.logoUrl)?.let { Uri.parse(it) }
                                )
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                                .build()
                        )
                        .build()
                }
                mediaId.startsWith(FAVORITE_PREFIX) -> {
                    val parts = mediaId.removePrefix(FAVORITE_PREFIX).split("/")
                    if (parts.size != 2) return null
                    val episodeId = parts[0].toIntOrNull() ?: return null
                    val episode = episodeDao.getById(episodeId) ?: return null
                    val podcast = podcastDao.getById(episode.podcastId)
                    MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(podcast?.name)
                                .setArtworkUri(
                                    (episode.artworkUrl ?: podcast?.logoUrl)?.let { Uri.parse(it) }
                                )
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()
                }
                else -> null
            }
        }

        private suspend fun resolveEpisodeToPlayable(episodeId: Int): MediaItem? {
            val episodeEntity = episodeDao.getById(episodeId) ?: return null
            val podcastEntity = podcastDao.getById(episodeEntity.podcastId) ?: return null

            val episode = Episode(
                id = episodeEntity.id,
                podcastId = episodeEntity.podcastId,
                title = episodeEntity.title,
                audioUrl = episodeEntity.audioUrl,
                datePublished = episodeEntity.datePublished,
                durationSeconds = episodeEntity.durationSeconds,
                progressSeconds = episodeEntity.progressSeconds,
                isListened = episodeEntity.isListened,
                artworkUrl = episodeEntity.artworkUrl,
                episodeType = episodeEntity.episodeType,
                youtubeVideoId = episodeEntity.youtubeVideoId,
                description = episodeEntity.description,
                mixcloudKey = episodeEntity.mixcloudKey,
                localAudioPath = episodeEntity.localAudioPath,
                soundcloudTrackUrl = episodeEntity.soundcloudTrackUrl
            )

            val podcast = Podcast(
                id = podcastEntity.id,
                name = podcastEntity.name,
                logoUrl = podcastEntity.logoUrl,
                description = podcastEntity.description,
                rssFeedUrl = podcastEntity.rssFeedUrl,
                type = podcastEntity.type,
                episodeCount = 0
            )

            // Resolve audio URL: Mixcloud first, then YouTube fallback
            var audioUrl = episode.audioUrl
            val mcKey = episode.mixcloudKey
            val videoId = episode.youtubeVideoId
            if (!mcKey.isNullOrBlank()) {
                val resolved = mixcloudStreamResolver.resolve(mcKey)
                if (resolved != null) {
                    audioUrl = resolved
                } else if (!videoId.isNullOrBlank()) {
                    val ytResolved = youTubeStreamResolver.resolve(videoId)
                    if (ytResolved != null) audioUrl = ytResolved
                }
            } else if (!videoId.isNullOrBlank()) {
                val resolved = youTubeStreamResolver.resolve(videoId)
                if (resolved != null) audioUrl = resolved
            }

            return MediaItem.Builder()
                .setMediaId("$EPISODE_PREFIX${episode.id}")
                .setUri(audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(podcast.name)
                        .setArtworkUri(
                            (episode.artworkUrl ?: podcast.logoUrl)?.let { Uri.parse(it) }
                        )
                        .build()
                )
                .build()
        }

        private fun browsableItem(mediaId: String, title: String, mediaType: Int): MediaItem {
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(mediaType)
                        .build()
                )
                .build()
        }
    }
}
