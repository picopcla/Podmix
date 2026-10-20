package com.podmix.next.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Télécharge l'audio avec l'UID de Podmix plutôt qu'avec le DownloadManager
 * système. Ce dernier peut laisser une requête bloquée à 0 % sur certains
 * appareils Samsung lorsque le téléphone utilise un VPN.
 */
public final class OfflineDownloadWorker extends Worker {
    private static final String TAG = "OfflineDownloadWorker";
    public static final String INPUT_ID = "id";
    public static final String INPUT_URL = "url";
    public static final String INPUT_EXTRACT_FROM = "extractFrom";
    public static final String INPUT_PATH = "path";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int PREFERRED_AUDIO_BITRATE_KBPS = 128;
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build();
    private static volatile boolean newPipeInitialized;

    public OfflineDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        String id = getInputData().getString(INPUT_ID);
        String url = getInputData().getString(INPUT_URL);
        String extractFrom = getInputData().getString(INPUT_EXTRACT_FROM);
        String path = getInputData().getString(INPUT_PATH);
        if (id == null || id.isBlank() || url == null || url.isBlank() || path == null || path.isBlank()) {
            return Result.failure();
        }

        SharedPreferences preferences = getApplicationContext().getSharedPreferences("podmix_downloads", Context.MODE_PRIVATE);
        File destination = new File(path);
        File partial = new File(path + ".part");
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            saveFailure(preferences, id, "Dossier hors connexion inaccessible");
            return Result.failure();
        }

        preferences.edit().putString(id + ".status", "downloading").remove(id + ".error").apply();
        String downloadUrl = url;
        boolean extractedOnDevice = false;
        if (extractFrom != null && !extractFrom.isBlank()) {
            try {
                downloadUrl = resolveAudioUrl(extractFrom);
                extractedOnDevice = true;
            } catch (Exception error) {
                Log.w(TAG, "Embedded extraction failed; using relay fallback", error);
                // Le relais historique reste le filet de sécurité si un site
                // change ou si l'extracteur embarqué ne peut pas répondre.
                downloadUrl = url;
            }
        }
        try (Response response = HTTP_CLIENT.newCall(new Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", extractedOnDevice ? USER_AGENT : "Podmix/1.0 Android")
            .header("Accept", "audio/*,*/*;q=0.8")
            .build()).execute()) {
            if (!response.isSuccessful()) throw new HttpStatusException(response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Réponse audio vide");
            long total = body.contentLength();
            preferences.edit().putLong(id + ".totalBytes", total).putLong(id + ".bytesDownloaded", 0).apply();
            if (partial.exists() && !partial.delete()) throw new IOException("Impossible de reprendre le fichier partiel");

            long downloaded = 0;
            long lastSavedAt = 0;
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Téléchargement interrompu");
                    output.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastSavedAt >= 250) {
                        preferences.edit().putLong(id + ".bytesDownloaded", downloaded).apply();
                        lastSavedAt = now;
                    }
                }
                output.getFD().sync();
            }
            if (destination.exists() && !destination.delete()) throw new IOException("Impossible de remplacer le fichier audio");
            if (!partial.renameTo(destination)) throw new IOException("Finalisation du fichier audio impossible");
            preferences.edit()
                .putString(id + ".status", "completed")
                .putLong(id + ".bytesDownloaded", downloaded)
                .putLong(id + ".totalBytes", total >= 0 ? total : downloaded)
                .remove(id + ".error")
                .apply();
            return Result.success();
        } catch (Exception error) {
            if (partial.exists()) partial.delete();
            if (isTransient(error) && getRunAttemptCount() < MAX_RETRY_ATTEMPTS) {
                preferences.edit()
                    .putString(id + ".status", "queued")
                    .putString(id + ".error", "Nouvelle tentative automatique")
                    .apply();
                return Result.retry();
            }
            saveFailure(preferences, id, error.getMessage());
            return Result.failure();
        }
    }

    private static String resolveAudioUrl(String pageUrl) throws Exception {
        ensureNewPipeInitialized();
        StreamExtractor extractor = NewPipe.getServiceByUrl(pageUrl).getStreamExtractor(pageUrl);
        extractor.fetchPage();
        List<AudioStream> streams = extractor.getAudioStreams();
        if (streams == null || streams.isEmpty()) throw new IOException("Aucun flux audio direct");

        AudioStream preferred = null;
        int preferredDistance = Integer.MAX_VALUE;
        AudioStream highest = null;
        int highestBitrate = Integer.MIN_VALUE;
        for (AudioStream stream : streams) {
            if (stream == null || !stream.isUrl() || stream.getUrl() == null || stream.getUrl().isBlank()) continue;
            int bitrate = bitrateKbps(stream);
            if (highest == null || bitrate > highestBitrate) {
                highest = stream;
                highestBitrate = bitrate;
            }
            if (isM4a(stream)) {
                int distance = Math.abs(bitrate - PREFERRED_AUDIO_BITRATE_KBPS);
                if (preferred == null || distance < preferredDistance
                        || (distance == preferredDistance && bitrate > bitrateKbps(preferred))) {
                    preferred = stream;
                    preferredDistance = distance;
                }
            }
        }
        AudioStream selected = preferred != null ? preferred : highest;
        if (selected == null) throw new IOException("Aucune URL audio directe");
        return selected.getUrl();
    }

    private static int bitrateKbps(AudioStream stream) {
        int bitrate = stream.getAverageBitrate();
        if (bitrate <= 0) bitrate = stream.getBitrate();
        if (bitrate <= 0) return 0;
        return bitrate > 10_000 ? Math.round(bitrate / 1_000f) : bitrate;
    }

    private static boolean isM4a(AudioStream stream) {
        if (stream.getFormat() == MediaFormat.M4A) return true;
        String codec = stream.getCodec();
        return codec != null && codec.toLowerCase(Locale.ROOT).contains("mp4a");
    }

    private static void ensureNewPipeInitialized() {
        if (newPipeInitialized) return;
        synchronized (OfflineDownloadWorker.class) {
            if (!newPipeInitialized) {
                NewPipe.init(new OkHttpNewPipeDownloader(HTTP_CLIENT));
                newPipeInitialized = true;
            }
        }
    }

    private static final class OkHttpNewPipeDownloader extends Downloader {
        private final OkHttpClient client;

        OkHttpNewPipeDownloader(OkHttpClient client) {
            this.client = client;
        }

        @NonNull
        @Override
        public org.schabi.newpipe.extractor.downloader.Response execute(
                @NonNull org.schabi.newpipe.extractor.downloader.Request request) throws IOException {
            Request.Builder builder = new Request.Builder().url(request.url());
            boolean hasUserAgent = false;
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                if (header.getKey().equalsIgnoreCase("User-Agent")) hasUserAgent = true;
                for (String value : header.getValue()) builder.addHeader(header.getKey(), value);
            }
            if (!hasUserAgent) builder.header("User-Agent", USER_AGENT);

            String method = request.httpMethod().toUpperCase(Locale.ROOT);
            RequestBody body = ("GET".equals(method) || "HEAD".equals(method))
                ? null
                : RequestBody.create(request.dataToSend() == null ? new byte[0] : request.dataToSend(), null);
            builder.method(method, body);
            try (Response response = client.newCall(builder.build()).execute()) {
                ResponseBody responseBody = response.body();
                return new org.schabi.newpipe.extractor.downloader.Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBody == null ? "" : responseBody.string(),
                    response.request().url().toString()
                );
            }
        }
    }

    static boolean isTransient(Exception error) {
        if (error instanceof HttpStatusException status) {
            return status.code == 408 || status.code == 429 || status.code >= 500;
        }
        return error instanceof IOException;
    }

    static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("Serveur audio : HTTP " + code);
            this.code = code;
        }
    }

    private static void saveFailure(SharedPreferences preferences, String id, String message) {
        preferences.edit().putString(id + ".status", "failed")
            .putString(id + ".error", message == null ? "Téléchargement impossible" : message).apply();
    }
}
