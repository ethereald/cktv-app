package com.cktv.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class MediaItem {
    String id = "";
    String title = "";
    String poster = "";
    String year = "";
    String rating = "";
    String source = "";
    String sourceName = "";
    String description = "";
    String remark = "";
    final List<String> episodes = new ArrayList<>();

    static MediaItem fromJson(JSONObject json) {
        MediaItem item = new MediaItem();
        item.id = json.optString("id", "");
        item.title = json.optString("title", "");
        item.poster = json.optString("poster", "");
        item.year = json.optString("year", "");
        item.rating = json.optString("rate", "");
        item.source = json.optString("source", "");
        item.sourceName = json.optString("source_name", "");
        item.description = json.optString("desc", json.optString("description", ""));
        item.remark = json.optString("remarks", "");
        JSONArray list = json.optJSONArray("episodes");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String episode = list.optString(i, "");
                if (!episode.isEmpty()) item.episodes.add(episode);
            }
        }
        return item;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("title", title);
            json.put("poster", poster);
            json.put("year", year);
            json.put("rate", rating);
            json.put("source", source);
            json.put("source_name", sourceName);
            json.put("desc", description);
            json.put("remarks", remark);
            json.put("episodes", new JSONArray(episodes));
        } catch (Exception error) {
            throw new IllegalStateException("Could not serialize media item", error);
        }
        return json;
    }

    String identity() {
        return source.isEmpty() ? id + "|" + title : source + "|" + id;
    }
}
