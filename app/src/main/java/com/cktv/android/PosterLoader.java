package com.cktv.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.WeakHashMap;
import java.util.Map;

final class PosterLoader {
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int CACHE_SIZE_KB = Math.max(4096, Math.min(16 * 1024,
            (int) (Runtime.getRuntime().maxMemory() / 1024L / 16L)));
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "CKTV-poster");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_SIZE_KB) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private static final Map<ImageView, String> REQUESTS = new WeakHashMap<>();

    private PosterLoader() {}

    static void load(ImageView view, String url) {
        load(view, url, "");
    }

    static void load(ImageView view, String url, String fallbackUrl) {
        if (url == null || url.isEmpty()) {
            view.setTag("");
            view.setImageDrawable(null);
            synchronized (REQUESTS) {
                REQUESTS.remove(view);
            }
            return;
        }
        String normalizedUrl = url.startsWith("//") ? "https:" + url : url.trim();
        view.setTag(normalizedUrl);
        view.setImageDrawable(null);
        synchronized (REQUESTS) {
            REQUESTS.put(view, normalizedUrl);
        }
        Bitmap cached = CACHE.get(normalizedUrl);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        int[] targetSize = targetSize(view);
        String fallback = fallbackUrl == null ? "" : fallbackUrl.trim();
        final String alternate = fallback.startsWith("//") ? "https:" + fallback : fallback;
        EXECUTOR.execute(() -> {
            if (!isCurrent(view, normalizedUrl)) return;
            Bitmap bitmap = null;
            Exception primaryError = null;
            try {
                bitmap = download(normalizedUrl, targetSize[0], targetSize[1]);
            } catch (Exception error) {
                primaryError = error;
            }
            if (bitmap == null && !alternate.isEmpty()) {
                if (!alternate.equals(normalizedUrl) && isCurrent(view, normalizedUrl)) {
                    try {
                        bitmap = download(alternate, targetSize[0], targetSize[1]);
                    } catch (Exception fallbackError) {
                        Log.w("CKTV", "Poster fallback could not be loaded: " + alternate,
                                fallbackError);
                    }
                }
            }
            if (bitmap == null) {
                if (primaryError != null) {
                    Log.w("CKTV", "Poster could not be loaded: " + normalizedUrl, primaryError);
                } else {
                    Log.w("CKTV", "Poster returned no decodable image: " + normalizedUrl);
                }
                return;
            }
            CACHE.put(normalizedUrl, bitmap);
            Bitmap loadedBitmap = bitmap;
            view.post(() -> {
                if (normalizedUrl.equals(view.getTag())) view.setImageBitmap(loadedBitmap);
            });
        });
    }

    private static boolean isCurrent(ImageView view, String url) {
        synchronized (REQUESTS) {
            return url.equals(REQUESTS.get(view));
        }
    }

    private static int[] targetSize(ImageView view) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 && view.getParent() instanceof android.view.View) {
            width = ((android.view.View) view.getParent()).getWidth();
        }
        if (height <= 0 && view.getLayoutParams() != null) {
            height = view.getLayoutParams().height;
        }
        float density = view.getResources().getDisplayMetrics().density;
        if (width <= 0) width = Math.round(240 * density);
        if (height <= 0) height = Math.round(320 * density);
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }

    private static Bitmap download(String requestUrl, int targetWidth, int targetHeight)
            throws Exception {
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
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_IMAGE_BYTES) {
                        throw new java.io.IOException("Poster image exceeds the 12 MB limit.");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] imageData = output.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(imageData, 0, imageData.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = sampleSize(
                        bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
                decode.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(imageData, 0, imageData.length, decode);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int sampleSize(int sourceWidth, int sourceHeight,
                                  int targetWidth, int targetHeight) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth
                || sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }
}
