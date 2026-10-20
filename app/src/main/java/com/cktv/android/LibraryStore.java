package com.cktv.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LibraryStore {
    private static final String PREFS = "cktv_library";
    private static final String FAVORITES = "favorites";
    private static final String HISTORY = "history";
    private static final String WORKING_SOURCES = "working_sources";
    private static final String FAILED_SOURCES = "failed_sources";
    private static final String PLAYBACK_PROGRESS = "playback_progress";

    private LibraryStore() {}

    static final class Progress {
        final int episodeIndex;
        final long positionMs;
        final long durationMs;

        Progress(int episodeIndex, long positionMs, long durationMs) {
            this.episodeIndex = episodeIndex;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    static List<MediaItem> favorites(Context context) {
        List<MediaItem> saved = read(context, FAVORITES);
        boolean changed = false;
        for (MediaItem item : saved) {
            if (!item.id.isEmpty() || !item.source.isEmpty() || !item.sourceName.isEmpty()
                    || !item.episodes.isEmpty() || !item.remark.isEmpty()) {
                item.id = "";
                item.source = "";
                item.sourceName = "";
                item.episodes.clear();
                item.remark = "";
                changed = true;
            }
        }
        if (changed) write(context, FAVORITES, saved);
        return saved;
    }

    static List<MediaItem> history(Context context) {
        return read(context, HISTORY);
    }

    static boolean isFavorite(Context context, MediaItem item) {
        String key = titleKey(item.title);
        for (MediaItem saved : favorites(context)) {
            if (key.equals(titleKey(saved.title))) return true;
        }
        return false;
    }

    static boolean toggleFavorite(Context context, MediaItem item) {
        List<MediaItem> saved = favorites(context);
        String key = titleKey(item.title);
        boolean removed = saved.removeIf(value -> key.equals(titleKey(value.title)));
        if (!removed) {
            MediaItem favorite = MediaItem.fromJson(item.toJson());
            favorite.id = "";
            favorite.source = "";
            favorite.sourceName = "";
            favorite.episodes.clear();
            favorite.remark = "";
            saved.add(0, favorite);
        }
        write(context, FAVORITES, saved);
        return !removed;
    }

    static void addHistory(Context context, MediaItem item) {
        List<MediaItem> saved = history(context);
        saved.removeIf(value -> value.identity().equals(item.identity()));
        saved.add(0, item);
        while (saved.size() > 60) saved.remove(saved.size() - 1);
        write(context, HISTORY, saved);
    }

    static MediaItem preferredSource(Context context, String title) {
        String key = titleKey(title);
        if (key.isEmpty()) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject workingSources = new JSONObject(prefs.getString(WORKING_SOURCES, "{}"));
            JSONObject saved = workingSources.optJSONObject(key);
            if (saved != null) {
                MediaItem item = MediaItem.fromJson(saved);
                if (!item.source.isEmpty() && !item.episodes.isEmpty()
                        && !isFailed(context, key, item.source)) return item;
            }
        } catch (Exception error) {
            Log.w("CKTV", "Could not read preferred playback sources.", error);
        }
        for (MediaItem item : history(context)) {
            if (key.equals(titleKey(item.title)) && !item.source.isEmpty()
                    && !item.episodes.isEmpty() && !isFailed(context, key, item.source)) {
                return item;
            }
        }
        return null;
    }

    static void rememberWorkingSource(Context context, MediaItem item) {
        if (item == null || item.source.isEmpty() || item.episodes.isEmpty()
                || titleKey(item.title).isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, JSONObject> saved = new LinkedHashMap<>();
        try {
            JSONObject existing = new JSONObject(prefs.getString(WORKING_SOURCES, "{}"));
            java.util.Iterator<String> keys = existing.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject value = existing.optJSONObject(key);
                if (value != null) saved.put(key, value);
            }
        } catch (Exception error) {
            Log.w("CKTV", "Could not read preferred playback sources before updating.", error);
        }
        String key = titleKey(item.title);
        saved.remove(key);
        saved.put(key, item.toJson());
        while (saved.size() > 60) saved.remove(saved.keySet().iterator().next());
        JSONObject values = new JSONObject();
        try {
            for (Map.Entry<String, JSONObject> entry : saved.entrySet()) {
                values.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not save preferred playback source", error);
        }
        JSONObject failed = readJsonObject(prefs, FAILED_SOURCES);
        JSONArray failedForTitle = failed.optJSONArray(key);
        if (failedForTitle != null) {
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < failedForTitle.length(); i++) {
                String source = failedForTitle.optString(i, "");
                if (!item.source.equals(source)) remaining.put(source);
            }
            if (remaining.length() == 0) failed.remove(key);
            else {
                try {
                    failed.put(key, remaining);
                } catch (Exception error) {
                    throw new IllegalStateException("Could not update failed playback sources", error);
                }
            }
        }
        prefs.edit().putString(WORKING_SOURCES, values.toString())
                .putString(FAILED_SOURCES, failed.toString()).apply();
        addHistory(context, item);
    }

    static void markSourceFailed(Context context, String title, MediaItem item) {
        if (item == null || item.source.isEmpty()) return;
        String key = titleKey(title);
        if (key.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject failed = readJsonObject(prefs, FAILED_SOURCES);
        JSONArray sources = failed.optJSONArray(key);
        if (sources == null) sources = new JSONArray();
        boolean alreadySaved = false;
        for (int i = 0; i < sources.length(); i++) {
            if (item.source.equals(sources.optString(i))) alreadySaved = true;
        }
        if (!alreadySaved) sources.put(item.source);
        JSONObject working = readJsonObject(prefs, WORKING_SOURCES);
        JSONObject preferred = working.optJSONObject(key);
        if (preferred != null && item.source.equals(preferred.optString("source", ""))) {
            working.remove(key);
        }
        try {
            failed.put(key, sources);
        } catch (Exception error) {
            throw new IllegalStateException("Could not record failed playback source", error);
        }
        prefs.edit().putString(WORKING_SOURCES, working.toString())
                .putString(FAILED_SOURCES, failed.toString()).apply();
    }

    static Progress playbackProgress(Context context, String title) {
        JSONObject saved = readJsonObject(context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE), PLAYBACK_PROGRESS).optJSONObject(titleKey(title));
        if (saved == null) return null;
        int episodeIndex = saved.optInt("episode_index", -1);
        long positionMs = saved.optLong("position_ms", 0);
        long durationMs = saved.optLong("duration_ms", 0);
        if (episodeIndex < 0 || positionMs < 10_000) return null;
        return new Progress(episodeIndex, positionMs, durationMs);
    }

    static void savePlaybackProgress(Context context, String title, int episodeIndex,
                                     long positionMs, long durationMs) {
        String key = titleKey(title);
        if (key.isEmpty() || episodeIndex < 0 || positionMs < 10_000) return;
        JSONObject progress = readJsonObject(context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE), PLAYBACK_PROGRESS);
        if (durationMs > 0 && positionMs >= durationMs - 15_000) {
            progress.remove(key);
        } else {
            JSONObject saved = new JSONObject();
            try {
                saved.put("episode_index", episodeIndex);
                saved.put("position_ms", positionMs);
                saved.put("duration_ms", durationMs);
                progress.put(key, saved);
            } catch (Exception error) {
                throw new IllegalStateException("Could not save playback progress", error);
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PLAYBACK_PROGRESS, progress.toString()).apply();
    }

    static void clearPlaybackProgress(Context context, String title, int episodeIndex) {
        String key = titleKey(title);
        JSONObject progress = readJsonObject(context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE), PLAYBACK_PROGRESS);
        JSONObject saved = progress.optJSONObject(key);
        if (saved == null || saved.optInt("episode_index", -1) != episodeIndex) return;
        progress.remove(key);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PLAYBACK_PROGRESS, progress.toString()).apply();
    }

    private static boolean isFailed(Context context, String titleKey, String source) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray sources = readJsonObject(prefs, FAILED_SOURCES).optJSONArray(titleKey);
        if (sources == null) return false;
        for (int i = 0; i < sources.length(); i++) {
            if (source.equals(sources.optString(i))) return true;
        }
        return false;
    }

    private static JSONObject readJsonObject(SharedPreferences prefs, String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (Exception error) {
            Log.w("CKTV", "Could not read saved library metadata: " + key, error);
            return new JSONObject();
        }
    }

    private static String titleKey(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？：；、（）【】《》「」『』·…]", "");
    }

    private static List<MediaItem> read(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(key, "[]");
        List<MediaItem> result = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(raw);
            for (int i = 0; i < list.length(); i++) {
                JSONObject value = list.optJSONObject(i);
                if (value != null) result.add(MediaItem.fromJson(value));
            }
        } catch (Exception error) {
            Log.w("CKTV", "Could not read saved library list: " + key, error);
        }
        return result;
    }

    private static void write(Context context, String key, List<MediaItem> items) {
        JSONArray values = new JSONArray();
        for (MediaItem item : items) values.put(item.toJson());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, values.toString()).apply();
    }
}
