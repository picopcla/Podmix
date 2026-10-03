package com.podmix.next.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the short 1001Tracklists phase outside Capacitor's WebView.  Android
 * keeps this foreground service alive while the user switches applications.
 */
public final class WebTimestampService extends Service {
    public static final String EXTRA_API_URL = "apiUrl";
    private static final String TAG = "PodmixWebTimestamp";
    private static final String CHANNEL_ID = "podmix-web-timestamps";
    private static final int NOTIFICATION_ID = 4302;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String apiUrl = intent == null ? "" : intent.getStringExtra(EXTRA_API_URL);
        if (apiUrl == null || apiUrl.isBlank()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Recherche des timestamps Web…"));
        executor.execute(() -> {
            try {
                processQueue(apiUrl.replaceAll("/+$", ""));
            } catch (Exception error) {
                Log.e(TAG, "Worker Web interrompu", error);
            } finally {
                busy.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void processQueue(String apiUrl) throws Exception {
        // Le VPS prépare parfois l'épisode suivant quelques secondes après la
        // fin du précédent. Garder le service deux minutes évite de dépendre
        // d'un timer WebView qui serait suspendu en arrière-plan.
        int idlePolls = 0;
        while (idlePolls < 24 && !Thread.currentThread().isInterrupted()) {
            if (processOne(apiUrl)) {
                idlePolls = 0;
            } else {
                idlePolls++;
                Thread.sleep(5000);
            }
        }
    }

    private boolean processOne(String apiUrl) throws Exception {
        JSONObject job = request(apiUrl + "/v1/web-timestamp-jobs/next", "GET", null);
        String jobId = job.optString("id", "");
        if (jobId.isBlank()) return false;
        String failure = "";
        JSONArray candidates = job.optJSONArray("webCandidates");
        if (candidates == null) candidates = new JSONArray();
        for (int index = 0; index < Math.min(3, candidates.length()); index++) {
            JSONObject candidate = candidates.optJSONObject(index);
            if (candidate == null) continue;
            String rawUrl = candidate.optString("url", "");
            try {
                String url = PodmixPlayerPlugin.validate1001Url(rawUrl);
                String html = PodmixPlayerPlugin.fetch1001Html(url, candidate.optString("address", ""));
                JSObject parsed = PodmixPlayerPlugin.parse1001Html(html, url);
                if (parsed == null || parsed.getJSONArray("tracks").length() < 3) {
                    failure = appendFailure(failure, url + " : aucune tracklist exploitable");
                    continue;
                }
                JSONObject payload = new JSONObject();
                payload.put("sourceUrl", parsed.getString("sourceUrl"));
                payload.put("candidates", parsed.getJSONArray("tracks"));
                request(apiUrl + "/v1/web-timestamp-jobs/" + jobId, "POST", payload);
                Log.i(TAG, "Timestamping Web terminé " + jobId);
                return true;
            } catch (Exception error) {
                failure = appendFailure(failure, rawUrl + " : " + error.getMessage());
                Log.w(TAG, "Candidat Web rejeté " + rawUrl, error);
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("message", failure.isBlank() ? "Aucun candidat 1001 exploitable" : failure);
        request(apiUrl + "/v1/web-timestamp-jobs/" + jobId + "/failure", "POST", payload);
        return true;
    }

    private static String appendFailure(String current, String next) {
        String value = current.isBlank() ? next : current + " · " + next;
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private static JSONObject request(String address, String method, @Nullable JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (payload != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = "";
        if (stream != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            body = result.toString();
        } finally {
            connection.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("VPS HTTP " + code + " " + body);
        return body.isBlank() ? new JSONObject() : new JSONObject(body);
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.podmix.next.R.mipmap.ic_launcher)
            .setContentTitle("Podmix")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID, "Timestamps Web", NotificationManager.IMPORTANCE_LOW
        ));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
