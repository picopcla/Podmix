package com.podmix.next.player;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ArtworkProvider extends ContentProvider {
    private static final String PREFERENCES = "podmix-artwork";
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build();

    @Nullable
    public static Uri register(Context context, @Nullable String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        Uri remote = Uri.parse(remoteUrl);
        String scheme = remote.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
        String key = sha256(remoteUrl);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(key, remoteUrl)
            .apply();
        return new Uri.Builder()
            .scheme("content")
            .authority(context.getPackageName() + ".artwork")
            .appendPath(key)
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(
        @NonNull Uri uri,
        @Nullable String[] projection,
        @Nullable String selection,
        @Nullable String[] selectionArgs,
        @Nullable String sortOrder
    ) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        Context context = getContext();
        if (context == null) return "image/*";
        String remoteUrl = preferences(context).getString(uri.getLastPathSegment(), "");
        String path = Uri.parse(remoteUrl).getPath();
        if (path != null && path.toLowerCase().endsWith(".png")) return "image/png";
        if (path != null && path.toLowerCase().endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
        @NonNull Uri uri,
        @Nullable ContentValues values,
        @Nullable String selection,
        @Nullable String[] selectionArgs
    ) {
        return 0;
    }

    @NonNull
    @Override
    public synchronized ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
        throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Lecture seule");
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Contexte indisponible");
        String key = uri.getLastPathSegment();
        if (key == null || !key.matches("[a-f0-9]{64}")) {
            throw new FileNotFoundException("Illustration inconnue");
        }
        String remoteUrl = preferences(context).getString(key, "");
        if (remoteUrl.isBlank()) throw new FileNotFoundException("Illustration non enregistrée");

        File directory = new File(context.getCacheDir(), "android-auto-artwork");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new FileNotFoundException("Cache indisponible");
        }
        File cached = new File(directory, key);
        if (!cached.isFile() || cached.length() == 0) download(remoteUrl, cached);
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void download(String remoteUrl, File destination) throws FileNotFoundException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        Request request = new Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", "Podmix/1.0 (Android Auto artwork)")
            .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw new IOException("Illustration trop volumineuse");
            }
            try (
                InputStream input = body.byteStream();
                FileOutputStream output = new FileOutputStream(temporary)
            ) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("Illustration trop volumineuse");
                    output.write(buffer, 0, read);
                }
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Mise en cache impossible");
            }
        } catch (Exception error) {
            temporary.delete();
            FileNotFoundException failure = new FileNotFoundException(
                "Téléchargement de l’illustration impossible"
            );
            failure.initCause(error);
            throw failure;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponible", error);
        }
    }
}
