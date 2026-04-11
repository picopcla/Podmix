package com.podmix.service

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
        private const val FAVORIS_ID = "FAVORIS"
        private const val PODCAST_PREFIX = "PODCAST/"
        private const val DJ_PREFIX = "DJ/"
        private const val EPISODE_PREFIX = "EPISODE/"
        private const val FAVORITE_PREFIX = "FAVORITE/"
    }

    override fun onCreate() {
        super.onCreate()
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            playerController.exoPlayer,
            LibrarySessionCallback()
        ).build()
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
                    parentId == FAVORIS_ID -> getFavorites()
                    parentId.startsWith(PODCAST_PREFIX) -> {
                        val id = parentId.removePrefix(PODCAST_PREFIX).toIntOrNull()
                        if (id != null) getEpisodesForPodcast(id) else emptyList()
                    }
                    parentId.startsWith(DJ_PREFIX) -> {
                        val id = parentId.removePrefix(DJ_PREFIX).toIntOrNull()
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
                resolved
            }
        }

        private fun getRootChildren(): List<MediaItem> = listOf(
            browsableItem(PODCASTS_ID, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
            browsableItem(DJS_ID, "DJs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            browsableItem(FAVORIS_ID, "Favoris", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        )

        private suspend fun getPodcastsList(type: String): List<MediaItem> {
            val prefix = if (type == "podcast") PODCAST_PREFIX else DJ_PREFIX
            return podcastDao.getByTypeSuspend(type).map { podcast ->
                MediaItem.Builder()
                    .setMediaId("$prefix${podcast.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(podcast.name)
                            .setArtworkUri(podcast.logoUrl?.let { Uri.parse(it) })
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(
                                if (type == "podcast") MediaMetadata.MEDIA_TYPE_PODCAST
                                else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                            )
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
                mixcloudKey = episodeEntity.mixcloudKey
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
