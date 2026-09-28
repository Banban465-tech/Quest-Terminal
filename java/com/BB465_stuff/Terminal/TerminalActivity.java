package com.BB465_stuff.Terminal;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tabbed terminal.
 *
 * Each tab is an independent session with its own scrollback, cwd and history.
 * Commands go over a Messenger to ShellService, which Shizuku runs as uid 2000.
 *
 * The builtins live here rather than in the service because they are UI-level
 * conveniences (tab state, local history) except for pkg/su/info which are
 * handed to the shell.
 */
public class TerminalActivity extends Activity {

    private LinearLayout root;
    private LinearLayout tabBar;
    private ScrollView scroll;
    private TextView output;
    private EditText input;
    private TextView status;

    private final List<Session> sessions = new ArrayList<Session>();
    private int current = 0;

    static class Session {
        // spannable, not StringBuilder: the prompt line carries its own color
        // so 'prompt color' can change it without re-running anything
        android.text.SpannableStringBuilder buf =
                new android.text.SpannableStringBuilder();
        final List<String> history = new ArrayList<String>();
        int histPos = 0;
        String cwd = "";
        String title = "sh";
        Thread worker;
        volatile boolean busy;
        /** true while this tab is driving a live PowerShell session */
        volatile boolean ps;
        /**
         * uid this session is running as. 2000 is the Shizuku/ByteZuku shell,
         * 0 means a granted su. The prompt glyph reads off this: $ for shell,
         * # for root. Refreshed by 'info' and after a successful su.
         */
        volatile int uid = 2000;
        /**
         * PowerShell input typed but not yet sent, because it is mid-block.
         * Each line used to be executed the moment it arrived, so a block
         * opener was a broken fragment:
         *   foreach ($i in 1..3) {   ->  "n="   then a ParserError on the '}'
         * and the sentinel got absorbed into the loop body. Holding the text
         * until it is a complete statement is what makes a loop, a function
         * or a here-string work at all.
         */
        final StringBuilder psPending = new StringBuilder();
        /** where we were before the last cd, for 'cd -' */
        String prevCwd = "";
    }

    private Shell sh;
    // in-process adb transport, used when Shizuku/ByteZuku is unavailable
    private AdbKey adbKey;
    private String adbHost;
    private int adbPort;
    private static final android.os.Handler UI =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        // stored preferences decide the font size, keep-screen-on and prompt
        // style before anything is drawn
        applyPrefs();
        // connect() can fail synchronously (no shizuku / no permission), and the
        // callbacks touch the UI, so hand it to the handler to run after onCreate
        // has finished assigning the views.
        UI.post(new Runnable() {
            public void run() { connectShell(); }
        });
    }

    private final Handler tick = new Handler(Looper.getMainLooper());

    /**
     * Keep the status line honest. Shizuku/ByteZuku answers the access prompt
     * asynchronously and the app previously had no way to notice, so the line
     * just read "checking" forever whether the user allowed or refused.
     */
    private final Runnable poller = new Runnable() {
        public void run() {
            if (sh == null) {
                String s = "shizuku: " + Shell.describeState();
                if (!s.equals(status.getText())) status.setText(s);
            }
            tick.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        tick.postDelayed(poller, 300);
        // No automatic adb arming any more. It was a guess that cost 15s of
        // socket timeouts on every launch and could never succeed: the PC's
        // key is paired over the WIFI TLS transport, adbd tracks authorisation
        // per transport, and a plain TCP 5555 connection from inside the app
        // gets a confirmation prompt a headset cannot answer. adb_keys is
        // root-only and TLS/SPAKE2 pairing is not reimplemented here, so the
        // shell comes from Shizuku instead. The ADB dialog is still there as
        // a manual escape hatch if that ever changes.
    }

    // ------------------------------------------------------------ adb endpoint
    // adbd keeps a fixed tcp port in persist.adb.tcp.port, so the app can find
    // its own device over loopback and re-arm the transport with no UI at all.

    private android.content.SharedPreferences adbPrefs() {
        return getSharedPreferences("adbcfg", android.content.Context.MODE_PRIVATE);
    }

    /** adbd's listening port, read straight off the device. */
    private int adbDevicePort() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(Runtime.getRuntime()
                    .exec(new String[]{"getprop", "persist.adb.tcp.port"})
                    .getInputStream()));
            String s = r.readLine();
            if (s != null && s.trim().length() > 0) {
                int p = Integer.parseInt(s.trim());
                if (p > 0 && p < 65536) return p;
            }
        } catch (Throwable t) { /* fall through to the common default */ }
        return 5555;
    }

    /** This device's own non-loopback IPv4 address, so the app looks like a LAN host. */
    private String adbLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress ia = as.nextElement();
                    if (!ia.isLoopbackAddress() && ia.getAddress().length == 4) {
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.i("BBterm", "adbLanIp failed: " + t);
        }
        return null;
    }

    private static String readAll(java.io.BufferedReader r) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        return b.toString();
    }

    private AdbKey loadAdbKey() {
        // An app cannot add its own key to adb_keys: modern adbd only accepts
        // new keys over the TLS/SPAKE2 wireless pairing transport, and
        // /data/misc/adb is unreadable even to shell. So prefer a key the
        // developer has already authorised on the PC, which needs no pairing.
        try {
            java.io.File pub = new java.io.File("/data/local/tmp/pc_adbkey");
            if (pub.isFile() && pub.canRead()) {
                AdbKey k = AdbKey.fromPem(pub);
                try {
                    java.security.MessageDigest md =
                        java.security.MessageDigest.getInstance("SHA-256");
                    byte[] h = md.digest(k.encodeBase64().getBytes("UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i++) {
                        sb.append(String.format("%02x", h[i]));
                    }
                    android.util.Log.i("BBterm", "keyhash=" + sb);
                } catch (Throwable t2) { }
                return k;
            }
        } catch (Throwable t) { /* fall through to our own key */ }
        try {
            java.io.File f = new java.io.File(getFilesDir(), "adbkey");
            return f.isFile() ? AdbKey.fromPem(f) : null;
        } catch (Throwable t) { return null; }
    }

    /**
     * Bring the adb transport up on its own. Tries the remembered endpoint
     * first, then loopback on the device's persisted adbd port. Runs off the UI
     * thread and reports the outcome to logcat under the BBterm tag.
     */
    private void autoArmAdb() {
        if (sh != null) return;
        final String host = adbPrefs().getString("host", null);
        final int port = adbPrefs().getInt("port", 0);
        new Thread(new Runnable() {
            public void run() {
                final AdbKey k = loadAdbKey();
                if (k == null) {
                    android.util.Log.i("BBterm", "autoArm: no adb key yet");
                    return;
                }
                int devPort = port > 0 ? port : adbDevicePort();
                String lan = adbLanIp();

                // Prefer a LAN address: a loopback connection comes from an app
                // on the device itself, and adbd demands an interactive
                // confirmation for those, which a headset cannot satisfy.
                java.util.LinkedHashMap<String, Integer> cand =
                    new java.util.LinkedHashMap<String, Integer>();
                if (host != null && host.indexOf("127.0.0.1") < 0) cand.put(host, devPort);
                if (lan != null && !lan.equals("127.0.0.1")) cand.put(lan, devPort);
                cand.put("127.0.0.1", devPort);
                android.util.Log.i("BBterm", "autoArm: lan=" + lan
                    + " port=" + devPort + " saved=" + host);

                for (java.util.Map.Entry<String, Integer> e : cand.entrySet()) {
                    AdbLink link = new AdbLink(k);
                    try {
                        if (link.connect(e.getKey(), e.getValue())) {
                            final String hh = e.getKey();
                            final int pp = e.getValue();
                            post(new Runnable() {
                                public void run() {
                                    adbKey = k; adbHost = hh; adbPort = pp;
                                    if (sh == null && status != null) {
                                        status.setText("shizuku: down | adb " + hh + ":" + pp);
                                    }
                                }
                            });
                            android.util.Log.i("BBterm", "autoArm: connected " + hh + ":" + pp);
                            return;
                        }
                        android.util.Log.i("BBterm", "autoArm: no " + e.getKey()
                            + " -> " + link.lastLog());
                    } catch (Throwable t) {
                        android.util.Log.i("BBterm", "autoArm: " + e.getKey() + " error " + t);
                    } finally {
                        link.close();
                    }
                }
                android.util.Log.i("BBterm", "autoArm: no endpoint worked");
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Shell.REQUEST_CODE) {
            // Report the actual verdict instead of blindly retrying.
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            status.setText("shizuku: " + (granted ? "ALLOWED" : "REFUSED")
                    + " - " + Shell.describeState());
            if (granted) {
                tick.postDelayed(new Runnable() {
                    public void run() { connectShell(); }
                }, 400);
            } else {
                append("Shizuku/ByteZuku refused access. Nothing can run without it.\n");
            }
        }
    }

    private void connectShell() {
        // built once and kept, so checkshizuku can re-run the bind later with
        // the same callbacks instead of only being able to report the state
        if (shCb == null) shCb = makeCallback();
        Shell.connect(this, shCb);
    }

    private Shell.Callback shCb;

    private Shell.Callback makeCallback() {
        // so a stale :bbterm can report itself even if it turns up later
        Shell.watchStale(this.shCb);
        return new Shell.Callback() {
            @Override
            public void onReady(Shell s) {
                sh = s;
                status.setText("shizuku: connected (uid 2000 shell)");
                // ask the headset what it is before the prompt needs to say so
                loadCodename(s);
                // prime the facts table so 'info' and 'rootcheck' have real
                // values the first time they are used
                loadFacts();
                newSession();
                if (pon("autoPowershell", false)) {
                    doPwshStart(sessions.get(current));
                }
            }
            @Override
            public void onFail(String why) {
                // Keep the concrete state visible instead of a vague message.
                status.setText("shizuku: " + Shell.describeState()
                        + (why.startsWith("requesting") ? " - awaiting your answer" : ""));
                if (why.startsWith("requesting")) {
                    append("Asked Shizuku/ByteZuku for access. Answer the prompt;"
                            + " this line will say GRANTED or DENIED.\n");
                } else if (why.startsWith("the running shell service")) {
                    append(why + "\n");
                } else {
                    append("cannot reach a shell: " + why + "\n\n"
                         + "Shizuku/ByteZuku must be running and this app granted.\n"
                         + "Open the manager, grant Terminal access, then reopen.\n");
                }
            }
            @Override
            public void onReply(int id, String out, String err, int code) {
                onShellReply(id, out, err, code);
            }
        };
    }

    // ------------------------------------------------------------------ prefs

    /**
     * Every preference is stored as a string and cycles through a fixed list of
     * words. That keeps the panel dead simple -- one row type, tap to advance --
     * and a bad stored value can never wedge a typed getter, because anything
     * not in the list falls back to the default.
     */
    private static final class Pref {
        final String key, label;
        final String[] opts;
        Pref(String key, String label, String... opts) {
            this.key = key; this.label = label; this.opts = opts;
        }
    }

    private static final Pref[] PREFS = {
        new Pref("promptStyle",    "prompt style",          "cmd", "plain", "unix"),
        new Pref("rootGlyph",      "root glyph #",          "on", "off"),
        new Pref("psBanner",       "powershell banner",     "on", "off"),
        new Pref("autoPowershell", "auto-start powershell", "off", "on"),
        new Pref("confirmClear",   "confirm before clear",  "off", "on"),
        new Pref("keepScreenOn",   "keep screen on",        "off", "on"),
        new Pref("fontSize",       "font size",    "9", "10", "11", "12", "14", "16", "18"),
        new Pref("font",           "font", "default", "mono", "mono-bold", "tinos",
                "opensans", "serif-mono", "casual"),
        new Pref("scrollback",     "scrollback",   "20k", "100k", "400k"),
        new Pref("startDir",       "start folder", "/", "/storage/emulated/0"),
        new Pref("busybox",        "busybox for scripts",   "on", "off"),
        new Pref("verboseLog",     "verbose logging",       "off", "on"),
    };

    // ---------------------------------------------------------------- colors
    // Stored as "#RRGGBB" strings, read straight out of prefs rather than
    // through pget(), because pget only accepts values from a Pref's option
    // list and a color is not a fixed list.
    static final String K_BG     = "bgHex";
    static final String K_PS     = "psHex";
    static final String K_FG     = "fgHex";
    static final String K_PROMPT = "promptHex";
    static final String K_DIM    = "dimHex";

    private static final int DEF_BG     = 0xFF000000;
    private static final int DEF_PS     = 0xFF012456;   // the classic console blue
    private static final int DEF_FG     = 0xFFCCCCCC;
    private static final int DEF_PROMPT = 0xFFCCCCCC;
    private static final int DEF_DIM    = 0xFF7A7A7A;

    /** one-tap picks for the color dialog, laid out dark to light */
    private static final int[] SWATCHES = {
        0xFF000000, 0xFF0B0B0F, 0xFF121216, 0xFF1A1A1A, 0xFF2A2A2A, 0xFF3A3A3A,
        0xFFCCCCCC, 0xFFFFFFFF, 0xFF7A7A7A, 0xFF7BE07B, 0xFFE0C07B, 0xFF9CDCFE,
        0xFF012456, 0xFF0A3A5C, 0xFF203040, 0xFF4A1F00, 0xFF5C0A0A, 0xFF1F4A1F,
    };

    /** theme presets, each one writes all five color keys at once */
    private static final String[] THEME_NAMES  = { "black", "console", "matrix", "amber", "slate", "light" };
    private static final int[][] THEME_VALUES = {
        { 0xFF000000, 0xFF012456, 0xFFCCCCCC, 0xFFCCCCCC, 0xFF7A7A7A },
        { 0xFF0B0B0F, 0xFF0A3A5C, 0xFFE0E0E0, 0xFF9CDCFE, 0xFF8A8A8A },
        { 0xFF000000, 0xFF001A00, 0xFF7BE07B, 0xFFB8FFC0, 0xFF3F7F45 },
        { 0xFF140D05, 0xFF2A1B02, 0xFFE0C07B, 0xFFFFD48A, 0xFF8A6A3A },
        { 0xFF101418, 0xFF1B2733, 0xFFC8D2DC, 0xFF7FB2D9, 0xFF6C7A87 },
        { 0xFFEDEDF2, 0xFFDCE4EC, 0xFF1A1A1A, 0xFF0A4A8A, 0xFF6A6A6A },
    };

    private int cget(String key, int def) {
        String v = adbPrefs().getString(key, null);
        if (v == null) return def;
        try {
            return android.graphics.Color.parseColor(
                    v.startsWith("#") ? v : "#" + v);
        } catch (Throwable t) {
            return def;   // hand-edited garbage in prefs, fall back
        }
    }

    private void cput(String key, int c) {
        adbPrefs().edit()
                .putString(key, String.format(Locale.US, "#%06X", 0xFFFFFF & c))
                .apply();
        applyColors();
    }

    private void cputTheme(int i) {
        int[] v = THEME_VALUES[((i % THEME_VALUES.length) + THEME_VALUES.length)
                % THEME_VALUES.length];
        android.content.SharedPreferences.Editor e = adbPrefs().edit();
        e.putString(K_BG,     hex(v[0]));
        e.putString(K_PS,     hex(v[1]));
        e.putString(K_FG,     hex(v[2]));
        e.putString(K_PROMPT, hex(v[3]));
        e.putString(K_DIM,    hex(v[4]));
        e.apply();
        applyColors();
        render();
    }

    private static String hex(int c) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & c);
    }

    /** which preset, if any, the current five colors match */
    private int themeIndex() {
        int[] cur = {
            cget(K_BG, DEF_BG), cget(K_PS, DEF_PS), cget(K_FG, DEF_FG),
            cget(K_PROMPT, DEF_PROMPT), cget(K_DIM, DEF_DIM),
        };
        for (int t = 0; t < THEME_VALUES.length; t++) {
            boolean same = true;
            for (int k = 0; k < 5; k++) {
                if ((cur[k] & 0xFFFFFF) != (THEME_VALUES[t][k] & 0xFFFFFF)) { same = false; break; }
            }
            if (same) return t;
        }
        return 0;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(1);
        return lp;
    }

    /** a small filled square for the color rows; stroked so black reads as black */
    private android.graphics.drawable.Drawable swatch(int c) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setColor(c);
        gd.setStroke(1, 0xFF555555);
        gd.setSize(dp(18), dp(18));
        return gd;
    }

    /** darken a color, for bars and headers that sit on the background */
    private static int shade(int c, float f) {
        int a = (c >>> 24) & 0xFF;
        int r = (int) (((c >> 16) & 0xFF) * f);
        int g = (int) (((c >> 8) & 0xFF) * f);
        int b = (int) ((c & 0xFF) * f);
        return (a << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** current background: PowerShell sessions use their own color */
    private int bgColor() {
        boolean ps = !sessions.isEmpty() && sessions.get(current) != null
                && sessions.get(current).ps;
        return ps ? cget(K_PS, DEF_PS) : cget(K_BG, DEF_BG);
    }

    /**
     * A prompt span we can find again later, so changing 'prompt color'
     * recolors every prompt already on screen instead of only new ones.
     */
    static class PromptMark extends android.text.style.ForegroundColorSpan {
        PromptMark(int color) { super(color); }
    }

    private void recolorPrompts() {
        int c = cget(K_PROMPT, DEF_PROMPT);
        for (Session s : sessions) {
            if (s == null || s.buf == null) continue;
            // ForegroundColorSpan is immutable - there is no setColor() - so
            // each span has to be dropped and re-added at the same range
            PromptMark[] ms = s.buf.getSpans(0, s.buf.length(), PromptMark.class);
            for (PromptMark m : ms) {
                int st = s.buf.getSpanStart(m);
                int en = s.buf.getSpanEnd(m);
                s.buf.removeSpan(m);
                s.buf.setSpan(new PromptMark(c), st, en,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** append the prompt with its own color instead of inheriting the text color */
    private void appendPrompt() { appendPromptThen(""); }

    /**
     * The prompt in the prompt color, then the echoed command in the normal
     * text color. Tinting the whole line would color the command too, which
     * makes it much harder to read what you actually typed.
     */
    private void appendPromptThen(String rest) {
        if (sessions.isEmpty()) newSession();
        Session ss = sessions.get(current);
        int start = ss.buf.length();
        ss.buf.append(prompt());
        ss.buf.setSpan(new PromptMark(cget(K_PROMPT, DEF_PROMPT)),
                start, ss.buf.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (rest != null && rest.length() > 0) ss.buf.append(rest);
        render();
    }

    /**
     * The background belongs to the whole window but depends on the current
     * tab, so it has to be recomputed whenever the tab changes. Without this,
     * starting PowerShell in one tab painted every other tab blue too, because
     * nothing reset it when you switched away.
     */
    private int lastBg = -1;

    private void applyBg() {
        int c = bgColor();
        if (c == lastBg) return;      // append() renders constantly
        lastBg = c;
        if (root != null) root.setBackgroundColor(c);
        if (tabBar != null) tabBar.setBackgroundColor(shade(c, 0.55f));
    }

    private void applyColors() {
        if (output != null) output.setTextColor(cget(K_FG, DEF_FG));
        if (input != null) {
            input.setTextColor(cget(K_FG, DEF_FG));
            input.setHintTextColor(cget(K_DIM, DEF_DIM));
        }
        lastBg = -1;                 // force, the colors just changed
        applyBg();
        if (status != null) status.setTextColor(cget(K_DIM, DEF_DIM));
        recolorPrompts();
    }

    // ----------------------------------------------------------------- fonts
    private android.graphics.Typeface fontFor(String f) {
        android.graphics.Typeface t = android.graphics.Typeface.MONOSPACE;
        if (f.equals("mono")) return t;
        if (f.equals("mono-bold")) {
            return android.graphics.Typeface.create(t, android.graphics.Typeface.BOLD);
        }
        if (f.equals("tinos") || f.equals("opensans")) {
            try {
                return getResources().getFont(f.equals("tinos")
                        ? com.BB465_stuff.Terminal.R.font.tinos
                        : com.BB465_stuff.Terminal.R.font.opensans_condensed);
            } catch (Throwable x) {
                // font missing from the apk, keep the terminal readable
                return t;
            }
        }
        // the remaining two are family names rather than constants, and
        // create() returns null if the device has no such family
        if (f.equals("serif-mono") || f.equals("casual")) {
            try {
                android.graphics.Typeface x = android.graphics.Typeface.create(
                        f.equals("casual") ? "casual" : "serif-monospace",
                        android.graphics.Typeface.NORMAL);
                if (x != null) return x;
            } catch (Throwable ignored) {
            }
        }
        return t;
    }

    private void applyFonts() {
        android.graphics.Typeface tf = fontFor(pget("font", "default"));
        if (output != null) output.setTypeface(tf);
        if (input != null) input.setTypeface(tf);
    }

    private String pget(String key, String def) {
        String v = adbPrefs().getString(key, null);
        if (v == null) return def;
        for (Pref p : PREFS) {
            if (!p.key.equals(key)) continue;
            for (String o : p.opts) if (o.equals(v)) return v;
        }
        return def;   // stored value is not one we know, fall back
    }

    private boolean pon(String key, boolean def) {
        return pget(key, def ? "on" : "off").equals("on");
    }

    private int pint(String key, int def) {
        try { return Integer.parseInt(pget(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    /** like pint, but understands a trailing k so the panel can say "100k" */
    private int pintk(String key, int def) {
        String v = pget(key, String.valueOf(def));
        try {
            if (v.length() > 1 && (v.endsWith("k") || v.endsWith("K"))) {
                return Integer.parseInt(v.substring(0, v.length() - 1)) * 1000;
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) { return def; }
    }

    private void pput(String key, String value) {
        adbPrefs().edit().putString(key, value).apply();
        applyPrefs();
        render();
    }

    /** PROMPT_STYLES is an array, so it has no indexOf of its own */
    private static int indexOfStyle(String s) {
        for (int i = 0; i < PROMPT_STYLES.length; i++) {
            if (PROMPT_STYLES[i].equals(s)) return i;
        }
        return -1;
    }

    /** push the preferences that act on live UI state */
    private void applyPrefs() {
        int at = indexOfStyle(pget("promptStyle", "cmd"));
        promptStyle = (at < 0) ? 0 : at;
        if (output != null) {
            output.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, pint("fontSize", 11));
        }
        applyFonts();
        applyColors();
        if (pon("keepScreenOn", false)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** interpreter used by 'run <script>' and the one-shot 'pwsh <cmd>' */
    private String scriptShell() {
        return pon("busybox", true) ? "/data/local/tmp/busybox sh " : "sh ";
    }

    /** text form, for when the panel is not what you want */
    private void printPrefs(Session s) {
        append("preferences\n");
        for (Pref p : PREFS) {
            String v = pget(p.key, p.opts[0]);
            StringBuilder sb = new StringBuilder("  " + p.label);
            while (sb.length() < 28) sb.append('.');
            append(sb.append(' ').append(v).append('\n').toString());
        }
        append("\nchange one with:  pref <name> <value>\n"
                + "or open the panel:  prefs\n");
        append("\ncolors\n");
        for (int i = 0; i < COLOR_KEYS.length; i++) {
            StringBuilder cb = new StringBuilder("  " + COLOR_KEYS[i][0]);
            while (cb.length() < 10) cb.append('.');
            append(cb.append(' ').append(hex(cget(COLOR_KEYS[i][1], COLOR_DEFS[i])))
                    .append('\n').toString());
        }
        append("  theme.... " + THEME_NAMES[themeIndex()] + "\n");
        append("\nchange one with:  color <name> <RRGGBB>\n");
    }

    /** set a preference from the command line, same words as the panel */
    private void setPref(String arg, Session s) {
        String a = (arg == null) ? "" : arg.trim();
        if (a.isEmpty()) { printPrefs(s); return; }
        String[] p = a.split("\\s+", 2);
        for (Pref pf : PREFS) {
            if (!pf.key.equalsIgnoreCase(p[0])) continue;
            if (p.length < 2) {
                append(pf.key + " = " + pget(pf.key, pf.opts[0]) + "   options:");
                for (String o : pf.opts) append(" " + o);
                append("\n");
                return;
            }
            String want = p[1].trim();
            for (String o : pf.opts) {
                if (o.equalsIgnoreCase(want)) {
                    pput(pf.key, o);
                    append(pf.label + " = " + o + "\n");
                    return;
                }
            }
            append("bad value. options for " + pf.key + ":");
            for (String o : pf.opts) append(" " + o);
            append("\n");
            return;
        }
        append("unknown preference '" + p[0] + "'. 'prefs' lists them all.\n");
    }

    // ------------------------------------------------------------- color picker
    /**
     * Pick one color. Swatches for the common cases, three sliders and a hex
     * box for everything else, and a live preview strip so you can see the
     * result against the actual terminal background before committing.
     */
    private void showColorPicker(final String key, String label, int cur) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText(label);
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(17);
        box.addView(head);

        final int bg = cget(K_BG, DEF_BG);
        final int fg = cget(K_FG, DEF_FG);

        // preview: real terminal background, the color as the text
        final TextView prev = new TextView(this);
        prev.setText("AaBbCc 123  $ ls -l  C:\\eureka\\>");
        prev.setTextSize(16);
        prev.setPadding(dp(10), dp(14), dp(10), dp(14));
        prev.setBackgroundColor(bg);
        box.addView(prev);

        final int[] rgb = { (cur >> 16) & 0xFF, (cur >> 8) & 0xFF, cur & 0xFF };

        // hex entry
        final EditText hexBox = new EditText(this);
        hexBox.setSingleLine(true);
        hexBox.setTextColor(0xFFEDEDF2);
        hexBox.setHintTextColor(0xFF707070);
        hexBox.setTextSize(13);
        hexBox.setText(hex(cur));
        box.addView(hexBox);

        final android.widget.SeekBar[] bars = new android.widget.SeekBar[3];
        final TextView[] nums = new TextView[3];
        final String[] names = { "R", "G", "B" };
        for (int ch = 0; ch < 3; ch++) {
            final int c = ch;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = new TextView(this);
            n.setText(names[ch]);
            n.setTextColor(0xFFCCCCCC);
            n.setTextSize(12);
            n.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(34)));
            r.addView(n);
            android.widget.SeekBar b = new android.widget.SeekBar(this);
            b.setMax(255);
            b.setProgress(rgb[ch]);
            b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(34), 1f));
            r.addView(b);
            final TextView v = new TextView(this);
            v.setTextColor(0xFFCCCCCC);
            v.setTextSize(12);
            v.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            v.setText(String.valueOf(rgb[ch]));
            v.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(34)));
            r.addView(v);
            b.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                    rgb[c] = p;
                    v.setText(String.valueOf(p));
                    hexBox.setText(hex(0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]));
                    prev.setTextColor(0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
                }
                public void onStartTrackingTouch(android.widget.SeekBar sb) { }
                public void onStopTrackingTouch(android.widget.SeekBar sb) { }
            });
            bars[ch] = b;
            nums[ch] = v;
            box.addView(r);
        }

        // swatches, 6 per row
        for (int i = 0; i < SWATCHES.length; i += 6) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = i; k < i + 6 && k < SWATCHES.length; k++) {
                final int sw = SWATCHES[k];
                TextView cell = new TextView(this);
                cell.setText(" ");
                cell.setGravity(Gravity.CENTER);
                cell.setBackgroundColor(sw);
                // a hairline so near-black swatches are still visible
                cell.setBackground(new android.graphics.drawable.GradientDrawable());
                android.graphics.drawable.GradientDrawable gd =
                        (android.graphics.drawable.GradientDrawable) cell.getBackground();
                gd.setColor(sw);
                gd.setStroke(1, 0xFF3A3A3A);
                cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        cput(key, sw);
                        prev.setTextColor(sw);
                        hexBox.setText(hex(sw));
                        rgb[0] = (sw >> 16) & 0xFF;
                        rgb[1] = (sw >> 8) & 0xFF;
                        rgb[2] = sw & 0xFF;
                        for (int q = 0; q < 3; q++) {
                            bars[q].setProgress(rgb[q]);
                            nums[q].setText(String.valueOf(rgb[q]));
                        }
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                lp.rightMargin = dp(4);
                row.addView(cell, lp);
            }
            box.addView(row);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(10), 0, 0);

        TextView cancel = mkBtn("CANCEL");
        cancel.setTextColor(0xFFCCCCCC);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView apply = mkBtn("APPLY");
        apply.setTextColor(0xFF000000);
        apply.setBackgroundColor(0xFFC0C0C0);
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // take the hex box as the source of truth, so a typed value
                // is honored even if the sliders were never touched
                String t = hexBox.getText().toString().trim();
                if (t.startsWith("#")) t = t.substring(1);
                if (t.length() == 6) {
                    try {
                        cput(key, 0xFF000000 | Integer.parseInt(t, 16));
                    } catch (NumberFormatException e) {
                        cput(key, 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
                    }
                } else {
                    cput(key, 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
                }
                render();
                dlg.dismiss();
            }
        });
        btns.addView(apply, new LinearLayout.LayoutParams(0, dp(44), 1f));
        box.addView(btns);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        dlg.setContentView(sc);
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface di) {
                dlg.getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
            }
        });
        dlg.show();

        // seed the preview with the current value
        prev.setTextColor(cur);
    }

    private void paintPadTarget(TextView which, String[] names, EditText[] targets, int sel) {
        which.setText("typing into: " + names[sel]);
        which.setTextColor(0xFFCCCCCC);
        for (int i = 0; i < targets.length; i++) {
            targets[i].setBackgroundColor(i == sel ? 0xFF243040 : 0xFF151515);
        }
    }

    /**
     * Digit pad for the port and the pairing code. Both are pure numbers and
     * summoning the headset soft keyboard to type five digits is miserable, so
     * these get real buttons. The IP field deliberately keeps the normal
     * keyboard because it is not numeric.
     */
    private LinearLayout numPad(final String[] names, final EditText[] targets,
                                final int[] maxLen) {
        final int[] sel = { 0 };
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        final TextView which = new TextView(this);
        which.setTextSize(11);
        which.setGravity(Gravity.CENTER);
        which.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(which);

        final String[] KEYS = { "1", "2", "3", "4", "5", "6",
                                 "7", "8", "9", "DEL", "0", "CLR" };
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String k = KEYS[r * 3 + c];
                TextView b = new TextView(this);
                b.setText(k);
                b.setTextSize(16);
                b.setTextColor(0xFFEDEDF2);
                b.setGravity(Gravity.CENTER);
                b.setBackgroundColor(0xFF2A2A2A);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        EditText t = targets[sel[0]];
                        String cur = t.getText().toString();
                        if (k.equals("DEL")) {
                            if (cur.length() > 0) {
                                t.setText(cur.substring(0, cur.length() - 1));
                            }
                        } else if (k.equals("CLR")) {
                            t.setText("");
                        } else if (cur.length() < maxLen[sel[0]]) {
                            t.setText(cur + k);
                        }
                        // keep the caret at the end, or a stale cursor position
                        // makes the next digit land in the middle
                        t.setSelection(t.getText().length());
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                lp.rightMargin = dp(4);
                row.addView(b, lp);
            }
            wrap.addView(row);
        }

        // tapping a field aims the pad at it instead of opening a keyboard
        for (int i = 0; i < targets.length; i++) {
            final int k = i;
            targets[i].setFocusable(false);
            targets[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sel[0] = k;
                    paintPadTarget(which, names, targets, k);
                }
            });
        }
        paintPadTarget(which, names, targets, 0);
        return wrap;
    }

    private static final String[][] COLOR_KEYS = {
        { "bg",     K_BG,     "background" },
        { "ps",     K_PS,     "powershell background" },
        { "fg",     K_FG,     "text" },
        { "prompt", K_PROMPT, "prompt" },
        { "dim",    K_DIM,    "dim text / hints" },
    };

    private static final int[] COLOR_DEFS = {
        DEF_BG, DEF_PS, DEF_FG, DEF_PROMPT, DEF_DIM,
    };

    /** colors from the prompt, because a headset panel is a poor place to type hex */
    private void doColor(String arg, Session s) {
        String a = (arg == null) ? "" : arg.trim();
        if (a.isEmpty()) {
            append("colors\n");
            for (int i = 0; i < COLOR_KEYS.length; i++) {
                StringBuilder sb = new StringBuilder("  " + COLOR_KEYS[i][0]);
                while (sb.length() < 10) sb.append('.');
                append(sb.append(' ').append(hex(cget(COLOR_KEYS[i][1], COLOR_DEFS[i])))
                        .append("   ").append(COLOR_KEYS[i][2]).append('\n').toString());
            }
            append("  theme.... " + THEME_NAMES[themeIndex()] + "\n");
            append("\nset one with:   color <name> <RRGGBB>\n"
                    + "for example:     color ps 0A3A5C\n"
                    + "or open the picker:  prefs\n");
            return;
        }
        String[] p = a.split("\\s+", 2);
        for (int i = 0; i < COLOR_KEYS.length; i++) {
            if (!COLOR_KEYS[i][0].equalsIgnoreCase(p[0])) continue;
            if (p.length < 2) {
                append(COLOR_KEYS[i][0] + " = "
                        + hex(cget(COLOR_KEYS[i][1], COLOR_DEFS[i])) + "\n");
                return;
            }
            String t = p[1].trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() != 6) {
                append("color '" + p[1].trim() + "' is not six hex digits,"
                        + " like 0A3A5C\n");
                return;
            }
            try {
                cput(COLOR_KEYS[i][1], 0xFF000000 | Integer.parseInt(t, 16));
                append(COLOR_KEYS[i][2] + " = " + hex(cget(COLOR_KEYS[i][1], COLOR_DEFS[i]))
                        + "\n");
            } catch (NumberFormatException e) {
                append("color '" + p[1].trim() + "' is not valid hex\n");
            }
            return;
        }
        if (p[0].equalsIgnoreCase("theme")) {
            String[] names = THEME_NAMES;
            String t = (p.length < 2) ? "" : p[1].trim();
            int at = 0;
            for (int k = 0; k < names.length; k++) if (names[k].equalsIgnoreCase(t)) at = k;
            if (t.length() > 0 && at == 0 && !names[0].equalsIgnoreCase(t)) {
                StringBuilder sb = new StringBuilder("themes:");
                for (String n : names) sb.append(' ').append(n);
                append(sb.toString() + "\n");
                return;
            }
            cputTheme(at);
            append("theme = " + names[at] + "\n");
            return;
        }
        append("unknown color '" + p[0] + "'. 'color' lists them all.\n");
    }

    /** a non-tappable divider so the panel reads as sections, not one long list */
    private TextView header(String label) {
        TextView h = new TextView(this);
        h.setText(label);
        h.setTextSize(10);
        h.setPadding(dp(4), dp(14), 0, dp(4));
        h.setTextColor(cget(K_DIM, DEF_DIM));
        return h;
    }

    /**
     * One tap: re-ask for Shizuku/ByteZuku access, and report what came back.
     * Safe to press when access is already fine, it just re-checks.
     */
    private void requestShellAccess() {
        append("asking Shizuku/ByteZuku for access again...\n");
        status.setText("shizuku: requesting access");
        Shell.requestAgain(this, makeCallback());
    }

    private void showPrefsDialog() {
        applyPrefs();
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText("Preferences");
        head.setTextColor(0xFFCCCCCC);
        head.setTextSize(17);
        box.addView(head);

        TextView hint = new TextView(this);
        hint.setText("tap a row to cycle it");
        hint.setTextColor(0xFF7A7A7A);
        hint.setTextSize(10);
        box.addView(hint);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView[] rows = new TextView[PREFS.length];
        list.addView(header("GENERAL"));
        for (int i = 0; i < PREFS.length; i++) {
            final Pref p = PREFS[i];
            final TextView row = new TextView(this);
            row.setTextSize(12);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setBackgroundColor(0xFF151515);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // advance to the next word in this row's list
                    String cur = pget(p.key, p.opts[0]);
                    int at = 0;
                    for (int k = 0; k < p.opts.length; k++) if (p.opts[k].equals(cur)) at = k;
                    pput(p.key, p.opts[(at + 1) % p.opts.length]);
                    paintPrefs(rows);
                }
            });
            rows[i] = row;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(1);
            list.addView(row, lp);
        }
        paintPrefs(rows);
        // ---- colors -------------------------------------------------------
        // not part of PREFS: the values are free-form hex, so they need a
        // swatch and a picker rather than a cycle through a fixed list
        list.addView(header("COLORS"));
        // declared before the rows that reference it from their click handler
        final Runnable[] repaintColorsRef = new Runnable[1];

        TextView themeRow = new TextView(this);
        themeRow.setTextSize(12);
        themeRow.setPadding(0, dp(10), 0, dp(10));
        themeRow.setBackgroundColor(0xFF151515);
        themeRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cputTheme(themeIndex() + 1); repaintColorsRef[0].run(); }
        });
        list.addView(themeRow, rowLp());
        final TextView[] themeRowRef = { themeRow };

        final String[][] COLOR_ROWS = {
            { K_BG,     "background",     "0" },
            { K_PS,     "powershell bg",  "1" },
            { K_FG,     "text",           "2" },
            { K_PROMPT, "prompt",         "3" },
            { K_DIM,    "dim / hints",    "4" },
        };
        final int[] colorDefaults = { DEF_BG, DEF_PS, DEF_FG, DEF_PROMPT, DEF_DIM };
        final TextView[] crows = new TextView[COLOR_ROWS.length];
        for (int i = 0; i < COLOR_ROWS.length; i++) {
            final int idx = i;
            final String key = COLOR_ROWS[i][0];
            final String name = COLOR_ROWS[i][1];
            final int def = colorDefaults[i];
            final TextView row = new TextView(this);
            row.setTextSize(12);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setBackgroundColor(0xFF151515);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showColorPicker(key, name, cget(key, def));
                    crows[idx].post(repaintColorsRef[0]);
                }
            });
            crows[i] = row;
            list.addView(row, rowLp());
        }
        // the swatch square at the start of each color row
        for (int i = 0; i < crows.length; i++) {
            crows[i].setCompoundDrawables(swatch(cget(COLOR_ROWS[i][0], colorDefaults[i])),
                    null, null, null);
        }

        final Runnable repaintColors = new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder("theme");
                while (sb.length() < 26) sb.append('.');
                themeRowRef[0].setText(sb.append(' ').append(THEME_NAMES[themeIndex()]).toString());
                for (int i = 0; i < crows.length; i++) {
                    int c = cget(COLOR_ROWS[i][0], colorDefaults[i]);
                    StringBuilder s2 = new StringBuilder(COLOR_ROWS[i][1]);
                    while (s2.length() < 26) s2.append('.');
                    crows[i].setText(s2.append(' ').append(hex(c)).toString());
                    crows[i].setCompoundDrawables(swatch(c), null, null, null);
                }
            }
        };
        repaintColorsRef[0] = repaintColors;
        repaintColors.run();

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, 0);

        TextView reset = new TextView(this);
        reset.setText("RESET");
        reset.setTextColor(0xFFEDEDF2);
        reset.setTextSize(13);
        reset.setGravity(Gravity.CENTER);
        reset.setBackgroundColor(0xFF2A2A2A);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.SharedPreferences.Editor e = adbPrefs().edit();
                for (Pref p : PREFS) e.remove(p.key);
                // colors live outside PREFS because their values are free-form
                e.remove(K_BG).remove(K_PS).remove(K_FG)
                        .remove(K_PROMPT).remove(K_DIM);
                e.apply();
                applyPrefs();
                paintPrefs(rows);
                if (repaintColorsRef[0] != null) repaintColorsRef[0].run();
            }
        });
        btns.addView(reset, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView close = new TextView(this);
        close.setText("CLOSE");
        close.setTextColor(0xFF000000);
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0xFFC0C0C0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1f));
        box.addView(btns);

        dlg.setContentView(box);
        dlg.show();
    }

    private void paintPrefs(TextView[] rows) {
        for (int i = 0; i < PREFS.length && i < rows.length; i++) {
            Pref p = PREFS[i];
            String v = pget(p.key, p.opts[0]);
            StringBuilder sb = new StringBuilder(p.label);
            while (sb.length() < 26) sb.append('.');
            rows[i].setText(sb.append(' ').append(v).toString());
            rows[i].setTextColor(v.equals("on") ? 0xFF7BE07B : 0xFFCCCCCC);
        }
    }

    // ------------------------------------------------------------------ ui

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        this.root = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor());

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFF0C0C0C);
        root.addView(tabBar);

        scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        output.setTextColor(0xFFCCCCCC);
        output.setPadding(dp(8), dp(6), dp(8), dp(6));
        output.setTextIsSelectable(true);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF0C0C0C);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        input = new EditText(this);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF666670);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent k) {
                submit();
                return true;
            }
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView go = new TextView(this);
        go.setText("GO");
        go.setTextColor(0xFF0B0B0F);
        go.setTextSize(13);
        go.setGravity(Gravity.CENTER);
        go.setBackgroundColor(0xFFC0C0C0);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submit(); }
        });
        bar.addView(go, new LinearLayout.LayoutParams(dp(64), dp(48)));

        TextView infoBtn = new TextView(this);
        infoBtn.setText("INFO");
        infoBtn.setTextColor(0xFFEDEDF2);
        infoBtn.setTextSize(11);
        infoBtn.setGravity(Gravity.CENTER);
        infoBtn.setBackgroundColor(0xFF2A2A2A);
        infoBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showInfoDialog(); }
        });
        bar.addView(infoBtn, new LinearLayout.LayoutParams(dp(64), dp(48)));

        TextView setBtn = new TextView(this);
        setBtn.setText("SET");
        setBtn.setTextColor(0xFFEDEDF2);
        setBtn.setTextSize(11);
        setBtn.setGravity(Gravity.CENTER);
        setBtn.setBackgroundColor(0xFF2A2A2A);
        setBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettingsDialog(); }
        });
        bar.addView(setBtn, new LinearLayout.LayoutParams(dp(56), dp(48)));

        TextView devBtn = new TextView(this);
        devBtn.setText("DEV");
        devBtn.setTextColor(0xFFEDEDF2);
        devBtn.setTextSize(11);
        devBtn.setGravity(Gravity.CENTER);
        devBtn.setBackgroundColor(0xFF2A2A2A);
        devBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDevSettings(); }
        });
        bar.addView(devBtn, new LinearLayout.LayoutParams(dp(56), dp(48)));

        TextView sysBtn = new TextView(this);
        sysBtn.setText("ABOUT");
        sysBtn.setTextColor(0xFFEDEDF2);
        sysBtn.setTextSize(11);
        sysBtn.setGravity(Gravity.CENTER);
        sysBtn.setBackgroundColor(0xFF2A2A2A);
        sysBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // verified: this is where the serial number lives
                openAndroidSettings("com.android.settings",
                        "com.android.settings.Settings$MyDeviceInfoActivity");
            }
        });
        bar.addView(sysBtn, new LinearLayout.LayoutParams(dp(56), dp(48)));

        TextView adbBtn = new TextView(this);
        adbBtn.setText("ADB");
        adbBtn.setTextColor(0xFFEDEDF2);
        adbBtn.setTextSize(11);
        adbBtn.setGravity(Gravity.CENTER);
        adbBtn.setBackgroundColor(0xFF2A2A2A);
        adbBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAdbDialog(); }
        });
        bar.addView(adbBtn, new LinearLayout.LayoutParams(dp(56), dp(48)));

        // permissions as a button, because the grant can be revoked by accident
        // and hunting for a command on a headset keyboard is the wrong way to
        // find out whether the shell is actually there
        TextView accBtn = new TextView(this);
        accBtn.setText("ACCESS");
        accBtn.setTextColor(0xFFEDEDF2);
        accBtn.setTextSize(11);
        accBtn.setGravity(Gravity.CENTER);
        accBtn.setBackgroundColor(0xFF2A2A2A);
        accBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestShellAccess(); }
        });
        bar.addView(accBtn, new LinearLayout.LayoutParams(dp(64), dp(48)));

        // preferences as a real button. Chasing fonts, colors and scrollback
        // should not require typing a command into a headset keyboard.
        TextView prefBtn = new TextView(this);
        prefBtn.setText("PREF");
        prefBtn.setTextColor(0xFFEDEDF2);
        prefBtn.setTextSize(11);
        prefBtn.setGravity(Gravity.CENTER);
        prefBtn.setBackgroundColor(0xFF2A2A2A);
        prefBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPrefsDialog(); }
        });
        bar.addView(prefBtn, new LinearLayout.LayoutParams(dp(56), dp(48)));

        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(0xFF808080);
        status.setTextSize(10);
        status.setPadding(dp(8), dp(2), dp(8), dp(2));
        root.addView(status);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(final String s) {
        UI.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivityHolder.ctx, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Dialogs need an activity context; the activity is not `this` in statics. */
    private static final class MainActivityHolder {
        static android.content.Context ctx;
    }

    /** true when the caller is not on the main looper */
    private static boolean offUi() {
        return android.os.Looper.myLooper() != android.os.Looper.getMainLooper();
    }

    private void append(String s) {
        // Every view lives on the main looper. Shell callbacks are already
        // posted there, but a builtin invoked from a worker thread would
        // otherwise take the whole app down with a view-off-thread crash, so
        // hop instead of exploding.
        if (offUi()) {
            final String t = s;
            UI.post(new Runnable() {
                public void run() { append(t); }
            });
            return;
        }
        if (sessions.isEmpty()) newSession();
        Session ss = sessions.get(current);
        ss.buf.append(s);
        int cap = pintk("scrollback", 100_000);
        if (cap < 4_000) cap = 4_000;
        if (ss.buf.length() > cap) {
            ss.buf.delete(0, ss.buf.length() - (cap - cap / 5));
        }
        render();
    }

    private void render() {
        if (offUi()) {
            UI.post(new Runnable() {
                public void run() { render(); }
            });
            return;
        }
        if (sessions.isEmpty()) { output.setText(""); drawTabs(); return; }
        Session ss = sessions.get(current);
        // pass the spannable itself, not toString(), or the prompt colors are
        // thrown away on every repaint
        if (ss == null) output.setText("");
        else output.setText(ss.buf, TextView.BufferType.SPANNABLE);
        applyBg();                 // the bg follows the tab, not the window
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); }
        });
        drawTabs();
    }

    private void drawTabs() {
        tabBar.removeAllViews();
        for (int i = 0; i < sessions.size(); i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(" " + sessions.get(i).title + " ");
            t.setTextSize(12);
            t.setTextColor(i == current ? 0xFF000000 : 0xFF9A9A9A);
            t.setBackgroundColor(i == current ? 0xFFC0C0C0 : 0xFF1A1A1A);
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { current = idx; render(); }
            });
            tabBar.addView(t);
        }
        TextView plus = new TextView(this);
        plus.setText(" + ");
        plus.setTextSize(14);
        plus.setTextColor(0xFFCCCCCC);
        plus.setPadding(dp(10), dp(8), dp(10), dp(8));
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newSession(); }
        });
        tabBar.addView(plus);
    }

    private void newSession() {
        Session s = new Session();
        s.title = "sh" + (sessions.size() + 1);
        s.cwd = pget("startDir", "/");
        s.buf.append(headset() + " - uid 2000 shell via Shizuku/ByteZuku\n"
                   + "type 'help' for the full command list\n\n");
        sessions.add(s);
        current = sessions.size() - 1;
        render();
    }

    /**
     * Verbs that stay local even while a PowerShell session is live.
     *
     * Without this list, anything typed in a PS session gets handed to
     * PowerShell, which is wrong for most of these: 'prefs' and 'info' are UI
     * and shell-inspection things PowerShell knows nothing about, and 'pkg'
     * generates a busybox sh script that PowerShell would try to parse as its
     * own. cd is here too, because doCd moves PowerShell's location itself and
     * then reads it back, so the prompt cannot drift out of sync.
     */
    private static boolean localInPs(String verb) {
        return verb.equals("clear") || verb.equals("cls") || verb.equals("exit")
            || verb.equals("tabnew") || verb.equals("tabclose") || verb.equals("help")
            || verb.equals("prompt") || verb.equals("pwshstart") || verb.equals("pwshstop")
            || verb.equals("cd") || verb.equals("chdir")
            || verb.equals("cdroot") || verb.equals("cdshell")
            || verb.equals("cdsdcard") || verb.equals("cddownload")
            || verb.equals("cdprefix") || verb.equals("cdpkg")
            || verb.equals("prefs") || verb.equals("pref") || verb.equals("colors")
            || verb.equals("color") || verb.equals("cheatsheet") || verb.equals("dev")
            || verb.equals("checkshizuku") || verb.equals("adbd")
            || verb.equals("info") || verb.equals("rootcheck") || verb.equals("commands")
            || verb.equals("pkg") || verb.equals("run") || verb.equals("su")
            || verb.equals("pwshfile") || verb.equals("pwshdemo");
    }

    /**
     * The adb cheat sheet, translated for a terminal that is already on the
     * device. Everything host-side (adb install, adb push, fastboot, the bash
     * xargs tricks) is dropped, because you would run that on the PC, not here.
     * What is left is the half that is actually useful from in here.
     */
    private void doCheatsheet(Session s) {
        append(
          "adb cheat sheet, on-device edition\n"
        + "=================================\n\n"

        + "-- input ------------------------------------------------------\n"
        + "  home / back               keyevent 3 / 4\n"
        + "  key <code|name>           any keycode, comma separated is fine\n"
        + "                            3 home   4 back    26 power  27 camera\n"
        + "                            66 enter 67 del     82 menu  187 recents\n"
        + "  text <words>              types into whatever has focus\n"
        + "  tap <x> <y>               input tap\n"
        + "  swipe <x1> <y1> <x2> <y2> [ms]\n"
        + "  focus                     which window is up right now\n\n"

        + "-- screen -----------------------------------------------------\n"
        + "  screengrab [file]         meta_screencap to a png\n"
        + "                            WARNING: comes out all black here, the\n"
        + "                            compositor blocks the capture. screencap\n"
        + "                            is black too. Use your eyes.\n"
        + "  record <file> [secs]      screenrecord, defaults to 10s\n\n"

        + "-- display ----------------------------------------------------\n"
        + "  wmsize [WxH|reset]        e.g. wmsize 2064x2208, wmsize reset\n"
        + "  density [n|reset]         e.g. density 480, density reset\n\n"

        + "-- power -------------------------------------------------------\n"
        + "  battery                   dumpsys battery\n"
        + "  battery level <0-100>     fake the reading\n"
        + "  battery status <1-5>      1 unknown 2 charging 3 discharging\n"
        + "                            4 not charging 5 full\n"
        + "  battery reset             back to the real thing\n\n"

        + "-- apps -------------------------------------------------------\n"
        + "  packages [filter]         pm list packages, optional grep\n"
        + "  launch <pkg[/activity]>   am start, -n added if you give a slash\n"
        + "  stopapp <pkg>             am force-stop\n"
        + "  clearapp <pkg>            pm clear, wipes data, stays installed\n"
        + "  installapk <path>         pm install -r\n"
        + "  uninstallapk <pkg>        pm uninstall\n"
        + "  apkpath <pkg>             where its apk lives\n"
        + "  monkey [args]             e.g. monkey -p com.x -v 1000 -s 1\n"
        + "  grantapp <pkg> <perm>     pm grant\n"
        + "  revokeapp <pkg> <perm>    pm revoke\n"
        + "  resetperms <pkg>          pm reset-permissions -p\n\n"

        + "-- intents ----------------------------------------------------\n"
        + "  openurl <url>             am start VIEW\n"
        + "  tel <number>              am start CALL\n"
        + "  sms <number> <body>       am start SENDTO\n\n"

        + "-- diagnostics ------------------------------------------------\n"
        + "  info                      model, codename, uid, selinux, root\n"
        + "  checkshizuku              did shizuku say yes or no\n"
        + "  commands [filter]         every command on PATH\n"
        + "  features                  pm list features\n"
        + "  servicelist               service list\n"
        + "  netstat                   netstat -a\n\n"

        + "-- anything the adb list has that you do not see here is just a\n"
        + "   normal command. This IS a shell, so 'pm', 'am', 'dumpsys',\n"
        + "   'input', 'wm', 'logcat', 'monkey' all work as typed.\n"
        + "   'cheatsheet' again brings this back.\n");
    }

    // ------------------------------------------------- device control shortcuts

    /**
     * These are the adb-cheat-sheet commands, minus the 'adb shell' prefix,
     * because this terminal is already running on the device. Same tools, one
     * keystroke shorter.
     */
    private void dev(Session s, String shown, String cmd) {
        if (sh == null) { append("no shell yet - need shizuku access (checkshizuku)\n"); return; }
        appendPromptThen(shown + "\n");
        s.busy = true;
        sh.exec(cmd, s.cwd);
    }

    private static String echo(String verb, String arg) {
        return (arg == null || arg.isEmpty()) ? verb : verb + " " + arg;
    }

    private static final String FOCUS_CMD =
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'";

    private void doKey(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) {
            append("key <code|name>   e.g. key 3, key KEYCODE_HOME, key 26\n"
                    + "  handy: 3 home  4 back  26 power  66 enter  67 del\n"
                    + "         82 menu  187 apps  224 wake\n");
            return;
        }
        // accept a comma or space separated list, it is the same input tool
        StringBuilder c = new StringBuilder("input keyevent");
        for (String k : arg.trim().split("[,\\s]+")) {
            if (!k.isEmpty()) c.append(' ').append(k);
        }
        dev(s, "key " + arg, c.toString());
    }

    private void doInputText(String arg, Session s) {
        if (arg == null || arg.isEmpty()) { append("text <words>   types into the focused field\n"); return; }
        // 'input text' wants %s for a space, not a literal one
        dev(s, echo("text", arg), "input text '" + arg.replace(" ", "%s") + "'");
    }

    private void doScreengrab(String arg, Session s) {
        if (sh == null) { append("no shell yet - need shizuku access (checkshizuku)\n"); return; }
        String f = (arg == null || arg.trim().isEmpty())
                ? "/storage/emulated/0/screen.png" : arg.trim();
        dev(s, echo("screengrab", arg),
                "meta_screencap -p '" + f + "' 2>&1; ls -l '" + f
                + "' 2>/dev/null; echo 'note: on this headset the capture comes out"
                + " all black - the compositor blocks it, use your eyes'");
    }

    private void doRecord(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) {
            append("record <file> [seconds]\n  screenrecord needs a stop, so pass a"
                    + " time limit:\n  record /storage/emulated/0/demo.mp4 10\n");
            return;
        }
        String[] p = arg.trim().split("\\s+");
        String f = p[0];
        String t = (p.length > 1) ? ("--time-limit " + p[1]) : "--time-limit 10";
        dev(s, echo("record", arg),
                "screenrecord --verbose " + t + " '" + f + "' 2>&1; ls -l '" + f + "' 2>/dev/null");
    }

    private void doWm(String arg, Session s, String what) {
        String a = (arg == null) ? "" : arg.trim();
        if (a.isEmpty()) { dev(s, "wmsize", "wm " + what); return; }
        dev(s, echo(what.equals("size") ? "wmsize" : "density", a),
                "wm " + what + " " + a);
    }

    private void doBattery(String arg, Session s) {
        String a = (arg == null) ? "" : arg.trim();
        if (a.isEmpty()) { dev(s, "battery", "dumpsys battery"); return; }
        if (a.startsWith("level")) {
            String n = a.substring(5).trim();
            dev(s, echo("battery", a), "dumpsys battery set level "
                    + (n.isEmpty() ? "100" : n));
            return;
        }
        if (a.startsWith("status")) {
            String n = a.substring(6).trim();
            dev(s, echo("battery", a), "dumpsys battery set status "
                    + (n.isEmpty() ? "2" : n));
            return;
        }
        if (a.equals("reset")) { dev(s, "battery reset", "dumpsys battery reset"); return; }
        dev(s, "battery", "dumpsys battery");
    }

    private void doLaunch(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) {
            append("launch <pkg>            e.g. launch com.oculus.vrshell\n"
                    + "launch <pkg>/<activity>  full component\n"
                    + "launch -n <pkg>/<activity>\n");
            return;
        }
        String a = arg.trim();
        if (a.contains("/") && !a.startsWith("-n")) a = "-n " + a;
        dev(s, echo("launch", arg), "am start " + a);
    }

    private void doClearApp(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) { append("clearapp <pkg>   wipes app data, keeps it installed\n"); return; }
        dev(s, echo("clearapp", arg), "pm clear " + arg.trim());
    }

    private void doInstallApk(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) { append("installapk <path-on-device>\n"); return; }
        dev(s, echo("installapk", arg), "pm install -r " + arg.trim());
    }

    private void doMonkey(String arg, Session s) {
        String a = (arg == null || arg.trim().isEmpty())
                ? "-v 1000 -s 1" : arg.trim();
        dev(s, echo("monkey", arg), "monkey " + a);
    }

    private void doPackages(String arg, Session s) {
        String f = (arg == null) ? "" : arg.trim();
        dev(s, echo("packages", arg), f.isEmpty() ? "pm list packages"
                : "pm list packages | grep -i " + q(f));
    }

    private void doPerm(String arg, Session s, String what) {
        if (arg == null || arg.trim().split("\\s+").length < 2) {
            append(what + "app <pkg> <android.permission.X>\n");
            return;
        }
        dev(s, echo(what + "app", arg), "pm " + what + " " + arg.trim());
    }

    private void doResetPerms(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) { append("resetperms <pkg>\n"); return; }
        dev(s, echo("resetperms", arg), "pm reset-permissions -p " + arg.trim());
    }

    private void doSms(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) { append("sms <number> <body>\n"); return; }
        int sp = arg.indexOf(' ');
        if (sp < 0) { append("sms <number> <body>\n"); return; }
        String num = arg.substring(0, sp).trim();
        String body = arg.substring(sp + 1).trim();
        dev(s, echo("sms", arg),
                "am start -a android.intent.action.SENDTO -d sms:" + num
                + " --es sms_body " + q(body) + " --ez exit_on_sent false");
    }

    // ------------------------------------------------------- command browser

    private static final String CMD_SEP = "__BBSEP__";

    /**
     * List everything the headset's shell can actually run. Walks $PATH the way
     * the shell does, so the answer matches what you can just type, rather than
     * a hardcoded list that would rot the first time the ROM changed.
     *
     * `commands` prints the lot, `commands grep` filters it.
     */
    private void doCommands(final String arg, final Session s) {
        if (sh == null) { append("no shell yet - need shizuku access (checkshizuku)\n"); return; }
        final String filter = arg == null ? "" : arg.trim();
        final boolean caseSensitive = filter.startsWith("~");
        final String needle = caseSensitive ? filter.substring(1) : filter;

        s.busy = true;
        append("listing commands" + (needle.isEmpty() ? "..." : " matching '" + needle + "'...") + "\n");
        // $PATH is split on ':' by setting IFS, so the walk below sees exactly
        // the same directory list the shell itself would.
        sh.execRaw("IFS=:; echo \"$PATH\"; echo " + CMD_SEP
                + "; for d in $PATH; do [ -d \"$d\" ] && ls -1 \"$d\" 2>/dev/null; done "
                + "| sort -u", new Shell.Raw() {
            public void got(String out) {
                s.busy = false;
                renderCommands(out, needle, caseSensitive);
                render();
            }
        });
    }

    private void renderCommands(String out, String needle, boolean caseSensitive) {
        if (out == null || out.isEmpty()) { append("no output from the shell\n"); return; }
        int sep = out.indexOf(CMD_SEP);
        if (sep < 0) { append("could not read $PATH back from the shell\n"); return; }

        String path = out.substring(0, sep).trim();
        String[] dirs = path.isEmpty() ? new String[0] : path.split(":");

        ArrayList<String> all = new ArrayList<String>();
        for (String line : out.substring(sep + CMD_SEP.length()).split("\n")) {
            String n = line.trim();
            if (n.isEmpty() || n.indexOf('/') >= 0) continue;   // skip stray paths
            if (!all.contains(n)) all.add(n);
        }
        java.util.Collections.sort(all);

        ArrayList<String> hit = new ArrayList<String>();
        for (String n : all) {
            if (needle.isEmpty()
                    || (caseSensitive ? n.contains(needle)
                                      : n.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US)))) {
                hit.add(n);
            }
        }

        StringBuilder head = new StringBuilder();
        head.append("PATH (").append(dirs.length).append(" dirs)\n");
        for (String d : dirs) head.append("  ").append(d).append('\n');
        head.append(all.size()).append(" commands available");
        if (!needle.isEmpty()) {
            head.append(", ").append(hit.size()).append(" matching '")
                .append(needle).append('\'');
        }
        head.append("\n\n");
        append(head.toString());

        if (hit.isEmpty()) {
            append("nothing matches. 'commands' with no argument lists all of them.\n");
            return;
        }

        // column layout, width driven by the longest name so nothing is cut up
        int widest = 0;
        for (String n : hit) widest = Math.max(widest, n.length());
        int colW = Math.min(widest + 2, 16);
        int perRow = Math.max(1, 60 / colW);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < hit.size(); i++) {
            String n = hit.get(i);
            line.append(n);
            if (n.length() < colW) {
                for (int p = n.length(); p < colW; p++) line.append(' ');
            }
            if ((i + 1) % perRow == 0 || i == hit.size() - 1) {
                append(trimRight(line.toString()) + "\n");
                line.setLength(0);
            }
        }
        if (!needle.isEmpty()) {
            append("\nto run one of these, just type it - e.g. " + hit.get(0) + "\n");
        }
    }

    private static String trimRight(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') e--;
        return s.substring(0, e);
    }

    // ------------------------------------------------------- shizuku verdict

    /**
     * The one command that answers "did shizuku say yes or no" plainly. It
     * separates the three states people conflate -- manager not running, access
     * denied, and access granted but the UserService not bound -- because the
     * fix is different for each. Also notes the flapping, since a denied read
     * right after a launch is often just a stale sample.
     */
    private void doCheckShizuku(final Session s) {
        append("shizuku check\n" + Shell.status());

        boolean alive = Shell.describeState().startsWith("server up");
        boolean granted = Shell.describeState().contains("GRANTED");
        boolean bound = (sh != null && sh.isReady());

        if (!alive) {
            append("  verdict       NO - the manager is not running\n"
                    + "                it is a separate process, start ByteZuku/Shizuku\n"
                    + "                and make sure it says 'running' here.\n");
            return;
        }
        if (!granted) {
            append("  verdict       NO - the manager has not granted this app\n"
                    + "                asking again now. The pop-up prompt\n"
                    + "                auto-dismisses, so the reliable place is the\n"
                    + "                manager's app list: turn Terminal on there.\n"
                    + "                i keep re-asking every 30s and watching, so\n"
                    + "                granting it will bind on its own.\n");
            if (shCb != null) Shell.requestAgain(this, shCb);
            return;
        }
        if (!bound) {
            append("  verdict       YES, but the shell is not bound yet\n"
                    + "                re-checking now. If this does not turn into a\n"
                    + "                proof line, the Shizuku server is probably\n"
                    + "                holding a binder for a dead :bbterm service;\n"
                    + "                'pkill -f shizuku_server' or a reboot clears it.\n");
            if (shCb != null) Shell.requestAgain(this, shCb);
            return;
        }

        append("  verdict       YES - a uid 2000 shell is live\n");
        s.busy = true;
        sh.execRaw("id -u; whoami; echo $PSVersionTable.PSVersion", new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                String t = (o == null || o.length() == 0) ? "(no reply)" : o.trim();
                if (t.startsWith("0") || t.contains("root")) s.uid = 0;
                append("  proof         " + t.replace("\n", "  ") + "\n");
                render();
            }
        });
    }

    // ------------------------------------------------------------ powershell


    /**
     * Bring up a real persistent PowerShell session: one pwsh process on the
     * far side that keeps its variables, modules and working directory between
     * lines. Switches the console to the PowerShell blue and prints the banner
     * people expect, then the prompt becomes a PS one.
     */
    private void doPwshStart(final Session s) {
        if (sh == null) { append("no shell transport\n"); return; }
        if (s.ps) { append("PowerShell is already running - 'pwshstop' first\n"); return; }
        append("starting PowerShell...\n");
        s.busy = true;
        sh.psStart(new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                if (o == null || o.startsWith("pwsh start failed")) {
                    append((o == null ? "pwsh start failed" : o) + "\n");
                    render();
                    return;
                }
                s.ps = true;
                root.setBackgroundColor(bgColor());
                s.title = "pwsh";
                s.buf.replace(0, s.buf.length(), "");
                if (pon("psBanner", true)) {
                    s.buf.append("Windows PowerShell\n"
                            + "Copyright (C) Microsoft Corporation. All rights reserved.\n"
                            + "\n");
                }
                render();
                appendPrompt();
            }
        });
    }

    private void doPwshStop(final Session s) {
        if (!s.ps) { append("PowerShell is not running\n"); return; }
        // throw away any half-typed block, it means nothing without its session
        s.psPending.setLength(0);
        sh.psStop(new Shell.Raw() {
            public void got(String o) {
                s.ps = false;
                s.title = "sh";
                root.setBackgroundColor(bgColor());
                append("\nPowerShell session ended.\n");
                render();
            }
        });
    }

    private void doPwshLine(final Session s, final String cmd) {
        s.busy = true;
        sh.psLine(cmd, new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                if (o != null && o.length() > 0) append(o.endsWith("\n") ? o : o + "\n");
                appendPrompt();
            }
        });
    }

    // -------------------------------------------------------------- builtins

    private void submit() {
        String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) return;
        input.setText("");

        // The guard has to come before the get. sessions is only filled in
        // once the shell connects, so pressing GO early used to throw
        // IndexOutOfBounds on the main thread and take the app with it.
        if (current < 0 || current >= sessions.size()) current = 0;
        if (sessions.isEmpty()) newSession();
        if (current < 0 || current >= sessions.size()) return;
        Session ss = sessions.get(current);
        ss.history.add(cmd);
        ss.histPos = ss.history.size();

        String[] parts = cmd.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        // In a live PowerShell session, lines belong to PowerShell. Only the
        // local navigation verbs stay local, so clear/exit/tabs keep working.
        if (ss.ps && !localInPs(verb)) {
            appendPromptThen(cmd + "\n");
            // held if it is part of an unfinished block, sent when it is whole
            psFeed(ss, cmd);
            return;
        }

        if (builtin(verb, arg)) return;

        appendPromptThen(cmd + "\n");
        ss.busy = true;
        if (pon("verboseLog", false)) {
            android.util.Log.i("BBterm", "run uid=" + ss.uid + " cwd=" + ss.cwd
                    + " -> " + translate(verb, arg, cmd));
        }
        if (sh == null) {
            // No Shizuku/ByteZuku service. Fall back to the in-process adb
            // transport rather than refusing to run anything.
            runViaAdb(cmd, verb, arg, ss);
            return;
        }
        final String real = translate(verb, arg, cmd);
        ss.worker = new Thread(new Runnable() {
            public void run() {
                sh.exec(real, ss.cwd);
            }
        });
        ss.worker.start();
    }

    /**
     * Upstream PowerShell only ships linux-arm64, but Android is Bionic, so the
     * binary cannot be exec'd directly. /data/local/tmp/pwsh/pwsh.sh launches it
     * through Debian's aarch64 glibc loader, which makes PowerShell just another
     * command on whichever transport is live.
     */
    private static final String PWSH = ShellService.PWSH;

    /** Rewrite a typed line into what the transport should actually run. */
    private String translate(String verb, String arg, String cmd) {
        if (verb.equals("pwsh") || verb.equals("powershell")) {
            if (arg.length() == 0) {
                return scriptShell() + PWSH + " -NoLogo -NoProfile -Command " + q(
                    "$PSVersionTable.PSVersion.ToString() + ' ' + $PSVersionTable.PSEdition"
                  + "; $PSHOME; try { (Get-Location).Path } catch { 'no cwd' }");
            }
            return scriptShell() + PWSH + " -NoLogo -NoProfile -Command " + q(arg);
        }
        if (verb.equals("pwshfile") && arg.length() > 0) {
            // resolve it the same way 'run' does, or a bare name or a name
            // with a space in it fails here too
            String p = resolveScript(arg.trim(), sessions.get(current).cwd);
            if (p == null) return "pwshfile: no such file: " + arg.trim();
            return scriptShell() + PWSH + " -NoLogo -NoProfile -File " + q(p);
        }
        if (verb.equals("adbd")) {
            // What the device's adb daemon is doing, without needing a host
            // adb server: there is no adb server on the device, adbd is the
            // daemon and init owns its lifetime.
            return "echo \"pid:      $(pidof adbd)\""
                 + "; echo \"owner:    $(ps -A -o USER,NAME | grep -w adbd)\""
                 + "; echo \"tcp port: $(getprop persist.adb.tcp.port)\""
                 + "; echo \"secure:   $(getprop ro.adb.secure)\""
                 + "; echo \"state:    $(getprop init.svc.adbd)\""
                 + "; echo \"listening: $(cat /proc/net/tcp6 | grep -c 15B3)\"";
        }
        // busybox applets resolve through its own argv[0]
        if (!cmd.contains("/") && isBusyboxApplet(verb)) {
            return ShellService.BUSYBOX + " " + cmd;
        }
        return cmd;
    }

    /**
     * Run a command over the in-process adb transport. Uses the same reply
     * plumbing as the Shizuku path so the terminal behaves identically.
     */
    private void runViaAdb(final String cmd, final String verb, final String arg, final Session ss) {
        if (adbKey == null || adbHost == null) {
            append("no shell transport\n"
                 + "Shizuku/ByteZuku is unavailable and no adb link is open.\n"
                 + "Tap ADB, enter ip + port, press CONNECT.\n");
            ss.busy = false;
            return;
        }
        ss.worker = new Thread(new Runnable() {
            public void run() {
                String out;
                AdbLink link = new AdbLink(adbKey);
                try {
                    if (!link.connect(adbHost, adbPort)) {
                        out = "adb connect failed\n" + link.lastLog();
                    } else {
                        out = link.exec(translate(verb, arg, cmd), 15000);
                    }
                } catch (Throwable t) {
                    out = "adb error: " + t;
                } finally {
                    link.close();
                }
                final String fin = out;
                post(new Runnable() {
                    public void run() {
                        append(fin.isEmpty() ? "(no output)\n" : fin);
                        ss.busy = false;
                    }
                });
            }
        });
        ss.worker.start();
    }

    // ---- prompt ----------------------------------------------------------
    // cmd-style with a drive letter, led by the headset's own codename, so the
    // prompt is right on every headset instead of hardcoding one of them:
    //     C:\eureka\>       Quest 3
    //     C:\hollywood\>    Quest 2S
    //     C:\batlleeye\>    Quest 2
    // The glyph is $ for an ordinary uid 2000 shell and # once su granted root:
    //     C:\eureka\$>      C:\eureka\#>
    //     eureka $:\>       the same thing unix-flavoured
    // 'prompt' cycles the three styles at runtime.
    private static final String[] PROMPT_STYLES = { "cmd", "plain", "unix" };
    private int promptStyle = 0;

    /**
     * Device codename, read from the device rather than assumed. Quest 3 is
     * eureka, Quest 2S is hollywood, Quest 2 is batlleeye, so a hardcoded name
     * would be wrong on two out of three. Cached in prefs because the prompt
     * needs it synchronously and getprop is not.
     */
    private volatile String codename = "";

    /** the codename for display, never empty */
    private String host() {
        String c = codename;
        return (c == null || c.length() == 0) ? "quest" : c;
    }

    /**
     * Codename -> retail name. ro.product.model is unreliable on these builds
     * (it can read back a generic string), so the codename is the honest
     * identifier and this table turns it into something a human recognises.
     * An unknown codename is shown raw rather than guessed at.
     */
    private static final String[][] HEADSETS = {
        { "eureka",    "Quest 3"    },
        { "panther",   "Quest 3S"   },
        { "seacliff",  "Quest Pro"  },
        { "hollywood", "Quest 2S"   },
        { "batlleeye", "Quest 2"    },
        { "vrcausal",  "Quest 1"    },
        { "aurora",    "Oculus Go"  },
    };

    private String modelName() {
        String c = host();
        for (String[] h : HEADSETS) {
            if (h[0].equals(c)) return h[1];
        }
        return c;
    }

    /** e.g. "Quest 3 'eureka'" */
    private String headset() {
        String m = modelName();
        return m.equals(host()) ? m : (m + " '" + host() + "'");
    }

    /** ask the device what it is, once the transport is up */
    private void loadCodename(final Shell s) {
        codename = adbPrefs().getString("codename", "");
        s.execRaw("getprop ro.product.device; getprop ro.product.name; "
                + "getprop ro.build.product", new Shell.Raw() {
            public void got(String o) {
                if (o == null) return;
                for (String line : o.split("\\s+")) {
                    String t = line.trim();
                    // skip empties and the literal 'unknown'/'localhost' noise
                    if (t.length() < 3 || t.length() > 24) continue;
                    if (!t.matches("[A-Za-z0-9_.-]+")) continue;
                    if (t.equalsIgnoreCase("unknown")) continue;
                    codename = t.toLowerCase();
                    adbPrefs().edit().putString("codename", codename).apply();
                    android.util.Log.i("BBterm", "codename=" + codename
                            + " model=" + modelName() + " prompt=" + prompt());
                    render();
                    return;
                }
            }
        });
    }

    /** $ for an ordinary uid 2000 shell, # once su has actually granted root */
    private String glyph(Session s) {
        if (!pon("rootGlyph", true)) return "";
        return s.uid == 0 ? "#" : "$";
    }

    /**
     * Map a posix cwd onto a drive letter, the way cmd would show it:
     *   C: = the device itself, whose root is displayed as \<codename>\
     *   D: = /storage/emulated/0, the shared volume
     *   E: = the tool prefix this app owns
     * Anything not under D: or E: is a path on C: below \<codename>\.
     */
    private String winPath(String cwd) {
        String w = (cwd == null || cwd.isEmpty()) ? "/" : cwd;
        final String sd = "/storage/emulated/0";
        if (w.equals("/")) return "C:\\" + host() + "\\";
        if (w.equals(sd)) return "D:\\";
        if (w.startsWith(sd + "/")) {
            return "D:\\" + w.substring(sd.length() + 1).replace('/', '\\') + "\\";
        }
        if (w.equals(ShellService.PREFIX)) return "E:\\";
        if (w.startsWith(ShellService.PREFIX + "/")) {
            return "E:\\" + w.substring(ShellService.PREFIX.length() + 1)
                    .replace('/', '\\') + "\\";
        }
        while (w.startsWith("/")) w = w.substring(1);
        if (w.isEmpty()) return "C:\\" + host() + "\\";
        return "C:\\" + host() + "\\" + w.replace('/', '\\') + "\\";
    }

    private String prompt() {
        if (sessions.isEmpty()) newSession();
        Session ss = sessions.get(current);
        String path = winPath(ss.cwd);
        // PowerShell draws its own prompt, so match it rather than the cmd one
        if (ss.ps) {
            // '>>' while a block is unfinished, so it is obvious the terminal
            // is waiting for more rather than having lost the line
            return (ss.psPending.length() > 0 ? ">> " : "PS " + path + ">");
        }
        String g = glyph(ss);
        String style = PROMPT_STYLES[promptStyle % PROMPT_STYLES.length];
        String p;
        if (style.equals("plain")) {
            p = path + ">";
        } else if (style.equals("unix")) {
            String home = "C:\\" + host() + "\\";
            String tail = path.startsWith(home) ? path.substring(home.length()) : path;
            // NB: String.matches is a full-string match, so testing a bare
            // "^[A-Za-z]:" against "D:\Music\" would be false and we'd
            // glue a stray backslash on the front. Check the two chars.
            boolean hasDrive = tail.length() >= 2
                    && Character.isLetter(tail.charAt(0)) && tail.charAt(1) == ':';
            if (!hasDrive && !tail.startsWith("\\")) tail = "\\" + tail;
            p = host() + " " + g + ":" + tail + ">";
        } else {
            p = path + g + ">";
        }
        if (p.length() > 44) {
            // cut on a path boundary, otherwise the window can start in the
            // middle of a token and the leading "..." reads as four dots
            String keep = p.substring(p.length() - 41);
            int slash = keep.indexOf('\\', 1);
            if (slash > 0) keep = keep.substring(slash);            p = "..." + keep;
        }
        return p;
    }

    private void onShellReply(int id, String out, String err, int code) {
        if (sessions.isEmpty()) newSession();
        for (Session s : sessions) {
            if (!s.busy) continue;
            s.busy = false;
            if (out != null && out.length() > 0) append(out);
            if (err != null && err.length() > 0) append(err);
            if (code != 0) append("[exit " + code + "]\n");
            // NB: do not try to track cd by asking pwd here. Every command runs
            // in its own 'sh -c', so a separate pwd can only report that
            // process's directory, not where the command left off - and asking
            // via execRaw, which has no cwd, reported the service's default
            // directory and overwrote s.cwd after every single command, undoing
            // cd entirely. The cd builtin is the only writer of s.cwd now.
            //
            // This is also the completion handler for every exec, so it is
            // where the next prompt belongs. Without it the terminal just
            // stopped after a command and looked hung - you had to press enter
            // to get a prompt back, which is what made cd look like it had
            // broken the app.
            appendPrompt();
            return;
        }
    }

    private boolean isBusyboxApplet(String v) {
        String[] common = {"awk", "sed", "grep", "find", "xxd", "base64", "nc", "tar",
                           "curl", "wget", "ps", "top", "hexdump", "stat", "du", "df",
                           "ls", "cat", "cp", "mv", "rm", "mkdir", "head", "tail",
                           "sort", "uniq", "wc", "tr", "cut", "date", "env"};
        for (String c : common) if (c.equals(v)) return true;
        return false;
    }

    /** returns true if the command was handled locally */
    private boolean builtin(String verb, String arg) {
      if (sessions.isEmpty()) newSession();
      Session s = sessions.get(current);

        if (verb.equals("help")) { append(helpText()); return true; }
        if (verb.equals("commands")) { doCommands(arg, s); return true; }
        if (verb.equals("prefs")) { showPrefsDialog(); return true; }
        if (verb.equals("pref")) { setPref(arg, s); return true; }
        if (verb.equals("color")) { doColor(arg, s); return true; }
        if (verb.equals("colors")) { showPrefsDialog(); return true; }

        // ---- device control: the adb cheat sheet, without the 'adb shell'
        if (verb.equals("home"))       { dev(s, "home", "input keyevent 3"); return true; }
        if (verb.equals("back"))       { dev(s, "back", "input keyevent 4"); return true; }
        if (verb.equals("key"))        { doKey(arg, s); return true; }
        if (verb.equals("text"))       { doInputText(arg, s); return true; }
        if (verb.equals("tap"))        { dev(s, echo("tap", arg), "input tap " + arg); return true; }
        if (verb.equals("swipe"))      { dev(s, echo("swipe", arg), "input swipe " + arg); return true; }
        if (verb.equals("focus"))      { dev(s, "focus", FOCUS_CMD); return true; }
        if (verb.equals("screengrab")) { doScreengrab(arg, s); return true; }
        if (verb.equals("record"))     { doRecord(arg, s); return true; }
        if (verb.equals("wmsize"))     { doWm(arg, s, "size"); return true; }
        if (verb.equals("density"))    { doWm(arg, s, "density"); return true; }
        if (verb.equals("battery"))    { doBattery(arg, s); return true; }
        if (verb.equals("launch"))     { doLaunch(arg, s); return true; }
        if (verb.equals("stopapp"))    { dev(s, echo("stopapp", arg), "am force-stop " + arg); return true; }
        if (verb.equals("clearapp"))   { doClearApp(arg, s); return true; }
        if (verb.equals("installapk")) { doInstallApk(arg, s); return true; }
        if (verb.equals("uninstallapk")) { dev(s, echo("uninstallapk", arg), "pm uninstall " + arg); return true; }
        if (verb.equals("apkpath"))    { dev(s, echo("apkpath", arg), "pm path " + arg); return true; }
        if (verb.equals("monkey"))     { doMonkey(arg, s); return true; }
        if (verb.equals("packages"))   { doPackages(arg, s); return true; }
        if (verb.equals("grantapp"))   { doPerm(arg, s, "grant"); return true; }
        if (verb.equals("revokeapp"))  { doPerm(arg, s, "revoke"); return true; }
        if (verb.equals("resetperms")) { doResetPerms(arg, s); return true; }
        if (verb.equals("openurl"))    { dev(s, echo("openurl", arg), "am start -a android.intent.action.VIEW -d " + q(arg)); return true; }
        if (verb.equals("tel"))        { dev(s, echo("tel", arg), "am start -a android.intent.action.CALL -d tel:" + arg); return true; }
        if (verb.equals("sms"))        { doSms(arg, s); return true; }
        if (verb.equals("features"))   { dev(s, "features", "pm list features"); return true; }
        if (verb.equals("servicelist")) { dev(s, "servicelist", "service list"); return true; }
        if (verb.equals("netstat"))    { dev(s, "netstat", "netstat -a"); return true; }
        if (verb.equals("cheatsheet")) { doCheatsheet(s); return true; }
        if (verb.equals("checkshizuku")) { doCheckShizuku(s); return true; }
        if (verb.equals("pwshstart")) { doPwshStart(s); return true; }
        if (verb.equals("pwshstop")) { doPwshStop(s); return true; }
        if (verb.equals("prompt")) {
            promptStyle = (promptStyle + 1) % PROMPT_STYLES.length;
            pput("promptStyle", PROMPT_STYLES[promptStyle]);
            append("prompt style: " + PROMPT_STYLES[promptStyle]
                    + "   now: " + prompt() + "\n");
            return true;
        }
        if (verb.equals("clear") || verb.equals("cls")) {
            if (pon("confirmClear", false) && !arg.toLowerCase(Locale.US).contains("sure")) {
                append("clear wipes this tab. run 'clear sure' to do it, or turn\n"
                        + "confirm off: pref confirmClear off\n");
                return true;
            }
            s.buf.replace(0, s.buf.length(), "");
            render();
            return true;
        }
        if (verb.equals("pwshdemo")) { doPwshDemo(s); return true; }
        if (verb.equals("cd") || verb.equals("chdir")) { doCd(arg, s); return true; }
        if (verb.equals("cdroot")) { doCd("/", s); return true; }
        if (verb.equals("cdshell")) { doCd("/storage/emulated/0", s); return true; }
        if (verb.equals("cdsdcard")) { doCd("/sdcard", s); return true; }
        if (verb.equals("cddownload")) { doCd("/storage/emulated/0/Download", s); return true; }
        if (verb.equals("cdprefix")) { doCd(ShellService.PREFIX, s); return true; }
        if (verb.equals("cdpkg")) { doCd(ShellService.PKGROOT, s); return true; }
        if (verb.equals("exit")) {
            if (sessions.size() > 1) { sessions.remove(current); current = 0; render(); }
            else { finish(); }
            return true;
        }
        if (verb.equals("su")) { doSu(arg, s); return true; }
        if (verb.equals("askforsu") || verb.equals("asksu")) { doAskSu(s); return true; }
        if (verb.equals("info")) { append(infoText(s)); return true; }
        if (verb.equals("rootcheck")) { append(rootText()); return true; }
        if (verb.equals("adbhelp") || verb.equals("settings")) {
            showSettingsDialog();
            return true;
        }
        if (verb.equals("pkg")) { doPkg(arg, s); return true; }
        if (verb.equals("apkinstall") || verb.equals("installapk")) {
            doApkInstall(arg, s);
            return true;
        }
        if (verb.equals("run")) { doRun(arg, s); return true; }
        if (verb.equals("tabnew")) { newSession(); return true; }
        if (verb.equals("tabclose")) {
            if (sessions.size() > 1) { sessions.remove(current); current = 0; render(); }
            return true;
        }
        return false;
    }

    /**
     * cd has to be a builtin. Handing it to the shell is pointless: every
     * command here runs in a fresh `sh -c`, so a `cd` executed there moves that
     * process's directory and then the process exits, taking the change with
     * it. The session's cwd is the only directory that persists, so cd has to
     * update it and the next command has to be launched from it.
     *
     * Accepts what a shell accepts, plus the drive-letter paths this prompt
     * displays, because the prompt says "D:\Music\" and people then type it.
     */
    private void doCd(String arg, final Session s) {
        if (sh == null) { append("no shell\n"); return; }
        String t = arg == null ? "" : arg.trim();

        // drop one layer of matching quotes
        if (t.length() >= 2
                && ((t.startsWith("'") && t.endsWith("'"))
                 || (t.startsWith("\"") && t.endsWith("\"")))) {
            t = t.substring(1, t.length() - 1);
        }

        if (t.length() == 0 || t.equals("~")) {
            t = ShellService.PREFIX;           // the most useful "home" here
        } else if (t.equals("-")) {
            t = s.prevCwd.length() > 0 ? s.prevCwd : s.cwd;
        } else if (t.length() >= 2 && Character.isLetter(t.charAt(0))
                && t.charAt(1) == ':') {
            t = posixFromWin(t);
        } else if (t.startsWith("~")) {
            t = ShellService.PREFIX + t.substring(1);
        } else if (!t.startsWith("/")) {
            t = s.cwd + "/" + t;
        }
        final String target = CdPath.resolve(t);

        // in a PowerShell session, move PowerShell's own location too, or the
        // two drift apart and the prompt lies
        if (s.ps) {
            s.busy = true;
            sh.psLine("Set-Location -LiteralPath '"
                    + target.replace("'", "''") + "'; (Get-Location).Path",
                    new Shell.Raw() {
                        public void got(String o) {
                            s.busy = false;
                            if (o == null || !o.trim().startsWith("/")) {
                                append("cd: no such directory: " + target + "\n");
                            } else {
                                s.prevCwd = s.cwd;
                                s.cwd = o.trim();
                                append("-> " + s.cwd + "\n");
                            }
                            appendPrompt();
                        }
                    });
            return;
        }

        s.busy = true;
        sh.execRaw("cd " + shq(target) + " 2>/dev/null && pwd -P", new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                String got = o == null ? "" : o.trim();
                if (!got.startsWith("/")) {
                    append("cd: no such directory: " + target + "\n");
                } else {
                    s.prevCwd = s.cwd;
                    s.cwd = got;
                    // say where we ended up. A bare prompt change is easy to
                    // miss, and a cd that silently did nothing is exactly the
                    // bug that made this look broken in the first place.
                    append("-> " + s.cwd + "\n");
                }
                appendPrompt();
            }
        });
    }

    /** single-quote a string for /system/bin/sh */
    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** the inverse of winPath: D:\Music -> /storage/emulated/0/Music */
    private String posixFromWin(String w) {
        String rest = w.substring(2).replace('\\', '/');
        while (rest.startsWith("/")) rest = rest.substring(1);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        char drive = Character.toUpperCase(w.charAt(0));
        if (drive == 'D') {
            return "/storage/emulated/0" + (rest.isEmpty() ? "" : "/" + rest);
        }
        if (drive == 'E') return ShellService.PREFIX + (rest.isEmpty() ? "" : "/" + rest);
        // C: is the device root, with <codename>\ standing in for /
        String home = "/" + host();
        if (rest.equalsIgnoreCase(host())) return "/";
        if (rest.toLowerCase(Locale.US).startsWith(host().toLowerCase(Locale.US) + "/")) {
            return "/" + rest.substring(host().length() + 1);
        }
        return home + (rest.isEmpty() ? "" : "/" + rest);
    }

    /**
     * The shared volume has several names and they are all the same place:
     *
     *   /sdcard              -> /storage/self/primary
     *   /storage/self/primary -> /storage/emulated/0
     *
     * The shell happily accepts any of them, and pwd reports back whichever one
     * you typed. That matters here because the prompt is derived from the
     * session directory: leaving it as /sdcard/Download would draw a nonsense
     * C:\sdcard\Download\ instead of the D:\ it actually is. So rewrite the
     * alias to the real path before anything else looks at it.
     *

    /**
     * su is a registered builtin, not a fake button. With no root it says so
     * plainly; with root present the same command works unchanged.
     */
    /**
     * pwshdemo: prove the live PowerShell session end to end from the prompt,
     * without typing several things and having to remember the order. Each step
     * is a separate psLine, so it also exercises the request/response path the
     * sentinel protocol depends on.
     */
    private void doPwshDemo(Session s) {
        if (!s.ps) {
            append("no PowerShell session. start one with: pwshstart\n");
            return;
        }
        append("running the live session through its paces\n\n");
        final String[][] STEPS = {
            { "$PSVersionTable.PSVersion.ToString(); $demo = 6*7; \"6*7 = $demo\"",
              "version and arithmetic" },
            { "$demo * 2", "variable persisted, $demo*2 =" },
            { "cd /storage/emulated/0; (Get-Location).Path", "cd persisted:" },
            { "Get-ChildItem -Name | Select-Object -First 5",
              "first 5 entries in the current directory:" },
            { "try { 1/0 } catch { \"caught: \" + $_.Exception.GetType().Name }",
              "error handling:" },
        };
        // chained rather than fired at once, so the answers come back in the
        // order the questions were asked
        runStep(s, STEPS, 0);
    }

    /** run STEPS[i], then STEPS[i+1], so the demo reads top to bottom */
    private void runStep(final Session s, final String[][] steps, final int i) {
        if (i >= steps.length) {
            append("\nif every line above came back non-empty, the session"
                    + " is healthy.\n");
            appendPrompt();
            return;
        }
        s.busy = true;
        sh.psLine(steps[i][0], new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                String body = (o == null) ? "" : o.trim();
                append(steps[i][1] + (body.length() == 0 ? "  (EMPTY REPLY)" : "  " + body)
                        + "\n");
                runStep(s, steps, i + 1);
            }
        });
    }

    /**
     * apkinstall: the adb install flags, run through the shell we already have.
     *
     * 'apkinstall -r -g - some.apk' needs the apk on stdin, because pm cannot
     * read one off /storage/emulated/0 - the sdcard is FUSE and the package
     * manager streams the file through a pipe, which fails there. That means
     * reading the file and handing it over, rather than sending a command line.
     */
    private void doApkInstall(String arg, final Session s) {
        if (sh == null) { append("no shell yet\n"); return; }
        String a = arg == null ? "" : arg.trim();
        if (a.length() == 0) { append(Apk.usage()); return; }

        String[] r = Apk.scriptFor(a, s.cwd);
        if (r[0] == null) { append(r[1]); return; }

        boolean viaStdin = false;
        for (String t : a.split("\\s+")) {
            if (t.equals("-")) { viaStdin = true; break; }
        }
        if (!viaStdin) {
            s.busy = true;
            sh.exec(r[0], s.cwd);
            return;
        }

        // the '-' form: find the file the same way the script would, then
        // stream it across
        String path = apkStdinPath(a, s.cwd);
        if (path == null) {
            append("apkinstall: name the apk to stream, e.g.\n"
                    + "  apkinstall -r -g - /storage/emulated/0/Download/app.apk\n");
            return;
        }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) {
            append("apkinstall: cannot read " + path + "\n");
            return;
        }
        append("  sending " + f.length() + " bytes to pm\n");
        s.busy = true;
        try {
            sh.execFromStream(f, r[0]);
        } catch (Throwable e) {
            s.busy = false;
            append("apkinstall: " + e + "\n");
        }
    }

    /** the file named after the '-' in an apkinstall line, skipping flag values */
    private static String apkStdinPath(String arg, String cwd) {
        String[] tok = arg.split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            String t = tok[i];
            if (t.startsWith("-") && !t.equals("-")) {
                if (t.indexOf('=') < 0 && Apk.takesValue(t)) i++;
                continue;
            }
            if (t.equals("-")) continue;
            if (t.startsWith("/")) return t;
            String base = (cwd == null || cwd.length() == 0) ? "/" : cwd;
            return base + "/" + t;
        }
        return null;
    }

    /**
     * Feed one typed line to the live PowerShell session, holding it if it is
     * part of a block that is not finished yet. The completeness rule lives in
     * PsInput so it can be tested without an Android runtime.
     */
    private void psFeed(Session s, String line) {
        if (s.psPending.length() > 0) s.psPending.append('\n');
        s.psPending.append(line);

        if (!PsInput.complete(s.psPending.toString())) {
            // show a continuation prompt, so it is obvious the terminal is
            // waiting for more rather than having lost the line
            append(">> ");
            return;
        }
        final String block = s.psPending.toString();
        s.psPending.setLength(0);
        s.busy = true;
        sh.psLine(block, new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                if (o != null && o.length() > 0) {
                    append(o.endsWith("\n") ? o : o + "\n");
                }
                appendPrompt();
            }
        });
    }

    /**
     * Build the su probe.
     *
     * The thing that triggers the manager's prompt is the su binary itself, at
     * whatever path it lives on - so each candidate is invoked by its own
     * absolute path, not by hoping `su` is on $PATH. A device can have su at
     * /product/bin/su with nothing named su on the PATH at all, so an earlier
     * version that detected the path and then ran a bare `su` could find a
     * binary and still ask the wrong one.
     *
     * Order is chosen by which daemon is actually running, because that is
     * what tells us whose su is the live one:
     *
     *   magiskd   -> /product/bin/su and /system/bin/su. /product is a real
     *                partition on modern Android and the stock su lives there,
     *                with Magisk magic-mounting over it, so it is usually the
     *                first hit rather than the legacy path it looks like.
     *   ksud      -> /debug_ramdisk/su and /data/adb/ksu/bin/su, which is
     *                where KernelSU puts it. Neither is on the normal PATH.
     *   apd       -> APatch's own location.
     *
     * Everything else is still tried afterwards, in a fixed order, so an
     * unrecognised manager or an unusual layout still gets found. The first
     * candidate that answers 0 wins and stops the walk, because each attempt
     * can block for the full timeout while the manager waits for the user -
     * trying all of them at 30s each would be minutes of nothing.
     */
    private static String suProbe(int timeoutSec) {
        return "BB=" + ShellService.BUSYBOX + "; "
             + "try_su() { "
             + "  _p=$1; "
             + "  [ -e \"$_p\" ] || return 0; "
             + "  if command -v timeout >/dev/null 2>&1; then "
             + "    _o=$(timeout " + timeoutSec + " \"$_p\" -c 'id -u' 2>&1 | head -1); "
             + "  else "
             + "    _o=$(\"$_p\" -c 'id -u' 2>&1 | head -1); "
             + "  fi; "
             + "  echo \"ATT $_p :: $_o\"; "
             + "  case \"$_o\" in 0*) exit 0;; esac; "
             + "  return 0; "
             + "}; "
             + "M=; C=; "
             + "if pidof magiskd >/dev/null 2>&1; then M=magisk; "
             + "C=\"/product/bin/su /system/bin/su /system/xbin/su\"; fi; "
             + "if pidof ksud >/dev/null 2>&1; then M=kernelsu; "
             + "C=\"/debug_ramdisk/su /data/adb/ksu/bin/su $C\"; fi; "
             + "if pidof apd >/dev/null 2>&1; then M=apatch; fi; "
             + "[ -n \"$M\" ] && echo \"MGR $M\"; "
             + "echo 'TRYBEGIN'; "
             + "for p in $C /product/bin/su /system/bin/su /system/xbin/su "
             + "/sbin/su /su/bin/su /debug_ramdisk/su /data/adb/ksu/bin/su "
             + "/vendor/bin/su; do try_su \"$p\"; done; "
             + "try_su \"$(command -v su 2>/dev/null)\"; "
             + "echo 'TRYEND'";
    }

    private static final String SU_PROBE = suProbe(8);

    /** results of parsing the probe output, so both entry points share the logic */
    private static class SuResult {
        String path;          // a su binary that exists
        String manager;       // magisk / kernelsu / apatch, null if none
        String said;          // what the winning su printed, "" if none did
        int attempts = 0;     // how many candidates were actually run
        boolean tried() { return !said.isEmpty(); }
        boolean granted() { return said.trim().equals("0"); }
        boolean present() { return path != null; }
    }

    private static SuResult parseSu(String o) {
        SuResult r = new SuResult();
        boolean inTry = false;
        for (String line : (o == null ? "" : o).split("\\n")) {
            String l = line.trim();
            if (l.startsWith("MGR ")) { r.manager = l.substring(4).trim(); continue; }
            if (l.equals("TRYBEGIN")) { inTry = true; continue; }
            if (l.equals("TRYEND")) { inTry = false; continue; }
            if (!inTry || !l.startsWith("ATT ")) continue;

            // ATT <path> :: <reply>
            String rest = l.substring(4);
            int sep = rest.indexOf(" :: ");
            if (sep < 0) continue;
            String p = rest.substring(0, sep).trim();
            String v = rest.substring(sep + 4).trim();
            r.attempts++;
            if (r.path == null && p.length() > 0) r.path = p;
            // first non-empty reply wins; the script exits early on 0 anyway
            if (r.said.isEmpty() && v.length() > 0) r.said = v;
        }
        return r;
    }

    /**
     * askforsu: deliberately ask the root manager for root, and wait long
     * enough for you to approve it.
     *
     * 'su -c <cmd>' is the Magisk protocol; KernelSU and APatch answer the
     * same call, so this asks whichever su is present rather than Magisk
     * specifically, and reports which manager it found.
     *
     * Two things this has to get right, both learned the hard way:
     *
     * 1. 'command -v su' only searches $PATH, and the managers do not all put
     *    su there. Magisk magic-mounts it at /system/bin/su, older Magisk used
     *    /system/xbin/su, KernelSU uses /debug_ramdisk/su and
     *    /data/adb/ksu/bin/su, APatch has its own. Checking only the PATH
     *    version called rooted devices unrooted, which is what it did on
     *    someone else's phone. So the real locations are checked too.
     *
     * 2. su blocks while the manager waits for approval, and the normal exec
     *    path has no timeout, so an unattended su would hang the terminal
     *    forever. Hence 'timeout' - short for a plain check, long here.
     */
    private void doAskSu(Session s) {
        if (sh == null) { append("no shell\n"); return; }
        append("asking for root...\n");
        append("if a prompt appears in your root manager, approve it.\n");
        s.busy = true;
        sh.execRaw(suProbe(30), new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                SuResult r = parseSu(o);
                if (r.path == null && r.manager == null) {
                    append("\nno root to ask - no su binary anywhere, and no root\n"
                         + "manager daemon is running. nothing to request.\n");
                    append("checked: /product/bin/su /system/bin/su\n"
                         + "         /system/xbin/su /sbin/su /su/bin/su\n"
                         + "         /debug_ramdisk/su /data/adb/ksu/bin/su\n"
                         + "         /vendor/bin/su, and $PATH\n");
                    append("         /product/bin/su is the stock location on\n"
                         + "         most modern Androids, which Magisk\n"
                         + "         magic-mounts over, so it is tried first.\n");
                    append("\nnot a problem: this terminal runs through Shizuku\n"
                         + "as uid 2000 either way.\n");
                    appendPrompt();
                    return;
                }
                if (r.manager != null) append("  manager: " + r.manager + "\n");
                if (r.path != null) append("  su:     " + r.path + "\n");
                if (r.granted()) {
                    append("\nroot granted, uid 0\n");
                    s.uid = 0;                 // prompt glyph flips $ to #
                    appendPrompt();
                    return;
                }
                append("\nroot refused\n");
                if (!r.tried()) {
                    append("  su said nothing for 30s. it is probably still\n"
                         + "  waiting for you, so check the root manager for an\n"
                         + "  unanswered prompt.\n");
                } else {
                    append("  it said: " + r.said + "\n");
                    append("  allow shell access in the manager's superuser\n"
                         + "  list, then run askforsu again.\n");
                }
                append("  the terminal is unaffected, still uid 2000.\n");
                appendPrompt();
            }
        });
    }

    private void doSu(String arg, final Session s) {
        if (sh == null) { append("no shell\n"); return; }
        sh.execRaw(SU_PROBE, new Shell.Raw() {
            public void got(String o) {
                SuResult r = parseSu(o);
                String uid = r.said;

                // No root. This is not a problem to report, it is the normal
                // case: everything in this app runs through Shizuku as uid
                // 2000, and root was only ever a bonus.
                if (r.path == null && r.manager == null) {
                    append("no root on this device - no su, no root manager.\n");
                    append("not a problem: everything here runs through\n"
                         + "Shizuku as uid 2000. still working as normal.\n");
                    append("\nroot would only add ptrace, /proc/<pid>/mem, and\n"
                         + "writes outside the shared folders. 'askforsu' asks\n"
                         + "properly and waits; 'rootcheck' lists where it looked.\n");
                    return;
                }

                if (r.granted()) {
                    append("root granted, uid 0\n");
                    if (r.manager != null) append("  manager: " + r.manager + "\n");
                    if (r.path != null) append("  su:     " + r.path + "\n");
                    s.uid = 0;                 // prompt glyph flips $ to #
                    if (arg != null && arg.length() > 0) {
                        sh.exec("su -c " + q(arg));
                    }
                    render();
                    return;
                }

                // su exists but did not hand over uid 0
                append("root found, not granted\n");
                if (r.manager != null) append("  manager: " + r.manager + "\n");
                if (r.path != null) append("  su:     " + r.path + "\n");
                if (!r.tried()) {
                    append("  it did not answer. it is probably waiting for you\n"
                         + "  to approve it in the root manager - look there, or\n"
                         + "  it timed out after 8s. 'askforsu' waits longer.\n");
                } else {
                    append("  it said: " + r.said + "\n");
                    append("  grant shell access in the root manager's superuser\n"
                         + "  list, then run askforsu again.\n");
                }
                append("  nothing else is affected; still on Shizuku uid 2000.\n");
                render();
            }
        });
    }

    /**
     * Everything the info panels want, in one round trip, as key=value lines.
     *
     * This used to be a probe() per value. probe() fired an async call and
     * then slept 120ms hoping the answer had landed, but every callback is
     * posted to the UI thread and the caller is *on* the UI thread, so the
     * callback could not run until the caller returned. Every value therefore
     * came back as the "?" placeholder. Batching also makes info one call
     * instead of ten.
     *
     * Every value is read from the device. Nothing here is hardcoded to a
     * particular headset or Android version.
     */
    private static final String FACTS_CMD =
              "echo model=$(getprop ro.product.model);"
            + " echo android=$(getprop ro.build.version.release);"
            + " echo api=$(getprop ro.build.version.sdk);"
            + " echo build=$(getprop ro.build.display.id);"
            + " echo fingerprint=$(getprop ro.build.fingerprint);"
            + " echo serial=$(getprop ro.serialno);"
            + " echo uid=$(id -u);"
            + " echo busybox=$(" + ShellService.BUSYBOX + " 2>/dev/null | head -1);"
            + " echo selinux=$(getenforce 2>/dev/null || echo unknown);"
            // Root detection looks everywhere the managers actually put su.
            // Checking only /product/bin/su called rooted devices unrooted,
            // because that is one Magisk-era path and no longer where any of
            // them keep it.
            // 'exit' here ends only the $( ) subshell, so the first match wins
            // and the "absent" default still runs when nothing matched
            + " echo su=$(for p in /system/bin/su /system/xbin/su /sbin/su"
            + " /su/bin/su /debug_ramdisk/su /data/adb/ksu/bin/su"
            + " /vendor/bin/su /product/bin/su; do"
            + " if [ -e \"$p\" ]; then echo $p; exit 0; fi; done; echo absent);"
            + " echo mgr=$(pidof magiskd >/dev/null 2>&1 && echo magisk"
            + " || pidof ksud >/dev/null 2>&1 && echo kernelsu"
            + " || pidof apd  >/dev/null 2>&1 && echo apatch"
            + " || echo none);"
            + " echo suworks=$(su -c 'id -u' 2>/dev/null | head -1);"
            + " echo adbport=$(getprop persist.adb.tcp.port);"
            + " echo installed=$(ls " + ShellService.PREFIX
            + " 2>/dev/null | tr '\\n' ' ')";

    private final java.util.HashMap<String, String> facts =
            new java.util.HashMap<String, String>();

    /** cached value, or a clear marker rather than a bare "?" */
    private String f(String key) {
        String v = facts.get(key);
        if (v == null) return "(loading...)";
        if (v.length() == 0) return "(empty)";
        return v;
    }

    private void loadFacts() {
        final Shell s = sh;
        if (s == null) return;
        s.execRaw(FACTS_CMD, new Shell.Raw() {
            public void got(String o) {
                facts.clear();
                if (o == null) return;
                for (String line : o.split("\\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    facts.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        });
    }

    private String rootText() {
        return "root\n"
             + "  manager    " + f("mgr") + "\n"
             + "  su         " + f("su") + "\n"
             + "  su works   " + f("suworks") + "\n"
             + "  uid now    " + f("uid") + "\n"
             + "  selinux    " + f("selinux") + "\n"
             + "\n"
             + "root is optional here. everything runs through Shizuku as\n"
             + "uid 2000; root would only add ptrace, /proc/<pid>/mem and\n"
             + "writes outside the shared folders.\n"
             + "\n"
             + "su is invoked at each of those paths in turn, not just as a\n"
             + "bare 'su', because the binary itself is what makes the manager\n"
             + "show its prompt. /product/bin/su is the stock location on most\n"
             + "modern Androids and Magisk magic-mounts over it, so it is\n"
             + "tried first. Checking only $PATH reported rooted phones as\n"
             + "unrooted, because su is not always on the PATH at all.\n";
    }

    /**
     * Reads android/api straight from getprop, so this reports whatever the
     * device is actually running rather than a value baked in for one headset.
     * Values come from the cached table, and a refresh is kicked off after
     * printing so a second 'info' is always current.
     */
    private String infoText(Session s) {
        String out = "device\n"
             + "  headset      " + headset() + "\n"
             + "  codename     " + host() + "\n"
             + "  model        " + f("model") + "\n"
             + "  android      " + f("android") + " (api " + f("api") + ")\n"
             + "  build        " + f("build") + "\n"
             + "  serial       " + f("serial") + "\n"
             + "  uid here     " + f("uid") + "\n"
             + "  cwd          " + (s.cwd.isEmpty() ? "/" : s.cwd) + "\n"
             + "  busybox      " + f("busybox") + "\n"
             + "  selinux      " + f("selinux") + "\n"
             + "  root         " + f("su") + "\n"
             + "  wireless dbg " + f("adbport") + "\n";
        loadFacts();
        return out;
    }

    private String adbHelpText() {
        return "wireless debugging\n"
             + "\nThis app cannot pair for you. adb pair needs an RSA-signed\n"
             + "handshake (ro.adb.secure=1) and there is no adb binary on the\n"
             + "device, so the pairing step has to run from your PC:\n"
             + "\n  1. on the Quest:  Settings > Developer > Wireless debugging\n"
             + "  2. pair device with pairing code  -> shows a 6-digit code\n"
             + "  3. on your PC:\n"
             + "       adb pair 192.168.1.50:&lt;pairing-port&gt;\n"
             + "       adb connect 192.168.1.50:5555\n"
             + "  4. that port changes every boot, read it here:\n"
             + "       getprop persist.adb.tcp.port\n"
             + "\ncurrent port: " + f("adbport") + "\n";
    }

    /**
     * pkg: install prebuilt arm64 packages from the Termux repo into PREFIX.
     *
     *   pkg install <name>   fetch, verify, extract, with dependencies
     *   pkg info <name>       what it would pull in, nothing downloaded
     *   pkg list              what is installed
     *   pkg remove <name>     delete the files it installed
     *   pkg search <text>     find package names
     *
     * The scripts themselves live in Pkg. Output arrives through the normal
     * shell reply, so progress shows as it happens rather than all at once.
     */
    private void doPkg(String arg, final Session s) {
        if (sh == null) { append("no shell yet\n"); return; }
        String a = arg == null ? "" : arg.trim();
        if (a.length() == 0) {
            append("pkg\n"
                 + "  install <name>   fetch, check the hash, extract, pull deps\n"
                 + "  status           is the install going, and what it printed\n"
                 + "  list             what is installed\n"
                 + "  info <name>      show what it would install, downloads nothing\n"
                 + "  remove <name>    delete the files it installed\n"
                 + "  search <text>    find package names\n"
                 + "  where            where everything is, and why\n"
                 + "\nprebuilt arm64 packages, nothing is compiled here.\n"
                 + "repo: " + Pkg.REPO + "\n"
                 + "into: " + Pkg.PREFIX + "\n"
                 + "\nan install runs in the background, because python is 17\n"
                 + "packages and takes minutes. this tab stays usable, and\n"
                 + "'pkg status' shows the progress.\n"
                 + "\nnote: this lives outside the app, so an uninstall will not\n"
                 + "remove it. use 'pkg list' then 'pkg remove' to clean up.\n");
            return;
        }
        String name;
        String verb;
        int sp = a.indexOf(' ');
        if (sp < 0) {
            // Subcommands that take no argument have to be recognised before
            // the bare-word fallback below, or 'pkg status' has no space in it
            // and became 'pkg install status', which then tried to download a
            // package called "status". That quietly broke pkg list, pkg where
            // and pkg log too, all of which are single words.
            String w = a.toLowerCase(Locale.US);
            if (w.equals("list") || w.equals("ls")) {
                sh.exec(Pkg.listScript(), s.cwd);
                return;
            }
            if (w.equals("where") || w.equals("root")) {
                append(Pkg.whereScript());
                return;
            }
            if (w.equals("status") || w.equals("log") || w.equals("progress")) {
                sh.exec(Pkg.statusScript(), s.cwd);
                return;
            }
            // bare "pkg python" is a common enough slip to be worth taking
            verb = "install";
            name = a;
        } else {
            verb = a.substring(0, sp).toLowerCase(Locale.US);
            name = a.substring(sp + 1).trim();
        }

        if (verb.equals("list") || verb.equals("ls")) {
            sh.exec(Pkg.listScript(), s.cwd);
            return;
        }
        if (verb.equals("where") || verb.equals("root")) {
            append(Pkg.whereScript());
            return;
        }
        if (verb.equals("status") || verb.equals("log") || verb.equals("progress")) {
            sh.exec(Pkg.statusScript(), s.cwd);
            return;
        }
        if (verb.equals("search") || verb.equals("find")) {
            if (name.length() == 0) { append("pkg search <text>\n"); return; }
            sh.exec(Pkg.searchScript(name), s.cwd);
            return;
        }

        boolean needsName = verb.equals("install") || verb.equals("i")
                || verb.equals("info") || verb.equals("show")
                || verb.equals("remove") || verb.equals("rm")
                || verb.equals("uninstall");
        if (!needsName) {
            append("unknown pkg command '" + verb + "'. try: pkg\n");
            return;
        }
        if (name.length() == 0) {
            append("pkg " + verb + " <name>\n");
            return;
        }
        // a name with a slash or a quote would break the generated script, and
        // the whole script is built by string concatenation
        String bad = Pkg.badName(name);
        if (bad != null) { append(bad + "\n"); return; }

        if (verb.equals("info") || verb.equals("show")) {
            append("resolving, nothing will be downloaded\n");
            sh.exec(Pkg.infoScript(name), s.cwd);
            return;
        }
        if (verb.equals("remove") || verb.equals("rm") || verb.equals("uninstall")) {
            sh.exec(Pkg.removeScript(name), s.cwd);
            return;
        }

        // Install runs in the background. Blocking on it looked broken: the
        // transport buffers a whole command and only replies when the process
        // exits, so the input stayed locked and the screen showed nothing for
        // the several minutes that 17 packages take. So the generated script
        // goes up as a file and gets started with nohup, and the user watches
        // it with 'pkg status'.
        append("preparing " + name + "...\n");
        s.busy = true;
        stageInstallScript(name, s);
    }

    /**
     * Upload the generated install script, then launch it detached.
     *
     * The script has to travel as a file rather than inline. This busybox's
     * ash writes here-documents to a temp file under /data/local, which uid
     * 2000 cannot write to, so the obvious way of shipping a script to the
     * device fails for a reason that has nothing to do with packages.
     */
    private void stageInstallScript(final String name, final Session s) {
        final String script = Pkg.installScript(name);
        java.io.File tmp;
        try {
            tmp = java.io.File.createTempFile("bbpkg", ".sh");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            w.write(script);
            w.close();
        } catch (Throwable e) {
            s.busy = false;
            append("pkg: could not stage the script: " + e + "\n");
            return;
        }
        new Thread("bbterm-pkgstage") {
            public void run() {
                String res = sh.uploadTo(tmp, Pkg.JOBFILE);
                tmp.delete();
                final boolean ok = !res.startsWith("error");
                final String detail = res;
                UI.post(new Runnable() {
                    public void run() {
                        s.busy = false;
                        if (!ok) {
                            append("pkg: could not upload the install script:\n"
                                    + detail + "\n");
                            return;
                        }
                        sh.exec(Pkg.launchScript(), s.cwd);
                    }
                });
            }
        }.start();
    }

    /**
     * run <script>
     *
     * Two things were wrong here. The path went into the command line
     * unquoted, so 'run my test.ps1' became 'sh my test.ps1' and sh tried to
     * run a file called "my" - which is why a perfectly good script reported
     * that it did not exist. And it always used sh, so a .ps1 was fed to the
     * wrong interpreter even inside a live PowerShell session.
     *
     * Now: the path is resolved against the session directory and the usual
     * download folders, existence is checked so the error names the file, and
     * the interpreter is chosen by extension.
     */
    private void doRun(String arg, Session s) {
        if (arg == null || arg.trim().isEmpty()) {
            append("usage: run <script>\n"
                 + "  run test.sh      run with the shell\n"
                 + "  run test.ps1     run with PowerShell\n"
                 + "  the path is quoted, so spaces are fine, and a bare name\n"
                 + "  is looked for in the current directory then Download.\n");
            return;
        }
        String name = arg.trim();
        // strip one layer of quotes the user may have typed
        if (name.length() >= 2
                && ((name.startsWith("'") && name.endsWith("'"))
                 || (name.startsWith("\"") && name.endsWith("\"")))) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.indexOf(' ') >= 0 && name.indexOf('\'') < 0 && name.indexOf('"') < 0) {
            // keep the whole thing as one name when it is quoted, otherwise the
            // first word is the name and the rest are arguments
            name = arg.trim();
        }
        final String path = resolveScript(name, s.cwd);
        if (path == null) {
            append("run: no such file: " + name + "\n");
            append("  looked in:\n    " + s.cwd + "\n");
            for (String d : SCRIPT_DIRS) append("    " + d + "\n");
            append("  give the full path if it is somewhere else\n");
            return;
        }

        boolean ps1 = path.toLowerCase(Locale.US).endsWith(".ps1");
        if (ps1) {
            String cmd = "Set-Location -LiteralPath '"
                    + path.replace("'", "''") + "'; . './"
                    + path.substring(path.lastIndexOf('/') + 1).replace("'", "''") + "'";
            if (s.ps) {
                appendPromptThen(cmd + "\n");
                doPwshLine(s, cmd);
            } else {
                // no live session, so one-shot it. -File takes the path
                // directly and does not need the directory change.
                final String one = "& '" + ShellService.PWSH + "' -NoLogo -NoProfile"
                        + " -NonInteractive -File " + q(path);
                appendPromptThen("pwsh " + path + "\n");
                s.busy = true;
                sh.exec(one, s.cwd);
            }
            return;
        }

        // a sh script: quote it, and make it executable first in case it is not
        final String line = scriptShell() + q(path);
        appendPromptThen(scriptShell() + path + "\n");
        s.busy = true;
        sh.exec("chmod +x " + q(path) + " 2>/dev/null; " + line, s.cwd);
    }

    private static final String[] SCRIPT_DIRS = {
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0",
    };

    /** find a script by name: as given, then the session dir, then the usual folders */
    private static String resolveScript(String name, String cwd) {
        if (name.startsWith("/")) {
            return new java.io.File(name).isFile() ? name : null;
        }
        String base = (cwd == null || cwd.length() == 0) ? "/" : cwd;
        java.io.File f = new java.io.File(base + "/" + name);
        if (f.isFile()) return f.getPath();
        for (String d : SCRIPT_DIRS) {
            f = new java.io.File(d + "/" + name);
            if (f.isFile()) return f.getPath();
        }
        return null;
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------------------------------------------------------------- text

    /**
     * Popup with device + environment facts. Same data as the `info` builtin
     * but as a dialog, because on a headset reading it in the scrollback is
     * awkward.
     */
    /**
     * Open the REAL Android settings, not the Quest one.
     *
     * An implicit android.settings.* intent gets hijacked by
     * com.oculus.vrshell.intents.AndroidIntentsRelayActivity, which drops you
     * in Meta's own VR settings shell that has no developer pages. Naming the
     * component explicitly skips resolution entirely, so vrshell never gets a
     * look in.
     *
     * Verified on device:
     *   com.android.settings/.Settings$DevelopmentSettingsDashboardActivity
     */
    private void openAndroidSettings(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } catch (Throwable t) {
            // Do NOT fall back to the implicit intent here. On Quest that is
            // claimed by com.oculus.vrshell.intents.AndroidIntentsRelayActivity
            // and lands in Meta's own settings shell instead of Android's.
            // A wrong-but-silent screen is worse than an honest message.
            toast("Can't open " + cls.substring(cls.lastIndexOf('$') + 1)
                    + "\nAndroid Settings won't accept the request.");
        }
    }

    private void openDevSettings() {
        openAndroidSettings("com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity");
    }

    /**
     * Jump into system settings. action null means the settings root, otherwise
     * a settings action string like APPLICATION_DEVELOPMENT_SETTINGS.
     */
    private void openSettings(String action) {
        try {
            Intent i = action == null
                    ? new Intent(android.provider.Settings.ACTION_SETTINGS)
                    : new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            // fall back to whatever settings app exists
            try {
                Intent f = new Intent(android.provider.Settings.ACTION_SETTINGS);
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(f);
            } catch (Throwable t2) {
                toast("no settings app available");
            }
        }
    }

    /**
     * Settings: transport status, and wireless-debugging setup.
     *
     * On the adb point specifically - the user is right that pairing is the
     * mechanism (a port plus a 6-digit code). What an app still cannot do is
     * perform the pairing itself: adb pair needs an RSA-signed AUTH handshake
     * and, on first contact, on-device approval. The app could implement that
     * protocol, but the approval prompt has to be accepted on the headset, so it
     * is not a silent operation either. Until that is built, this menu reports
     * the live state and gives the exact commands to run from the PC.
     */
    /**
     * Panel for the in-process adb transport: pair with a device, or connect to
     * one that already trusts this app's key. Fields are the IP address, the
     * port and the six-digit pairing code shown by
     * Developer options -> Wireless debugging.
     */
    private void showAdbDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText("ADB  (in-process, no root)");
        head.setTextColor(0xFFCCCCCC);
        head.setTextSize(17);
        box.addView(head);

        TextView hint = new TextView(this);
        hint.setTextSize(11);
        hint.setTextColor(0xFF8A8A8A);
        hint.setText("On the headset: Developer options > Wireless debugging >"
                + " Pair device with pairing code. Enter that IP, port and code here."
                + " Pairing port differs from the connect port.");
        box.addView(hint);

        final EditText fIp = new EditText(this);
        fIp.setHint("IP address (e.g 192.168.1.50)");
        fIp.setTextColor(0xFFEDEDF2);
        fIp.setHintTextColor(0xFF707070);
        fIp.setTextSize(13);
        fIp.setSingleLine(true);
        String ph = adbPrefs().getString("host", "");
        if (ph.length() > 0) fIp.setText(ph);
        box.addView(fIp);

        final EditText fPort = new EditText(this);
        fPort.setHint("port - tap, then use the pad");
        fPort.setTextColor(0xFFEDEDF2);
        fPort.setHintTextColor(0xFF707070);
        fPort.setTextSize(13);
        fPort.setSingleLine(true);
        int pp = adbPrefs().getInt("port", 0);
        if (pp > 0) fPort.setText(String.valueOf(pp));
        box.addView(fPort);

        final EditText fCode = new EditText(this);
        fCode.setHint("pairing code - 6 digits");
        fCode.setTextColor(0xFFEDEDF2);
        fCode.setHintTextColor(0xFF707070);
        fCode.setTextSize(13);
        fCode.setSingleLine(true);
        box.addView(fCode);

        // port and code are both pure digits, so give them buttons
        box.addView(numPad(new String[] { "PORT", "CODE" },
                new EditText[] { fPort, fCode }, new int[] { 5, 6 }));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        final TextView out = new TextView(this);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setTextSize(10);
        out.setTextColor(0xFF9CDCFE);
        out.setText("idle");

        TextView bPair = mkBtn("PAIR");
        TextView bConn = mkBtn("CONNECT");
        TextView bRun  = mkBtn("RUN 'id'");
        row1.addView(bPair, new LinearLayout.LayoutParams(0, dp(40), 1f));
        row1.addView(bConn, new LinearLayout.LayoutParams(0, dp(40), 1f));
        row1.addView(bRun,  new LinearLayout.LayoutParams(0, dp(40), 1f));
        box.addView(row1);

        // Self: talk to this device's own adbd over loopback. No IP to type and
        // no pairing, because the port is persisted and our key is already known.
        final TextView bSelf = mkBtn("SELF  127.0.0.1:" + adbDevicePort());
        box.addView(bSelf, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        ScrollView sc = new ScrollView(this);
        sc.addView(out);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = mkBtn("CLOSE");
        close.setTextColor(0xFFCCCCCC);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        box.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        final AdbKey[] holder = new AdbKey[1];
        bPair.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { adbGo(holder, out, fIp, fPort, fCode, 0); }
        });
        bConn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { adbGo(holder, out, fIp, fPort, fCode, 1); }
        });
        bRun.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { adbRun(holder, out, fIp, fPort); }

        });
        bSelf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fIp.setText("127.0.0.1");
                fPort.setText(String.valueOf(adbDevicePort()));
                adbGo(holder, out, fIp, fPort, fCode, 1);
            }
        });

        dlg.setContentView(box);
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface di) {
                dlg.getWindow().setLayout(dp(760), dp(560));
                dlg.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF0A0A0A));
            }
        });
        dlg.show();

        // resolve the key in the background so the buttons are never blocked
        new Thread(new Runnable() {
            public void run() {
                try {
                    AdbKey existing = loadAdbKey();
                    if (existing != null) {
                        holder[0] = existing;
                        return;
                    }
                    java.io.File f = new java.io.File(getFilesDir(), "adbkey");
                    AdbKey k = AdbKey.generate();
                    String pem = "-----BEGIN PRIVATE KEY-----\n"
                            + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                                .encodeToString(k.priv.getEncoded())
                            + "\n-----END PRIVATE KEY-----\n";
                    java.io.FileWriter w = new java.io.FileWriter(f);
                    w.write(pem); w.close();
                    holder[0] = k;
                } catch (Throwable t) {
                    post(new Runnable() { public void run() {
                        out.setText("key error: " + t); } });
                }
            }
        }).start();
    }

    private void adbGo(final AdbKey[] holder, final TextView out,
                       final EditText ip, final EditText port, final EditText code,
                       final int mode) {
        final String h = ip.getText().toString().trim();
        final String p = port.getText().toString().trim();
        final String c = code.getText().toString().trim();
        if (holder[0] == null) { out.setText("key still generating..."); return; }
        if (h.isEmpty() || p.isEmpty()) { out.setText("need IP and port"); return; }
        out.setText(mode == 0 ? "pairing..." : "connecting...");
        final AdbKey k = holder[0];
        new Thread(new Runnable() {
            public void run() {
                String res;
                AdbLink link = new AdbLink(k);
                try {
                    boolean ok = (mode == 0)
                        ? link.pair(h, Integer.parseInt(p), c)
                        : link.connect(h, Integer.parseInt(p));
                    res = (ok ? "OK\n" : "FAILED\n") + link.lastLog();
                    if (ok) {
                        // Remember it so the command line can use this transport.
                        post(new Runnable() {
                            public void run() {
                                adbKey = k;
                                adbHost = h;
                                adbPort = Integer.parseInt(p);
                                adbPrefs().edit()
                                    .putString("host", h)
                                    .putInt("port", Integer.parseInt(p))
                                    .apply();
                                if (sh == null) {
                                    status.setText("shizuku: down | using adb transport");
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    res = "error: " + t + "\n" + link.lastLog();
                } finally {
                    link.close();
                }
                final String fin = res;
                post(new Runnable() { public void run() { out.setText(fin); } });
            }
        }).start();
    }

    private void adbRun(final AdbKey[] holder, final TextView out,
                        final EditText ip, final EditText port) {
        final String h = ip.getText().toString().trim();
        final String p = port.getText().toString().trim();
        if (holder[0] == null) { out.setText("key still generating..."); return; }
        out.setText("connecting + running id...");
        final AdbKey k = holder[0];
        new Thread(new Runnable() {
            public void run() {
                String res;
                AdbLink link = new AdbLink(k);
                try {
                    if (!link.connect(h, Integer.parseInt(p))) {
                        res = "connect failed\n" + link.lastLog();
                    } else {
                        res = link.exec("id", 8000) + "\n" + link.lastLog();
                    }
                } catch (Throwable t) {
                    res = "error: " + t + "\n" + link.lastLog();
                } finally {
                    link.close();
                }
                final String fin = res;
                post(new Runnable() { public void run() { out.setText(fin); } });
            }
        }).start();
    }

    private void post(final Runnable r) {
        runOnUiThread(r);
    }

    private TextView mkBtn(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFF000000);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFF9CDCFE);
        return t;
    }

    private void showSettingsDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText("Settings");
        head.setTextColor(0xFFCCCCCC);
        head.setTextSize(17);
        box.addView(head);

        final TextView body = new TextView(this);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(11);
        body.setTextColor(0xFFCCCCCC);
        body.setText("reading state...");
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = new TextView(this);
        close.setText("CLOSE");
        close.setTextColor(0xFF000000);
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0xFFC0C0C0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        box.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        // deep links into the settings pages this app actually needs
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setPadding(0, dp(6), 0, 0);
        // Components verified with `cmd package resolve-activity --brief -n ...`
        // on this device. Naming them explicitly is required: an implicit
        // android.settings.* intent is hijacked by
        // com.oculus.vrshell.intents.AndroidIntentsRelayActivity and lands in
        // Meta's own settings shell, which has no developer pages at all.
        // There is no wireless-debugging settings activity on Quest, only a
        // QS tile behind BIND_QUICK_SETTINGS_TILE, so that one is not linked.
        final String[][] nav = {
            {"DEV OPTIONS", "com.android.settings/.Settings$DevelopmentSettingsDashboardActivity"},
            {"APPS",        "com.android.settings/.Settings$ManageApplicationsActivity"},
            {"ABOUT",       "com.android.settings/.Settings$MyDeviceInfoActivity"},
        };
        for (final String[] nv : nav) {
            final int slash = nv[1].lastIndexOf('/');
            final String pkg = nv[1].substring(0, slash);
            final String cls = nv[1].substring(slash + 1);
            TextView b = new TextView(this);
            b.setText(nv[0]);
            b.setTextColor(0xFFEDEDF2);
            b.setTextSize(11);
            b.setGravity(Gravity.CENTER);
            b.setBackgroundColor(0xFF2A2A2A);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openAndroidSettings(pkg, cls); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            links.addView(b, lp);
        }
        box.addView(links);

        dlg.setContentView(box);
        dlg.show();

        if (sh == null) {
            body.setText("transport   shizuku NOT connected\n\n"
                    + "grant this app in Shizuku/ByteZuku, then reopen.\n"
                    + "see adb pairing notes below for a fallback path.\n");
            return;
        }

        final String[] keys = {
            "transport", "echo connected-via-shizuku",
            "uid",        "id -u",
            "selinux",    "getenforce",
            "root",       "[ -e /product/bin/su ] && echo present || echo absent",
            "adb secure", "getprop ro.adb.secure",
            "adb port",   "getprop persist.adb.tcp.port",
            "pair port",  "getprop persist.adb.pairing.port",
        };
        final StringBuilder sb = new StringBuilder();
        sb.append("WIRELESS DEBUGGING\n\n");
        sb.append("adb pair needs a port plus a 6-digit code, and the\n");
        sb.append("app cannot complete the RSA handshake silently - the\n");
        sb.append("device must approve it. Run from your PC:\n\n");
        sb.append("  1. Quest: Settings > Developer > Wireless debugging\n");
        sb.append("     > Pair device with pairing code\n");
        sb.append("  2. PC:  adb pair 192.168.1.50:<pair-port> <code>\n");
        sb.append("     adb connect 192.168.1.50:5555\n\n");
        sb.append("the port changes every boot, it is below.\n\n");
        sb.append("live state\n");
        final TextView out = body;
        fill(keys, 0, sb, out);
    }

    private void showInfoDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText("Terminal");
        head.setTextColor(0xFFCCCCCC);
        head.setTextSize(17);
        box.addView(head);

        final TextView body = new TextView(this);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(11);
        body.setTextColor(0xFFCCCCCC);
        body.setPadding(0, dp(10), 0, dp(10));
        body.setText("gathering...");
        // note: body must be added to exactly one parent. Adding it to box as
        // well as the ScrollView throws "child already has a parent".
        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        box.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = new TextView(this);
        close.setText("CLOSE");
        close.setTextColor(0xFF0B0B0F);
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0xFFC0C0C0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        box.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        dlg.setContentView(box);
        dlg.show();

        // fill it in from the shell, one probe at a time
        if (sh == null) {
            body.setText("no shell transport yet.\n\n"
                    + "grant this app in Shizuku/ByteZuku, then reopen.\n"
                    + "Shizuku must be running.");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        final String[] keys = {
            "model",     "getprop ro.product.model",
            "android",   "getprop ro.build.version.release",
            "build",     "getprop ro.build.version.display.id",
            "serial",    "getprop ro.serialno",
            "uid",       "id -u",
            "selinux",   "getenforce",
            "root",      "[ -e /product/bin/su ] && echo present || echo absent",
            "busybox",   "/data/local/tmp/busybox | head -1",
            "adb port",  "getprop persist.adb.tcp.port",
            "prefix",    "ls -d /data/local/tmp/bbterm 2>/dev/null || echo none",
        };
        fill(keys, 0, sb, body);
    }

    private void fill(final String[] keys, final int i, final StringBuilder sb,
                      final TextView body) {
        if (i >= keys.length) {
            body.setText(sb.toString());
            return;
        }
        final String label = keys[i];
        final String cmd = keys[i + 1];
        sh.execRaw(cmd + " 2>&1", new Shell.Raw() {
            public void got(String o) {
                String v = (o == null || o.trim().isEmpty()) ? "-" : o.trim();
                sb.append(pad(label, 10)).append(v).append('\n');
                body.setText(sb.toString());
                fill(keys, i + 2, sb, body);
            }
        });
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private String helpText() {
        Session s = sessions.isEmpty() ? null : sessions.get(current);
        int uid = s == null ? 2000 : s.uid;
        return headset() + " - uid 2000 shell through Shizuku/ByteZuku, no root\n"
             + "drive map   C: device root (\\" + host() + "\\)   D: /storage/emulated/0   E: tool prefix\n"
             + "privilege   $ = uid " + uid + " shell   # = uid 0 root\n"
             + "prompt      " + prompt() + "        ('prompt' cycles cmd / plain / unix)\n\n"
             + "navigation\n"
             + "  cdroot            go to /                    -> " + winPath("/") + "\n"
             + "  cdshell           go to /storage/emulated/0   -> " + winPath("/storage/emulated/0") + "\n"
             + "  cdprefix          go to the tool prefix       -> " + winPath(ShellService.PREFIX) + "\n"
             + "  clear / cls       clear the screen\n"
             + "  tabnew / tabclose manage tabs\n"
             + "  prompt            cycle the prompt style\n"
             + "  exit              close this tab\n\n"
             + "this headset\n"
             + "  info              model, codename, serial, uid, selinux, root\n"
             + "  commands          list every command on PATH ('commands grep' filters)\n"
             + "  checkshizuku      did shizuku say yes or no, and can we prove it\n"
             + "  cheatsheet        the adb cheat sheet, on-device edition\n"
             + "  prefs             open the preferences panel (tap a row to change)\n"
             + "  pref <name> [val] show or set one from the prompt\n"
             + "  color             list the colors, or: color <name> <RRGGBB>\n"
             + "                    names: bg ps fg prompt dim theme\n"
             + "  rootcheck         root status: manager, su, whether it works\n"
             + "  askforsu          ask the root manager for root and wait 30s.\n"
             + "                    approve it in Magisk/KernelSU/APatch when the\n"
             + "                    prompt appears. says granted, refused, or\n"
             + "                    no root, and names the manager it found.\n"
             + "  adbd              adb daemon state on this device\n"
             + "  settings          open a Quest settings page (adbhelp lists targets)\n"
             + "  pkg install <p>   prebuilt Termux arm64 packages, with dependencies\n"
             + "  pkg list          what is installed\n"
             + "  pkg remove <p>    delete the files a package installed\n"
             + "  cd <dir>            change directory, this is a real builtin.\n"
             + "                      accepts ~, - for the previous directory,\n"
             + "                      quoted paths with spaces, and the D: drive\n"
             + "                      form the prompt shows, e.g. cd D:\\Music\n"
             + "  cdroot / cdshell    the device root, the shared volume\n"
             + "  cdsdcard            same shared volume, /sdcard is an alias\n"
             + "  cddownload          straight to the Download folder\n"
             + "  cdprefix / cdpkg    the app's own folder, installed packages\n"
             + "                      /sdcard, /mnt/sdcard, /mnt/user and\n"
             + "                      /storage/self/primary are all the same as\n"
             + "                      /storage/emulated/0 and all get rewritten\n"
             + "                      to it, so the prompt never shows /sdcard\n"
             + "  run <script>        run a script. .ps1 goes to PowerShell,\n"
             + "                      anything else to the shell. the path is\n"
             + "                      quoted, so spaces are fine\n"
             + "  su <cmd>          run as root if there is root, else as shell\n\n"
             + "what is actually different on a Quest\n"
             + "  implicit android.settings.* intents never arrive - vrshell's\n"
             + "    AndroidIntentsRelayActivity swallows them, so every settings\n"
             + "    target here is named by component, not by action string.\n"
             + "  screencap returns a black frame, so verify UI through logcat:\n"
             + "    logcat -s BBterm, AndroidRuntime, System.err\n"
             + "  adb cannot authorise itself. The PC's key is paired over the WIFI\n"
             + "    TLS transport only, and adbd tracks authorisation per transport,\n"
             + "    so a plain TCP 5555 connection from inside an app gets a\n"
             + "    confirmation prompt it can never satisfy. /data/misc/adb/adb_keys\n"
             + "    is root-only, and TLS/SPAKE2 pairing is not reimplemented here.\n"
             + "    That is why the shell comes from Shizuku instead: it is already\n"
             + "    uid 2000 and needs no key at all.\n"
             + "  SELinux is Enforcing, so uid 2000 has CapEff 0 and cannot ptrace\n"
             + "    another process or read /proc/<pid>/mem. Anything that needs to\n"
             + "    inject into a running process wants root.\n"
             + "  wireless debugging is on a fixed port from\n"
             + "    persist.adb.tcp.port, and adbd listens on [::]:5555 as uid 2000.\n\n"
             + "power shell\n"
             + "  pwsh <cmd>        one-shot PowerShell 7.6.6 via a glibc loader + ICU shim\n"
             + "  pwshstart         start a live PowerShell session (blue console, PS prompt)\n"
             + "  pwshstop          end the PowerShell session and go back to sh\n"
             + "  pwshfile <f.ps1>  run a PowerShell script file\n"
             + "  pwshdemo          exercise the live session and print every answer\n"
             + "  pwsh <expr>       one-shot, no session. good for a quick check:\n"
             + "                    pwsh 6*7\n"
             + "                    pwsh $PSVersionTable.PSVersion\n\n"
             + "  busybox applets resolve automatically (awk, sed, grep, tar,\n"
             + "  find, xxd, wget, hexdump, stat, du, df ...)\n"
             + "  there is no curl applet in this busybox, use wget\n\n"
             + "handy on Quest\n"
             + "  dumpsys battery   power state, headset runtime\n"
             + "  dumpsys thermalservice   thermal zones and throttling\n"
             + "  dumpsys display   compositor and refresh rate\n"
             + "  pm list packages  installed apps\n"
             + "  ps -A -o USER,NAME   who is running as what, adbd included\n\n"
             + "hard limits for uid 2000\n"
             + "  ptrace / process injection   EPERM under SELinux Enforcing\n"
             + "  su                         needs a real root install\n"
             + "  self-authorising with adb   needs root or a TLS pairing prompt\n"
             + "  symlinks in the app folder  /storage/emulated/0 refuses them\n"
             + "  package installs           go to " + ShellService.PKGROOT + " because\n"
             + "                             of that, which is ext4, not the sdcard\n";
    }
}
