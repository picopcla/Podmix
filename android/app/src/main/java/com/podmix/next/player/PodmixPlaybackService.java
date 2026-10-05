package com.podmix.next.player;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.PendingIntent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.MediaConstants;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.podmix.next.R;
import com.podmix.next.MainActivity;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

@OptIn(markerClass = UnstableApi.class)
public class PodmixPlaybackService extends MediaLibraryService {
    private static final String ROOT_ID = "podmix-root";
    private static final String RESUME_ID = "podmix-resume";
    private static final String QUEUE_ID = "podmix-queue";
    private static final String FAVORITES_ID = "podmix-favorites";
    private static final String PODCASTS_ID = "podmix-podcasts";
    private static final String SHOWS_ID = "podmix-shows";
    private static final String DJ_SETS_ID = "podmix-dj-sets";
    private static final String RADIOS_ID = "podmix-radios";
    private static final String FAVORITE_PREFIX = "favorite::";
    private static final String RESUME_PREFIX = "resume::";
    private static final String EPISODE_START_PREFIX = "episode-start::";
    private static final String EPISODE_RESUME_PREFIX = "episode-resume::";
    private static final String COMPLETED_EPISODES_PREFERENCES = "podmix-completed-episodes";
    private static final String ACTION_TOGGLE_FAVORITE = "com.podmix.next.TOGGLE_FAVORITE";
    private static final String ACTION_TOGGLE_REPEAT_ONE = "com.podmix.next.TOGGLE_REPEAT_ONE";
    public static final String ACTION_SEEK_NEXT_TRACK = "com.podmix.next.SEEK_NEXT_TRACK";
    public static final String ACTION_SEEK_PREVIOUS_TRACK = "com.podmix.next.SEEK_PREVIOUS_TRACK";
    public static final String ARG_ABSOLUTE_POSITION_MS = "podmixAbsolutePositionMs";
    private static final String EXTRA_LIVE = "podmixLive";
    private static final String EXTRA_SOURCE_KIND = "podmixSourceKind";
    private static WeakReference<PodmixPlaybackService> activeService = new WeakReference<>(null);
    private ExoPlayer player;
    private TrackAwarePlayer trackAwarePlayer;
    private MediaLibrarySession mediaSession;
    private String completionCandidateEpisodeId = "";
    private int exposedRepeatMode = Player.REPEAT_MODE_OFF;
    @Nullable private TrackState repeatedTrackState;
    private String repeatedTrackEpisodeMediaId = "";
    private final Handler trackHandler = new Handler(Looper.getMainLooper());
    private String lastPublishedTrackId = "";
    private final Runnable trackPresentationUpdater = new Runnable() {
        @Override public void run() {
            enforceContinuousTrackRepeat();
            refreshTrackPresentation();
            trackHandler.postDelayed(this, 750);
        }
    };
    private long stalledSinceMs = 0;
    private long lastObservedPositionMs = C.TIME_UNSET;
    private int consecutiveRecoveries = 0;
    private long lastRecoveryAtMs = 0;
    private long playbackGeneration = 0;
    @Nullable private Runnable pendingErrorRecovery;
    @Nullable private ConnectivityManager.NetworkCallback networkCallback;
    @Nullable private ConnectivityManager connectivityManager;
    private String lastCustomLayoutSignature = "";
    private final Map<String, LibraryEntry> cachedEntriesByMediaId = new HashMap<>();
    private final Map<String, List<LibraryEntry>> cachedTracksByEpisodeId = new HashMap<>();
    private final Runnable playbackHealthMonitor = new Runnable() {
        @Override public void run() {
            monitorPlaybackHealth();
            trackHandler.postDelayed(this, 2_000);
        }
    };
    
    // Cache en mémoire pour éviter de parser le JSON à chaque appel
    private List<LibraryEntry> cachedLibraryEntries = null;
    private Set<String> cachedFavoriteIds = null;
    private List<MediaItem> cachedResumeItems = null;
    
    // Versions en cache pour détecter les changements
    private long cachedLibraryVersion = 0;
    private long cachedFavoritesVersion = 0;
    private long cachedResumeVersion = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = new WeakReference<>(this);
        
        // A YouTube fallback may be a muxed MP4 even though Podmix only needs
        // its AAC track.  A multi-minute minimum made Media3 prepare far too
        // much of that larger file before audible playback. Keep a useful
        // network reserve but optimise the thresholds for audio playback.
        androidx.media3.exoplayer.DefaultLoadControl loadControl = 
            new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000,          // minBufferMs: 30 seconds
                    3 * 60 * 1000,   // maxBufferMs: 3 minutes
                    500,             // bufferForPlaybackMs
                    2_000            // bufferForPlaybackAfterRebufferMs
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        );
        
        // Audio attributes pour le streaming média
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();
        // Keep a finite timeout: a relay that accepts the connection without
        // returning media bytes must surface as an error instead of leaving
        // the player frozen in BUFFERING forever.
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("Podmix/1.0.66 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(25_000);
        
        // Création ExoPlayer sans cache (le cache SimpleCache causait un crash)
        // DefaultDataSource délègue les flux réseau à notre factory HTTP mais
        // conserve la prise en charge des URI file:// et content://. Sans lui,
        // un épisode téléchargé pouvait être affiché « Disponible » sans que
        // le lecteur puisse ouvrir son fichier local.
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
        player = new ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(8)))
            .setLoadControl(loadControl)
            // Use Media3's focus manager: spoken guidance ducks or pauses and
            // resumes the same stream, while a permanent takeover (Spotify)
            // does not get stolen back.
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)  // CPU éveillé pour streaming
            .build();
        
        // Same model as Podmix Legacy: the audio remains one continuous
        // episode, while this forwarding player publishes the current track
        // and maps Android Auto's transport buttons to timestamp navigation.
        trackAwarePlayer = new TrackAwarePlayer(player);
        mediaSession = new MediaLibrarySession.Builder(this, trackAwarePlayer, new LibraryCallback()).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                cancelPendingRecovery();
                playbackGeneration++;
                consecutiveRecoveries = 0;
                lastPublishedTrackId = "";
                updateSessionActivity(mediaItem);
                refreshTrackPresentation();
                setExposedRepeatMode(exposedRepeatMode);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d("PodmixService", "state=" + playbackState
                    + " playWhenReady=" + player.getPlayWhenReady()
                    + " playing=" + player.isPlaying()
                    + " suppression=" + player.getPlaybackSuppressionReason());
                // The Capacitor/WebView process may already be gone when an
                // Android Auto or background play reaches the real end. Keep
                // completion in the playback service as well, otherwise the
                // car never receives the "Lu" state for that episode.
                if (playbackState == Player.STATE_ENDED) {
                    markCurrentEpisodeCompleted();
                    clearNativeResume(player.getCurrentMediaItem());
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                Log.d("PodmixService", "playWhenReady=" + playWhenReady
                    + " reason=" + reason
                    + " state=" + player.getPlaybackState()
                    + " suppression=" + player.getPlaybackSuppressionReason());
                if (!playWhenReady) {
                    saveNativeResume();
                }
                if (!playWhenReady) {
                    cancelPendingRecovery();
                    playbackGeneration++;
                    consecutiveRecoveries = 0;
                }
            }

            @Override
            public void onPlaybackSuppressionReasonChanged(int playbackSuppressionReason) {
                Log.d("PodmixService", "suppression=" + playbackSuppressionReason
                    + " playWhenReady=" + player.getPlayWhenReady()
                    + " playing=" + player.isPlaying());
            }
            
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                String mediaId = player.getCurrentMediaItem() != null ? player.getCurrentMediaItem().mediaId : "?";
                android.util.Log.e("PodmixService", "Player error on " + mediaId + ": " + error.getErrorCodeName() + " - " + error.getMessage());
                schedulePlaybackRecovery("player-error");
            }
        });
        trackHandler.post(trackPresentationUpdater);
        trackHandler.post(playbackHealthMonitor);
        trackHandler.postDelayed(nativeResumeSaver, NATIVE_RESUME_SAVE_MS);
        registerNetworkRecovery();
    }

    // ---- Native resume store -------------------------------------------
    // The WebView used to be the only writer of resume positions. When an
    // episode is started from Android Auto, a Bluetooth/Bose button or with
    // the screen off, the WebView may not run, so no position was saved and
    // Android Auto only offered "start from the beginning". The service now
    // records the position itself, under the plain episode id.
    private static final String NATIVE_RESUME_PREFS = "podmix-native-resume";
    private static final long NATIVE_RESUME_SAVE_MS = 10_000;
    private static final String LAST_PLAYED_KEY = "lastEpisodeId";

    private final Runnable nativeResumeSaver = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) saveNativeResume();
            trackHandler.postDelayed(this, NATIVE_RESUME_SAVE_MS);
        }
    };

    static String plainEpisodeId(@Nullable String mediaId) {
        String result = mediaId == null ? "" : mediaId;
        for (int guard = 0; guard < 4; guard++) {
            String stripped = result;
            for (String prefix : new String[] {
                RESUME_PREFIX, EPISODE_RESUME_PREFIX, EPISODE_START_PREFIX, "episode::"
            }) {
                if (stripped.startsWith(prefix)) {
                    stripped = stripped.substring(prefix.length());
                    break;
                }
            }
            if (stripped.equals(result)) break;
            result = stripped;
        }
        return result;
    }

    private void saveNativeResume() {
        if (player == null) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null || isLiveRadioItem(item)) return;
        String episodeId = plainEpisodeId(item.mediaId);
        if (episodeId.isEmpty()) return;
        long positionMs = Math.max(0, player.getCurrentPosition());
        long durationMs = player.getDuration();
        if (positionMs < 5_000) return; // never overwrite a real position with a fresh start
        if (durationMs != C.TIME_UNSET && durationMs > 0
            && positionMs >= (long) (durationMs * 0.98)) {
            clearNativeResume(item);
            return;
        }
        try {
            SharedPreferences prefs = getSharedPreferences(NATIVE_RESUME_PREFS, MODE_PRIVATE);
            JSONObject all = new JSONObject(prefs.getString("items", "{}"));
            JSONObject entry = new JSONObject();
            entry.put("episodeId", episodeId);
            entry.put("id", RESUME_PREFIX + episodeId);
            entry.put("positionMs", positionMs);
            entry.put("durationMs", durationMs == C.TIME_UNSET ? 0 : durationMs);
            entry.put("at", System.currentTimeMillis());
            entry.put("title", String.valueOf(item.mediaMetadata.title == null ? "" : item.mediaMetadata.title));
            entry.put("artist", String.valueOf(item.mediaMetadata.artist == null ? "" : item.mediaMetadata.artist));
            entry.put("url", item.localConfiguration.uri.toString());
            entry.put("artworkUrl", item.mediaMetadata.artworkUri == null
                ? "" : item.mediaMetadata.artworkUri.toString());
            all.put(episodeId, entry);
            // Keep the 20 most recent entries.
            while (all.length() > 20) {
                String oldest = null; long oldestAt = Long.MAX_VALUE;
                java.util.Iterator<String> keys = all.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    long at = all.getJSONObject(key).optLong("at", 0);
                    if (at < oldestAt) { oldestAt = at; oldest = key; }
                }
                if (oldest == null) break;
                all.remove(oldest);
            }
            prefs.edit()
                .putString("items", all.toString())
                .putString(LAST_PLAYED_KEY, episodeId)
                .apply();
            cachedResumeItems = null;
        } catch (Exception error) {
            Log.w("PodmixService", "Unable to save native resume position", error);
        }
    }

    private void clearNativeResume(@Nullable MediaItem item) {
        if (item == null) return;
        String episodeId = plainEpisodeId(item.mediaId);
        if (episodeId.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences(NATIVE_RESUME_PREFS, MODE_PRIVATE);
            JSONObject all = new JSONObject(prefs.getString("items", "{}"));
            if (all.has(episodeId)) {
                all.remove(episodeId);
                prefs.edit().putString("items", all.toString()).apply();
                cachedResumeItems = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private JSONObject nativeResumeEntry(String episodeId) {
        try {
            JSONObject all = new JSONObject(
                getSharedPreferences(NATIVE_RESUME_PREFS, MODE_PRIVATE).getString("items", "{}"));
            return all.optJSONObject(plainEpisodeId(episodeId));
        } catch (Exception error) {
            return null;
        }
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    private void updateSessionActivity(@Nullable MediaItem item) {
        if (mediaSession == null || item == null) return;
        Bundle extras = item.mediaMetadata.extras;
        String sourceId = extras == null ? "" : extras.getString(MainActivity.EXTRA_SOURCE_ID, "");
        if (sourceId == null || sourceId.isBlank()) return;
        Intent intent = new Intent(this, MainActivity.class)
            .setAction("com.podmix.next.OPEN_CURRENT_SOURCE")
            .putExtra(MainActivity.EXTRA_SOURCE_ID, sourceId)
            .putExtra(MainActivity.EXTRA_EPISODE_ID, extras.getString(MainActivity.EXTRA_EPISODE_ID, ""))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            sourceId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        mediaSession.setSessionActivity(pendingIntent);
    }

    private void monitorPlaybackHealth() {
        if (player == null || !player.getPlayWhenReady()) {
            stalledSinceMs = 0;
            lastObservedPositionMs = C.TIME_UNSET;
            consecutiveRecoveries = 0;
            return;
        }
        long positionMs = Math.max(0, player.getCurrentPosition());
        boolean moving = lastObservedPositionMs == C.TIME_UNSET
            || Math.abs(positionMs - lastObservedPositionMs) >= 500;
        if (moving) {
            stalledSinceMs = 0;
            consecutiveRecoveries = 0;
        } else if (player.getPlaybackState() == Player.STATE_BUFFERING) {
            if (stalledSinceMs == 0) stalledSinceMs = android.os.SystemClock.elapsedRealtime();
            if (shouldRecoverPlayback(
                player.getPlayWhenReady(),
                player.getPlaybackState(),
                player.getPlaybackSuppressionReason(),
                android.os.SystemClock.elapsedRealtime() - stalledSinceMs
            )) {
                recoverPlayback("buffer-stall");
            }
        } else {
            stalledSinceMs = 0;
        }
        lastObservedPositionMs = positionMs;
    }

    static boolean shouldRecoverPlayback(
        boolean playWhenReady,
        int playbackState,
        int suppressionReason,
        long stalledForMs
    ) {
        return playWhenReady
            && playbackState == Player.STATE_BUFFERING
            && suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            // Give the HTTP source enough time to report its own timeout.
            // Restarting it every 12 seconds used to abort a cold yt-dlp
            // resolution before it could return the first audio bytes.
            && stalledForMs >= 35_000;
    }

    private void schedulePlaybackRecovery(String reason) {
        if (player == null || !player.getPlayWhenReady() || consecutiveRecoveries >= 5) return;
        cancelPendingRecovery();
        long generation = playbackGeneration;
        String mediaId = player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId;
        long delayMs = Math.min(10_000, 1_000L << consecutiveRecoveries);
        pendingErrorRecovery = () -> {
            pendingErrorRecovery = null;
            String currentId = player == null || player.getCurrentMediaItem() == null
                ? "" : player.getCurrentMediaItem().mediaId;
            if (generation != playbackGeneration || !mediaId.equals(currentId)) return;
            recoverPlayback(reason);
        };
        trackHandler.postDelayed(pendingErrorRecovery, delayMs);
    }

    private void recoverPlayback(String reason) {
        if (player == null || !player.getPlayWhenReady()
            || player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (consecutiveRecoveries >= 5 && now - lastRecoveryAtMs < 60_000) return;
        if (now - lastRecoveryAtMs >= 60_000) consecutiveRecoveries = 0;
        long positionMs = Math.max(0, player.getCurrentPosition());
        int index = Math.max(0, player.getCurrentMediaItemIndex());
        boolean live = isLiveRadioItem(player.getCurrentMediaItem());
        consecutiveRecoveries++;
        lastRecoveryAtMs = now;
        stalledSinceMs = 0;
        Log.w("PodmixService", "Playback recovery " + consecutiveRecoveries + "/5 (" + reason + ") at " + positionMs + "ms");
        player.stop();
        if (live) {
            // A reconnect at the old position may replay minutes of retained
            // Icecast/HLS data. Recreate the source at its default live edge.
            player.seekToDefaultPosition(index);
            Log.i("PodmixService", "Radio recovery moved to live edge");
        } else {
            player.seekTo(index, positionMs);
        }
        player.prepare();
        player.play();
    }

    private void cancelPendingRecovery() {
        if (pendingErrorRecovery == null) return;
        trackHandler.removeCallbacks(pendingErrorRecovery);
        pendingErrorRecovery = null;
    }

    private void registerNetworkRecovery() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                trackHandler.post(() -> {
                    if (player == null || !player.getPlayWhenReady()) return;
                    int state = player.getPlaybackState();
                    if (state == Player.STATE_IDLE || state == Player.STATE_BUFFERING) {
                        consecutiveRecoveries = 0;
                        recoverPlayback("network-available");
                    }
                });
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException error) {
            Log.w("PodmixService", "Network recovery callback unavailable", error);
            networkCallback = null;
        }
    }

    private final class LibraryCallback implements MediaLibrarySession.Callback {
        // A bare "play" from a Bluetooth/Bose button, the car or the system
        // media notification arrives with an empty queue after the service
        // was killed. Reload the last episode at its saved position.
        @Override
        public ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(
            MediaSession session,
            MediaSession.ControllerInfo controller
        ) {
            try {
                String lastId = getSharedPreferences(NATIVE_RESUME_PREFS, MODE_PRIVATE)
                    .getString(LAST_PLAYED_KEY, "");
                JSONObject entry = lastId.isEmpty() ? null : nativeResumeEntry(lastId);
                if (entry != null && !entry.optString("url", "").isEmpty()) {
                    String artworkUrl = entry.optString("artworkUrl", "");
                    MediaItem item = new MediaItem.Builder()
                        .setMediaId(entry.optString("id", RESUME_PREFIX + lastId))
                        .setUri(entry.optString("url"))
                        .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(decodeHtmlEntities(entry.optString("title", "")))
                            .setArtist(decodeHtmlEntities(entry.optString("artist", "")))
                            .setArtworkUri(artworkUrl.isEmpty() ? null : Uri.parse(artworkUrl))
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build())
                        .build();
                    completionCandidateEpisodeId = lastId;
                    return Futures.immediateFuture(new MediaItemsWithStartPosition(
                        List.of(item), 0, Math.max(0, entry.optLong("positionMs", 0))));
                }
            } catch (Exception error) {
                Log.w("PodmixService", "Playback resumption failed", error);
            }
            return Futures.immediateFailedFuture(new UnsupportedOperationException("Nothing to resume"));
        }

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
                    .add(new SessionCommand(ACTION_TOGGLE_REPEAT_ONE, Bundle.EMPTY))
                    .add(new SessionCommand(ACTION_SEEK_NEXT_TRACK, Bundle.EMPTY))
                    .add(new SessionCommand(ACTION_SEEK_PREVIOUS_TRACK, Bundle.EMPTY))
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
            if (ACTION_SEEK_NEXT_TRACK.equals(command.customAction)
                || ACTION_SEEK_PREVIOUS_TRACK.equals(command.customAction)) {
                if (player == null) {
                    return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    );
                }
                long requestedPositionMs = args.getLong(ARG_ABSOLUTE_POSITION_MS, C.TIME_UNSET);
                if (requestedPositionMs != C.TIME_UNSET) {
                    player.seekTo(Math.max(0, requestedPositionMs));
                }
                int direction = ACTION_SEEK_NEXT_TRACK.equals(command.customAction) ? 1 : -1;
                boolean moved = moveByTrack(direction);
                Log.i("PodmixService", "Explicit track navigation direction=" + direction
                    + " moved=" + moved + " position=" + player.getCurrentPosition());
                return Futures.immediateFuture(new SessionResult(
                    moved ? SessionResult.RESULT_SUCCESS : SessionResult.RESULT_ERROR_BAD_VALUE
                ));
            }
            if (ACTION_TOGGLE_REPEAT_ONE.equals(command.customAction)) {
                if (player == null) {
                    return Futures.immediateFuture(
                        new SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    );
                }
                int nextMode = trackAwarePlayer.getRepeatMode() == Player.REPEAT_MODE_ONE
                    ? Player.REPEAT_MODE_OFF
                    : Player.REPEAT_MODE_ONE;
                trackAwarePlayer.setRepeatMode(nextMode);
                TrackPresentation currentTrack = currentTrackPresentation();
                String favoriteId = currentTrack != null ? currentTrack.favoriteId : favoriteIdFor(
                    player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId
                );
                updateFavoriteLayout(favoriteId);
                return Futures.immediateFuture(
                    new SessionResult(SessionResult.RESULT_SUCCESS)
                );
            }
            if (!ACTION_TOGGLE_FAVORITE.equals(command.customAction)) {
                return MediaLibrarySession.Callback.super.onCustomCommand(
                    session, controller, command, args
                );
            }
            TrackPresentation currentTrack = currentTrackPresentation();
            String favoriteId = currentTrack != null ? currentTrack.favoriteId : favoriteIdFor(
                player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId
            );
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
            // Android Auto caches browse results aggressively. Notify both the
            // dedicated Favorites node and the root when a car-side action
            // changes the collection.
            mediaSession.notifyChildrenChanged(FAVORITES_ID, ids.size(), null);
            mediaSession.notifyChildrenChanged(ROOT_ID, 0, null);
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
                    .setExtras(contentStyles(
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_GRID_ITEM,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                    ))
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
                List<LibraryEntry> allEntries = libraryEntries();
                // Keep the four media families distinct. Podcasts are the
                // musical programmes for which Podmix exposes timestamps and
                // tracklists; shows are regular spoken-audio programmes.
                // Android Auto exposes four root categories comfortably.
                if (hasSourceKind(allEntries, "podcast")) {
                    rootItems.add(collectionItem(
                        PODCASTS_ID,
                        "Podcasts",
                        "Tracklists et timestamps",
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    ));
                }
                if (hasSourceKind(allEntries, "show")) {
                    rootItems.add(collectionItem(
                        SHOWS_ID,
                        "Émissions",
                        "Interviews et programmes audio",
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    ));
                }
                if (hasSourceKind(allEntries, "dj")) {
                    rootItems.add(collectionItem(
                        DJ_SETS_ID,
                        "DJ sets",
                        "Sets et tracklists",
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    ));
                }
                if (hasSourceKind(allEntries, "radio")) {
                    rootItems.add(collectionItem(
                        RADIOS_ID,
                        "Radios",
                        "En direct",
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    ));
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(rootItems, params));
            }
            // Reprendre l'écoute
            if (RESUME_ID.equals(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofItemList(resumeItems(), params));
            }
            // Catégories par type
            if (PODCASTS_ID.equals(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofItemList(sourcesByKind("podcast"), params));
            }
            if (SHOWS_ID.equals(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofItemList(sourcesByKind("show"), params));
            }
            if (DJ_SETS_ID.equals(parentId)) {
                // A DJ set is already the unit the listener chose.  Unlike a
                // podcast it must not be hidden one level below a synthetic
                // "source" folder: Android Auto shows each set directly,
                // then its tracks.
                return Futures.immediateFuture(LibraryResult.ofItemList(djSetItems(), params));
            }
            if (RADIOS_ID.equals(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofItemList(sourcesByKind("radio"), params));
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
            List<LibraryEntry> library = libraryEntries();
            LibraryEntry episode = episodeContainerFor(parentId, library);
            if (episode != null) {
                return Futures.immediateFuture(LibraryResult.ofItemList(
                    pagedItems(episodePlaybackChoices(episode, library), page, pageSize), params
                ));
            }
            List<MediaItem> children = new ArrayList<>();
            for (LibraryEntry entry : library) {
                if (parentId.equals(entry.parentId)) children.add(entry.item);
            }
            if (children.isEmpty()) {
                return Futures.immediateFuture(LibraryResult.ofItemList(children, params));
            }
            int safePage = Math.max(0, page);
            int safePageSize = pageSize <= 0 ? children.size() : pageSize;
            int start = Math.min(children.size(), safePage * safePageSize);
            int end = Math.min(children.size(), start + safePageSize);
            return Futures.immediateFuture(
                LibraryResult.ofItemList(children.subList(start, end), params)
            );
        }

        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
            MediaLibrarySession session,
            MediaSession.ControllerInfo browser,
            String query,
            @Nullable LibraryParams params
        ) {
            // Android Auto sends the query first, then requests the paged results.
            // The catalog is local, so there is no asynchronous search job to start.
            return Futures.immediateFuture(LibraryResult.ofVoid(params));
        }

        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
            MediaLibrarySession session,
            MediaSession.ControllerInfo browser,
            String query,
            int page,
            int pageSize,
            @Nullable LibraryParams params
        ) {
            String needle = query == null ? "" : query.trim().toLowerCase();
            List<MediaItem> matches = new ArrayList<>();
            for (LibraryEntry entry : libraryEntries()) {
                if (!entry.playable) continue;
                MediaMetadata metadata = entry.item.mediaMetadata;
                String haystack = ((metadata.title == null ? "" : metadata.title.toString())
                    + " " + (metadata.artist == null ? "" : metadata.artist.toString())
                    + " " + entry.item.mediaId).toLowerCase();
                if (needle.isEmpty() || haystack.contains(needle)) matches.add(entry.item);
            }
            int safePage = Math.max(0, page);
            int safePageSize = pageSize <= 0 ? matches.size() : pageSize;
            int start = Math.min(matches.size(), safePage * safePageSize);
            int end = Math.min(matches.size(), start + safePageSize);
            return Futures.immediateFuture(
                LibraryResult.ofItemList(matches.subList(start, end), params)
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
            for (MediaItem item : allPlayableItems()) {
                if (mediaId.equals(item.mediaId)) {
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null));
                }
            }
            for (LibraryEntry episode : libraryEntries()) {
                if (!episode.item.mediaId.startsWith("episode::")) continue;
                for (MediaItem choice : episodePlaybackChoices(episode, libraryEntries())) {
                    if (mediaId.equals(choice.mediaId)) {
                        return Futures.immediateFuture(LibraryResult.ofItem(choice, null));
                    }
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
            // Android Auto, however, returns the browsable episode item with
            // its URI attached. Treating it as an in-app item leaves a queue
            // of one full episode: no current track name, no Next button and
            // no track favorite. Keep episode containers on the library path
            // so they can be expanded into their track queue below.
            List<LibraryEntry> library = libraryEntries();
            MediaItem requestedContinuousItem = mediaItems.get(Math.max(0, Math.min(
                startIndex == C.INDEX_UNSET ? 0 : startIndex,
                mediaItems.size() - 1
            )));
            Bundle continuousExtras = requestedContinuousItem.mediaMetadata.extras;
            if (continuousExtras != null && continuousExtras.getBoolean("podmixContinuousEpisode", false)) {
                LibraryEntry episode = episodeContainerFor(requestedContinuousItem.mediaId, library);
                completionCandidateEpisodeId = episode == null
                    ? requestedContinuousItem.mediaId
                    : episode.item.mediaId.substring("episode::".length());
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(mediaItems, 0, Math.max(0, startPositionMs))
                );
            }
            boolean requestsEpisodeContainer = mediaItems.stream()
                .anyMatch(item -> episodeContainerFor(item.mediaId, library) != null);
            boolean requestsLiveRadio = mediaItems.stream().anyMatch(PodmixPlaybackService::isLiveRadioItem);
            if (!requestsEpisodeContainer
                && mediaItems.stream().allMatch(item -> item.localConfiguration != null)) {
                completionCandidateEpisodeId = "";
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex,
                        requestsLiveRadio ? C.TIME_UNSET : startPositionMs
                    )
                );
            }

            MediaItem requested = mediaItems.get(Math.max(0, Math.min(
                startIndex == C.INDEX_UNSET ? 0 : startIndex,
                mediaItems.size() - 1
            )));
            if (requested.mediaId.startsWith(RESUME_PREFIX)) {
                MediaItem resume = findPlayableItem(requested.mediaId);
                if (resume != null) {
                    completionCandidateEpisodeId = requested.mediaId.substring(RESUME_PREFIX.length());
                    long positionMs = getResumePosition(
                        requested.mediaId.substring(RESUME_PREFIX.length())
                    );
                    return Futures.immediateFuture(
                        new MediaItemsWithStartPosition(
                            List.of(resume), 0, positionMs
                        )
                    );
                }
            }

            EpisodePlaybackChoice playbackChoice = episodePlaybackChoiceFor(
                requested.mediaId, library
            );
            if (playbackChoice != null) {
                completionCandidateEpisodeId = playbackChoice.episodeId;
                long positionMs = playbackChoice.resume
                    ? getResumePosition(playbackChoice.episodeId)
                    : 0;
                return Futures.immediateFuture(
                    episodeTracksWithStartPosition(playbackChoice.episode, library, positionMs)
                );
            }

            LibraryEntry selected = null;
            for (LibraryEntry entry : library) {
                if (entry.item.mediaId.equals(requested.mediaId)) {
                    selected = entry;
                    break;
                }
            }
            // The Capacitor player stores a plain episode id (for example the
            // RSS guid), while Android Auto's browsable library deliberately
            // namespaces it as episode::<guid>.  A resumed car session carries
            // the former even though it has a URI, so resolve it back to its
            // episode container before deciding whether to keep its one-item
            // queue.
            if (selected == null) {
                selected = episodeContainerFor(requested.mediaId, library);
            }

            if ("radio".equals(selected.kind)) {
                completionCandidateEpisodeId = "";
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(List.of(selected.item), 0, C.TIME_UNSET)
                );
            }
            if (selected == null) {
                completionCandidateEpisodeId = "";
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                );
            }

            // Opening an episode from Android Auto starts its first track and
            // exposes every track to Prev/Next. A direct selection of an
            // individual track still follows the generic queue branch below.
            if (isEpisodeContainer(selected.item.mediaId)) {
                completionCandidateEpisodeId = selected.item.mediaId.substring("episode::".length());
                return Futures.immediateFuture(
                    episodeTracksWithStartPosition(selected, library, Math.max(0, startPositionMs))
                );
            }

            // Selecting a song in Android Auto starts the full episode at the
            // song timestamp. The underlying item stays continuous, while the
            // session publishes that song's metadata and Previous/Next seeks.
            LibraryEntry selectedEpisode = episodeContainerFor(selected.parentId, library);
            if (selectedEpisode != null) {
                completionCandidateEpisodeId = selectedEpisode.item.mediaId.substring("episode::".length());
                return Futures.immediateFuture(new MediaItemsWithStartPosition(
                    List.of(selectedEpisode.item),
                    0,
                    Math.max(0, selected.item.clippingConfiguration.startPositionMs)
                ));
            }

            List<MediaItem> playlist = new ArrayList<>();
            completionCandidateEpisodeId = "";
            int selectedIndex = 0;
            for (LibraryEntry entry : library) {
                if (!entry.playable || !entry.groupId.equals(selected.groupId)) continue;
                if (entry.item.mediaId.equals(selected.item.mediaId)) {
                    selectedIndex = playlist.size();
                }
                playlist.add(entry.item);
            }
            // Handle resume position
            long resumePositionMs = C.TIME_UNSET;
            if (selected.item.mediaId.startsWith(RESUME_PREFIX)) {
                String episodeId = selected.item.mediaId.substring(RESUME_PREFIX.length());
                resumePositionMs = getResumePosition(episodeId);
            }
            return Futures.immediateFuture(
                new MediaItemsWithStartPosition(playlist, selectedIndex, resumePositionMs)
            );
        }

        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            List<MediaItem> mediaItems
        ) {
            List<MediaItem> library = allPlayableItems();
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

    private List<MediaItem> allPlayableItems() {
        List<MediaItem> items = new ArrayList<>();
        for (LibraryEntry entry : libraryEntries()) {
            if (entry.playable) items.add(entry.item);
        }
        items.addAll(resumeItems());
        return items;
    }

    private boolean isEpisodeContainer(String mediaId) {
        for (LibraryEntry entry : libraryEntries()) {
            if (mediaId.equals(entry.item.mediaId)
                && Boolean.TRUE.equals(entry.item.mediaMetadata.isBrowsable)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private LibraryEntry episodeContainerFor(String mediaId, List<LibraryEntry> library) {
        if (mediaId == null || mediaId.isBlank()) return null;
        LibraryEntry direct = cachedEntriesByMediaId.get(mediaId);
        if (direct != null && direct.item.mediaId.startsWith("episode::")
            && Boolean.TRUE.equals(direct.item.mediaMetadata.isBrowsable)) return direct;
        LibraryEntry raw = cachedEntriesByMediaId.get("episode::" + mediaId);
        return raw != null && Boolean.TRUE.equals(raw.item.mediaMetadata.isBrowsable) ? raw : null;
    }

    private List<MediaItem> pagedItems(List<MediaItem> items, int page, int pageSize) {
        if (items.isEmpty()) return items;
        int safePage = Math.max(0, page);
        int safePageSize = pageSize <= 0 ? items.size() : pageSize;
        int start = Math.min(items.size(), safePage * safePageSize);
        int end = Math.min(items.size(), start + safePageSize);
        return new ArrayList<>(items.subList(start, end));
    }

    private List<MediaItem> episodePlaybackChoices(
        LibraryEntry episode,
        List<LibraryEntry> library
    ) {
        String episodeId = episode.item.mediaId.substring("episode::".length());
        List<MediaItem> choices = new ArrayList<>();
        long resumeMs = getResumePosition(episodeId);
        if (resumeMs != C.TIME_UNSET && resumeMs > 1_000) {
            choices.add(episodePlaybackChoiceItem(
                EPISODE_RESUME_PREFIX + episodeId,
                "Reprendre la lecture",
                "Reprendre à " + formatPosition(resumeMs),
                episode
            ));
        }
        choices.add(episodePlaybackChoiceItem(
            EPISODE_START_PREFIX + episodeId,
            "Lire depuis le début",
            "Épisode complet · 00:00",
            episode
        ));
        for (LibraryEntry entry : library) {
            if (entry.playable && episode.item.mediaId.equals(entry.parentId)) {
                choices.add(entry.item);
            }
        }
        return choices;
    }

    private MediaItem episodePlaybackChoiceItem(
        String mediaId,
        String title,
        String artist,
        LibraryEntry episode
    ) {
        return new MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(episode.item.mediaMetadata.artworkUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build())
            .build();
    }

    @Nullable
    private EpisodePlaybackChoice episodePlaybackChoiceFor(
        String mediaId,
        List<LibraryEntry> library
    ) {
        boolean resume = mediaId != null && mediaId.startsWith(EPISODE_RESUME_PREFIX);
        boolean start = mediaId != null && mediaId.startsWith(EPISODE_START_PREFIX);
        if (!resume && !start) return null;
        String episodeId = mediaId.substring((resume
            ? EPISODE_RESUME_PREFIX : EPISODE_START_PREFIX).length());
        LibraryEntry episode = episodeContainerFor(episodeId, library);
        return episode == null ? null : new EpisodePlaybackChoice(episode, episodeId, resume);
    }

    private MediaItemsWithStartPosition episodeTracksWithStartPosition(
        LibraryEntry episode,
        List<LibraryEntry> library,
        long requestedPositionMs
    ) {
        // One episode, one data source. Track names and transport commands are
        // virtual views over timestamps, so boundaries never reconnect HTTP.
        return new MediaItemsWithStartPosition(
            List.of(episode.item), 0, Math.max(0, requestedPositionMs)
        );
    }

    private String formatPosition(long positionMs) {
        long totalSeconds = Math.max(0, positionMs / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
            ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    @Nullable
    private MediaItem findPlayableItem(String mediaId) {
        for (MediaItem item : allPlayableItems()) {
            if (mediaId.equals(item.mediaId)) return item;
        }
        return null;
    }

    private List<LibraryEntry> libraryEntries() {
        long currentVersion = getSharedPreferences("podmix-library", MODE_PRIVATE).getLong("version", 0);
        
        // Retourne le cache si valide et version inchangée
        // La version persistée est l'autorité d'invalidation. Un TTL court
        // reparsait inutilement plusieurs mégaoctets toutes les cinq secondes.
        if (cachedLibraryEntries != null && currentVersion == cachedLibraryVersion) {
            return cachedLibraryEntries;
        }
        
        List<LibraryEntry> items = new ArrayList<>();
        String raw = readPersistedLibrary();
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
                // Android Auto renders MediaMetadata as plain text. Some catalogues
                // legitimately provide HTML entities (for example "Above &amp;
                // Beyond"), which the WebView normally decodes but Android Auto
                // would otherwise display verbatim.
                String title = decodeHtmlEntities(source.optString("title", "Podmix"));
                String artist = decodeHtmlEntities(source.optString("artist", ""));
                if (isNativeCompletedEpisode(id)) {
                    if (!title.contains("✓ Lu")) title += " · ✓ Lu";
                    if (!artist.contains("✓ Lu")) artist = "✓ Lu · " + artist;
                }
                String sourceKind = source.optString("kind", "");
                Bundle metadataExtras = browsable ? contentStyles(
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                ) : new Bundle();
                metadataExtras.putString(EXTRA_SOURCE_KIND, sourceKind);
                metadataExtras.putBoolean(EXTRA_LIVE, "radio".equals(sourceKind));
                MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setMediaId(id)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(ArtworkProvider.register(this, artworkUrl))
                        .setExtras(metadataExtras)
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
                    source.optString("favoriteId", ""),
                    sourceKind
                ));
            }
        } catch (Exception ignored) {
            // Une bibliothèque corrompue reste simplement vide.
        }
        int sourceCount = 0;
        int episodeCount = 0;
        int trackCount = 0;
        for (LibraryEntry entry : items) {
            if (ROOT_ID.equals(entry.parentId)) sourceCount++;
            else if (entry.item.mediaId.startsWith("episode::")) episodeCount++;
            else if (entry.playable) trackCount++;
        }
        Log.i(
            "PodmixService",
            "Android Auto library loaded: " + items.size() + " items ("
                + sourceCount + " sources, " + episodeCount + " episodes, "
                + trackCount + " tracks), " + raw.getBytes(StandardCharsets.UTF_8).length + " bytes"
        );
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
                entry.favoriteId,
                entry.kind
            ));
        }
        items.addAll(aliases);
        
        // Met en cache les données parsées
        cachedLibraryEntries = items;
        cachedEntriesByMediaId.clear();
        cachedTracksByEpisodeId.clear();
        for (LibraryEntry entry : items) {
            cachedEntriesByMediaId.put(entry.item.mediaId, entry);
            if (entry.parentId.startsWith("episode::") && entry.playable) {
                cachedTracksByEpisodeId
                    .computeIfAbsent(entry.parentId, ignored -> new ArrayList<>())
                    .add(entry);
            }
        }
        cachedFavoriteIds = null; // Invalide le cache des favoris car ils dépendent de la bibliothèque
        cachedResumeItems = null; // Invalide le cache de reprise
        cachedLibraryVersion = currentVersion;
        
        return items;
    }

    private boolean isNativeCompletedEpisode(String mediaId) {
        if (mediaId == null || !mediaId.startsWith("episode::")) return false;
        String episodeId = mediaId.substring("episode::".length());
        return getSharedPreferences(COMPLETED_EPISODES_PREFERENCES, MODE_PRIVATE)
            .getBoolean(episodeId, false);
    }

    private void markCurrentEpisodeCompleted() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        String mediaId = player.getCurrentMediaItem().mediaId;
        if (mediaId == null || mediaId.isBlank()) return;
        String episodeId = completionCandidateEpisodeId;
        if (mediaId.startsWith("episode::")) {
            episodeId = mediaId.substring("episode::".length());
        }
        if (episodeId.isBlank()) return;
        SharedPreferences preferences = getSharedPreferences(
            COMPLETED_EPISODES_PREFERENCES, MODE_PRIVATE
        );
        if (preferences.getBoolean(episodeId, false)) return;
        preferences.edit().putBoolean(episodeId, true).apply();
        completionCandidateEpisodeId = "";
        Log.i("PodmixService", "Episode completed natively: " + episodeId);
        invalidateCache();
    }

    private String readPersistedLibrary() {
        File file = new File(getFilesDir(), "podmix-library.json");
        if (file.isFile()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8
            ))) {
                StringBuilder raw = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
                String line;
                while ((line = reader.readLine()) != null) raw.append(line);
                return raw.toString();
            } catch (Exception error) {
                Log.w("PodmixService", "Unable to read Android Auto library", error);
            }
        }
        // Migration path for a library saved by older releases.
        return getSharedPreferences("podmix-library", MODE_PRIVATE).getString("items", "[]");
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
        long currentVersion = getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE).getLong("version", 0);
        
        // Retourne le cache si valide et version inchangée
        if (cachedFavoriteIds != null && currentVersion == cachedFavoritesVersion) {
            return cachedFavoriteIds;
        }
        
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
        
        cachedFavoriteIds = result;
        cachedFavoritesVersion = currentVersion;
        return result;
    }

    private void saveFavoriteIds(Set<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) array.put(id);
        getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE)
            .edit()
            .putString("trackIds", array.toString())
            .putBoolean("initialized", true)
            .putLong("version", System.currentTimeMillis())
            .apply();
        
        // Invalide les caches après modification
        cachedFavoriteIds = ids;
        cachedFavoritesVersion = System.currentTimeMillis();
        cachedLibraryEntries = null;
        cachedResumeItems = null;
        cachedLibraryVersion = 0;
        cachedResumeVersion = 0;
    }

    private void updateFavoriteLayout(@Nullable String favoriteId) {
        if (mediaSession == null) return;
        List<CommandButton> layout = new ArrayList<>();
        boolean repeatingOne = player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE;
        boolean favorite = favoriteId != null && !favoriteId.isBlank() && favoriteIds().contains(favoriteId);
        String signature = repeatingOne + ":" + (favoriteId == null ? "" : favoriteId) + ":" + favorite;
        if (signature.equals(lastCustomLayoutSignature)) return;
        lastCustomLayoutSignature = signature;
        // The car head unit maps ICON_REPEAT_ONE to its "previous" glyph. Keep the
        // very same two-arrows repeat pictogram as the Podmix phone player and put it
        // next to the forward control rather than letting Android Auto choose a slot.
        layout.add(new CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(repeatingOne ? "Arrêter la boucle" : "Répéter le morceau")
            .setCustomIconResId(R.drawable.ic_repeat)
            .setSessionCommand(new SessionCommand(ACTION_TOGGLE_REPEAT_ONE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build());
        if (favoriteId == null || favoriteId.isBlank()) {
            mediaSession.setCustomLayout(layout);
            return;
        }
        CommandButton button = new CommandButton.Builder()
            .setDisplayName(favorite ? "Retirer des favoris" : "Ajouter aux favoris")
            .setIconResId(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border)
            .setSessionCommand(new SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build();
        layout.add(button);
        mediaSession.setCustomLayout(layout);
    }

    @Nullable
    private TrackPresentation currentTrackPresentation() {
        if (player == null || player.getCurrentMediaItem() == null) return null;
        String currentId = player.getCurrentMediaItem().mediaId;
        List<LibraryEntry> library = libraryEntries();

        // A regular in-app track queue already uses its own track media id.
        LibraryEntry direct = cachedEntriesByMediaId.get(currentId);
        if (direct != null && !direct.favoriteId.isBlank()) {
            return new TrackPresentation(direct.item, direct.favoriteId);
        }

        // A resumed episode is a single media item. Locate its browsable
        // Android Auto counterpart, then select the child whose timestamp is
        // immediately before the absolute player position.
        LibraryEntry episode = episodeContainerFor(currentId, library);
        if (episode == null) return null;
        LibraryEntry selected = null;
        long positionMs = Math.max(0, player.getCurrentPosition());
        List<LibraryEntry> episodeTracks = cachedTracksByEpisodeId.getOrDefault(episode.item.mediaId, List.of());
        for (LibraryEntry entry : episodeTracks) {
            long startMs = entry.item.clippingConfiguration.startPositionMs;
            if (startMs <= positionMs) selected = entry;
            else break;
        }
        if (selected == null && !episodeTracks.isEmpty()) selected = episodeTracks.get(0);
        if (selected == null || selected.favoriteId.isBlank()) return null;
        return new TrackPresentation(selected.item, selected.favoriteId);
    }

    private void refreshTrackPresentation() {
        if (trackAwarePlayer == null) return;
        TrackPresentation track = currentTrackPresentation();
        String favoriteId = track == null ? favoriteIdFor(
            player == null || player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId
        ) : track.favoriteId;
        if (track == null) {
            lastPublishedTrackId = "";
            trackAwarePlayer.clearTrackPresentation();
            updateFavoriteLayout(favoriteId);
            return;
        }
        if (!track.favoriteId.equals(lastPublishedTrackId)) {
            lastPublishedTrackId = track.favoriteId;
            trackAwarePlayer.setTrackPresentation(track.item.mediaMetadata);
            Log.d("PodmixService", "Android Auto track: " + track.item.mediaMetadata.title);
        }
        updateFavoriteLayout(favoriteId);
    }

    private boolean moveByTrack(int direction) {
        if (player == null || player.getCurrentMediaItem() == null) return false;
        String currentId = player.getCurrentMediaItem().mediaId;
        List<LibraryEntry> library = libraryEntries();
        LibraryEntry episode = episodeContainerFor(currentId, library);
        if (episode == null) return false;
        List<LibraryEntry> tracks = cachedTracksByEpisodeId.getOrDefault(episode.item.mediaId, List.of());
        if (tracks.size() < 2) return false;
        long positionMs = Math.max(0, player.getCurrentPosition());
        int index = 0;
        for (int candidate = 0; candidate < tracks.size(); candidate++) {
            if (tracks.get(candidate).item.clippingConfiguration.startPositionMs <= positionMs) index = candidate;
            else break;
        }
        int target = index + direction;
        // Reaching a boundary is not a successful navigation. Clamping the
        // target to the current index restarted the same song and made both
        // the phone and Android Auto report a move that never happened.
        if (target < 0 || target >= tracks.size()) return false;
        long targetPositionMs = tracks.get(target).item.clippingConfiguration.startPositionMs;
        player.seekTo(targetPositionMs);
        if (exposedRepeatMode == Player.REPEAT_MODE_ONE) {
            repeatedTrackState = currentContinuousTrackState();
            repeatedTrackEpisodeMediaId = player.getCurrentMediaItem() == null
                ? "" : player.getCurrentMediaItem().mediaId;
        }
        // Seeking is not a play command. In particular, the same virtual
        // track navigation is used to keep a SoundTouch/Google Cast session
        // in sync while the local player is deliberately paused. Starting it
        // here caused two outputs to compete and could steal audio focus.
        refreshTrackPresentation();
        Log.d("PodmixService", "Track navigation " + index + " -> " + target
            + " at " + positionMs + "ms -> " + targetPositionMs + "ms");
        return true;
    }

    private boolean canMoveByTrack(int direction) {
        if (player == null || player.getCurrentMediaItem() == null) return false;
        LibraryEntry episode = episodeContainerFor(player.getCurrentMediaItem().mediaId, libraryEntries());
        if (episode == null) return false;
        List<LibraryEntry> tracks = cachedTracksByEpisodeId.getOrDefault(episode.item.mediaId, List.of());
        if (tracks.size() < 2) return false;
        long positionMs = Math.max(0, player.getCurrentPosition());
        int index = 0;
        for (int candidate = 0; candidate < tracks.size(); candidate++) {
            if (tracks.get(candidate).item.clippingConfiguration.startPositionMs <= positionMs) index = candidate;
            else break;
        }
        int target = index + direction;
        return target >= 0 && target < tracks.size();
    }

    @Nullable
    private TrackState currentContinuousTrackState() {
        if (player == null || player.getCurrentMediaItem() == null) return null;
        Bundle extras = player.getCurrentMediaItem().mediaMetadata.extras;
        if (extras == null || !extras.getBoolean("podmixContinuousEpisode", false)) return null;
        return buildCurrentTrackState(
            player.getCurrentMediaItem().mediaId,
            Math.max(0, player.getCurrentPosition()),
            Math.max(0, player.getDuration())
        );
    }

    private void setExposedRepeatMode(int repeatMode) {
        exposedRepeatMode = repeatMode;
        TrackState continuousTrack = repeatMode == Player.REPEAT_MODE_ONE
            ? currentContinuousTrackState() : null;
        if (continuousTrack != null) {
            // The real playlist contains one complete episode. Repeating that
            // MediaItem would loop the full episode, not the displayed song.
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            repeatedTrackState = continuousTrack;
            repeatedTrackEpisodeMediaId = player.getCurrentMediaItem().mediaId;
        } else {
            repeatedTrackState = null;
            repeatedTrackEpisodeMediaId = "";
            player.setRepeatMode(repeatMode);
        }
    }

    private void enforceContinuousTrackRepeat() {
        if (player == null || repeatedTrackState == null
            || player.getCurrentMediaItem() == null
            || !player.getCurrentMediaItem().mediaId.equals(repeatedTrackEpisodeMediaId)) return;
        TrackState track = repeatedTrackState;
        long positionMs = Math.max(0, player.getCurrentPosition());
        if (!shouldLoopContinuousTrack(
            exposedRepeatMode,
            player.getPlayWhenReady(),
            track.startMs,
            track.endMs,
            positionMs
        )) return;
        if (track.index == track.count - 1) markCurrentEpisodeCompleted();
        player.seekTo(track.startMs);
        refreshTrackPresentation();
    }

    static boolean shouldLoopContinuousTrack(
        int repeatMode,
        boolean playWhenReady,
        long startMs,
        long endMs,
        long positionMs
    ) {
        return repeatMode == Player.REPEAT_MODE_ONE
            && playWhenReady
            && endMs > startMs
            && positionMs >= endMs;
    }

    private final class TrackAwarePlayer extends ForwardingPlayer {
        private final List<Player.Listener> listeners = new ArrayList<>();
        @Nullable private MediaMetadata trackMetadata;

        TrackAwarePlayer(Player wrappedPlayer) { super(wrappedPlayer); }

        @Override public void play() {
            if (shouldRestartLiveOnPlay(
                isLiveRadioItem(getCurrentMediaItem()),
                getPlayWhenReady(),
                getPlaybackState()
            )) {
                Log.i("PodmixService", "Radio resumed with a fresh live connection");
                super.stop();
                super.seekToDefaultPosition();
                super.prepare();
            }
            super.play();
        }

        @Override public void pause() {
            super.pause();
        }

        @Override public void setRepeatMode(int repeatMode) {
            setExposedRepeatMode(repeatMode);
        }

        @Override public int getRepeatMode() {
            return exposedRepeatMode;
        }

        @Override public void addListener(Player.Listener listener) {
            listeners.add(listener);
            super.addListener(listener);
        }

        @Override public void removeListener(Player.Listener listener) {
            listeners.remove(listener);
            super.removeListener(listener);
        }

        void setTrackPresentation(MediaMetadata metadata) {
            trackMetadata = metadata;
            notifyMetadataChanged();
        }

        void clearTrackPresentation() {
            if (trackMetadata == null) return;
            trackMetadata = null;
            notifyMetadataChanged();
        }

        private void notifyMetadataChanged() {
            MediaMetadata metadata = getMediaMetadata();
            for (Player.Listener listener : new ArrayList<>(listeners)) {
                listener.onMediaMetadataChanged(metadata);
            }
        }

        @Override public MediaMetadata getMediaMetadata() {
            MediaMetadata base = super.getMediaMetadata();
            if (trackMetadata == null) return base;
            return base.buildUpon()
                .setTitle(trackMetadata.title)
                .setArtist(trackMetadata.artist)
                .setSubtitle(trackMetadata.artist)
                .setAlbumTitle(base.title)
                .setArtworkUri(trackMetadata.artworkUri != null ? trackMetadata.artworkUri : base.artworkUri)
                .build();
        }

        @Override public void seekToNextMediaItem() {
            if (!moveByTrack(1) && isPhysicalTrackQueue()) super.seekToNextMediaItem();
        }

        @Override public void seekToPreviousMediaItem() {
            if (!moveByTrack(-1) && isPhysicalTrackQueue()) super.seekToPreviousMediaItem();
        }

        @Override public void seekToNext() {
            if (!moveByTrack(1) && isPhysicalTrackQueue()) super.seekToNext();
        }

        @Override public void seekToPrevious() {
            if (!moveByTrack(-1) && isPhysicalTrackQueue()) super.seekToPrevious();
        }

        private boolean isPhysicalTrackQueue() {
            MediaItem item = getCurrentMediaItem();
            return item != null
                && item.mediaMetadata.extras != null
                && item.mediaMetadata.extras.getBoolean("podmixTrackNavigation", false);
        }

        private boolean canNavigateTracks(int direction) {
            if (canMoveByTrack(direction)) return true;
            if (!isPhysicalTrackQueue()) return false;
            return direction > 0 ? super.hasNextMediaItem() : super.hasPreviousMediaItem();
        }

        @Override public Player.Commands getAvailableCommands() {
            Player.Commands.Builder commands = super.getAvailableCommands().buildUpon();
            if (canNavigateTracks(1)) commands
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT);
            else commands
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT);
            if (canNavigateTracks(-1)) commands
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS);
            else commands
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS);
            return commands.build();
        }

        @Override public boolean isCommandAvailable(int command) {
            if (command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                || command == Player.COMMAND_SEEK_TO_NEXT) return canNavigateTracks(1);
            if (command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                || command == Player.COMMAND_SEEK_TO_PREVIOUS) return canNavigateTracks(-1);
            return super.isCommandAvailable(command);
        }
    }

    static boolean shouldRestartLiveOnPlay(
        boolean live,
        boolean playWhenReady,
        int playbackState
    ) {
        return live
            && !playWhenReady
            // BUFFERING is the normal state immediately after a brand-new
            // queue is prepared. Restart only an established/failed session.
            && playbackState != Player.STATE_BUFFERING;
    }

    static boolean isLiveRadioItem(@Nullable MediaItem item) {
        if (item == null || item.mediaMetadata.extras == null) return false;
        Bundle extras = item.mediaMetadata.extras;
        return extras.getBoolean(EXTRA_LIVE, false)
            || "radio".equals(extras.getString(EXTRA_SOURCE_KIND, ""));
    }

    @Nullable
    static TrackState currentTrackState(String mediaId, long positionMs, long durationMs) {
        PodmixPlaybackService service = activeService.get();
        return service == null ? null : service.buildCurrentTrackState(mediaId, positionMs, durationMs);
    }

    @Nullable
    private TrackState buildCurrentTrackState(String mediaId, long positionMs, long durationMs) {
        List<LibraryEntry> library = libraryEntries();
        LibraryEntry episode = episodeContainerFor(mediaId, library);
        if (episode == null) return null;
        List<LibraryEntry> tracks = cachedTracksByEpisodeId.getOrDefault(episode.item.mediaId, List.of());
        if (tracks.isEmpty()) return null;
        int index = 0;
        for (int candidate = 0; candidate < tracks.size(); candidate++) {
            if (tracks.get(candidate).item.clippingConfiguration.startPositionMs <= positionMs) index = candidate;
            else break;
        }
        long firstTrackStartMs = tracks.get(0).item.clippingConfiguration.startPositionMs;
        long startMs = index == 0 && positionMs < firstTrackStartMs
            ? 0 : tracks.get(index).item.clippingConfiguration.startPositionMs;
        long endMs = index + 1 < tracks.size()
            ? tracks.get(index + 1).item.clippingConfiguration.startPositionMs
            : durationMs;
        if (endMs <= startMs) endMs = durationMs;
        return new TrackState(index, tracks.size(), tracks.get(index).item.mediaId,
            Math.max(0, startMs), Math.max(0, endMs));
    }

    static final class TrackState {
        final int index;
        final int count;
        final String mediaId;
        final long startMs;
        final long endMs;

        TrackState(int index, int count, String mediaId, long startMs, long endMs) {
            this.index = index;
            this.count = count;
            this.mediaId = mediaId;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class TrackPresentation {
        final MediaItem item;
        final String favoriteId;

        TrackPresentation(MediaItem item, String favoriteId) {
            this.item = item;
            this.favoriteId = favoriteId;
        }
    }

    private static final class LibraryEntry {
        final MediaItem item;
        final String groupId;
        final String parentId;
        final boolean playable;
        final String favoriteId;
        final String kind;

        LibraryEntry(
            MediaItem item,
            String groupId,
            String parentId,
            boolean playable,
            String favoriteId,
            String kind
        ) {
            this.item = item;
            this.groupId = groupId;
            this.parentId = parentId;
            this.playable = playable;
            this.favoriteId = favoriteId;
            this.kind = kind;
        }
    }

    private static final class EpisodePlaybackChoice {
        final LibraryEntry episode;
        final String episodeId;
        final boolean resume;

        EpisodePlaybackChoice(LibraryEntry episode, String episodeId, boolean resume) {
            this.episode = episode;
            this.episodeId = episodeId;
            this.resume = resume;
        }
    }

    // Called by the Capacitor plugin after an atomic library commit.  Refresh
    // both the in-memory snapshot and every Android Auto browse node so the car
    // never keeps a formerly truncated source or an empty episode folder.
    public static void invalidateCache() {
        PodmixPlaybackService service = activeService.get();
        if (service == null) return;
        service.trackHandler.post(() -> {
            service.cachedLibraryEntries = null;
            service.cachedResumeItems = null;
            service.cachedLibraryVersion = 0;
            service.cachedResumeVersion = 0;
            if (service.mediaSession == null) return;
            List<LibraryEntry> entries = service.libraryEntries();
            Map<String, Integer> childCounts = new HashMap<>();
            for (LibraryEntry entry : entries) {
                childCounts.put(entry.parentId, childCounts.getOrDefault(entry.parentId, 0) + 1);
            }
            int rootCollectionCount = 0;
            if (service.hasSourceKind(entries, "podcast")) rootCollectionCount++;
            if (service.hasSourceKind(entries, "show")) rootCollectionCount++;
            if (service.hasSourceKind(entries, "dj")) rootCollectionCount++;
            if (service.hasSourceKind(entries, "radio")) rootCollectionCount++;
            service.mediaSession.notifyChildrenChanged(ROOT_ID, rootCollectionCount, null);
            service.mediaSession.notifyChildrenChanged(PODCASTS_ID, service.sourcesByKind("podcast").size(), null);
            service.mediaSession.notifyChildrenChanged(SHOWS_ID, service.sourcesByKind("show").size(), null);
            service.mediaSession.notifyChildrenChanged(DJ_SETS_ID, service.djSetItems().size(), null);
            service.mediaSession.notifyChildrenChanged(RADIOS_ID, service.sourcesByKind("radio").size(), null);
            for (Map.Entry<String, Integer> child : childCounts.entrySet()) {
                if (!ROOT_ID.equals(child.getKey())) {
                    service.mediaSession.notifyChildrenChanged(child.getKey(), child.getValue(), null);
                }
            }
        });
    }

    // A resume update is frequent and independent from the full browse tree.
    // Keep the parsed Android Auto library alive and refresh only this small
    // collection, otherwise playback progress causes severe allocation churn.
    public static void invalidateResumeCache() {
        PodmixPlaybackService service = activeService.get();
        if (service == null) return;
        service.trackHandler.post(() -> {
            service.cachedResumeItems = null;
            service.cachedResumeVersion = 0;
            if (service.mediaSession == null) return;
            service.mediaSession.notifyChildrenChanged(
                RESUME_ID,
                service.resumeItems().size(),
                null
            );
        });
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        PodmixPlayerPlugin.disconnectBoseOnTaskRemoval(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        trackHandler.removeCallbacks(trackPresentationUpdater);
        trackHandler.removeCallbacks(playbackHealthMonitor);
        cancelPendingRecovery();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        }
        if (activeService.get() == this) activeService.clear();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            saveNativeResume();
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private List<MediaItem> resumeItems() {
        long currentVersion = getSharedPreferences("podmix-resume", MODE_PRIVATE).getLong("version", 0);
        
        // Retourne le cache si valide et version inchangée
        if (cachedResumeItems != null && currentVersion == cachedResumeVersion) {
            return cachedResumeItems;
        }
        
        List<MediaItem> items = new ArrayList<>();
        String raw = getSharedPreferences("podmix-resume", MODE_PRIVATE).getString("items", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString("id", "");
                String episodeId = obj.optString("episodeId", "");
                String title = decodeHtmlEntities(obj.optString("title", ""));
                String artist = decodeHtmlEntities(obj.optString("artist", ""));
                String url = obj.optString("url", "");
                String artworkUrl = obj.optString("artworkUrl", "");
                if (id.isEmpty() || url.isEmpty()) continue;
                items.add(new MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(url)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(artworkUrl.isEmpty() ? null : Uri.parse(artworkUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build())
                    .build());
            }
        } catch (Exception ignored) {
        }
        
        // Episodes only the service knows about (started from the car or a
        // Bluetooth button while the WebView was not running).
        try {
            JSONObject all = new JSONObject(
                getSharedPreferences(NATIVE_RESUME_PREFS, MODE_PRIVATE).getString("items", "{}"));
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                JSONObject entry = all.getJSONObject(keys.next());
                String id = entry.optString("id", "");
                String url = entry.optString("url", "");
                if (id.isEmpty() || url.isEmpty()) continue;
                boolean known = false;
                for (MediaItem existing : items) if (id.equals(existing.mediaId)) known = true;
                if (known) continue;
                String artworkUrl = entry.optString("artworkUrl", "");
                items.add(new MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(url)
                    .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(decodeHtmlEntities(entry.optString("title", "")))
                        .setArtist(decodeHtmlEntities(entry.optString("artist", "")))
                        .setArtworkUri(artworkUrl.isEmpty() ? null : Uri.parse(artworkUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build())
                    .build());
            }
        } catch (Exception ignored) {
        }

        cachedResumeItems = items;
        cachedResumeVersion = currentVersion;
        return items;
    }

    private boolean hasSourceKind(List<LibraryEntry> entries, String kind) {
        for (LibraryEntry entry : entries) {
            if (kind.equals(entry.kind)) return true;
        }
        return false;
    }

    private static String decodeHtmlEntities(String value) {
        if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private static Bundle contentStyles(int browsableStyle, int playableStyle) {
        Bundle extras = new Bundle();
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsableStyle);
        extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playableStyle);
        return extras;
    }

    private static MediaItem collectionItem(
        String mediaId,
        String title,
        String subtitle,
        int childrenStyle
    ) {
        return new MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setExtras(contentStyles(
                    childrenStyle,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                ))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build())
            .build();
    }

    private List<MediaItem> sourcesByKind(String kind) {
        List<MediaItem> items = new ArrayList<>();
        for (LibraryEntry entry : libraryEntries()) {
            // The category level must show sources only. Returning every item
            // flattens episodes and tracks in Android Auto, which prevents its
            // normal source → episode → track browsing flow.
            if (kind.equals(entry.kind) && ROOT_ID.equals(entry.parentId)) {
                items.add(entry.item);
            }
        }
        return items;
    }

    private List<MediaItem> djSetItems() {
        List<MediaItem> items = new ArrayList<>();
        for (LibraryEntry entry : libraryEntries()) {
            // In the synchronized library a DJ source contains exactly its
            // set episode(s).  Return those episodes directly from the DJ
            // category, while retaining their normal child track list.
            if (!"dj".equals(entry.kind)
                || !entry.item.mediaId.startsWith("episode::")
                || !entry.parentId.startsWith("source::")) {
                continue;
            }
            items.add(entry.item);
        }
        return items;
    }

    private long getResumePosition(String episodeId) {
        JSONObject nativeEntry = nativeResumeEntry(episodeId);
        if (nativeEntry != null && nativeEntry.optLong("positionMs", 0) > 0) {
            return nativeEntry.optLong("positionMs");
        }
        episodeId = plainEpisodeId(episodeId);
        String raw = getSharedPreferences("podmix-resume", MODE_PRIVATE).getString("items", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String id = obj.optString("episodeId", "");
                if (episodeId.equals(id)) {
                    double positionSeconds = obj.optDouble("positionSeconds", 0.0);
                    long positionMs = (long) (positionSeconds * 1000);
                    return positionMs > 0 ? positionMs : C.TIME_UNSET;
                }
            }
        } catch (Exception ignored) {
        }
        return C.TIME_UNSET;
    }

}
