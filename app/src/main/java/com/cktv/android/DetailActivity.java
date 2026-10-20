package com.cktv.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class DetailActivity extends Activity {
    private final List<MediaItem> sources = new ArrayList<>();
    private MediaItem selectedItem;
    private LinearLayout page;
    private LinearLayout sourceGrid;
    private GridLayout episodeGrid;
    private TextView titleView;
    private TextView metaView;
    private TextView descriptionView;
    private TextView sourceStatus;
    private TextView episodeStatus;
    private ImageView posterView;
    private Button favoriteButton;
    private Button searchOtherSourcesButton;
    private Button continueButton;
    private int detailRequestId;
    private boolean preferredSourceUsed;
    private boolean alternateSourcesSearched;
    private boolean resumeFromHistory;
    private boolean historyResumeStarted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            selectedItem = MediaItem.fromJson(new JSONObject(getIntent().getStringExtra("item")));
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟影片資訊。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        resumeFromHistory = getIntent().getBooleanExtra("resume_from_history", false);
        MediaItem preferred = LibraryStore.preferredSource(this, selectedItem.title);
        if (preferred != null) {
            selectedItem = preferred;
            preferredSourceUsed = true;
        }
        buildUi();
        if (preferredSourceUsed) {
            sources.add(selectedItem);
            renderSources();
            showSourceInfo(selectedItem);
            renderDetail(selectedItem);
            searchOtherSourcesButton.setVisibility(View.VISIBLE);
            sourceStatus.setText("上次成功播放片源：" + sourceLabel(selectedItem)
                    + "；將先使用此片源，失敗時再搜尋其他片源。");
        } else if (selectedItem.source.isEmpty()) loadSources(selectedItem.title);
        else {
            sources.add(selectedItem);
            renderSources();
            chooseSource(selectedItem);
            loadAlternateSources(selectedItem);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(TvUi.BACKGROUND);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(TvUi.dp(this, 38), TvUi.dp(this, 18),
                TvUi.dp(this, 38), TvUi.dp(this, 36));
        scroll.addView(page);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = TvUi.button(this, "返回");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back);
        TextView brand = TvUi.text(this, "CKTV  /  影片詳情", 16, TvUi.MUTED);
        brand.setPadding(TvUi.dp(this, 18), 0, 0, 0);
        toolbar.addView(brand);
        page.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, TvUi.dp(this, 54)));

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.TOP);
        hero.setPadding(0, TvUi.dp(this, 18), 0, 0);
        posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterView.setBackground(TvUi.background(TvUi.SURFACE, TvUi.dp(this, 8)));
        hero.addView(posterView, new LinearLayout.LayoutParams(
                TvUi.dp(this, 185), TvUi.dp(this, 278)));

        LinearLayout information = new LinearLayout(this);
        information.setOrientation(LinearLayout.VERTICAL);
        information.setPadding(TvUi.dp(this, 28), 0, 0, 0);
        titleView = TvUi.text(this, selectedItem.title, 30, Color.WHITE);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        information.addView(titleView);
        metaView = TvUi.text(this, "", 16, TvUi.MUTED);
        metaView.setPadding(0, TvUi.dp(this, 10), 0, 0);
        information.addView(metaView);
        descriptionView = TvUi.text(this, selectedItem.description, 16, 0xFFD6DBE7);
        descriptionView.setLineSpacing(TvUi.dp(this, 3), 1f);
        descriptionView.setPadding(0, TvUi.dp(this, 16), 0, TvUi.dp(this, 12));
        information.addView(descriptionView);
        favoriteButton = TvUi.button(this, "☆  加入收藏");
        favoriteButton.setOnClickListener(view -> toggleFavorite());
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 48));
        favoriteParams.topMargin = TvUi.dp(this, 6);
        information.addView(favoriteButton, favoriteParams);
        hero.addView(information, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sourcesHeading = TvUi.text(this, "選擇片源", 22, Color.WHITE);
        sourcesHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        sourcesHeading.setPadding(0, TvUi.dp(this, 24), 0, TvUi.dp(this, 8));
        page.addView(sourcesHeading);
        sourceStatus = TvUi.text(this, "正在尋找可播放片源…", 14, TvUi.MUTED);
        page.addView(sourceStatus);
        sourceGrid = new LinearLayout(this);
        sourceGrid.setOrientation(LinearLayout.VERTICAL);
        page.addView(sourceGrid);
        searchOtherSourcesButton = TvUi.button(this, "搜尋其他片源");
        searchOtherSourcesButton.setVisibility(View.GONE);
        searchOtherSourcesButton.setOnClickListener(view -> {
            alternateSourcesSearched = true;
            searchOtherSourcesButton.setEnabled(false);
            searchOtherSourcesButton.setText("正在搜尋其他片源…");
            loadAlternateSources(selectedItem);
        });
        page.addView(searchOtherSourcesButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 46)));

        TextView episodesHeading = TvUi.text(this, "選集", 22, Color.WHITE);
        episodesHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        episodesHeading.setPadding(0, TvUi.dp(this, 20), 0, TvUi.dp(this, 8));
        page.addView(episodesHeading);
        episodeStatus = TvUi.text(this, "選擇片源後載入劇集。", 14, TvUi.MUTED);
        page.addView(episodeStatus);
        continueButton = TvUi.button(this, "繼續播放");
        continueButton.setVisibility(View.GONE);
        continueButton.setOnClickListener(view -> {
            MediaItem item = selectedItem;
            LibraryStore.Progress progress = LibraryStore.playbackProgress(
                    this, item.title);
            if (progress != null && progress.episodeIndex < item.episodes.size()) {
                play(item, progress.episodeIndex, progress.positionMs);
            }
        });
        page.addView(continueButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 48)));
        episodeGrid = new GridLayout(this);
        episodeGrid.setColumnCount(6);
        page.addView(episodeGrid);

        setContentView(scroll);
        String posterUrl = ApiClient.imageUrl(this, selectedItem.poster);
        PosterLoader.load(posterView, posterUrl.isEmpty() ? selectedItem.poster : posterUrl,
                selectedItem.poster);
        refreshFavoriteButton();
    }

    private void loadSources(String query) {
        sourceStatus.setText("正在尋找《" + query + "》的可播放片源…");
        ApiClient.search(this, query, new ApiClient.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray values) {
                sources.clear();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value != null) {
                        MediaItem result = MediaItem.fromJson(value);
                        if (!result.source.isEmpty() && !result.id.isEmpty()) sources.add(result);
                    }
                }
                renderSources();
                if (sources.isEmpty()) sourceStatus.setText("找不到可播放片源。");
                else {
                    sourceStatus.setText("找到 " + sources.size() + " 個片源，正在載入劇集…");
                    chooseSource(sources.get(0));
                }
            }

            @Override public void onError(String message) {
                sourceStatus.setText("尋找片源失敗：" + message);
            }
        });
    }

    private void loadAlternateSources(MediaItem selected) {
        ApiClient.search(this, selected.title, new ApiClient.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray values) {
                if (isFinishing()) return;
                String titleKey = normalizeTitle(selected.title);
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value == null) continue;
                    MediaItem candidate = MediaItem.fromJson(value);
                    if (!candidate.source.isEmpty() && !candidate.id.isEmpty()
                            && titleKey.equals(normalizeTitle(candidate.title))
                            && !containsSource(candidate)) {
                        sources.add(candidate);
                    }
                }
                renderSources();
                if (searchOtherSourcesButton != null) {
                    searchOtherSourcesButton.setEnabled(true);
                    searchOtherSourcesButton.setText("搜尋其他片源");
                    searchOtherSourcesButton.setVisibility(View.GONE);
                }
                if (preferredSourceUsed && selectedItem.identity().equals(selected.identity())) {
                    sourceStatus.setText("已載入 " + sources.size()
                            + " 個片源；播放失敗時將自動嘗試其他片源。");
                }
            }

            @Override public void onError(String message) {
                if (!isFinishing() && selectedItem.identity().equals(selected.identity())) {
                    if (searchOtherSourcesButton != null) {
                        searchOtherSourcesButton.setEnabled(true);
                        searchOtherSourcesButton.setText("搜尋其他片源");
                    }
                    String sourceName = selected.sourceName.isEmpty()
                            ? selected.source : selected.sourceName;
                    sourceStatus.setText("目前片源：" + sourceName
                            + "（無法載入自動備援清單：" + message + "）");
                }
            }
        });
    }

    private boolean containsSource(MediaItem item) {
        for (MediaItem source : sources) {
            if (source.identity().equals(item.identity())) return true;
        }
        return false;
    }

    private void renderSources() {
        sourceGrid.removeAllViews();
        for (int start = 0; start < sources.size(); start += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = start; i < Math.min(start + 4, sources.size()); i++) {
                MediaItem item = sources.get(i);
                String label = item.sourceName.isEmpty() ? item.source : item.sourceName;
                if (!item.year.isEmpty()) label += "  ·  " + item.year;
                Button button = TvUi.button(this, label);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 46));
                params.setMargins(0, 0, TvUi.dp(this, 10), TvUi.dp(this, 8));
                row.addView(button, params);
                button.setOnClickListener(view -> chooseSource(item));
            }
            sourceGrid.addView(row);
        }
    }

    private void chooseSource(MediaItem item) {
        int requestId = ++detailRequestId;
        preferredSourceUsed = preferredSourceUsed
                && selectedItem.identity().equals(item.identity());
        selectedItem = item;
        showSourceInfo(item);
        episodeGrid.removeAllViews();
        episodeStatus.setText("正在載入劇集…");
        sourceStatus.setText("目前片源：" + sourceLabel(item));
        ApiClient.detail(this, item.source, item.id, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject value) {
                if (requestId != detailRequestId || isFinishing()) return;
                MediaItem detail = MediaItem.fromJson(value);
                if (detail.title.isEmpty()) detail.title = item.title;
                if (detail.poster.isEmpty()) detail.poster = item.poster;
                if (detail.description.isEmpty()) detail.description = item.description;
                if (detail.source.isEmpty()) detail.source = item.source;
                if (detail.sourceName.isEmpty()) detail.sourceName = item.sourceName;
                selectedItem = detail;
                renderDetail(detail);
            }

            @Override public void onError(String message) {
                if (requestId != detailRequestId || isFinishing()) return;
                episodeStatus.setText("載入劇集失敗：" + message);
            }
        });
    }

    private void showSourceInfo(MediaItem item) {
        titleView.setText(item.title);
        String meta = item.year;
        if (!item.sourceName.isEmpty()) meta += (meta.isEmpty() ? "" : "  ·  ") + item.sourceName;
        if (!item.remark.isEmpty()) meta += (meta.isEmpty() ? "" : "  ·  ") + item.remark;
        metaView.setText(meta);
        descriptionView.setText(item.description);
        PosterLoader.load(posterView, ApiClient.imageUrl(this, item.poster));
        refreshFavoriteButton();
    }

    private void renderDetail(MediaItem detail) {
        if (detail.episodes.isEmpty()) {
            episodeStatus.setText("此片源沒有提供可播放劇集。");
            continueButton.setVisibility(View.GONE);
            return;
        }
        episodeStatus.setText("共 " + detail.episodes.size() + " 集");
        refreshContinueButton(detail);
        for (int i = 0; i < detail.episodes.size(); i++) {
            final int index = i;
            String label = detail.episodes.size() == 1
                    ? "立即播放" : String.format(Locale.ROOT, "第 %02d 集", i + 1);
            Button button = TvUi.button(this, label);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = TvUi.dp(this, 176);
            params.height = TvUi.dp(this, 54);
            params.setMargins(0, 0, TvUi.dp(this, 10), TvUi.dp(this, 10));
            episodeGrid.addView(button, params);
            button.setOnClickListener(view -> play(detail, index));
        }
        resumeFromHistory(detail);
        if (episodeGrid.getChildCount() > 0) TvUi.focusOn(episodeGrid.getChildAt(0));
    }

    private void resumeFromHistory(MediaItem item) {
        if (!resumeFromHistory || historyResumeStarted) return;
        LibraryStore.Progress progress = LibraryStore.playbackProgress(this, item.title);
        if (progress == null || progress.episodeIndex >= item.episodes.size()) return;
        historyResumeStarted = true;
        play(item, progress.episodeIndex, progress.positionMs);
    }

    private void play(MediaItem item, int episode) {
        play(item, episode, 0);
    }

    private void refreshContinueButton(MediaItem item) {
        LibraryStore.Progress progress = LibraryStore.playbackProgress(this, item.title);
        if (progress == null || progress.episodeIndex >= item.episodes.size()) {
            continueButton.setVisibility(View.GONE);
            return;
        }
        String episodeLabel = item.episodes.size() == 1
                ? "正片" : String.format(Locale.ROOT, "第 %02d 集", progress.episodeIndex + 1);
        continueButton.setText("繼續播放 " + episodeLabel + " · "
                + formatPosition(progress.positionMs));
        continueButton.setVisibility(View.VISIBLE);
    }

    private static String formatPosition(long positionMs) {
        long totalSeconds = Math.max(0, positionMs / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void play(MediaItem item, int episode, long resumePositionMs) {
        String url = episodeUrl(item.episodes.get(episode));
        String episodeLabel = item.episodes.size() == 1
                ? "正片" : String.format(Locale.ROOT, "第 %02d 集", episode + 1);
        String namedEpisode = item.episodes.get(episode);
        if (namedEpisode.contains("$")) {
            episodeLabel = namedEpisode.split("\\$", 2)[0].trim();
        }
        LibraryStore.addHistory(this, item);
        if ("huangguo".equals(item.source)) {
            ArrayList<String> urls = new ArrayList<>();
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<String> sourceItems = new ArrayList<>();
            urls.add(ApiClient.baseUrl(this) + (url.startsWith("/") ? url : "/" + url));
            labels.add(sourceLabel(item));
            sourceItems.add(item.toJson().toString());
            launchPlayer(item, episodeLabel, urls, labels, sourceItems, episode,
                    resumePositionMs, false);
            return;
        }
        ArrayList<String> urls = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        addCandidate(urls, labels, sourceItems, url, sourceLabel(item), item);
        String titleKey = normalizeTitle(item.title);
        for (MediaItem candidate : sources) {
            if ("huangguo".equals(candidate.source)
                    || candidate.episodes.size() <= episode
                    || !titleKey.equals(normalizeTitle(candidate.title))) {
                continue;
            }
            String candidateUrl = episodeUrl(candidate.episodes.get(episode));
            addCandidate(urls, labels, sourceItems, candidateUrl,
                    sourceLabel(candidate), candidate);
        }
        launchPlayer(item, episodeLabel, urls, labels, sourceItems, episode,
                resumePositionMs,
                preferredSourceUsed && !alternateSourcesSearched);
    }

    private static String episodeUrl(String episode) {
        String value = episode == null ? "" : episode.trim();
        if (value.contains("$")) value = value.split("\\$", 2)[1].trim();
        return value;
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？：；、（）【】《》「」『』·…]", "");
    }

    private static void addCandidate(ArrayList<String> urls, ArrayList<String> labels,
                                     ArrayList<String> sourceItems, String url, String label,
                                     MediaItem sourceItem) {
        String candidate = url == null ? "" : url.trim();
        if (candidate.isEmpty() || urls.contains(candidate)) return;
        urls.add(candidate);
        labels.add(label == null || label.trim().isEmpty() ? "其他片源" : label.trim());
        sourceItems.add(sourceItem.toJson().toString());
    }

    private void launchPlayer(MediaItem item, String episodeLabel,
                              ArrayList<String> urls, ArrayList<String> labels,
                              ArrayList<String> sourceItems, int episodeIndex,
                              long resumePositionMs, boolean searchOnFailure) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putStringArrayListExtra("candidate_urls", urls);
        intent.putStringArrayListExtra("source_labels", labels);
        intent.putStringArrayListExtra("source_items", sourceItems);
        intent.putExtra("title", item.title);
        intent.putExtra("episode", episodeLabel);
        intent.putExtra("episode_index", episodeIndex);
        intent.putExtra("resume_position_ms", resumePositionMs);
        intent.putExtra("search_sources_on_failure", searchOnFailure);
        startActivity(intent);
    }

    private static String sourceLabel(MediaItem item) {
        return item.sourceName.isEmpty() ? item.source : item.sourceName;
    }

    private void toggleFavorite() {
        boolean added = LibraryStore.toggleFavorite(this, selectedItem);
        refreshFavoriteButton();
        Toast.makeText(this, added ? "已加入收藏" : "已取消收藏",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshFavoriteButton() {
        boolean saved = LibraryStore.isFavorite(this, selectedItem);
        favoriteButton.setText(saved ? "★  已收藏" : "☆  加入收藏");
    }
}
