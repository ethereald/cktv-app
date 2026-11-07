package com.cktv.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AI黃果 scraper. The site moves between domains, so the current routes are read on demand from
 * a "latest address" portal and the last working origin is reused until it stops answering.
 * Mirrors pwa/huangguo.js.
 */
final class Huangguo {
    static final String IMAGE_PREFIX = "hgimg:";
    static final String EPISODE_PREFIX = "hgep:";

    private static final String[] PORTALS = {"https://huangguo1.com/"};
    private static final String[] FALLBACK_ORIGINS = {"https://huangguoai.com"};
    private static final String DEFAULT_CATEGORY = "ai-duanju";
    private static final String KEY_PATH = "/static/web/js/plugins/crypto-image-protocol.js";
    private static final long ROUTE_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern ROUTE = Pattern.compile("class=\"route-card[^\"]*\"[^>]*data-url=\"(https?://[^\"]+)\"");
    private static final Pattern VIDEO_DATA = Pattern.compile(
            "<script id=\"videoInitialData\" type=\"application/json\">([\\s\\S]*?)</script>");
    private static final Pattern CATEGORY = Pattern.compile(
            "<a class=\"hg-topbar-nav__item[^\"]*\"[^>]*data-nav-key=\"video_category_\\d+\"[^>]*"
                    + "data-nav-name=\"([^\"]+)\"[^>]*data-nav-route=\"([a-z0-9-]+)\"");

    private static final Object LOCK = new Object();
    private static List<String> routes = new ArrayList<>();
    private static long routesAt;
    private static String goodOrigin = "";
    private static List<String> knownCategories = new ArrayList<>();
    private static SecretKeySpec imageKey;
    private static byte[] imageIv;

    private Huangguo() {}

    interface Validator {
        boolean ok(String html);
    }

    private static final class Page {
        final String html;
        final String origin;

        Page(String html, String origin) {
            this.html = html;
            this.origin = origin;
        }
    }

    static final class EpisodeSource {
            final String url;
            final String origin;
            final String authKey;
            final String authValue;
            final String app;

            EpisodeSource(String url, String origin, String authKey, String authValue, String app) {
                this.url = url;
                this.origin = origin;
                this.authKey = authKey;
                this.authValue = authValue;
                this.app = app;
        }
    }

    static JSONObject list(String category, int page) throws Exception {
        int current = Math.max(1, page);
        String wanted = category != null && category.matches("[a-z0-9-]+") ? category : "";
        List<String> known;
        synchronized (LOCK) {
            known = new ArrayList<>(knownCategories);
        }
        if (!wanted.isEmpty() && !known.isEmpty() && !known.contains(wanted)) wanted = "";
        String first = !wanted.isEmpty() ? wanted : known.isEmpty() ? DEFAULT_CATEGORY : known.get(0);
        Page result;
        try {
            result = get(categoryPath(first, 1), html -> html.contains("hg-drama-card"));
        } catch (IOException error) {
            if (wanted.isEmpty() || DEFAULT_CATEGORY.equals(wanted)) throw error;
            first = DEFAULT_CATEGORY;
            result = get(categoryPath(first, 1), html -> html.contains("hg-drama-card"));
        }
        JSONArray categories = parseCategories(result.html);
        if (categories.length() > 0) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < categories.length(); i++) ids.add(categories.getJSONObject(i).getString("id"));
            synchronized (LOCK) {
                knownCategories = ids;
            }
        }
        Page hot = get("/api/videos/category/" + first + "?sort=hot_window&page="
                + current + "&size=21", html -> {
            try {
                JSONObject data = new JSONObject(html).optJSONObject("data");
                return data != null && data.optJSONArray("items") != null
                        && data.optJSONObject("pagination") != null;
            } catch (org.json.JSONException error) {
                return false;
            }
        });
        JSONObject data = new JSONObject(hot.html).getJSONObject("data");
        JSONArray items = data.getJSONArray("items");
        JSONArray list = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            int episodes = item.optInt("episode_count", 0);
            list.put(new JSONObject()
                    .put("id", item.optString("id", ""))
                    .put("title", item.optString("title", ""))
                    .put("poster", imageRef(absolute(item.optString("cover", ""), hot.origin)))
                    .put("year", "")
                    .put("rate", item.isNull("score") ? "" : item.optString("score", ""))
                    .put("source", "huangguo")
                    .put("source_name", "黃果")
                    .put("desc", item.optString("description", ""))
                    .put("remarks", episodes > 0 ? "更新至" + episodes + "集" : "")
                    .put("episodes", new JSONArray()));
        }
        return new JSONObject()
                .put("list", list)
                .put("page", current)
                .put("pagecount", Math.max(1, data.getJSONObject("pagination").optInt("pages", 1)))
                .put("categories", categories);
    }

    static JSONObject search(String query) throws Exception {
        Page result = get("/search/?keyword=" + encode(query.trim()), html -> html.contains("hg-layout"));
        return new JSONObject().put("list", parseCards(result.html, result.origin));
    }

    static JSONObject detail(String id) throws Exception {
        requireNumber(id);
        Page result = get("/video/" + id + "/", html -> html.contains("id=\"videoInitialData\""));
        JSONObject data = videoData(result.html);
        List<Integer> numbers = episodeNumbers(result.html, data);
        JSONArray episodes = new JSONArray();
        for (int n : numbers) episodes.put("第" + n + "集$" + EPISODE_PREFIX + id + ":" + n);
        String poster = data.optString("posterSrc", data.optString("coverSrc", ""));
        String time = data.optString("time", "");
        return new JSONObject()
                .put("id", id)
                .put("title", data.optString("title", ""))
                .put("poster", imageRef(poster))
                .put("year", time.length() >= 4 ? time.substring(0, 4) : "")
                .put("rate", "")
                .put("source", "huangguo")
                .put("source_name", "黃果")
                .put("desc", data.optString("description", ""))
                .put("remarks", numbers.isEmpty() ? "" : "更新至" + numbers.size() + "集")
                .put("episodes", episodes);
    }

    /** Resolves a fresh signed HLS playlist URL for an {@code hgep:ID:N} episode reference. */
    static EpisodeSource episodeSource(String reference) throws Exception {
        Matcher match = Pattern.compile("^" + EPISODE_PREFIX + "(\\d+):(\\d+)$").matcher(reference.trim());
        if (!match.matches()) throw new IOException("Invalid episode reference");
        String id = match.group(1);
        int episode = Integer.parseInt(match.group(2));
        String path = episode > 1 ? "/video/" + id + "/ep-" + episode + "/" : "/video/" + id + "/";
        Page result = get(path, html -> html.contains("id=\"videoInitialData\""));
        String url = videoData(result.html).optJSONObject("epPlaySrcs") == null ? ""
                : videoData(result.html).optJSONObject("epPlaySrcs").optString(String.valueOf(episode), "");
        if (!url.isEmpty()) return new EpisodeSource(url, result.origin, "", "", "");
        Page playback = get("/api/videos/" + id + "/episodes/" + episode + "/playback", body -> {
            try {
                JSONObject response = new JSONObject(body);
                JSONObject data = response.optJSONObject("data");
                JSONObject streams = data == null ? null : data.optJSONObject("streams");
                return response.optInt("status") == 1 && data != null
                        && "GET".equals(data.optString("manifest_method"))
                        && streams != null && !streams.optString("main").isEmpty();
            } catch (org.json.JSONException error) {
                return false;
            }
        }, true);
        JSONObject data = new JSONObject(playback.html).getJSONObject("data");
        JSONObject cdn = data.optJSONObject("cdn");
        boolean enabled = cdn != null && cdn.optBoolean("m3u8Enabled");
        return new EpisodeSource(data.getJSONObject("streams").getString("main"), playback.origin,
                enabled ? cdn.optString("authKey") : "", enabled ? cdn.optString("authValue") : "",
                enabled ? cdn.optString("app") : "");
    }

    /** Downloads a cover image and decrypts it when the site served it encrypted. */
    static byte[] fetchImage(String reference) throws Exception {
        String url = reference.startsWith(IMAGE_PREFIX) ? reference.substring(IMAGE_PREFIX.length()) : reference;
        byte[] bytes = fetchBytes(url);
        if (isImage(bytes)) return bytes;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                loadImageKey(attempt > 0);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, imageKey, new IvParameterSpec(imageIv));
                byte[] plain = cipher.doFinal(bytes);
                if (isImage(plain)) return plain;
            } catch (IOException | java.security.GeneralSecurityException ignored) {
                // Retried once with a freshly downloaded key.
            }
        }
        throw new IOException("Image decryption failed");
    }

    private static synchronized void loadImageKey(boolean refresh) throws Exception {
        if (imageKey != null && !refresh) return;
        String html = get(KEY_PATH, text -> text.contains("decryptKey")).html;
        Matcher key = Pattern.compile("\"decryptKey\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        Matcher iv = Pattern.compile("\"decryptIV\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        if (!key.find() || !iv.find()) throw new IOException("AI黃果 image key unavailable");
        imageKey = new SecretKeySpec(key.group(1).getBytes(StandardCharsets.UTF_8), "AES");
        imageIv = iv.group(1).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        return (b[0] == (byte) 0xff && b[1] == (byte) 0xd8)
                || (b[0] == (byte) 0x89 && b[1] == 0x50)
                || (b[0] == 0x47 && b[1] == 0x49)
                || (b[0] == 0x52 && b[1] == 0x49 && b[8] == 0x57);
    }

    private static String imageRef(String url) {
        return url == null || !url.matches("(?i)^https?://.*") ? "" : IMAGE_PREFIX + url;
    }

    private static String categoryPath(String id, int page) {
        return "/" + id + "/" + (page > 1 ? page + "/" : "");
    }

    private static void requireNumber(String value) throws IOException {
        if (value == null || !value.matches("\\d+")) throw new IOException("Invalid id");
    }

    private static List<String> origins(boolean refresh) {
        synchronized (LOCK) {
            if (refresh || routes.isEmpty() || System.currentTimeMillis() - routesAt > ROUTE_TTL_MS) {
                discover();
            }
            Set<String> all = new LinkedHashSet<>();
            if (!goodOrigin.isEmpty()) all.add(goodOrigin);
            all.addAll(routes);
            all.addAll(Arrays.asList(FALLBACK_ORIGINS));
            return new ArrayList<>(all);
        }
    }

    private static void discover() {
        for (String portal : PORTALS) {
            try {
                Matcher match = ROUTE.matcher(fetchText(portal));
                List<String> found = new ArrayList<>();
                while (match.find()) {
                    String origin = match.group(1).replaceAll("/+$", "");
                    if (!found.contains(origin)) found.add(origin);
                }
                if (!found.isEmpty()) {
                    routes = found;
                    routesAt = System.currentTimeMillis();
                    return;
                }
            } catch (Exception ignored) {
                // Try the next portal.
            }
        }
    }

    // Tries the last working origin, then every route; if all fail, re-reads the portal once.
    private static Page get(String path, Validator validator) throws IOException {
        return get(path, validator, false);
    }

    private static Page get(String path, Validator validator, boolean post) throws IOException {
        Set<String> tried = new LinkedHashSet<>();
        IOException failure = new IOException("AI黃果 is unreachable");
        for (boolean refresh : new boolean[]{false, true}) {
            for (String origin : origins(refresh)) {
                if (!tried.add(origin)) continue;
                try {
                    String html = post
                            ? new String(fetchBytes(origin + path, true), StandardCharsets.UTF_8)
                            : fetchText(origin + path);
                    if (validator.ok(html)) {
                        synchronized (LOCK) {
                            goodOrigin = origin;
                        }
                        return new Page(html, origin);
                    }
                    failure = new IOException("AI黃果 returned an unexpected page");
                } catch (IOException error) {
                    failure = error;
                }
            }
        }
        throw failure;
    }

    private static String fetchText(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        return fetchBytes(url, false);
    }

    private static byte[] fetchBytes(String url, boolean post) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (post) {
                URL target = new URL(url);
                String origin = target.getProtocol() + "://" + target.getAuthority();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Origin", origin);
                connection.setRequestProperty("Referer", origin + "/");
                connection.setDoOutput(true);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write("{}".getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("AI黃果 returned HTTP " + status);
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw new IOException("AI黃果 response too large");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject videoData(String html) throws Exception {
        Matcher match = VIDEO_DATA.matcher(html);
        if (!match.find()) throw new IOException("AI黃果 detail page layout changed");
        return new JSONObject(match.group(1));
    }

    // A video page only embeds playable URLs for neighbouring episodes, so the full list
    // comes from the episode selector (falling back to the tracking data).
    private static List<Integer> episodeNumbers(String html, JSONObject data) {
        Set<Integer> numbers = new java.util.TreeSet<>();
        Matcher match = Pattern.compile("data-ep-id=\"(\\d+)\"").matcher(html);
        while (match.find()) numbers.add(Integer.parseInt(match.group(1)));
        if (numbers.isEmpty()) {
            for (String name : new String[]{"trackingEpisodes", "epPlaySrcs"}) {
                JSONObject source = data.optJSONObject(name);
                if (source == null) continue;
                java.util.Iterator<String> keys = source.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.matches("\\d+")) numbers.add(Integer.parseInt(key));
                }
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int n : numbers) if (n > 0) result.add(n);
        return result;
    }

    // Cards after the header belong to the page (the header holds a "guess you like" popup);
    // the first <template> starts the hidden hot/original/random tabs, which are skipped.
    private static JSONArray parseCards(String html, String origin) throws Exception {
        int header = html.indexOf("</header>");
        String body = header >= 0 ? html.substring(header + 9) : html;
        int template = body.indexOf("<template");
        if (template >= 0) body = body.substring(0, template);
        JSONArray list = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        String[] parts = body.split("<div class=\"hg-drama-card\"");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            String id = first(part, "href=\"/video/(\\d+)/");
            if (id.isEmpty() || !seen.add(id)) continue;
            String title = decode(first(part, "data-track-title=\"([^\"]*)\""));
            if (title.isEmpty()) {
                title = stripTags(first(part, "<h3 class=\"hg-drama-card__title\">([\\s\\S]*?)</h3>"))
                        .replaceAll("全集在线观看$", "");
            }
            list.put(new JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("poster", imageRef(absolute(first(part, "data-src=\"([^\"]+)\""), origin)))
                    .put("year", "")
                    .put("rate", first(part, "hg-drama-card__score\">\\s*([\\d.]+)"))
                    .put("source", "huangguo")
                    .put("source_name", "黃果")
                    .put("desc", stripTags(first(part, "<p class=\"hg-drama-card__desc\">([\\s\\S]*?)</p>")))
                    .put("remarks", decode(first(part, "data-ep-base=\"([^\"]*)\"")))
                    .put("episodes", new JSONArray()));
        }
        return list;
    }

    private static int parsePageCount(String html) {
        String declared = first(html, "data-pages=\"(\\d+)\"");
        if (!declared.isEmpty() && Integer.parseInt(declared) > 0) return Integer.parseInt(declared);
        int max = 1;
        Matcher match = Pattern.compile("class=\"hg-pager__page[^\"]*\"[^>]*>(\\d+)<").matcher(html);
        while (match.find()) max = Math.max(max, Integer.parseInt(match.group(1)));
        return max;
    }

    private static JSONArray parseCategories(String html) throws Exception {
        JSONArray categories = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        Matcher match = CATEGORY.matcher(html);
        while (match.find()) {
            if (seen.add(match.group(2))) {
                categories.put(new JSONObject().put("id", match.group(2)).put("name", decode(match.group(1))));
            }
        }
        return categories;
    }

    private static String first(String text, String regex) {
        Matcher match = Pattern.compile(regex).matcher(text);
        return match.find() ? match.group(1) : "";
    }

    private static String absolute(String value, String origin) {
        String v = decode(value).trim();
        if (v.isEmpty()) return "";
        if (v.startsWith("//")) return "https:" + v;
        if (v.matches("(?i)^https?:.*")) return v;
        return origin + (v.startsWith("/") ? v : "/" + v);
    }

    private static String stripTags(String html) {
        return decode(html.replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    private static String decode(String value) {
        if (value == null) return "";
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
