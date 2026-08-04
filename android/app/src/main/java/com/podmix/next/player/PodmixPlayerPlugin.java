package com.podmix.next.player;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
import androidx.media3.session.SessionToken;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@CapacitorPlugin(name = "PodmixPlayer")
@OptIn(markerClass = UnstableApi.class)
public class PodmixPlayerPlugin extends Plugin implements Player.Listener {
    private static final String TAG = "PodmixPlayer";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4201;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private String playbackError = "";
    private String castMediaId = "";
    private long castPositionOffsetMs = 0;
    private long castEndPositionMs = 0;

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
                MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(source.optString("title", "Podmix"))
                    .setArtist(source.optString("artist", ""))
                    .setArtworkUri(ArtworkProvider.register(
                        getContext(),
                        source.optString("artworkUrl", "")
                    ))
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
    public void next(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            if (remote != null && !castMediaId.isBlank()) {
                if (mediaController.hasNextMediaItem()) {
                    mediaController.seekToNextMediaItem();
                    loadControllerItemOnCast(remote, mediaController, true);
                }
            } else if (mediaController.hasNextMediaItem()) {
                mediaController.seekToNextMediaItem();
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void previous(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            if (remote != null && !castMediaId.isBlank()) {
                long relativePosition = Math.max(0, remote.getApproximateStreamPosition() - castPositionOffsetMs);
                if (relativePosition > 5000) {
                    remote.seek(castPositionOffsetMs);
                } else if (mediaController.hasPreviousMediaItem()) {
                    mediaController.seekToPreviousMediaItem();
                    loadControllerItemOnCast(remote, mediaController, true);
                }
            } else if (mediaController.getCurrentPosition() > 5000) {
                mediaController.seekTo(0);
            } else if (mediaController.hasPreviousMediaItem()) {
                mediaController.seekToPreviousMediaItem();
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void play(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            if (remote != null && !castMediaId.isBlank()) remote.play();
            else mediaController.play();
            JSObject result = state(mediaController);
            result.put("playing", true);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            if (remote != null && !castMediaId.isBlank()) remote.pause();
            else mediaController.pause();
            JSObject result = state(mediaController);
            result.put("playing", false);
            call.resolve(result);
        });
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        double positionSeconds = call.getDouble("positionSeconds", 0.0);
        withController(call, mediaController -> {
            RemoteMediaClient remote = castClient();
            if (remote != null && !castMediaId.isBlank()) {
                long target = castPositionOffsetMs + Math.max(0, (long) (positionSeconds * 1000));
                if (castEndPositionMs > castPositionOffsetMs) target = Math.min(target, castEndPositionMs);
                remote.seek(target);
            } else {
                mediaController.seekTo(Math.max(0, (long) (positionSeconds * 1000)));
            }
            call.resolve(state(mediaController));
        });
    }

    @PluginMethod
    public void getState(PluginCall call) {
        withController(call, mediaController -> call.resolve(state(mediaController)));
    }

    @PluginMethod
    public void syncLibrary(PluginCall call) {
        JSArray items = call.getArray("items");
        if (items == null) {
            call.reject("items est obligatoire");
            return;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item != null) {
                ArtworkProvider.register(
                    getContext(),
                    item.optString("artworkUrl", "")
                );
            }
        }
        getContext().getSharedPreferences("podmix-library", Context.MODE_PRIVATE)
            .edit()
            .putString("items", items.toString())
            .putLong("version", System.currentTimeMillis())
            .apply();
        JSObject result = new JSObject();
        result.put("count", items.length());
        call.resolve(result);
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
        getContext().getSharedPreferences("podmix-resume", Context.MODE_PRIVATE)
            .edit()
            .putString("items", items.toString())
            .putLong("version", System.currentTimeMillis())
            .apply();
        JSObject result = new JSObject();
        result.put("count", items.length());
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

    private String validate1001Url(String value) throws Exception {
        URI uri = URI.create(value.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || (!host.equals("1001tracklists.com") && !host.equals("www.1001tracklists.com"))
            || !uri.getPath().matches("^/tracklist/[a-z0-9]+/[^/?#]+\\.html$")) {
            throw new IllegalArgumentException("Seules les pages HTTPS /tracklist/… sont acceptées");
        }
        return "https://www.1001tracklists.com" + uri.getPath();
    }

    private String fetch1001Html(String url, String rawAddress) throws Exception {
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
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; SM-S916B) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger(0);
        Runnable[] poll = new Runnable[1];
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
                            webView.destroy();
                            call.resolve(result);
                            return;
                        }
                    } catch (Exception error) {
                        Log.d(TAG, "Page 1001Tracklists pas encore exploitable", error);
                    }
                    if (attempts.incrementAndGet() >= 12) {
                        if (finished.compareAndSet(false, true)) {
                            webView.destroy();
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
                handler.postDelayed(poll[0], 700);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && finished.compareAndSet(false, true)) {
                    webView.destroy();
                    call.reject("Chargement 1001Tracklists impossible : " + error.getDescription()
                        + diagnosticSuffix(httpDiagnostic));
                }
            }
        });
        webView.loadUrl(url);
    }

    private String diagnosticSuffix(String diagnostic) {
        return diagnostic == null || diagnostic.isBlank() ? "" : " — accès natif : " + diagnostic;
    }

    private JSObject parse1001Html(String html, String sourceUrl) {
        Document document = Jsoup.parse(html, sourceUrl);
        List<Element> elements = document.select("div.tlpItem, div.tlpTog");
        if (elements.isEmpty()) return null;
        JSArray tracks = new JSArray();
        Set<String> seen = new HashSet<>();
        int durationSeconds = 0;
        for (Element element : elements) {
            Element valueElement = element.selectFirst("span.trackValue");
            String value = valueElement == null ? "" : valueElement.text().trim();
            if (value.isBlank()) {
                Element metadata = element.selectFirst("meta[itemprop=name]");
                value = metadata == null ? "" : metadata.attr("content").trim();
            }
            if (value.isBlank()) continue;
            double providedTime = 0;
            Element cueInput = element.selectFirst("input[id$=_cue_seconds]");
            if (cueInput != null) {
                try {
                    providedTime = Double.parseDouble(cueInput.attr("value"));
                } catch (NumberFormatException ignored) {
                    providedTime = 0;
                }
            }
            if (providedTime <= 0) {
                Element cue = element.selectFirst("span.cueValueField, span.cueVal, span.timing");
                if (cue != null) providedTime = parseClock(cue.text());
            }
            String[] identity = splitArtistTitle(value);
            String key = (identity[0] + "|" + identity[1] + "|" + providedTime).toLowerCase(Locale.ROOT);
            if (identity[1].isBlank() || !seen.add(key)) continue;
            JSObject track = new JSObject();
            track.put("artist", identity[0]);
            track.put("title", identity[1]);
            track.put("providedTime", providedTime);
            tracks.put(track);
            durationSeconds = Math.max(durationSeconds, (int) providedTime);
        }
        if (tracks.length() < 3) return null;
        JSObject result = new JSObject();
        result.put("sourceUrl", sourceUrl);
        result.put("pageTitle", document.title().trim());
        result.put("durationSeconds", durationSeconds);
        result.put("tracks", tracks);
        return result;
    }

    private double parseClock(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)")
            .matcher(value);
        if (!matcher.find()) return 0;
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        return matcher.group(3) == null
            ? first * 60.0 + second
            : first * 3600.0 + second * 60.0 + Integer.parseInt(matcher.group(3));
    }

    private String[] splitArtistTitle(String value) {
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
        File destination = new File(directory, safeId + ".audio");
        if (destination.isFile()) {
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", "completed");
            result.put("localUri", Uri.fromFile(destination).toString());
            call.resolve(result);
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(call.getString("title", "Podmix"))
                .setDescription("Téléchargement pour l’écoute hors connexion")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationUri(Uri.fromFile(destination));
            DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            long requestId = manager.enqueue(request);
            downloads().edit()
                .putLong(id + ".requestId", requestId)
                .putString(id + ".path", destination.getAbsolutePath())
                .apply();
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("requestId", requestId);
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
        long requestId = preferences.getLong(id + ".requestId", -1);
        String path = preferences.getString(id + ".path", "");
        File file = path.isBlank() ? null : new File(path);
        if (file != null && file.isFile()) {
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", "completed");
            result.put("bytesDownloaded", file.length());
            result.put("localUri", Uri.fromFile(file).toString());
            call.resolve(result);
            return;
        }
        if (requestId < 0) {
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", "not_found");
            call.resolve(result);
            return;
        }
        DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(requestId))) {
            if (!cursor.moveToFirst()) {
                call.resolve(downloadNotFound(id));
                return;
            }
            int androidStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            JSObject result = new JSObject();
            result.put("id", id);
            result.put("status", downloadStatus(androidStatus));
            result.put("bytesDownloaded", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            result.put("totalBytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            result.put("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            call.resolve(result);
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
        long requestId = preferences.getLong(id + ".requestId", -1);
        String path = preferences.getString(id + ".path", "");
        if (requestId >= 0) {
            DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            manager.remove(requestId);
        }
        boolean removed = path.isBlank() || !new File(path).exists() || new File(path).delete();
        preferences.edit().remove(id + ".requestId").remove(id + ".path").apply();
        JSObject result = new JSObject();
        result.put("id", id);
        result.put("removed", removed);
        call.resolve(result);
    }

    @PluginMethod
    public void openCastPicker(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                CastContext castContext = CastContext.getSharedInstance(getActivity());
                CastSession current = castContext.getSessionManager().getCurrentCastSession();
                if (current != null && current.isConnected()) {
                    RemoteMediaClient remote = current.getRemoteMediaClient();
                    if (controller != null && remote != null && !castMediaId.isBlank()) {
                        long relativePosition = Math.max(0, remote.getApproximateStreamPosition() - castPositionOffsetMs);
                        boolean wasPlaying = remote.isPlaying();
                        controller.seekTo(relativePosition);
                        if (wasPlaying) controller.play();
                    }
                    castMediaId = "";
                    castPositionOffsetMs = 0;
                    castEndPositionMs = 0;
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
                if (controller != null && remote != null && !castMediaId.isBlank()) {
                    long relativePosition = Math.max(0, remote.getApproximateStreamPosition() - castPositionOffsetMs);
                    boolean wasPlaying = remote.isPlaying();
                    controller.seekTo(relativePosition);
                    if (wasPlaying) controller.play(); else controller.pause();
                }
                castMediaId = "";
                castPositionOffsetMs = 0;
                castEndPositionMs = 0;
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
                MediaInfo mediaInfo = new MediaInfo.Builder(url)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(contentType)
                    .setMetadata(metadata)
                    .build();
                long positionMs = Math.max(0, (long) (call.getDouble("positionSeconds", 0.0) * 1000));
                castPositionOffsetMs = Math.max(0, (long) (call.getDouble("positionOffsetSeconds", 0.0) * 1000));
                castEndPositionMs = Math.max(0, (long) (call.getDouble("endPositionSeconds", 0.0) * 1000));
                MediaItem localItem = controller == null ? null : controller.getCurrentMediaItem();
                castMediaId = localItem == null ? url : localItem.mediaId;
                session.getRemoteMediaClient().load(new MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(positionMs)
                    .build());
                if (controller != null) controller.pause();
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
        castPositionOffsetMs = Math.max(0, item.clippingConfiguration.startPositionMs);
        long clippingEnd = item.clippingConfiguration.endPositionMs;
        castEndPositionMs = clippingEnd > castPositionOffsetMs ? clippingEnd : 0;
        remote.load(new MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(autoplay)
            .setCurrentTime(castPositionOffsetMs)
            .build());
        mediaController.pause();
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
        new Thread(() -> {
            try {
                String setUri = soapEnvelope(
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURIMetaData>" +
                    xmlEscape(boseDidl(url, title)) + "</CurrentURIMetaData>" +
                    "<CurrentURI>" + xmlEscape(url) + "</CurrentURI>"
                );
                boseRequest(ip, 8091, "POST", "/AVTransport/Control", setUri,
                    "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI");
                Thread.sleep(200);
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
                JSObject result = new JSObject(); result.put("ok", true); call.resolve(result);
            } catch (Exception error) {
                call.reject("Diffusion Bose impossible", error);
            }
        }, "podmix-bose-play").start();
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
        new Thread(() -> {
            try {
                boseRequest(ip, "POST", "/key", "<key state=\"press\" sender=\"Gabbo\">" + key + "</key>");
                boseRequest(ip, "POST", "/key", "<key state=\"release\" sender=\"Gabbo\">" + key + "</key>");
                JSObject result = new JSObject(); result.put("ok", true); call.resolve(result);
            } catch (Exception error) {
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

    private String boseRequest(String ip, String method, String path, String body) throws Exception {
        return boseRequest(ip, 8090, method, path, body, null, 3000, 5000);
    }

    private String boseRequest(
        String ip, int port, String method, String path, String body, String soapAction
    ) throws Exception {
        return boseRequest(ip, port, method, path, body, soapAction, 3000, 5000);
    }

    private String boseRequest(
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
        JSObject result = new JSObject();
        result.put("playing", mediaController.isPlaying() || mediaController.getPlayWhenReady());
        result.put("positionSeconds", mediaController.getCurrentPosition() / 1000.0);
        result.put("durationSeconds", mediaController.getDuration() > 0 ? mediaController.getDuration() / 1000.0 : 0);
        result.put("playbackState", mediaController.getPlaybackState());
        MediaMetadata metadata = mediaController.getMediaMetadata();
        result.put("title", metadata.title == null ? "" : metadata.title.toString());
        result.put("artist", metadata.artist == null ? "" : metadata.artist.toString());
        result.put("queueIndex", mediaController.getCurrentMediaItemIndex());
        result.put("queueSize", mediaController.getMediaItemCount());
        result.put("hasNext", mediaController.hasNextMediaItem());
        result.put("hasPrevious", mediaController.hasPreviousMediaItem());
        MediaItem currentItem = mediaController.getCurrentMediaItem();
        result.put("mediaId", currentItem == null ? "" : currentItem.mediaId);
        result.put("error", playbackError);
        RemoteMediaClient remote = castClient();
        if (remote != null && !castMediaId.isBlank()) {
            long absolutePosition = remote.getApproximateStreamPosition();
            long absoluteDuration = remote.getStreamDuration();
            long end = castEndPositionMs > castPositionOffsetMs ? castEndPositionMs : absoluteDuration;
            result.put("playing", remote.isPlaying());
            result.put("positionSeconds", Math.max(0, absolutePosition - castPositionOffsetMs) / 1000.0);
            result.put("durationSeconds", end > castPositionOffsetMs ? (end - castPositionOffsetMs) / 1000.0 : 0);
            result.put("playbackState", remote.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE ? Player.STATE_IDLE : Player.STATE_READY);
            result.put("mediaId", castMediaId);
        }
        return result;
    }

    private void emitState() {
        if (controller != null) {
            notifyListeners("stateChanged", state(controller), true);
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
        emitState();
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
