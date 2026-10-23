package com.cktv.android;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

@UnstableApi
public class PlayerActivity extends Activity {
    private static final String TAG = "CKTV-Player";
    private static final long PROGRESS_SAVE_INTERVAL_MS = 5000;
    private static final long SEEK_INCREMENT_MS = 10_000;
    private final ArrayList<String> playbackUrls = new ArrayList<>();
    private final ArrayList<String> sourceLabels = new ArrayList<>();
    private final ArrayList<String> sourceItems = new ArrayList<>();
    private final ArrayList<Integer> fallbackIndexes = new ArrayList<>();
    private final Set<String> attemptedProviders = new HashSet<>();
    private final ArrayList<MediaItem> fallbackCandidates = new ArrayList<>();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressSaver = new Runnable() {
        @Override public void run() {
            savePlaybackProgress();
            if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
            }
        }
    };
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView errorView;
    private int playbackAttempt;
    private String lastPlaybackFailure = "";
    private String fallbackTitle = "";
    private int fallbackEpisodeIndex;
    private long resumePositionMs;
    private boolean fallbackSearchEnabled;
    private boolean fallbackSearchStarted;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildPlayer();
        ArrayList<String> urls = getIntent().getStringArrayListExtra("candidate_urls");
        ArrayList<String> labels = getIntent().getStringArrayListExtra("source_labels");
        ArrayList<String> items = getIntent().getStringArrayListExtra("source_items");
        if (urls != null) playbackUrls.addAll(urls);
        if (labels != null) sourceLabels.addAll(labels);
        if (items != null) sourceItems.addAll(items);
        while (sourceItems.size() < playbackUrls.size()) sourceItems.add("");
        while (fallbackIndexes.size() < playbackUrls.size()) fallbackIndexes.add(-1);
        for (String sourceJson : sourceItems) {
            try {
                if (sourceJson != null && !sourceJson.isEmpty()) {
                    String source = MediaItem.fromJson(new JSONObject(sourceJson)).source;
                    if (!source.isEmpty()) attemptedProviders.add(source);
                }
            } catch (Exception error) {
                Log.w(TAG, "Could not read playback source metadata.", error);
            }
        }
        String url = getIntent().getStringExtra("url");
        if (playbackUrls.isEmpty() && url != null && !url.trim().isEmpty()) {
            playbackUrls.add(url.trim());
            sourceLabels.add("目前片源");
            sourceItems.add("");
            fallbackIndexes.add(-1);
        }
        fallbackTitle = getIntent().getStringExtra("title");
        fallbackEpisodeIndex = getIntent().getIntExtra("episode_index", 0);
        resumePositionMs = getIntent().getLongExtra("resume_position_ms", 0);
        fallbackSearchEnabled = getIntent().getBooleanExtra(
                "search_sources_on_failure", false);
        String title = getIntent().getStringExtra("title");
        String episode = getIntent().getStringExtra("episode");
        if (playbackUrls.isEmpty()) {
            showError("此集沒有可播放的網址。");
            return;
        }
        setTitle((title == null ? "CKTV" : title) + " · " + (episode == null ? "" : episode));
        startPlayback(0);
    }

    private void buildPlayer() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        errorView = new TextView(this);
        errorView.setTextColor(0xFFFFFFFF);
        errorView.setTextSize(18);
        errorView.setPadding(TvUi.dp(this, 32), TvUi.dp(this, 24),
                TvUi.dp(this, 32), TvUi.dp(this, 24));
        errorView.setBackgroundColor(0xCC090D17);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        setContentView(root);
    }

    private void startPlayback(int sourceIndex) {
        int attempt = ++playbackAttempt;
        String url = playbackUrls.get(sourceIndex);
        if (player != null) {
            player.release();
            player = null;
        }
        String siteOrigin = ApiClient.baseUrl(this);
        // Match the cross-origin request headers sent by CK TV's browser HLS player.
        Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Referer", siteOrigin + "/");
        requestHeaders.put("Origin", siteOrigin);
        requestHeaders.put("Accept", "*/*");
        DefaultHttpDataSource.Factory dataSource = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Collections.unmodifiableMap(requestHeaders));
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .build();
        playerView.setPlayer(player);
        configureTimeBar();
        showControllerOnTimeBar();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (attempt != playbackAttempt || isFinishing()) return;
                lastPlaybackFailure = playbackFailure(error, url);
                Log.e(TAG, lastPlaybackFailure, error);
                markFailedSource(sourceIndex);
                if (sourceIndex + 1 < playbackUrls.size()) {
                    int nextIndex = sourceIndex + 1;
                    showError("片源「" + sourceLabel(sourceIndex)
                            + "」連線失敗，正在自動嘗試片源 "
                            + (nextIndex + 1) + "/" + playbackUrls.size()
                            + "：「" + sourceLabel(nextIndex) + "」…");
                    startPlayback(nextIndex);
                } else if (sourceIndex < fallbackIndexes.size()
                        && fallbackIndexes.get(sourceIndex) >= 0) {
                    tryFallbackCandidate(fallbackIndexes.get(sourceIndex) + 1);
                } else if (fallbackSearchEnabled && !fallbackSearchStarted) {
                    searchFallbackSources();
                } else {
                    showError(lastPlaybackFailure + "\n已自動嘗試 "
                            + playbackUrls.size()
                            + " 個片源，請返回詳情手動選擇其他片源。");
                }
            }

            @Override public void onPlaybackStateChanged(int playbackState) {
                if (attempt == playbackAttempt && playbackState == Player.STATE_READY) {
                    errorView.setVisibility(View.GONE);
                    rememberSuccessfulSource(sourceIndex);
                    if (resumePositionMs <= 0) {
                        LibraryStore.clearPlaybackProgress(
                                PlayerActivity.this, fallbackTitle, fallbackEpisodeIndex);
                    }
                    progressHandler.removeCallbacks(progressSaver);
                    progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS);
                } else if (attempt == playbackAttempt && playbackState == Player.STATE_ENDED) {
                    progressHandler.removeCallbacks(progressSaver);
                    LibraryStore.clearPlaybackProgress(
                            PlayerActivity.this, fallbackTitle, fallbackEpisodeIndex);
                }
            }
        });
        Uri uri = Uri.parse(url.trim());
        androidx.media3.common.MediaItem.Builder media =
                new androidx.media3.common.MediaItem.Builder().setUri(uri);
        if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) {
            media.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        player.setMediaItem(media.build());
        if (resumePositionMs > 0) player.seekTo(resumePositionMs);
        player.prepare();
        player.play();
    }

    private void configureTimeBar() {
        View bar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (bar instanceof androidx.media3.ui.DefaultTimeBar) {
            ((androidx.media3.ui.DefaultTimeBar) bar).setKeyTimeIncrement(SEEK_INCREMENT_MS);
        }
    }

    private void showControllerOnTimeBar() {
        playerView.showController();
        playerView.post(() -> {
            View bar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
            if (bar != null) bar.requestFocus();
        });
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long duration = player.getDuration();
        if (duration != androidx.media3.common.C.TIME_UNSET) target = Math.min(target, duration);
        player.seekTo(target);
    }

    private void rememberSuccessfulSource(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= sourceItems.size()) return;
        String sourceJson = sourceItems.get(sourceIndex);
        if (sourceJson == null || sourceJson.isEmpty()) return;
        try {
            MediaItem item = MediaItem.fromJson(new JSONObject(sourceJson));
            LibraryStore.rememberWorkingSource(this, item);
        } catch (Exception error) {
            Log.w(TAG, "Could not save the source that started playback.", error);
        }
    }

    private void markFailedSource(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= sourceItems.size()) return;
        String sourceJson = sourceItems.get(sourceIndex);
        if (sourceJson == null || sourceJson.isEmpty()) return;
        try {
            MediaItem item = MediaItem.fromJson(new JSONObject(sourceJson));
            LibraryStore.markSourceFailed(this, fallbackTitle, item);
        } catch (Exception error) {
            Log.w(TAG, "Could not record failed playback source.", error);
        }
    }

    private void savePlaybackProgress() {
        if (player == null || player.getPlaybackState() != Player.STATE_READY) return;
        long positionMs = player.getCurrentPosition();
        long durationMs = player.getDuration();
        LibraryStore.savePlaybackProgress(this, fallbackTitle, fallbackEpisodeIndex,
                positionMs, durationMs);
    }

    private void searchFallbackSources() {
        fallbackSearchStarted = true;
        showError("上次使用的片源無法播放，正在搜尋其他片源…");
        ApiClient.search(this, fallbackTitle, new ApiClient.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray values) {
                String titleKey = normalizeTitle(fallbackTitle);
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value == null) continue;
                    MediaItem candidate = MediaItem.fromJson(value);
                    if (candidate.source.isEmpty() || candidate.id.isEmpty()
                            || !titleKey.equals(normalizeTitle(candidate.title))
                            || !attemptedProviders.add(candidate.source)) {
                        continue;
                    }
                    fallbackCandidates.add(candidate);
                }
                if (fallbackCandidates.isEmpty()) {
                    showError(lastPlaybackFailure
                            + "\n找不到其他可用片源，請返回詳情手動搜尋片源。");
                    return;
                }
                tryFallbackCandidate(0);
            }

            @Override public void onError(String message) {
                if (!isFinishing()) {
                    showError(lastPlaybackFailure + "\n搜尋其他片源失敗：" + message
                            + "\n請返回詳情手動搜尋片源。");
                }
            }
        });
    }

    private void tryFallbackCandidate(int candidateIndex) {
        if (isFinishing()) return;
        if (candidateIndex >= fallbackCandidates.size()) {
            showError(lastPlaybackFailure + "\n已搜尋其他片源，但沒有可播放的劇集。"
                    + "\n請返回詳情手動選擇片源。");
            return;
        }
        MediaItem candidate = fallbackCandidates.get(candidateIndex);
        if (fallbackEpisodeIndex >= 0 && fallbackEpisodeIndex < candidate.episodes.size()) {
            MediaItem resolved = withEpisodeMetadata(candidate, candidateIndex);
            appendAndPlay(resolved, candidateIndex);
            return;
        }
        ApiClient.detail(this, candidate.source, candidate.id,
                new ApiClient.Callback<JSONObject>() {
                    @Override public void onSuccess(JSONObject value) {
                        if (isFinishing()) return;
                        MediaItem detail = MediaItem.fromJson(value);
                        MediaItem resolved = withEpisodeMetadata(detail, candidateIndex);
                        if (fallbackEpisodeIndex >= 0
                                && fallbackEpisodeIndex < resolved.episodes.size()) {
                            appendAndPlay(resolved, candidateIndex);
                        } else {
                            tryFallbackCandidate(candidateIndex + 1);
                        }
                    }

                    @Override public void onError(String message) {
                        if (!isFinishing()) tryFallbackCandidate(candidateIndex + 1);
                    }
                });
    }

    private MediaItem withEpisodeMetadata(MediaItem item, int candidateIndex) {
        MediaItem candidate = fallbackCandidates.get(candidateIndex);
        if (item.title.isEmpty()) item.title = candidate.title;
        if (item.poster.isEmpty()) item.poster = candidate.poster;
        if (item.description.isEmpty()) item.description = candidate.description;
        if (item.source.isEmpty()) item.source = candidate.source;
        if (item.sourceName.isEmpty()) item.sourceName = candidate.sourceName;
        return item;
    }

    private void appendAndPlay(MediaItem item, int candidateIndex) {
        String url = episodeUrl(item.episodes.get(fallbackEpisodeIndex));
        if (url.isEmpty()) {
            tryFallbackCandidate(candidateIndex + 1);
            return;
        }
        String sourceName = item.sourceName.isEmpty() ? item.source : item.sourceName;
        playbackUrls.add(url);
        sourceLabels.add(sourceName);
        sourceItems.add(item.toJson().toString());
        fallbackIndexes.add(candidateIndex);
        showError("正在嘗試備用片源「" + sourceName + "」…");
        startPlayback(playbackUrls.size() - 1);
    }

    private static String episodeUrl(String episode) {
        if (episode == null) return "";
        String value = episode.trim();
        return value.contains("$") ? value.split("\\$", 2)[1].trim() : value;
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？：；、（）【】《》「」『』·…]", "");
    }

    private String sourceLabel(int index) {
        return index >= 0 && index < sourceLabels.size()
                ? sourceLabels.get(index) : "片源 " + (index + 1);
    }

    private String playbackFailure(PlaybackException error, String url) {
        StringBuilder message = new StringBuilder("播放失败：")
                .append(error.getErrorCodeName());
        String host = Uri.parse(url).getHost();
        if (host != null && !host.isEmpty()) message.append("\n服务器：").append(host);
        Throwable cause = error;
        int depth = 0;
        while (cause != null && depth < 4) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                HttpDataSource.InvalidResponseCodeException httpError =
                        (HttpDataSource.InvalidResponseCodeException) cause;
                message.append("\nHTTP 状态：").append(httpError.responseCode);
                break;
            }
            if (cause.getCause() == null && cause.getMessage() != null
                    && !cause.getMessage().trim().isEmpty()) {
                message.append("\n网络详情：").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        message.append("\n請檢查電視盒網路/DNS，或從影片詳情更換片源。");
        return message.toString();
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (playerView != null && playerView.isControllerFullyVisible()) {
                        playerView.hideController();
                    } else {
                        finish();
                    }
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
        }
        if (playerView != null && !playerView.isControllerFullyVisible()
                && isControllerWakeKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) seekBy(-SEEK_INCREMENT_MS);
                else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) seekBy(SEEK_INCREMENT_MS);
                if (event.getRepeatCount() == 0) showControllerOnTimeBar();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isControllerWakeKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        progressHandler.removeCallbacks(progressSaver);
        if (player != null) {
            savePlaybackProgress();
            player.release();
            player = null;
        }
    }
}
