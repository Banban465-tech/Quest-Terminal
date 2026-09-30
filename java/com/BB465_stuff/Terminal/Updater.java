package com.BB465_stuff.Terminal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks GitHub what releases this repo has, and fetches one. There is no release
 * channel here: the newest published release that carries an apk is the one you
 * get. The install itself is left to the caller - nothing in this file writes an
 * APK over the installed app.
 *
 * The releases API returns a lot of JSON and the project has no JSON library, so
 * this reads the few fields it needs by hand. It only ever parses GitHub's own
 * response for this repo, so the format is known.
 */
final class Updater {

    static final String REPO = "Banban465-tech/Quest-Terminal";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases";
    static final String ASSET = "terminal.apk";

    private static final int TIMEOUT_MS = 20000;
    private static final long MAX_APK = 96L * 1024 * 1024;

    static final class Release {
        String tag = "";
        String name = "";
        String url = "";        // browser_download_url of the apk
        String notes = "";
        boolean pre;
        boolean draft;
        String published = "";  // published_at
        long publishedMs;

        /** the bit a person recognises, falls back to the tag when there is no name */
        String label() {
            return (name != null && name.trim().length() > 0) ? name.trim() : tag;
        }

        /** "1.2.2" style names only, so junk tags compare as newer instead of breaking */
        int[] version() {
            String s = label();
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
            String[] parts = s.split("\\.");
            if (parts.length == 0 || parts.length > 4) return null;
            int[] v = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { v[i] = Integer.parseInt(parts[i].trim()); }
                catch (NumberFormatException e) { return null; }
            }
            return v;
        }
    }

    // ---------------------------------------------------------------- releases

    /** newest published release that actually carries an apk, or null */
    static Release latest() throws Exception {
        Release[] all = list();
        Release best = null;
        for (Release r : all) {
            if (r.draft) continue;
            if (r.url.length() == 0) continue;          // nothing to install
            if (best == null || r.publishedMs > best.publishedMs) best = r;
        }
        return best;
    }

    static Release[] list() throws Exception {
        String body = get(API, "application/vnd.github+json");
        List<Release> out = new ArrayList<Release>();
        for (String chunk : objects(body)) {
            Release r = new Release();
            r.tag = str(chunk, "tag_name");
            r.name = str(chunk, "name");
            r.pre = bool(chunk, "prerelease");
            r.draft = bool(chunk, "draft");
            r.published = str(chunk, "published_at");
            r.publishedMs = parseDate(r.published);
            r.notes = str(chunk, "body");
            r.url = asset(chunk, ASSET);
            out.add(r);
        }
        return out.toArray(new Release[out.size()]);
    }

    /** is the candidate actually newer than what is installed */
    static boolean newerThan(Release r, String currentVersion) {
        if (r == null) return false;
        int[] a = r.version();
        int[] b = parse(currentVersion);
        if (a == null || b == null) return true;   // unparseable names, trust the date
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    static int[] parse(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return out.length == 0 ? null : out;
    }

    // ---------------------------------------------------------------- download

    /** fetch the apk to a local file, reporting progress in percent */
    static File download(String url, final Progress p) throws Exception {
        HttpURLConnection c = open(url, "application/octet-stream");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("http " + code);
        long len = c.getContentLengthLong();
        if (len > MAX_APK) throw new Exception("apk is " + (len / 1048576) + "MB, refusing");

        File dir = new File(Updater.fallback(), "dl");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("no cache dir");
        File out = new File(dir, "terminal-update.apk");
        FileOutputStream fos = new FileOutputStream(out);
        InputStream in = c.getInputStream();
        try {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            int last = -1;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (len > 0) {
                    int pct = (int) (got * 100 / len);
                    if (pct != last) { last = pct; p.progress(pct); }
                }
            }
            if (got < 1024) throw new Exception("only got " + got + " bytes");
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { fos.close(); } catch (Throwable ignored) { }
            c.disconnect();
        }
        p.progress(100);
        return out;
    }

    interface Progress {
        void progress(int pct);
    }

    // ---------------------------------------------------------------- plumbing

    private static String cache;

    static void cacheDir(String dir) { cache = dir; }

    private static String fallback() {
        return cache != null ? cache : System.getProperty("java.io.tmpdir");
    }

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "QuestTerminal");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return c;
    }

    private static String get(String url, String accept) throws Exception {
        HttpURLConnection c = open(url, accept);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new Exception("no releases yet");
            if (code == 403) throw new Exception("github rate limit, try later");
            if (code < 200 || code >= 300) throw new Exception("http " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            } finally { in.close(); }
            return new String(bos.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- tiny json

    /** every top level {...} in an array body, strings still escaped */
    static List<String> objects(String json) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '{') {
                if (depth == 1) start = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 1 && start >= 0) {
                    out.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** a string field, unescaped, "" when absent */
    static String str(String obj, String key) {
        String raw = raw(obj, key);
        if (raw == null) return "";
        if (raw.length() >= 2 && raw.charAt(0) == '"') {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        return raw;
    }

    static boolean bool(String obj, String key) {
        return "true".equals(raw(obj, key));
    }

    /** the raw token after "key": , or null */
    private static String raw(String obj, String key) {
        String needle = "\"" + key + "\"";
        int at = obj.indexOf(needle);
        if (at < 0) return null;
        int i = at + needle.length();
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':')) i++;
        if (i >= obj.length()) return null;
        if (obj.charAt(i) == '"') {
            int end = i + 1;
            boolean esc = false;
            while (end < obj.length()) {
                char ch = obj.charAt(end);
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') return obj.substring(i, end + 1);
                end++;
            }
            return null;
        }
        int end = i;
        while (end < obj.length() && ",}]\n\r ".indexOf(obj.charAt(end)) < 0) end++;
        return obj.substring(i, end);
    }

    /** browser_download_url of the named asset, "" when the release has no such file */
    static String asset(String releaseJson, String name) {
        int at = releaseJson.indexOf("\"assets\"");
        if (at < 0) return "";
        int depth = 0;
        int i = at;
        boolean inStr = false;
        boolean esc = false;
        int start = -1;
        for (; i < releaseJson.length(); i++) {
            char ch = releaseJson.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '{') { if (depth == 1) start = i; depth++; }
            else if (ch == '}') {
                depth--;
                if (depth == 1 && start >= 0) {
                    String a = releaseJson.substring(start, i + 1);
                    if (name.equals(str(a, "name"))) return str(a, "browser_download_url");
                    start = -1;
                }
            }
        }
        return "";
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { b.append(ch); continue; }
            char nx = s.charAt(++i);
            switch (nx) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { b.append('u'); }
                    }
                    break;
                default: b.append(nx);
            }
        }
        return b.toString();
    }

    private static long parseDate(String iso) {
        // 2026-09-28T02:42:23Z
        if (iso == null || iso.length() < 10) return 0;
        try {
            int y = Integer.parseInt(iso.substring(0, 4));
            int m = Integer.parseInt(iso.substring(5, 7));
            int d = Integer.parseInt(iso.substring(8, 10));
            return ((long) y * 372 + (m - 1) * 31 + (d - 1)) * 86400000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private Updater() {}
}
