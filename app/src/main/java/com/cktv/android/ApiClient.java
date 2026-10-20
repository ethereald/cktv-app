package com.cktv.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    private static final String PREFS = "cktv_settings";
    private static final String DEFAULT_BASE_URL = "https://tv.chuankangkk.top";
    private static final String DEFAULT_GATE_PASSWORD = "cknb666";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object GATE_LOCK = new Object();
    private static final Set<String> GATED_HOSTS = new HashSet<>();
    private static final CookieManager COOKIES = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    static {
        CookieHandler.setDefault(COOKIES);
    }

    private ApiClient() {}

    static String baseUrl(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("base_url", DEFAULT_BASE_URL).trim().replaceAll("/+$", "");
    }

    static String gatePassword(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("gate_password", DEFAULT_GATE_PASSWORD);
    }

    static void saveSettings(Context context, String baseUrl, String password) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (normalized.isEmpty()) normalized = DEFAULT_BASE_URL;
        if (!normalized.matches("(?i)^https?://.*")) normalized = "https://" + normalized;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("base_url", normalized)
                .putString("gate_password", password == null || password.trim().isEmpty()
                        ? DEFAULT_GATE_PASSWORD : password.trim())
                .apply();
        synchronized (GATE_LOCK) {
            GATED_HOSTS.clear();
            COOKIES.getCookieStore().removeAll();
        }
    }

    static void catalog(Context context, String category, int page, boolean douban,
                        Callback<JSONObject> callback) {
        String selectedCategory;
        String type;
        switch (category) {
            case "movie":
                selectedCategory = "热门";
                type = "全部";
                break;
            case "anime":
                selectedCategory = "tv";
                type = "tv_animation";
                break;
            case "variety":
                selectedCategory = "show";
                type = "show";
                break;
            case "documentary":
                selectedCategory = "tv";
                type = "tv_documentary";
                break;
            default:
                selectedCategory = "tv";
                type = "tv";
                break;
        }
        catalog(context, category, page, douban, selectedCategory, type, callback);
    }

    static void catalog(Context context, String category, int page, boolean douban,
                        String selectedCategory, String type, Callback<JSONObject> callback) {
        if (!douban) {
            String engine = "bangumi".equals(category) ? "bangumi" : "bilibili";
            String kind = "bangumi".equals(category) ? "anime" : "bangumi";
            catalog(context, engine, kind, page, callback);
            return;
        }
        String kind = "movie".equals(category) ? "movie" : "tv";
        int pageStart = (page - 1) * 20;
        String path = "/api/douban/categories?kind=" + kind
                + "&category=" + encode(selectedCategory)
                + "&type=" + encode(type)
                + "&limit=20&start=" + pageStart;
        get(context, path, json -> {
            JSONArray list = json.optJSONArray("list");
            JSONObject result = new JSONObject();
            result.put("list", list == null ? new JSONArray() : list);
            result.put("page", page);
            result.put("hasMore", list != null && list.length() >= 20);
            return result;
        }, callback);
    }

    private static void catalog(Context context, String engine, String kind, int page,
                                Callback<JSONObject> callback) {
        String path = "/api/catalog?engine=" + encode(engine)
                + "&kind=" + encode(kind) + "&page=" + page;
        get(context, path, json -> json, callback);
    }

    static void search(Context context, String query, Callback<JSONArray> callback) {
        get(context, "/api/search?q=" + encode(query) + "&slim=0", json -> {
            JSONArray list = json.optJSONArray("results");
            return list == null ? new JSONArray() : list;
        }, callback);
    }

    static String imageUrl(Context context, String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return "";
        if (imageUrl.startsWith("/")) return baseUrl(context) + imageUrl;
        return baseUrl(context) + "/api/image-proxy?url=" + encode(imageUrl.trim());
    }

    static void detail(Context context, String source, String id, Callback<JSONObject> callback) {
        if ("huangguo".equals(source)) {
            get(context, "/api/huangguo?action=detail&id=" + encode(id), json -> json, callback);
            return;
        }
        get(context, "/api/detail?source=" + encode(source) + "&id=" + encode(id), json -> json, callback);
    }

    static void adultCatalog(Context context, String source, String type, int page, String query,
                             Callback<JSONObject> callback) {
        String path = "/api/adult?action=" + (query == null || query.trim().isEmpty() ? "list" : "search")
                + "&source=" + encode(source) + "&page=" + page;
        if (type != null && !type.isEmpty()) path += "&t=" + encode(type);
        if (query != null && !query.trim().isEmpty()) path += "&q=" + encode(query.trim());
        get(context, path, json -> json, callback);
    }

    static void huangguoCatalog(Context context, String category, int page,
                                Callback<JSONObject> callback) {
        get(context, "/api/huangguo?action=list&t=" + encode(category) + "&page=" + page,
                json -> json, callback);
    }

    static void live(Context context, String id, Callback<JSONObject> callback) {
        get(context, "/api/live?id=" + encode(id), json -> json, callback);
    }

    private interface Parser<T> {
        T parse(JSONObject json) throws Exception;
    }

    private static <T> void get(Context context, String path, Parser<T> parser, Callback<T> callback) {
        String base = baseUrl(context);
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                ensureGate(context, base);
                connection = open(base + path, "GET");
                Response response = readResponse(connection);
                if (response.status < 200 || response.status >= 300) {
                    throw new IOException(errorMessage(response));
                }
                T value = parser.parse(new JSONObject(response.body));
                MAIN.post(() -> callback.onSuccess(value));
            } catch (Exception error) {
                String message = error.getMessage();
                MAIN.post(() -> callback.onError(message == null ? error.toString() : message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static void ensureGate(Context context, String base) throws Exception {
        synchronized (GATE_LOCK) {
            if (GATED_HOSTS.contains(base)) return;
            HttpURLConnection statusConnection = open(base + "/api/gate", "GET");
            Response status;
            try {
                status = readResponse(statusConnection);
            } finally {
                statusConnection.disconnect();
            }
            JSONObject gate = parseObject(status.body);
            boolean foreign = gate.optBoolean("foreign", false);
            if (foreign && !gate.optBoolean("open", false)) {
                HttpURLConnection authConnection = open(base + "/api/gate", "POST");
                Response auth;
                try {
                    authConnection.setDoOutput(true);
                    authConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] payload = new JSONObject().put("code", gatePassword(context))
                            .toString().getBytes(StandardCharsets.UTF_8);
                    try (java.io.OutputStream output = authConnection.getOutputStream()) {
                        output.write(payload);
                    }
                    auth = readResponse(authConnection);
                } finally {
                    authConnection.disconnect();
                }
                JSONObject result = parseObject(auth.body);
                if (auth.status < 200 || auth.status >= 300 || !result.optBoolean("open", false)) {
                    throw new IOException(auth.status == 429
                            ? "Too many password attempts. Try again later."
                            : "CK TV rejected the access password. Check it in Settings.");
                }
            } else if (status.status >= 500) {
                throw new IOException("CK TV access check failed (HTTP " + status.status + ").");
            }
            GATED_HOSTS.add(base);
        }
    }

    private static HttpURLConnection open(String endpoint, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CKTV-Android/1.0");
        return connection;
    }

    private static Response readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return new Response(status, "");
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return new Response(status, body.toString());
    }

    private static JSONObject parseObject(String body) throws Exception {
        try {
            return new JSONObject(body);
        } catch (Exception error) {
            throw new IOException("CK TV returned an invalid response.", error);
        }
    }

    private static String errorMessage(Response response) {
        try {
            String message = new JSONObject(response.body).optString("error", "");
            if ("region".equals(message)) {
                return "此專區受網站地區限制，預設密碼無法略過地區限制。";
            }
            if ("gate".equals(message)) {
                return "此專區需要存取密碼，請在設定中檢查密碼。";
            }
            if (!message.isEmpty()) return message + " (HTTP " + response.status + ")";
        } catch (Exception ignored) {
            // Use the HTTP status when the server response is not JSON.
        }
        return "CK TV request failed (HTTP " + response.status + ").";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode request parameter", error);
        }
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
