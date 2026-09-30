package com.BB465_stuff.Terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.util.Log;

/**
 * The adb client, running on the device as lib/arm64-v8a/libadb.so.
 *
 * Native libraries are the one executable thing an app can ship: the package
 * manager extracts them into nativeLibraryDir with the exec bit set, and that
 * directory is not app-writable, so the Android 10 write-execute restriction
 * never applies. Renaming adb to libadb.so is the whole trick.
 *
 * HOME points at a private directory so the generated key survives updates and
 * never touches ~/.android, which does not exist for an app.
 */
final class AdbBin {

    private static final String TAG = "BBterm";
    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final int LONG_TIMEOUT_MS = 120000;

    /**
     * Its own adb server, off the default 5037.
     *
     * adb connects to whatever already listens on the server port, so on a
     * device running another adb-using app this process inherits *that* app's
     * server and its transports. Observed on a Quest 3: the only server on 5037
     * belonged to another package, so 'adb kill-server' killed that app's server
     * instead of resetting this one, and 'already connected' kept answering
     * from a transport made before the key was authorised.
     *
     * -P on every call keeps the two apart, which makes kill-server mean
     * something. Costs one extra server process while the app is open.
     */
    static final int SERVER_PORT = 5038;

    private final String bin;
    private final String home;
    private final String device;

    private static String cachedBin;
    private static String cachedVersion;

    AdbBin(Context ctx, String device) {
        this.bin = binaryPath(ctx);
        this.home = homeDir(ctx);
        this.device = device;
    }

    // ---------------------------------------------------------------- discovery

    /** the extracted native library, or null if the APK did not carry one */
    static String binaryPath(Context ctx) {
        if (cachedBin != null) return cachedBin.length() == 0 ? null : cachedBin;
        try {
            String dir = ctx.getApplicationInfo().nativeLibraryDir;
            if (dir == null) { cachedBin = ""; return null; }
            File f = new File(dir, "libadb.so");
            if (!f.isFile()) { Log.i(TAG, "no libadb.so in " + dir); cachedBin = ""; return null; }
            if (!f.canExecute()) {
                Log.w(TAG, "libadb.so is not executable: " + f);
                cachedBin = ""; return null;
            }
            cachedBin = f.getAbsolutePath();
            return cachedBin;
        } catch (Throwable t) {
            Log.w(TAG, "binaryPath failed", t);
            cachedBin = "";
            return null;
        }
    }

    /** private key storage, so adb finds $HOME/.android/adbkey */
    static String homeDir(Context ctx) {
        try {
            return ctx.getDir("adb_keys", Context.MODE_PRIVATE).getAbsolutePath();
        } catch (Throwable t) {
            return ctx.getFilesDir().getAbsolutePath();
        }
    }

    static boolean available(Context ctx) {
        return binaryPath(ctx) != null;
    }

    /** run "adb version" once; the banner is how we know the ELF really is adb */
    static String version(Context ctx) {
        if (cachedVersion != null) return cachedVersion;
        String p = binaryPath(ctx);
        if (p == null) return cachedVersion = "absent";
        List<String> cmd = new ArrayList<String>();
        cmd.add(p);
        cmd.add("-P");
        cmd.add(String.valueOf(SERVER_PORT));
        cmd.add("version");
        Result r = exec(cmd, homeDir(ctx), ADB_USER, adbHostname(), DEFAULT_TIMEOUT_MS);
        String out = r.out;
        if (out.length() == 0) out = "not runnable: " + firstLine(r.err);
        cachedVersion = out;
        Log.i(TAG, "adb version -> " + out.replace('\n', ' '));
        return cachedVersion;
    }

    /**
     * Never spawns a process. version() forks adb and waits, so calling it from
     * the UI thread can ANR; anything that renders text uses this instead.
     */
    static String versionCached() {
        return cachedVersion;
    }

    /** fill the cache from a background thread, at most once per process */
    static void warmVersion(final Context ctx) {
        if (cachedVersion != null) return;
        Thread t = new Thread(new Runnable() {
            public void run() { version(ctx); }
        }, "bbterm-adbver");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- running

    static final class Result {
        String out = "";
        String err = "";
        int code = -1;
        boolean timedOut;
    }

    /**
     * Run a command and collect its output. Package-private so other in-APK
     * tools can reuse the pump-and-timeout logic instead of forking their own;
     * home may be null when the command has no use for it.
     */
    /**
     * The name this app announces when it pairs.
     *
     * adb builds it as USER + '@' + HOSTNAME, falling back to the logname and
     * then to net.hostname. net.hostname is empty on a Quest and is not
     * writable by a uid 2000 shell, so it falls back to a generated id and the
     * paired-devices list ends up reading something like
     * 'adb-<random>_localhost'. Setting both of these makes the entry read
     * 'Terminal@localhost-Quest3' instead.
     *
     * ADB_WIFI_HOSTNAME would do the same in one variable, but this build does
     * not have it - zero occurrences in the binary - so it is not an option.
     */
    static final String ADB_USER = "Terminal";

    /**
     * loopback, plus this headset's codename. Set once from the shell's
     * ro.product.device so the announced name follows the device rather than a
     * marketing string: 'eureka' is what identifies the hardware, 'Quest 3' is
     * what it is sold as and the same on several models.
     */
    static String codename = "";

    static String adbHostname() {
        String raw = codename.length() > 0 ? codename
                : (android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);
        StringBuilder sb = new StringBuilder("localhost");
        // spaces dropped: 'Quest 3' becomes 'Quest3'. A hostname with a space
        // in it is not a hostname.
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Result exec(List<String> cmd, String home, String user, String hostname, int timeoutMs) {
        Result r = new Result();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
if (home != null) {
                  pb.environment().put("HOME", home);
                  pb.environment().put("TMPDIR", home);
              }
              if (user != null) pb.environment().put("USER", user);
              if (hostname != null) pb.environment().put("HOSTNAME", hostname);
            p = pb.start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            // Drain on its own thread: reading inline blocks until EOF, so a
            // process that never exits would never reach waitFor() and the
            // timeout would be decoration.
            Thread pump = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), "UTF-8"), 8192);
                        char[] buf = new char[4096];
                        int n;
                        while ((n = br.read(buf)) > 0) synchronized (sb) { sb.append(buf, 0, n); }
                    } catch (Throwable ignored) { }
                }
            }, "bbterm-adbout");
            pump.setDaemon(true);
            pump.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                r.timedOut = true;
                r.err = "no answer in " + (timeoutMs / 1000) + "s";
                p.destroy();
                pump.join(500);
                synchronized (sb) { r.out = sb.toString(); }
                return r;
            }
            // give the pump a moment to drain what is left in the pipe
            pump.join(500);
            r.code = p.exitValue();
            synchronized (sb) { r.out = sb.toString(); }
        } catch (Throwable t) {
            r.err = t.toString();
            try { if (p != null) p.destroy(); } catch (Throwable ignored) { }
        }
        return r;
    }

    /** blocking adb invocation, output and diagnostics together */
public String run(String... args) {
          if (bin == null) return "adb: not in this build";
          List<String> cmd = new ArrayList<String>();
          cmd.add(bin);
          cmd.add("-P");
          cmd.add(String.valueOf(SERVER_PORT));
          for (String a : args) cmd.add(a);
        Result r = exec(cmd, home, ADB_USER, adbHostname(), isSlow(args) ? LONG_TIMEOUT_MS : DEFAULT_TIMEOUT_MS);
        StringBuilder sb = new StringBuilder(r.out);
        if (r.err.length() > 0) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append(r.err);
        }
        return sb.toString();
    }

    // Scan all args, not args[0]: the form is "adb -s dev tcpip 5555", so the
    // verb is not first and checking only the first gave every -s call the
    // short timeout.
    private static boolean isSlow(String[] args) {
        for (String a : args) {
            if (isSlow(a)) return true;
        }
        return false;
    }

    private static boolean isSlow(String verb) {
        return "pair".equals(verb) || "push".equals(verb) || "wait-for-device".equals(verb)
                || "reconnect".equals(verb) || "connect".equals(verb);
    }

private Result raw(int timeoutMs, String... args) {
          if (bin == null) { Result r = new Result(); r.err = "adb: not in this build"; return r; }
          List<String> cmd = new ArrayList<String>();
          cmd.add(bin);
          cmd.add("-P");
          cmd.add(String.valueOf(SERVER_PORT));
          for (String a : args) cmd.add(a);
        return exec(cmd, home, ADB_USER, adbHostname(), timeoutMs);
    }

    // ---------------------------------------------------------------- commands

    String devices() {
        return run("devices");
    }

    /** the device entry has to be "device", not "unauthorized" or "offline" */
    boolean isOnline() {
        String s = devices();
        for (String line : s.split("\n")) {
            if (line.startsWith(device) && line.trim().endsWith("device")) return true;
        }
        return false;
    }

    /**
     * adbd restarts when its port changes, so the transport is gone for a
     * moment after tcpip and the reconnect has to be polled rather than read
     * once.
     */
    boolean waitOnline(int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (isOnline()) return true;
            if (System.currentTimeMillis() >= end) return false;
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * A key adbd has never seen makes it raise the 'allow debugging' prompt, and
     * on a headset the user has to find a controller and aim at the panel before
     * they can accept. Poll for that, reporting the entry as it changes, rather
     * than declaring failure while the prompt is still on screen.
     */
    boolean authorizeAndWait(int timeoutMs, StringBuilder note) {
        String state = "";
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            String s = devices();
            for (String line : s.split("\n")) {
                if (!line.startsWith(device)) continue;
                String t = line.trim();
                if (t.endsWith("device")) return true;
                int sp = t.lastIndexOf('\t');
                if (sp >= 0) {
                    String now = t.substring(sp + 1);
                    if (!now.equals(state)) {
                        state = now;
                        note.append("  transport: ").append(now).append('\n');
                    }
                }
            }
            if (System.currentTimeMillis() >= end) return false;
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    String pair(int port, String code) {
        return run("pair", "127.0.0.1:" + port, code);
    }

    String connect(String hostPort) {
        return run("connect", hostPort);
    }

    String tcpip(int port) {
        return run("-s", device, "tcpip", String.valueOf(port));
    }

    /** adbd restarts as root on a userdebug/eng build; returns what it said */
    String adbRoot() {
        return run("-s", device, "root");
    }

    /** one remote command, passed as a single string so the far shell parses it */
    public String shell(String cmdline) {
        return run("-s", device, "shell", cmdline);
    }

    public Result shellResult(String cmdline) {
        return shellResult(cmdline, DEFAULT_TIMEOUT_MS);
    }

    /** long-running remote commands need their own budget, not adb's 20s */
    public Result shellResult(String cmdline, int timeoutMs) {
        return raw(timeoutMs, "-s", device, "shell", cmdline);
    }

    /** what uid the far side gives us, as a string; "" when unknown */
    public String uid() {
        Result r = shellResult("id -u");
        return firstLine(r.out.length() > 0 ? r.out : r.err);
    }

    public String push(String local, String remote) {
        return run("-s", device, "push", local, remote);
    }

    String device() {
        return device;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (t.length() > 0) return t;
        }
        return "";
    }
}
