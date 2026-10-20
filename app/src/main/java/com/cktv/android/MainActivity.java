package com.cktv.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.media3.common.util.UnstableApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@UnstableApi
public class MainActivity extends Activity {
    private final List<MediaItem> items = new ArrayList<>();
    private GridView grid;
    private LinearLayout pager;
    private LinearLayout filterRows;
    private TextView heading;
    private TextView status;
    private TextView pageLabel;
    private EditText searchField;
    private MediaAdapter adapter;
    private Button previousPage;
    private Button nextPage;
    private Button firstCategoryButton;
    private String activeKind = "movie";
    private String activeLabel = "電影";
    private String activePrimary = "热门";
    private String activeSubcategory = "全部";
    private String activeSource = "zy91md";
    private String activeLiveGroup = "news";
    private int page = 1;
    private int pageCount = 1;
    private int contentRequestId;
    private boolean catalogMode;
    private boolean showingHistory;
    private long lastBackPressedAt;
    private JSONArray adultSources = new JSONArray();
    private JSONArray adultCategories = new JSONArray();
    private JSONArray huangguoCategories = new JSONArray();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(TvUi.BACKGROUND);
        getWindow().setNavigationBarColor(TvUi.BACKGROUND);
        buildUi();
        loadCatalog(activeKind, activeLabel);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 8),
                TvUi.dp(this, 24), TvUi.dp(this, 4));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = TvUi.text(this, "CKTV", 28, Color.WHITE);
        logo.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(logo, new LinearLayout.LayoutParams(
                TvUi.dp(this, 112), TvUi.dp(this, 54)));

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(16);
        searchField.setTextColor(Color.WHITE);
        searchField.setHintTextColor(TvUi.MUTED);
        searchField.setHint("搜尋電影、劇集");
        searchField.setPadding(TvUi.dp(this, 16), 0, TvUi.dp(this, 16), 0);
        searchField.setBackground(TvUi.background(TvUi.SURFACE, TvUi.dp(this, 9)));
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH
                    || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_DONE) {
                performSearch();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0,
                TvUi.dp(this, 48), 1f);
        searchParams.setMargins(0, 0, TvUi.dp(this, 8), 0);
        top.addView(searchField, searchParams);
        top.addView(topButton("搜尋", this::performSearch));
        top.addView(topButton("收藏", this::showFavorites));
        top.addView(topButton("播放記錄", this::showHistory));
        top.addView(topButton("設定", this::showSettings));
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 52)));

        LinearLayout kinds = new LinearLayout(this);
        kinds.setGravity(Gravity.CENTER_VERTICAL);
        addKindButton(kinds, "電影", "movie", true);
        addKindButton(kinds, "劇集", "tv", true);
        addKindButton(kinds, "動漫", "anime", true);
        addKindButton(kinds, "綜藝", "variety", true);
        addKindButton(kinds, "紀錄片", "documentary", true);
        addKindButton(kinds, "番組", "bangumi", false);
        addKindButton(kinds, "嗶哩", "bilibili", false);
        addKindButton(kinds, "直播", "live", false);
        addKindButton(kinds, "18+專區", "adult", false);
        addKindButton(kinds, "AI黃果", "huangguo", false);
        android.widget.HorizontalScrollView categoryScroll = new android.widget.HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        categoryScroll.setFocusable(false);
        categoryScroll.addView(kinds);
        root.addView(categoryScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 50)));

        filterRows = new LinearLayout(this);
        filterRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(filterRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        heading = TvUi.text(this, "劇集", 23, Color.WHITE);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0,
                TvUi.dp(this, 38), 1f));
        status = TvUi.text(this, "正在連線 CKTV", 13, TvUi.MUTED);
        titleRow.addView(status);
        root.addView(titleRow);

        grid = new GridView(this);
        grid.setNumColumns(gridColumns());
        grid.setColumnWidth(TvUi.dp(this, 160));
        grid.setHorizontalSpacing(TvUi.dp(this, 12));
        grid.setVerticalSpacing(TvUi.dp(this, 5));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(TvUi.dp(this, 2), TvUi.dp(this, 4),
                TvUi.dp(this, 2), TvUi.dp(this, 6));
        grid.setClipToPadding(false);
        adapter = new MediaAdapter(this, items, this::openItem);
        grid.setAdapter(adapter);
        grid.addOnLayoutChangeListener((view, left, gridTop, right, bottom,
                                        oldLeft, oldGridTop, oldRight, oldBottom) -> {
            if (bottom - gridTop != oldBottom - oldGridTop) resizeCardsToGrid();
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        previousPage = TvUi.button(this, "上一頁");
        nextPage = TvUi.button(this, "下一頁");
        pageLabel = TvUi.text(this, "第 1 頁", 13, TvUi.MUTED);
        pageLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pageTextParams = new LinearLayout.LayoutParams(
                TvUi.dp(this, 90), TvUi.dp(this, 40));
        pager.addView(previousPage);
        pager.addView(pageLabel, pageTextParams);
        pager.addView(nextPage);
        root.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(this, 42)));
        previousPage.setOnClickListener(view -> loadCatalogPage(Math.max(1, page - 1)));
        nextPage.setOnClickListener(view -> loadCatalogPage(page + 1));
        setContentView(root);
        pager.setVisibility(View.GONE);
        grid.post(this::resizeCardsToGrid);
        if (firstCategoryButton != null) firstCategoryButton.requestFocus();
    }

    private void resizeCardsToGrid() {
        if (grid == null || adapter == null || grid.getHeight() <= 0
                || adapter.getCount() == 0) return;
        int contentHeight = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        int spacing = TvUi.dp(this, 5);
        int minimumCardHeight = TvUi.dp(this, 176);
        int columns = Math.max(1, grid.getNumColumns());
        int itemRows = (adapter.getCount() + columns - 1) / columns;
        int visibleRows = Math.min(3, itemRows);
        int cardHeight = minimumCardHeight;
        while (visibleRows > 1) {
            int rowHeight = (contentHeight - spacing * (visibleRows - 1)) / visibleRows;
            if (rowHeight >= minimumCardHeight) {
                cardHeight = Math.min(TvUi.dp(this, 232), rowHeight);
                break;
            }
            visibleRows--;
        }
        adapter.setCardHeight(cardHeight);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                long now = SystemClock.elapsedRealtime();
                if (lastBackPressedAt != 0 && now - lastBackPressedAt <= 2500) {
                    finish();
                } else {
                    lastBackPressedAt = now;
                    Toast.makeText(this, "再按一次返回鍵即可離開 CKTV",
                            Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)
                && isGridFocused()) {
            boolean down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            if (gridVerticalTarget(down) >= 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    moveGridFocusVertically(down);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isGridFocused() {
        View focused = getCurrentFocus();
        while (focused != null && focused != grid) {
            if (focused.getParent() == grid) return true;
            if (!(focused.getParent() instanceof View)) return false;
            focused = (View) focused.getParent();
        }
        return false;
    }

    private void moveGridFocusVertically(boolean down) {
        int targetPosition = gridVerticalTarget(down);
        if (targetPosition < 0) return;
        View focused = getCurrentFocus();
        while (focused != null && focused.getParent() != grid) {
            if (!(focused.getParent() instanceof View)) return;
            focused = (View) focused.getParent();
        }
        if (focused == null) return;
        grid.setSelection(targetPosition);
        grid.post(() -> {
            int childIndex = targetPosition - grid.getFirstVisiblePosition();
            if (childIndex >= 0 && childIndex < grid.getChildCount()) {
                grid.getChildAt(childIndex).requestFocus();
            } else {
                grid.postDelayed(() -> {
                    int visibleIndex = targetPosition - grid.getFirstVisiblePosition();
                    if (visibleIndex >= 0 && visibleIndex < grid.getChildCount()) {
                        grid.getChildAt(visibleIndex).requestFocus();
                    }
                }, 120);
            }
        });
    }

    private int gridVerticalTarget(boolean down) {
        View focused = getCurrentFocus();
        while (focused != null && focused.getParent() != grid) {
            if (!(focused.getParent() instanceof View)) return -1;
            focused = (View) focused.getParent();
        }
        if (focused == null) return -1;
        int position = grid.getPositionForView(focused);
        if (position == android.widget.AdapterView.INVALID_POSITION) return -1;
        int target = down ? position + Math.max(1, grid.getNumColumns())
                : position - Math.max(1, grid.getNumColumns());
        return target >= 0 && target < adapter.getCount() ? target : -1;
    }

    private int gridColumns() {
        float density = getResources().getDisplayMetrics().density;
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        return Math.max(3, Math.min(6, widthDp / 190));
    }

    private Button topButton(String label, Runnable action) {
        Button button = TvUi.button(this, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 46));
        params.setMargins(TvUi.dp(this, 5), 0, 0, 0);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void addKindButton(LinearLayout row, String label, String kind, boolean douban) {
        Button button = TvUi.button(this, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 42));
        params.setMargins(0, 0, TvUi.dp(this, 8), 0);
        row.addView(button, params);
        if (firstCategoryButton == null) firstCategoryButton = button;
        button.setOnClickListener(view -> {
            activeKind = kind;
            loadCatalog(kind, label, douban);
        });
    }

    private void loadCatalog(String kind, String label) {
        loadCatalog(kind, label, true);
    }

    private void loadCatalog(String kind, String label, boolean douban) {
        beginCatalog(kind, label, douban);
    }

    private void beginCatalog(String kind, String label, boolean douban) {
        showingHistory = false;
        activeKind = kind;
        activeLabel = label;
        activePrimary = "movie".equals(kind) ? "热门" : "variety".equals(kind) ? "show" : "tv";
        activeSubcategory = defaultSubcategory(kind);
        activeSource = "zy91md";
        activeLiveGroup = "news";
        adultSources = new JSONArray();
        adultCategories = new JSONArray();
        huangguoCategories = new JSONArray();
        catalogMode = true;
        page = 1;
        pageCount = 1;
        renderFilters();
        loadCatalogPage(1, douban);
    }

    private void loadCatalogPage(int requestedPage) {
        loadCatalogPage(requestedPage, !"bangumi".equals(activeKind) && !"bilibili".equals(activeKind));
    }

    private void loadCatalogPage(int requestedPage, boolean douban) {
        if ("adult".equals(activeKind)) {
            loadAdultPage(requestedPage);
            return;
        }
        if ("huangguo".equals(activeKind)) {
            loadHuangguoPage(requestedPage);
            return;
        }
        if ("live".equals(activeKind)) {
            loadLiveGroup(activeLiveGroup);
            return;
        }
        page = requestedPage;
        int requestId = ++contentRequestId;
        heading.setText(activeLabel);
        status.setText("載入中…");
        updatePager(false);
        ApiClient.catalog(this, activeKind, requestedPage, douban,
                activePrimary, activeSubcategory, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject response) {
                if (requestId != contentRequestId) return;
                JSONArray values = response.optJSONArray("list");
                if (values == null) values = new JSONArray();
                List<MediaItem> result = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value != null) result.add(MediaItem.fromJson(value));
                }
                replaceItems(result);
                page = response.optInt("page", requestedPage);
                status.setText(result.size() + " 部影片");
                pageLabel.setText("第 " + page + " 頁");
                previousPage.setEnabled(page > 1);
                pageCount = response.optBoolean("hasMore", result.size() >= 20)
                        ? requestedPage + 1 : requestedPage;
                updatePager(response.optBoolean("hasMore", result.size() >= 20));
                focusFirstItem();
            }

            @Override public void onError(String message) {
                if (requestId != contentRequestId) return;
                status.setText("載入失敗：" + message);
                replaceItems(new ArrayList<>());
                updatePager(false);
            }
        });
    }

    private void loadAdultPage(int requestedPage) {
        page = requestedPage;
        int requestId = ++contentRequestId;
        heading.setText(activeLabel);
        status.setText("正在載入18+專區…");
        updatePager(false);
        ApiClient.adultCatalog(this, activeSource, activeSubcategory, requestedPage, "",
                new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject response) {
                if (requestId != contentRequestId) return;
                adultSources = response.optJSONArray("sources") == null
                        ? adultSources : response.optJSONArray("sources");
                adultCategories = response.optJSONArray("categories") == null
                        ? adultCategories : response.optJSONArray("categories");
                JSONArray values = response.optJSONArray("list");
                List<MediaItem> result = parseMedia(values);
                replaceItems(result);
                page = response.optInt("page", requestedPage);
                pageCount = Math.max(1, response.optInt("pagecount", requestedPage));
                renderFilters();
                status.setText(result.size() + " 部影片");
                pageLabel.setText("第 " + page + " 頁 / " + pageCount);
                updatePager(page < pageCount);
                focusFirstItem();
            }

            @Override public void onError(String message) {
                if (requestId != contentRequestId) return;
                status.setText("18+專區載入失敗：" + message);
                replaceItems(new ArrayList<>());
                updatePager(false);
            }
        });
    }

    private void loadHuangguoPage(int requestedPage) {
        page = requestedPage;
        int requestId = ++contentRequestId;
        heading.setText(activeLabel);
        status.setText("正在載入AI黃果…");
        updatePager(false);
        ApiClient.huangguoCatalog(this, activeSubcategory, requestedPage,
                new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject response) {
                if (requestId != contentRequestId) return;
                huangguoCategories = response.optJSONArray("categories") == null
                        ? huangguoCategories : response.optJSONArray("categories");
                List<MediaItem> result = parseMedia(response.optJSONArray("list"));
                replaceItems(result);
                page = response.optInt("page", requestedPage);
                pageCount = Math.max(1, response.optInt("pagecount", requestedPage));
                renderFilters();
                status.setText(result.size() + " 部影片");
                pageLabel.setText("第 " + page + " 頁 / " + pageCount);
                updatePager(page < pageCount);
                focusFirstItem();
            }

            @Override public void onError(String message) {
                if (requestId != contentRequestId) return;
                status.setText("AI黃果載入失敗：" + message);
                replaceItems(new ArrayList<>());
                updatePager(false);
            }
        });
    }

    private void loadLiveGroup(String group) {
        activeLiveGroup = group;
        int requestId = ++contentRequestId;
        catalogMode = false;
        updatePager(false);
        heading.setText(activeLabel);
        status.setText("直播頻道");
        List<MediaItem> channels = liveChannels(group);
        if (requestId != contentRequestId) return;
        replaceItems(channels);
        status.setText(channels.size() + " 个频道");
        renderFilters();
        focusFirstItem();
    }

    private List<MediaItem> parseMedia(JSONArray values) {
        List<MediaItem> result = new ArrayList<>();
        if (values == null) return result;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) result.add(MediaItem.fromJson(value));
        }
        return result;
    }

    private void renderFilters() {
        if (filterRows == null) return;
        filterRows.removeAllViews();
        if ("movie".equals(activeKind)) {
            addFilterRow("分類", Arrays.asList(
                    new FilterOption("熱門電影", "熱門"),
                        new FilterOption("最新電影", "最新"),
                    new FilterOption("豆瓣高分", "豆瓣高分"),
                    new FilterOption("冷門佳片", "冷门佳片")), activePrimary,
                    value -> {
                        activePrimary = value;
                        page = 1;
                        renderFilters();
                        loadCatalogPage(1, true);
                    });
            addFilterRow("篩選", Arrays.asList(
                    new FilterOption("全部", "全部"), new FilterOption("華語", "华语"),
                    new FilterOption("歐美", "欧美"), new FilterOption("韓國", "韩国"),
                    new FilterOption("日本", "日本"), new FilterOption("喜劇", "喜剧"),
                    new FilterOption("動作", "动作"), new FilterOption("科幻", "科幻"),
                    new FilterOption("動畫", "动画"), new FilterOption("懸疑", "悬疑"),
                    new FilterOption("愛情", "爱情"), new FilterOption("紀錄片", "纪录片"),
                    new FilterOption("劇情", "剧情"), new FilterOption("犯罪", "犯罪"),
                    new FilterOption("驚悚", "惊悚"), new FilterOption("奇幻", "奇幻"),
                    new FilterOption("戰爭", "战争"), new FilterOption("歷史", "历史")),
                    activeSubcategory, value -> changeSubcategory(value, true));
        } else if ("tv".equals(activeKind) || "anime".equals(activeKind)
                || "documentary".equals(activeKind)) {
            addFilterRow("類型", Arrays.asList(
                    new FilterOption("全部", "tv"), new FilterOption("國產", "tv_domestic"),
                    new FilterOption("歐美", "tv_american"), new FilterOption("日本", "tv_japanese"),
                    new FilterOption("韓國", "tv_korean"), new FilterOption("動漫", "tv_animation"),
                    new FilterOption("紀錄片", "tv_documentary")), activeSubcategory,
                    value -> changeSubcategory(value, true));
        } else if ("variety".equals(activeKind)) {
            addFilterRow("類型", Arrays.asList(
                    new FilterOption("全部", "show"), new FilterOption("國內", "show_domestic"),
                    new FilterOption("國外", "show_foreign")), activeSubcategory,
                    value -> changeSubcategory(value, true));
        } else if ("adult".equals(activeKind)) {
            List<FilterOption> sources = new ArrayList<>();
            for (int i = 0; i < adultSources.length(); i++) {
                JSONObject source = adultSources.optJSONObject(i);
                if (source != null) sources.add(new FilterOption(
                        toTraditional(source.optString("name", source.optString("key"))),
                        source.optString("key")));
            }
            if (!sources.isEmpty()) {
                addFilterRow("源站", sources, activeSource, value -> {
                    activeSource = value;
                    activeSubcategory = "";
                    page = 1;
                    loadAdultPage(1);
                });
            }
            List<FilterOption> categories = new ArrayList<>();
            categories.add(new FilterOption("全部", ""));
            for (int i = 0; i < adultCategories.length(); i++) {
                JSONObject category = adultCategories.optJSONObject(i);
                if (category != null) categories.add(new FilterOption(
                        toTraditional(category.optString("type_name", category.optString("name"))),
                        category.optString("type_id", category.optString("id"))));
            }
            if (categories.size() > 1) {
                addFilterRow("分類", categories, activeSubcategory,
                        value -> changeSubcategory(value, false));
            }
        } else if ("huangguo".equals(activeKind)) {
            List<FilterOption> categories = new ArrayList<>();
            for (int i = 0; i < huangguoCategories.length(); i++) {
                JSONObject category = huangguoCategories.optJSONObject(i);
                if (category != null) categories.add(new FilterOption(
                        toTraditional(category.optString("name")), category.optString("id")));
            }
            if (!categories.isEmpty()) {
                addFilterRow("分類", categories, activeSubcategory,
                        value -> changeSubcategory(value, false));
            }
        } else if ("live".equals(activeKind)) {
            addFilterRow("頻道", Arrays.asList(
                    new FilterOption("新聞", "news"), new FilterOption("央視", "cctv"),
                    new FilterOption("衛視", "satellite"), new FilterOption("地方", "local"),
                    new FilterOption("平台", "platform")), activeLiveGroup,
                    this::loadLiveGroup);
        }
        filterRows.setVisibility(filterRows.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addFilterRow(String label, List<FilterOption> options, String selected,
                              java.util.function.Consumer<String> onSelect) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = TvUi.text(this, toTraditional(label), 13, TvUi.MUTED);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(caption, new LinearLayout.LayoutParams(
                TvUi.dp(this, 54), TvUi.dp(this, 42)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.addView(buttons);
        row.addView(scroll, new LinearLayout.LayoutParams(0, TvUi.dp(this, 46), 1f));
        for (FilterOption option : options) {
            Button button = TvUi.button(this, option.label);
            if (option.value.equals(selected)) {
                button.setTextColor(TvUi.ACCENT);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, TvUi.dp(this, 42));
            params.setMargins(0, 0, TvUi.dp(this, 6), 0);
            buttons.addView(button, params);
            button.setOnClickListener(view -> onSelect.accept(option.value));
        }
        filterRows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, TvUi.dp(this, 46)));
    }

    private void changeSubcategory(String value, boolean douban) {
        activeSubcategory = value;
        page = 1;
        renderFilters();
        loadCatalogPage(1, douban);
    }

    private String defaultSubcategory(String kind) {
        switch (kind) {
            case "movie": return "全部";
            case "anime": return "tv_animation";
            case "documentary": return "tv_documentary";
            case "variety": return "show";
            case "huangguo": return "hot";
            case "adult": return "";
            default: return "tv";
        }
    }

    private List<MediaItem> liveChannels(String group) {
        List<MediaItem> result = new ArrayList<>();
        if ("news".equals(group)) {
            addLive(result, "cctvplus1", "CCTV+ 1", "news");
            addLive(result, "cctvplus2", "CCTV+ 2", "news");
            addLive(result, "cgtn", "CGTN 英語", "news");
            addLive(result, "cgtn-doc", "CGTN 紀錄", "news");
            addLive(result, "cgtn-es", "CGTN 西語", "news");
            addLive(result, "cgtn-fr", "CGTN 法語", "news");
            addLive(result, "cgtn-ar", "CGTN 阿語", "news");
            addLive(result, "cgtn-ru", "CGTN 俄語", "news");
        } else if ("cctv".equals(group)) {
            String[][] channels = {
                    {"cctv1", "CCTV-1 綜合"}, {"cctv2", "CCTV-2 財經"},
                    {"cctv3", "CCTV-3 綜藝"}, {"cctv4", "CCTV-4 中文國際"},
                    {"cctv5", "CCTV-5 體育"}, {"cctv5plus", "CCTV-5+ 賽事"},
                    {"cctv6", "CCTV-6 電影"}, {"cctv7", "CCTV-7 國防軍事"},
                    {"cctv8", "CCTV-8 電視劇"}, {"cctv10", "CCTV-10 科教"},
                    {"cctv11", "CCTV-11 戲曲"}, {"cctv12", "CCTV-12 社會與法"},
                    {"cctv13", "CCTV-13 新聞"}, {"cctv15", "CCTV-15 音樂"},
                    {"cctv16", "CCTV-16 奧林匹克"}, {"cctv17", "CCTV-17 農業農村"}
            };
            for (String[] channel : channels) addLive(result, channel[0], channel[1], group);
        } else if ("satellite".equals(group)) {
            String[][] channels = {
                    {"hunan", "湖南衛視"}, {"zhejiang", "浙江衛視"},
                    {"dongfang", "東方衛視"}, {"jiangsu", "江蘇衛視"},
                    {"guangdong", "廣東衛視"}, {"shenzhen", "深圳衛視"},
                    {"shandong", "山東衛視"}, {"anhui", "安徽衛視"},
                    {"tianjin", "天津衛視"}, {"chongqing", "重慶衛視"},
                    {"dongnan", "東南衛視"}, {"jiangxi", "江西衛視"},
                    {"liaoning", "遼寧衛視"}, {"henan", "河南衛視"},
                    {"hubei", "湖北衛視"}, {"heilongjiang", "黑龍江衛視"},
                    {"sichuan", "四川衛視"}, {"hebei", "河北衛視"},
                    {"jilin", "吉林衛視"}, {"guangxi", "廣西衛視"},
                    {"yunnan", "雲南衛視"}, {"guizhou", "貴州衛視"},
                    {"xinjiang", "新疆衛視"}, {"gansu", "甘肅衛視"},
                    {"neimenggu", "內蒙古衛視"}, {"ningxia", "寧夏衛視"},
                    {"qinghai", "青海衛視"}, {"xizang", "西藏衛視"}
            };
            for (String[] channel : channels) addLive(result, channel[0], channel[1], group);
        } else if ("local".equals(group)) {
            String[][] channels = {
                    {"hrb-news", "哈爾濱新聞"}, {"hrb-movie", "哈爾濱影視"},
                    {"hebei-event", "河北活動"}, {"lanzhou-news", "蘭州新聞"},
                    {"lanzhou-tour", "蘭州文旅"}, {"dongguan", "東莞綜合"},
                    {"baicheng", "白城綜合"}, {"bread", "麵包台"},
                    {"abn", "ABN 中文"}, {"angel", "天使中文"}
            };
            for (String[] channel : channels) addLive(result, channel[0], channel[1], group);
        } else if ("platform".equals(group)) {
            addLiveLink(result, "抖音直播", "https://live.douyin.com/");
            addLiveLink(result, "快手直播", "https://live.kuaishou.com/");
            addLiveLink(result, "嗶哩直播", "https://live.bilibili.com/");
            addLiveLink(result, "鬥魚", "https://www.douyu.com/");
            addLiveLink(result, "虎牙", "https://www.huya.com/");
            addLiveLink(result, "央視頻", "https://www.yangshipin.cn/");
        }
        return result;
    }

    private void addLive(List<MediaItem> target, String id, String title, String group) {
        MediaItem item = new MediaItem();
        item.id = id;
        item.title = title;
        item.source = "live";
        item.sourceName = group;
        item.remark = "直播頻道";
        target.add(item);
    }

    private void addLiveLink(List<MediaItem> target, String title, String url) {
        MediaItem item = new MediaItem();
        item.id = url;
        item.title = title;
        item.source = "live_link";
        item.sourceName = "平台";
        item.remark = "開啟直播平台";
        target.add(item);
    }

    private static final class FilterOption {
        final String label;
        final String value;
        FilterOption(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static String toTraditional(String value) {
        return value.replace("电影", "電影")
                .replace("剧集", "劇集")
                .replace("电视剧", "電視劇")
                .replace("动漫", "動漫")
                .replace("综艺", "綜藝")
                .replace("纪录", "紀錄")
                .replace("番组", "番組")
                .replace("哔哩", "嗶哩")
                .replace("黄果", "黃果")
                .replace("新闻", "新聞")
                .replace("央视", "央視")
                .replace("卫视", "衛視")
                .replace("国产", "國產")
                .replace("国内", "國內")
                .replace("国外", "國外")
                .replace("欧美", "歐美")
                .replace("韩国", "韓國")
                .replace("华语", "華語")
                .replace("喜剧", "喜劇")
                .replace("动作", "動作")
                .replace("动画", "動畫")
                .replace("悬疑", "懸疑")
                .replace("爱情", "愛情")
                .replace("剧情", "劇情")
                .replace("惊悚", "驚悚")
                .replace("战争", "戰爭")
                .replace("历史", "歷史")
                .replace("综合", "綜合")
                .replace("财经", "財經")
                .replace("体育", "體育")
                .replace("赛事", "賽事")
                .replace("中文国际", "中文國際")
                .replace("国防军事", "國防軍事")
                .replace("科教", "科教")
                .replace("社会与法", "社會與法")
                .replace("音乐", "音樂")
                .replace("奥林匹克", "奧林匹克")
                .replace("农业农村", "農業農村")
                .replace("英语", "英語")
                .replace("西语", "西語")
                .replace("法语", "法語")
                .replace("阿语", "阿語")
                .replace("俄语", "俄語")
                .replace("哈尔滨", "哈爾濱")
                .replace("东莞", "東莞")
                .replace("兰州", "蘭州")
                .replace("江苏", "江蘇")
                .replace("广东", "廣東")
                .replace("东方", "東方")
                .replace("辽宁", "遼寧")
                .replace("黑龙江", "黑龍江")
                .replace("云南", "雲南")
                .replace("重庆", "重慶")
                .replace("江西", "江西")
                .replace("内蒙古", "內蒙古")
                .replace("宁夏", "寧夏")
                .replace("青海", "青海")
                .replace("西藏", "西藏")
                .replace("分类", "分類")
                .replace("筛选", "篩選")
                .replace("源站", "來源");
    }

    private void performSearch() {
        String query = searchField.getText().toString().trim();
        if (query.isEmpty()) {
            status.setText("請輸入片名搜尋。");
            TvUi.focusOn(searchField);
            return;
        }
        showingHistory = false;
        hideKeyboard();
        int requestId = ++contentRequestId;
        catalogMode = false;
        filterRows.setVisibility(View.GONE);
        updatePager(false);
        heading.setText("搜尋：" + query);
        status.setText("正在搜尋全站片源…");
        ApiClient.search(this, query, new ApiClient.Callback<JSONArray>() {
            @Override public void onSuccess(JSONArray values) {
                if (requestId != contentRequestId) return;
                List<MediaItem> result = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value != null) result.add(MediaItem.fromJson(value));
                }
                replaceItems(result);
                status.setText(result.size() + " 筆結果");
                if (result.isEmpty()) status.setText("找不到相關影片。");
                else focusFirstItem();
            }

            @Override public void onError(String message) {
                if (requestId != contentRequestId) return;
                status.setText("搜尋失敗：" + message);
                replaceItems(new ArrayList<>());
            }
        });
    }

    private void showFavorites() {
        showLocal("我的收藏", LibraryStore.favorites(this));
    }

    private void showHistory() {
        showLocal("播放記錄", LibraryStore.history(this));
    }

    private void showLocal(String label, List<MediaItem> values) {
        contentRequestId++;
        showingHistory = "播放記錄".equals(label);
        catalogMode = false;
        filterRows.setVisibility(View.GONE);
        updatePager(false);
        heading.setText(label);
        replaceItems(values);
        status.setText(values.size() + " 部影片");
        if (!values.isEmpty()) {
            grid.post(() -> {
                grid.setSelection(0);
                focusFirstItem();
            });
        }
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(TvUi.dp(this, 20), TvUi.dp(this, 12),
                TvUi.dp(this, 20), TvUi.dp(this, 4));
        form.setOrientation(LinearLayout.VERTICAL);
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setTextColor(Color.WHITE);
        url.setHintTextColor(TvUi.MUTED);
        url.setHint("CK TV address");
        url.setText(ApiClient.baseUrl(this));
        form.addView(url);
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setTextColor(Color.WHITE);
        password.setHintTextColor(TvUi.MUTED);
        password.setHint("Access password");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(ApiClient.gatePassword(this));
        form.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("連線設定")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ApiClient.saveSettings(this, url.getText().toString(),
                        password.getText().toString());
                dialog.dismiss();
                loadCatalog(activeKind, activeLabel,
                        !"bangumi".equals(activeKind) && !"bilibili".equals(activeKind));
            });
            TvUi.focusOn(url);
        });
        dialog.show();
    }

    private void openItem(MediaItem item) {
        if ("live_link".equals(item.source)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.id)));
            } catch (Exception error) {
                status.setText("此裝置沒有可用的瀏覽器。");
            }
            return;
        }
        if ("live".equals(item.source)) {
            status.setText("正在連線 " + item.title + "…");
            ApiClient.live(this, item.id, new ApiClient.Callback<JSONObject>() {
                @Override public void onSuccess(JSONObject response) {
                    JSONArray urls = response.optJSONArray("urls");
                    String url = urls == null ? "" : urls.optString(0, "");
                    if (!response.optBoolean("ok", false) || url.isEmpty()) {
                        status.setText(item.title + " 暫無可用直播串流。");
                        return;
                    }
                    Intent player = new Intent(MainActivity.this, PlayerActivity.class);
                    player.putExtra("url", url);
                    player.putExtra("title", item.title);
                    player.putExtra("episode", "直播");
                    startActivity(player);
                }

                @Override public void onError(String message) {
                    status.setText("直播連線失敗：" + message);
                }
            });
            return;
        }
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra("item", item.toJson().toString());
        intent.putExtra("resume_from_history", showingHistory);
        startActivity(intent);
    }

    private void replaceItems(List<MediaItem> result) {
        items.clear();
        items.addAll(result);
        adapter.notifyDataSetChanged();
        grid.post(this::resizeCardsToGrid);
    }

    private void focusFirstItem() {
        grid.post(() -> {
            grid.setSelection(0);
            grid.post(() -> {
                if (grid.getChildCount() > 0 && grid.getFirstVisiblePosition() == 0) {
                    grid.getChildAt(0).requestFocus();
                }
            });
        });
    }

    private void updatePager(boolean hasMore) {
        if (pager == null) return;
        pager.setVisibility(catalogMode ? View.VISIBLE : View.GONE);
        if (pageLabel != null) pageLabel.setText("第 " + page + " 頁");
        if (previousPage != null) previousPage.setEnabled(page > 1);
        if (nextPage != null) nextPage.setEnabled(catalogMode && hasMore);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
    }

    private static final class MediaAdapter extends BaseAdapter {
        private final Activity activity;
        private final List<MediaItem> values;
        private final ItemClickListener itemClickListener;
        private int cardHeight;
        private int posterHeight;

        MediaAdapter(Activity activity, List<MediaItem> values, ItemClickListener itemClickListener) {
            this.activity = activity;
            this.values = values;
            this.itemClickListener = itemClickListener;
            setCardHeight(TvUi.dp(activity, 136));
        }

        void setCardHeight(int height) {
            int nextHeight = Math.max(TvUi.dp(activity, 176), height);
            int nextPosterHeight = nextHeight - TvUi.dp(activity, 72);
            if (cardHeight == nextHeight && posterHeight == nextPosterHeight) return;
            cardHeight = nextHeight;
            posterHeight = nextPosterHeight;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return values.size(); }
        @Override public Object getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
            LinearLayout card;
            ImageView poster;
            TextView title;
            TextView subtitle;
            if (recycled instanceof LinearLayout && recycled.getTag() instanceof ViewHolder) {
                card = (LinearLayout) recycled;
                ViewHolder holder = (ViewHolder) card.getTag();
                poster = holder.poster;
                title = holder.title;
                subtitle = holder.subtitle;
            } else {
                card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setClickable(true);
                card.setPadding(TvUi.dp(activity, 4), TvUi.dp(activity, 4),
                        TvUi.dp(activity, 4), TvUi.dp(activity, 4));
                card.setBackground(TvUi.background(TvUi.SURFACE, TvUi.dp(activity, 9)));
                poster = new ImageView(activity);
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(poster, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, posterHeight));
                title = TvUi.text(activity, "", 14, Color.WHITE);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setPadding(TvUi.dp(activity, 5), TvUi.dp(activity, 5),
                        TvUi.dp(activity, 5), 0);
                card.addView(title, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(activity, 40)));
                subtitle = TvUi.text(activity, "", 11, TvUi.MUTED);
                subtitle.setMaxLines(1);
                subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                subtitle.setPadding(TvUi.dp(activity, 5), TvUi.dp(activity, 2),
                        TvUi.dp(activity, 5), 0);
                card.addView(subtitle, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, TvUi.dp(activity, 16)));
                card.setOnFocusChangeListener((view, focused) -> {
                    view.setBackground(TvUi.background(focused ? 0xFF243B4A : TvUi.SURFACE,
                            TvUi.dp(activity, 9)));
                    view.setScaleX(focused ? 1.04f : 1f);
                    view.setScaleY(focused ? 1.04f : 1f);
                    view.setElevation(focused ? TvUi.dp(activity, 8) : 0);
                });
                card.setTag(new ViewHolder(poster, title, subtitle));
                card.setOnClickListener(view -> {
                    ViewHolder holder = (ViewHolder) view.getTag();
                    if (holder.item != null) itemClickListener.onClick(holder.item);
                });
            }
            card.setLayoutParams(new GridView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cardHeight));
            ViewGroup.LayoutParams posterParams = poster.getLayoutParams();
            posterParams.height = posterHeight;
            poster.setLayoutParams(posterParams);
            MediaItem item = values.get(position);
            ((ViewHolder) card.getTag()).item = item;
            title.setText(toTraditional(item.title));
            String details = item.year;
            if (!item.rating.isEmpty()) details += (details.isEmpty() ? "" : "  ·  ") + "★ " + item.rating;
            if (!item.sourceName.isEmpty()) details += (details.isEmpty() ? "" : "  ·  ") + toTraditional(item.sourceName);
            subtitle.setText(details);
            String sourcePoster = item.poster == null ? "" : item.poster.trim();
            String proxyPoster = ApiClient.imageUrl(activity, sourcePoster);
            if (sourcePoster.startsWith("http://") || sourcePoster.startsWith("https://")
                    || sourcePoster.startsWith("//")) {
                PosterLoader.load(poster, sourcePoster, proxyPoster);
            } else {
                PosterLoader.load(poster, proxyPoster, "");
            }
            return card;
        }

        private static final class ViewHolder {
            final ImageView poster;
            final TextView title;
            final TextView subtitle;
            MediaItem item;

            ViewHolder(ImageView poster, TextView title, TextView subtitle) {
                this.poster = poster;
                this.title = title;
                this.subtitle = subtitle;
            }
        }

        interface ItemClickListener {
            void onClick(MediaItem item);
        }
    }
}
