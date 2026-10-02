package com.BB465_stuff.Terminal;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches the video once into the app's own cache.
 *
 * The question this answers is "how does it play on a Quest that has never
 * seen the file". It cannot ride along in the apk: the source is 12MB, which
 * would take a 2.7MB apk to 15MB and every user would pay that on install for
 * a joke they may never open. And copying it by hand means an adb push per
 * headset, which is exactly the per-device work this is meant to avoid.
 *
 * So the app takes the URL, writes it into cacheDir, and plays from there. The
 * cache copy is app-private, which also means it needs no storage permission at
 * all, so this path works even where the READ_MEDIA_VIDEO prompt was refused.
 */
class AppleSource {

    /** called from a worker thread */
    interface Progress { void bytes(long got, long total); }

    private static final int TIMEOUT_MS = 30000;
    private static final String NAME = "badapple.mp4";

    /** the canonical copy, kept outside the repo's release assets */
    static final String DEFAULT_URL =
            "https://github.com/Banban465-tech/Quest-Terminal/releases/download/badapple/badapple.mp4";

    static File cached(Context ctx) {
        return new File(ctx.getCacheDir(), NAME);
    }

    /**
     * @return null on success, otherwise the reason it did not happen
     */
    static String fetch(Context ctx, String url, Progress prog) {
        File out = cached(ctx);
        File part = new File(ctx.getCacheDir(), NAME + ".part");

        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "QuestTerminal");

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                return "badapple: http " + code + " fetching the video\n";
            }
            long total = c.getContentLengthLong();

            in = c.getInputStream();
            fos = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (prog != null) prog.bytes(got, total);
            }
            fos.flush();
            fos.close();
            fos = null;

            if (got == 0) {
                part.delete();
                return "badapple: the server sent an empty file\n";
            }

            // only replace the good copy once the new one is completely there,
            // so a download cut off halfway cannot destroy a working cache
            if (out.exists() && !out.delete()) {
                part.delete();
                return "badapple: cannot replace the cached copy\n";
            }
            if (!part.renameTo(out)) {
                part.delete();
                return "badapple: could not move the download into place\n";
            }
            return null;
        } catch (Exception e) {
            try { if (part != null) part.delete(); } catch (Throwable ignored) { }
            return "badapple: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "\n";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private AppleSource() { }
}