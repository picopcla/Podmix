package com.podmix.next;

import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import com.podmix.next.player.PodmixPlaybackService;

@RunWith(AndroidJUnit4.class)
public class PlaybackInstrumentedTest {
    @Test
    public void media3PreparesARealPodcastStream() throws Exception {
        assertStreamReady(
            "https://audio.thisisdistorted.com/repository/audio/episodes/Captive_Soul_097_192k-1784898982063251543-NDI4OTUtODY0MTkxOTE=.mp3"
        );
    }

    @Test
    public void media3PreparesAnHttpAacRadioStream() throws Exception {
        assertStreamReady("http://icecast.radiofrance.fr/fip-hifi.aac");
    }

    @Test
    public void media3QueueCanMoveToNextEpisode() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SessionToken token = new SessionToken(context, new ComponentName(context, PodmixPlaybackService.class));
        ListenableFuture<MediaController> future = new MediaController.Builder(context, token).buildAsync();
        MediaController controller = future.get(15, TimeUnit.SECONDS);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                controller.setMediaItems(List.of(
                    new MediaItem.Builder().setMediaId("one").setUri("https://example.com/one.mp3").build(),
                    new MediaItem.Builder().setMediaId("two").setUri("https://example.com/two.mp3").build()
                ));
                controller.prepare();
            });
            AtomicInteger index = new AtomicInteger();
            AtomicInteger count = new AtomicInteger();
            long deadline = System.currentTimeMillis() + 2_000;
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    index.set(controller.getCurrentMediaItemIndex());
                    count.set(controller.getMediaItemCount());
                });
                if (count.get() == 2) break;
                Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::seekToNextMediaItem);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> index.set(controller.getCurrentMediaItemIndex()));
            assertTrue(count.get() == 2 && index.get() == 1);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                controller.stop();
                MediaController.releaseFuture(future);
            });
        }
    }

    @Test
    public void androidAutoCanBrowsePodmixLibrary() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences =
            context.getSharedPreferences("podmix-library", Context.MODE_PRIVATE);
        SharedPreferences favoritePreferences =
            context.getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE);
        String previousLibrary = preferences.getString("items", "[]");
        String previousFavorites = favoritePreferences.getString("trackIds", "[]");
        boolean previousFavoritesInitialized =
            favoritePreferences.getBoolean("initialized", false);
        preferences
            .edit()
            .putString(
                "items",
                "["
                    + "{\"id\":\"source::podcast\",\"parentId\":\"podmix-root\",\"title\":\"Mon podcast\","
                    + "\"artist\":\"Podcast\",\"artworkUrl\":\"https://example.com/logo.jpg\","
                    + "\"browsable\":true,\"playable\":false},"
                    + "{\"id\":\"episode::mix\",\"parentId\":\"source::podcast\",\"title\":\"Mon épisode\","
                    + "\"artist\":\"Mon podcast\",\"browsable\":true,\"playable\":false},"
                    + "{\"id\":\"mix::track::1\",\"parentId\":\"episode::mix\",\"groupId\":\"mix\","
                    + "\"favoriteId\":\"mix::track::1\","
                    + "\"url\":\"https://example.com/mix.mp3\",\"title\":\"Premier morceau\",\"artist\":\"Artiste A\","
                    + "\"artworkUrl\":\"https://example.com/track-cover.jpg\","
                    + "\"browsable\":false,\"playable\":true,\"startPositionSeconds\":10,\"endPositionSeconds\":20},"
                    + "{\"id\":\"mix::track::2\",\"parentId\":\"episode::mix\",\"groupId\":\"mix\","
                    + "\"favoriteId\":\"mix::track::2\","
                    + "\"url\":\"https://example.com/mix.mp3\",\"title\":\"Deuxième morceau\",\"artist\":\"Artiste B\","
                    + "\"artworkUrl\":\"https://example.com/logo.jpg\","
                    + "\"browsable\":false,\"playable\":true,\"startPositionSeconds\":20,\"endPositionSeconds\":30}"
                    + "]"
            )
            .commit();
        favoritePreferences.edit()
            .putString("trackIds", "[\"mix::track::1\",\"mix::track::2\"]")
            .putBoolean("initialized", true)
            .commit();
        SessionToken token = new SessionToken(context, new ComponentName(context, PodmixPlaybackService.class));
        ListenableFuture<MediaBrowser> future = new MediaBrowser.Builder(context, token).buildAsync();
        MediaBrowser browser = future.get(15, TimeUnit.SECONDS);
        try {
            AtomicReference<ListenableFuture<LibraryResult<MediaItem>>> rootFuture = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> rootFuture.set(browser.getLibraryRoot(null))
            );
            LibraryResult<MediaItem> root = rootFuture.get().get(15, TimeUnit.SECONDS);
            assertTrue(root.value != null && "podmix-root".equals(root.value.mediaId));

            AtomicReference<ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>>
                childrenFuture = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> childrenFuture.set(browser.getChildren("podmix-root", 0, 20, null))
            );
            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> children =
                childrenFuture.get().get(15, TimeUnit.SECONDS);
            assertTrue(children.value != null && children.value.size() == 3);

            AtomicReference<ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>>
                favoritesFuture = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> favoritesFuture.set(browser.getChildren("podmix-favorites", 0, 20, null))
            );
            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> favorites =
                favoritesFuture.get().get(15, TimeUnit.SECONDS);
            assertTrue(
                favorites.value != null
                    && favorites.value.size() == 2
                    && "Premier morceau".contentEquals(favorites.value.get(0).mediaMetadata.title)
            );

            AtomicReference<ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>>
                episodesFuture = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> episodesFuture.set(browser.getChildren("source::podcast", 0, 20, null))
            );
            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> episodes =
                episodesFuture.get().get(15, TimeUnit.SECONDS);
            assertTrue(
                episodes.value != null
                    && episodes.value.size() == 1
                    && "Mon épisode".contentEquals(episodes.value.get(0).mediaMetadata.title)
            );

            AtomicReference<ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>>
                tracksFuture = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> tracksFuture.set(browser.getChildren("episode::mix", 0, 20, null))
            );
            LibraryResult<com.google.common.collect.ImmutableList<MediaItem>> tracks =
                tracksFuture.get().get(15, TimeUnit.SECONDS);
            assertTrue(
                tracks.value != null
                    && tracks.value.size() == 2
                    && "Premier morceau".contentEquals(tracks.value.get(0).mediaMetadata.title)
                    && tracks.value.get(0).mediaMetadata.artworkUri != null
                    && "content".equals(tracks.value.get(0).mediaMetadata.artworkUri.getScheme())
                    && tracks.value.get(1).mediaMetadata.artworkUri != null
                    && !tracks.value.get(0).mediaMetadata.artworkUri.equals(
                        tracks.value.get(1).mediaMetadata.artworkUri
                    )
                    && tracks.value.get(0).clippingConfiguration.startPositionMs == 10_000
                    && tracks.value.get(0).clippingConfiguration.endPositionMs == 20_000
            );

            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> browser.setMediaItem(new MediaItem.Builder().setMediaId("mix::track::2").build())
            );
            AtomicInteger queueSize = new AtomicInteger();
            AtomicInteger queueIndex = new AtomicInteger();
            long deadline = System.currentTimeMillis() + 2_000;
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    queueSize.set(browser.getMediaItemCount());
                    queueIndex.set(browser.getCurrentMediaItemIndex());
                });
                if (queueSize.get() == 2 && queueIndex.get() == 1) break;
                Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertTrue(queueSize.get() == 2 && queueIndex.get() == 1);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> browser.setMediaItem(new MediaItem.Builder()
                    .setMediaId("favorite::mix::track::2")
                    .build())
            );
            deadline = System.currentTimeMillis() + 2_000;
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    queueSize.set(browser.getMediaItemCount());
                    queueIndex.set(browser.getCurrentMediaItemIndex());
                });
                if (queueSize.get() == 2 && queueIndex.get() == 1) break;
                Thread.sleep(50);
            } while (System.currentTimeMillis() < deadline);
            assertTrue(queueSize.get() == 2 && queueIndex.get() == 1);
        } finally {
            preferences.edit().putString("items", previousLibrary).commit();
            favoritePreferences.edit()
                .putString("trackIds", previousFavorites)
                .putBoolean("initialized", previousFavoritesInitialized)
                .commit();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> MediaController.releaseFuture(future)
            );
        }
    }

    private void assertStreamReady(String url) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SessionToken token = new SessionToken(context, new ComponentName(context, PodmixPlaybackService.class));
        ListenableFuture<MediaController> future = new MediaController.Builder(context, token).buildAsync();
        MediaController controller = future.get(15, TimeUnit.SECONDS);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                controller.setMediaItem(MediaItem.fromUri(url));
                controller.prepare();
                controller.play();
            });
            long deadline = System.currentTimeMillis() + 30_000;
            AtomicInteger state = new AtomicInteger(Player.STATE_IDLE);
            AtomicReference<PlaybackException> error = new AtomicReference<>();
            while (System.currentTimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    state.set(controller.getPlaybackState());
                    error.set(controller.getPlayerError());
                });
                if (state.get() == Player.STATE_READY || error.get() != null) break;
                Thread.sleep(250);
            }
            assertTrue(
                "Media3 error: " + error.get(),
                error.get() == null && state.get() == Player.STATE_READY
            );
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                controller.stop();
                MediaController.releaseFuture(future);
            });
        }
    }
}
