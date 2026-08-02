package com.podmix.next.player;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.podmix.next.R;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

@OptIn(markerClass = UnstableApi.class)
public class PodmixPlaybackService extends MediaLibraryService {
    private static final String ROOT_ID = "podmix-root";
    private static final String QUEUE_ID = "podmix-queue";
    private static final String FAVORITES_ID = "podmix-favorites";
    private static final String FAVORITE_PREFIX = "favorite::";
    private static final String ACTION_TOGGLE_FAVORITE = "com.podmix.next.TOGGLE_FAVORITE";
    private ExoPlayer player;
    private MediaLibrarySession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("Podmix/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build();
        mediaSession = new MediaLibrarySession.Builder(this, player, new LibraryCallback()).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                updateFavoriteLayout(favoriteIdFor(mediaItem == null ? "" : mediaItem.mediaId));
            }
        });
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(
            MediaSession session,
            MediaSession.ControllerInfo controller
        ) {
            MediaSession.ConnectionResult result =
                MediaLibrarySession.Callback.super.onConnect(session, controller);
            return MediaSession.ConnectionResult.accept(
                result.availableSessionCommands.buildUpon()
                    .add(new SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                    .build(),
                result.availablePlayerCommands
            );
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            SessionCommand command,
            Bundle args
        ) {
            if (!ACTION_TOGGLE_FAVORITE.equals(command.customAction)) {
                return MediaLibrarySession.Callback.super.onCustomCommand(
                    session, controller, command, args
                );
            }
            String favoriteId = favoriteIdFor(player.getCurrentMediaItem() == null
                ? ""
                : player.getCurrentMediaItem().mediaId);
            if (favoriteId == null) {
                return Futures.immediateFuture(
                    new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                );
            }
            Set<String> ids = favoriteIds();
            boolean nowFavorite;
            if (ids.contains(favoriteId)) {
                ids.remove(favoriteId);
                nowFavorite = false;
            } else {
                ids.add(favoriteId);
                nowFavorite = true;
            }
            saveFavoriteIds(ids);
            updateFavoriteLayout(nowFavorite ? favoriteId : null);
            return Futures.immediateFuture(
                new SessionResult(SessionResult.RESULT_SUCCESS)
            );
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
            MediaLibrarySession session,
            MediaSession.ControllerInfo browser,
            @Nullable LibraryParams params
        ) {
            MediaItem root = new MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                    .setTitle("Podmix")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build())
                .build();
            return Futures.immediateFuture(LibraryResult.ofItem(root, params));
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
            MediaLibrarySession session,
            MediaSession.ControllerInfo browser,
            String parentId,
            int page,
            int pageSize,
            @Nullable LibraryParams params
        ) {
            if (ROOT_ID.equals(parentId)) {
                List<MediaItem> rootItems = new ArrayList<>();
                for (LibraryEntry entry : libraryEntries()) {
                    if (ROOT_ID.equals(entry.parentId)) rootItems.add(entry.item);
                }
                MediaItem favorites = new MediaItem.Builder()
                    .setMediaId(FAVORITES_ID)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("Favoris")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                    .build();
                rootItems.add(favorites);
                MediaItem queue = new MediaItem.Builder()
                    .setMediaId(QUEUE_ID)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("File d’écoute")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                    .build();
                rootItems.add(queue);
                return Futures.immediateFuture(LibraryResult.ofItemList(rootItems, params));
            }
            if (QUEUE_ID.equals(parentId)) {
                List<MediaItem> items = new ArrayList<>();
                int start = Math.max(0, page * pageSize);
                int end = Math.min(player.getMediaItemCount(), start + pageSize);
                for (int index = start; index < end; index++) {
                    MediaItem source = player.getMediaItemAt(index);
                    items.add(source.buildUpon()
                        .setMediaMetadata(source.mediaMetadata.buildUpon()
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build())
                        .build());
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(items, params));
            }
            List<MediaItem> children = new ArrayList<>();
            for (LibraryEntry entry : libraryEntries()) {
                if (parentId.equals(entry.parentId)) children.add(entry.item);
            }
            if (children.isEmpty()) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params));
            }
            int start = Math.max(0, page * pageSize);
            int end = Math.min(children.size(), start + pageSize);
            return Futures.immediateFuture(
                LibraryResult.ofItemList(children.subList(start, end), params)
            );
        }

        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
            MediaLibrarySession session,
            MediaSession.ControllerInfo browser,
            String mediaId
        ) {
            for (int index = 0; index < player.getMediaItemCount(); index++) {
                MediaItem item = player.getMediaItemAt(index);
                if (mediaId.equals(item.mediaId)) {
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null));
                }
            }
            for (MediaItem item : libraryItems()) {
                if (mediaId.equals(item.mediaId)) {
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null));
                }
            }
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
        }

        @Override
        public ListenableFuture<MediaItemsWithStartPosition> onSetMediaItems(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            List<MediaItem> mediaItems,
            int startIndex,
            long startPositionMs
        ) {
            if (mediaItems.isEmpty()) {
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(mediaItems, C.INDEX_UNSET, C.TIME_UNSET)
                );
            }

            // The in-app player already provides complete playable queues.
            if (mediaItems.stream().allMatch(item -> item.localConfiguration != null)) {
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                );
            }

            List<LibraryEntry> library = libraryEntries();
            MediaItem requested = mediaItems.get(Math.max(0, Math.min(
                startIndex == C.INDEX_UNSET ? 0 : startIndex,
                mediaItems.size() - 1
            )));
            LibraryEntry selected = null;
            for (LibraryEntry entry : library) {
                if (entry.item.mediaId.equals(requested.mediaId)) {
                    selected = entry;
                    break;
                }
            }
            if (selected == null) {
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                );
            }

            List<MediaItem> playlist = new ArrayList<>();
            int selectedIndex = 0;
            for (LibraryEntry entry : library) {
                if (!entry.playable || !entry.groupId.equals(selected.groupId)) continue;
                if (entry.item.mediaId.equals(selected.item.mediaId)) {
                    selectedIndex = playlist.size();
                }
                playlist.add(entry.item);
            }
            return Futures.immediateFuture(
                new MediaItemsWithStartPosition(playlist, selectedIndex, C.TIME_UNSET)
            );
        }

        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            List<MediaItem> mediaItems
        ) {
            List<MediaItem> library = libraryItems();
            List<MediaItem> resolved = new ArrayList<>();
            for (MediaItem requested : mediaItems) {
                // The in-app controller already supplies a URI, metadata and
                // sometimes clipping boundaries for a track inside an episode.
                // Replacing it by the library entry would turn that track back
                // into the complete podcast. Android Auto sends ID-only items,
                // which still need normal library resolution below.
                if (requested.localConfiguration != null) {
                    resolved.add(requested);
                    continue;
                }
                MediaItem match = null;
                for (MediaItem candidate : library) {
                    if (candidate.mediaId.equals(requested.mediaId)) {
                        match = candidate;
                        break;
                    }
                }
                resolved.add(match != null ? match : requested);
            }
            return Futures.immediateFuture(resolved);
        }
    }

    private List<MediaItem> libraryItems() {
        List<MediaItem> items = new ArrayList<>();
        for (LibraryEntry entry : libraryEntries()) items.add(entry.item);
        return items;
    }

    private List<LibraryEntry> libraryEntries() {
        List<LibraryEntry> items = new ArrayList<>();
        String raw = getSharedPreferences("podmix-library", MODE_PRIVATE).getString("items", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject source = array.getJSONObject(index);
                String id = source.optString("id", "");
                String url = source.optString("url", "");
                String parentId = source.optString("parentId", ROOT_ID);
                boolean browsable = source.optBoolean("browsable", false);
                boolean playable = source.optBoolean("playable", !url.isBlank());
                if (id.isBlank() || (!browsable && url.isBlank())) continue;
                String artworkUrl = source.optString("artworkUrl", "");
                MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setMediaId(id)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(source.optString("title", "Podmix"))
                        .setArtist(source.optString("artist", ""))
                        .setArtworkUri(ArtworkProvider.register(this, artworkUrl))
                        .setIsBrowsable(browsable)
                        .setIsPlayable(playable)
                        .build())
                    ;
                if (!url.isBlank()) itemBuilder.setUri(url);
                double clipStart = Math.max(0, source.optDouble("startPositionSeconds", 0.0));
                double clipEnd = source.optDouble("endPositionSeconds", -1.0);
                if (clipStart > 0 || clipEnd > clipStart) {
                    MediaItem.ClippingConfiguration.Builder clipping =
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((long) (clipStart * 1000));
                    if (clipEnd > clipStart) {
                        clipping.setEndPositionMs((long) (clipEnd * 1000));
                    }
                    itemBuilder.setClippingConfiguration(clipping.build());
                }
                items.add(new LibraryEntry(
                    itemBuilder.build(),
                    source.optString("groupId", id),
                    parentId,
                    playable,
                    source.optString("favoriteId", "")
                ));
            }
        } catch (Exception ignored) {
            // Une bibliothèque corrompue reste simplement vide.
        }
        Set<String> favorites = favoriteIds();
        List<LibraryEntry> aliases = new ArrayList<>();
        for (LibraryEntry entry : items) {
            if (!entry.playable
                || entry.favoriteId.isBlank()
                || !favorites.contains(entry.favoriteId)) {
                continue;
            }
            aliases.add(new LibraryEntry(
                entry.item.buildUpon()
                    .setMediaId(FAVORITE_PREFIX + entry.favoriteId)
                    .build(),
                FAVORITES_ID,
                FAVORITES_ID,
                true,
                entry.favoriteId
            ));
        }
        items.addAll(aliases);
        return items;
    }

    @Nullable
    private String favoriteIdFor(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) return null;
        for (LibraryEntry entry : libraryEntries()) {
            if (mediaId.equals(entry.item.mediaId) && !entry.favoriteId.isBlank()) {
                return entry.favoriteId;
            }
        }
        return null;
    }

    private Set<String> favoriteIds() {
        Set<String> result = new HashSet<>();
        String raw = getSharedPreferences(
            "podmix-favorites", Context.MODE_PRIVATE
        ).getString("trackIds", "[]");
        try {
            JSONArray ids = new JSONArray(raw);
            for (int index = 0; index < ids.length(); index++) {
                String id = ids.optString(index, "");
                if (!id.isBlank()) result.add(id);
            }
        } catch (Exception ignored) {
            // Des favoris corrompus repartent d'une liste vide.
        }
        return result;
    }

    private void saveFavoriteIds(Set<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE)
            .edit()
            .putString("trackIds", array.toString())
            .putBoolean("initialized", true)
            .apply();
    }

    private void updateFavoriteLayout(@Nullable String favoriteId) {
        if (mediaSession == null) return;
        if (favoriteId == null || favoriteId.isBlank()) {
            mediaSession.setCustomLayout(new ArrayList<>());
            return;
        }
        boolean favorite = favoriteIds().contains(favoriteId);
        CommandButton button = new CommandButton.Builder()
            .setDisplayName(favorite ? "Retirer des favoris" : "Ajouter aux favoris")
            .setIconResId(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border)
            .setSessionCommand(new SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build();
        mediaSession.setCustomLayout(List.of(button));
    }

    private static final class LibraryEntry {
        final MediaItem item;
        final String groupId;
        final String parentId;
        final boolean playable;
        final String favoriteId;

        LibraryEntry(
            MediaItem item,
            String groupId,
            String parentId,
            boolean playable,
            String favoriteId
        ) {
            this.item = item;
            this.groupId = groupId;
            this.parentId = parentId;
            this.playable = playable;
            this.favoriteId = favoriteId;
        }
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
