package com.podmix.next.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Télécharge l'audio avec l'UID de Podmix plutôt qu'avec le DownloadManager
 * système. Ce dernier peut laisser une requête bloquée à 0 % sur certains
 * appareils Samsung lorsque le téléphone utilise un VPN.
 */
public final class OfflineDownloadWorker extends Worker {
    public static final String INPUT_ID = "id";
    public static final String INPUT_URL = "url";
    public static final String INPUT_PATH = "path";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    public OfflineDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        String id = getInputData().getString(INPUT_ID);
        String url = getInputData().getString(INPUT_URL);
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
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();
        try (Response response = client.newCall(new Request.Builder()
            .url(url)
            .header("User-Agent", "Podmix/1.0 Android")
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
