package com.cktv.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PosterLoader {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final int CACHE_SIZE_KB = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_SIZE_KB) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private PosterLoader() {}

    static void load(ImageView view, String url) {
        load(view, url, "");
    }

    static void load(ImageView view, String url, String fallbackUrl) {
        if (url == null || url.isEmpty()) {
            view.setTag("");
            view.setImageDrawable(null);
            return;
        }
        view.setTag(url);
        view.setImageDrawable(null);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            Exception primaryError = null;
            try {
                bitmap = download(url);
            } catch (Exception error) {
                primaryError = error;
            }
            String alternate = fallbackUrl == null ? "" : fallbackUrl.trim();
            if (bitmap == null && !alternate.isEmpty()) {
                if (alternate.startsWith("//")) alternate = "https:" + alternate;
                if (!alternate.equals(url)) {
                    try {
                        bitmap = download(alternate);
                    } catch (Exception fallbackError) {
                        Log.w("CKTV", "Poster fallback could not be loaded: " + alternate,
                                fallbackError);
                    }
                }
            }
            if (bitmap == null) {
                if (primaryError != null) {
                    Log.w("CKTV", "Poster could not be loaded: " + url, primaryError);
                } else {
                    Log.w("CKTV", "Poster returned no decodable image: " + url);
                }
                return;
            }
            CACHE.put(url, bitmap);
            Bitmap loadedBitmap = bitmap;
            view.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(loadedBitmap);
            });
        });
    }

    private static Bitmap download(String url) throws Exception {
        String requestUrl = url.startsWith("//") ? "https:" + url : url;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            connection.setRequestProperty("Referer", "https://tv.chuankangkk.top/");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Image request failed (HTTP " + status + ")");
            }
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("image/")) {
                throw new java.io.IOException("Image server returned " + contentType);
            }
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
