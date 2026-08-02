package com.podmix.next.player;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

public class FeedRefreshWorker extends Worker {
    private static final String CHANNEL_ID = "podmix-new-episodes";
    private static final String TAG = "PodmixFeedRefresh";

    public FeedRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String raw = context.getSharedPreferences("podmix-subscriptions", Context.MODE_PRIVATE)
            .getString("items", "[]");
        int newCount = 0;
        String latestTitle = "";
        try {
            JSONArray subscriptions = new JSONArray(raw);
            for (int index = 0; index < subscriptions.length(); index++) {
                JSONObject subscription = subscriptions.getJSONObject(index);
                String sourceId = subscription.optString("id", "");
                String feedUrl = subscription.optString("feedUrl", "");
                if (sourceId.isBlank() || feedUrl.isBlank()) continue;
                URI uri = URI.create(feedUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme())) continue;
                FeedState state = fetch(feedUrl);
                String preferenceKey = "known." + Integer.toHexString(sourceId.hashCode());
                String previousRaw = context.getSharedPreferences("podmix-feed-state", Context.MODE_PRIVATE)
                    .getString(preferenceKey, "");
                Set<String> previous = new HashSet<>();
                if (!previousRaw.isBlank()) {
                    JSONArray known = new JSONArray(previousRaw);
                    for (int item = 0; item < known.length(); item++) previous.add(known.getString(item));
                    for (String id : state.ids) if (!previous.contains(id)) newCount++;
                    if (!state.latestTitle.isBlank()) latestTitle = state.latestTitle;
                }
                context.getSharedPreferences("podmix-feed-state", Context.MODE_PRIVATE)
                    .edit().putString(preferenceKey, new JSONArray(state.ids).toString()).apply();
            }
            if (newCount > 0) notifyNewEpisodes(newCount, latestTitle);
            return Result.success();
        } catch (Exception error) {
            Log.e(TAG, "Feed refresh failed", error);
            return Result.retry();
        }
    }

    private FeedState fetch(String feedUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(feedUrl).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "PodmixAndroid/2.0");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(connection.getInputStream());
            NodeList entries = document.getElementsByTagName("item");
            if (entries.getLength() == 0) entries = document.getElementsByTagName("entry");
            Set<String> ids = new HashSet<>();
            String latestTitle = "";
            for (int index = 0; index < Math.min(100, entries.getLength()); index++) {
                Element entry = (Element) entries.item(index);
                String id = text(entry, "guid");
                if (id.isBlank()) id = text(entry, "id");
                if (id.isBlank()) id = text(entry, "link");
                if (!id.isBlank()) ids.add(id);
                if (latestTitle.isBlank()) latestTitle = text(entry, "title");
            }
            return new FeedState(ids, latestTitle);
        } finally {
            connection.disconnect();
        }
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : "";
    }

    private void notifyNewEpisodes(int count, String latestTitle) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Nouveaux épisodes", NotificationManager.IMPORTANCE_DEFAULT
            ));
        }
        String text = count == 1 && !latestTitle.isBlank() ? latestTitle : count + " nouveaux épisodes disponibles";
        manager.notify(4301, new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.podmix.next.R.mipmap.ic_launcher)
            .setContentTitle("Podmix")
            .setContentText(text)
            .setAutoCancel(true)
            .build());
    }

    private static final class FeedState {
        final Set<String> ids;
        final String latestTitle;

        FeedState(Set<String> ids, String latestTitle) {
            this.ids = ids;
            this.latestTitle = latestTitle;
        }
    }
}
