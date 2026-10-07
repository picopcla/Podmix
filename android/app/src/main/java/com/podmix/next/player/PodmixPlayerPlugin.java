package com.podmix.next.player;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.AtomicFile;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.podmix.next.MainActivity;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import androidx.work.Constraints;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@CapacitorPlugin(name = "PodmixPlayer", permissions = {
    @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO })
})
@OptIn(markerClass = UnstableApi.class)
public class PodmixPlayerPlugin extends Plugin implements Player.Listener {
    private static final String TAG = "PodmixPlayer";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4201;
    private static final ExecutorService ARTWORK_PREFETCH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService MUSIC_RECOGNITION_EXECUTOR = Executors.newSingleThreadExecutor();
    // SoundTouch applies AVTransport commands asynchronously. Keep media
    // replacements strictly ordered: a previous episode must never finish
    // loading after the episode the listener selected most recently.
    private static final ExecutorService BOSE_PLAYBACK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String BOSE_OUTPUT_PREFERENCES = "podmix-bose-output";
    private static final String BOSE_ACTIVE_IP = "active-ip";
    private static final AtomicBoolean BOSE_TASK_REMOVED = new AtomicBoolean(true);
    private static final Object LIBRARY_SYNC_LOCK = new Object();
    private static final String LIBRARY_FILE = "podmix-library.json";
    private static final String LIBRARY_STAGING_FILE = "podmix-library.staging.json";
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private String playbackError = "";
    private long stateSequence = 0;
    private int repeatMode = Player.REPEAT_MODE_OFF;
    private String castMediaId = "";
    private long castPositionOffsetMs = 0;
    private long castEndPositionMs = 0;
    private long lastCastAbsolutePositionMs = 0;
    private boolean lastCastWasPlaying = false;
    private long castMissingSinceMs = 0;
    private long lastCastPersistenceAtMs = 0;
    private long lastPersistedCastPositionMs = -1;
    private boolean lastPersistedCastWasPlaying = false;
    private static final String CAST_PREFERENCES = "podmix-cast-session";

    private interface ControllerAction {
        void run(MediaController mediaController) throws Exception;
    }

    private synchronized ListenableFuture<MediaController> ensureController() {
        if (controllerFuture == null) {
            SessionToken token = new SessionToken(
                getContext(),
                new ComponentName(getContext(), PodmixPlaybackService.class)
            );
            controllerFuture = new MediaController.Builder(getContext(), token)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync();
            controllerFuture.addListener(() -> {
                try {
                    controller = controllerFuture.get();
                    controller.addListener(this);
                    emitState();
                } catch (Exception error) {
                    Log.e(TAG, "Connexion Media3 impossible", error);
                    controller = null;
                }
            }, ContextCompat.getMainExecutor(getContext()));
        }
        return controllerFuture;
    }

    private void withController(PluginCall call, ControllerAction action) {
        ListenableFuture<MediaController> future = ensureController();
        future.addListener(() -> {
            try {
                MediaController mediaController = future.get();
                action.run(mediaController);
            } catch (Exception error) {
                call.reject("Connexion au lecteur Android impossible", error);
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private RemoteMediaClient castClient() {
        try {
            CastSession session = CastContext.getSharedInstance(getContext())
                .getSessionManager().getCurrentCastSession();
            return session != null && session.isConnected() ? session.getRemoteMediaClient() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private SharedPreferences castPreferences() {
        return getContext().getSharedPreferences(CAST_PREFERENCES, Context.MODE_PRIVATE);
    }

    private void restoreCastIdentityIfNeeded(RemoteMediaClient remote) {
        if (remote == null || !castMediaId.isBlank()) return;
        SharedPreferences preferences = castPreferences();
        castMediaId = preferences.getString("mediaId", "");
        castPositionOffsetMs = preferences.getLong("positionOffsetMs", 0);
        castEndPositionMs = preferences.getLong("endPositionMs", 0);
        lastCastAbsolutePositionMs = preferences.getLong(
            "absolutePositionMs", Math.max(0, remote.getApproximateStreamPosition()));
        lastCastWasPlaying = preferences.getBoolean("playing", remote.isPlaying());
    }

    private void persistCastIdentity() {
        persistCastIdentity(false);
    }

    private void persistCastIdentity(boolean force) {
        if (castMediaId.isBlank()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force
            && lastCastWasPlaying == lastPersistedCastWasPlaying
            && Math.abs(lastCastAbsolutePositionMs - lastPersistedCastPositionMs) < 5_000
            && now - lastCastPersistenceAtMs < 5_000) {
            return;
        }
        castPreferences().edit()
            .putString("mediaId", castMediaId)
            .putLong("positionOffsetMs", castPositionOffsetMs)
            .putLong("endPositionMs", castEndPositionMs)
            .putLong("absolutePositionMs", lastCastAbsolutePositionMs)
            .putBoolean("playing", lastCastWasPlaying)
            .apply();
        lastCastPersistenceAtMs = now;
        lastPersistedCastPositionMs = lastCastAbsolutePositionMs;
        lastPersistedCastWasPlaying = lastCastWasPlaying;
    }

    private void clearCastIdentity() {
        castMediaId = "";
        castPositionOffsetMs = 0;
        castEndPositionMs = 0;
        lastCastAbsolutePositionMs = 0;
        lastCastWasPlaying = false;
        castMissingSinceMs = 0;
        lastCastPersistenceAtMs = 0;
        lastPersistedCastPositionMs = -1;
        lastPersistedCastWasPlaying = false;
        castPreferences().edit().clear().apply();
    }

    static long localPositionForCastAbsolute(boolean continuousEpisode, long absolutePositionMs, long positionOffsetMs) {
        return continuousEpisode
            ? Math.max(0, absolutePositionMs)
            : Math.max(0, absolutePositionMs - positionOffsetMs);
    }

    private long localPositionForCastAbsolute(MediaController mediaController, long absolutePositionMs) {
        return localPositionForCastAbsolute(
            isContinuousEpisode(mediaController),
            absolutePositionMs,
            castPositionOffsetMs
        );
    }

    private void recoverDisconnectedCastIfNeeded(MediaController mediaController, RemoteMediaClient remote) {
        if (remote != null || castMediaId.isBlank()) {
            castMissingSinceMs = 0;
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (castMissingSinceMs == 0) {
            castMissingSinceMs = now;
            return;
        }
        if (now - castMissingSinceMs < 5_000) return;
        long positionMs = localPositionForCastAbsolute(mediaController, lastCastAbsolutePositionMs);
        boolean resume = lastCastWasPlaying;
        Log.w(TAG, "Cast disappeared; restoring local playback at " + positionMs + "ms");
        clearCastIdentity();
        mediaController.seekTo(positionMs);
        if (resume) mediaController.play(); else mediaController.pause();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                getActivity(),
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    @PluginMethod
    public void recognizeMusic(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "recognizeMusicAfterPermission");
            return;
        }
        captureAndRecognizeMusic(call);
    }

    @PermissionCallback
    public void recognizeMusicAfterPermission(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            call.reject("L'autorisation du microphone est nécessaire pour identifier le morceau");
            return;
        }
        captureAndRecognizeMusic(call);
    }

    private void captureAndRecognizeMusic(PluginCall call) {
        String apiUrl = call.getString("apiUrl", "").trim().replaceAll("/+$", "");
        if (!apiUrl.startsWith("https://") && !apiUrl.startsWith("http://")) {
            call.reject("Adresse du serveur de reconnaissance invalide");
            return;
        }
        MUSIC_RECOGNITION_EXECUTOR.execute(() -> {
            try {
                byte[] sample = captureMicrophoneWav(8);
                JSObject result = postRecognitionSample(apiUrl, sample);
                call.resolve(result);
            } catch (Exception error) {
                Log.w(TAG, "Reconnaissance musicale impossible", error);
                call.reject(error.getMessage() == null ? "Reconnaissance musicale impossible" : error.getMessage(), error);
            }
        });
    }

    private byte[] captureMicrophoneWav(int seconds) throws Exception {
        final int sampleRate = 16_000;
        final int channel = AudioFormat.CHANNEL_IN_MONO;
        final int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimum = AudioRecord.getMinBufferSize(sampleRate, channel, encoding);
        if (minimum <= 0) throw new IllegalStateException("Microphone indisponible");
        int bufferSize = Math.max(minimum, sampleRate / 2);
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channel, encoding, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("Microphone indisponible");
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(sampleRate * seconds * 2);
        byte[] buffer = new byte[bufferSize];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            recorder.startRecording();
            while (System.nanoTime() < deadline) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) pcm.write(buffer, 0, read);
            }
        } finally {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release();
        }
        if (pcm.size() < sampleRate * 2) throw new IllegalStateException("Pas assez d'audio capturé");
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.size() + 44);
        byte[] data = pcm.toByteArray();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + data.length);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16); header.putShort((short) 1); header.putShort((short) 1);
        header.putInt(sampleRate); header.putInt(sampleRate * 2); header.putShort((short) 2); header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)); header.putInt(data.length);
        wav.write(header.array()); wav.write(data);
        return wav.toByteArray();
    }

    private JSObject postRecognitionSample(String apiUrl, byte[] sample) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl + "/v1/music-recognition").openConnection();
        connection.setConnectTimeout(12_000); connection.setReadTimeout(35_000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "audio/wav");
        connection.setFixedLengthStreamingMode(sample.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(sample); }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) body.append(line);
        }
        JSONObject payload;
        try {
            payload = body.length() == 0 ? new JSONObject() : new JSONObject(body.toString());
        } catch (Exception error) {
            throw new IllegalStateException("Réponse invalide du serveur de reconnaissance", error);
        }
        if (status < 200 || status >= 300) throw new IllegalStateException(payload.optString("message", "Reconnaissance indisponible"));
        JSObject result = new JSObject();
        result.put("matched", payload.optBoolean("matched", false));
        result.put("title", payload.optString("title", ""));
        result.put("artist", payload.optString("artist", ""));
        result.put("album", payload.optString("album", ""));
        result.put("artworkUrl", payload.optString("artworkUrl", ""));
        result.put("spotifyUrl", payload.optString("spotifyUrl", ""));
        result.put("deezerUrl", payload.optString("deezerUrl", ""));
        result.put("appleMusicUrl", payload.optString("appleMusicUrl", ""));
        return result;
    }

    @PluginMethod
    public void load(PluginCall call) {
        ensureNotificationPermission();
        String url = call.getString("url");
        if (url == null || url.isBlank()) {
            call.reject("url est obligatoire");
            return;
        }
        String title = call.getString("title", "Podmix");
        String artist = call.getString("artist", "");
        String id = call.getString("id", url);
        boolean autoplay = Boolean.TRUE.equals(call.getBoolean("autoplay", false));
        withController(call, mediaController -> {
            playbackError = "";
            MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(ArtworkProvider.register(
                    getContext(),
                    call.getString("artworkUrl", "")
                ))
                .setExtras(liveMediaExtras(Boolean.TRUE.equals(call.getBoolean("live", false))))
                .build();
            MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setMediaId(id)
                .setUri(url)
                .setMediaMetadata(metadata);
            double clipStart = Math.max(0, call.getDouble("startPositionSeconds", 0.0));
            double clipEnd = call.getDouble("endPositionSeconds", -1.0);
            if (clipStart > 0 || clipEnd > clipStart) {
                MediaItem.ClippingConfiguration.Builder clipping = new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((long) (clipStart * 1000));
                if (clipEnd > clipStart) clipping.setEndPositionMs((long) (clipEnd * 1000));
                itemBuilder.setClippingConfiguration(clipping.build());
            }
            MediaItem item = itemBuilder.build();
            // A queue prepared for a remote output must stay silent locally.
            // Media3 preserves playWhenReady across media replacement, so an
            // explicit pause is required before replacing a playing item.
            mediaController.pause();
            mediaController.setMediaItem(item);
            mediaController.prepare();
            if (autoplay) {
                mediaController.play();
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        JSArray queue = call.getArray("items");
        if (queue == null || queue.length() == 0) {
            call.reject("items est obligatoire");
            return;
        }
        int startIndex = Math.max(0, call.getInt("startIndex", 0));
        double startPositionSeconds = Math.max(0, call.getDouble("startPositionSeconds", 0.0));
        boolean autoplay = Boolean.TRUE.equals(call.getBoolean("autoplay", false));
        try {
            List<MediaItem> items = new ArrayList<>();
            for (int index = 0; index < queue.length(); index++) {
                JSONObject source = queue.getJSONObject(index);
                String url = source.optString("url", "");
                if (url.isBlank()) continue;
                Bundle navigation = new Bundle();
                navigation.putString(MainActivity.EXTRA_SOURCE_ID, source.optString("podmixSourceId", ""));
                navigation.putString(MainActivity.EXTRA_EPISODE_ID, source.optString("podmixEpisodeId", ""));
                navigation.putBoolean("podmixContinuousEpisode", source.optBoolean("continuousEpisode", false));
                navigation.putBoolean("podmixTrackNavigation", source.optBoolean("trackNavigation", false));
                navigation.putBoolean("podmixLive", source.optBoolean("live", false));
                MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(source.optString("title", "Podmix"))
                    .setArtist(source.optString("artist", ""))
                    .setArtworkUri(ArtworkProvider.register(
                        getContext(),
                        source.optString("artworkUrl", "")
                    ))
                    .setExtras(navigation)
                    .build();
                MediaItem.Builder itemBuilder = new MediaItem.Builder()
                    .setMediaId(source.optString("id", url))
                    .setUri(url)
                    .setMediaMetadata(metadata);
                double clipStart = Math.max(0, source.optDouble("startPositionSeconds", 0.0));
                double clipEnd = source.optDouble("endPositionSeconds", -1.0);
                if (clipStart > 0 || clipEnd > clipStart) {
                    MediaItem.ClippingConfiguration.Builder clipping = new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((long) (clipStart * 1000));
                    if (clipEnd > clipStart) clipping.setEndPositionMs((long) (clipEnd * 1000));
                    itemBuilder.setClippingConfiguration(clipping.build());
                }
                items.add(itemBuilder.build());
            }
            if (items.isEmpty()) {
                call.reject("La file ne contient aucune URL valide");
                return;
            }
            withController(call, mediaController -> {
                playbackError = "";
                mediaController.setRepeatMode(repeatMode);
                mediaController.setShuffleModeEnabled(false);
                mediaController.pause();
                mediaController.setMediaItems(
                    items,
                    Math.min(startIndex, items.size() - 1),
                    (long) (startPositionSeconds * 1000)
                );
                mediaController.prepare();
                if (autoplay) mediaController.play();
                call.resolve(state(mediaController));
            });
        } catch (Exception error) {
            call.reject("File de lecture invalide", error);
        }
    }

    @PluginMethod
    public void getPendingPlaybackTarget(PluginCall call) {
        SharedPreferences preferences = getContext().getSharedPreferences(
            MainActivity.PLAYBACK_TARGET_PREFERENCES,
            Context.MODE_PRIVATE
        );
        JSObject result = new JSObject();
        result.put("sourceId", preferences.getString(MainActivity.EXTRA_SOURCE_ID, ""));
        result.put("episodeId", preferences.getString(MainActivity.EXTRA_EPISODE_ID, ""));
        preferences.edit().clear().apply();
        call.resolve(result);
    }

    @PluginMethod
    public void setRepeatMode(PluginCall call) {
        int mode = Math.max(0, Math.min(2, call.getInt("mode", 0)));
        repeatMode = mode == 1 ? Player.REPEAT_MODE_ONE : mode == 2 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF;
        withController(call, mediaController -> {
            mediaController.setRepeatMode(repeatMode);
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void next(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            restoreCastIdentityIfNeeded(remote);
            if (remote != null && !castMediaId.isBlank()) {
                if (isContinuousEpisode(mediaController)) {
                    navigateContinuousTrack(call, mediaController, remote, 1);
                    return;
                } else if (isPhysicalTrackQueue(mediaController) && mediaController.hasNextMediaItem()) {
                    mediaController.seekToNextMediaItem();
                    loadControllerItemOnCast(remote, mediaController, true);
                }
            } else if (isContinuousEpisode(mediaController)) {
                navigateContinuousTrack(call, mediaController, null, 1);
                return;
            } else if (isPhysicalTrackQueue(mediaController) && mediaController.hasNextMediaItem()) {
                mediaController.seekToNextMediaItem();
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void previous(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            restoreCastIdentityIfNeeded(remote);
            if (remote != null && !castMediaId.isBlank()) {
                if (isContinuousEpisode(mediaController)) {
                    navigateContinuousTrack(call, mediaController, remote, -1);
                    return;
                } else if (isPhysicalTrackQueue(mediaController) && mediaController.hasPreviousMediaItem()) {
                    mediaController.seekToPreviousMediaItem();
                    loadControllerItemOnCast(remote, mediaController, true);
                }
            } else if (isContinuousEpisode(mediaController)) {
                navigateContinuousTrack(call, mediaController, null, -1);
                return;
            } else if (isPhysicalTrackQueue(mediaController) && mediaController.hasPreviousMediaItem()) {
                mediaController.seekToPreviousMediaItem();
            }
            call.resolve(state(mediaController));
        });
    }

    private void navigateContinuousTrack(
        PluginCall call,
        MediaController mediaController,
        RemoteMediaClient remote,
        int direction
    ) {
        Bundle args = new Bundle();
        if (remote != null) {
            args.putLong(
                PodmixPlaybackService.ARG_ABSOLUTE_POSITION_MS,
                Math.max(0, remote.getApproximateStreamPosition())
            );
        }
        String action = direction > 0
            ? PodmixPlaybackService.ACTION_SEEK_NEXT_TRACK
            : PodmixPlaybackService.ACTION_SEEK_PREVIOUS_TRACK;
        long beforeMs = Math.max(0, mediaController.getCurrentPosition());
        ListenableFuture<SessionResult> navigation = mediaController.sendCustomCommand(
            new SessionCommand(action, Bundle.EMPTY),
            args
        );
        navigation.addListener(() -> {
            try {
                SessionResult result = navigation.get();
                if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                    call.reject("Aucun morceau disponible dans cette direction");
                    return;
                }
                if (remote != null) {
                    loadControllerItemOnCast(remote, mediaController, true);
                }
                Log.i(TAG, "Track command direction=" + direction + " position="
                    + beforeMs + " -> " + mediaController.getCurrentPosition());
                call.resolve(state(mediaController));
            } catch (Exception error) {
                call.reject("Changement de morceau impossible", error);
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private boolean isContinuousEpisode(MediaController mediaController) {
        MediaItem item = mediaController.getCurrentMediaItem();
        return item != null
            && item.mediaMetadata.extras != null
            && item.mediaMetadata.extras.getBoolean("podmixContinuousEpisode", false);
    }

    private static Bundle liveMediaExtras(boolean live) {
        Bundle extras = new Bundle();
        extras.putBoolean("podmixLive", live);
        return extras;
    }

    private boolean isPhysicalTrackQueue(MediaController mediaController) {
        MediaItem item = mediaController.getCurrentMediaItem();
        return item != null
            && item.mediaMetadata.extras != null
            && item.mediaMetadata.extras.getBoolean("podmixTrackNavigation", false);
    }

    @PluginMethod
    public void play(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            restoreCastIdentityIfNeeded(remote);
            if (remote != null && !castMediaId.isBlank()) {
                lastCastWasPlaying = true;
                lastCastAbsolutePositionMs = Math.max(0, remote.getApproximateStreamPosition());
                persistCastIdentity(true);
                remote.play();
            } else {
                mediaController.play();
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            restoreCastIdentityIfNeeded(remote);
            if (remote != null && !castMediaId.isBlank()) {
                lastCastWasPlaying = false;
                lastCastAbsolutePositionMs = Math.max(0, remote.getApproximateStreamPosition());
                persistCastIdentity(true);
                remote.pause();
            } else {
                mediaController.pause();
            }
            JSObject result = state(mediaController);
            result.put("playing", false);
            result.put("playRequested", false);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        double positionSeconds = call.getDouble("positionSeconds", 0.0);
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            restoreCastIdentityIfNeeded(remote);
            if (remote != null && !castMediaId.isBlank()) {
                long target = castPositionOffsetMs + Math.max(0, (long) (positionSeconds * 1000));
                if (castEndPositionMs > castPositionOffsetMs) target = Math.min(target, castEndPositionMs);
                lastCastAbsolutePositionMs = target;
                persistCastIdentity(true);
                remote.seek(target);
            } else {
                long targetMs = Math.max(0, (long) (positionSeconds * 1000));
                MediaItem currentItem = mediaController.getCurrentMediaItem();
                if (currentItem != null && currentItem.mediaMetadata.extras != null
                    && currentItem.mediaMetadata.extras.getBoolean("podmixContinuousEpisode", false)) {
                    PodmixPlaybackService.TrackState trackState = PodmixPlaybackService.currentTrackState(
                        currentItem.mediaId,
                        mediaController.getCurrentPosition(),
                        mediaController.getDuration()
                    );
                    if (trackState != null) targetMs += trackState.startMs;
                }
                mediaController.seekTo(targetMs);
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void getState(PluginCall call) {
        withController(call, mediaController -> call.resolve(state(mediaController)));
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        double volume = call.getDouble("volume", 1.0);
        final float clampedVolume = (float) Math.max(0.0, Math.min(1.0, volume));
        withController(call, mediaController -> {
            mediaController.setVolume(clampedVolume);
            JSObject result = new JSObject();
            result.put("volume", clampedVolume);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void getVolume(PluginCall call) {
        withController(call, mediaController -> {
            JSObject result = new JSObject();
            result.put("volume", mediaController.getVolume());
            call.resolve(result);
        });
    }

    @PluginMethod
    public void getAppInfo(PluginCall call) {
        try {
            android.content.pm.PackageInfo packageInfo = getContext()
                .getPackageManager()
                .getPackageInfo(getContext().getPackageName(), 0);
            JSObject result = new JSObject();
            result.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
            result.put("versionCode", android.os.Build.VERSION.SDK_INT >= 28
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode);
            result.put("lastUpdateTime", packageInfo.lastUpdateTime);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Informations de version indisponibles", error);
        }
    }

    @PluginMethod
    public void cacheArtwork(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || url.isBlank()) {
            call.reject("url est obligatoire");
            return;
        }
        // Une image ne doit jamais bloquer le fil d'interface du WebView.
        // La file unique limite aussi les téléchargements simultanés au
        // démarrage, tout en gardant les fichiers sur le stockage persistant.
        ARTWORK_PREFETCH_EXECUTOR.execute(() -> {
            try {
                Uri uri = ArtworkProvider.prefetch(getContext(), url);
                if (uri == null) {
                    call.reject("URL d'illustration invalide");
                    return;
                }
                JSObject result = new JSObject();
                result.put("uri", uri.toString());
                String dataUrl = ArtworkProvider.webDataUrl(getContext(), url);
                if (dataUrl != null) result.put("dataUrl", dataUrl);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Mise en cache de l'illustration impossible", error);
            }
        });
    }

    @PluginMethod
    public void beginLibrarySync(PluginCall call) {
        String syncId = call.getString("syncId", "").trim();
        if (syncId.isEmpty()) {
            call.reject("syncId est obligatoire");
            return;
        }
        synchronized (LIBRARY_SYNC_LOCK) {
            try {
                File staging = new File(getContext().getFilesDir(), LIBRARY_STAGING_FILE);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(staging, false)) {
                    output.write('[');
                }
                getContext().getSharedPreferences("podmix-library", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pendingSyncId", syncId)
                    .putInt("pendingCount", 0)
                    .commit();
                JSObject result = new JSObject();
                result.put("ok", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Initialisation de la bibliothèque Android Auto impossible", error);
            }
        }
    }

    @PluginMethod
    public void appendLibraryChunk(PluginCall call) {
        String syncId = call.getString("syncId", "").trim();
        JSArray items = call.getArray("items");
        if (syncId.isEmpty() || items == null) {
            call.reject("syncId et items sont obligatoires");
            return;
        }
        synchronized (LIBRARY_SYNC_LOCK) {
            SharedPreferences preferences = getContext().getSharedPreferences(
                "podmix-library", Context.MODE_PRIVATE
            );
            if (!syncId.equals(preferences.getString("pendingSyncId", ""))) {
                call.reject("Synchronisation Android Auto périmée");
                return;
            }
            int previousCount = preferences.getInt("pendingCount", 0);
            StringBuilder serialized = new StringBuilder();
            Set<String> registeredArtwork = new HashSet<>();
            int appended = 0;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                if (previousCount + appended > 0) serialized.append(',');
                serialized.append(item);
                appended++;
                String artworkUrl = item.optString("artworkUrl", "");
                if (!artworkUrl.isEmpty() && registeredArtwork.add(artworkUrl)) {
                    ArtworkProvider.register(getContext(), artworkUrl);
                }
            }
            try {
                File staging = new File(getContext().getFilesDir(), LIBRARY_STAGING_FILE);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(staging, true)) {
                    output.write(serialized.toString().getBytes(StandardCharsets.UTF_8));
                }
                int totalCount = previousCount + appended;
                preferences.edit().putInt("pendingCount", totalCount).commit();
                JSObject result = new JSObject();
                result.put("count", totalCount);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Écriture d'un bloc Android Auto impossible", error);
            }
        }
    }

    @PluginMethod
    public void commitLibrarySync(PluginCall call) {
        String syncId = call.getString("syncId", "").trim();
        Integer expectedCountValue = call.getInt("expectedCount");
        int expectedCount = expectedCountValue == null ? -1 : expectedCountValue;
        synchronized (LIBRARY_SYNC_LOCK) {
            SharedPreferences preferences = getContext().getSharedPreferences(
                "podmix-library", Context.MODE_PRIVATE
            );
            int actualCount = preferences.getInt("pendingCount", -1);
            if (!syncId.equals(preferences.getString("pendingSyncId", ""))) {
                call.reject("Synchronisation Android Auto périmée");
                return;
            }
            if (expectedCount < 0 || actualCount != expectedCount) {
                call.reject("Bibliothèque Android Auto incomplète : " + actualCount + "/" + expectedCount);
                return;
            }
            File staging = new File(getContext().getFilesDir(), LIBRARY_STAGING_FILE);
            File target = new File(getContext().getFilesDir(), LIBRARY_FILE);
            AtomicFile atomicFile = new AtomicFile(target);
            java.io.FileOutputStream targetOutput = null;
            try {
                try (java.io.FileOutputStream stagingOutput = new java.io.FileOutputStream(staging, true)) {
                    stagingOutput.write(']');
                    stagingOutput.getFD().sync();
                }
                targetOutput = atomicFile.startWrite();
                try (java.io.FileInputStream input = new java.io.FileInputStream(staging)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) targetOutput.write(buffer, 0, read);
                    }
                }
                targetOutput.getFD().sync();
                atomicFile.finishWrite(targetOutput);
                targetOutput = null;
                long bytes = target.length();
                preferences.edit()
                    .remove("pendingSyncId")
                    .remove("pendingCount")
                    .putInt("count", actualCount)
                    .putLong("bytes", bytes)
                    .putLong("version", System.currentTimeMillis())
                    .commit();
                if (!staging.delete()) staging.deleteOnExit();
                PodmixPlaybackService.invalidateCache();
                Log.i(TAG, "Android Auto library committed: " + actualCount + " items, " + bytes + " bytes");
                JSObject result = new JSObject();
                result.put("count", actualCount);
                result.put("bytes", bytes);
                call.resolve(result);
            } catch (Exception error) {
                if (targetOutput != null) atomicFile.failWrite(targetOutput);
                call.reject("Validation de la bibliothèque Android Auto impossible", error);
            }
        }
    }

    @PluginMethod
    public void syncFavorites(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null) {
            call.reject("ids est obligatoire");
            return;
        }
        getContext().getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE)
            .edit()
            .putString("trackIds", ids.toString())
            .putBoolean("initialized", true)
            .putLong("version", System.currentTimeMillis())
            .apply();
        JSObject result = new JSObject();
        result.put("count", ids.length());
        call.resolve(result);
    }

    @PluginMethod
    public void getFavorites(PluginCall call) {
        SharedPreferences preferences =
            getContext().getSharedPreferences("podmix-favorites", Context.MODE_PRIVATE);
        JSObject result = new JSObject();
        try {
            result.put("ids", new JSArray(
                preferences.getString("trackIds", "[]")
            ));
        } catch (Exception error) {
            result.put("ids", new JSArray());
        }
        result.put("initialized", preferences.getBoolean("initialized", false));
        call.resolve(result);
    }

    @PluginMethod
    public void syncResume(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null) {
            call.reject("items est obligatoire");
            return;
        }
        JSArray completedEpisodeIds = call.getArray("completedEpisodeIds");
        SharedPreferences.Editor completedEditor = getContext()
            .getSharedPreferences("podmix-completed-episodes", Context.MODE_PRIVATE)
            .edit();
        if (completedEpisodeIds != null) {
            for (int index = 0; index < completedEpisodeIds.length(); index++) {
                try {
                    String episodeId = completedEpisodeIds.getString(index);
                    if (episodeId != null && !episodeId.isBlank()) {
                        completedEditor.putBoolean(episodeId, true);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        completedEditor.apply();
        getContext().getSharedPreferences("podmix-resume", Context.MODE_PRIVATE)
            .edit()
            .putString("items", items.toString())
            .putLong("version", System.currentTimeMillis())
            .apply();
        // La position évolue pendant toute la lecture. N'invalider ici que la
        // petite liste de reprises : vider le cache global reparsait le fichier
        // Android Auto de plusieurs mégaoctets à chaque seconde.
        PodmixPlaybackService.invalidateResumeCache();
        JSObject result = new JSObject();
        result.put("count", items.length());
        call.resolve(result);
    }

    @PluginMethod
    public void getCompletedEpisodeIds(PluginCall call) {
        SharedPreferences preferences = getContext()
            .getSharedPreferences("podmix-completed-episodes", Context.MODE_PRIVATE);
        JSArray ids = new JSArray();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) ids.put(entry.getKey());
        }
        JSObject result = new JSObject();
        result.put("ids", ids);
        call.resolve(result);
    }

    @PluginMethod
    public void syncSubscriptions(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null) {
            call.reject("items est obligatoire");
            return;
        }
        getContext().getSharedPreferences("podmix-subscriptions", Context.MODE_PRIVATE)
            .edit()
            .putString("items", items.toString())
            .apply();
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            FeedRefreshWorker.class, 6, TimeUnit.HOURS
        ).setConstraints(constraints).build();
        WorkManager.getInstance(getContext()).enqueueUniquePeriodicWork(
            "podmix-feed-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        );
        JSObject result = new JSObject();
        result.put("count", items.length());
        call.resolve(result);
    }

    @PluginMethod
    public void extractTracks(PluginCall call) {
        String episodeId = call.getString("episodeId");
        String audioPath = call.getString("audioPath");
        JSArray tracks = call.getArray("tracks");
        if (episodeId == null || audioPath == null || tracks == null) {
            call.reject("episodeId, audioPath et tracks sont obligatoires");
            return;
        }
        File episodeDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "podmix/tracks/" + episodeId);
        if (!episodeDir.exists()) episodeDir.mkdirs();
        new Thread(() -> {
            try {
                JSONArray tracksArray = new JSONArray();
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject track = tracks.getJSONObject(i);
                    double start = track.optDouble("start", 0.0);
                    double end = track.optDouble("end", -1.0);
                    String trackId = track.optString("id", String.valueOf(i));
                    File outputFile = new File(episodeDir, trackId + ".mp3");
                    if (outputFile.exists() && outputFile.length() > 0) {
                        JSONObject result = new JSONObject();
                        result.put("trackId", trackId);
                        result.put("path", outputFile.getAbsolutePath());
                        result.put("status", "exists");
                        tracksArray.put(result);
                        continue;
                    }
                    List<String> cmd = new ArrayList<>();
                    cmd.add("ffmpeg");
                    cmd.add("-y");
                    cmd.add("-hide_banner");
                    cmd.add("-loglevel");
                    cmd.add("error");
                    cmd.add("-ss");
                    cmd.add(String.format("%.3f", start));
                    cmd.add("-i");
                    cmd.add(audioPath);
                    if (end > start) {
                        cmd.add("-t");
                        cmd.add(String.format("%.3f", end - start));
                    }
                    cmd.add("-map");
                    cmd.add("0:a:0");
                    cmd.add("-vn");
                    cmd.add("-c:a");
                    cmd.add("libmp3lame");
                    cmd.add("-b:a");
                    cmd.add("160k");
                    cmd.add(outputFile.getAbsolutePath());
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    Process process = pb.start();
                    int exitCode = process.waitFor();
                    JSONObject result = new JSONObject();
                    result.put("trackId", trackId);
                    result.put("path", outputFile.getAbsolutePath());
                    result.put("status", exitCode == 0 ? "extracted" : "failed");
                    tracksArray.put(result);
                }
                JSObject response = new JSObject();
                response.put("tracks", tracksArray);
                call.resolve(response);
            } catch (Exception error) {
                call.reject("Extraction des pistes impossible", error);
            }
        }, "podmix-extract-tracks").start();
    }

    @PluginMethod
    public void getStorage(PluginCall call) {
        File directory = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "podmix");
        if (!directory.exists()) directory.mkdirs();
        long downloaded = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) if (file.isFile()) downloaded += file.length();
        }
        JSObject result = new JSObject();
        result.put("downloadedBytes", downloaded);
        result.put("availableBytes", directory.getUsableSpace());
        result.put("totalBytes", directory.getTotalSpace());
        call.resolve(result);
    }

    @PluginMethod
    public void searchTracklists1001(PluginCall call) {
        String query = call.getString("query", "").replaceAll("\\s+", " ").trim();
        if (query.length() < 3 || query.length() > 180) {
            call.reject("La recherche 1001Tracklists doit contenir entre 3 et 180 caractères");
            return;
        }
        new Thread(() -> {
            String diagnostic = "";
            try {
                // L'endpoint AJAX retourne directement les identifiants et URLs
                // des tracklists. Il est plus fiable que la page de formulaire,
                // qui est aujourd'hui une recherche d'artistes et peut ne pas
                // afficher les émissions radio correspondantes.
                JSObject ajaxResult = search1001Ajax(query);
                if (ajaxResult.getJSONArray("candidates").length() > 0) {
                    call.resolve(ajaxResult);
                    return;
                }
                diagnostic = "API 1001 reçue, sans résultat exploitable";
            } catch (Exception error) {
                diagnostic = "API " + error.getClass().getSimpleName() + " : " + error.getMessage();
                Log.w(TAG, "Recherche AJAX 1001Tracklists bloquée, essai HTML", error);
            }
            try {
                Request request = new Request.Builder()
                    .url("https://www.1001tracklists.com/search/result.php")
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                    .header("Referer", "https://www.1001tracklists.com/")
                    .post(new FormBody.Builder()
                        .add("search_selection", "1")
                        .add("main_search", query)
                        .build())
                    .build();
                try (Response response = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(25, TimeUnit.SECONDS)
                    .build().newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IllegalStateException("HTTP " + response.code());
                    }
                    String html = response.body().string();
                    JSObject result = parse1001SearchHtml(html);
                    if (result.getJSONArray("candidates").length() > 0) {
                        call.resolve(result);
                        return;
                    }
                    diagnostic = diagnostic + " · HTML reçu, sans résultat exploitable";
                }
            } catch (Exception error) {
                diagnostic = diagnostic + " · HTML " + error.getClass().getSimpleName() + " : " + error.getMessage();
                Log.w(TAG, "Recherche HTTP 1001Tracklists bloquée, essai WebView", error);
            }
            String finalDiagnostic = diagnostic;
            getActivity().runOnUiThread(() -> search1001WithWebView(call, query, finalDiagnostic));
        }, "podmix-1001-search").start();
    }

    private JSObject search1001Ajax(String query) throws Exception {
        HttpUrl url = new HttpUrl.Builder()
            .scheme("https")
            .host("www.1001tracklists.com")
            .addPathSegments("ajax/search_tracklist.php")
            .addQueryParameter("p", query)
            .addQueryParameter("noIDFieldCheck", "true")
            .addQueryParameter("fixedMode", "true")
            .addQueryParameter("sf", "p")
            .build();
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .header("Referer", "https://www.1001tracklists.com/")
            .build();
        try (Response response = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return parse1001SearchAjax(response.body().string());
        }
    }

    private JSObject parse1001SearchAjax(String rawJson) throws Exception {
        JSONObject payload = new JSONObject(rawJson);
        JSONArray data = payload.optJSONArray("data");
        JSArray candidates = new JSArray();
        Set<String> seen = new HashSet<>();
        if (data != null) {
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.optJSONObject(index);
                JSONObject properties = item != null ? item.optJSONObject("properties") : null;
                if (properties == null) continue;
                String uniqueId = properties.optString("id_unique", "").trim();
                String urlName = properties.optString("url_name", "").trim().replaceAll("\\s+", "-");
                if (uniqueId.isEmpty() || urlName.isEmpty()) continue;
                try {
                    String tracklistUrl = validate1001Url(
                        "https://www.1001tracklists.com/tracklist/" + uniqueId + "/" + urlName + ".html"
                    );
                    if (!seen.add(tracklistUrl)) continue;
                    JSObject candidate = new JSObject();
                    candidate.put("url", tracklistUrl);
                    candidate.put("title", properties.optString("title", urlName).trim());
                    candidate.put("domain", "1001tracklists.com");
                    candidates.put(candidate);
                    if (candidates.length() >= 20) break;
                } catch (Exception ignored) {
                    // Les éléments hors format tracklist restent exclus.
                }
            }
        }
        JSObject result = new JSObject();
        result.put("candidates", candidates);
        return result;
    }

    private JSObject parse1001SearchHtml(String html) {
        Document document = Jsoup.parse(html, "https://www.1001tracklists.com/");
        JSArray candidates = new JSArray();
        Set<String> seen = new HashSet<>();
        for (Element anchor : document.select("a[href*=/tracklist/]")) {
            try {
                String url = validate1001Url(anchor.absUrl("href"));
                if (!seen.add(url)) continue;
                JSObject candidate = new JSObject();
                candidate.put("url", url);
                candidate.put("title", anchor.text().trim());
                candidate.put("domain", "1001tracklists.com");
                candidates.put(candidate);
                if (candidates.length() >= 10) break;
            } catch (Exception ignored) {
                // Les liens hors tracklist sont volontairement ignorés.
            }
        }
        JSObject result = new JSObject();
        result.put("candidates", candidates);
        return result;
    }

    private void search1001WithWebView(PluginCall call, String query, String httpDiagnostic) {
        Handler handler = new Handler(Looper.getMainLooper());
        WebView webView = new WebView(getContext());
        FrameLayout host = new FrameLayout(getActivity());
        host.setVisibility(View.INVISIBLE);
        host.setAlpha(0.01f);
        host.addView(webView, new FrameLayout.LayoutParams(1, 1));
        ViewGroup content = getActivity().findViewById(android.R.id.content);
        content.addView(host);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean polling = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger(0);
        Runnable[] poll = new Runnable[1];
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                detachWebView(webView, host);
                call.reject("Recherche 1001Tracklists expirée" + diagnosticSuffix(httpDiagnostic));
            }
        };
        handler.postDelayed(timeout, 30000);
        poll[0] = () -> {
            if (finished.get()) return;
            webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()", raw -> {
                if (finished.get()) return;
                try {
                    String html = new JSONArray("[" + raw + "]").getString(0);
                    JSObject result = parse1001SearchHtml(html);
                    if (result.getJSONArray("candidates").length() > 0 && finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout);
                        detachWebView(webView, host);
                        call.resolve(result);
                        return;
                    }
                } catch (Exception error) {
                    Log.d(TAG, "Recherche 1001Tracklists pas encore exploitable", error);
                }
                if (attempts.incrementAndGet() >= 24) {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout);
                        detachWebView(webView, host);
                        call.reject("Recherche 1001Tracklists sans résultat" + diagnosticSuffix(httpDiagnostic));
                    }
                } else {
                    handler.postDelayed(poll[0], 1000);
                }
            });
        };
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                if (polling.compareAndSet(false, true)) handler.postDelayed(poll[0], 700);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && finished.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout);
                    detachWebView(webView, host);
                    call.reject("Recherche 1001Tracklists impossible : " + error.getDescription()
                        + diagnosticSuffix(httpDiagnostic));
                }
            }
        });
        try {
            String form = "search_selection=1&main_search="
                + URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            webView.postUrl(
                "https://www.1001tracklists.com/search/result.php",
                form.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception error) {
            detachWebView(webView, host);
            call.reject("Préparation de la recherche 1001Tracklists impossible", error);
        }
    }

    @PluginMethod
    public void fetchTracklist1001(PluginCall call) {
        String rawUrl = call.getString("url", "");
        String address = call.getString("address", "");
        final String url;
        try {
            url = validate1001Url(rawUrl);
        } catch (Exception error) {
            call.reject("URL 1001Tracklists invalide", error);
            return;
        }
        new Thread(() -> {
            String httpDiagnostic = "";
            try {
                String html = fetch1001Html(url, address);
                JSObject result = parse1001Html(html, url);
                if (result != null) {
                    call.resolve(result);
                    return;
                }
                Document document = Jsoup.parse(html, url);
                httpDiagnostic = "HTTP reçu, mais aucune piste trouvée"
                    + " (titre : " + document.title()
                    + ", taille : " + html.length() + " caractères)";
            } catch (Exception error) {
                httpDiagnostic = error.getClass().getSimpleName() + " : " + error.getMessage();
                Log.w(TAG, "Accès HTTP 1001Tracklists bloqué, essai WebView", error);
            }
            String diagnostic = httpDiagnostic;
            getActivity().runOnUiThread(() -> fetch1001WithWebView(call, url, diagnostic));
        }, "podmix-1001-http").start();
    }

    @PluginMethod
    public void fetchPublishedTracklist(PluginCall call) {
        String rawUrl = call.getString("url", "");
        final String url;
        try {
            url = validatePublishedMediaUrl(rawUrl);
        } catch (Exception error) {
            call.reject("URL média publiée invalide", error);
            return;
        }
        Log.i(TAG, "fetchPublishedTracklist start " + url);
        getActivity().runOnUiThread(() -> fetchPublishedWithWebView(call, url));
    }

    @PluginMethod
    public void startWebTimestampWorker(PluginCall call) {
        String apiUrl = call.getString("apiUrl", "").trim();
        if (!apiUrl.matches("^https?://[^\\s]+$")) {
            call.reject("Adresse VPS invalide");
            return;
        }
        Intent intent = new Intent(getContext(), WebTimestampService.class)
            .putExtra(WebTimestampService.EXTRA_API_URL, apiUrl);
        ContextCompat.startForegroundService(getContext(), intent);
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    private String validatePublishedMediaUrl(String value) throws Exception {
        URI uri = URI.create(value.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
            || !(host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("youtu.be")
                || host.equals("soundcloud.com") || host.equals("www.soundcloud.com")
                || host.equals("mixcloud.com") || host.equals("www.mixcloud.com"))) {
            throw new IllegalArgumentException("Seuls YouTube, SoundCloud et Mixcloud en HTTPS sont acceptés");
        }
        return uri.toString();
    }

    private void fetchPublishedWithWebView(PluginCall call, String url) {
        Handler handler = new Handler(Looper.getMainLooper());
        WebView webView = new WebView(getContext());
        FrameLayout host = new FrameLayout(getActivity());
        host.setVisibility(View.INVISIBLE);
        host.setAlpha(0.01f);
        host.addView(webView, new FrameLayout.LayoutParams(1, 1));
        ViewGroup content = getActivity().findViewById(android.R.id.content);
        content.addView(host);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean polling = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger(0);
        Runnable[] poll = new Runnable[1];
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                Log.w(TAG, "fetchPublishedTracklist timeout " + url);
                detachWebView(webView, host);
                call.reject("Chargement média publié expiré");
            }
        };
        handler.postDelayed(timeout, 45000);
        poll[0] = () -> {
            if (finished.get()) return;
            webView.evaluateJavascript(
                "(function(){"
                    + "var more=[].slice.call(document.querySelectorAll('tp-yt-paper-button,button')).filter(function(b){return /more|plus|show more|afficher plus/i.test(b.innerText||'')})[0];"
                    + "if(more) more.click();"
                    + "return document.title+'\\n'+document.body.innerText;"
                    + "})()",
                raw -> {
                    if (finished.get()) return;
                    try {
                        String text = new JSONArray("[" + raw + "]").getString(0);
                        JSObject result = parsePublishedText(text, url);
                        if (result != null && finished.compareAndSet(false, true)) {
                            Log.i(TAG, "fetchPublishedTracklist success " + url
                                + " tracks=" + result.getJSONArray("tracks").length()
                                + " textLength=" + text.length());
                            handler.removeCallbacks(timeout);
                            detachWebView(webView, host);
                            call.resolve(result);
                            return;
                        }
                    } catch (Exception error) {
                        Log.d(TAG, "Page média publiée pas encore exploitable", error);
                    }
                    if (attempts.incrementAndGet() >= 36) {
                        if (finished.compareAndSet(false, true)) {
                            Log.w(TAG, "fetchPublishedTracklist no timestamped tracks " + url);
                            handler.removeCallbacks(timeout);
                            detachWebView(webView, host);
                            call.reject("Aucune tracklist timestampée trouvée dans la page publiée");
                        }
                    } else {
                        handler.postDelayed(poll[0], 1000);
                    }
                }
            );
        };
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                if (polling.compareAndSet(false, true)) handler.postDelayed(poll[0], 1600);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && finished.compareAndSet(false, true)) {
                    Log.w(TAG, "fetchPublishedTracklist page error " + url + " : " + error.getDescription());
                    handler.removeCallbacks(timeout);
                    detachWebView(webView, host);
                    call.reject("Chargement média publié impossible : " + error.getDescription());
                }
            }
        });
        webView.loadUrl(url);
    }

    private JSObject parsePublishedText(String text, String sourceUrl) {
        JSArray tracks = new JSArray();
        Set<String> seen = new HashSet<>();
        int durationSeconds = 0;
        Pattern linePattern = Pattern.compile("(?<!\\d)(\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)\\s+(.{4,180})");
        Pattern trailingPattern = Pattern.compile("(.{4,180}?)\\s*[\\[(](\\d{1,2}:\\d{2}(?::\\d{2})?)[\\])]\\s*:?[\\s]*$");
        for (String rawLine : text.split("\\n")) {
            String line = rawLine.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            Matcher matcher = linePattern.matcher(line);
            Matcher trailingMatcher = trailingPattern.matcher(line);
            boolean leading = matcher.find();
            boolean trailing = !leading && trailingMatcher.find();
            if (!leading && !trailing) continue;
            String timestamp = trailing ? trailingMatcher.group(2) : matcher.group(1);
            double providedTime = parseClock(timestamp);
            if (providedTime < 0) continue;
            String value = (trailing ? trailingMatcher.group(1) : matcher.group(2))
                .replaceAll("^(\\d{1,3}[.)\\-:]\\s*)", "")
                .replaceAll("\\s+(Listen|Play|Reply|Like|Show less|Show more).*$", "")
                .trim();
            String[] identity = splitArtistTitle(value);
            if (identity[1].isBlank() || identity[0].equals("Artiste inconnu")) continue;
            String key = (identity[0] + "|" + identity[1] + "|" + providedTime).toLowerCase(Locale.ROOT);
            if (!seen.add(key)) continue;
            JSObject track = new JSObject();
            track.put("artist", identity[0]);
            track.put("title", identity[1]);
            if (providedTime >= 0) {
                track.put("providedTime", providedTime);
            } else {
                track.put("providedTime", JSONObject.NULL);
            }
            tracks.put(track);
            durationSeconds = Math.max(durationSeconds, (int) providedTime);
            if (tracks.length() >= 80) break;
        }
        if (tracks.length() < 3) return null;
        JSObject result = new JSObject();
        result.put("sourceUrl", sourceUrl);
        result.put("pageTitle", "");
        result.put("durationSeconds", durationSeconds);
        result.put("tracks", tracks);
        return result;
    }

    public static String validate1001Url(String value) throws Exception {
        URI uri = URI.create(value.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || (!host.equals("1001tracklists.com") && !host.equals("www.1001tracklists.com"))
            || !uri.getPath().matches("^/tracklist/[a-z0-9]+/[^/?#]+\\.html$")) {
            throw new IllegalArgumentException("Seules les pages HTTPS /tracklist/… sont acceptées");
        }
        return "https://www.1001tracklists.com" + uri.getPath();
    }

    public static String fetch1001Html(String url, String rawAddress) throws Exception {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true);
        String address = rawAddress == null ? "" : rawAddress.trim();
        if (!address.isBlank()) {
            if (!address.matches("^[0-9a-fA-F:.]+$")) throw new IllegalArgumentException("Adresse serveur invalide");
            InetAddress resolved = InetAddress.getByName(address);
            if (resolved.isAnyLocalAddress() || resolved.isLoopbackAddress() || resolved.isLinkLocalAddress()
                || resolved.isSiteLocalAddress() || resolved.isMulticastAddress()) {
                throw new IllegalArgumentException("Adresse serveur non publique");
            }
            builder.dns(hostname -> {
                String normalized = hostname.toLowerCase(Locale.ROOT);
                if (normalized.equals("1001tracklists.com") || normalized.equals("www.1001tracklists.com")) {
                    return java.util.Collections.singletonList(resolved);
                }
                return Dns.SYSTEM.lookup(hostname);
            });
        }
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .header("Referer", "https://www.1001tracklists.com/")
            .build();
        try (Response response = builder.build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String html = response.body().string();
            if (html.length() > 6_000_000) throw new IllegalStateException("Page trop volumineuse");
            return html;
        }
    }

    private void fetch1001WithWebView(PluginCall call, String url, String httpDiagnostic) {
        Handler handler = new Handler(Looper.getMainLooper());
        WebView webView = new WebView(getContext());
        FrameLayout host = new FrameLayout(getActivity());
        host.setVisibility(View.INVISIBLE);
        host.setAlpha(0.01f);
        host.addView(webView, new FrameLayout.LayoutParams(1, 1));
        ViewGroup content = getActivity().findViewById(android.R.id.content);
        content.addView(host);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean polling = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger(0);
        Runnable[] poll = new Runnable[1];
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                detachWebView(webView, host);
                call.reject("Chargement 1001Tracklists expiré" + diagnosticSuffix(httpDiagnostic));
            }
        };
        handler.postDelayed(timeout, 30000);
        poll[0] = () -> {
            if (finished.get()) return;
            webView.evaluateJavascript(
                "(function(){return document.documentElement.outerHTML;})()",
                raw -> {
                    if (finished.get()) return;
                    try {
                        String html = new JSONArray("[" + raw + "]").getString(0);
                        JSObject result = parse1001Html(html, url);
                        if (result != null && finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(timeout);
                            detachWebView(webView, host);
                            call.resolve(result);
                            return;
                        }
                    } catch (Exception error) {
                        Log.d(TAG, "Page 1001Tracklists pas encore exploitable", error);
                    }
                    if (attempts.incrementAndGet() >= 24) {
                        if (finished.compareAndSet(false, true)) {
                            handler.removeCallbacks(timeout);
                            detachWebView(webView, host);
                            call.reject("1001Tracklists n’a renvoyé aucune tracklist exploitable"
                                + diagnosticSuffix(httpDiagnostic));
                        }
                    } else {
                        handler.postDelayed(poll[0], 1000);
                    }
                }
            );
        };
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                if (polling.compareAndSet(false, true)) handler.postDelayed(poll[0], 700);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && finished.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout);
                    detachWebView(webView, host);
                    call.reject("Chargement 1001Tracklists impossible : " + error.getDescription()
                        + diagnosticSuffix(httpDiagnostic));
                }
            }
        });
        webView.loadUrl(url);
    }

    private void detachWebView(WebView webView, ViewGroup host) {
        try {
            host.removeView(webView);
            ViewParent parent = host.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(host);
            webView.stopLoading();
            webView.destroy();
        } catch (Exception error) {
            Log.d(TAG, "Nettoyage WebView 1001 incomplet", error);
        }
    }

    private String diagnosticSuffix(String diagnostic) {
        return diagnostic == null || diagnostic.isBlank() ? "" : " — accès natif : " + diagnostic;
    }

    public static JSObject parse1001Html(String html, String sourceUrl) {
        Document document = Jsoup.parse(html, sourceUrl);
        List<Element> elements = document.select(".tlpItem");
        if (elements.isEmpty()) elements = document.select(".tlpTog");
        if (elements.isEmpty()) return null;
        JSArray tracks = new JSArray();
        Set<String> seen = new HashSet<>();
        int durationSeconds = 0;
        boolean missingTimestamp = false;
        for (Element element : elements) {
            Element valueElement = element.selectFirst(".trackValue");
            String value = valueElement == null ? "" : valueElement.text().trim();
            if (value.isBlank()) {
                Element metadata = element.selectFirst("meta[itemprop=name]");
                value = metadata == null ? "" : metadata.attr("content").trim();
            }
            if (value.isBlank()) continue;
            double providedTime = -1;
            // Les input *_cue_seconds peuvent être des champs cachés
            // initialisés à 0. Le repère affiché dans la page est prioritaire.
            providedTime = readVisibleCue(element);
            if (providedTime < 0) {
                Element cueInput = element.selectFirst("input[id$=_cue_seconds]");
                if (cueInput != null && !cueInput.attr("value").isBlank()) {
                    try {
                        providedTime = Double.parseDouble(cueInput.attr("value"));
                    } catch (NumberFormatException ignored) {
                        providedTime = -1;
                    }
                }
            }
            if (providedTime < 0) missingTimestamp = true;
            String[] identity = splitArtistTitle(value);
            String key = (identity[0] + "|" + identity[1] + "|" + providedTime).toLowerCase(Locale.ROOT);
            if (identity[1].isBlank() || !seen.add(key)) continue;
            JSObject track = new JSObject();
            track.put("artist", identity[0]);
            track.put("title", identity[1]);
            if (providedTime >= 0) {
                track.put("providedTime", providedTime);
            } else {
                track.put("providedTime", JSONObject.NULL);
            }
            tracks.put(track);
            durationSeconds = Math.max(durationSeconds, (int) providedTime);
        }
        if (tracks.length() < 3 || missingTimestamp) return null;
        JSObject result = new JSObject();
        result.put("sourceUrl", sourceUrl);
        result.put("pageTitle", document.title().trim());
        result.put("durationSeconds", durationSeconds);
        result.put("tracks", tracks);
        return result;
    }

    private static double parseClock(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)")
            .matcher(value);
        if (!matcher.find()) return -1;
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        return matcher.group(3) == null
            ? first * 60.0 + second
            : first * 3600.0 + second * 60.0 + Integer.parseInt(matcher.group(3));
    }

    private static double parseCueValue(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) return -1;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return parseClock(cleaned);
        }
    }

    private static double readVisibleCue(Element element) {
        String[] attributes = {"data-cue", "data-time", "data-seconds", "data-cue-seconds", "data-position"};
        for (String attribute : attributes) {
            if (element.hasAttr(attribute)) {
                double value = parseCueValue(element.attr(attribute));
                if (value >= 0) return value;
            }
        }
        Element cue = element.selectFirst("[data-cue], [data-time], [data-seconds], [data-cue-seconds], [data-position]");
        if (cue != null) {
            for (String attribute : attributes) {
                if (cue.hasAttr(attribute)) {
                    double value = parseCueValue(cue.attr(attribute));
                    if (value >= 0) return value;
                }
            }
        }
        cue = element.selectFirst(".cueValueField, .cueVal, .timing");
        if (cue != null) {
            double value = parseClock(cue.text());
            if (value >= 0) return value;
        }
        return -1;
    }

    private static String[] splitArtistTitle(String value) {
        for (String separator : new String[]{" - ", " – ", " — "}) {
            int index = value.indexOf(separator);
            if (index > 0) {
                return new String[]{value.substring(0, index).trim(), value.substring(index + separator.length()).trim()};
            }
        }
        return new String[]{"Artiste inconnu", value.trim()};
    }

    @PluginMethod
    public void download(PluginCall call) {
        ensureNotificationPermission();
        String url = call.getString("url");
        String id = call.getString("id");
        if (url == null || url.isBlank() || id == null || id.isBlank()) {
            call.reject("url et id sont obligatoires");
            return;
        }
        String safeId = id.replaceAll("[^a-zA-Z0-9._-]", "_");
        File directory = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "podmix");
        if (!directory.exists() && !directory.mkdirs()) {
            call.reject("Création du dossier hors connexion impossible");
            return;
        }
        // Les versions antérieures pouvaient laisser une requête
        // DownloadManager sans identifiant persisté, qui réservait encore le
        // nom « .audio » et empêchait toute reprise. Les nouveaux transferts
        // utilisent un emplacement distinct ; les fichiers déjà terminés
        // restent lus via leur chemin mémorisé.
        File destination = new File(directory, safeId + ".offline");
        SharedPreferences preferences = downloads();
        if ("work".equals(preferences.getString(id + ".engine", ""))) {
            JSObject existing = workDownloadState(id, preferences);
            String status = existing.getString("status", "unknown");
            if ("queued".equals(status) || "downloading".equals(status) || "completed".equals(status)) {
                call.resolve(existing);
                return;
            }
            WorkManager.getInstance(getContext()).cancelUniqueWork(offlineWorkName(id));
            preferences.edit().remove(id + ".engine").apply();
        }
        long existingRequestId = preferences.getLong(id + ".requestId", -1);
        if (existingRequestId >= 0) {
            JSObject existing = downloadState(id, existingRequestId, preferences.getString(id + ".path", ""));
            String status = existing.getString("status", "unknown");
            // Une demande DownloadManager à 0 octet dans l'état queued est
            // précisément le blocage Samsung corrigé ici. On la migre lors
            // du prochain appui au lieu de la présenter comme active.
            boolean stuckLegacyQueue = "queued".equals(status) && existing.optLong("bytesDownloaded", 0) == 0;
            if (!stuckLegacyQueue && !"not_found".equals(status) && !"unknown".equals(status) && !"failed".equals(status)) {
                call.resolve(existing);
                return;
            }
            // Un téléchargement échoué garde parfois son fichier partiel.
            // On le retire avant de permettre une relance propre.
            DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            manager.remove(existingRequestId);
            if (destination.isFile()) destination.delete();
            preferences.edit().remove(id + ".requestId").remove(id + ".path").apply();
        } else if (destination.isFile()) {
            // Fallback pour les anciens téléchargements terminés avant le suivi
            // par DownloadManager. Les nouveaux ne passent jamais par ici tant
            // que leur requête Android est active.
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", "completed");
            result.put("bytesDownloaded", destination.length());
            result.put("totalBytes", destination.length());
            result.put("localUri", Uri.fromFile(destination).toString());
            call.resolve(result);
            return;
        }
        try {
            // Sur certains Samsung récents, DownloadManager conserve les
            // requêtes de Podmix dans l'état « queued » indéfiniment, même
            // lorsque le réseau est disponible. WorkManager exécute le flux
            // avec l'UID de Podmix et reste relançable après un redémarrage.
            Data input = new Data.Builder()
                .putString(OfflineDownloadWorker.INPUT_ID, id)
                .putString(OfflineDownloadWorker.INPUT_URL, url)
                .putString(OfflineDownloadWorker.INPUT_PATH, destination.getAbsolutePath())
                .build();
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OfflineDownloadWorker.class)
                .setInputData(input)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build();
            preferences.edit()
                .putString(id + ".engine", "work")
                .putString(id + ".path", destination.getAbsolutePath())
                .putString(id + ".status", "queued")
                .putLong(id + ".bytesDownloaded", 0)
                .putLong(id + ".totalBytes", -1)
                .remove(id + ".error")
                .apply();
            WorkManager.getInstance(getContext()).enqueueUniqueWork(offlineWorkName(id), ExistingWorkPolicy.REPLACE, work);
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", "queued");
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Téléchargement impossible", error);
        }
    }

    @PluginMethod
    public void getDownload(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isBlank()) {
            call.reject("id est obligatoire");
            return;
        }
        SharedPreferences preferences = downloads();
        if ("work".equals(preferences.getString(id + ".engine", ""))) {
            call.resolve(workDownloadState(id, preferences));
            return;
        }
        long requestId = preferences.getLong(id + ".requestId", -1);
        String path = preferences.getString(id + ".path", "");
        if (requestId < 0) {
            call.resolve(downloadNotFound(id));
            return;
        }
        call.resolve(downloadState(id, requestId, path));
    }

    @PluginMethod
    public void listDownloads(PluginCall call) {
        SharedPreferences preferences = downloads();
        JSArray states = new JSArray();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.endsWith(".engine") && "work".equals(entry.getValue())) {
                String id = key.substring(0, key.length() - ".engine".length());
                states.put(workDownloadState(id, preferences));
            }
            if (!key.endsWith(".requestId") || !(entry.getValue() instanceof Long)) continue;
            String id = key.substring(0, key.length() - ".requestId".length());
            if ("work".equals(preferences.getString(id + ".engine", ""))) continue;
            states.put(downloadState(id, (Long) entry.getValue(), preferences.getString(id + ".path", "")));
        }
        JSObject result = new JSObject();
        result.put("downloads", states);
        call.resolve(result);
    }

    private JSObject downloadState(String id, long requestId, String path) {
        File file = path == null || path.isBlank() ? null : new File(path);
        DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(requestId))) {
            if (!cursor.moveToFirst()) {
                if (file != null && file.isFile()) {
                    JSObject completed = new JSObject();
                    completed.put("id", id);
                    completed.put("requestId", requestId);
                    completed.put("status", "completed");
                    completed.put("bytesDownloaded", file.length());
                    completed.put("totalBytes", file.length());
                    completed.put("localUri", Uri.fromFile(file).toString());
                    return completed;
                }
                return downloadNotFound(id);
            }
            int androidStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("requestId", requestId);
            String resolvedStatus = downloadStatus(androidStatus);
            if (androidStatus == DownloadManager.STATUS_SUCCESSFUL
                && (file == null || !file.isFile() || file.length() <= 0)) {
                resolvedStatus = "not_found";
            }
            result.put("status", resolvedStatus);
            result.put("bytesDownloaded", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            result.put("totalBytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            result.put("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            if (androidStatus != DownloadManager.STATUS_SUCCESSFUL) {
                Log.i("PodmixDownload", "id=" + id + " status=" + downloadStatus(androidStatus)
                    + " reason=" + cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    + " bytes=" + cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    + "/" + cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            }
            if (androidStatus == DownloadManager.STATUS_SUCCESSFUL && file != null && file.isFile()) {
                result.put("localUri", Uri.fromFile(file).toString());
            }
            return result;
        } catch (Exception error) {
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("requestId", requestId);
            result.put("status", "unknown");
            return result;
        }
    }

    @PluginMethod
    public void removeDownload(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isBlank()) {
            call.reject("id est obligatoire");
            return;
        }
        SharedPreferences preferences = downloads();
        if ("work".equals(preferences.getString(id + ".engine", ""))) {
            WorkManager.getInstance(getContext()).cancelUniqueWork(offlineWorkName(id));
        }
        long requestId = preferences.getLong(id + ".requestId", -1);
        String path = preferences.getString(id + ".path", "");
        if (requestId >= 0) {
            DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            manager.remove(requestId);
        }
        boolean removed = path.isBlank() || !new File(path).exists() || new File(path).delete();
        preferences.edit().remove(id + ".requestId").remove(id + ".path")
            .remove(id + ".engine").remove(id + ".status").remove(id + ".bytesDownloaded")
            .remove(id + ".totalBytes").remove(id + ".error").apply();
        JSObject result = new JSObject();
        result.put("id", id);
        result.put("removed", removed);
        call.resolve(result);
    }

    @PluginMethod
    public void downloadApk(PluginCall call) {
        ensureNotificationPermission();
        String url = call.getString("url");
        String versionName = call.getString("versionName", "update");
        if (url == null || url.isBlank()) {
            call.reject("url est obligatoire");
            return;
        }
        File directory = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "podmix/updates");
        if (!directory.exists() && !directory.mkdirs()) {
            call.reject("Création du dossier de mise à jour impossible");
            return;
        }
        String safeVersion = versionName.replaceAll("[^a-zA-Z0-9._-]", "_");
        File destination = new File(directory, "podmix-" + safeVersion + ".apk");
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Podmix " + versionName)
                .setDescription("Téléchargement de la mise à jour")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(destination))
                .setMimeType("application/vnd.android.package-archive");
            DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            long requestId = manager.enqueue(request);
            SharedPreferences prefs = getContext().getSharedPreferences("podmix_updates", Context.MODE_PRIVATE);
            prefs.edit()
                .putLong("apk.requestId", requestId)
                .putString("apk.path", destination.getAbsolutePath())
                .apply();
            JSObject result = new JSObject();
            result.put("requestId", requestId);
            result.put("path", destination.getAbsolutePath());
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Téléchargement de la mise à jour impossible", error);
        }
    }

    @PluginMethod
    public void getApkStatus(PluginCall call) {
        long requestId = call.getLong("requestId", -1L);
        if (requestId < 0) {
            call.reject("requestId est obligatoire");
            return;
        }
        DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(requestId))) {
            if (!cursor.moveToFirst()) {
                JSObject result = new JSObject();
                result.put("status", "not_found");
                result.put("progress", 0);
                call.resolve(result);
                return;
            }
            int androidStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            String status;
            switch (androidStatus) {
                case DownloadManager.STATUS_PENDING: status = "queued"; break;
                case DownloadManager.STATUS_RUNNING: status = "downloading"; break;
                case DownloadManager.STATUS_PAUSED: status = "paused"; break;
                case DownloadManager.STATUS_SUCCESSFUL: status = "completed"; break;
                case DownloadManager.STATUS_FAILED: status = "failed"; break;
                default: status = "unknown";
            }
            JSObject result = new JSObject();
            result.put("status", status);
            result.put("bytesDownloaded", downloaded);
            result.put("totalBytes", total);
            result.put("progress", total > 0 ? (double) downloaded / total : 0);
            SharedPreferences prefs = getContext().getSharedPreferences("podmix_updates", Context.MODE_PRIVATE);
            String path = prefs.getString("apk.path", "");
            if (!path.isBlank() && new File(path).exists()) {
                result.put("localUri", Uri.fromFile(new File(path)).toString());
                result.put("path", path);
            }
            call.resolve(result);
        }
    }

    @PluginMethod
    public void installApk(PluginCall call) {
        String path = call.getString("path");
        String localUri = call.getString("localUri");
        File file;
        if (path != null && !path.isBlank()) {
            file = new File(path);
        } else if (localUri != null && !localUri.isBlank()) {
            try {
                file = new File(Uri.parse(localUri).getPath());
            } catch (Exception error) {
                call.reject("URI invalide");
                return;
            }
        } else {
            call.reject("path ou localUri est obligatoire");
            return;
        }
        if (!file.exists()) {
            call.reject("Le fichier APK n'existe pas: " + file.getAbsolutePath());
            return;
        }
        try {
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                file
            );
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject result = new JSObject();
            result.put("ok", true);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Installation de l'APK impossible", error);
        }
    }

    @PluginMethod
    public void openCastPicker(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastContext castContext = CastContext.getSharedInstance(getActivity());
                CastSession current = castContext.getSessionManager().getCurrentCastSession();
                if (current != null && current.isConnected()) {
                    RemoteMediaClient remote = current.getRemoteMediaClient();
                    restoreCastIdentityIfNeeded(remote);
                    if (controller != null && remote != null && !castMediaId.isBlank()) {
                        long localPosition = localPositionForCastAbsolute(
                            controller,
                            remote.getApproximateStreamPosition()
                        );
                        boolean wasPlaying = remote.isPlaying();
                        controller.seekTo(localPosition);
                        if (wasPlaying) controller.play(); else controller.pause();
                    }
                    clearCastIdentity();
                    castContext.getSessionManager().endCurrentSession(true);
                    JSObject result = new JSObject();
                    result.put("connected", false);
                    call.resolve(result);
                    return;
                }
                MediaRouteSelector selector = new MediaRouteSelector.Builder()
                    .addControlCategory(CastMediaControlIntent.categoryForCast("CC1AD845"))
                    .build();
                MediaRouteChooserDialog dialog = new MediaRouteChooserDialog(getActivity());
                dialog.setRouteSelector(selector);
                dialog.show();
                JSObject result = new JSObject();
                result.put("pickerOpened", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Sélecteur Google Cast indisponible", error);
            }
        });
    }

    private MediaRouteSelector castRouteSelector() {
        return new MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast("CC1AD845"))
            .build();
    }

    private String castDeviceType(int type) {
        return switch (type) {
            case MediaRouter.RouteInfo.DEVICE_TYPE_TV -> "Téléviseur";
            case MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> "Enceinte";
            case MediaRouter.RouteInfo.DEVICE_TYPE_AUDIO_VIDEO_RECEIVER -> "Ampli";
            case MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> "Groupe";
            default -> "Google Cast";
        };
    }

    @PluginMethod
    public void discoverCastDevices(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastContext.getSharedInstance(getActivity());
                MediaRouter router = MediaRouter.getInstance(getActivity());
                MediaRouteSelector selector = castRouteSelector();
                MediaRouter.Callback callback = new MediaRouter.Callback() {};
                router.addCallback(
                    selector,
                    callback,
                    MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                );
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        JSArray devices = new JSArray();
                        for (MediaRouter.RouteInfo route : router.getRoutes()) {
                            if (!route.isEnabled() || route.isDefault() || !route.matchesSelector(selector)) continue;
                            JSObject device = new JSObject();
                            device.put("id", route.getId());
                            device.put("name", route.getName());
                            device.put("description", route.getDescription());
                            device.put("deviceType", castDeviceType(route.getDeviceType()));
                            device.put("connected", route.isSelected() || route.getConnectionState() == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED);
                            devices.put(device);
                        }
                        JSObject result = new JSObject();
                        result.put("devices", devices);
                        call.resolve(result);
                    } catch (Exception error) {
                        call.reject("Recherche Google Cast impossible", error);
                    } finally {
                        router.removeCallback(callback);
                    }
                }, 1400);
            } catch (Exception error) {
                call.reject("Recherche Google Cast impossible", error);
            }
        });
    }

    @PluginMethod
    public void connectCastDevice(PluginCall call) {
        String routeId = call.getString("id", "");
        getActivity().runOnUiThread(() -> {
            try {
                CastContext.getSharedInstance(getActivity());
                MediaRouter router = MediaRouter.getInstance(getActivity());
                MediaRouter.RouteInfo target = null;
                for (MediaRouter.RouteInfo route : router.getRoutes()) {
                    if (route.getId().equals(routeId) && route.matchesSelector(castRouteSelector())) {
                        target = route;
                        break;
                    }
                }
                if (target == null || !target.isEnabled()) {
                    call.reject("Cet appareil Cast n’est plus disponible");
                    return;
                }
                target.select();
                long deadline = System.currentTimeMillis() + 12000;
                Handler handler = new Handler(Looper.getMainLooper());
                Runnable waiter = new Runnable() {
                    @Override
                    public void run() {
                        CastSession session = CastContext.getSharedInstance(getActivity())
                            .getSessionManager().getCurrentCastSession();
                        if (session != null && session.isConnected()) {
                            JSObject result = new JSObject();
                            result.put("connected", true);
                            result.put("deviceName", session.getCastDevice() == null
                                ? "Chromecast"
                                : session.getCastDevice().getFriendlyName());
                            call.resolve(result);
                            return;
                        }
                        if (System.currentTimeMillis() >= deadline) {
                            call.reject("Connexion à l’appareil Cast trop longue");
                            return;
                        }
                        handler.postDelayed(this, 250);
                    }
                };
                handler.post(waiter);
            } catch (Exception error) {
                call.reject("Connexion Google Cast impossible", error);
            }
        });
    }

    @PluginMethod
    public void disconnectCast(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastContext castContext = CastContext.getSharedInstance(getActivity());
                CastSession current = castContext.getSessionManager().getCurrentCastSession();
                RemoteMediaClient remote = current == null ? null : current.getRemoteMediaClient();
                restoreCastIdentityIfNeeded(remote);
                if (controller != null && remote != null && !castMediaId.isBlank()) {
                    long localPosition = localPositionForCastAbsolute(
                        controller,
                        remote.getApproximateStreamPosition()
                    );
                    boolean wasPlaying = remote.isPlaying();
                    controller.seekTo(localPosition);
                    if (wasPlaying) controller.play(); else controller.pause();
                }
                clearCastIdentity();
                if (current != null) castContext.getSessionManager().endCurrentSession(true);
                JSObject result = controller == null ? new JSObject() : state(controller);
                result.put("connected", false);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Déconnexion Google Cast impossible", error);
            }
        });
    }

    @PluginMethod
    public void cast(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isBlank()) {
            call.reject("url est obligatoire");
            return;
        }
        getActivity().runOnUiThread(() -> {
            try {
                CastSession session = CastContext.getSharedInstance(getActivity())
                    .getSessionManager().getCurrentCastSession();
                if (session == null || !session.isConnected() || session.getRemoteMediaClient() == null) {
                    call.reject("Aucun appareil Cast connecté");
                    return;
                }
                com.google.android.gms.cast.MediaMetadata metadata =
                    new com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
                metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, call.getString("title", "Podmix"));
                metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, call.getString("artist", ""));
                String artworkUrl = call.getString("artworkUrl", "");
                if (!artworkUrl.isBlank()) metadata.addImage(new com.google.android.gms.common.images.WebImage(Uri.parse(artworkUrl)));
                String contentType = call.getString("contentType", inferContentType(url));
                boolean live = call.getBoolean("live", false);
                MediaInfo mediaInfo = new MediaInfo.Builder(url)
                    .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(contentType)
                    .setMetadata(metadata)
                    .build();
                long positionMs = live
                    ? 0
                    : Math.max(0, (long) (call.getDouble("positionSeconds", 0.0) * 1000));
                castPositionOffsetMs = live
                    ? 0
                    : Math.max(0, (long) (call.getDouble("positionOffsetSeconds", 0.0) * 1000));
                castEndPositionMs = live
                    ? 0
                    : Math.max(0, (long) (call.getDouble("endPositionSeconds", 0.0) * 1000));
                MediaItem localItem = controller == null ? null : controller.getCurrentMediaItem();
                castMediaId = localItem == null ? url : localItem.mediaId;
                lastCastAbsolutePositionMs = positionMs;
                lastCastWasPlaying = true;
                castMissingSinceMs = 0;
                persistCastIdentity(true);
                if (controller != null) controller.pause();
                session.getRemoteMediaClient().load(new MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(positionMs)
                    .build());
                JSObject result = new JSObject();
                result.put("connected", true);
                result.put("deviceName", session.getCastDevice() == null ? "Chromecast" : session.getCastDevice().getFriendlyName());
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Lecture Cast impossible", error);
            }
        });
    }

    @PluginMethod
    public void getCastState(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastSession session = CastContext.getSharedInstance(getActivity())
                    .getSessionManager().getCurrentCastSession();
                JSObject result = new JSObject();
                boolean connected = session != null && session.isConnected();
                result.put("connected", connected);
                if (connected) restoreCastIdentityIfNeeded(session.getRemoteMediaClient());
                if (connected && session.getCastDevice() != null) {
                    result.put("deviceName", session.getCastDevice().getFriendlyName());
                }
                call.resolve(result);
            } catch (Exception error) {
                call.reject("État Cast indisponible", error);
            }
        });
    }

    @PluginMethod
    public void setCastVolume(PluginCall call) {
        double volume = call.getDouble("volume", 0.5);
        final double clampedVolume = Math.max(0.0, Math.min(1.0, volume));
        getActivity().runOnUiThread(() -> {
            try {
                CastSession session = CastContext.getSharedInstance(getActivity())
                    .getSessionManager().getCurrentCastSession();
                if (session == null || !session.isConnected()) {
                    call.reject("Aucun appareil Cast connecté");
                    return;
                }
                session.setVolume(clampedVolume);
                JSObject result = new JSObject();
                result.put("volume", clampedVolume);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Volume Cast impossible", error);
            }
        });
    }

    @PluginMethod
    public void getCastVolume(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastSession session = CastContext.getSharedInstance(getActivity())
                    .getSessionManager().getCurrentCastSession();
                if (session == null || !session.isConnected()) {
                    call.reject("Aucun appareil Cast connecté");
                    return;
                }
                double volume = session.getVolume();
                JSObject result = new JSObject();
                result.put("volume", volume);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Volume Cast indisponible", error);
            }
        });
    }

    private String inferContentType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return "application/x-mpegURL";
        if (lower.contains(".mp3")) return "audio/mpeg";
        if (lower.contains(".ogg") || lower.contains(".opus")) return "audio/ogg";
        return "audio/mp4";
    }

    private void loadControllerItemOnCast(
        RemoteMediaClient remote,
        MediaController mediaController,
        boolean autoplay
    ) {
        MediaItem item = mediaController.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null) return;
        String url = item.localConfiguration.uri.toString();
        MediaMetadata localMetadata = item.mediaMetadata;
        com.google.android.gms.cast.MediaMetadata metadata =
            new com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        metadata.putString(
            com.google.android.gms.cast.MediaMetadata.KEY_TITLE,
            localMetadata.title == null ? "Podmix" : localMetadata.title.toString()
        );
        metadata.putString(
            com.google.android.gms.cast.MediaMetadata.KEY_ARTIST,
            localMetadata.artist == null ? "" : localMetadata.artist.toString()
        );
        MediaInfo mediaInfo = new MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(inferContentType(url))
            .setMetadata(metadata)
            .build();
        castMediaId = item.mediaId;
        long currentPositionMs = Math.max(0, mediaController.getCurrentPosition());
        PodmixPlaybackService.TrackState continuousTrack = isContinuousEpisode(mediaController)
            ? PodmixPlaybackService.currentTrackState(item.mediaId, currentPositionMs, mediaController.getDuration())
            : null;
        castPositionOffsetMs = continuousTrack == null
            ? Math.max(0, item.clippingConfiguration.startPositionMs)
            : continuousTrack.startMs;
        long clippingEnd = continuousTrack == null
            ? item.clippingConfiguration.endPositionMs
            : continuousTrack.endMs;
        castEndPositionMs = clippingEnd > castPositionOffsetMs ? clippingEnd : 0;
        long castStartMs = continuousTrack == null ? castPositionOffsetMs : currentPositionMs;
        lastCastAbsolutePositionMs = castStartMs;
        lastCastWasPlaying = autoplay;
        castMissingSinceMs = 0;
        persistCastIdentity(true);
        mediaController.pause();
        remote.load(new MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(autoplay)
            .setCurrentTime(castStartMs)
            .build());
    }

    @PluginMethod
    public void bosePlay(PluginCall call) {
        String ip = call.getString("ip", "");
        String url = call.getString("url", "");
        String title = call.getString("title", "Podmix");
        int positionSeconds = Math.max(0, call.getInt("positionSeconds", 0));
        if (url.isBlank()) {
            call.reject("url est obligatoire");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            call.reject("Le média Bose doit utiliser HTTP ou HTTPS");
            return;
        }
        BOSE_PLAYBACK_EXECUTOR.execute(() -> {
            try {
                Log.i(TAG, "Bose replace requested: " + title + " → " + url);
                // On SoundTouch speakers, SetAVTransportURI while another
                // network item is buffering is unreliable: the old item can
                // remain selected and the following Play merely resumes it.
                // Stop first, then let the renderer settle before replacing
                // the URI. This also makes same-URL track seeks deterministic.
                try {
                    String stop = soapEnvelope("Stop", "<InstanceID>0</InstanceID>");
                    boseRequest(ip, 8091, "POST", "/AVTransport/Control", stop,
                        "urn:schemas-upnp-org:service:AVTransport:1#Stop");
                    Thread.sleep(300);
                } catch (Exception ignored) {
                    // Some SoundTouch firmware accepts the URI replacement
                    // without a Stop. Continue with the normal load.
                }
                String setUri = soapEnvelope(
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURIMetaData>" +
                    xmlEscape(boseDidl(url, title)) + "</CurrentURIMetaData>" +
                    "<CurrentURI>" + xmlEscape(url) + "</CurrentURI>"
                );
                boseRequest(ip, 8091, "POST", "/AVTransport/Control", setUri,
                    "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI");
                Thread.sleep(450);
                String play = soapEnvelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                boseRequest(ip, 8091, "POST", "/AVTransport/Control", play,
                    "urn:schemas-upnp-org:service:AVTransport:1#Play");
                if (positionSeconds > 2) {
                    Thread.sleep(500);
                    String seek = soapEnvelope(
                        "Seek",
                        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" +
                        formatBoseTime(positionSeconds) + "</Target>"
                    );
                    try {
                        boseRequest(ip, 8091, "POST", "/AVTransport/Control", seek,
                            "urn:schemas-upnp-org:service:AVTransport:1#Seek");
                    } catch (Exception ignored) {
                        Log.w(TAG, "La reprise de position n'est pas prise en charge par ce flux Bose");
                    }
                }
                waitForBosePlayback(ip, title, url);
                if (BOSE_TASK_REMOVED.get()) {
                    boseRequest(ip, "POST", "/key", "<key state=\"press\" sender=\"Gabbo\">STOP</key>");
                    boseRequest(ip, "POST", "/key", "<key state=\"release\" sender=\"Gabbo\">STOP</key>");
                    throw new IOException("Application fermée pendant le transfert Bose");
                }
                getContext().getSharedPreferences(BOSE_OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putString(BOSE_ACTIVE_IP, ip).apply();
                JSObject result = new JSObject(); result.put("ok", true); call.resolve(result);
            } catch (Exception error) {
                Log.e(TAG, "Bose replacement failed for " + title, error);
                call.reject("Diffusion Bose impossible", error);
            }
        });
    }

    private void waitForBosePlayback(String ip, String title, String url) throws Exception {
        String lastStatus = "";
        Exception lastError = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            Thread.sleep(attempt == 0 ? 600 : 750);
            try {
                String nowPlaying = boseRequest(ip, "GET", "/now_playing", "");
                lastStatus = xmlValue(nowPlaying, "playStatus");
                String currentTitle = xmlValue(nowPlaying, "track");
                String location = xmlAttribute(nowPlaying, "ContentItem", "location");
                Log.i(TAG, "Bose playback check " + (attempt + 1) + "/12: status="
                    + lastStatus + ", title=" + currentTitle + ", location=" + location);
                if (isBosePlayingStatus(lastStatus)) {
                    Log.i(TAG, "Bose replace confirmed in PLAY_STATE: " + title);
                    return;
                }
                // Some firmware accepts the URI but loses the first Play while
                // opening the stream. Retry an explicit Play, never a toggle.
                if (attempt == 3 || attempt == 7) {
                    String play = soapEnvelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
                    boseRequest(ip, 8091, "POST", "/AVTransport/Control", play,
                        "urn:schemas-upnp-org:service:AVTransport:1#Play");
                }
            } catch (Exception error) {
                lastError = error;
                Log.w(TAG, "Bose playback confirmation attempt failed", error);
            }
        }
        IOException failure = new IOException(
            "La Bose n'est pas passée en lecture (dernier état: "
                + (lastStatus.isBlank() ? "inconnu" : lastStatus) + ") pour " + url
        );
        if (lastError != null) failure.initCause(lastError);
        throw failure;
    }

    static boolean isBosePlayingStatus(String status) {
        return "PLAY_STATE".equals(status);
    }

    @PluginMethod
    public void boseDiscover(PluginCall call) {
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(48);
            try {
                Set<String> prefixes = new HashSet<>();
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface network : Collections.list(interfaces)) {
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (InetAddress address : Collections.list(network.getInetAddresses())) {
                        byte[] bytes = address.getAddress();
                        if (bytes.length != 4 || !address.isSiteLocalAddress()) continue;
                        String host = address.getHostAddress();
                        int dot = host.lastIndexOf('.');
                        if (dot > 0) prefixes.add(host.substring(0, dot + 1));
                    }
                }
                List<Future<JSObject>> probes = new ArrayList<>();
                for (String prefix : prefixes) {
                    for (int host = 1; host <= 254; host++) {
                        String ip = prefix + host;
                        probes.add(pool.submit(() -> {
                            try {
                                String info = boseRequest(ip, 8090, "GET", "/info", "", null, 350, 700);
                                String name = xmlValue(info, "name");
                                if (name.isBlank()) return null;
                                JSObject device = new JSObject();
                                device.put("ip", ip);
                                device.put("name", name);
                                device.put("type", xmlValue(info, "type"));
                                device.put("id", xmlAttribute(info, "info", "deviceID"));
                                return device;
                            } catch (Exception ignored) {
                                return null;
                            }
                        }));
                    }
                }
                JSArray devices = new JSArray();
                for (Future<JSObject> probe : probes) {
                    JSObject device = probe.get(3, TimeUnit.SECONDS);
                    if (device != null) devices.put(device);
                }
                JSObject result = new JSObject();
                result.put("devices", devices);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Recherche Bose impossible", error);
            } finally {
                pool.shutdownNow();
            }
        }, "podmix-bose-discovery").start();
    }

    @PluginMethod
    public void boseKey(PluginCall call) {
        String ip = call.getString("ip", "");
        String key = call.getString("key", "PLAY_PAUSE").replaceAll("[^A-Z_]", "");
        if ("STOP".equals(key)) {
            getContext().getSharedPreferences(BOSE_OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(BOSE_ACTIVE_IP).apply();
        }
        new Thread(() -> {
            try {
                Log.i(TAG, "Bose key: " + key);
                boseRequest(ip, "POST", "/key", "<key state=\"press\" sender=\"Gabbo\">" + key + "</key>");
                boseRequest(ip, "POST", "/key", "<key state=\"release\" sender=\"Gabbo\">" + key + "</key>");
                JSObject result = new JSObject(); result.put("ok", true); call.resolve(result);
            } catch (Exception error) {
                Log.e(TAG, "Bose key failed: " + key, error);
                call.reject("Commande Bose impossible", error);
            }
        }, "podmix-bose-key").start();
    }

    @PluginMethod
    public void boseSetVolume(PluginCall call) {
        String ip = call.getString("ip", "");
        int volume = Math.max(0, Math.min(100, call.getInt("volume", 30)));
        boseAsync(call, ip, "POST", "/volume", "<volume>" + volume + "</volume>");
    }

    @PluginMethod
    public void boseGetState(PluginCall call) {
        String ip = call.getString("ip", "");
        new Thread(() -> {
            try {
                String info = boseRequest(ip, "GET", "/info", "");
                String volumeXml = boseRequest(ip, "GET", "/volume", "");
                String playingXml = boseRequest(ip, "GET", "/now_playing", "");
                JSObject result = new JSObject();
                result.put("ok", true);
                result.put("name", xmlValue(info, "name"));
                String volume = xmlValue(volumeXml, "actualvolume");
                result.put("volume", volume.isBlank() ? 0 : Integer.parseInt(volume));
                String status = xmlValue(playingXml, "playStatus");
                String position = xmlValue(playingXml, "time");
                result.put("playing", "PLAY_STATE".equals(status));
                result.put("playStatus", status);
                result.put("positionSeconds", position.isBlank() ? 0 : Integer.parseInt(position));
                result.put("source", xmlAttribute(playingXml, "nowPlaying", "source"));
                result.put("title", xmlValue(playingXml, "track"));
                result.put("location", xmlAttribute(playingXml, "ContentItem", "location"));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Enceinte Bose inaccessible", error);
            }
        }, "podmix-bose-state").start();
    }

    private void boseAsync(PluginCall call, String ip, String method, String path, String body) {
        new Thread(() -> {
            try {
                boseRequest(ip, method, path, body);
                JSObject result = new JSObject(); result.put("ok", true); call.resolve(result);
            } catch (Exception error) {
                call.reject("Enceinte Bose inaccessible", error);
            }
        }, "podmix-bose").start();
    }

    public static void markBoseAppSessionActive(Context context) {
        if (!BOSE_TASK_REMOVED.getAndSet(false)) return;
        context.getSharedPreferences(BOSE_OUTPUT_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(BOSE_ACTIVE_IP).apply();
    }

    public static void disconnectBoseOnTaskRemoval(Context context) {
        BOSE_TASK_REMOVED.set(true);
        SharedPreferences preferences = context.getSharedPreferences(
            BOSE_OUTPUT_PREFERENCES,
            Context.MODE_PRIVATE
        );
        String ip = preferences.getString(BOSE_ACTIVE_IP, "");
        preferences.edit().remove(BOSE_ACTIVE_IP).apply();
        if (ip == null || ip.isBlank()) return;
        BOSE_PLAYBACK_EXECUTOR.execute(() -> {
            try {
                Log.i(TAG, "Task removed: disconnecting remembered Bose at " + ip);
                boseRequest(ip, "POST", "/key", "<key state=\"press\" sender=\"Gabbo\">STOP</key>");
                boseRequest(ip, "POST", "/key", "<key state=\"release\" sender=\"Gabbo\">STOP</key>");
            } catch (Exception error) {
                Log.i(TAG, "Task removed: remembered Bose already unreachable");
            }
        });
    }

    private static String boseRequest(String ip, String method, String path, String body) throws Exception {
        return boseRequest(ip, 8090, method, path, body, null, 3000, 5000);
    }

    private static String boseRequest(
        String ip, int port, String method, String path, String body, String soapAction
    ) throws Exception {
        return boseRequest(ip, port, method, path, body, soapAction, 3000, 5000);
    }

    private static String boseRequest(
        String ip, int port, String method, String path, String body, String soapAction,
        int connectTimeoutMs, int readTimeoutMs
    ) throws Exception {
        InetAddress address = InetAddress.getByName(ip);
        if (!address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("L’adresse Bose doit être une IPv4 privée du réseau local");
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream output = socket.getOutputStream();
            String headers = method + " " + path + " HTTP/1.1\r\nHost: " + ip +
                ":" + port + "\r\nConnection: close\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n" +
                (soapAction == null ? "" : "SOAPACTION: \"" + soapAction + "\"\r\n") +
                "Content-Length: " + payload.length + "\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            if (payload.length > 0) output.write(payload);
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            if (status == null || (!status.contains(" 200 ") && !status.contains(" 201 "))) {
                throw new IllegalStateException("Réponse SoundTouch invalide : " + status);
            }
            StringBuilder response = new StringBuilder();
            String line;
            boolean bodyStarted = false;
            while ((line = reader.readLine()) != null) {
                if (bodyStarted) response.append(line).append('\n');
                else if (line.isEmpty()) bodyStarted = true;
            }
            return response.toString();
        }
    }

    private String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String soapEnvelope(String action, String content) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            content + "</u:" + action + "></s:Body></s:Envelope>";
    }

    private String boseDidl(String url, String title) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>" + xmlEscape(title) + "</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\">" + xmlEscape(url) + "</res>" +
            "</item></DIDL-Lite>";
    }

    private String formatBoseTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remaining = seconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remaining);
    }

    private String xmlAttribute(String xml, String tag, String attribute) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("<" + tag + "[^>]*\\s" + attribute + "=\"([^\"]*)\"")
            .matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String xmlValue(String xml, String tag) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", java.util.regex.Pattern.DOTALL)
            .matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private SharedPreferences downloads() {
        return getContext().getSharedPreferences("podmix_downloads", Context.MODE_PRIVATE);
    }

    private static String offlineWorkName(String id) {
        return "podmix-offline-" + Integer.toHexString(id.hashCode());
    }

    private JSObject workDownloadState(String id, SharedPreferences preferences) {
        JSObject result = new JSObject();
        String path = preferences.getString(id + ".path", "");
        File file = path.isBlank() ? null : new File(path);
        String status = preferences.getString(id + ".status", "not_found");
        if (file != null && file.isFile() && file.length() > 0) status = "completed";
        else if ("completed".equals(status)) status = "not_found";
        result.put("id", id);
        result.put("status", status);
        long bytes = "completed".equals(status) && file != null ? file.length() : preferences.getLong(id + ".bytesDownloaded", 0);
        long total = "completed".equals(status) && file != null ? file.length() : preferences.getLong(id + ".totalBytes", -1);
        result.put("bytesDownloaded", bytes);
        result.put("totalBytes", total);
        if ("completed".equals(status) && file != null) result.put("localUri", Uri.fromFile(file).toString());
        return result;
    }

    private JSObject downloadNotFound(String id) {
        JSObject result = new JSObject();
        result.put("id", id);
        result.put("status", "not_found");
        return result;
    }

    private String downloadStatus(int status) {
        return switch (status) {
            case DownloadManager.STATUS_PENDING -> "queued";
            case DownloadManager.STATUS_RUNNING -> "downloading";
            case DownloadManager.STATUS_PAUSED -> "paused";
            case DownloadManager.STATUS_SUCCESSFUL -> "completed";
            case DownloadManager.STATUS_FAILED -> "failed";
            default -> "unknown";
        };
    }

    private JSObject state(MediaController mediaController) {
        RemoteMediaClient remote = castClient();
        restoreCastIdentityIfNeeded(remote);
        recoverDisconnectedCastIfNeeded(mediaController, remote);
        JSObject result = new JSObject();
        long absolutePositionMs = Math.max(0, mediaController.getCurrentPosition());
        long absoluteDurationMs = Math.max(0, mediaController.getDuration());
        result.put("playing", mediaController.isPlaying());
        result.put("playRequested", mediaController.getPlayWhenReady());
        result.put("positionSeconds", absolutePositionMs / 1000.0);
        result.put("durationSeconds", absoluteDurationMs / 1000.0);
        result.put("absolutePositionSeconds", absolutePositionMs / 1000.0);
        result.put("bufferedPositionSeconds", Math.max(0, mediaController.getBufferedPosition()) / 1000.0);
        result.put("playbackSuppressionReason", mediaController.getPlaybackSuppressionReason());
        result.put("playbackState", mediaController.getPlaybackState());
        MediaMetadata metadata = mediaController.getMediaMetadata();
        result.put("title", metadata.title == null ? "" : metadata.title.toString());
        result.put("artist", metadata.artist == null ? "" : metadata.artist.toString());
        result.put("queueIndex", mediaController.getCurrentMediaItemIndex());
        result.put("queueSize", mediaController.getMediaItemCount());
        boolean physicalTrackQueue = isPhysicalTrackQueue(mediaController);
        result.put("hasNext", physicalTrackQueue && mediaController.hasNextMediaItem());
        result.put("hasPrevious", physicalTrackQueue && mediaController.hasPreviousMediaItem());
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        result.put("mediaId", currentItem == null ? "" : currentItem.mediaId);
        PodmixPlaybackService.TrackState trackState = currentItem == null ? null
            : PodmixPlaybackService.currentTrackState(currentItem.mediaId, absolutePositionMs, absoluteDurationMs);
        if (trackState != null && currentItem.mediaMetadata.extras != null
            && currentItem.mediaMetadata.extras.getBoolean("podmixContinuousEpisode", false)) {
            result.put("queueIndex", trackState.index);
            result.put("queueSize", trackState.count);
            result.put("hasNext", trackState.index < trackState.count - 1);
            result.put("hasPrevious", trackState.index > 0);
            result.put("mediaId", trackState.mediaId);
            result.put("positionSeconds", Math.max(0, absolutePositionMs - trackState.startMs) / 1000.0);
            result.put("durationSeconds", trackState.endMs > trackState.startMs
                ? (trackState.endMs - trackState.startMs) / 1000.0 : 0);
            result.put("positionOffsetSeconds", trackState.startMs / 1000.0);
        }
        result.put("error", playbackError);
        if (remote != null && !castMediaId.isBlank()) {
            long absolutePosition = remote.getApproximateStreamPosition();
            long absoluteDuration = remote.getStreamDuration();
            lastCastAbsolutePositionMs = Math.max(0, absolutePosition);
            lastCastWasPlaying = remote.isPlaying();
            castMissingSinceMs = 0;
            persistCastIdentity();
            PodmixPlaybackService.TrackState castTrackState = currentItem != null && isContinuousEpisode(mediaController)
                ? PodmixPlaybackService.currentTrackState(currentItem.mediaId, absolutePosition, absoluteDurationMs)
                : null;
            long offset = castTrackState == null ? castPositionOffsetMs : castTrackState.startMs;
            long end = castTrackState != null
                ? castTrackState.endMs
                : (castEndPositionMs > castPositionOffsetMs ? castEndPositionMs : absoluteDuration);
            result.put("playing", remote.isPlaying());
            result.put("playRequested", remote.isPlaying() || remote.isBuffering());
            result.put("absolutePositionSeconds", Math.max(0, absolutePosition) / 1000.0);
            result.put("positionSeconds", Math.max(0, absolutePosition - offset) / 1000.0);
            result.put("durationSeconds", end > offset ? (end - offset) / 1000.0 : 0);
            result.put("positionOffsetSeconds", offset / 1000.0);
            result.put("playbackState", remote.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE ? Player.STATE_IDLE : Player.STATE_READY);
            if (castTrackState != null) {
                result.put("queueIndex", castTrackState.index);
                result.put("queueSize", castTrackState.count);
                result.put("hasNext", castTrackState.index < castTrackState.count - 1);
                result.put("hasPrevious", castTrackState.index > 0);
                result.put("mediaId", castTrackState.mediaId);
            } else {
                result.put("mediaId", castMediaId);
            }
        }
        // All command replies, polls and events share one ordering.
        result.put("stateSequence", ++stateSequence);
        return result;
    }

    private void emitState() {
        if (controller != null) {
            // Playback snapshots are not durable events: never replay old positions.
            notifyListeners("stateChanged", state(controller), false);
        }
    }

    @Override
    public void onEvents(@NonNull Player player, Player.Events events) {
        emitState();
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        playbackError = error.getErrorCodeName() + (error.getMessage() == null ? "" : " · " + error.getMessage());
        Log.e(TAG, playbackError, error);
        // onEvents delivers the complete batch after individual callbacks.
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_READY) playbackError = "";
        // Emit only from onEvents, after Media3 has applied the whole batch.
    }

    @Override
    protected void handleOnDestroy() {
        if (controller != null) {
            controller.removeListener(this);
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        controller = null;
        controllerFuture = null;
        super.handleOnDestroy();
    }
}
