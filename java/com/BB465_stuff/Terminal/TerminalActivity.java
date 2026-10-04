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
 * Tabbed terminal. Each tab is an independent session with its own scrollback,
 * cwd and history. Commands go over a Parcel transact to ShellService, which
 * Shizuku runs as uid 2000.
 *
 * The builtins live here rather than in the service because they are UI-level
 * conveniences (tab state, local history), except pkg/su/info which are handed
 * to the shell.
 */
public class TerminalActivity extends Activity {

    private LinearLayout root;
    /**
     * root's parent. Nothing is drawn over the whole window any more: the
     * video overlay goes into root at the scroll view's index instead, so the
     * input bar and status line stay visible while it plays. A full-window
     * overlay hid the 'badapple stop' command and the message saying what is
     * playing.
     */
    private android.widget.FrameLayout host;
    /** index in root that scroll occupies, where an overlay has to go */
    private int paneIndex = 1;
    private BadApple apple;
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
          /**
           * how much of buf is the startup banner. While the buffer is still
           * exactly this long nothing has been typed, so the banner can be
           * rewritten when the transport changes. Past it, the banner is
           * history and only gets a fresh status line appended.
           */
          int bannerLen;
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
         * Executing each line as it arrived turned a block opener into a
         * broken fragment: "foreach ($i in 1..3) {" came back as "n=" and
         * then a ParserError on the '}'. Holding the text until it is a
         * complete statement is what makes loops, functions and here-strings
         * work at all.
         */
        final StringBuilder psPending = new StringBuilder();
        /** where we were before the last cd, for 'cd -' */
        String prevCwd = "";
    }

    /** null means nothing is up, and the status line has to say why */
    private Transport sh;
    private SuShell suT;
    private AdbShell adbT;
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

    // ---------------------------------------------------- pkg install progress
    //
    // 'pkg install' runs nohup'd, because a 17-package pull would otherwise
    // lock the input for minutes. The cost was that the tab said "preparing
    // python" and then nothing until the user thought to type 'pkg status'.
    // The log the job writes is polled and echoed here instead.

    private boolean pkgFollow = false;
    private boolean pkgQuery = false;
    private int pkgShown = 0;
    private Session pkgSession;
    private long pkgUntil = 0;

    private final Runnable pkgPoll = new Runnable() {
        public void run() {
            if (!pkgFollow) return;
            final Session s = pkgSession;
            // a job killed before it could write job.exit (a reboot, say) would
            // otherwise be followed forever, printing nothing
            if (System.currentTimeMillis() > pkgUntil) {
                pkgFollow = false;
                appendTo(s, "-- stopped following after 15 minutes. "
                        + "'pkg status' still reports whatever it wrote --\n");
                return;
            }
            // never talk to the transport while the user has a command of their
            // own in flight, or the two replies interleave
            if (s != null && sh != null && !s.busy && !pkgQuery) {
                pkgQuery = true;
                sh.execRaw(Pkg.watchScript(), new Shell.Raw() {
                    public void got(String out) {
                        pkgQuery = false;
                        stepPkg(s, out);
                    }
                });
            }
            if (pkgFollow) tick.postDelayed(this, 1200);
        }
    };

    /** start echoing the job log into the tab that asked for it */
    private void followPkg(Session s, String name) {
        pkgFollow = false;                 // a second install replaces the first
        pkgQuery = false;
        pkgShown = 0;
        pkgSession = s;
        pkgFollow = true;
        pkgUntil = System.currentTimeMillis() + 15 * 60 * 1000L;
        // a moment's grace: nohup has not created the log yet on the first tick
        tick.postDelayed(pkgPoll, 700);
    }

    /** one poll's reply - a header, then the whole log of which we show the new lines */
    private void stepPkg(Session s, String out) {
        if (out == null) return;                    // transport hiccup, try again
        int nl = out.indexOf('\n');
        if (nl < 0 || !out.startsWith(Pkg.WATCH)) return;
        String[] f = out.substring(Pkg.WATCH.length(), nl).split("\\|");
        if (f.length < 3) return;
        int whole;
        try { whole = Integer.parseInt(f[0].trim()); }
        catch (NumberFormatException e) { return; }
        boolean done = "1".equals(f[1].trim());
        String rc = f[2].trim();

        // cut the body down to the lines the header counted, so a line still
        // being written is neither shown nor counted twice
        String body = out.substring(nl + 1);
        int cut = 0, seen = 0;
        while (cut < body.length() && seen < whole) {
            if (body.charAt(cut) == '\n') seen++;
            cut++;
        }
        if (seen < whole) return;                   // body clipped, ask again
        if (cut > 0) showPkgLines(s, body.substring(0, cut));
        pkgShown = whole;

        if (!done) return;
        pkgFollow = false;
        appendTo(s, "\n-- install finished, exit code " + rc
                + ("0".equals(rc) ? "" : ", which is not zero, so read the lines above")
                + " --\n");
    }

    /** print the part of the log that has not been printed yet */
    private void showPkgLines(Session s, String text) {
        String[] lines = text.split("\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].length() == 0) n--;   // text ends with a newline
        int from = Math.min(pkgShown, n);
        if (from >= n) return;
        StringBuilder b = new StringBuilder();
        for (int i = from; i < n; i++) b.append(lines[i]).append('\n');
        appendTo(s, b.toString());
    }

    /**
     * Append into one named tab. append() targets whichever tab is on screen,
     * which is wrong for a background job: its output has to land in the tab
     * that started it, even if the user has since switched.
     */
    private void appendTo(final Session s, final String text) {
        if (offUi()) {
            UI.post(new Runnable() {
                public void run() { appendTo(s, text); }
            });
            return;
        }
        s.buf.append(text);
        int cap = pintk("scrollback", 100_000);
        if (cap < 4_000) cap = 4_000;
        if (s.buf.length() > cap) {
            s.buf.delete(0, s.buf.length() - (cap - cap / 5));
        }
        render();
    }

    @Override
    protected void onPause() {
        // a decoder thread and an audio player outliving the window is a leak
        // and a surprise on the next resume, so leaving the app stops it
        if (apple != null) {
            apple.stop(null);
            apple = null;
        }
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // any key ends the video, which is what makes it usable in a headset
        // where there is no pointer and Escape is not a thing
        if (apple != null && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            apple.stop("stopped");
            apple = null;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick.postDelayed(poller, 300);
        warmAdbSerial();
        // No automatic adb arming: the PC's key is paired over the WIFI TLS
        // transport, adbd tracks authorisation per transport, and a plain TCP
        // 5555 connection from inside the app gets a confirmation prompt a
        // headset cannot answer. 'adbsetup' is the deliberate route.
    }

    // adbd keeps a fixed tcp port in persist.adb.tcp.port, so the app can find
    // its own device over loopback and re-arm the transport with no UI.
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

    private static String readAll(java.io.BufferedReader r) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        return b.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4601) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
            }
            append(ok
                    ? "video permission: granted. type playvideo to play something.\n"
                    : "video permission: denied, so it cannot read the video.\n"
                      + "  without it there is nothing to show. grant it in\n"
                      + "  Settings > Apps > Terminal > Permissions.\n");
            return;
        }
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

    // ---------------------------------------------------------------- transports

    /** one line naming what is actually running commands, for status and help */
    private String transportLine() {
        if (sh == null) return "no transport";
        return sh.describe();
    }

    private void setTransport(Transport t, String note) {
        Transport old = sh;
        sh = t;
        if (old != null && old != t && old instanceof SuShell) ((SuShell) old).shutdown();
        if (old != null && old != t && old instanceof AdbShell) ((AdbShell) old).shutdown();
        for (Session s : sessions) {
            if (t != null) s.uid = t.uid();
            if (s.ps && t != null && old != t) {
                // a live pwsh pipe belongs to the transport that started it
                s.ps = false;
            }
        }
        if (status != null) status.setText(note);
    refreshBanner();
    }

    /**
     * The startup banner says what the transport was when the tab opened, so a
     * tab that began as "no transport" stays wrong after adbsetup pairs. While
     * nothing has been typed the banner is rewritten; once there is real output
     * under it, the banner is history and only a status line is added.
     */
    private void refreshBanner() {
        if (sessions.isEmpty()) return;
        Session s = sessions.get(current);
        if (s.ps || s.bannerLen == 0) return;
        if (s.buf.length() > s.bannerLen) {
            append("transport is now " + transportLine() + "\n");
            appendPrompt();
            return;
        }
        // buf is a SpannableStringBuilder, so clear() rather than setLength(0)
        s.buf.clear();
        s.buf.append(headset() + " - " + transportLine() + "\n"
                + "type 'help' for the full command list\n\n");
        s.bannerLen = s.buf.length();
        render();
    }


    /**
     * Root won, so every later command goes through su instead of whatever was
     * running before. uid 0 is a superset of uid 2000, so there is nothing to
     * give up by switching.
     */
    private void adoptRoot(String suPath) {
        if (shCb == null) shCb = makeCallback();
        suT = new SuShell(suPath, shCb);
        setTransport(suT, "root: uid 0 via su " + suPath);
        pput("suPath", suPath);
        pput("suGranted", "1");
        loadFacts();
        render();
    }

    /** the adb client is now talking to this device; use it for commands */
    private void adoptAdb(String device) {
        if (shCb == null) shCb = makeCallback();
adbT = new AdbShell(this, device, shCb);
    adbT.refreshUid();
    setTransport(adbT, "adb: " + device);
    pput("adbDev", device);
    // same as the shizuku path: without this the prompt and the startup banner
    // never learn the codename, because only the shizuku callback used to ask
    loadCodename(adbT);
    loadFacts();
    render();
    }

    private void adoptShizuku() {
        if (shCb == null) shCb = makeCallback();
        setTransport(shCbReady, "shizuku: connected (uid 2000 shell)");
    }

    /** set by the Shizuku callback, so adbsetup can fall back to it */
    private Shell shCbReady;

    /**
     * The adb banner for a screen being drawn right now. Never forks adb: the
     * version is warmed in the background, and until it lands this says so
     * rather than blocking the UI thread on a process spawn.
     */
    private String adbBanner() {
        AdbBin.warmVersion(this);
        String v = AdbBin.versionCached();
        if (v == null) return "present (reading version...)";
        if (AdbBin.firstLine(v).startsWith("absent")) return "not in this build";
        return "present (" + AdbBin.firstLine(v) + ")";
    }

    /**
     * Nothing is running commands: wireless adb is the default, so it is
     * connected automatically from the saved device or the fixed port adbd
     * keeps in persist.adb.tcp.port. Once this app has paired, adbd trusts the
     * loopback connection and no confirmation prompt is involved.
     */
    private void appendNoShell() {
        final String suSaved = pget("suPath", null);
        final boolean suWas = pon("suGranted", false);
        final String adbSaved = pget("adbDev", null);
        final boolean hasBin = AdbBin.available(this);

        append("\n");
        if (hasBin) {
            append("adb client:  " + adbBanner() + "\n");
        } else {
            append("adb client:  not in this build\n");
        }
        append("su:          " + describeSuOnDisk() + "\n");
        append("\nnothing is running commands yet. shizuku is tried first, then adb:\n\n");
        append("  1. wireless adb   pairs once with 'adbsetup', then this app\n");
        append("                    connects on its own at every launch. no root,\n");
        append("                    no Shizuku.\n");
        append("  2. su             root via the app's own su. needs a root\n");
        append("                    manager and one approval tap.\n");
        append("  3. checkshizuku   Shizuku/ByteZuku, running with Terminal\n");
        append("                    granted. uid 2000.\n");

        if (suWas && suSaved != null) {
            append("\nre-checking the su you granted before...\n");
            new Thread(new Runnable() {
                public void run() {
                    Su.Probe p = Su.probe(new String[]{suSaved}, 4000, 1);
                    post(new Runnable() {
                        public void run() {
                            if (p.status == Su.GRANTED) {
                                adoptRoot(p.path);
                                append("root is still granted. uid 0.\n");
                            } else {
                                append("no longer granted. run 'su' to ask again.\n");
                                autoConnectAdb(adbSaved);
                            }
                        }
                    });
                }
            }).start();
        } else {
            // Shizuku first, adb only if that does not come up. The banner used
            // to lead with adb as 'the default', which meant a headset with a
            // working Shizuku grant still went down the pairing path first and
            // printed a connect attempt every launch.
            // Shizuku first. adb is armed either way, so the saved serial is the one
            // that answers rather than the scanned emulator serial.
            tryShizukuThenAdb(adbSaved);
            autoConnectAdb(adbSaved);
        }
    }

    /**
     * Give Shizuku a moment to answer before falling back to adb. Shizuku binds
     * its UserService asynchronously, so returning 'not ready' immediately would
     * skip it on every launch and land straight back on adb.
     *
     * @return true if a Shizuku transport took over, so adb should not be tried
     */
    private boolean tryShizukuThenAdb(final String adbSaved) {
        final boolean granted = pon("shizukuGranted", false);
        if (!granted) return false;
        connectShell();
        for (int i = 0; i < 30; i++) {
            if (sh != null && sh.isReady()) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        return sh != null && sh.isReady();
    }

    /**
     * Raise the wireless adb transport with no pairing UI: connect to the
     * saved device, else to the port in persist.adb.tcp.port, else 5555. A
     * device that was paired before answers straight away; one that never
     * was needs 'adbsetup' once, and the message says so.
     */
    private void autoConnectAdb(String savedDev) {
        // A working Shizuku is still no reason to leave the adb transport
        // unarmed. Without an explicit 'adb connect' the adb server only ever
        // reports whatever it stumbled onto by scanning, which on this device
        // is the local emulator serial 'emulator-5554' rather than the saved
        // 127.0.0.1:5555. Connecting is one round trip and idempotent, so it is
        // done every launch and only the message is toned down when Shizuku
        // already has the session.
        final boolean quiet = sh != null && sh.isReady();
        if (!AdbBin.available(this)) {
            append("no adb client in this build, so 'adbsetup' cannot run.\n");
            appendPrompt();
            return;
        }
        final String dev = (savedDev != null && savedDev.length() > 0)
                ? savedDev : loopback(adbDevicePort());
        if (quiet) {
            append("\nadb transport is kept warm on " + dev + ".\n");
        } else {
            append("\nconnecting the adb client to " + dev + "...\n");
        }
        new Thread(new Runnable() {
            public void run() {
                AdbBin b = adbBin(dev);
                b.connect(dev);
                final boolean on = b.isOnline();
                post(new Runnable() {
                    public void run() {
                        if (on) {
                            // only take over as the command transport when
                            // nothing else is serving. with Shizuku up the
                            // connect is just there so the saved serial is the
                            // one that answers, not emulator-5554
                            if (sh != null && sh.isReady()) {
                                append("adb: " + dev + " (" + b.devices().trim() + ")\n");
                                appendPrompt();
                                return;
                            }
                            adoptAdb(dev);
                            append("adb transport is up.\n");
                        } else if (quiet) {
                            append("adb: not connected yet, run 'adbsetup' to pair.\n");
                            appendPrompt();
                        } else {
                            append("no adb transport. pair once with 'adbsetup':\n");
                            append("  Settings > Developer > Wireless debugging >\n");
                            append("  pair device with pairing code\n");
                            append("after that this app connects on its own.\n");
                        }
                        appendPrompt();
                    }
                });
            }
        }).start();
    }

    /**
     * Connect the saved serial, late enough that nothing has restarted the adb
     * server underneath it.
     *
     * Getting this wrong is invisible: an unarmed client still works, because
     * the adb server finds the headset by scanning and registers it as
     * 'emulator-5554'. So 'adb -s 127.0.0.1:5555 shell' fails while 'adb
     * devices' looks perfectly healthy, and the saved device is never used.
     *
     * It has to run after codename resolution, because that deliberately
     * restarts the server to hand it a name, and a restart drops every
     * transport including one made a moment earlier.
     */
    private void warmAdbSerial() {
        if (!AdbBin.available(this)) return;
        tick.postDelayed(new Runnable() {
            public void run() { warmAdbSerialNow(); }
        }, 4000);
    }

    private void warmAdbSerialNow() {
        final String want = pget("adbDev", loopback(adbDevicePort()));
        Thread t = new Thread(new Runnable() {
            public void run() {
                AdbBin b = adbBin(want);
                b.connect(want);
                if (b.isOnline()) return;
                // adbd can still be coming back after a reboot, so one retry
                // rather than leaving it unarmed until the next launch
                try { Thread.sleep(2500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!isFinishing()) adbBin(want).connect(want);
            }
        }, "adb-warm");
        t.setDaemon(true);
        t.start();
    }

    private String describeSuOnDisk() {
        String found = Su.find();
        if (found == null) return "no su binary on this device";
        String mgr = Su.managerFor(found);
        return found + (mgr == null ? "" : " (" + mgr + ")")
                + (pon("suGranted", false) ? ", granted" : ", not granted");
    }

    private Shell.Callback makeCallback() {
        // so a stale :bbterm can report itself even if it turns up later
        Shell.watchStale(this.shCb);
        return new Shell.Callback() {
            @Override
public void onReady(Shell s) {
                  shCbReady = s;
                  if (sh != null && sh.isReady()) {
                      // Wireless adb won the startup race and owns the terminal;
                      // Shizuku stays on standby for 'adbuse' / 'checkshizuku'.
                      status.setText("shizuku: connected (standby, adb is active)");
                      return;
                  }
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
                    append("shizuku: " + Shell.describeState() + "\n");
                    appendNoShell();
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
     * Every preference is a string cycling through a fixed list of words: one row
     * type, tap to advance, and a bad stored value can never wedge a typed
     * getter because anything not in the list falls back to the default.
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
        new Pref("verboseLog",     "verbose logging",       "off", "on"),
    };

    // Stored as "#RRGGBB" strings, read straight out of prefs rather than
    // through pget(), which only accepts a Pref's fixed option list.
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

    private int lastBg = -1;

    /**
     * The background depends on the current tab, so it has to be recomputed when
     * the tab changes: without this, starting PowerShell in one tab painted every
     * other tab blue too.
     */
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

    // pget only returns values it finds in PREFS, so keys the update menu owns
    // need this instead or they would silently read back as the default
    private String praw(String key, String def) {
        String v = adbPrefs().getString(key, null);
        return v == null ? def : v;
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

    /**
     * Interpreter used by 'run <script>' and the one-shot 'pwsh <cmd>'.
     *
     * /system/bin/sh (mksh) is always present and toybox covers the applets,
     * so there is nothing else to choose. An explicit shebang in the script
     * wins regardless, since this only supplies the interpreter for a bare
     * script name.
     */
    private String scriptShell() {
        return "sh ";
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
        // Height has to be pinned. Added without params the bar's height is
        // unspecified, and a horizontal LinearLayout holding a weighted child
        // (the spacer that pushes the version into the corner) stretches to
        // fill the parent. It then takes all the vertical space and leaves the
        // output ScrollView, which is height 0 weight 1, with nothing - so the
        // terminal pane never draws.
        root.addView(tabBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
        // remember where the scroll view landed, so the video overlay can take
        // exactly that slot instead of covering the whole window
        paneIndex = root.indexOfChild(scroll);

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

        // permissions as a button, because the grant can be revoked by accident
        // update check, where a person will actually look. This was ACCESS,
        // which asked Shizuku for access even when wireless adb was already
        // up and running the commands, so it was a button that did nothing.
        TextView updBtn = new TextView(this);
        updBtn.setText("\u27F3");
        updBtn.setTextColor(0xFFEDEDF2);
        updBtn.setTextSize(15);
        updBtn.setGravity(Gravity.CENTER);
        updBtn.setBackgroundColor(0xFF2A2A2A);
        updBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showUpdateDialog(); }
        });
        bar.addView(updBtn, new LinearLayout.LayoutParams(dp(64), dp(48)));

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

        // host exists so a full-screen overlay can sit on top of the whole app
        // without disturbing the layout above. root is unchanged inside it, so
        // the vertical LinearLayout still measures the way it always has.
        host = new android.widget.FrameLayout(this);
        host.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(host);
    }

    // ---------------------------------------------------------- bad apple

    /**
     * Find the video without being told where it is. A person dropping a file
     * in Downloads and typing 'badapple' is the whole interaction, so the
     * common places get checked before giving up and asking.
     */
    private java.io.File findAppleVideo(String named) {
        java.util.List<java.io.File> tries = new java.util.ArrayList<java.io.File>();
        if (named != null && named.length() > 0) {
            tries.add(new java.io.File(named));
        }
        String[] dirs = {
            System.getenv("BADAPPLE_VIDEO"),
            "/sdcard/Download",
            "/sdcard/Downloads",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads",
            "/data/local/tmp",
            System.getProperty("user.home"),
        };
        for (String d : dirs) {
            if (d == null || d.length() == 0) continue;
            java.io.File dir = new java.io.File(d);
            java.io.File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (java.io.File f : kids) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (n.contains("bad apple") || n.contains("badapple")
                        || n.contains("bad_apple")) {
                    if (isVideo(f)) return f;
                }
            }
        }
        // no name match, so take the first video we can see
        for (String d : dirs) {
            if (d == null || d.length() == 0) continue;
            java.io.File[] kids = new java.io.File(d).listFiles();
            if (kids == null) continue;
            for (java.io.File f : kids) {
                if (isVideo(f)) return f;
            }
        }
        return null;
    }

    private boolean isVideo(java.io.File f) {
        if (!f.isFile() || f.length() < 1024) return false;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".mov") || n.endsWith(".m4v") || n.endsWith(".3gp");
    }

    private void doBadApple(String arg) {
        String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        String named = "";
        int fps = 12;
        boolean loop = true;

        for (String w : a) {
            if (w.length() == 0) continue;
            if (w.startsWith("fps=")) {
                try {
                    fps = Math.max(1, Math.min(60, Integer.parseInt(w.substring(4))));
                } catch (NumberFormatException e) {
                    append("badapple: fps= wants a number, not '" + w.substring(4) + "'\n");
                    return;
                }
            } else if (w.equals("once")) {
                loop = false;
            } else if (w.equals("loop")) {
                loop = true;
            } else if (w.equals("stop")) {
                if (apple != null) {
                    apple.stop("stopped");
                    apple = null;
                    append("badapple: stopped\n");
                } else {
                    append("badapple: not playing\n");
                }
                return;
            } else if (w.equals("url")) {
                append("badapple url <address>\n"
                        + "  fetches that address into the app cache and plays it.\n\n"
                        + "  default is " + AppleSource.DEFAULT_URL + "\n");
                return;
            } else if (w.equals("help")) {
                append(appleHelp());
                return;
            } else {
                named = w;
            }
        }

        if (apple != null) {
            apple.stop(null);
            apple = null;
        }

        // a bare http(s) argument means "fetch this", which is how a headset that has
        // never seen the file gets one without an adb push per device
        if (named.startsWith("http://") || named.startsWith("https://")) {
            grabApple(named, fps, loop);
            return;
        }

        // A cached copy is app-private, so it needs no storage permission and
        // works on a fresh headset that has never had anything pushed to it.
        // Only fall back to shared storage, which does need the grant, when
        // there is no cache and nothing was named.
        java.io.File f = AppleSource.cached(this);
        boolean haveCache = f.isFile() && f.length() > 1024;

        if (!haveCache && named.length() == 0 && !hasMediaRead()) {
            append("badapple: not on this headset yet, fetching it.\n"
                    + "  first run is ~12MB, then it is cached.\n");
            grabApple(AppleSource.DEFAULT_URL, fps, loop);
            return;
        }

        if (!haveCache && !hasMediaRead()) {
            requestMediaRead();
            append("badapple: no cached copy, and reading video needs permission.\n"
                    + "  asked for it. accept it, or just type termdev badapple\n"
                    + "  again to download instead.\n");
            return;
        }

        if (!haveCache && named.length() == 0 && System.getenv("BADAPPLE_VIDEO") == null) {
            java.io.File found = findAppleVideo("");
            if (found != null) f = found;
        } else if (named.length() > 0) {
            java.io.File direct = new java.io.File(named);
            if (direct.isFile()) f = direct;
        }

        if (!f.isFile() || f.length() < 1024) {
            grabApple(AppleSource.DEFAULT_URL, fps, loop);
            return;
        }

        startApple(f, fps, loop, "badapple");
    }

    /** pull the video down on a worker, then start playing it */
    private void grabApple(final String url, final int fps, final boolean loop) {
        append("badapple: downloading, this is 12MB or so.\n");
        final long t0 = System.currentTimeMillis();
        Thread th = new Thread(new Runnable() {
            public void run() {
                final long[] lastReport = {0};
                final String err = AppleSource.fetch(TerminalActivity.this, url,
                        new AppleSource.Progress() {
                            public void bytes(long got, long total) {
                                // only every megabyte, or the scrollback fills
                                // with progress lines on a fast link
                                if (got - lastReport[0] < 1048576L) return;
                                lastReport[0] = got;
                                final String line = "badapple: "
                                        + (got / 1048576L) + "MB"
                                        + (total > 0 ? " of " + (total / 1048576L) + "MB" : "")
                                        + "\n";
                                UI.post(new Runnable() {
                                    public void run() { append(line); }
                                });
                            }
                        });
                UI.post(new Runnable() {
                    public void run() {
                        if (err != null) {
                            append(err
                                    + "  no cached copy, so there is nothing to play.\n\n");
                            append(appleHelp());
                            return;
                        }
                        append("badapple: got it in "
                                + ((System.currentTimeMillis() - t0) / 1000L) + "s\n");
                        startApple(AppleSource.cached(TerminalActivity.this), fps, loop, "badapple");
                    }
                });
            }
        }, "badapple-fetch");
        th.setDaemon(true);
        th.start();
    }

    /** start playback on an already-resolved file */
    private void startApple(java.io.File f, int fps, boolean loop, String label) {
        if (apple != null) {
            apple.stop(null);
            apple = null;
        }
        final BadApple[] slot = new BadApple[1];
        BadApple ba = new BadApple(this, root, paneIndex, new BadApple.Say() {
            public void say(String s) {
                append(s);
                // a callback can fire after the caller already started another
                // one, so only clear the field if it is still ours
                if (apple == slot[0]) apple = null;
            }
        }, f.getAbsolutePath(), fps, loop).labelled(label);
        slot[0] = ba;
        apple = ba;
        String err = ba.start();
        if (err != null) {
            apple = null;
            append(err);
        }
    }

    private boolean hasMediaRead() {
        // READ_EXTERNAL_STORAGE is the whole story up to API 32, after that
        // video moved to READ_MEDIA_VIDEO
        String p = android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO"
                : "android.permission.READ_EXTERNAL_STORAGE";
        return checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaRead() {
        final String[] want = android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{"android.permission.READ_MEDIA_VIDEO",
                               "android.permission.READ_MEDIA_IMAGES"}
                : new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
        try {
            requestPermissions(want, 4601);
        } catch (Throwable e) {
            append("badapple: could not ask for permission: "
                    + e.getClass().getSimpleName() + "\n");
        }
    }

    private String appleHelp() {
        return "hehe\n\n"
            + "termdev badapple [file|url] [fps=N] [once|loop]\n"
            + "  plays it over the app in its own view, so the frames never\n"
            + "  land in the scrollback and the terminal stays usable\n\n"
            + "  termdev badapple                play it, downloading first if needed\n"
            + "  termdev badapple once fps=12    straight through, no loop\n"
            + "  termdev badapple <url>          fetch that url instead of the default\n"
            + "  termdev badapple <file>         play a file already on the headset\n"
            + "  termdev badapple stop           stop it early\n\n"
            + "  for any other video on the headset, use playvideo.\n\n"
            + "  on a headset that has never seen it, the first run pulls about\n"
            + "  12MB and caches it in the app. after that it is instant and\n"
            + "  works with no permission granted and no network.\n\n"
            + "  tap the video or press any key to stop it\n";
    }

    // ---------------------------------------------------------- playvideo

    /** where a dropped video turns up. badapple searches these too. */
    private static final String[] MEDIA_DIRS = {
        "/sdcard/Download", "/sdcard/Downloads",
        "/storage/emulated/0/Download", "/storage/emulated/0/Downloads",
        "/storage/emulated/0/Movies", "/data/local/tmp",
    };

    /**
     * playvideo <file> - any video on the headset, through the same overlay
     * badapple uses. BadApple is a general player that happens to have one
     * particular video and one default url bolted on; this is the general case,
     * so it never downloads anything and only guesses which file you meant if
     * you leave the name off.
     */
    private void doPlayVideo(String arg, Session s) {
        String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        String named = "";
        boolean loop = true;
        boolean list = false;

        for (String w : a) {
            if (w.length() == 0) continue;
            if (w.equals("stop")) {
                if (apple != null) {
                    apple.stop("stopped");
                    apple = null;
                    append("playvideo: stopped\n");
                } else {
                    append("playvideo: nothing playing\n");
                }
                return;
            } else if (w.equals("help")) {
                append(playVideoHelp());
                return;
            } else if (w.equals("list")) {
                list = true;
            } else if (w.equals("once")) {
                loop = false;
            } else if (w.equals("loop")) {
                loop = true;
            } else if (w.startsWith("fps=")) {
                append("playvideo: fps= is accepted and ignored. it only meant\n"
                        + "  something back when the frames were drawn as text;\n"
                        + "  playback is clocked by the audio track.\n");
                return;
            } else if (w.startsWith("http://") || w.startsWith("https://")) {
                append("playvideo: that is an address, not a file.\n"
                        + "  fetch it first, then play it:\n"
                        + "    curl -o /sdcard/Download/clip.mp4 " + w + "\n"
                        + "    playvideo /sdcard/Download/clip.mp4\n");
                return;
            } else {
                named = w;
            }
        }

        if (list) { listMedia(); return; }

        java.io.File f = named.length() > 0 ? resolveMedia(named, s) : firstMedia();
        if (f == null) {
            append(named.length() > 0
                    ? "playvideo: no video called '" + named + "'\n"
                    : "playvideo: no videos found\n");
            append(playVideoHelp());
            return;
        }

        // the app cache needs no grant; shared storage does
        if (!f.canRead()) {
            if (!hasMediaRead()) {
                requestMediaRead();
                append("playvideo: " + f.getName() + " is on shared storage, which needs\n"
                        + "  the video permission. asked for it - accept it, then type\n"
                        + "  'playvideo " + f.getAbsolutePath() + "' again.\n");
                return;
            }
            append("playvideo: cannot read " + f.getAbsolutePath() + "\n");
            return;
        }

        // no announce here: the overlay names the file as it starts, and saying
        // it twice made playvideo print its own line and then badapple's
        startApple(f, 0, loop, "playvideo");
    }

    /** a named video: as typed, then relative to the session dir, then the usual folders */
    private java.io.File resolveMedia(String named, Session s) {
        java.io.File direct = new java.io.File(named);
        if (isVideo(direct)) return direct;
        if (s != null && s.cwd != null) {
            java.io.File rel = new java.io.File(s.cwd, named);
            if (isVideo(rel)) return rel;
        }
        for (String d : MEDIA_DIRS) {
            java.io.File f = new java.io.File(d, named);
            if (isVideo(f)) return f;
        }
        return null;
    }

    /** nothing named, so take the first video in the first folder that has one */
    private java.io.File firstMedia() {
        for (String d : MEDIA_DIRS) {
            java.io.File[] kids = new java.io.File(d).listFiles();
            if (kids == null) continue;
            java.util.Arrays.sort(kids);
            for (java.io.File f : kids) {
                if (isVideo(f)) return f;
            }
        }
        java.io.File cached = AppleSource.cached(this);
        return isVideo(cached) ? cached : null;
    }

    private void listMedia() {
        int n = 0;
        for (String d : MEDIA_DIRS) {
            java.io.File[] kids = new java.io.File(d).listFiles();
            if (kids == null) continue;
            java.util.Arrays.sort(kids);
            for (java.io.File f : kids) {
                if (!isVideo(f)) continue;
                n++;
                append("  " + f.getAbsolutePath() + "  "
                        + (f.length() / 1048576L) + "MB\n");
            }
        }
        java.io.File cached = AppleSource.cached(this);
        if (isVideo(cached)) {
            n++;
            append("  " + cached.getAbsolutePath() + "  (app cache)\n");
        }
        if (n == 0) append("no videos in Download, Downloads, Movies or /data/local/tmp\n");
        else append(n + " video(s)\n");
    }

    private String playVideoHelp() {
        return "playvideo\n\n"
            + "playvideo <file> [once|loop]\n"
            + "  plays any video already on the headset, in its own view over the\n"
            + "  app, so the frames never land in the scrollback\n\n"
            + "  playvideo              play the first video it finds\n"
            + "  playvideo list         every video it can see, with sizes\n"
            + "  playvideo <file>       play that one\n"
            + "  playvideo <file> once  straight through, no loop\n"
            + "  playvideo stop         stop it early\n\n"
            + "  a bare name is looked for in the current directory first, so\n"
            + "  'cd /sdcard/Movies' then 'playvideo clip.mp4' works. failing\n"
            + "  that it tries Download, Downloads, Movies and /data/local/tmp.\n\n"
            + "  an http address is not a file. fetch it with curl first:\n"
            + "    curl -o /sdcard/Download/clip.mp4 https://host/clip.mp4\n\n"
            + "  tap the video or press any key to stop it\n";
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
        // Views live on the main looper, so hop instead of crashing with a
        // view-off-thread exception.
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

        // spacer then version, so it sits in the far corner of the bar
        View gap = new View(this);
        tabBar.addView(gap, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView ver = new TextView(this);
        ver.setText(versionCorner());
        ver.setTextSize(11);
        ver.setTextColor(0xFF5A5A5A);
        ver.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        ver.setPadding(dp(8), dp(6), dp(10), dp(6));
        ver.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showUpdateDialog(); }
        });
        tabBar.addView(ver);
    }

    /** "v2.0" normally, "v2.0 -> 1.2.4" once an update has been spotted */
    private String versionCorner() {
        String v = myVersion();
        String newer = praw("updSeen", "");
        if (newer.length() == 0) return "v" + v;
        return "v" + v + " \u2192 " + newer;
    }

    private void doCredits(Session s) {
        append("credits to:\n"
             + "  FreeXR      https://discord.gg/ABCXxDyqrH\n"
             + "  yanno1222 / .networth_\n"
             + "  appnana / gamble_now\n"
             + "\n"
             + "badapple:\n"
             + "  ZUN / Team Shanghai Alice    original 'Bad Apple!!' (Touhou 4: Lotus Land Story)\n"
             + "  Masayoshi Minoshima          arrangement (Alstroemeria Records, Lovelight)\n"
             + "  nomico                       vocals\n"
             + "  Anira (\u3042\u306b\u3089)   the shadow-art PV\n"
             + "  kasidid2                     YouTube upload: watch?v=FtutLA63Cp8\n"
             + "                               (niconico original sm8628149)\n"
             + "\n");
    }

    private void newSession() {
        Session s = new Session();
        s.title = "sh" + (sessions.size() + 1);
        s.cwd = pget("startDir", "/");
        s.uid = sh == null ? 2000 : sh.uid();
        s.buf.append(headset() + " - " + transportLine() + "\n"
                   + "type 'help' for the full command list\n\n");
        s.bannerLen = s.buf.length();
        sessions.add(s);
        current = sessions.size() - 1;
        render();
    }

    /**
     * Verbs that stay local even while a PowerShell session is live. Without
     * this, anything typed in a PS session gets handed to PowerShell: the
     * UI and shell-inspection verbs mean nothing there, and PowerShell would
     * try to parse the sh script that 'pkg' generates. cd is local
     * because doCd moves PowerShell's location itself and reads it back.
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
            || verb.equals("adbsetup")
            || verb.equals("adbtcpip") || verb.equals("adbwireless")
            || verb.equals("adbroot") || verb.equals("adbdev")
            || verb.equals("adbkillserver") || verb.equals("adbstartserver")
            || verb.equals("termdev")
            || verb.equals("adbhint") || verb.equals("adbh")
            || verb.equals("adbuse") || verb.equals("adbshizuku")
            || verb.equals("info") || verb.equals("rootcheck") || verb.equals("commands")
            || verb.equals("pkg") || verb.equals("run") || verb.equals("su")
            || verb.equals("update") || verb.equals("updates") || verb.equals("credits")
            || verb.equals("disableupdates") || verb.equals("enableupdates")
            || verb.equals("playvideo")
            || verb.equals("pwshfile") || verb.equals("pwshdemo")
            || verb.equals("curl");
    }

    /**
     * The adb sheet, translated for a terminal already on the device: host-side
     * things (adb install, fastboot, the bash xargs tricks) are dropped, since
     * you would run those on the PC.
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
        + "  update                    check github for a newer build, install from a menu\n"
        + "  disableupdates            point the ota urls at loopback\n"
        + "  enableupdates             put the stock ota urls back\n\n"

        + "-- intents ----------------------------------------------------\n"
        + "  openurl <url>             am start VIEW\n"
        + "  tel <number>              am start CALL\n"
        + "  sms <number> <body>       am start SENDTO\n\n"

        + "-- network ----------------------------------------------------\n"
        + "  curl <url>                HTTP(S) in the app; real TLS, unlike the\n"
        + "                            device's wget. no shell needed to fetch.\n"
        + "                            -o save, -I headers, -sSL quiet redirects.\n"
        + "                            'curl -h' for the whole flag list\n\n"

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

    /**
     * Point both OTA urls at loopback so an update cannot be fetched, and delete
     * them again to hand the stock servers back. A headset on the Quest store
     * path has no use for Horizon OS updates, and the download can wedge the
     * updater in the background where it is hard to notice.
     */
    private void doOta(String arg, Session s, boolean block) {
        String[] keys = { "ota_update_server_url", "ota_download_url" };
        StringBuilder cmd = new StringBuilder();
        for (String k : keys) {
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("settings ").append(block ? "put" : "delete")
               .append(" global ").append(k)
               .append(block ? " http://127.0.0.1/" : "");
        }
        dev(s, echo(block ? "disableupdates" : "enableupdates", arg), cmd.toString());
    }

    /** versionName out of the manifest, so the menu never quotes a stale literal */
    private String myVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** versionCode, read live too, so nothing pins a stale number */
    private int myVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Update menu: current version, check, check-on-startup. Nothing is installed
     * from here without a tap on the install row, and the row only appears once
     * an apk is actually on disk.
     */
    private void showUpdateDialog() {
        final String mine = myVersion();

        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        box.setBackgroundColor(0xFF0A0A0A);

        TextView head = new TextView(this);
        head.setText("Update");
        head.setTextColor(0xFFCCCCCC);
        head.setTextSize(17);
        box.addView(head);

        final TextView status = new TextView(this);
        status.setText("installed  v" + mine);
        status.setTextColor(0xFF7A7A7A);
        status.setTextSize(11);
        box.addView(status);

        box.addView(button("check for updates", 0xFF20242E, new Tap() {
            public void run(TextView self) { checkForUpdate(dlg, box, status, mine); }
        }));

        box.addView(button("check on startup   " + (praw("updStart", "off").equals("on") ? "on" : "off"),
                0xFF14141A, new Tap() {
            public void run(TextView self) {
                String v = praw("updStart", "off").equals("on") ? "off" : "on";
                pput("updStart", v);
                self.setText("check on startup   " + v);
            }
        }));


        box.addView(button("close", 0xFF14141A, new Tap() {
            public void run(TextView self) { dlg.dismiss(); }
        }));

        dlg.setContentView(box);
        dlg.show();
    }

    /** a row handler gets the row, so it can relabel or disable itself */
    private interface Tap {
        void run(TextView self);
    }

    private TextView button(String label, int bg, final Tap onTap) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFCCCCCC);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackgroundColor(bg);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onTap.run(t); }
        });
        return t;
    }

    private void checkForUpdate(final android.app.Dialog dlg, final LinearLayout box,
                                final TextView status, final String mine) {
        status.setText("checking github...");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                Updater.Release r = null;
                try {
                    r = Updater.latest();
                    if (r == null) msg = "no releases with an apk yet";
                    else if (!Updater.newerThan(r, mine)) msg = "you are on v" + mine + ", " + r.label() + " is the newest";
                    else msg = "you are on v" + mine + ", " + r.label() + " is available";
                } catch (Throwable t) {
                    msg = "check failed: " + t.getMessage();
                }
                final Updater.Release found = r;
                final String text = msg;
                final boolean ok = found != null && Updater.newerThan(found, mine);
                runOnUiThread(new Runnable() {
                    public void run() {
                        status.setText(text);
                        if (ok) {
                            // show it in the corner too, so the menu is findable later
                            pput("updSeen", found.label());
                            render();
                            addInstallRow(box, status, found, mine);
                        }
                    }
                });
            }
        }, "bbterm-update-check").start();
    }

    /** download, then hand the file to the transport for pm install - never automatic */
    private void addInstallRow(final LinearLayout box, final TextView status,
                               final Updater.Release r, final String mine) {
        final TextView row = button("download and install " + r.label(), 0xFF1B2A1B, new Tap() {
            public void run(TextView self) {
                self.setEnabled(false);
                status.setText("downloading 0%");
                new Thread(new Runnable() {
                    public void run() {
                        String msg;
                        try {
                            final java.io.File f = Updater.download(r.url, new Updater.Progress() {
                                public void progress(final int pct) {
                                    runOnUiThread(new Runnable() {
                                        public void run() { status.setText("downloading " + pct + "%"); }
                                    });
                                }
                            });
                            msg = installUpdate(f, mine);
                        } catch (Throwable t) {
                            msg = "download failed: " + t.getMessage();
                        }
                        final String text = msg;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                status.setText(text);
                                self.setEnabled(true);
                            }
                        });
                    }
                }, "bbterm-update-dl").start();
            }
        });
        // land it above the close row
        box.addView(row, Math.max(0, box.getChildCount() - 1));
    }

    /** push the apk somewhere writable and let pm replace us with it */
    private String installUpdate(java.io.File f, String mine) {
        final Transport t = sh;
        if (t == null || !t.isReady()) {
            return "downloaded, but no transport. pair adb or start shizuku, then 'update' again";
        }
        String remote = "/data/local/tmp/bbupdate.apk";
        String up;
        try {
            up = t.uploadTo(f, remote);
        } catch (Throwable e) {
            return "upload failed: " + e.getMessage();
        }
        if (up != null && up.startsWith("error")) return "upload failed: " + up;
        t.exec("pm install -r " + remote);
        return "installing, the app restarts if it succeeds";
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
     * Walk $PATH the way the shell does, so the list matches what you can just
     * type. `commands` prints it all, `commands grep` filters.
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
     * The command that answers "did shizuku say yes or no": it separates
     * manager-not-running, access-denied, and access-granted-but-service-not-
     * bound, because the fix differs for each.
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
     * Bring up a persistent PowerShell session: one pwsh process on the far side
     * that keeps its variables, modules and working directory between lines.
     */
    private void doPwshStart(final Session s) {
        if (sh == null) { append("no shell transport\n"); return; }
        if (s.ps) { append("PowerShell is already running - 'pwshstop' first\n"); return; }
        append("starting PowerShell...\n");
        s.busy = true;
        sh.psStart(new Shell.Raw() {
            public void got(String o) {
                s.busy = false;
                // A live session is a BB| version probe. "ok" is accepted too
                // so a transport that starts pwsh but does not probe still
                // works instead of printing "ok" and leaving the tab in sh.
                String t = o == null ? "" : o.trim();
                boolean live = t.startsWith("BB|") || t.equals("ok")
                        || t.equals("already running");
                if (!live) {
                    append((t.length() == 0 ? "pwsh start failed" : t) + "\n");
                    render();
                    return;
                }
                s.ps = true;
                root.setBackgroundColor(bgColor());
                s.title = "pwsh";
                s.buf.replace(0, s.buf.length(), "");
                if (pon("psBanner", true)) {
                    s.buf.append(psBanner(t.startsWith("BB|") ? t : "BB|unknown|Unknown||"));
                }
                render();
                appendPrompt();
            }
        });
    }

    /**
     * The pwshstart banner, built from the version probe ShellService ran while
     * starting the session. A hardcoded "Windows PowerShell" would be wrong
     * twice over: this is pwsh on linux-arm64, not Windows PowerShell 5.1, and
     * the version is whatever the user actually has installed.
     */
    private String psBanner(String probe) {
        String ver = "unknown", edition = "Unknown", os = "";
        String[] p = probe == null ? new String[0] : probe.split("\\|", -1);
        if (p.length >= 5 && p[0].trim().equals("BB")) {
            ver = p[1].trim();
            edition = p[2].trim();
            // OS reads "Linux 5.15.94-android14-8-..."; the first token is the
            // part worth a banner, the kernel string wraps on a headset screen.
            os = p[3].trim().split("\\s")[0];
            if (ver.length() == 0) ver = "unknown";
            if (edition.length() == 0) edition = "Unknown";
        }
        StringBuilder b = new StringBuilder();
        b.append("Quest PowerShell\n");
        b.append("PowerShell ").append(ver).append(" (").append(edition).append(')');
        if (os.length() > 0) b.append(" on ").append(os);
        b.append('\n');
        b.append("Microsoft (C) All rights reserved.\n");
        b.append('\n');
        return b.toString();
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

    // ------------------------------------------------------------------ curl

    /**
     * curl, served from the app process. It needs no shell to run at all:
     * Java carries the TLS stack, which is the entire reason this exists (the
     * device has no curl and its wget does not check certificates). A shell is
     * only consulted to put a saved file somewhere the app itself cannot
     * write, which on this target is anything on /sdcard outside Android/data.
     */
    private void doCurl(String arg, Session s) {
        final String[] args = Curl.split(arg == null ? "" : arg);
        final Curl.Opts o;
        try {
            o = Curl.parse(args, s.cwd);
        } catch (Curl.Weird w) {
            append("curl: " + w.getMessage() + "\n  'curl -h' for usage\n");
            return;
        }
        if (o.help || args.length == 0) { append(Curl.help()); return; }
        if (o.version) { append("curl 8.7.1 (QuestTerminal; java.net)\n"); return; }

        // -O names the file from the url, -o names it explicitly, neither
        // means "print the body here"
        final String outPath;
        if (o.output != null) outPath = curlPath(o.output, s);
        else if (o.remoteName) outPath = curlPath(curlName(o.urls), s);
        else outPath = null;

        if (!o.silent) {
            append("curl: " + (o.head ? "HEAD " : "")
                    + (o.urls.isEmpty() ? "?" : o.urls.get(0))
                    + " -> " + (outPath == null ? "the screen" : outPath) + "\n");
        }

        final Curl.Say say = new Curl.Say() {
            public void say(String t) { append(t); }
        };
        s.busy = true;
        Thread th = new Thread(new Runnable() {
            public void run() {
                java.io.File tmp = null;
                try {
                    if (outPath == null) {
                        Curl.Result r = Curl.request(o, say, null);
                        reportCurl(r, null, o);
                    } else {
                        tmp = java.io.File.createTempFile("curl-", ".part", getCacheDir());
                        Curl.Result r = Curl.request(o, say, tmp);
                        String bad = placeCurl(tmp, outPath);
                        if (bad != null) append("curl: " + bad + "\n");
                        else reportCurl(r, outPath, o);
                    }
                } catch (Curl.Weird w) {
                    append("curl: " + w.getMessage() + "\n");
                } catch (Exception e) {
                    append("curl: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "\n");
                } finally {
                    if (tmp != null) tmp.delete();
                    final Session cur = sessions.isEmpty() ? null : sessions.get(current);
                    if (cur != null) cur.busy = false;
                    appendPrompt();
                }
            }
        }, "curl");
        th.setDaemon(true);
        th.start();
    }

    private void reportCurl(Curl.Result r, String outPath, Curl.Opts o) {
        if (o.include || o.head) {
            append(r.status + "\n");
            for (String h : r.headers) append(h + "\n");
            append("\n");
        }
        if (o.head) return;
        if (outPath != null) {
            if (!o.silent) append("curl: wrote " + r.bytes + " bytes to " + outPath + "\n");
            return;
        }
        byte[] body = r.body == null ? new byte[0] : r.body;
        if (isBinary(body)) {
            append("curl: " + r.bytes + " bytes of binary"
                    + (r.contentType.length() == 0 ? "" : ", " + r.contentType)
                    + "; not shown, use -o <file>\n");
            return;
        }
        String text;
        try { text = new String(body, "UTF-8"); }
        catch (Exception e) { text = new String(body); }
        append(text);
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') append("\n");
        if (r.truncated) {
            append("curl: truncated at " + (Curl.MAX_MEM / 1024) + "KB, use -o <file>\n");
        }
    }

    /** NUL, or more than a few percent of control bytes, means "do not paint this" */
    private static boolean isBinary(byte[] b) {
        if (b == null || b.length == 0) return false;
        int ctrl = 0;
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) return true;
            if (v < 9 || (v > 13 && v < 32)) ctrl++;
        }
        return ctrl > b.length / 20;
    }

    /** the drive letters the prompt draws, then the session cwd, then tidy up */
    private String curlPath(String raw, Session s) {
        String p = raw;
        if (p.length() >= 2 && p.charAt(1) == ':') {
            char d = Character.toUpperCase(p.charAt(0));
            String rest = p.substring(2).replace('\\', '/');
            while (rest.startsWith("/")) rest = rest.substring(1);
            if (d == 'D') p = "/storage/emulated/0/" + rest;
            else if (d == 'E') p = ShellService.PREFIX + "/" + rest;
            else p = "/" + rest;
        } else if (!p.startsWith("/")) {
            String base = (s.cwd == null || s.cwd.isEmpty()) ? "/" : s.cwd;
            p = base + "/" + p;
        }
        return CdPath.resolve(p);
    }

    private static String curlName(java.util.List<String> urls) {
        if (urls.isEmpty()) return "download";
        String u = urls.get(0);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        return name.length() == 0 ? "index.html" : name;
    }

    /** null on success, or a line saying why the body could not be placed */
    private String placeCurl(java.io.File tmp, String path) {
        java.io.File dest = new java.io.File(path);
        if (isAppPath(dest)) {
            return copyFile(tmp, dest) ? null : "cannot write " + path;
        }
        // /sdcard outside Android/data is not the app's to write, so stage it
        // through the transport, which runs as uid 2000 and owns those paths
        if (sh != null && sh.isReady()) {
            String res = sh.uploadTo(tmp, path);
            if (res == null || !res.startsWith("error")) return null;
            return copyFile(tmp, dest) ? null : res;
        }
        return copyFile(tmp, dest) ? null
                : "cannot write " + path + " with no shell; try cdprefix first";
    }

    private boolean isAppPath(java.io.File f) {
        String p = f.getAbsolutePath();
        java.io.File[] roots = new java.io.File[] {
            getFilesDir(), getCacheDir(), getExternalFilesDir(null),
            getExternalCacheDir(), getDataDir(),
        };
        for (java.io.File r : roots) {
            if (r == null) continue;
            String rp = r.getAbsolutePath();
            if (p.equals(rp) || p.startsWith(rp + java.io.File.separator)) return true;
        }
        return false;
    }

    private static boolean copyFile(java.io.File src, java.io.File dst) {
        try {
            java.io.File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
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
            // No transport at all. The adbsetup hint is the honest answer,
            // because a command cannot run until a transport exists.
            ss.busy = false;
            appendNoShell();
            appendPrompt();
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
     * Upstream pwsh is linux-arm64 glibc and Android is Bionic, so pwsh.sh
     * launches it through Debian's glibc loader rather than exec'ing it.
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
        // No busybox rewriting: the device's toybox (/system/bin) answers the
        // common applets as typed, exactly like a normal shell would.
        return cmd;
    }

    // ------------------------------------------------------------ adb builtins
    // A real adb, shipped as lib/arm64-v8a/libadb.so and run from the app's own
    // nativeLibraryDir. Unlike the in-process protocol it can pair, so it can be
    // authorised, and on a userdebug build it can ask adbd to restart as root.

    private void adbJob(final Session s, final java.util.concurrent.Callable<String> job) {
        if (!AdbBin.available(this)) {
            append("this build carries no adb client (lib/arm64-v8a/libadb.so).\n");
            return;
        }
        s.busy = true;
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    out = job.call();
                } catch (Throwable t) {
                    out = "error: " + t;
                }
                final String fin = out == null ? "" : out;
                post(new Runnable() {
                    public void run() {
                        s.busy = false;
                        if (fin.length() > 0) append(fin.endsWith("\n") ? fin : fin + "\n");
                        appendPrompt();
                        render();
                    }
                });
            }
        }, "bbterm-adbjob").start();
    }

    private AdbBin adbBin(String device) {
        return new AdbBin(this, device);
    }

    private static String loopback(int port) {
        return "127.0.0.1:" + port;
    }

    /**
     * adbsetup <pairport> <code> [port]: the whole flow, since the steps depend
     * on each other and doing them by hand means reading a number off one screen
     * and typing it into another. Pair over loopback, connect, pin adbd to 5555
     * so the port stops moving every boot, reconnect, then ask for root.
     *
     * A client is bound to one address and every -s call and isOnline() check
     * uses it, so pairing and running need separate instances: the pairing port
     * is a TLS socket that closes when pairing ends, and a client pointed at it
     * reports the connect port as offline forever.
     */
    private void doAdbSetup(String arg, final Session s) {
        final String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        if (a.length < 2) { append(adbHelpText()); return; }
        final int pp = posInt(a[0]);
        final String code = a[1];
        if (pp <= 0) { append("bad pairing port: " + a[0] + "\n"); return; }
        final int dp = a.length > 2 ? posInt(a[2]) : adbDevicePort();

        adbJob(s, new java.util.concurrent.Callable<String>() {
            public String call() {
                StringBuilder sb = new StringBuilder();
                AdbBin pairer = adbBin(loopback(pp));
                sb.append("$ adb pair ").append(loopback(pp)).append(' ').append(code).append('\n');
                String pairOut = pairer.pair(pp, code);
                sb.append(pairOut);
                if (!pairOut.toLowerCase(java.util.Locale.US).contains("success")) {
                    sb.append("\npairing did not complete. both the code and the port come\n"
                            + "off the wireless debugging screen, and the pair port changes\n"
                            + "every time you ask for a new code.\n");
                    return sb.toString();
                }
                AdbBin b = adbBin(loopback(dp));
                sb.append("\n$ adb connect ").append(loopback(dp)).append('\n');
                sb.append(b.connect(loopback(dp)));

                // Remember the pairing the moment it succeeds. The key is now in
                // adb_keys on the headset; whether or not the 'allow debugging?'
                // tap has been accepted, this device is the one to talk to next
                // time. Saving it after the auth wait instead meant a failed
                // authorisation threw the pairing away, so every launch started
                // over as though nothing had ever been paired.
                final String paired = loopback(dp);
                post(new Runnable() {
                    public void run() { pput("adbDev", paired); }
                });
                sb.append("\nsaved this device, so later commands know where to look.\n");

                // First contact with this key makes the headset raise its own
                // 'allow debugging' panel, and the user is wearing the thing:
                // wait for the tap instead of racing it.
                StringBuilder auth = new StringBuilder();
                if (!b.authorizeAndWait(90000, auth)) {
                    sb.append("\nconnect did not take. device list:\n").append(b.devices());
                    if (auth.length() > 0) sb.append(auth);
                    sb.append("\n'unauthorized' means the headset is still showing the\n"
                            + "'allow debugging' prompt: accept it on the device, then run\n"
                            + "this again. adbd restarts once accepted, so a retry is normal.\n");
                    return sb.toString();
                }
                if (auth.length() > 0) sb.append('\n').append(auth);
                sb.append("\ntransport authorized as ").append(b.device()).append('\n');
                sb.append("\n$ adb -s ").append(loopback(dp)).append(" tcpip 5555\n");
                sb.append(b.tcpip(5555));
                AdbBin pinned = adbBin(loopback(5555));
                sb.append(pinned.connect(loopback(5555)));
                if (!pinned.waitOnline(10000)) {
                    sb.append("\nadbd did not come back on 5555. device list:\n")
                      .append(pinned.devices());
                    return sb.toString();
                }
                final String dev = loopback(5555);
                sb.append("\nusing ").append(dev).append('\n');

                String rootOut = pinned.adbRoot();
                if (rootOut.length() > 0) sb.append(rootOut);
                pinned.connect(dev);
                String who = AdbBin.firstLine(pinned.shell("whoami"));
                if (!"root".equals(who)) {
                    pinned.connect(dev);
                    who = AdbBin.firstLine(pinned.shell("whoami"));
                }
                if ("root".equals(who)) {
                    sb.append("shell uid: root, uid 0\n");
                } else {
                    // adbd only restarts as root on a userdebug build, so on a
                    // retail headset this is the expected answer, not a fault.
                    sb.append("shell uid: ").append(who.length() > 0 ? who : "unknown")
                      .append("  (adbd stays non-root on a production build;")
                      .append(" 'su' or Shizuku is the way to uid 0)\n");
                }
                post(new Runnable() {
                    public void run() {
                        pput("adbDev", dev);
                        adoptAdb(dev);
                        render();
                    }
                });
                return sb.toString();
            }
        });
    }

    /**
     * adbtcpip [port]. With a transport up this is one adb call; without one,
     * root does the same job by hand. That is the only way to pin the port
     * before any transport exists, and what makes a paired device come back on
     * the same port after a reboot.
     */
    private void doAdbTcpip(String arg, final Session s) {
        final int port = posInt(arg);
        final String dev = pget("adbDev", loopback(adbDevicePort()));
        adbJob(s, new java.util.concurrent.Callable<String>() {
            public String call() {
                StringBuilder sb = new StringBuilder();
                AdbBin b = adbBin(dev);
                if (b.isOnline()) {
                    sb.append("$ adb -s ").append(dev).append(" tcpip ").append(port).append('\n');
                    sb.append(b.tcpip(port)).append('\n');
                    b.connect(loopback(port));
                    if (b.isOnline()) {
                        final String use = loopback(port);
                        post(new Runnable() {
                            public void run() {
                                pput("adbDev", use);
                                adoptAdb(use);
                                render();
                            }
                        });
                    }
                    return sb.toString();
                }
                sb.append("no adb link yet, trying the root way\n");
                if (suT == null) {
                    sb.append("no root either. run 'su' first and this will do it for\n"
                            + "you, or do it from a PC:\n\n  adb -s ").append(dev)
                      .append(" tcpip ").append(port).append('\n');
                    return sb.toString();
                }
                String cmd = "setprop service.adb.tcp.port " + port
                        + "; stop adbd; start adbd; sleep 2; getprop service.adb.tcp.port";
                sb.append(cmd).append('\n');
                Su.Result r = Su.run(suT.suPath(), cmd, null, 30000);
                sb.append(r.out.length() > 0 ? r.out : r.err).append('\n');
                b.connect(loopback(port));
                if (b.isOnline()) {
                    final String use = loopback(port);
                    post(new Runnable() {
                        public void run() {
                            pput("adbDev", use);
                            adoptAdb(use);
                            render();
                        }
                    });
                }
                return sb.toString();
            }
        });
    }

    /**
     * termdev: diagnostics for a terminal, nothing else.
     *
     * termdev                 list these
     * termdev info            what the build is and what it found on this device
     * termdev log [n]         the last n lines this app wrote to logcat
     * termdev savelog <dir>   dump that log to <dir>/bbterm.log, then print it
     * termdev server          which adb server is answering, and whose
     * termdev selftest        report what is and is not reachable from here
     */
    private void doTermDev(String arg, final Session s) {
        String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        String sub = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.US);

        if (sub.length() == 0 || sub.equals("help")) {
            append("termdev info            build, device, transport\n"
                    + "termdev log [n]         last n lines from logcat (default 200)\n"
                    + "termdev savelog <dir>   write those lines to <dir>/bbterm.log\n"
+ "termdev server          which adb server answers, and whose\n"
                    + "termdev keys             where the adb key is kept, and if it is there\n"
                    + "termdev adb <args...>    run the bundled adb directly\n"
                    + "termdev selftest        what is reachable from in here\n\n");
            return;
        }

        if (sub.equals("badapple") || sub.equals("apple")) {
            // pass the rest through, so 'termdev badapple once fps=12' means
            // exactly what 'badapple once fps=12' means
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < a.length; i++) {
                if (rest.length() > 0) rest.append(' ');
                rest.append(a[i]);
            }
            doBadApple(rest.toString());
            return;
        }

        if (sub.equals("keys")) {
            append(AdbBin.keyReport());
            append("\nthis is app-private storage, so it survives a restart and a\n"
                    + "reboot. only an uninstall or 'clear data' throws it away, and\n"
                    + "that means a new key, which means pairing again.\n\n");
            return;
        }

        if (sub.equals("adb")) {
            // straight passthrough to the bundled adb, so anything this app has
            // no command for is still reachable: 'termdev adb devices',
            // 'termdev adb -s 127.0.0.1:5555 shell ls', and so on.
            if (a.length < 2) {
                append("termdev adb <args...>    run the bundled adb directly\n"
                        + "  e.g. termdev adb devices\n"
                        + "       termdev adb -s 127.0.0.1:5555 shell id -u\n"
                        + "       termdev adb reconnect\n\n");
                return;
            }
            final String[] rest = new String[a.length - 1];
            System.arraycopy(a, 1, rest, 0, rest.length);
            adbJob(s, new java.util.concurrent.Callable<String>() {
                public String call() {
                    if (!AdbBin.available(TerminalActivity.this)) {
                        return "no adb client in this build.\n";
                    }
                    AdbBin b = adbBin(pget("adbDev", loopback(adbDevicePort())));
                    StringBuilder sb = new StringBuilder("$ adb");
                    for (String r : rest) sb.append(' ').append(r);
                    sb.append('\n');
                    String out = b.run(rest);
                    sb.append(out.length() > 0 ? out : "(no output)\n");
                    return sb.toString();
                }
            });
            return;
        }

        if (sub.equals("info")) {
            append("build:     " + myVersion() + " (code " + myVersionCode() + ")\n"
                    + "device:    " + headset() + "\n"
                    + "transport: " + transportLine() + "\n"
                    + "adb client:" + (AdbBin.available(this) ? " " : " not in this build, ")
                    + AdbBin.versionCached().replace('\n', ' ') + "\n"
                    + "adb server: port " + AdbBin.SERVER_PORT + " (its own)\n"
                    + "su:        " + describeSuOnDisk() + "\n"
                    + "prefix:    " + ShellService.PREFIX + "\n"
                    + "packages:  " + ShellService.PKGROOT + "\n\n");
            return;
        }

        if (sub.equals("log") || sub.equals("savelog")) {
            int n = 200;
            String dir = null;
            for (int i = 1; i < a.length; i++) {
                try { n = Integer.parseInt(a[i]); } catch (NumberFormatException e) { dir = a[i]; }
            }
            final int lines = Math.max(1, Math.min(2000, n));
            final String where = sub.equals("savelog") ? (dir == null ? ShellService.PKGROOT : dir) : null;
            final int keep = lines;
            devJobs.add(new java.util.concurrent.Callable<String>() {
                public String call() { return fetchLog(keep, where); }
            });
            adbJob(s, devJobs.remove(devJobs.size() - 1));
            return;
        }

        if (sub.equals("server")) {
            append("this app talks to adb server port " + AdbBin.SERVER_PORT + ".\n"
                    + "adb normally reuses whatever already listens on 5037, which on\n"
                    + "this device was another app's server, so 'adb kill-server' would\n"
                    + "have killed that app instead of resetting this one.\n\n");
            sh.exec("ss -tln | grep -E '5037|" + AdbBin.SERVER_PORT + "'");
            return;
        }

        if (sub.equals("selftest")) {
            append("checking what is reachable from in here:\n\n");
            String[] probe = {
                    "echo ok",
                    "id -u",
                    "ls -d " + ShellService.PKGROOT,
                    "ls -d " + ShellService.PREFIX,
                    "getprop ro.product.device",
                    "settings get global device_name",
                    "command -v toybox",
                    "command -v busybox",
                    "command -v pwsh",
            };
            for (String c : probe) {
                sh.execRaw(c, new Shell.Raw() {
                    public void got(String o) {
                        String v = o == null ? "" : o.trim();
                        append(v.length() > 0 ? "  ok    " + firstLineOf(v) : "  FAIL");
                    }
                });
            }
            return;
        }

        append("termdev: no such thing: " + sub + "\ntermdev for the list.\n");
    }

    private static String firstLineOf(String s) {
        for (String l : s.split("\n")) if (l.trim().length() > 0) return l.trim();
        return "";
    }

    private final java.util.List<java.util.concurrent.Callable<String>> devJobs =
            new java.util.ArrayList<java.util.concurrent.Callable<String>>();

    /** pull our own logcat lines, optionally writing them to a file first */
    private String fetchLog(int n, String dir) {
        StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(n), "-s", "BBterm:*");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String l;
            while ((l = br.readLine()) != null) out.append(l).append('\n');
            br.close();
            p.waitFor();
        } catch (Throwable t) {
            return "logcat failed: " + t + "\n";
        }
        String body = out.toString();
        if (body.trim().length() == 0) body = "(nothing logged under the BBterm tag)\n";
        if (dir == null) return body;
        String path = dir.endsWith("/") ? dir + "bbterm.log" : dir + "/bbterm.log";
        StringBuilder sb = new StringBuilder();
        sb.append("$ cat > ").append(path).append(" << 'BBEOF'\n").append(body).append("BBEOF\n");
        sb.append("$ wc -l ").append(path).append('\n');
        sh.execRaw("cat > " + Su.q(path) + " << 'BBEOF'\n" + body + "BBEOF\n"
                + "wc -l " + Su.q(path), new Shell.Raw() {
            public void got(String o) { }
        });
        return sb.toString();
    }

    /**
     * adbkillserver runs `adb kill-server`, adbstartserver runs `adb
     * start-server`. Nothing else: no connect, no device list, no whoami.
     *
     * They are thin on purpose. Folding a reconnect and a uid probe in meant
     * `adbkillserver` did four things, and the extra output obscured which one
     * actually failed. Anyone who wants the rest has 'adbsetup' or 'adbdev',
     * which do exactly that and say so.
     *
     * Both hit this app's own server on 5038, so neither can reach another
     * app's.
     */
    private void doAdbServer(final Session s, final boolean kill) {
        adbJob(s, new java.util.concurrent.Callable<String>() {
            public String call() {
                if (!AdbBin.available(TerminalActivity.this)) {
                    return "no adb client in this build.\n";
                }
                AdbBin b = adbBin(pget("adbDev", loopback(adbDevicePort())));
                String verb = kill ? "kill-server" : "start-server";
                String out = b.run(verb);
                return "$ adb " + verb + "\n"
                        + (out.trim().length() > 0 ? out : "(no output)\n");
            }
        });
    }

    private void doAdbRoot(final Session s) {
        final String dev = pget("adbDev", loopback(adbDevicePort()));
        adbJob(s, new java.util.concurrent.Callable<String>() {
            public String call() {
                AdbBin b = adbBin(dev);
                if (!b.isOnline()) return "no adb link to " + dev + ".\n";
                StringBuilder sb = new StringBuilder("$ adb -s ").append(dev).append(" root\n");
                sb.append(b.adbRoot()).append('\n');
                b.connect(dev);
                String who = AdbBin.firstLine(b.shell("whoami"));
                sb.append("shell uid: ").append(who).append('\n');
                if ("root".equals(who)) {
                    sb.append("this build lets adbd restart as root, so uid 0 needs no\n"
                            + "root manager at all.\n");
                } else {
                    sb.append("ro.debuggable is not set, so adbd will not restart as root.\n"
                            + "the shell stays uid 2000; 'su' is the way to root on a\n"
                            + "stock build.\n");
                }
                return sb.toString();
            }
        });
    }

    /** what the bundled client can see, for when pairing is not working */
    private void doAdbDev(final Session s) {
        final android.content.Context ctx = this;
        adbJob(s, new java.util.concurrent.Callable<String>() {
            public String call() {
                AdbBin b = adbBin(pget("adbDev", loopback(adbDevicePort())));
                java.io.File key = new java.io.File(AdbBin.homeDir(ctx), ".android/adbkey");
                return "binary:    " + AdbBin.binaryPath(ctx) + "\n"
                        + "version:   " + AdbBin.version(ctx).replace('\n', ' ') + "\n"
                        + "HOME:      " + AdbBin.homeDir(ctx) + "\n"
                        + "key file:  " + (key.isFile() ? "present" : "not generated yet") + "\n"
                        + "devices:\n" + b.devices() + "\n"
                        + "port prop: " + adbDevicePort() + "\n";
            }
        });
    }

    /** go back to Shizuku without touching anything else */
    private void doAdbUse(String arg, final Session s) {
        if (shCbReady == null) {
            append("no Shizuku/ByteZuku connection to go back to. run checkshizuku.\n");
            return;
        }
        setTransport(shCbReady, "shizuku: connected (uid 2000 shell)");
        append("back on " + transportLine() + "\n");
        render();
    }

    /** first integer in a string, or the fallback; 0 means "not given" */
    private static int posInt(String s) {
        if (s == null) return 0;
        for (String tok : s.trim().split("\\s+")) {
            try {
                int v = Integer.parseInt(tok);
                if (v > 0) return v;
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    // ----------------------------------------------------------------- prompt
    // cmd-style with a drive letter, led by the headset's own codename so it is
    // right on every headset: C:\eureka\>, C:\hollywood\>, C:\batlleeye\>. The
    // glyph is $ for uid 2000 and # once su has granted root; 'prompt' cycles
    // the three styles at runtime.
    private static final String[] PROMPT_STYLES = { "cmd", "plain", "unix" };
    private int promptStyle = 0;

    /**
     * Read from the device and cached in prefs, because the prompt needs it
     * synchronously and getprop is not.
     */
    private volatile String codename = "";

    /** the codename for display, never empty */
    private String host() {
        String c = codename;
        return (c == null || c.length() == 0) ? "quest" : c;
    }

    /**
     * Codename -> retail name. ro.product.model reads back a generic string on
     * these builds, so the codename is the honest identifier; an unknown one is
     * shown raw rather than guessed at.
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
    private void loadCodename(final Transport t) {
        codename = adbPrefs().getString("codename", "");
        t.execRaw("getprop ro.product.device; getprop ro.product.name; "
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
                    // the adb pairing name is built from this, so it has to be
                    // set here rather than read separately per call
                    AdbBin.noteContext(TerminalActivity.this);
                    AdbBin.codename = codename;
                    // and the server has to be restarted to use it. adb is a
                    // client to a long-lived server, and it is the server that
                    // announces the name, using the environment it inherited when
                    // it was first spawned - which was during warmVersion, long
                    // before the codename was known. Re-advertising on later
                    // calls does nothing, they only talk to a socket.
                    AdbBin.restartForCodename(TerminalActivity.this, codename);
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
     * posix cwd -> drive letter, cmd style: C: is the device root (drawn as
     * \<codename>\), D: is /storage/emulated/0, E: is the tool prefix.
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
            // Do not track cd by asking pwd here: every command runs in its own
            // 'sh -c', so a separate pwd can only report that process's
            // directory and would overwrite s.cwd, undoing cd. The cd builtin is
            // the only writer of s.cwd now.
            //
            // This is also the completion handler for every exec, so the next
            // prompt belongs here - without it the terminal looked hung after a
            // command until you pressed enter.
            appendPrompt();
            return;
        }
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
        if (verb.equals("update") || verb.equals("updates")) { showUpdateDialog(); return true; }
        if (verb.equals("credits")) { doCredits(s); return true; }
        if (verb.equals("disableupdates")) { doOta(arg, s, true); return true; }
        if (verb.equals("enableupdates"))  { doOta(arg, s, false); return true; }
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
        if (verb.equals("adbsetup")) { doAdbSetup(arg, s); return true; }
        if (verb.equals("adbtcpip") || verb.equals("adbwireless")) { doAdbTcpip(arg, s); return true; }
        if (verb.equals("adbroot")) { doAdbRoot(s); return true; }
        if (verb.equals("termdev")) { doTermDev(arg, s); return true; }
        // no bare 'badapple'/'apple' verb: it shadowed the user's own scripts
        // and duplicated 'termdev badapple', which is where it lives now.
        if (verb.equals("playvideo")) { doPlayVideo(arg, s); return true; }
        if (verb.equals("curl")) { doCurl(arg, s); return true; }
        if (verb.equals("adbkillserver")) { doAdbServer(s, true); return true; }
        if (verb.equals("adbstartserver")) { doAdbServer(s, false); return true; }
        if (verb.equals("adbdev")) { doAdbDev(s); return true; }
        if (verb.equals("adbhint") || verb.equals("adbh")) { append(adbHelpText()); return true; }
        if (verb.equals("adbuse") || verb.equals("adbshizuku")) { doAdbUse(arg, s); return true; }
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
     * cd must be a builtin: every command runs in a fresh `sh -c`, so a `cd`
     * executed there moves that process's directory and dies with it. The
     * session cwd is the only directory that persists, and cd accepts the
     * drive-letter paths this prompt displays, since people then type them.
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
     * The apk travels on stdin ('apkinstall -r -g - some.apk') because pm
     * cannot read one off /storage/emulated/0 - the sdcard is FUSE and pm
     * streams the file through a pipe, which fails there.
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
     * 'su [command]': probe root, then run the rest of the line as root over the
     * root transport. The probe runs in this process rather than over the shell
     * transport: a su request made as uid 2000 is recorded by Magisk/KernelSU
     * against "shell", so root looked granted while the app's own commands got
     * nothing. Requested from the app's own uid it is recorded against this
     * package, which is the entry the user can see and revoke.
     */
    private void doSu(final String arg, final Session s) {
        startSuProbe(s, arg, 20000, true);
    }

    /**
     * 'askforsu': same probe on a long budget, since the manager is waiting for
     * an approval tap. A short timeout reports "timed out" for "not yet".
     */
    private void doAskSu(Session s) {
        startSuProbe(s, null, 60000, true);
    }

    private void startSuProbe(final Session s, final String arg,
                              final int timeoutMs, final boolean announce) {
        s.busy = true;
        String mgr = Su.managerFor(pget("suPath", Su.find()));
        final String[] cands = Su.candidates(mgr, pget("suPath", null));
        if (announce) {
            append("asking " + getPackageName() + " for root...\n");
            append("approve it in your root manager if a prompt appears.\n");
        }
        new Thread(new Runnable() {
            public void run() {
                final Su.Probe p = Su.probe(cands, timeoutMs, 4);
                post(new Runnable() {
                    public void run() { onSuProbe(p, arg); }
                });
            }
        }, "bbterm-suprobe").start();
    }

    private void onSuProbe(Su.Probe p, String arg) {
        for (Session s : sessions) s.busy = false;

        if (p.status == Su.GRANTED) {
            String mgr = Su.managerFor(p.path);
            append("root granted, uid 0\n");
            append("  su:      " + p.path + "\n");
            if (mgr != null) append("  manager: " + mgr + "\n");
            append("  grant is on " + getPackageName() + ", not on shell.\n");
            adoptRoot(p.path);
            if (arg != null && arg.length() > 0) sh.exec(arg);
            render();
            return;
        }

        if (!p.hadBinary) {
            append("no root on this device - no su binary anywhere.\n");
            append("not a problem. currently: " + transportLine() + "\n");
            append("  'adbsetup' gets a uid 2000 shell with no root at all,\n"
                 + "  and needs no root manager.\n");
            appendPrompt();
            return;
        }

        append("root found, not granted\n");
        append("  su:      " + p.path + "\n");
        String mgr = Su.managerFor(p.path);
        if (mgr != null) append("  manager: " + mgr + "\n");
        if (p.status == Su.TIMEOUT) {
            append("  it said nothing for " + (p.note) + ". it is most likely\n"
                 + "  holding a prompt open - look at the root manager.\n");
        } else {
            append("  it said: " + p.note + "\n");
            append("  approve " + getPackageName() + " in the superuser list,\n"
                 + "  then run su again.\n");
        }
        append("  nothing else changed; still " + transportLine() + ".\n");
        render();
    }

    /**
     * Everything the info panels want in one round trip, as key=value lines.
     * This used to be one probe() per value, but probe()'s callback is posted to
     * the UI thread the caller is already on, so nothing ever landed and every
     * value came back "?". All values are read from the device; none is baked in
     * for a particular headset or Android version.
     */
    private static final String FACTS_CMD =
              "echo model=$(getprop ro.product.model);"
            + " echo android=$(getprop ro.build.version.release);"
            + " echo api=$(getprop ro.build.version.sdk);"
+ " echo build=$(getprop ro.build.display.id);"
              + " echo fingerprint=$(getprop ro.build.fingerprint);"
              // firmware detail, so 'info' answers what version this is running
              + " echo incremental=$(getprop ro.build.version.incremental);"
              + " echo brand=$(getprop ro.product.brand);"
              + " echo manufacturer=$(getprop ro.product.manufacturer);"
              + " echo patch=$(getprop ro.build.version.security_patch);"
            + " echo serial=$(getprop ro.serialno);"
            + " echo uid=$(id -u);"
            + " echo toybox=$(command -v toybox 2>/dev/null || echo absent);"
            + " echo selinux=$(getenforce 2>/dev/null || echo unknown);"
            // Check every path the managers actually use: /product/bin/su alone
            // called rooted devices unrooted. 'exit' ends only the $( ) subshell,
            // so the first match wins and the fallback runs when none matched.
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
        final Transport t = sh;
        if (t == null) return;
        t.execRaw(FACTS_CMD, new Shell.Raw() {
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
             + "  transport  " + transportLine() + "\n"
             + "  uid now    " + (sh == null ? "none" : String.valueOf(sh.uid())) + "\n"
             + "  manager    " + f("mgr") + "\n"
             + "  su         " + f("su") + "\n"
             + "  su works   " + f("suworks") + "\n"
             + "  selinux    " + f("selinux") + "\n"
             + "\n"
             + "three ways to get a shell here, none of which needs the\n"
             + "others. 'adbsetup' is the one that works on an unrooted\n"
             + "headset with no Shizuku at all.\n"
             + "\n"
             + "su is invoked at each known path in turn, by absolute path,\n"
             + "not as a bare 'su': the binary itself is what makes the\n"
             + "manager show its prompt, and it is not always on $PATH.\n"
             + "/product/bin/su is the stock location on modern Android and\n"
             + "Magisk magic-mounts over it; KernelSU uses /debug_ramdisk/su.\n";
    }

    /**
     * Values come from the cached table; a refresh is kicked off after printing
     * so a second 'info' is always current.
     */
    private String infoText(Session s) {
        String out = "device\n"
             + "  headset      " + headset() + "\n"
             + "  codename     " + host() + "\n"
             + "  model        " + f("model") + "\n"
             + "  android      " + f("android") + " (api " + f("api") + ")\n"
             + "  build        " + f("build") + "\n"
             + "  serial       " + f("serial") + "\n"
             + "  transport    " + transportLine() + "\n"
             + "  uid here     " + f("uid") + "\n"
             + "  cwd          " + (s.cwd.isEmpty() ? "/" : s.cwd) + "\n"
             + "  toybox       " + f("toybox") + "\n"
             + "  selinux      " + f("selinux") + "\n"
             + "  su           " + f("su") + "\n"
             + "  adb client   " + (AdbBin.available(this) ? adbBanner() : "not in this build") + "\n"
             + "  adb device   " + pget("adbDev", "not paired") + "\n"
             + "  wireless dbg " + f("adbport") + "\n";
        loadFacts();
        return out;
    }

    /**
     * The pairing code and port come off the headset's own wireless-debugging
     * screen and cannot be read from here; the port adbd keeps afterwards can,
     * and that is the one worth remembering.
     */
    private String adbHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("wireless debugging\n\n");
        if (!AdbBin.available(this)) {
            sb.append("this build carries no adb client. nothing to pair with.\n");
            return sb.toString();
        }
        sb.append("adb client:  ").append(adbBanner()).append("\n");
        sb.append("key store:   ").append(AdbBin.homeDir(this)).append("/.android/adbkey\n\n");
        sb.append("one command does the whole flow:\n\n");
        sb.append("  adbsetup <pairport> <code> [port]\n\n");
        sb.append("the port and the 6-digit code are both on the headset's\n");
        sb.append("own Settings > Developer > Wireless debugging screen,\n");
        sb.append("under 'pair device with pairing code'. the pair port\n");
        sb.append("changes every time you ask for a new code.\n\n");
        sb.append("the first connect also makes the headset show its own\n");
        sb.append("'allow debugging' prompt for this app's key. accept it on\n");
        sb.append("the device; adbsetup waits there for a minute and a half.\n\n");
        sb.append("adbsetup pairs, connects, pins adbd to 5555 so the port\n");
        sb.append("stops moving every boot, and asks for root. on a retail\n");
        sb.append("build adbd stays uid 2000 and the shell is still usable.\n\n");
        sb.append("these keep things moving:\n\n");
        sb.append("  adbtcpip [port]           pin adbd to a fixed port\n");
        sb.append("  adbroot                   ask adbd to restart as root;\n");
        sb.append("                              works on userdebug/eng builds\n");
        sb.append("  adbdev                    show what the client sees\n");
        sb.append("  adbstartserver            adb start-server\n");
        sb.append("  adbkillserver             adb kill-server\n");
sb.append("  termdev                  info | log | savelog | server | selftest\n");
        sb.append("  adbuse                    go back to Shizuku\n\n");
        sb.append("current port: ").append(f("adbport")).append("\n");
        return sb.toString();
    }

    /**
     * pkg: prebuilt arm64 packages from the Termux repo. The generated scripts
     * live in Pkg; output arrives through the normal shell reply, so progress
     * shows as it happens rather than all at once.
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
            // Arg-less subcommands must be matched before the bare-word fallback
            // below, or 'pkg status' becomes 'pkg install status' and tries to
            // download a package called "status".
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

        // Background it: the transport buffers a whole command until the process
        // exits, so blocking left the input locked and the screen blank for the
        // minutes 17 packages take. The script goes up as a file and is started
        // with nohup; the user watches it with 'pkg status'.
        append("preparing " + name + ", progress prints below as it goes\n");
        s.busy = true;
        stageInstallScript(name, s);
    }

    /**
     * The script travels as a file rather than inline: ash writes here-documents
     * here-documents to a temp file under /data/local, which uid 2000 cannot
     * write to, so shipping one to the device fails for a reason that has
     * nothing to do with packages.
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
                        followPkg(s, name);
                    }
                });
            }
        }.start();
    }

    /**
     * run <script>: the path is resolved against the session directory and the
     * usual download folders, existence is checked so the error names the file,
     * and the interpreter is chosen by extension (a .ps1 used to be fed to sh,
     * even inside a live PowerShell session).
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
     * Open the real Android settings, not the Quest one: an implicit
     * android.settings.* intent is hijacked by
     * com.oculus.vrshell.intents.AndroidIntentsRelayActivity, which drops you in
     * Meta's VR settings shell with no developer pages. Naming the component
     * skips resolution entirely so vrshell never gets a look in. Class verified
     * on device: com.android.settings/.Settings$DevelopmentSettingsDashboardActivity
     */
    private void openAndroidSettings(String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } catch (Throwable t) {
            // Do NOT fall back to the implicit intent: on Quest it is claimed by
            // vrshell and lands in Meta's settings shell instead of Android's. A
            // wrong-but-silent screen is worse than an honest message.
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
        // Verified with `cmd package resolve-activity --brief -n ...` on device.
        // Naming components is required: implicit android.settings.* intents are
        // hijacked by vrshell. There is no wireless-debugging settings activity
        // on Quest, only a QS tile, so that one is not linked.
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
                    + "no Shizuku at all? 'adbsetup' gets a shell anyway.\n");
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
        sb.append("adbsetup <pairport> <code> pairs this app's own adb\n");
        sb.append("client, so everything runs on the headset, not a PC.\n");
        sb.append("both numbers come from the headset's own screen:\n\n");
        sb.append("  1. Quest: Settings > Developer > Wireless debugging\n");
        sb.append("     > Pair device with pairing code\n");
        sb.append("  2. type:  adbsetup <pair-port> <code>\n\n");
        sb.append("the pair port changes every time you ask for a new\n");
        sb.append("code. adbdev shows the live state.\n\n");
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
              "android",   "getprop ro.build.version.release",
              "sdk",       "getprop ro.build.version.sdk",
              "patch",     "getprop ro.build.version.security_patch",
              "build",     "getprop ro.build.display.id",
              "incremental", "getprop ro.build.version.incremental",
              "model",     "getprop ro.product.model",
              "brand",     "getprop ro.product.brand",
              "made by",   "getprop ro.product.manufacturer",
              "serial",    "getprop ro.serialno",
              "uid",       "id -u",
              "selinux",   "getenforce",
              "root",      "[ -e /product/bin/su ] && echo present || echo absent",
              "adb port",  "getprop persist.adb.tcp.port",
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
        return headset() + " - " + transportLine() + "\n"
             + "drive map   C: device root (\\" + host() + "\\)   D: /storage/emulated/0   E: tool prefix\n"
             + "privilege   $ = uid " + uid + " shell   # = uid 0 root\n"
             + "prompt      " + prompt() + "        ('prompt' cycles cmd / plain / unix)\n\n"
             + "getting a shell - any one of these is enough, none needs the others\n"
             + "  adbsetup <pairport> <code> [port]\n"
             + "                    the only adb command you need. pairs this app's\n"
             + "                    own adb client to this device and runs on it, so\n"
             + "                    it works on an unrooted headset with no Shizuku:\n"
             + "                    pair, connect, pin the port, ask for root.\n"
             + "                    both numbers are on the headset's own\n"
             + "                    Settings > Developer > Wireless debugging screen.\n"
             + "  adbtcpip [port]           pin adbd to a fixed port (5555)\n"
             + "  adbroot                   ask adbd to restart as root (userdebug)\n"
             + "  adbdev                    what the bundled client can see\n"
              + "  adbstartserver            adb start-server\n"
              + "  adbkillserver             adb kill-server\n"
+ "  termdev                  info | log | savelog | server | selftest\n"
              + "  termdev badapple         play a video over the app, not in the scrollback\n"
              + "  playvideo <file>          play any video on the headset, same overlay\n"
              + "  playvideo list            what videos it can see\n"
              + "  adbuse                    go back to Shizuku\n"
             + "  su [command]              root via the app's own su\n"
             + "  askforsu                  same, but waits 60s for the prompt\n"
             + "  checkshizuku              did shizuku say yes or no\n\n"
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
              + "network\n"
              + "  curl <url>        fetch an http(s) url in the app, with real TLS\n"
              + "                    (the device has no curl, and its wget does not\n"
              + "                    verify certificates). runs with no shell needed.\n"
              + "                    -o file, -O, -I, -i, -L, -X, -H, -d, -u, -k,\n"
              + "                    -A, -e, -b, -r, -x, -m all work. 'curl -h'\n"
              + "                    has the full list and examples\n\n"
              + "what is actually different on a Quest\n"
             + "  implicit android.settings.* intents never arrive - vrshell's\n"
             + "    AndroidIntentsRelayActivity swallows them, so every settings\n"
             + "    target here is named by component, not by action string.\n"
             + "  screencap returns a black frame, so verify UI through logcat:\n"
             + "    logcat -s BBterm, AndroidRuntime, System.err\n"
             + "  adb has to be paired before it is trusted, and /data/misc/adb/\n"
             + "    adb_keys is root-only, so a PC's key is paired over the WIFI\n"
             + "    TLS transport only. adbsetup does that pairing for this app's\n"
             + "    own bundled client, which is why the commands run on the\n"
             + "    headset instead of on a PC.\n"
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
             + "  applets (awk, sed, grep, tar, wget, hexdump ...) come from\n"
             + "  the device's own toybox in /system/bin, as typed\n\n"
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
